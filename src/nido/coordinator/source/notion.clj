(ns nido.coordinator.source.notion
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
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.source.notion-cache :as notion-cache]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst]
   [nido.notion.client :as notion]
   [nido.notion.views :as views]))

(def ^:private failure-threshold 3)

;; Half-open cooldown: once a breaker trips, suppress polling for this long,
;; then allow one probe poll. A successful probe closes the breaker; a failed
;; probe re-arms the cooldown. This makes manual `source:reset` unnecessary for
;; faults that eventually clear on their own (Notion 5xx, a refreshed token).
(def ^:private breaker-cooldown-s (* 5 60))

(defn- seconds-since
  "Whole seconds between an ISO-8601 instant string and now (via the clock
   seam). Returns nil if `iso` is nil/unparseable."
  [iso]
  (when iso
    (try
      (.toSeconds (java.time.Duration/between
                   (java.time.Instant/parse iso)
                   (java.time.Instant/parse (clock/now-iso))))
      (catch Exception _ nil))))

(defn- cooldown-elapsed?
  "True if a tripped breaker is due for a half-open probe. Permissive when no
   `:breaker-opened-at` is recorded (legacy state, or a manual edit) so the
   source still gets a probe rather than staying dark forever."
  [state]
  (if-let [since (seconds-since (:breaker-opened-at state))]
    (>= since breaker-cooldown-s)
    true))

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

(defn ^{:malli/schema [:=> [:cat :map :any :any] :map]}
  poll-once!
  "One iteration of the polling loop for a given source-config.
   Resolves :view via the views registry, queries the Notion data source,
   and calls emit-fn for each newly appeared page.

   source-config must have :project (keyword) and :view (keyword).
   Optional :additional-filter is merged with the view's filter.

   Reads prior state from disk; returns updated state (caller must persist).

   Breaker semantics:
   - 401 auth error  → open immediately (bad token won't self-heal)
   - 5xx / other API errors → open after `failure-threshold` consecutive failures
   - connectivity (`:network` / status 0) → never trips; transient by nature
     on a laptop that sleeps and roams. Tracked under `:network-failures` for
     visibility, but polling continues so the source recovers on its own.
   - 200 success     → clear counters + close breaker
   - half-open: a tripped breaker suppresses polling until `breaker-cooldown-s`
     has elapsed since `:breaker-opened-at`, then allows one probe poll."
  [source-config token emit-fn]
  (let [hash        (sources/config-hash source-config)
        prior-state (sst/read-state hash)]
    (if (and (= :open (:breaker prior-state))
             (not (cooldown-elapsed? prior-state)))
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
                                        :pages                (notion-cache/pages-snapshot pages)
                                        :last-polled-at       (clock/now-iso)
                                        :last-poll-result     :ok
                                        :consecutive-failures 0
                                        :network-failures     0)
                                 (dissoc :breaker :breaker-opened-at))]
            ;; Reconcile mode (:reconcile? true) re-emits ALL current members
            ;; every poll, so eligible/un-triaged tickets re-fire and failed
            ;; triages retry — the coordinator pre-spawn gate drops the ones
            ;; already triaged / dismissed / in-progress. Default mode keeps the
            ;; diff-only "emit once when a page first appears" behaviour (first
            ;; poll seeds and emits nothing).
            (let [emit? (if (:reconcile? source-config)
                          (constantly true)
                          (let [adds (when-let [prev (:last-rows prior-state)]
                                       (set/difference current-rows prev))]
                            (fn [page] (contains? (or adds #{}) (:page-id page)))))]
              (doseq [page pages
                      :when (emit? page)]
                (let [ev-priority (priority-from-page page priority-from)
                      payload     (cond-> page
                                    ev-priority (assoc :priority ev-priority))]
                  (emit-fn payload))))
            new-state)

          ;; Connectivity failures are transient — never trip the breaker, just
          ;; keep polling. If we got here on a half-open probe (breaker already
          ;; open from a real fault), re-arm the cooldown so we don't probe
          ;; every poll while the network is still down.
          (= error :network)
          (cond-> (-> prior-state
                      (assoc :type             :notion-view
                             :source-config    source-config
                             :last-polled-at   (clock/now-iso)
                             :last-poll-result {:error :network :status status})
                      (update :network-failures (fnil inc 0)))
            (= :open (:breaker prior-state)) (assoc :breaker-opened-at (clock/now-iso)))

          ;; Auth errors open immediately — a bad token won't get better.
          (= error :auth)
          (-> prior-state
              (assoc :type              :notion-view
                     :source-config     source-config
                     :last-polled-at    (clock/now-iso)
                     :last-poll-result  {:error :auth :status status}
                     :breaker           :open
                     :breaker-opened-at (clock/now-iso))
              (update :consecutive-failures (fnil inc 0)))

          :else
          (let [next-failures ((fnil inc 0) (:consecutive-failures prior-state))
                ;; trip if we've crossed the threshold, or re-arm if this was a
                ;; failed half-open probe (breaker was already open).
                tripped?      (or (= :open (:breaker prior-state))
                                  (>= next-failures failure-threshold))]
            (cond-> (assoc prior-state
                           :type                 :notion-view
                           :source-config        source-config
                           :last-polled-at       (clock/now-iso)
                           :last-poll-result     {:error error :status status}
                           :consecutive-failures next-failures)
              tripped? (assoc :breaker :open :breaker-opened-at (clock/now-iso)))))))))

(defn ^{:malli/schema [:=> [:cat :map :any :map] :map]}
  start-instance!
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

(defn ^{:malli/schema [:=> [:cat] :any]}
  register! []
  (sources/register-source!
   {:type   :notion-view
    :schema [:map
             [:type    [:= :notion-view]]
             [:project keyword?]
             [:view    keyword?]
             [:poll    {:optional true} string?]
             [:reconcile? {:optional true} boolean?]
             [:additional-filter {:optional true} [:map-of keyword? any?]]
             [:priority-from     {:optional true} [:map [:property string?]]]]
    :events [:map [:source [:= :notion-view]] [:page-id string?]]
    :start! (fn [source-config emit-fn]
              (start-instance! source-config emit-fn {}))}))
