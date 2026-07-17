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
   [nido.project :as project]
   [nido.session.lifecycle :as lifecycle]))

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
   here. Their union is every row `grouped-rows` emits — a workstream is always
   reachable from exactly one tab, which is the guarantee that nothing can be
   hidden by default (a source filter defaulting to :notion once hid every
   :in-progress row). An unrecognized `tab` reads as :intake."
  [tab grouped]
  (->> (case tab
         :active [[:shipping    (:shipping grouped)]
                  [:in-progress (:in-progress grouped)]]
         [[:triage   (concat (-> grouped :triage :in-flight)
                             (-> grouped :triage :queued))]
          [:incoming (:incoming grouped)]])
       (into [] (keep (fn [[stage rows]] (when (seq rows) [stage (vec rows)]))))))

(defn gate-actions
  "Follow-actions for a gate, derived from its spine `stage`, whether a session is
   `parked?`, and its `origin` (Dismiss is dropped for :notion triage rows — Notion
   drives the board, so a local dismiss no longer hides them; kept for Slack).
   Each is a descriptor {:id :label :kind :style (:input)}:
     :kind :mutation -> one-click button, resolved nido-side (resolve-gate! on :id).
     :kind :resume   -> resume the parked agent. With :input it renders a one-click
                        button carrying that canned input (e.g. Apply -> \"apply\");
                        without :input it renders the free-text reply textarea.
   :style is a render hint (:primary | :danger | :default)."
  ([stage parked?] (gate-actions stage parked? nil))
  ([stage parked? origin]
   (case stage
     :incoming    [{:id :promote :label "Promote" :kind :mutation :style :primary}
                   {:id :drop    :label "Dismiss" :kind :mutation :style :danger}]
     :triage      (let [dismiss  {:id :dismiss :label "Dismiss" :kind :mutation :style :danger}
                        dismiss? (not= :notion origin)]
                    ;; Apply finalizes the verdict nido-side (ticket:complete — no
                    ;; conversation, so it works for legacy pre-notion-cli reviews too);
                    ;; Reply (free-text overrides/redo) resumes the agent; Dismiss takes
                    ;; it off the radar nido-side (dropped for Notion — Notion drives it).
                    (if parked?
                      (into [{:id :apply :label "Apply" :kind :mutation :style :primary}]
                            (concat (when dismiss? [dismiss])
                                    [{:id :reply :label "Reply" :kind :resume :style :default}]))
                      (if dismiss? [dismiss] [])))
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

(defn workstream
  "Full detail for one workstream: origin, spine stage, label, a light ledger
   facet, a newest-first entry INDEX (nil when ≤1 entry), the SELECTED entry's
   report (`selected-seq`, default latest, out-of-range → latest), `:on-latest?`
   (is the selected entry the current one — gates the pane's live actions), and
   its sessions on the autonomy axis. nil when the workstream is absent."
  ([project ws-id] (workstream project ws-id nil))
  ([project ws-id selected-seq]
   (when-let [w (cws/read-ws project ws-id)]
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
        :sessions     (mapv session-facet sessions)}))))

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
   STRING (the web routes on it, e.g. /gate/<project>/…; the dashboard's
   all-session-rows tags rows with the same string key) so a gate reads the same
   whether it came from `gates` (string or keyword arg) or `all-gates`."
  [project row]
  (let [parked? (= :parked-at-gate (:engagement row))
        psess   (parked-session project (:ws-id row))]
    {:ws-id        (:ws-id row)
     :project      (name project)
     :origin       (:origin row)
     :stage        (:stage row)
     :label        (:label row)
     :report       (latest-report project (:ws-id row))
     :actions      (gate-actions (:stage row) parked? (:origin row))
     :session      (:name psess)
     :resume-error (get-in psess [:autonomy :error])
     :working?     (resuming? project (:ws-id row))}))

(defn gates
  "A project's gates: workstreams that want you now (needs-you), each hydrated
   with its report + follow-actions. A SETTLED (closed) workstream is never a gate,
   even if a stale stage-override still projects :needs-you. `live-names` threads
   into the engagement projection (pass it so a downed one-off reads idle)."
  ([project] (gates project nil))
  ([project live-names]
   (->> (list-workstreams project live-names)
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
   Mirrors the dashboard's cross-project aggregation (see ui.server/all-session-rows).
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

(defn dismiss!
  "Take a workstream off the triage radar: record a :dismissed disposition on its
   ticket (so auto-re-triage skips it) and settle the workstream (:dropped, which
   frees its trigger's in-flight slot and removes it from the board). A ref-less
   workstream just closes. A workstream-less ws-id (e.g. a bare watched-view row)
   is a no-op: {:decision :no-workstream}. Returns {:decision :dismissed} otherwise."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    (do
      (when-let [br (:id (wsv/ledger-ref w))]
        (tickets/dismiss! project br))
      (cws/close! project ws-id :dropped)
      {:decision :dismissed})
    {:decision :no-workstream}))

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
          {:decision :error :error (:error created)}
          (let [br (:id created)]
            (cws/add-ref! project ws-id {:adapter :notion :id br
                                         :page-id (:page-id created) :url (:url created)})
            (try (slack/post-message channel (slack/keychain-token)
                   {:text (str "Ticket created: " (:url created)) :thread-ts ts})
                 (catch Throwable _ nil))   ; link-back is best-effort; the ticket already stands
            (tickets/complete! project slack-id :triaged :applied)
            {:decision :created :br br}))))))

(defn apply!
  "Accept a parked triage verdict WITHOUT resuming the review conversation. Two paths:

   • Slack proposal — when the latest ledger report is a `:proposed-ticket` and the
     workstream carries no :notion ref yet, deterministically create the Notion page,
     associate its BR-####, post the link back to Slack, and complete the slack-id
     record (see apply-proposed!). Returns {:decision :created :br …} | {:decision :error …}.

   • Legacy nido-only — otherwise finalize the ticket :triaged/:applied and refresh its
     facets — the exact nido-side mutation the current triage skill's apply step performs
     (`bb nido:ticket:complete`; the notion-cli migration dropped all Notion writeback).
     Replaces the old resume-\"apply\" path, which replayed the review conversation and
     FAILED for legacy reviews whose apply called the removed Notion MCP tools. A ref-less
     workstream is a no-op. Returns {:decision :applied}.

   The daemon's sweep settles the now-resolved parked session (ticket left review)."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    (let [report (latest-report project ws-id)]
      (if (and (= :proposed-ticket (:format report))
               (nil? (wsv/notion-ref w)))
        (apply-proposed! project ws-id report w)
        (do
          (when-let [br (:id (wsv/ledger-ref w))]
            (tickets/complete! project br :triaged :applied)
            (try (facets/refresh-for-ticket! project br)   ; reads Notion — best-effort
                 (catch Throwable _ nil)))
          {:decision :applied})))
    {:decision :applied}))

(defn resolve-gate!
  "Apply a gate follow-action, dispatching on `action-id`. A workstream-less ws-id
   (e.g. a bare watched-view row) is a no-op: {:decision :no-workstream}.
     :promote -> set-stage! :in-progress   :dismiss -> off-radar (ticket :dismissed + :dropped)
     :drop    -> close! :dropped            :done    -> set-stage! :done
     :apply   -> apply! (ticket:complete)   :reply   -> resume! the parked agent with `input`
   Returns the resolver's result map."
  ([project ws-id action-id] (resolve-gate! project ws-id action-id nil))
  ([project ws-id action-id input]
   (if (nil? (cws/read-ws project ws-id))
     {:decision :no-workstream}
     (case action-id
       :promote (set-stage! project ws-id :in-progress)
       :done    (set-stage! project ws-id :done)
       :dismiss (dismiss! project ws-id)
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
          (:shipping grouped)))

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

(defn all-grouped
  "[{:project <string> :grouped <grouped-map>} …] across every registered
   project (mirrors all-gates). A project that can't be read contributes
   nothing rather than failing the board."
  []
  (->> (project/list-projects)
       (keep (fn [[pname _]]
               (try {:project (name pname) :grouped (grouped pname)}
                    (catch Throwable _ nil))))
       vec))

(defn- scope-keep
  "Keep only entries whose :project matches `scope` (no-op on \"all\")."
  [scope xs] (if (= "all" scope) xs (filterv #(= scope (:project %)) xs)))

(defn screen
  "The single pure derivation from view-state to the screen-model. Every render
   site (full page + SSE poll, overview + detail) renders a slice of THIS value,
   so they cannot disagree. `data` injects what only IO can produce:
     :groups  (all-grouped)  :gates (all-gates)  :pending (#{\"project/ws-id\"} optimistic bridge keys).
   Selection detail is attached by the caller (needs work/workstream + dev-states).

   NO row filtering: every workstream the model emits is reachable from the
   surface. The board's tabs select BANDS, not rows — filtering here is what hid
   every :in-progress row behind the source chip's `source=notion` default.
   `:tab` is passed through verbatim for the surface to render; defaulting it is
   view-state's job, not the core's (borrowing that default is what pulled a UI
   require into this namespace)."
  [{:keys [scope tab] :or {scope "all"}}
   {:keys [groups gates pending] :or {groups [] gates [] pending #{}}}]
  (let [scoped     (scope-keep scope groups)
        kept-gates (->> (scope-keep scope gates)
                        (mapv (fn [g] (assoc g :pending?
                                             (or (boolean (:working? g))
                                                 (contains? pending (str (:project g) "/" (:ws-id g))))))))]
    {:scope       scope
     :tab         tab
     :groups      scoped
     :gates       kept-gates
     :needs-count (count kept-gates)}))
