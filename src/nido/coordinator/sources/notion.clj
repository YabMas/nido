(ns nido.coordinator.sources.notion
  "The :notion-view source plugin. Polls a Notion database, diffs results
   against the per-source state snapshot, emits one event per new page.
   See spec §Source: :notion-view."
  (:require
   [clojure.set :as set]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.state :as sst]
   [nido.notion.client :as notion]))

(def ^:private failure-threshold 3)

(defn- poll-once!
  "One iteration of the polling loop for a given source-config.
   Pure of HTTP via the `query` and `emit` callbacks.
   Returns updated state.

   Breaker semantics:
   - 401 auth error  → open immediately (bad token won't self-heal)
   - other errors    → open after `failure-threshold` consecutive failures
   - 200 success     → clear counter + close breaker"
  [{:keys [source-config token query emit]} prior-state]
  (let [{:keys [database]} source-config
        {:keys [status results error]} (query database token)]
    (cond
      (= status 200)
      (let [pages         (mapv notion/normalise-page results)
            current-rows  (into #{} (map :page-id) pages)
            new-state     (-> prior-state
                              (assoc :type                 :notion-view
                                     :source-config        source-config
                                     :last-rows            current-rows
                                     :last-polled-at       (clock/now-iso)
                                     :last-poll-result     :ok
                                     :consecutive-failures 0)
                              (dissoc :breaker))]
        (when-let [prev (:last-rows prior-state)]
          (let [additions (set/difference current-rows prev)]
            (doseq [page pages
                    :when (contains? additions (:page-id page))]
              (emit page))))
        new-state)

      ;; Auth errors open immediately — a bad token won't get better.
      (= error :auth)
      (-> prior-state
          (assoc :type             :notion-view
                 :source-config    source-config
                 :last-polled-at   (clock/now-iso)
                 :last-poll-result {:error :auth :status status}
                 :breaker          :open)
          (update :consecutive-failures (fnil inc 0)))

      :else
      (let [next-failures ((fnil inc 0) (:consecutive-failures prior-state))]
        (cond-> prior-state
          true (assoc :type                 :notion-view
                      :source-config        source-config
                      :last-polled-at       (clock/now-iso)
                      :last-poll-result     {:error error :status status}
                      :consecutive-failures next-failures)
          (>= next-failures failure-threshold) (assoc :breaker :open))))))

(defn start-instance!
  "Start one source-instance. Returns {:poll! :stop!} per the source-plugin
   contract.

   `opts` (test-only):
     :token  — bypass keychain read (production: omit)
     :query  — fake query fn (production: omit; defaults to notion/database-query)"
  [source-config emit-fn {:keys [token query] :as _opts}]
  (let [hash    (sources/config-hash source-config)
        token   (or token (notion/keychain-token))
        query   (or query notion/database-query)
        emit    (fn [payload]
                  (emit-fn {:type          :notion-view
                            :source-config source-config
                            :payload       payload}))]
    {:poll! (fn []
              (let [prior (sst/read-state hash)]
                (when-not (= :open (:breaker prior))
                  (let [next (poll-once! {:source-config source-config
                                          :token         token
                                          :query         query
                                          :emit          emit}
                                         prior)]
                    (sst/write-state! hash next)))))
     :stop! (fn []
              nil)}))

(defn register! []
  (sources/register-source!
   {:type   :notion-view
    :schema [:map
             [:database string?]
             [:view     {:optional true} string?]
             [:poll     string?]]
    :events [:map [:source [:= :notion-view]] [:page-id string?]]
    :start! (fn [source-config emit-fn]
              (start-instance! source-config emit-fn {}))}))
