(ns nido.slack.client
  "Slack Web API client + macOS Keychain helpers for the bot token.
   Used by the :slack-channel source.

   Keychain entries are scoped per-user with service name 'nido-slack'.
   `sh!` is a redef seam so tests can stub `security` invocations.
   `http-request` is a redef seam so tests can stub HTTP calls.

   NOTE: Slack returns HTTP 200 even on logical errors, signalling failure via
   a JSON \"ok\": false field — so we inspect `ok`, not just the status."
  (:require
   [babashka.http-client :as http]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn ^{:malli/schema [:=> [:cat :any] :map]}
  sh!
  "Wrapped shell-out so tests can stub `security` calls."
  [args]
  (p/sh args))

(defn- whoami
  "Resolve the current username via `whoami`. Called at invocation time.
   Uses p/sh directly (not sh!) — not a security call, not a test seam."
  []
  (str/trim (:out (p/sh ["whoami"]))))

(defn ^{:malli/schema [:=> [:cat] [:maybe :SlackToken]]}
  keychain-token
  "Read the Slack bot token from the user's macOS Keychain. Returns the trimmed
   token string, or nil if the entry isn't present."
  []
  (let [{:keys [exit out]} (sh! ["security" "find-generic-password"
                                 "-s" "nido-slack" "-a" (whoami) "-w"])]
    (when (zero? exit) (str/trim out))))

(defn ^{:malli/schema [:=> [:cat :SlackToken] :any]}
  keychain-set!
  "Upsert the Slack bot token into the user's macOS Keychain. `-U` upserts."
  [token]
  (sh! ["security" "add-generic-password"
        "-s" "nido-slack" "-a" (whoami) "-U" "-w" token]))

(defn ^{:malli/schema [:=> [:cat :keyword :string :map] :map]}
  http-request
  "Wrapped HTTP call so tests can stub. Returns {:status :body}."
  [method url opts]
  (case method
    :get  (http/get  url (assoc opts :throw false))
    :post (http/post url (assoc opts :throw false))))

(defn- api-error
  "Map an HTTP response to a normalized error keyword, or nil if it is a Slack
   logical success (HTTP 200 + ok:true). Returns {:error :kw :detail str}."
  [{:keys [status body]}]
  (cond
    (= status 429) {:error :rate-limit}
    (>= (or status 0) 500) {:error :server}
    (zero? (or status 0)) {:error :network}
    (= status 200)
    (let [parsed (json/parse-string body true)]
      (if (:ok parsed)
        nil
        (let [e (:error parsed)]
          (if (#{"invalid_auth" "not_authed" "token_revoked" "account_inactive"} e)
            {:error :auth :detail e}
            {:error :api :detail e}))))
    :else {:error :api :detail (str "http-" status)}))

(defn ^{:malli/schema [:=> [:cat :string :SlackToken :map] :map]}
  conversations-history
  "GET conversations.history for `channel`. Options:
     :oldest    — only messages after this ts (exclusive via :inclusive false)
     :limit     — page size (default 200)
     :cursor    — pagination cursor
   Returns {:messages [...] :has_more bool :next_cursor str} on success,
   or {:error :auth|:server|:network|:rate-limit|:api :detail str}.
   Messages come back newest-first (Slack's order)."
  [channel token {:keys [oldest limit cursor]
                  :or   {limit 200}}]
  (let [params (cond-> {"channel" channel "limit" (str limit) "inclusive" "false"}
                 oldest (assoc "oldest" oldest)
                 cursor (assoc "cursor" cursor))
        resp   (try
                 (http-request
                   :get "https://slack.com/api/conversations.history"
                   {:headers      {"Authorization" (str "Bearer " token)}
                    :query-params params
                    :timeout      10000})
                 (catch Exception e {:status 0 :exception e}))]
    (if-let [err (api-error resp)]
      err
      (let [parsed (json/parse-string (:body resp) true)]
        {:messages    (:messages parsed)
         :has_more    (boolean (:has_more parsed))
         :next_cursor (get-in parsed [:response_metadata :next_cursor])}))))

(defn ^{:malli/schema [:=> [:cat :string :string :SlackToken] [:maybe :string]]}
  chat-permalink
  "GET chat.getPermalink for one message. Returns the permalink URL, or nil on
   any error (a missing permalink must not block emission)."
  [channel ts token]
  (let [resp (try
               (http-request
                 :get "https://slack.com/api/chat.getPermalink"
                 {:headers      {"Authorization" (str "Bearer " token)}
                  :query-params {"channel" channel "message_ts" ts}
                  :timeout      10000})
               (catch Exception e {:status 0 :exception e}))]
    (when-not (api-error resp)
      (:permalink (json/parse-string (:body resp) true)))))

(defn- post-json
  "POST a JSON body to a Slack Web API method; map to {:ok true ...} or {:error :kw}.
   `ok-extra` pulls extra keys off the parsed body into the success map."
  [url token body ok-extra]
  (let [resp (try
               (http-request :post url
                             {:headers {"Authorization" (str "Bearer " token)
                                        "Content-Type"  "application/json; charset=utf-8"}
                              :body    (json/generate-string body)
                              :timeout 10000})
               (catch Exception e {:status 0 :exception e}))]
    (if-let [err (api-error resp)]
      err
      (merge {:ok true} (ok-extra (json/parse-string (:body resp) true))))))

(defn ^{:malli/schema [:=> [:cat :string :SlackToken :map] :map]}
  post-message
  "POST chat.postMessage. `:thread-ts` (optional) threads the reply.
   Returns {:ok true :ts <new-ts>} or {:error :kw}."
  [channel token {:keys [text thread-ts]}]
  (post-json "https://slack.com/api/chat.postMessage" token
             (cond-> {:channel channel :text text}
               thread-ts (assoc :thread_ts thread-ts))
             (fn [parsed] {:ts (:ts parsed)})))

(defn ^{:malli/schema [:=> [:cat :string :string :SlackToken :string] :map]}
  add-reaction
  "POST reactions.add — best-effort emoji ack. Returns {:ok true} or {:error :kw}."
  [channel ts token emoji]
  (post-json "https://slack.com/api/reactions.add" token
             {:channel channel :timestamp ts :name emoji}
             (constantly {})))

(defn ^{:malli/schema [:=> [:cat :string :string] :string]}
  message-id
  "Stable, unique, filesystem-safe id for a channel message."
  [channel ts]
  (str "slack-" channel "-" ts))

(def ^:private id-pattern
  "A thread id is `slack-<channel>-<root ts>`; a ticket id scopes one by a key, `<thread id>~<key>`.
   Channel ids are upper-case alphanumerics, a ts is `<seconds>.<micros>`, a key lower-case
   alphanumerics and dashes — so nothing else can parse as either."
  #"^slack-([A-Z0-9]+)-(\d+\.\d+)(?:~([a-z0-9][a-z0-9-]*))?$")

(defn ^{:malli/schema [:=> [:cat :string :string] :string]}
  ticket-id
  "The id of the ticket proposed under `key` from the thread `thread-id`. Throws on a thread id
   or key that would not parse back, so a malformed id is never minted."
  [thread-id key]
  (let [id (str thread-id "~" key)
        p  (re-matches id-pattern id)]
    (when-not (and p (nil? (re-find #"~" thread-id)))
      (throw (ex-info "Not a thread id and ticket key" {:thread-id thread-id :key key})))
    id))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :map]]}
  parse-id
  "A thread id as {:channel :ts :thread-id}, a ticket id as the same plus :key — or nil for
   anything that is not exactly one of the two forms. The one place a Slack id is read."
  [id]
  (when-let [[_ channel ts key] (and (string? id) (re-matches id-pattern id))]
    (cond-> {:channel channel :ts ts :thread-id (message-id channel ts)}
      key (assoc :key key))))

(defn- get-json
  "GET a Slack Web API method; {:parsed body} on success, or {:error :kw}."
  [url token params]
  (let [resp (try
               (http-request :get url
                             {:headers      {"Authorization" (str "Bearer " token)}
                              :query-params params
                              :timeout      10000})
               (catch Exception e {:status 0 :exception e}))]
    (or (api-error resp) {:parsed (json/parse-string (:body resp) true)})))

(def ^:private max-reply-pages 10)

(defn ^{:malli/schema [:=> [:cat :string :string :SlackToken] :map]}
  conversations-replies
  "GET conversations.replies for the message `ts` in `channel`, every page up to a cap. Returns
   {:messages [...]} oldest first — the thread's root then its replies when `ts` is the root —
   or {:error :kw}."
  [channel ts token]
  (loop [cursor nil, acc [], pages 0]
    (let [{:keys [parsed] :as r} (get-json "https://slack.com/api/conversations.replies" token
                                           (cond-> {"channel" channel "ts" ts "limit" "200"}
                                             cursor (assoc "cursor" cursor)))]
      (if-not parsed
        r
        (let [acc'   (into acc (:messages parsed))
              cursor (get-in parsed [:response_metadata :next_cursor])]
          (if (and (:has_more parsed) (seq cursor) (< (inc pages) max-reply-pages))
            (recur cursor acc' (inc pages))
            {:messages acc'}))))))

(defn ^{:malli/schema [:=> [:cat :string :SlackToken] [:maybe :string]]}
  users-info
  "The display name Slack shows for `user` — its display name, else its real name — or nil
   when Slack answers neither or the call fails."
  [user token]
  (let [{:keys [parsed]} (get-json "https://slack.com/api/users.info" token {"user" user})
        profile          (get-in parsed [:user :profile])]
    (some #(when-not (str/blank? %) %)
          [(:display_name profile) (:real_name profile) (get-in parsed [:user :real_name])])))

(defn- truncate [s n]
  (if (> (count s) n) (subs s 0 n) s))

(defn ^{:malli/schema [:=> [:cat :string :map] :map]}
  normalise-message
  "Turn a raw Slack message + its permalink into the event-payload shape the
   spawn pipeline consumes. The full text is the triage brief; :title is a
   truncated display line."
  [channel {:keys [ts text user]} permalink]
  (let [text (or text "")]
    {:adapter :slack-message
     :id      (message-id channel ts)
     :ts      ts
     :channel channel
     :url     permalink
     :title   (truncate text 80)
     :text    text
     :user    user}))

(defn ^{:malli/schema [:=> [:cat :string [:vector :map] :string [:maybe :string]] :map]}
  normalise-thread
  "Turn a thread's messages (oldest first, each optionally carrying :user-name) into the event
   payload the spawn pipeline consumes: keyed by the thread, its text the transcript — root
   first, every reply after it, each under its author — and the reacted message's ts beside it."
  [channel messages reacted-ts permalink]
  (let [root       (first messages)
        root-text  (or (:text root) "")
        line       (fn [{:keys [user user-name text]}]
                     (str (or user-name user "unknown") ": " (or text "")))]
    {:adapter    :slack-message
     :id         (message-id channel (:ts root))
     :ts         (:ts root)
     :channel    channel
     :reacted-ts reacted-ts
     :url        permalink
     :title      (truncate root-text 80)
     :text       (str/join "\n\n" (map line messages))
     :user       (:user root)}))
