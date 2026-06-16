(ns nido.coordinator.sources.slack
  "The :slack-channel source plugin. Polls a Slack channel via
   conversations.history, watermarks on message ts, and emits one event per
   new top-level message.

   Cold start: the first poll seeds the watermark to the channel's newest ts
   and emits nothing, so turning the trigger on does not replay the backlog.
   Subsequent polls page (newest-first) until has_more is false, then emit
   qualifying messages oldest-first and advance the watermark to the max ts.

   See spec §Polling & identity mechanics."
  (:require
   [nido.coordinator.clock :as clock]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.state :as sst]
   [nido.slack.client :as client]))

(def ^:private failure-threshold 3)
(def ^:private breaker-cooldown-s (* 5 60))
(def ^:private default-subtypes-skip #{"channel_join" "channel_leave" "channel_topic"
                                       "channel_purpose" "channel_name" "channel_archive"})
(def ^:private max-pages 10)

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

(defn- ts> [a b]
  (and a (or (nil? b) (pos? (compare (bigdec a) (bigdec b))))))

(defn- qualifies?
  "A top-level user/bot message that is not a skipped system subtype."
  [skip {:keys [subtype ts]}]
  (and ts (not (contains? skip subtype))))

(defn- fetch-since
  "Page conversations.history (newest-first) collecting all messages after
   `oldest`. Returns {:messages [...]} (newest-first) or {:error :kw}."
  [channel token oldest]
  (loop [cursor nil, acc [], pages 0]
    (let [resp (client/conversations-history channel token
                                             (cond-> {} oldest (assoc :oldest oldest)
                                                        cursor (assoc :cursor cursor)))]
      (if (:error resp)
        resp
        (let [acc' (into acc (:messages resp))]
          (if (and (:has_more resp) (:next_cursor resp) (< (inc pages) max-pages))
            (recur (:next_cursor resp) acc' (inc pages))
            {:messages acc'}))))))

(defn poll-once!
  "One iteration for a source-config. Reads prior state, returns updated state
   (caller persists). source-config must have :project, :channel."
  [source-config token emit-fn]
  (let [hash        (sources/config-hash source-config)
        prior-state (sst/read-state hash)
        channel     (:channel source-config)
        skip        (or (:subtypes-skip source-config) default-subtypes-skip)
        base        {:type :slack-channel :source-config source-config
                     :last-polled-at (clock/now-iso)}]
    (if (and (= :open (:breaker prior-state)) (not (cooldown-elapsed? prior-state)))
      prior-state
      (let [resp (fetch-since channel token (:last-seen-ts prior-state))]
        (cond
          ;; success
          (not (:error resp))
          (let [watermark (:last-seen-ts prior-state)
                msgs (->> (:messages resp)
                          (filter #(qualifies? skip %))
                          ;; Exclude messages at or before the watermark: Slack's
                          ;; oldest param is exclusive but stubs (and edge cases)
                          ;; may still return the boundary message.
                          (filter #(ts> (:ts %) watermark))
                          (sort-by :ts #(compare (bigdec %1) (bigdec %2))))  ; oldest-first
                newest (reduce (fn [m {:keys [ts]}] (if (ts> ts m) ts m))
                               watermark
                               (:messages resp))]
            ;; Cold start (no prior watermark): seed, emit nothing.
            (when (some? watermark)
              (doseq [m msgs]
                (let [permalink (client/chat-permalink channel (:ts m) token)]
                  (emit-fn (client/normalise-message channel m permalink)))))
            (-> base
                (assoc :last-seen-ts        (or newest (:last-seen-ts prior-state) "0")
                       :last-poll-result    :ok
                       :consecutive-failures 0
                       :network-failures    0)
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

(defn start-instance!
  [source-config emit-fn {:keys [token] :as _opts}]
  (let [hash  (sources/config-hash source-config)
        token (or token (client/keychain-token))
        emit  (fn [payload]
                (emit-fn {:type :slack-channel :source-config source-config :payload payload}))]
    {:poll! (fn [] (sst/write-state! hash (poll-once! source-config token emit)))
     :stop! (fn [] nil)}))

(defn register! []
  (sources/register-source!
   {:type   :slack-channel
    :schema [:map
             [:type    [:= :slack-channel]]
             [:project keyword?]
             [:channel string?]
             [:poll    {:optional true} string?]
             [:subtypes-skip {:optional true} [:set string?]]]
    :events [:map [:source [:= :slack-channel]] [:id string?]]
    :start! (fn [source-config emit-fn]
              (start-instance! source-config emit-fn {}))}))
