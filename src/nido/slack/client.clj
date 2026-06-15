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

(defn sh!
  "Wrapped shell-out so tests can stub `security` calls."
  [args]
  (p/sh args))

(defn- whoami
  "Resolve the current username via `whoami`. Called at invocation time.
   Uses p/sh directly (not sh!) — not a security call, not a test seam."
  []
  (str/trim (:out (p/sh ["whoami"]))))

(defn keychain-token
  "Read the Slack bot token from the user's macOS Keychain. Returns the trimmed
   token string, or nil if the entry isn't present."
  []
  (let [{:keys [exit out]} (sh! ["security" "find-generic-password"
                                 "-s" "nido-slack" "-a" (whoami) "-w"])]
    (when (zero? exit) (str/trim out))))

(defn keychain-set!
  "Upsert the Slack bot token into the user's macOS Keychain. `-U` upserts."
  [token]
  (sh! ["security" "add-generic-password"
        "-s" "nido-slack" "-a" (whoami) "-U" "-w" token]))

(defn http-request
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

(defn conversations-history
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

(defn chat-permalink
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

(defn message-id
  "Stable, unique, filesystem-safe id for a channel message."
  [channel ts]
  (str "slack-" channel "-" ts))

(defn- truncate [s n]
  (if (> (count s) n) (subs s 0 n) s))

(defn normalise-message
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
