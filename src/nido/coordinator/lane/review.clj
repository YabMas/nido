(ns nido.coordinator.lane.review
  "Bridge between a ticket's triage and Run lifecycle state. A run's clean exit takes its state
   from what the ticket's ledger says it left (replacing the _run-status.edn path for triage),
   and a parked run whose ticket a person has since resolved is swept to terminal so it stops
   occupying its trigger's in-flight budget."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as cws]))

(defn ^{:malli/schema [:=> [:cat :keyword] [:maybe :keyword]]}
  run-state-from-ticket
  "Map a ticket's status to the Run state a clean agent exit implies:
     :awaiting-input  → :awaiting-review   (triage parked for human review)
     :planning        → :awaiting-review   (plan Run owns the ticket; parked)
     :triaged/:dismissed → :done           (resolved in-session)
     nil / anything else → :done           (cancelled/cleared → terminal)
   :investigating maps to :awaiting-review defensively (a clean exit that left
   the ticket :investigating shouldn't happen, but keeping it in the review
   queue beats silently dropping it; abnormal exits are handled as :failed
   elsewhere)."
  [ticket-status]
  (case ticket-status
    (:awaiting-input :investigating :planning) :awaiting-review
    :done))

(defn- in-review?
  "True while a parked run's ticket still occupies the person's review queue: it reads
   :investigating, :awaiting-input or :planning. A plan run is left out of its own ticket's
   status — open while parked, it would answer :planning for itself — so it stays only while a
   choice to plan the ticket holds, and is resolved once work is recorded past the plan or the
   ticket is dismissed. A triage run counts itself: parked with no verdict accepted, it is still
   waiting on its person. A run with no resolvable ticket, or one no workstream carries, is not in
   review, so the sweep resolves it."
  [{:keys [project skill] :as run}]
  (let [br (some-> run :event-payload :id)
        w  (when-not (str/blank? br) (cws/find-by-ref-id project br))]
    (contains? #{:investigating :awaiting-input :planning}
               (tickets/status-of project w (when (= :plan-bug skill) (:id run))))))

(defn ^{:malli/schema [:=> [:cat] :any]}
  sweep-resolved!
  "Transition every :awaiting-review triage or plan run whose ticket is no
   longer in review (its verdict accepted, or the ticket dismissed) → :done,
   freeing the trigger's in-flight budget AND tearing down the now-finished
   session — once the human has resolved the ticket the review surface is no
   longer needed. Returns the number transitioned."
  []
  (->> (runs/list-run-ids)
       (keep runs/read-run)
       (filter #(and (= :awaiting-review (:state %))
                     (#{:triage-bug :plan-bug :triage-slack} (:skill %))))
       (remove in-review?)
       (reduce (fn [n r]
                 (runs/transition! (:id r) :done)
                 (runs/teardown-session-for-run! r)
                 (inc n))
               0)))
