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
   [nido.config :as config]
   [nido.coordinator.promote :as promote]
   [nido.coordinator.scratch :as scratch]
   [nido.coordinator.session :as csession]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as cws]
   [nido.coordinator.workstreams-view :as wsv]
   [nido.session.lifecycle :as lifecycle]))

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

(def ^:private canonical-default-target
  "Fallback when a project hasn't configured a default for the action."
  {:promote :in-progress :new :in-progress})

(defn- project-entry
  "projects.edn entry for `project`, tolerating symbol / keyword / string keys."
  [projects project]
  (or (get projects project)
      (get projects (symbol (name project)))
      (get projects (keyword (name project)))
      (get projects (name project))))

(defn default-target
  "Default target stage for a `new`/`promote` gesture in `project`. `action` is
   :promote or :new. A value configured under the project's :workstream-defaults
   is honored only when it names a spine stage; otherwise the canonical default."
  [project action]
  (let [configured (get-in (project-entry (config/read-projects) project)
                           [:workstream-defaults action])]
    (if (some #{configured} stages)
      configured
      (canonical-default-target action))))

(defn set-stage!
  "Move a workstream to `target` stage — the single mutation behind the
   new/promote/done surface verbs. Dispatch:
     :in-progress → the full promote gesture (gate + provision the planning leg)
     :done        → close the workstream (:done outcome)
     other        → advance the stored stage only (no autonomous leg)
   Returns {:decision <kw>}: promote's decision verbatim, else :done / :advanced."
  [project ws-id target]
  (case target
    :in-progress (promote/promote-workstream! project ws-id)
    :done        (do (cws/close! project ws-id :done) {:decision :done})
    (do (cws/advance-stage! project ws-id target) {:decision :advanced})))

(defn new!
  "Birth a scratch workstream and bring its session up. Mirrors the proven add
   path: lifecycle/up! creates the worktree + services (it is heavy and slow —
   surfaces wrap this in their own progress UI); scratch/birth! births the
   ref-less workstream + human session. Idempotent on an existing session name.
   Returns the ws-id. `project` may be a keyword or string."
  [project session-name]
  (lifecycle/up! session-name {:project (name project)})
  (scratch/birth! (keyword (name project)) session-name))

(defn open-target
  "Where `open` lands for a workstream: the most-recently-active LIVE session,
   else the most-recently-active session, else nil. Returns {:project :session}.
   Ordering reuses wsv/session-rows (newest-active first)."
  [project ws-id]
  (let [rows       (wsv/session-rows project ws-id)
        live-names (->> (csession/list-sessions project ws-id)
                        (filter csession/live?)
                        (map :name)
                        set)
        pick       (or (first (filter #(live-names (:name %)) rows))
                       (first rows))]
    (when pick {:project project :session (:name pick)})))
