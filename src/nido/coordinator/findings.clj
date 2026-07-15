(ns nido.coordinator.findings
  "Staging findings round: a human files findings after reviewing the deployed
   staging build; nido records the immutable :findings event on the workstream's
   active ledger, seeds a live resolution tracker, reopens the workstream to
   :in-progress, and provisions a fresh impl session off current main. Modeled on
   nido.coordinator.promote. See spec
   docs/superpowers/specs/2026-07-13-staging-findings-round-design.md."
  (:require
   [clojure.pprint :as pprint]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as ws]))

(defn- assign-ids
  "Fill any missing :id on items as f1, f2, … (1-based positional)."
  [items]
  (vec (map-indexed (fn [i it] (update it :id #(or % (str "f" (inc i))))) items)))

(defn- append-event!
  "Append the :findings event to the workstream's ledger (the one canonical store).
   Validation runs at the ledger boundary (report/entry-payload)."
  [project w round session]
  (let [content (with-out-str (pprint/pprint round))
        entry   (cond-> {:kind :findings} session (assoc :session session))]
    (ws/append-entry! project (:id w) entry content)))

(defn- notion-br
  "The workstream's Notion BR-#### id, or nil. Only Notion-backed workstreams can be
   auto-provisioned via the :plan-bug leg — its spawn path assumes a Notion ref, so a
   non-Notion ws would mint a phantom workstream."
  [w]
  (some #(when (= :notion (:adapter %)) (:id %)) (:external-refs w)))

(defn- enqueue-impl!
  "Enqueue a :plan-bug provisioning envelope so the daemon builds a fresh impl session
   off current main. Only for Notion-backed workstreams (see notion-br). Returns the
   envelope path, or nil when skipped (non-Notion ws — reopen + event + tracker still
   apply; a human resumes the work)."
  [project w]
  (when-let [br (notion-br w)]
    (cstate/ensure-dirs!)
    (let [m       (tickets/read-meta project br)
          payload (cond-> {:id br}
                    m (assoc :notion-page-id (:notion-page-id m)
                             :url            (:url m)
                             :title          (:title m)))]
      (queue/enqueue! {:target  {:project (keyword (name project)) :trigger :plan-bug}
                       :payload payload}))))

(defn file!
  "File a staging findings round on a shipped (settled) workstream. `opts` =
   {:items [{:summary :severity (:id) (:area)} …] :staging-ref? :note? :session?}.
   Appends the immutable :findings event, seeds the tracker, reopens to
   :in-progress, and enqueues a provisioning envelope. Returns {:round n :queued p}.
   Throws if the workstream is absent or not settled."
  [project ws-id {:keys [items staging-ref note session]}]
  (let [w (or (ws/read-ws project ws-id)
              (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))]
    (when-not (:closed w)
      (throw (ex-info "Can only file findings on a shipped (settled) workstream"
                      {:project project :ws-id ws-id :stage (:stage w)})))
    (let [round-n (inc (:round (:findings w) 0))
          items*  (assign-ids items)
          round   (cond-> {:format :findings :round round-n :items items*}
                    staging-ref (assoc :staging-ref staging-ref)
                    note        (assoc :note note))]
      (append-event! project w round session)
      (ws/set-findings! project ws-id {:round round-n
                                       :open (set (map :id items*))
                                       :resolved {}})
      (ws/reopen! project ws-id :in-progress)
      {:round round-n :queued (enqueue-impl! project w)})))

(defn resolve!
  "Mark findings items resolved by PR/commit `by`. Moves each known id from :open
   to :resolved {id {:by by :at …}}; unknown ids are ignored. Throws when there is
   no open findings tracker. Returns the updated tracker."
  [project ws-id item-ids by]
  (let [w (ws/read-ws project ws-id)
        t (:findings w)]
    (when-not t
      (throw (ex-info "No open findings round" {:project project :ws-id ws-id})))
    (let [at  (clock/now-iso)
          ids (filter (:open t) item-ids)
          t'  (-> t
                  (update :open #(reduce disj % ids))
                  (update :resolved merge
                          (into {} (map (fn [id] [id {:by by :at at}])) ids)))]
      (ws/set-findings! project ws-id t')
      t')))

(defn open-count
  "Count of unresolved finding ids on a workstream record (0 when no tracker)."
  [ws-record]
  (count (:open (:findings ws-record))))
