(ns nido.coordinator.lane.backfill
  "Record as entries the status a workstream's mutable records already hold, so a status read
   off the ledger alone answers for workstreams that were settled, staged, born a one-off,
   resolved or seen in Notion before those decisions were recorded.

   One-shot in purpose, idempotent in fact: every step compares what the record holds with what
   the ledger already says, and appends only the difference — so it is safe to run again, and a
   second run appends nothing."
  (:require
   [nido.coordinator.lane.pickup :as pickup]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.view.workstreams :as wsv]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.source.notion-cache :as notion-cache]))

(defn- newest [project ws-id kinds]
  (last (filter #(kinds (:kind %)) (:entries (ws/read-ws project ws-id)))))

(defn- append! [project ws-id r]
  (ws/append-entry! project ws-id {:kind (:format r)} (pr-str r))
  (:format r))

(defn- notion-page-id
  "The page of the workstream's Notion ref: its stored :page-id, else the one its :url names,
   else the cached page whose :br is the ref's id — a ref carries a :page-id only when the event
   that spawned it did."
  [w facts]
  (some (fn [r]
          (when (= :notion (:adapter r))
            (or (:page-id r)
                (pickup/extract-page-id (:url r))
                (when-let [br (:id r)]
                  (some (fn [[page-id f]] (when (= br (:br f)) page-id)) facts)))))
        (:external-refs w)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :int]]}
  unaccepted-triage
  "The :seq of the workstream's newest :triage when no :triage-accepted cites it, else nil."
  [project ws-id]
  (let [w      (ws/read-ws project ws-id)
        newest (:seq (last (filter #(= :triage (:kind %)) (:entries w))))]
    (when (and newest
               (not-any? #(= newest (:triage-seq %)) (ws/entries-of project ws-id :triage-accepted)))
      newest)))

(def ^:private taken
  "Ticket statuses that say a person took the triage verdict."
  #{:triaged :planning :implementing :done})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] [:vector :keyword]]}
  backfill-workstream!
  "Bring one workstream's ledger level with its records; returns the kinds appended, in order.
   `facts` is the project's Notion page-id → {:status …} cache.

   - its index rows kept beside their entries (`ws/freeze-index!`)
   - a :closed for a record settled with no close since its last reopen
   - a :scratch for a one-off still stored at :scratch with no birth entry
   - a :stage-set for an open record whose stored lifecycle stage its newest stage entry
     does not name
   - a :findings-resolved for the tracker's resolved items no entry of its round names
   - a :notion-status for a cached page status, when no Notion entry records one yet — the
     poller records every later change, and a cache read cannot say it is newer than an entry
   - a :triage-accepted for a verdict its ticket's status says was taken and no acceptance cites
   - a :closed :dismissed for an open record whose ticket alone was dismissed"
  [project ws-id facts]
  (ws/freeze-index! project ws-id)
  (let [w        (ws/read-ws project ws-id)
        id       ws-id
        appended (volatile! [])
        add!     #(vswap! appended conj (append! project id %))]
    (when-let [c (:closed w)]
      (when-not (= :closed (:kind (newest project id #{:closed :reopened})))
        (add! (cond-> {:format :closed :outcome (:outcome c) :by :backfill}
                (:design c) (assoc :design (:design c))))))
    (when (and (= :scratch (:stage w))
               (not-any? #(= :scratch (:kind %)) (:entries w)))
      (add! {:format :scratch}))
    (when (and (nil? (:closed w)) (contains? session/lifecycle-stages (:stage w)))
      (let [said (some->> (last (ws/entries-of project id :stage-set)) :stage)]
        (when-not (= said (:stage w))
          (add! {:format :stage-set :stage (:stage w) :by :backfill}))))
    (when-let [{:keys [round resolved]} (:findings w)]
      (let [named (into #{} (comp (filter #(= round (:round %))) (mapcat :items))
                        (ws/entries-of project id :findings-resolved))
            owed  (vec (sort (remove named (keys resolved))))]
        (when (and round (seq owed))
          (add! {:format :findings-resolved :round round :items owed :by "backfill"}))))
    (when-let [br (:id (wsv/ledger-ref w))]
      (let [st (tickets/status project br)]
        (when-let [n (and (taken st) (unaccepted-triage project id))]
          (add! {:format :triage-accepted :triage-seq n}))
        (when (and (= :dismissed st) (nil? (:closed (ws/read-ws project id)))
                   (not= :closed (:kind (newest project id #{:closed :reopened}))))
          (add! {:format :closed :outcome :dismissed :by :backfill}))))
    (when-let [page-id (notion-page-id w facts)]
      (when-let [status (get-in facts [page-id :status])]
        (when (empty? (ws/entries-of project id :notion-status))
          (add! {:format :notion-status :page-id page-id :status status :by :backfill}))))
    @appended))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :map]}
  backfill!
  "Run `backfill-workstream!` over every workstream of `project`. Returns
   {:workstreams <n> :appended {<kind> <count>} :failed [{:ws-id :error}]} — one workstream that
   cannot be read or written does not stop the rest."
  [project]
  (let [facts (notion-cache/project-page-facts project)]
    (reduce (fn [acc id]
              (try
                (-> acc
                    (update :workstreams inc)
                    (update :appended #(merge-with + % (frequencies (backfill-workstream! project id facts)))))
                (catch Exception e
                  (update acc :failed conj {:ws-id id :error (ex-message e)}))))
            {:workstreams 0 :appended {} :failed []}
            (ws/list-ids project))))
