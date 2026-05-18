(ns nido.coordinator.sources.notion
  "The :notion-view source plugin. Polls a Notion database via the views
   registry, diffs results against the per-source state snapshot, emits
   one event per new page.

   View resolution: :view (keyword) is looked up in the per-project
   notion-views.edn registry via nido.notion.views/resolve-view, which
   returns {:database <id> :filter <filter>}. An optional :additional-filter
   on the source-config is merged with the view filter via {:and [...]}.

   See spec §Source: :notion-view."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.sources.state :as sst]
   [nido.notion.client :as notion]
   [nido.notion.views :as views]))

(def ^:private failure-threshold 3)

(defn- priority-from-page
  "Given a normalised Notion page and a :priority-from config map, extract the
   numeric value or return nil. Defensive — accepts both the flattened
   top-level value (when normalise-page promoted it) and the raw formula
   map (in case the property hasn't been promoted)."
  [page priority-from]
  (when-let [prop-name (:property priority-from)]
    (let [;; Build the kebab-keyword the way client/normalise-page does.
          k (-> prop-name
                str/lower-case
                (str/replace #"[^a-z0-9]+" "-")
                (str/replace #"^-|-$" "")
                keyword)
          v (or (get page k)                              ;; promoted top-level
                (get-in page [:properties k]))]           ;; fallback
      (cond
        (number? v)                                (long v)
        (and (map? v) (number? (:number v)))       (long (:number v))
        (and (map? v) (-> v :formula :number number?))
        (long (-> v :formula :number))
        :else nil))))

(defn- merge-filters
  "Combine the view's filter with an optional :additional-filter into a
   single Notion filter object."
  [view-filter additional-filter]
  (cond
    (nil? additional-filter) view-filter
    (nil? view-filter)       additional-filter
    :else                    {:and [view-filter additional-filter]}))

(defn poll-once!
  "One iteration of the polling loop for a given source-config.
   Resolves :view via the views registry, queries the Notion data source,
   and calls emit-fn for each newly appeared page.

   source-config must have :project (keyword) and :view (keyword).
   Optional :additional-filter is merged with the view's filter.

   Reads prior state from disk; returns updated state (caller must persist).

   Breaker semantics:
   - 401 auth error  → open immediately (bad token won't self-heal)
   - other errors    → open after `failure-threshold` consecutive failures
   - 200 success     → clear counter + close breaker"
  [source-config token emit-fn]
  (let [hash        (sources/config-hash source-config)
        prior-state (sst/read-state hash)]
    (if (= :open (:breaker prior-state))
      prior-state
      (let [{:keys [project view additional-filter priority-from]} source-config
            {:keys [database filter]}  (views/resolve-view project view)
            combined-filter            (merge-filters filter additional-filter)
            ds-id                      (notion/resolve-data-source-id database token)
            {:keys [status results error]} (notion/data-source-query ds-id token
                                                                      {:filter combined-filter})]
        (cond
          (= status 200)
          (let [pages        (mapv notion/normalise-page results)
                current-rows (into #{} (map :page-id) pages)
                new-state    (-> prior-state
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
                  (let [ev-priority (priority-from-page page priority-from)
                        payload     (cond-> page
                                      ev-priority (assoc :priority ev-priority))]
                    (emit-fn payload)))))
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
              (>= next-failures failure-threshold) (assoc :breaker :open))))))))

(defn start-instance!
  "Start one source-instance. Returns {:poll! :stop!} per the source-plugin
   contract.

   `opts` (test-only):
     :token — bypass keychain read (production: omit)"
  [source-config emit-fn {:keys [token] :as _opts}]
  (let [hash   (sources/config-hash source-config)
        token  (or token (notion/keychain-token))
        emit   (fn [payload]
                 (emit-fn {:type          :notion-view
                           :source-config source-config
                           :payload       payload}))]
    {:poll! (fn []
              (let [next (poll-once! source-config token emit)]
                (sst/write-state! hash next)))
     :stop! (fn []
              nil)}))

(defn register! []
  (sources/register-source!
   {:type   :notion-view
    :schema [:map
             [:type    [:= :notion-view]]
             [:project keyword?]
             [:view    keyword?]
             [:poll    {:optional true} string?]
             [:additional-filter {:optional true} [:map-of keyword? any?]]
             [:priority-from     {:optional true} [:map [:property string?]]]]
    :events [:map [:source [:= :notion-view]] [:page-id string?]]
    :start! (fn [source-config emit-fn]
              (start-instance! source-config emit-fn {}))}))
