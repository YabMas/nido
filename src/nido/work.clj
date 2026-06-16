(ns nido.work
  "The work-plane core: the single vocabulary every surface (TUI, web) wraps.

   Sits ABOVE the coordinator record layer (nido.coordinator.workstream/.session/
   .workstreams-view/.promote/.scratch/.tickets) and presents the ONE coherent
   model from docs/superpowers/specs/2026-06-16-coherent-workstream-core-and-thin-
   surfaces-design.md: a single stage spine (intake→triage→ready→in-progress→done),
   scratch folded in at :in-progress, and runs presented as autonomous sessions.
   Surfaces render + route; all model logic lives here. Ships as a projection over
   today's storage — no migration."
  (:require
   [nido.coordinator.session :as csession]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as cws]
   [nido.coordinator.workstreams-view :as wsv]))

(def stages
  "The canonical spine, in order. A PR merge is the event that advances
   in-progress→done; it is not a stage of its own."
  [:intake :triage :ready :in-progress :done])

(defn classify-origin
  "Origin of a workstream from its RAW record: :notion :github :slack :scratch.
   Delegates to the battle-tested source classifier (ref-less-but-autonomous
   workstreams are NOT scratch — scratch is keyed on the :scratch stage marker)."
  [ws]
  (wsv/ws-source ws))

(defn- to-spine
  "Project one wsv row onto the single spine: rename :source→:origin, fold a
   scratch workstream to :in-progress, and a settled (closed) one to :done."
  [row]
  (let [origin (:source row)
        stage  (cond
                 (= :settled (:engagement row)) :done
                 (= :scratch origin)            :in-progress
                 :else                          (:stage row))]
    (-> row
        (assoc :origin origin :stage stage)
        (dissoc :source))))

(defn list-workstreams
  "All of a project's workstreams as enriched rows on the single spine. `live-names`
   (optional set of session names actually holding ports) is threaded into the
   engagement projection — pass it so a downed one-off reads idle."
  ([project] (list-workstreams project nil))
  ([project live-names]
   (mapv to-spine (wsv/workstream-rows project live-names))))

(defn grouped
  "Workstreams grouped along the single spine for the board:
   {:triage {:in-flight [..] :queued [..]} :ready [..] :in-progress [..]}.
   Scratch one-offs fold into :in-progress (done via list-workstreams' remap);
   :done is omitted. The board renders these groups directly."
  ([project] (grouped project nil))
  ([project live-names]
   (wsv/grouped-by-stage (list-workstreams project live-names))))

(defn- session-status
  "Unified status across the autonomy axis: an autonomous session reports its
   burst phase; an interactive (human) session reads :up when live, :down when
   archived."
  [s]
  (if (:autonomy s)
    (get-in s [:autonomy :phase])
    (if (csession/live? s) :up :down)))

(defn- session-facet
  "One session on the autonomy axis."
  [s]
  (let [auto (:autonomy s)]
    {:name           (:name s)
     :autonomy-level (if auto :autonomous :interactive)
     :parked?        (csession/parked? s)
     :status         (session-status s)
     :brakes         (when auto (:limits auto))}))

(defn- ledger-summary
  "Light ledger facet for the detail view: its key (BR-#### / slack id), status,
   and report count. nil when `k` is nil (the workstream carries no ledger ref)."
  [project k]
  (when k
    (let [m (tickets/read-meta project k)]
      {:key          k
       :status       (:status m)
       :report-count (count (:entries m))})))

(defn workstream
  "Full detail for one workstream: origin, spine stage, label, a light ledger
   facet, and its sessions on the autonomy axis. nil when absent. Reads the
   workstream record once and projects it via wsv/workstream-row (no
   full-project scan), keeping w/row consistent."
  [project ws-id]
  (when-let [w (cws/read-ws project ws-id)]
    (let [sessions (csession/list-sessions project ws-id)
          row      (to-spine (wsv/workstream-row project w))]
      {:id       ws-id
       :project  project
       :origin   (classify-origin w)
       :stage    (:stage row)
       :label    (:label row)
       :ledger   (ledger-summary project (:br-id row))
       :sessions (mapv session-facet sessions)})))
