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
   [babashka.fs :as fs]
   [clojure.set :as set]
   [clojure.string :as str]
   [nido.config :as config]
   [nido.coordinator.facets :as facets]
   [nido.coordinator.notion-cache :as notion-cache]
   [nido.coordinator.promote :as promote]
   [nido.coordinator.report :as report]
   [nido.coordinator.resume :as resume]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.scratch :as scratch]
   [nido.coordinator.session :as csession]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.io :as io]
   [nido.coordinator.workstream :as cws]
   [nido.coordinator.workstreams-view :as wsv]
   [nido.notion.client :as notion]
   [nido.notion.views :as views]
   [nido.slack.client :as slack]
   [nido.process :as proc]
   [nido.project :as project]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as sstate]))

(def stages
  "The canonical spine, in order. A PR merge is the event that advances
   :shipping → :done; :shipping is the merge-pipeline stage entered by `nido ship`."
  [:intake :triage :ready :in-progress :shipping :done])

(defn tab-bands
  "Ordered [stage rows] pairs for `tab` out of a `grouped` map, empty bands
   dropped. The ONE place the band→tab mapping lives, so no surface can disagree
   about where a band belongs.

     :intake — :triage (in-flight then queued) + :incoming   — work arriving via
               the various streams, awaiting a verdict.
     :active — :shipping + :in-progress (most-advanced first) — work nido is
               driving.

   These are nido's two jobs. The backlog (:ready) and the archive (:done) live
   in Notion and are never emitted by grouped-by-stage, so they are not bands
   here. :dismissed IS a band — the nido-side veto has no Notion archive to
   fall into, so hiding it would be the silent loss this guarantee exists to
   prevent. It trails :intake exactly as :winding-down trails :active. Their
   union is every row `grouped-rows` emits — a workstream is always reachable
   from at least one tab, which is the guarantee that nothing can be hidden by
   default (a source filter defaulting to :notion once hid every :in-progress
   row). Exactly one, with one transient exception: a dismissed workstream still
   holding a live session is BOTH :dismissed (projected from its row) and
   :winding-down (computed from raw records), until the daemon's sweep tears the
   session down. Double-reachable is the harmless direction — both bands' actions
   are sane — and it self-heals. An unrecognized `tab` reads as :intake.

   :active's trailing band is :winding-down — closed workstreams still holding
   live resources (bring-down! is their one action)."
  [tab grouped]
  (->> (case tab
         :active [[:shipping     (:shipping grouped)]
                  [:in-progress  (:in-progress grouped)]
                  [:winding-down (:winding-down grouped)]]
         [[:triage   (concat (-> grouped :triage :in-flight)
                             (-> grouped :triage :queued))]
          [:incoming (:incoming grouped)]
          [:dismissed (:dismissed grouped)]])
       (into [] (keep (fn [[stage rows]] (when (seq rows) [stage (vec rows)]))))))

(defn gate-actions
  "Follow-actions for a gate, derived from its spine `stage` and whether a session is
   `parked?`. Each is a descriptor {:id :label :kind :style (:input)}:
     :kind :mutation -> one-click button, resolved nido-side (resolve-gate! on :id).
     :kind :resume   -> resume the parked agent. With :input it renders a one-click
                        button carrying that canned input (e.g. Apply -> \"apply\");
                        without :input it renders the free-text reply textarea.
   :style is a render hint (:primary | :danger | :default).

   `origin` is accepted and ignored — it once fenced Dismiss off Notion triage rows
   (2026-07-17-triage-routing-model-design.md §5, on the grounds that a local dismiss
   was a no-op there). It is no longer: to-spine's :dismissed stamp makes the veto
   stick, and the Dismissed band + Restore make it reversible instead of silent.
   The arg stays for call-site compatibility."
  ([stage parked?] (gate-actions stage parked? nil))
  ([stage parked? _origin]
   (case stage
     :incoming    [{:id :promote :label "Promote" :kind :mutation :style :primary}
                   {:id :drop    :label "Dismiss" :kind :mutation :style :danger}]
     :triage      (let [dismiss {:id :dismiss :label "Dismiss" :kind :mutation :style :danger}]
                    ;; Apply executes the routed verdict to Notion nido-side (Ball Holder +
                    ;; App Domain, deep properties/callout — apply-routed!, no conversation),
                    ;; falling back to nido-only ticket:complete for legacy/Slack reports;
                    ;; Reply (free-text overrides/redo) resumes the agent; Dismiss takes it
                    ;; off the radar nido-side, writing nothing to Notion.
                    (if parked?
                      [{:id :apply :label "Apply" :kind :mutation :style :primary}
                       dismiss
                       {:id :reply :label "Reply" :kind :resume :style :default}]
                      [dismiss]))
     ;; The nido-side veto, reversible: Restore clears the ticket status so the row
     ;; rejoins the triage queue and the auto-triage gate can pick it up again.
     :dismissed   [{:id :restore :label "Restore" :kind :mutation :style :default}]
     :ready       [{:id :promote :label "Promote" :kind :mutation :style :primary}
                   {:id :drop    :label "Drop"    :kind :mutation :style :danger}]
     :in-progress (if parked?
                    [{:id :reply :label "Reply" :kind :resume :style :default}
                     {:id :done  :label "Done"  :kind :mutation :style :primary}]
                    [])
     :shipping    (if parked?
                    ;; Blocked in the merge lane: Reply resumes the agent with a
                    ;; note; Drop takes it off the queue (back to :in-progress).
                    ;; The usual path is to fix in the worktree and `nido ship` again.
                    [{:id :reply :label "Reply" :kind :resume                :style :default}
                     {:id :drop  :label "Drop"  :kind :mutation              :style :danger}]
                    [])
     [])))

(defn classify-origin
  "Origin of a workstream from its RAW record: :notion :github :slack :scratch.
   Delegates to the battle-tested source classifier (ref-less-but-autonomous
   workstreams are NOT scratch — scratch is keyed on the :scratch stage marker)."
  [ws]
  (wsv/ws-source ws))

(defn- to-spine
  "Project one wsv row onto the single spine: rename :source→:origin, fold a
   scratch workstream to :in-progress, and a settled (closed) one to :done.

   :dismissed? is checked FIRST and wins over every fold. It is the nido-side
   dismiss veto, and it is deliberately outside both stage projections: a
   dismissed row's Notion lifecycle is unchanged (Notion still says whatever it
   said), so :dismissed is a BOARD BAND, not a lifecycle position. Order matters —
   dismiss settles the workstream, so a ledger-driven row reads :settled and would
   fold to :done, which is a band on neither tab.

   This is the one function BOTH row paths pass through — list-workstreams (the
   board list) and work/workstream (the detail pane) — so the stamp reaches the
   band key, gate-actions, and the pane's stage line from here alone."
  [row]
  (let [origin (:source row)
        stage  (cond
                 (:dismissed? row)              :dismissed
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

(defn winding-down
  "Closed (:done/:dropped) workstreams of `project` still holding ≥1 live
   session — resources you're paying for on finished work. Never gates
   (:needs-you false); rendered as the Active tab's trailing band with one
   action: bring-down!. Empty when live-names is empty/nil."
  [project live-names]
  (let [live (set live-names)]
    (if (empty? live)
      []
      (->> (cws/list-ids project)
           (keep #(cws/read-ws project %))
           (filter :closed)
           (keep (fn [w]
                   (let [sessions (csession/list-sessions project (:id w))
                         live-s   (filterv #(contains? live (:name %)) sessions)]
                     (when (seq live-s)
                       {:ws-id     (:id w)
                        :project   (name project)
                        :stage     :winding-down
                        :origin    (classify-origin w)
                        :label     (wsv/label w sessions)
                        :outcome   (get-in w [:closed :outcome])
                        :sessions  (mapv :name live-s)
                        :needs-you false}))))
           vec))))

(defn grouped
  "Workstreams grouped along the single spine for the board:
   {:triage {:in-flight [..] :queued [..]} :ready [..] :in-progress [..]}.
   Scratch one-offs fold into :in-progress (done via list-workstreams' remap);
   :done is omitted. The board renders these groups directly."
  ([project] (grouped project nil))
  ([project live-names]
   (assoc (wsv/grouped-by-stage (list-workstreams project live-names))
          :winding-down (winding-down project live-names))))

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
     :brakes         (when auto (:limits auto))
     :error          (when auto (:error auto))}))

(defn- ledger-summary
  "Light ledger facet for the detail view: its key (BR-#### / slack id), status
   (from the ticket status record), and report count (from the workstream
   ledger — the single event store). nil when `k` is nil (the workstream
   carries no ledger ref)."
  [project k]
  (when k
    {:key          k
     :status       (:status (tickets/read-meta project k))
     :report-count (count (:entries (cws/find-by-ref-id project k)))}))

(defn- first-heading
  "The text of the first markdown heading in `md` (e.g. '# Verdict' -> \"Verdict\"),
   or nil."
  [md]
  (some->> md
           str/split-lines
           (some #(second (re-matches #"#+\s+(.*)" %)))))

(defn- entry->report
  "Render a ledger entry as a `:format`-tagged gate report. An `.edn` file is a
   typed event — read + validated against the schema for its `:kind`
   (report/validate-event), :at stamped from the entry; any other file is markdown
   (:format :markdown). A typed `.edn` that fails to read/validate degrades to a
   :markdown payload of its raw text rather than blanking the pane."
  [base-dir entry]
  (let [f (str (fs/path base-dir (:file entry)))]
    (or (when (str/ends-with? (str (:file entry)) ".edn")
          (try (-> (report/validate-event (:kind entry) (io/read-edn f))
                   (assoc :at (:at entry)))
               (catch Throwable _ nil)))
        (let [md (when (fs/exists? f) (slurp f))]
          {:format   :markdown
           :kind     (:kind entry)
           :at       (:at entry)
           :title    (first-heading md)
           :markdown md}))))

(defn- first-line
  "First non-blank line of `s`, trimmed, or nil."
  [s]
  (some->> (some-> s str/split-lines)
           (map str/trim)
           (some not-empty)))

(defn- index-row
  "Lightweight index entry for the pane list: {:seq :kind :at :title}. Title is the
   typed report's :title / markdown's first heading / first line / the kind name —
   never blank."
  [base-dir entry]
  (let [r (entry->report base-dir entry)]
    {:seq   (:seq entry)
     :kind  (:kind entry)
     :at    (:at entry)
     :title (or (report/report-title r)
                (not-empty (:title r))
                (first-line (:markdown r))
                (name (:kind entry)))}))

(defn- report-at
  "The entry whose :seq is `seq` (else the latest), rendered via entry->report."
  [base-dir entries seq]
  (let [by-seq (into {} (map (juxt :seq identity)) entries)]
    (entry->report base-dir (or (get by-seq seq) (last entries)))))

(defn- active-ledger
  "The workstream's own ledger — the single event store. {:base-dir <string|nil>
   :entries <vector>}, oldest-first."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    {:base-dir (cstate/workstream-dir project ws-id) :entries (vec (:entries w))}
    {:base-dir nil :entries []}))

(defn- intake-fallback
  "Synthetic markdown report from a workstream's stored intake text (un-triaged
   Slack inbox), or nil. The last-resort report when no ledger entry exists."
  [project ws-id]
  (when-let [w (cws/read-ws project ws-id)]
    (when-let [intake (:intake w)]
      (let [text (or (-> intake :payload :text) (-> intake :payload :title) "")]
        (when (seq text)
          {:format   :markdown
           :kind     :slack-report
           :at       (:created-at w)
           :title    (first-heading text)
           :markdown text})))))

(defn latest-report
  "The workstream's most recent ledger entry as a `:format`-tagged gate report,
   or nil. Resolves the active ledger (the workstream's own event store), reads
   its latest entry, and finally falls back to stored intake text so an un-triaged
   :incoming Slack report still shows its message body."
  [project ws-id]
  (let [{:keys [base-dir entries]} (active-ledger project ws-id)]
    (if (seq entries)
      (entry->report base-dir (last entries))
      (intake-fallback project ws-id))))

(defn environment
  "The workstream's single current environment: its latest live HEAVY (impl)
   session record, or nil when none exists yet (still triage, or a light-only
   scratch). Resolved by weight + recency, NOT by liveness — a down-but-provisioned
   impl session is still the environment (you Start it). :archived (torn-down)
   sessions are excluded. Callers use (:name env) to resolve dev-state/facts."
  [project ws-id]
  (->> (csession/list-sessions project ws-id)
       (filter #(= :heavy (:weight %)))
       (filter csession/live?)
       (sort-by :created-at)
       last))

(defn- bare-pane
  "Pane detail for a bare watched-view row — a page in the project's Notion cache
   that no workstream covers (wsv/bare-row). Derived FROM bare-row rather than
   rebuilt, so the pane can never disagree with the board list about stage or
   label — the same reason `workstream` routes through wsv/workstream-row.

   Carries explicit nils for the ledger/report/environment keys the full pane
   renders: there is nothing behind any of them, and the pane's :bare? branch
   skips those blocks outright. `fct` is the cache entry, passed in so the caller
   reads project-page-facts once."
  [project page-id fct]
  (let [row (wsv/bare-row project page-id fct)]
    {:ws-id         page-id
     :project       project
     :origin        :notion
     :bare?         true
     :label         (:label row)
     :br-id         (:br-id row)
     :stage         (:stage row)
     :notion-status (:status fct)
     :ledger        nil
     :entries       nil
     :selected-seq  nil
     :on-latest?    true
     :report        nil
     :environment   nil
     :sessions      []}))

(defn workstream
  "Full detail for one workstream: origin, spine stage, label, a light ledger
   facet, a newest-first entry INDEX (nil when ≤1 entry), the SELECTED entry's
   report (`selected-seq`, default latest, out-of-range → latest), `:on-latest?`
   (is the selected entry the current one — gates the pane's live actions),
   `:environment` (the one current session — `work/environment`), and its sessions
   on the autonomy axis. nil when the workstream is absent."
  ([project ws-id] (workstream project ws-id nil))
  ([project ws-id selected-seq]
   (if-let [w (cws/read-ws project ws-id)]
     (let [sessions (csession/list-sessions project ws-id)
           ;; Pass the Notion cache so the pane derives stage exactly like the board
           ;; list (workstream-rows) — else the pane goes Notion-driven vs legacy and
           ;; they disagree (e.g. a promoted ticket: list :ready, pane :in-progress).
           row      (to-spine (wsv/workstream-row project w nil (notion-cache/project-page-facts project)))
           {:keys [base-dir entries]} (active-ledger project ws-id)
           sel      (when (seq entries)
                      (or ((set (map :seq entries)) selected-seq)
                          (:seq (last entries))))
           index    (when (> (count entries) 1)
                      (vec (reverse (mapv #(index-row base-dir %) entries))))]
       {:ws-id        ws-id
        :project      project
        :origin       (classify-origin w)
        :stage        (:stage row)
        :label        (:label row)
        :links        (:links row)
        :ledger       (ledger-summary project (:br-id row))
        :entries      index
        :selected-seq sel
        ;; The selected entry is the CURRENT one (newest, or there are none).
        ;; Live actions are offered only on the current entry — older entries are
        ;; an immutable read-back, not something you act on.
        :on-latest?   (or (empty? entries) (= sel (:seq (last entries))))
        :report       (if (seq entries)
                        (report-at base-dir entries sel)
                        (latest-report project ws-id))
        :environment  (environment project ws-id)
        :sessions     (mapv session-facet sessions)})
     ;; No workstream at this id. For a bare watched-view row the ws-id IS the
     ;; Notion page-id, so read-ws always misses and there are two live cases:
     ;;   1. a workstream now covers the page — Start triage minted one under a
     ;;      FRESH nido ws-id while the URL still names the page-id. Resolve to it,
     ;;      or the pane stays bare forever after starting a triage.
     ;;   2. still uncovered — render the bare pane.
     ;; A genuinely unknown ws-id falls through both and stays nil.
     ;; find-by-ref-id is O(workstreams), but only runs on a read-ws miss — i.e.
     ;; only for a bare-row selection, never on the hot path.
     (let [fct (get (notion-cache/project-page-facts project) ws-id)
           now (when-let [br (:br fct)] (cws/find-by-ref-id project br))]
       (cond
         now (workstream project (:id now) selected-seq)
         fct (bare-pane project ws-id fct)
         :else nil)))))

(defn- parked-session
  "The first parked autonomous session under a workstream, or nil — the session a
   :reply resolves against."
  [project ws-id]
  (->> (csession/list-sessions project ws-id)
       (filter csession/parked?)
       first))

(defn- resuming?
  "True iff the workstream has a LIVE autonomous session ACTIVELY EXECUTING a turn
   (phase :preprocessing/:running) — i.e. a resume/burst is genuinely in flight.
   Keeps 'Apply → working…' honest: the gate stays visible but offers no actions
   until the agent parks or terminates.

   Checks in-progress-phases rather than the old `(not parked?)`: a :failed/:done/
   :queued session is NOT in flight, and counting it stranded a PERMANENT 'working…'
   on any workstream carrying a failed-but-unarchived session — e.g. a plan-bug
   spawn failure, whose teardown is a no-op so the session stays :live at :failed.
   That dead 'working…' hides the gate's own actions (Promote/Drop), so the ticket
   looks stuck."
  [project ws-id]
  (->> (csession/list-sessions project ws-id)
       (some (fn [s] (and (:autonomy s)
                          (csession/live? s)
                          (contains? csession/in-progress-phases
                                     (get-in s [:autonomy :phase])))))
       boolean))

(defn- ->gate
  "Hydrate one needs-you spine row into a gate. `:project` is canonicalized to a
   STRING (the web routes on it, e.g. /gate/<project>/…; all-machine-rows tags
   rows with the same string key) so a gate reads the same whether it came from
   `gates` (string or keyword arg) or `all-gates`."
  [project row]
  (let [parked? (= :parked-at-gate (:engagement row))
        psess   (parked-session project (:ws-id row))]
    {:ws-id        (:ws-id row)
     :project      (name project)
     :origin       (:origin row)
     :stage        (:stage row)
     :label        (:label row)
     :links        (:links row)
     :report       (latest-report project (:ws-id row))
     :actions      (gate-actions (:stage row) parked? (:origin row))
     :session      (:name psess)
     :resume-error (get-in psess [:autonomy :error])
     :working?     (resuming? project (:ws-id row))}))

(defn gates
  "A project's gates: workstreams that want you now (needs-you), each hydrated
   with its report + follow-actions. A SETTLED (closed) workstream is never a gate,
   even if a stale stage-override still projects :needs-you.
   A DISMISSED row is never a gate either — and the filter is load-bearing, not
   cosmetic: Notion-driven rows ignore nido :closed for engagement, and the parked
   session is torn down asynchronously by review/sweep-resolved!, so without this a
   just-dismissed row keeps its :needs-you until the daemon next ticks.
   `live-names` threads into the engagement projection (pass it so a downed
   one-off reads idle)."
  ([project] (gates project nil))
  ([project live-names]
   (->> (list-workstreams project live-names)
        (remove #(= :dismissed (:stage %)))
        (filter :needs-you)
        (remove #(= :settled (:engagement %)))
        (mapv #(->gate project %)))))

(defn gate
  "Full gate detail for one workstream, or nil when it is absent or not a gate."
  [project ws-id]
  (->> (gates project)
       (filter #(= ws-id (:ws-id %)))
       first))

(defn all-gates
  "Gates across every registered project, needs-you/newest-first within each.
   Mirrors the dashboard's cross-project aggregation (see all-machine-rows).
   `->gate` canonicalizes each gate's :project to a string, so the raw
   list-projects key threads straight through. A project that can't be read
   contributes no gates rather than failing the board."
  []
  (->> (project/list-projects)
       (mapcat (fn [[pname _entry]]
                 (try (gates pname)
                      (catch Throwable _ []))))
       vec))

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
   new/promote/done surface verbs. A workstream-less ws-id (e.g. a bare
   watched-view row) is a no-op: {:decision :no-workstream}. Dispatch:
     :in-progress → the full promote gesture (gate + provision the planning leg)
     :done        → close the workstream (:done outcome)
     other        → advance the stored stage only (no autonomous leg)
   Returns {:decision <kw>}: promote's decision verbatim, else :done / :advanced."
  [project ws-id target]
  (if (nil? (cws/read-ws project ws-id))
    {:decision :no-workstream}
    (case target
      :in-progress (promote/promote-workstream! project ws-id)
      :done        (do (cws/close! project ws-id :done) {:decision :done})
      (do (cws/advance-stage! project ws-id target) {:decision :advanced}))))

(defn- bare-row-br
  "The BR-#### behind a bare watched-view row, whose synthetic ws-id IS the Notion
   page-id (workstreams-view/bare-row). nil when the page is not in the project's
   watched-view cache, or carries no unique-id."
  [project page-id]
  (get-in (notion-cache/project-page-facts project) [page-id :br]))

(defn dismiss!
  "Take a workstream off the triage radar: record a :dismissed disposition on its
   ticket (so auto-re-triage skips it) and settle the workstream :dismissed (which
   frees its trigger's in-flight slot and removes it from the queue).

   BOTH writes carry the veto, because neither alone covers every row. The ticket
   record needs a ledger ref (notion-or-slack), which a ref-less coordinator
   workstream has not got; the :closed outcome is ignored outright by the
   notion-driven projection. Closing :dismissed rather than :dropped is safe
   because nothing branches on the outcome value — every other reader tests
   :closed for presence or renders (name outcome).

   A workstream-less ws-id is a bare watched-view row: the ticket stamp alone
   carries the veto there, and only a page with no BR is a genuine no-op
   ({:decision :no-workstream}). Returns {:decision :dismissed} otherwise."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    (do
      (when-let [br (:id (wsv/ledger-ref w))]
        (tickets/dismiss! project br))
      (cws/close! project ws-id :dismissed)
      {:decision :dismissed})
    ;; Bare watched-view row: no workstream to close, so the ticket stamp IS the
    ;; whole veto — bare-row reads :dismissed? straight off ticket status, which
    ;; lands the row in the Dismissed band where Restore already works.
    ;; tickets/dismiss! creates the record when absent, so a never-triaged page
    ;; is dismissable. Exactly mirrors restore!'s bare branch. Only a page with
    ;; no BR is a genuine no-op.
    (if-let [br (bare-row-br project ws-id)]
      (do (tickets/dismiss! project br) {:decision :dismissed})
      {:decision :no-workstream})))

(defn restore!
  "Undo a dismiss: clear the ticket's status so it is re-triable and reopen the
   workstream. The inverse of dismiss!, with one deliberate asymmetry — the
   pre-dismiss status is NOT restored. Dismiss lets the daemon sweep tear the
   parked triage session down, so putting :awaiting-input back would name a
   conversation that no longer exists; a status-less ticket is honest and lets the
   auto-triage gate pick it up fresh. The ledger (and its triage report) survive
   untouched.

   Reopens at :triaging, NOT :triage, even though :triage is where the row lands.
   :triage is a member of session/lifecycle-stages, so storing it makes
   stage-projection treat it as a manual override on the now-open workstream and
   stop deriving from the ticket status forever — the row would pin at :triage,
   never reach :ready, never be promotable again. :triaging is the create! default,
   deliberately absent from lifecycle-stages precisely so it never overrides, and
   derive-stage on the status-less ticket yields :triage anyway. Do not 'correct'
   this to :triage.

   A ws-id with no workstream is a bare watched-view row: its Restore button is
   real (bare-row stamps :dismissed?, so a `bb nido:ticket:dismiss` orphan lands in
   the band), and clearing the ticket status IS the whole undo there — the row has
   no nido state beyond it. Only a row whose page carries no BR is a genuine no-op:
   {:decision :no-workstream}."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    (do
      (when-let [br (:id (wsv/ledger-ref w))]
        (tickets/clear-status! project br))
      (cws/reopen! project ws-id :triaging)
      {:decision :restored})
    (if-let [br (bare-row-br project ws-id)]
      (do (tickets/clear-status! project br)
          {:decision :restored})
      {:decision :no-workstream})))

(defn- slack-ref-of
  "The workstream's :slack-message external-ref map, or nil."
  [w]
  (some #(when (= :slack-message (:adapter %)) %) (:external-refs w)))

(defn- parse-slack-id
  "Split a Slack message id `slack-<channel>-<ts>` into {:channel <str> :ts <str>},
   or nil. Channel is dashless (e.g. \"C07N0U273AR\"); ts is digits+dot — the greedy
   channel group backtracks so the trailing numeric ts stays whole."
  [slack-id]
  (when-let [[_ channel ts] (re-matches #"^slack-(.+)-([0-9.]+)$" (str slack-id))]
    {:channel channel :ts ts}))

(defn- apply-proposed!
  "Approve a Slack-originated proposal: create the Notion page at \"Not started\",
   associate the new BR-#### with THIS workstream (one ledger across the backlog
   gap), post the ticket link back to the Slack thread, and complete the slack-id
   ticket record so the parked session sweeps to :done. On a create error, leaves
   the ws parked & re-approvable. Returns {:decision :created :br …} |
   {:decision :error :error :kw}.

   Ordering is load-bearing: ledger-ref = (or notion-ref slack-ref), so once the
   :notion BR is add-ref!'d it becomes the ledger key. Capture the slack-id BEFORE
   add-ref!, and complete! the SLACK-id record — that is the id the run's
   event-payload carries, so completing it is what lets sweep-resolved! settle the
   parked session."
  [project ws-id proposal w]
  (let [slack-id (:id (slack-ref-of w))
        parsed   (parse-slack-id slack-id)]
    (if (or (str/blank? slack-id) (nil? parsed))
      {:decision :error :error :no-slack-ref}
      (let [{:keys [channel ts]} parsed
            db                   (:database (views/load-registry project))
            ntok                 (notion/keychain-token)
            ds                   (notion/resolve-data-source-id db ntok)
            created              (notion/create-page! ds ntok
                                   {:title       (:title proposal)
                                    :description (report/report->markdown proposal)
                                    :type        (:ticket-type proposal)
                                    :status      "Not started"
                                    :priority    (:priority proposal)})]
        (if (:error created)
          ;; Carry :status through — an :http error is only actionable with the code
          ;; (a 400 is a payload we built wrong; a 404 is a sharing/permission problem).
          (cond-> {:decision :error :error (:error created)}
            (:status created) (assoc :status (:status created)))
          (let [br (:id created)]
            (cws/add-ref! project ws-id {:adapter :notion :id br
                                         :page-id (:page-id created) :url (:url created)})
            (try (slack/post-message channel (slack/keychain-token)
                   {:text (str "Ticket created: " (:url created)) :thread-ts ts})
                 (catch Throwable _ nil))   ; link-back is best-effort; the ticket already stands
            (tickets/complete! project slack-id :triaged :applied)
            {:decision :created :br br}))))))

(defn- triage-notion-props
  "Notion :properties map for a routed :triage-report. Ball Holder replaces; App Domain
   unions `current-domains` (the page's existing multi_select names) with the routed one.
   Deep (`:notion-writes` present) adds Type/Effort/Status/Title; Effort is skipped when
   :squirrel (not a real select option)."
  [{:keys [routing notion-writes]} current-domains]
  (let [domains (->> (conj (vec current-domains) (:app-domain routing))
                     (remove nil?) distinct (mapv (fn [n] {:name n})))]
    (cond-> {"Ball Holder" {:people [{:id (report/owner->user-id (:owner routing))}]}
             "App Domain"  {:multi_select domains}}
      notion-writes
      (into (cond-> {}
              (:type notion-writes)
              (assoc "Type" {:select {:name (:type notion-writes)}})
              (and (:effort notion-writes) (not= :squirrel (:effort notion-writes)))
              (assoc "Effort" {:select {:name (name (:effort notion-writes))}})
              (:status-transition notion-writes)
              (assoc "Status" {:status {:name (second (:status-transition notion-writes))}})
              (:title notion-writes)
              (assoc "Task result" {:title [{:text {:content (:title notion-writes)}}]}))))))

(defn- our-callout?
  "True when `block` is our enriched callout carrying `marker`."
  [block marker]
  (and (= "callout" (:type block))
       (some #(str/includes? (or (get-in % [:text :content]) "") marker)
             (get-in block [:callout :rich_text]))))

(defn- prepend-enriched-callout!
  "Best-effort deep enrichment: delete our prior callout (idempotency), prepend a fresh
   one, verify it landed at the top. Returns :ok | :warn. Never throws.

   The callout body is split into capped rich_text runs — a single run over 2000
   chars 400s the request, and since this leg is best-effort that failure showed
   up only as a :warn, silently dropping the enrichment on the longest (i.e. most
   valuable) reports."
  [page-id br desc token]
  (try
    (let [marker (str "🤖 Enriched (triage " br ")")
          block  {:object "block" :type "callout"
                  :callout {:icon {:type "emoji" :emoji "🤖"}
                            :rich_text (notion/rich-text-runs (str marker "\n" desc))}}
          first0 (-> (notion/retrieve-block-children page-id token {}) :results first)]
      (when (and first0 (our-callout? first0 marker))
        (notion/delete-block! (:id first0) token))
      (if (:error (notion/prepend-block-children! page-id [block] token))
        :warn
        (if (our-callout? (-> (notion/retrieve-block-children page-id token {}) :results first) marker)
          :ok :warn)))
    (catch Throwable _ :warn)))

(defn- apply-routed!
  "Execute a routed :triage-report's Notion writes, then complete the record. Property
   writes gate completion; the deep callout is best-effort. Returns {:decision :applied
   [:callout :warn]} on success, {:decision :notion-failed :error <kw>} otherwise."
  [project _ws-id report w]
  (let [page-id (:page-id (wsv/notion-ref w))
        br      (:id (wsv/ledger-ref w))
        token   (notion/keychain-token)]
    (cond
      (nil? token)          {:decision :notion-failed :error :no-token}
      (str/blank? page-id)  {:decision :notion-failed :error :no-page-id}
      :else
      (let [page (notion/retrieve-page page-id token)]
        (if (:error page)
          ;; Can't read the current App Domain tags, so we can't honor "additive, never
          ;; clobber" — fail closed and leave the ticket parked for retry rather than
          ;; write with incomplete data (see nido.coordinator.notify/merged-participants
          ;; for the same additive-write contract).
          {:decision :notion-failed :error (:error page)}
          (let [current (keep :name (get-in page [:properties (keyword "App Domain") :multi_select]))
                res     (notion/update-page-properties! page-id (triage-notion-props report current) token)]
            (if (:error res)
              {:decision :notion-failed :error (:error res)}
              (let [callout (when-let [desc (get-in report [:notion-writes :description-prepend])]
                              (prepend-enriched-callout! page-id br desc token))]
                (when br
                  (tickets/complete! project br :triaged :applied)
                  (try (facets/refresh-for-ticket! project br) (catch Throwable _ nil)))
                (cond-> {:decision :applied}
                  (= :warn callout) (assoc :callout :warn))))))))))

(defn apply!
  "Accept a parked triage verdict WITHOUT resuming the review conversation. Three paths:

   • Slack proposal (`:proposed-ticket`, no :notion ref yet) → create the Notion page
     (apply-proposed!). Returns {:decision :created …} | {:decision :error …}.
   • Routed Notion triage (`:triage-report` with :routing, on a :notion-backed ws) →
     execute the routing outcome to Notion (apply-routed!): Ball Holder + App Domain,
     deep properties, deep callout. Returns {:decision :applied [:callout :warn]} or
     {:decision :notion-failed :error <kw>} (ticket left parked to retry).
   • Legacy / Slack-triage (any other report, or a ref-less ws) → finalize the ticket
     :triaged/:applied nido-side only. Returns {:decision :applied}.

   The daemon's sweep settles the now-resolved parked session."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    (let [report (latest-report project ws-id)]
      (cond
        (and (= :proposed-ticket (:format report)) (nil? (wsv/notion-ref w)))
        (apply-proposed! project ws-id report w)

        (and (= :triage-report (:format report)) (:routing report) (wsv/notion-ref w))
        (apply-routed! project ws-id report w)

        :else
        (do
          (when-let [br (:id (wsv/ledger-ref w))]
            (tickets/complete! project br :triaged :applied)
            (try (facets/refresh-for-ticket! project br)
                 (catch Throwable _ nil)))
          {:decision :applied})))
    {:decision :applied}))

(def ^:private workstream-less-actions
  "Gate actions that are meaningful on a bare watched-view row — one with no
   workstream of its own, only a Notion page and a ticket record. Each carries its
   own workstream-less branch AND its own :no-workstream refusal, so the read-ws
   guard in resolve-gate! must route them BEFORE it, or the only actions such a
   row offers become silent no-ops."
  #{:restore :dismiss})

(defn resolve-gate!
  "Apply a gate follow-action, dispatching on `action-id`. A workstream-less ws-id
   (e.g. a bare watched-view row) is a no-op — {:decision :no-workstream} — for
   every action but :restore and :dismiss, which such a row legitimately offers
   (see workstream-less-actions).
     :promote -> set-stage! :in-progress   :dismiss -> off-radar (ticket + ws :dismissed)
     :drop    -> close! :dropped            :done    -> set-stage! :done
     :apply   -> apply! (ticket:complete)   :reply   -> resume! the parked agent with `input`
     :restore -> restore! (clear ticket status + reopen at :triaging)
   Returns the resolver's result map."
  ([project ws-id action-id] (resolve-gate! project ws-id action-id nil))
  ([project ws-id action-id input]
   (cond
     ;; Bare-row-capable actions run before the guard (see workstream-less-actions).
     (contains? workstream-less-actions action-id)
     (case action-id
       :restore (restore! project ws-id)
       :dismiss (dismiss! project ws-id))

     (nil? (cws/read-ws project ws-id)) {:decision :no-workstream}
     :else
     (case action-id
       :promote (set-stage! project ws-id :in-progress)
       :done    (set-stage! project ws-id :done)
       :drop    (do (cws/close! project ws-id :dropped) {:decision :dropped})
       :apply   (apply! project ws-id)
       :reply   (resume/resume! project ws-id input)
       (throw (ex-info "Unknown gate action" {:action-id action-id :ws-id ws-id}))))))

(defn new!
  "Birth a scratch workstream and bring its session up. Mirrors the proven add
   path: lifecycle/up! creates the worktree + services (it is heavy and slow —
   surfaces wrap this in their own progress UI); scratch/birth! births the
   ref-less workstream + human session. Idempotent on an existing session name.
   Returns the ws-id. `project` may be a keyword or string."
  [project session-name]
  (let [opts {:project (name project)}]
    (lifecycle/up! session-name opts)
    (scratch/birth! (keyword (name project)) session-name
                    (lifecycle/session-weight session-name opts))))

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

(defn reclaimed?
  "True iff `session` under `ws-id` is owned by a Run whose ephemeral
   session-home was reclaimed — i.e. it CAN be re-hydrated but isn't landable
   right now. Cheap (a run lookup + a symlink stat); the interactive open path
   uses it to decide whether to re-provision before landing. False for a session
   with no owning Run (nothing to re-hydrate from) or whose home is present."
  [project ws-id session]
  (boolean
   (when-let [run (runs/find-for-session project ws-id session)]
     (not (runs/home-present? run)))))

(defn ensure-open!
  "Make `session` under `ws-id` landable, re-provisioning its session-home when
   the Run that owns it had the home reclaimed. Returns true if it re-hydrated,
   false if nothing was needed (home present, or no owning Run). SLOW when it
   re-provisions (brings the session back up) — callers run it off the render
   thread. Throws (tagged `:rehydrate-failed`) if re-provisioning fails."
  [project ws-id session]
  (boolean
   (when-let [run (runs/find-for-session project ws-id session)]
     (runs/ensure-session-home! run))))

(defn facet-dimensions
  "Ordered facet keys (kebab keywords) for `source` in `project`. :notion and :all
   resolve to the configured Notion dimensions (today the only configured source);
   other sources have none yet. 1-arity = project-wide (:all).

   The 2-arity `source` dispatch has no production caller today — the TUI's three
   call sites (tui.clj) all use the 1-arity, and server/facet-dims-for (the web
   caller) was deleted with the source/facet chips. Kept for the TUI's later
   migration onto the band model; do not remove."
  ([project] (facet-dimensions project :all))
  ([project source]
   (if (contains? #{:all :notion} source)
     (mapv notion/normalise-property-name (views/facet-properties project))
     [])))

(defn grouped-rows
  "Flat seq of every workstream row in a `work/grouped` map (all board bands).

   No production caller today (views/facet-rows, its last one, is gone with the
   source/facet chips) — but it is load-bearing as a test oracle: it is the
   independent traversal work_test.clj's tab-bands-union-covers-every-row-exactly-once
   cross-checks tab-bands against, so a one-sided edit to either breaks that test.
   Do not delete as 'unused'."
  [grouped]
  (concat (:incoming grouped)
          (get-in grouped [:triage :in-flight])
          (get-in grouped [:triage :queued])
          (:in-progress grouped)
          (:shipping grouped)
          (:winding-down grouped)
          (:dismissed grouped)))

(defn- facet-row-values
  "The value(s) a row carries for facet `k`, as a seq (vector facets expand to
   their elements; a scalar yields a 1-seq; absent/empty yields nil)."
  [k row]
  (let [v (get-in row [:facets k])]
    (cond (nil? v) nil
          (coll? v) (seq v)
          :else [v])))

(defn facet-values
  "Ordered distinct values present for facet `k` across the project's non-done
   workstreams, with :unclassified appended when any such row lacks a value."
  [project k]
  (let [rows (remove #(= :done (:stage %)) (list-workstreams project))
        present (->> rows (mapcat #(facet-row-values k %)) distinct vec)
        any-missing? (some #(nil? (facet-row-values k %)) rows)]
    (cond-> present any-missing? (conj :unclassified))))

(defn facet-match?
  "True when `row` satisfies every active selection in `facet-filter`. A value of
   :all (or absent) does not constrain. :unclassified matches a row missing that
   facet. A scalar/vector facet matches by =/contains?."
  [facet-filter row]
  (every?
   (fn [[k v]]
     (or (= v :all)
         (let [vals (facet-row-values k row)]
           (if (= v :unclassified)
             (nil? vals)
             (boolean (some #(= v %) vals))))))
   facet-filter))

(defn session-live?
  "Does this registry-shaped session map hold a port RIGHT NOW? Probes the
   recorded app/nREPL ports rather than trusting that they were recorded — the
   registry is only cleaned on a graceful `down!` (engine/stop-session! →
   state/remove-from-registry!), so a reboot, a JVM crash or a `kill` leaves an
   entry with its port numbers intact indefinitely.

   `:pg-port` is deliberately not a signal: most sessions point at the project's
   SHARED cluster, which answers whenever any one session is up. `:repl-pid` is
   not a signal either: PIDs are recycled, so a months-old entry whose PID was
   reused would read as live forever."
  [s]
  (boolean (or (and (pos-int? (:app-port s))   (proc/tcp-open? (:app-port s)))
               (and (pos-int? (:nrepl-port s)) (proc/tcp-open? (:nrepl-port s))))))

(defn live-session-names
  "Set of session names for `project` that are actually up — i.e. hold an open
   app/nREPL port right now. THE liveness oracle: the TUI board, the web
   grouping, the adopter, and the winding-down band all read this one fn."
  [project]
  (->> (lifecycle/list-all-data {:project (name project)})
       :sessions
       (filter session-live?)
       (map :name)
       set))

(def ^:private registry-prune-grace-ms
  "Never prune an entry younger than this. `:created-at` is restamped on every
   `up!` (engine/start-session!), so this only ever shields a session that just
   started — belt and braces on top of the fact that the entry is written AFTER
   its services are listening."
  (* 10 60 1000))

(defn- entry-age-ms
  "Milliseconds since the entry was (re)registered, or nil when it carries no
   parseable `:created-at` — a pre-timestamp entry is by definition old."
  [entry now-ms]
  (when-let [ts (:created-at entry)]
    (try (- now-ms (.toEpochMilli (java.time.Instant/parse ts)))
         (catch Exception _ nil))))

(defn- prunable?
  "Only an entry that once recorded a probeable port is a prune candidate. An
   entry that never had one — a `:lite` session (`:services []`, so the engine
   records :app-port/:nrepl-port nil) — was never `live` under session-live?'s
   definition, so judging it dead on liveness grounds is a category error: it
   would be pruned while in active use, and reclaim/orphan-instance-dirs would
   then delete its state dir."
  [entry]
  (or (pos-int? (:app-port entry)) (pos-int? (:nrepl-port entry))))

(defn- prune-veto?
  "Keep an entry whose ports do not answer but whose JVM is still running.
   `session-live?` ignores :repl-pid on purpose — a recycled PID would make a
   dead session read live forever, the wrong failure for the ORACLE. Deleting is
   the opposite trade: a false `dead` costs a PGDATA via reclaim, a recycled PID
   costs only a delayed prune. So a live PID vetoes the DELETE without ever
   making the oracle report `live`."
  [entry]
  (boolean (and (pos-int? (:repl-pid entry))
                (proc/process-alive? (:repl-pid entry)))))

(defn prune-dead-registry!
  "Drop every registry entry whose session no longer holds a port, and return
   their instance-ids. The registry is otherwise only cleaned by a graceful
   `down!` (engine/stop-session! → state/remove-from-registry!), so a reboot, a
   JVM crash or a `kill` leaves an entry — and its phantom Winding-down row —
   behind forever. Entries inside the grace window are left alone.

   Two extra guards keep this DELETE path safe, distinct from session-live?'s
   ORACLE contract (a false `live` there is the expensive mistake — see
   session-live?'s docstring; that fn is untouched):
     prunable?    — an entry with no recorded port (a `:lite` session) is never
                    a candidate; it was never `live` and judging it dead would
                    be a category error, not a liveness result.
     prune-veto?  — an entry whose :repl-pid is still running is kept even
                    though its ports don't answer; a false `dead` here costs a
                    PGDATA via reclaim, so the JVM's liveness (not just its
                    ports) gets a say before deletion.

   Registry-global, not per-project: one call covers every project."
  ([] (prune-dead-registry! (System/currentTimeMillis)))
  ([now-ms]
   (let [dead (->> (sstate/read-registry)
                   (remove (fn [[_ entry]]
                             (or (not (prunable? entry))
                                 (session-live? entry)
                                 (prune-veto? entry)
                                 (when-let [age (entry-age-ms entry now-ms)]
                                   (< age registry-prune-grace-ms)))))
                   vec)]
     (sstate/remove-many-from-registry! (map first dead))
     (mapv (fn [[k entry]] (or (:instance-id entry) k)) dead))))

(defn bring-down!
  "Down every live session of a workstream — the winding-down band's one action.
   Synchronous and slow (lifecycle/down! per session); callers own async + UI
   optimism. Returns {:downed [names]}."
  [project ws-id]
  (let [live  (live-session-names project)
        names (->> (csession/list-sessions project ws-id)
                   (map :name)
                   (filterv live))]
    (doseq [n names]
      (lifecycle/down! n {:project (name project)}))
    {:downed names}))

(defn- owned-session-names
  "Session names owned by ANY workstream of `project` — open or closed. Closed
   owners keep their sessions out of adoption (they are winding-down leftovers)."
  [project]
  (->> (cws/list-ids project)
       (mapcat #(csession/list-sessions project %))
       (map :name)
       set))

(defn orphan-live-sessions
  "Pure: the live sessions no workstream owns."
  [live owned]
  (set/difference (set live) (set owned)))

(defn- yield-duplicate-scratch!
  "Adopted-then-claimed: delete any BARE scratch workstream (no refs, no ledger
   entries, exactly one session) whose session is also owned by another OPEN
   workstream — the newest real owner wins. Returns the deleted ws-ids."
  [project]
  (let [open (->> (cws/list-ids project)
                  (keep #(cws/read-ws project %))
                  (remove :closed))
        owners-of (fn [n]
                    (filter (fn [w] (some #(= n (:name %))
                                          (csession/list-sessions project (:id w))))
                            open))]
    (->> open
         (filter (fn [w] (and (scratch/scratch? w) (empty? (:entries w)))))
         (keep (fn [w]
                 (let [sess (csession/list-sessions project (:id w))]
                   (when (and (= 1 (count sess))
                              (some #(not= (:id w) (:id %))
                                    (owners-of (:name (first sess)))))
                     (cws/delete! project (:id w))
                     (:id w)))))
         vec)))

(defn adopt-orphans!
  "Enforce the invariant: every live session is reachable from a workstream.
   Births a scratch workstream for each live orphan (idempotent — birth! no-ops
   on an owned name), then yields bare scratch duplicates to real owners.
   Returns {:adopted [names] :yielded [ws-ids]}."
  [project]
  (let [orphans (sort (orphan-live-sessions (live-session-names project)
                                            (owned-session-names project)))]
    (doseq [n orphans]
      (scratch/birth! (keyword (name project)) n
                      (lifecycle/session-weight n {:project (name project)})))
    {:adopted (vec orphans)
     :yielded (yield-duplicate-scratch! project)}))

(defn- instance-id-for [project-name session-name]
  (if (= project-name session-name)
    project-name
    (str project-name "--" session-name)))

(defn machine-rows
  "Machine facts for every worktree of one project: registry entry, TCP liveness,
   RSS for the repl JVM + PG, and the configured heap ceiling. No UI-optimistic
   state — that is a surface concern injected by callers that need it."
  [project-name project-dir]
  (let [base     (lifecycle/worktrees-dir project-name project-dir)
        registry (sstate/read-registry)]
    (when (fs/exists? base)
      (->> (fs/list-dir base)
           (filter fs/directory?)
           (map (fn [d]
                  (let [nm       (str (fs/file-name d))
                        wt-path  (str d)
                        entry    (get registry wt-path)
                        port     (:app-port entry)
                        live?    (and (pos-int? port) (proc/tcp-open? port))
                        iid      (instance-id-for project-name nm)
                        repl-rss (when (and live? (:repl-pid entry))
                                   (proc/rss-bytes (:repl-pid entry)))
                        session  (when live? (sstate/read-session iid))
                        pg-pid   (when session
                                   (get-in session [:service-states :pg :pg-pid]))
                        pg-rss   (when (and live? pg-pid) (proc/rss-bytes pg-pid))
                        heap-max (when session
                                   (get-in session [:context :session :jvm :heap-max]))]
                    {:name nm :wt-path wt-path :entry entry :live? live?
                     :repl-rss repl-rss :pg-rss pg-rss :heap-max heap-max})))
           (sort-by :name)))))

(defn machine-facts
  "Machine facts for `names` (sessions of `project`), keyed by session name.
   The workstream pane's per-session ports/RSS/heap column feed."
  [project names]
  (let [dir  (:directory (get (project/list-projects) (name project)))
        keep (set names)]
    (into {}
          (for [{:keys [name entry live? repl-rss pg-rss heap-max]}
                (machine-rows (clojure.core/name project) dir)
                :when (contains? keep name)]
            [name {:live? live? :url (:url entry)
                   :pg-port (:pg-port entry) :nrepl-port (:nrepl-port entry)
                   :app-port (:app-port entry)
                   :repl-rss repl-rss :pg-rss pg-rss :heap-max heap-max}]))))

(defn all-machine-rows
  "Machine rows across all registered projects, live-first, each tagged :project.
   2-arity is pure (inject rows-fn + projects) for tests."
  ([] (all-machine-rows machine-rows (project/list-projects)))
  ([rows-fn projects]
   (->> (for [[pname entry] projects
              row           (or (try (rows-fn pname (:directory entry))
                                     (catch Throwable _ nil))
                                [])]
          (assoc row :project pname))
        (sort-by (juxt #(if (:live? %) 0 1) :project :name)))))

(defn all-grouped
  "[{:project <string> :grouped <grouped-map>} …] across every registered
   project (mirrors all-gates). A project that can't be read contributes
   nothing rather than failing the board."
  []
  (->> (project/list-projects)
       (keep (fn [[pname _]]
               (try {:project (name pname) :grouped (grouped pname (live-session-names pname))}
                    (catch Throwable _ nil))))
       vec))

(defn- scope-keep
  "Keep only entries whose :project matches `scope` (no-op on \"all\")."
  [scope xs] (if (= "all" scope) xs (filterv #(= scope (:project %)) xs)))

(defn screen
  "The single pure derivation from view-state to the screen-model. Every render
   site (full page + SSE poll, overview + detail) renders a slice of THIS value,
   so they cannot disagree. `data` injects what only IO can produce:
     :groups  (all-grouped)  :gates (all-gates)  :pending (#{\"project/ws-id\"} optimistic bridge keys)
     :winddown-pending (#{\"project/ws-id\"} optimistic bridge keys for pending bring-down!s).
   Selection detail is attached by the caller (needs work/workstream + dev-states).

   NO row filtering: every workstream the model emits is reachable from the
   surface. The board's tabs select BANDS, not rows — filtering here is what hid
   every :in-progress row behind the source chip's `source=notion` default.
   `:tab` is passed through verbatim for the surface to render; defaulting it is
   view-state's job, not the core's (borrowing that default is what pulled a UI
   require into this namespace)."
  [{:keys [scope tab] :or {scope "all"}}
   {:keys [groups gates pending winddown-pending]
    :or {groups [] gates [] pending #{} winddown-pending #{}}}]
  (let [scoped     (->> (scope-keep scope groups)
                        (mapv (fn [{:keys [project] :as g}]
                                (update-in g [:grouped :winding-down]
                                           (fn [rows]
                                             (mapv #(assoc % :pending?
                                                           (contains? winddown-pending
                                                                      (str project "/" (:ws-id %))))
                                                   rows))))))
        kept-gates (->> (scope-keep scope gates)
                        (mapv (fn [g] (assoc g :pending?
                                             (or (boolean (:working? g))
                                                 (contains? pending (str (:project g) "/" (:ws-id g))))))))]
    {:scope       scope
     :tab         tab
     :groups      scoped
     :gates       kept-gates
     :needs-count (count kept-gates)}))
