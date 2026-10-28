(ns nido.slack.socket
  "Slack Socket Mode: an outbound websocket over which Slack pushes events, so nido hears a
   reaction anywhere the bot has been added without polling any channel.

   Hides the wire. `connect!` opens the connection with the app-level token (xapp-, a different
   credential from the bot token: it can open a connection and do nothing else), acks every
   envelope on receipt — an unacked envelope is redelivered — reopens with backoff when the
   connection drops or Slack asks for a refresh, and stops on an auth refusal rather than retry
   a token that will never work. Its caller sees reaction events and connection states only.

   `sh!`/`http-request` (from the client) and `open-websocket` are the redef seams."
  (:require
   [babashka.http-client.websocket :as ws]
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.slack.client :as client]))

(def ^:private keychain-service "nido-slack-app")

(defn- whoami []
  (str/trim (:out (p/sh ["whoami"]))))

(defn ^{:malli/schema [:=> [:cat] [:maybe :AppToken]]}
  app-token
  "The Slack app-level token from the user's macOS Keychain, or nil when it is not stored."
  []
  (let [{:keys [exit out]} (client/sh! ["security" "find-generic-password"
                                        "-s" keychain-service "-a" (whoami) "-w"])]
    (when (zero? exit)
      (let [t (str/trim out)] (when-not (str/blank? t) t)))))

(defn ^{:malli/schema [:=> [:cat :AppToken] :any]}
  app-token-set!
  "Upsert the Slack app-level token into the user's macOS Keychain."
  [token]
  (client/sh! ["security" "add-generic-password"
               "-s" keychain-service "-a" (whoami) "-U" "-w" token]))

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  normalise-envelope
  "One Socket Mode frame as {:ack <envelope id or nil> :event <reaction or nil> :control
   <:hello | :disconnect | nil>}. A kept event is a reaction added to a message:
   {:channel :ts :user :reaction}. A frame that does not parse answers all three nil."
  [frame]
  (let [m (try (json/parse-string frame true) (catch Exception _ nil))
        e (get-in m [:payload :event])]
    {:ack     (:envelope_id m)
     :control (case (:type m) "hello" :hello "disconnect" :disconnect nil)
     :event   (when (and (= "events_api" (:type m))
                         (= "reaction_added" (:type e))
                         (= "message" (get-in e [:item :type])))
                {:channel  (get-in e [:item :channel])
                 :ts       (get-in e [:item :ts])
                 :user     (:user e)
                 :reaction (:reaction e)})}))

(defn- open-url
  "POST apps.connections.open → {:url wss} or {:error :auth|:network|…}."
  [token]
  (let [resp (try (client/http-request :post "https://slack.com/api/apps.connections.open"
                                       {:headers {"Authorization" (str "Bearer " token)
                                                  "Content-Type" "application/x-www-form-urlencoded"}
                                        :timeout 10000})
                  (catch Exception _ {:status 0}))
        body (try (json/parse-string (:body resp) true) (catch Exception _ nil))]
    (cond
      (zero? (or (:status resp) 0))  {:error :network}
      (= 429 (:status resp))         {:error :rate-limit}
      (:ok body)                     {:url (:url body)}
      (#{"invalid_auth" "not_authed" "token_revoked" "account_inactive" "not_allowed_token_type"}
       (:error body))                {:error :auth :detail (:error body)}
      :else                          {:error :api :detail (or (:error body) (str "http-" (:status resp)))})))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  open-websocket
  "Open a websocket — the seam tests replace."
  [opts]
  (ws/websocket opts))

(def ^:private max-backoff-ms 60000)

(defn ^{:malli/schema [:=> [:cat :AppToken :any :any] :any]}
  connect!
  "Hold a Socket Mode connection on a background thread, calling `on-event` with each reaction
   event and `on-status` with every change of connection state ({:state :connecting | :open |
   :closed | :refused …}). Answers a no-argument function that closes it for good."
  [token on-event on-status]
  (let [stopped? (atom false)
        current  (atom nil)
        handle   (fn [sock frame]
                   (let [{:keys [ack event control]} (normalise-envelope frame)]
                     (when ack (ws/send! sock (json/generate-string {:envelope_id ack})))
                     (case control
                       :hello      (on-status {:state :open})
                       :disconnect (ws/close! sock)
                       nil)
                     (when event (on-event event))))
        worker   (future
                   (loop [backoff 1000]
                     (when-not @stopped?
                       (on-status {:state :connecting})
                       (let [{:keys [url error detail]} (open-url token)]
                         (if (= :auth error)
                           (on-status {:state :refused :error :auth :detail detail})
                           (let [done   (promise)
                                 buf    (StringBuilder.)
                                 opened (when url
                                          (try
                                            (open-websocket
                                             {:uri        url
                                              :on-message (fn [sock data last?]
                                                            (.append buf (str data))
                                                            (when last?
                                                              (let [frame (str buf)]
                                                                (.setLength buf 0)
                                                                (try (handle sock frame)
                                                                     (catch Throwable _ nil)))))
                                              :on-close   (fn [_ _ _] (deliver done :closed))
                                              :on-error   (fn [_ _] (deliver done :error))})
                                            (catch Throwable _ nil)))]
                             (when opened
                               (reset! current opened)
                               @done
                               (reset! current nil))
                             (on-status (cond-> {:state :closed}
                                          error (assoc :error error :detail detail)))
                             (when-not @stopped?
                               (Thread/sleep (long backoff))
                               ;; An opened connection is what proves the token and the network,
                               ;; so the next drop starts the backoff over.
                               (recur (if opened 1000 (min max-backoff-ms (* 2 backoff)))))))))))]
    (fn []
      (reset! stopped? true)
      (when-let [s @current] (try (ws/close! s) (catch Throwable _ nil)))
      (future-cancel worker))))
