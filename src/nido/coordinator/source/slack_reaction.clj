(ns nido.coordinator.source.slack-reaction
  "The :slack-reaction source plugin. Polls a Slack channel over a rolling
   window and scans each message's reactions for a configured emoji (default
   \"owl\"), emitting one event per newly-reacted message.

   Cold start: the first poll seeds the seen-set with the ts of every
   currently-reacted message in the window and emits nothing, so turning the
   trigger on does not replay pre-existing reactions. Subsequent polls emit
   only messages whose ts is not already in the seen-set. The seen-set is
   pruned each successful poll to ts >= oldest (window-bounded) — since ts
   only ages, a pruned message's ts can never re-enter the window and so can
   never re-fire.

   See spec §Polling & identity mechanics."
  (:require
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst]
   [nido.slack.client :as client]))

(def ^:private failure-threshold 3)
(def ^:private breaker-cooldown-s (* 5 60))
(def ^:private max-pages 10)
(def ^:private default-window-s (* 3 24 60 60))

(defn- seconds-since [iso]
  (when iso
    (try (.toSeconds (java.time.Duration/between
                       (java.time.Instant/parse iso)
                       (java.time.Instant/parse (clock/now-iso))))
         (catch Exception _ nil))))

(defn- cooldown-elapsed? [state]
  (if-let [since (seconds-since (:breaker-opened-at state))]
    (>= since breaker-cooldown-s)
    true))

(defn- window->seconds
  "Parse a window string like \"3d\"/\"12h\"/\"30m\" into seconds. Falls back
   to `default-window-s` when `s` is absent or doesn't match."
  [s]
  (or (when s
        (when-let [[_ n unit] (re-matches #"(\d+)([dhm])" s)]
          (let [n (Long/parseLong n)]
            (case unit
              "d" (* n 24 60 60)
              "h" (* n 60 60)
              "m" (* n 60)))))
      default-window-s))

(defn- oldest-ts
  "The Slack ts string for now - window-s."
  [window-s]
  (let [now-epoch (.getEpochSecond (java.time.Instant/parse (clock/now-iso)))]
    (format "%d.000000" (- now-epoch window-s))))

(defn- fetch-window
  "Page conversations.history (newest-first) collecting all messages at/after
   `oldest`. Returns {:messages [...]} (newest-first) or {:error :kw}."
  [channel token oldest]
  (loop [cursor nil, acc [], pages 0]
    (let [resp (client/conversations-history channel token
                                             (cond-> {:oldest oldest}
                                               cursor (assoc :cursor cursor)))]
      (if (:error resp)
        resp
        (let [acc' (into acc (:messages resp))]
          (if (and (:has_more resp) (:next_cursor resp) (< (inc pages) max-pages))
            (recur (:next_cursor resp) acc' (inc pages))
            {:messages acc'}))))))

(defn- ts>=oldest? [ts oldest]
  (not (neg? (compare (bigdec ts) (bigdec oldest)))))

(defn ^{:malli/schema [:=> [:cat :map :any :any] :map]}
  poll-once!
  "One iteration for a source-config. Reads prior state, returns updated
   state (caller persists). source-config must have :project, :channel."
  [source-config token emit-fn]
  (let [hash        (sources/config-hash source-config)
        prior-state (sst/read-state hash)
        channel     (:channel source-config)
        emoji       (or (:emoji source-config) "owl")
        window-s    (window->seconds (:window source-config))
        oldest      (oldest-ts window-s)
        base        {:type :slack-reaction :source-config source-config
                     :last-polled-at (clock/now-iso)}]
    (if (and (= :open (:breaker prior-state)) (not (cooldown-elapsed? prior-state)))
      prior-state
      (let [resp (fetch-window channel token oldest)]
        (cond
          ;; success
          (not (:error resp))
          (let [seen     (or (:seen prior-state) #{})
                pruned   (into #{} (filter #(ts>=oldest? % oldest) seen))
                owled    (filter #(client/owl-reacted? % emoji) (:messages resp))
                cold?    (not (:cold-started? prior-state))
                new-owls (if cold? '() (remove #(contains? pruned (:ts %)) owled))]
            (when-not cold?
              (doseq [m new-owls]
                (let [permalink (client/chat-permalink channel (:ts m) token)]
                  (emit-fn (client/normalise-message channel m permalink)))))
            (-> base
                (assoc :seen (into pruned (map :ts (if cold? owled new-owls)))
                       :cold-started?        true
                       :last-poll-result     :ok
                       :consecutive-failures 0
                       :network-failures     0)
                (dissoc :breaker :breaker-opened-at)))

          ;; transient: rate-limit / network -- never trip
          (#{:rate-limit :network} (:error resp))
          (cond-> (-> (merge prior-state base)
                      (assoc :last-poll-result {:error (:error resp)})
                      (update :network-failures (fnil inc 0)))
            (= :open (:breaker prior-state)) (assoc :breaker-opened-at (clock/now-iso)))

          ;; auth: open immediately
          (= :auth (:error resp))
          (-> (merge prior-state base)
              (assoc :last-poll-result {:error :auth}
                     :breaker :open :breaker-opened-at (clock/now-iso))
              (update :consecutive-failures (fnil inc 0)))

          ;; other (server / api): trip after threshold
          :else
          (let [next-failures ((fnil inc 0) (:consecutive-failures prior-state))
                tripped?      (or (= :open (:breaker prior-state))
                                  (>= next-failures failure-threshold))]
            (cond-> (-> (merge prior-state base)
                        (assoc :last-poll-result {:error (:error resp) :detail (:detail resp)}
                               :consecutive-failures next-failures))
              tripped? (assoc :breaker :open :breaker-opened-at (clock/now-iso)))))))))

(defn ^{:malli/schema [:=> [:cat :map :any :map] :map]}
  start-instance!
  [source-config emit-fn {:keys [token] :as _opts}]
  (let [hash  (sources/config-hash source-config)
        token (or token (client/keychain-token))
        emit  (fn [payload]
                (emit-fn {:type :slack-reaction :source-config source-config :payload payload}))]
    {:poll! (fn [] (sst/write-state! hash (poll-once! source-config token emit)))
     :stop! (fn [] nil)}))

(defn ^{:malli/schema [:=> [:cat] :any]}
  register! []
  (sources/register-source!
   {:type   :slack-reaction
    :schema [:map
             [:type    [:= :slack-reaction]]
             [:project keyword?]
             [:channel string?]
             [:emoji   {:optional true} string?]
             [:window  {:optional true} string?]
             [:poll    {:optional true} string?]]
    :events [:map [:source [:= :slack-reaction]] [:id string?]]
    :start! (fn [source-config emit-fn]
              (start-instance! source-config emit-fn {}))}))
