(ns nido.coordinator.lane.phase
  "Opening a phased workstream's next phase once its gate has been met.

   The same gesture as a findings round — a settled workstream put back to work —
   and for the same reason it reopens rather than mints: one design record governs
   every landing of one story. A gate is ASSERTED with its evidence, never checked,
   because nido observes no production; what a person looked at is written down so
   a reader can tell a number from a vibe.

   Starts no session. The reopened workstream owes the next phase's implementation,
   which the board shows and a person or the driver picks up."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.phase :as phase]
   [nido.coordinator.record.workstream :as ws]))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  advance!
  "Assert the current phase's gate with `(:evidence opts)` and open the next phase
   of the design whose plan waits on it (`ws/plan-design` — the one the close names).
   Returns {:opened <claim> :phase n :of m}.

   Throws ex-info, writing nothing, when the workstream is not settled between
   phases or the evidence is blank — and, through `record.workstream/open-phase!`,
   which asks again under the append lock, when the close names no phased design or
   the plan has no next phase."
  [project ws-id {:keys [evidence]}]
  (let [w      (or (ws/read-ws project ws-id)
                   (throw (ex-info "Workstream not found" {:project project :ws-id ws-id})))
        design (ws/plan-design project ws-id)
        p      (phase/progress design (:entries w))]
    (when-not (= :between-phases (get-in w [:closed :outcome]))
      (throw (ex-info (str ws-id " is not waiting on a gate — it is "
                           (if-let [o (get-in w [:closed :outcome])] (str "closed " (name o)) "open"))
                      {:ws-id ws-id :closed (:closed w)})))
    (when (str/blank? evidence)
      (throw (ex-info "A gate is asserted with its evidence — say what was observed"
                      {:ws-id ws-id})))
    (let [opens (:claim (:next p))]
      (ws/open-phase! project ws-id {:format   :phase-gate
                                     :design   {:seq (:seq design)}
                                     :opens    (or opens "")
                                     :evidence evidence})
      {:opened opens :phase (inc (:current p)) :of (:of p)})))
