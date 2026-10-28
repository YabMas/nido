(ns nido.coordinator.source.slack-events
  "The :slack-events source plugin. Holds a Slack Socket Mode connection and emits one event per
   newly owled thread, wherever the bot has been added — public channel, private channel, a
   top-level message or a reply. Its config names no channel: what it hears is decided by where
   a person has added the bot, and it never joins anything itself.

   The connection lives between polls, so `start-instance!` opens it and the socket's callback
   only queues what it delivered; `poll-once!` drains that queue on the daemon's tick — dropping
   what it has already emitted, resolving each reacted message to its whole thread — so the
   daemon still drives this source through its poll and stop pair alone.

   Identity is the reacted message (channel + ts), remembered in persisted state for a retention
   age, so a redelivered reaction or a second owl on one message emits nothing new. The event is
   keyed by the THREAD and carries :once-per-ref? true, so a thread nido already has a workstream
   for is never triaged again (spawn/ref-has-pending-run?).

   One connection per daemon: an owl must reach one project's triggers once, so the first
   instance to start owns the connection and a second opens none, saying so in its state.

   Reactions made while the connection is down are not recovered; the state says since when it
   has been down, which the coordinator's source health shows."
  (:require
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst]
   [nido.slack.client :as client]
   [nido.slack.socket :as socket]))

(def ^:private retention-s (* 30 24 60 60))

(defonce ^:private !owner
  ;; The config hash of the instance holding this daemon's connection, or nil.
  (atom nil))

(defn- seconds-between [from-iso to-iso]
  (try (.toSeconds (java.time.Duration/between (java.time.Instant/parse from-iso)
                                               (java.time.Instant/parse to-iso)))
       (catch Exception _ nil)))

(defn- seen-key [{:keys [channel ts]}] (str channel "|" ts))

(defn- prune [seen now]
  (into {} (filter (fn [[_ at]] (let [age (seconds-between at now)]
                                  (and age (< age retention-s)))))
        seen))

(defn- thread-of
  "The thread a reacted message belongs to, oldest first, or {:error kw}. A reply's ts answers
   only the reply from conversations.replies, so its :thread_ts is followed to the root."
  [{:keys [channel ts]} token]
  (let [r (client/conversations-replies channel ts token)]
    (if (:error r)
      r
      (let [root-ts (or (:thread_ts (first (:messages r))) ts)]
        (if (= root-ts ts)
          r
          (client/conversations-replies channel root-ts token))))))

(defn- with-names
  "Each message with the :user-name Slack shows for its author, looked up once per author."
  [messages token]
  (let [names (into {} (for [u (distinct (keep :user messages))]
                         [u (client/users-info u token)]))]
    (mapv #(cond-> % (get names (:user %)) (assoc :user-name (get names (:user %)))) messages)))

(defn ^{:malli/schema [:=> [:cat :map :any :any :any] :map]}
  poll-once!
  "One iteration: take the reactions the connection queued since the last poll, keep those of
   the configured emoji not already seen, resolve each to its thread and emit it; return the
   state to persist. A reaction whose thread cannot be read yet stays queued for the next poll.
   `inbox` is an atom holding {:reactions [...] :connection {...}}."
  [source-config inbox token emit-fn]
  (let [hash   (sources/config-hash source-config)
        prior  (sst/read-state hash)
        now    (clock/now-iso)
        emoji  (or (:emoji source-config) "owl")
        [{:keys [reactions connection]}] (swap-vals! inbox assoc :reactions [])
        seen0  (prune (or (:seen prior) {}) now)
        [seen retry]
        (reduce (fn [[seen retry] r]
                  (cond
                    (not= emoji (:reaction r))      [seen retry]
                    (contains? seen (seen-key r))   [seen retry]
                    :else
                    (let [thread (thread-of r token)]
                      (if (or (:error thread) (empty? (:messages thread)))
                        [seen (conj retry r)]
                        (let [msgs      (with-names (:messages thread) token)
                              root      (first msgs)
                              permalink (client/chat-permalink (:channel r) (:ts root) token)]
                          (emit-fn (assoc (client/normalise-thread (:channel r) msgs (:ts r) permalink)
                                          :once-per-ref? true))
                          [(assoc seen (seen-key r) now) retry])))))
                [seen0 []]
                reactions)]
    (when (seq retry) (swap! inbox update :reactions #(into (vec retry) %)))
    (cond-> (merge (dissoc prior :breaker :breaker-opened-at)
                   {:type           :slack-events
                    :source-config  source-config
                    :last-polled-at now
                    :seen           seen
                    :connection     connection})
      (= :refused (:state connection))
      (assoc :breaker :open :breaker-opened-at (or (:since connection) now)
             :last-poll-result {:error (or (:error connection) :refused)})
      (not= :refused (:state connection))
      (assoc :last-poll-result (if (seq retry) {:error :thread-unreadable :pending (count retry)} :ok)))))

(defn- status-fn
  "The on-status callback: records the connection's state, when it entered it, and when it was
   last open."
  [inbox]
  (fn [{:keys [state] :as status}]
    (let [now (clock/now-iso)]
      (swap! inbox update :connection
             (fn [c] (cond-> (merge (select-keys c [:last-connected-at])
                                    (dissoc status :state)
                                    {:state state :since now})
                       (= :open state) (assoc :last-connected-at now)))))))

(defn ^{:malli/schema [:=> [:cat :map :any :map] :map]}
  start-instance!
  "Open the connection — unless another instance in this daemon already holds it — and answer
   the poll and stop functions; stop closes it. Opts may carry :token and :app-token."
  [source-config emit-fn {:keys [token app-token] :as _opts}]
  (let [hash      (sources/config-hash source-config)
        token     (or token (client/keychain-token))
        app-token (or app-token (socket/app-token))
        inbox     (atom {:reactions [] :connection {:state :connecting :since (clock/now-iso)}})
        owner?    (compare-and-set! !owner nil hash)
        close     (cond
                    (not owner?)
                    (do (reset! inbox {:reactions []
                                       :connection {:state :refused :error :another-instance-connected
                                                    :since (clock/now-iso)}})
                        nil)
                    (nil? app-token)
                    (do (reset! inbox {:reactions []
                                       :connection {:state :refused :error :no-app-token
                                                    :since (clock/now-iso)}})
                        nil)
                    :else
                    (socket/connect! app-token
                                     (fn [event] (swap! inbox update :reactions conj event))
                                     (status-fn inbox)))
        emit      (fn [payload]
                    (emit-fn {:type :slack-events :source-config source-config :payload payload}))]
    {:poll! (fn [] (sst/write-state! hash (poll-once! source-config inbox token emit)))
     :stop! (fn []
              (when close (close))
              (compare-and-set! !owner hash nil))}))

(defn ^{:malli/schema [:=> [:cat] :any]}
  register! []
  (sources/register-source!
   {:type   :slack-events
    :schema [:map
             [:type    [:= :slack-events]]
             [:project keyword?]
             [:emoji   {:optional true} string?]
             [:poll    {:optional true} string?]]
    :events [:map [:source [:= :slack-events]] [:id string?]]
    :start! (fn [source-config emit-fn]
              (start-instance! source-config emit-fn {}))}))
