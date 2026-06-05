(ns nido.coordinator.review
  "Bridge between the ticket record (skill-maintained triage state) and Run
   lifecycle state. The skill writes ticket status across the resume boundary;
   the coordinator derives Run state from it (replacing the _run-status.edn
   path for triage) and sweeps resolved parked runs to terminal so they stop
   occupying their trigger's in-flight budget."
  (:require
   [nido.coordinator.runs :as runs]
   [nido.coordinator.tickets :as tickets]))

(defn run-state-from-ticket
  "Map a ticket's status to the Run state a clean agent exit implies:
     :awaiting-input  → :awaiting-review   (triage parked for human review)
     :planning        → :awaiting-review   (plan Run owns the ticket; parked)
     :triaged/:skipped → :done             (resolved in-session)
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
  "True while a ticket still occupies the human's review queue (triage or plan).
   A run with no resolvable BR-#### (nil br-id) can't map to a ticket, so it is
   treated as not-in-review — the sweep then resolves it to :done."
  [project br-id]
  (and (some? br-id)
       (contains? #{:investigating :awaiting-input :planning}
                  (tickets/status project br-id))))

(defn sweep-resolved!
  "Transition every :awaiting-review triage or plan run whose ticket is no
   longer in review (resolved via apply/skip, or cleared via cancel) → :done,
   freeing the trigger's in-flight budget. Returns the number transitioned."
  []
  (->> (runs/list-run-ids)
       (keep runs/read-run)
       (filter #(and (= :awaiting-review (:state %))
                     (#{:triage-bug :plan-bug} (:skill %))))
       (remove #(in-review? (:project %) (some-> % :event-payload :id)))
       (reduce (fn [n r] (runs/transition! (:id r) :done) (inc n)) 0)))
