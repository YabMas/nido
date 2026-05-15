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

(defn- poll-once!
  "One iteration of the polling loop for a given source-config.
   Pure of HTTP via the `query` and `emit` callbacks.
   Returns updated state."
  [{:keys [source-config token query emit]} prior-state]
  (let [{:keys [database]} source-config
        {:keys [status results error]} (query database token)]
    (cond
      (= status 200)
      (let [pages        (mapv notion/normalise-page results)
            current-rows (into #{} (map :page-id) pages)
            new-state    (assoc prior-state
                                :type                 :notion-view
                                :source-config        source-config
                                :last-rows            current-rows
                                :last-polled-at       (clock/now-iso)
                                :last-poll-result     :ok
                                :consecutive-failures 0)]
        (when-let [prev (:last-rows prior-state)]
          (let [additions (set/difference current-rows prev)]
            (doseq [page pages
                    :when (contains? additions (:page-id page))]
              (emit page))))
        new-state)

      :else
      (-> prior-state
          (assoc :type             :notion-view
                 :source-config    source-config
                 :last-polled-at   (clock/now-iso)
                 :last-poll-result {:error error :status status})
          (update :consecutive-failures (fnil inc 0))))))

(defn start-instance!
  "Start one source-instance. Returns {:poll! :stop!} per the source-plugin
   contract.

   `opts` (test-only):
     :token  — bypass keychain read (production: omit)
     :query  — fake query fn (production: omit; defaults to notion/database-query)"
  [source-config emit-fn {:keys [token query] :as _opts}]
  (let [hash    (sources/config-hash source-config)
        token   (or token (notion/keychain-token))
        query   (or query notion/database-query)]
    {:poll! (fn []
              (let [prior (sst/read-state hash)
                    next  (poll-once! {:source-config source-config
                                       :token         token
                                       :query         query
                                       :emit          emit-fn}
                                      prior)]
                (sst/write-state! hash next)))
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
