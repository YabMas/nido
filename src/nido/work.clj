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
   [nido.coordinator.clock :as clock]
   [nido.coordinator.facets :as facets]
   [nido.coordinator.notion-cache :as notion-cache]
   [nido.coordinator.pickup :as pickup]
   [nido.coordinator.promote :as promote]
   [nido.coordinator.report :as report]
   [nido.coordinator.resume :as resume]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.scratch :as scratch]
   [nido.coordinator.session :as csession]
   [nido.coordinator.spawn :as spawn]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.triggers :as triggers]
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
  "The canonical spine, in order — session/lifecycle-stages, sequenced. A PR merge
   is the event that advances :shipping → :done; :shipping is the merge-pipeline
   stage entered by `nido ship`.

   These are STORED stage names, because both readers turn them into one: the TUI
   stage picker feeds set-stage! straight from this vector, and default-target
   validates a project's configured override against it. The head used to read
   :intake — the name of the TAB that holds the :triage and :incoming bands
   (tab-bands), never a stage any record carries. Picking it wrote a stage
   grouped-by-stage has no key for, and the workstream dropped off every band."
  [:incoming :triage :ready :in-progress :shipping :done])

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

   :active's trailing band is :winding-down — finished workstreams still holding
   live resources, whether nido settled them or Notion did (bring-down! is their
   one action)."
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

(def ^:private workstream-less-actions
  "Gate actions that are meaningful on a bare watched-view row — one with no
   workstream of its own, only a Notion page and a ticket record. Each carries its
   own workstream-less branch AND its own :no-workstream refusal, so the read-ws
   guard in resolve-gate! must route them BEFORE it, or the only actions such a
   row offers become silent no-ops.
   :start-triage — the Notion page is exactly what it spawns from.

   Also the filter gate-actions applies to a bare row's per-stage action set:
   anything outside this set (:promote, :drop, :done, :apply, :reply) routes to
   set-stage!/apply!/resume! and hits resolve-gate!'s read-ws guard, so offering
   it would be a button that can only ever no-op.

   The membership is read in both directions, so adding an id here has TWO
   effects at once: it lets resolve-gate! route the action before the guard,
   AND it silently starts OFFERING that action on every bare-row stage whose
   per-stage case emits it (gate-actions' filter) — so it needs its own
   workstream-less branch and :no-workstream refusal before it is added."
  #{:restore :dismiss :start-triage})

(def option-action-ids
  "Gate action id per blocker-option position — :option-a … :option-f. Fixed ids
   rather than an encoded payload: a click POSTs an id and nothing else, so the
   text the agent is resumed with is built HERE, from the ledger, at click time
   (choose-option! → option-input). Same reasoning as approval-input — an answer
   the browser could compose is an answer that can arrive saying anything."
  (mapv #(keyword (str "option-" (str/lower-case %))) report/option-letters))

(def ^:private option-index
  "Action id → the position it selects."
  (into {} (map-indexed (fn [i id] [id i])) option-action-ids))

(defn option-action?
  "True for an :option-<letter> gate action id."
  [action-id]
  (contains? option-index action-id))

(defn- option-actions
  "One button per branch of a blocker's :options, lettered by position. `:kind
   :mutation` because it is resolved nido-side, like :approve — the descriptor
   carries no input for the browser to send. Order is the record's order, so the
   letters here and the letters on the card cannot disagree.

   `entry-seq` is the ledger position of the report these were built from. It
   rides on every button and comes back with the click, because a LETTER ALONE
   IS NOT AN ANSWER: it only means something relative to the question that was on
   screen, and by click time the ledger may hold a different one (the agent
   appended, another tab answered). choose-option! refuses on the mismatch.
   Omitted when unknown, which fails closed there rather than resolving blind."
  [entry-seq options]
  (vec (map-indexed
        (fn [i {:keys [label recommended?]}]
          (cond-> {:id    (nth option-action-ids i)
                   :label (str (report/option-letter i) " — " label)
                   :kind  :mutation
                   :style (if recommended? :primary :default)}
            entry-seq (assoc :seq entry-seq)))
        options)))

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
   The arg stays for call-site compatibility.

   `{:options [...]}` is the CURRENT report's blocker options, when it has any:
   a parked gate whose latest entry named its branches offers one button per
   branch (option-actions) instead of only a textarea. The call sites read
   :report-format, :options AND :seq off the SAME report the reader is looking at,
   so the buttons and the card can never disagree about the question — and :seq
   is what lets the resolver notice that the question itself has since changed.

   `{:bare? true}` marks a row with no workstream behind it (wsv/bare-row). On
   :triage that swaps Apply/Reply — which need an agent — for :start-triage. Then,
   for EVERY stage, a bare row's action set is filtered down to
   workstream-less-actions — the ids resolve-gate! can actually act on without a
   workstream. A bare row reaches :triage, :ready, :in-progress or :done
   (session/notion-stage's range) — OR :dismissed, folded in ahead of that range
   by to-spine's :dismissed? check (not by notion-stage) once the ticket is
   vetoed; without this filter a bare :ready row would offer Promote/Drop, both
   of which can only return {:decision :no-workstream}. Filtering the existing
   per-stage result (rather than adding a bare branch to every stage) keeps this
   correct automatically if the action sets change."
  ([stage parked?] (gate-actions stage parked? nil nil))
  ([stage parked? origin] (gate-actions stage parked? origin nil))
  ([stage parked? _origin {:keys [bare? report-format options] entry-seq :seq}]
   (let [actions
         (case stage
           :incoming    [{:id :promote :label "Promote" :kind :mutation :style :primary}
                         {:id :drop    :label "Dismiss" :kind :mutation :style :danger}]
           :triage      (let [dismiss {:id :dismiss :label "Dismiss" :kind :mutation :style :danger}]
                          ;; Apply executes the routed verdict to Notion nido-side (Ball Holder +
                          ;; App Domain, deep properties/callout — apply-routed!, no conversation),
                          ;; falling back to nido-only ticket:complete for legacy/Slack reports;
                          ;; Reply (free-text overrides/redo) resumes the agent; Dismiss takes it
                          ;; off the radar nido-side, writing nothing to Notion.
                          (cond
                            ;; A bare row has no workstream and therefore no agent to Apply or
                            ;; Reply to — the only forward move is to start the triage that
                            ;; never ran.
                            bare?   [{:id :start-triage :label "Start triage"
                                      :kind :mutation :style :primary}
                                     dismiss]
                            parked? [{:id :apply :label "Apply" :kind :mutation :style :primary}
                                     dismiss
                                     {:id :reply :label "Reply" :kind :resume :style :default}]
                            :else   [dismiss]))
           ;; The nido-side veto, reversible: Restore clears the ticket status so the row
           ;; rejoins the triage queue and the auto-triage gate can pick it up again.
           :dismissed   [{:id :restore :label "Restore" :kind :mutation :style :default}]
           :ready       [{:id :promote :label "Promote" :kind :mutation :style :primary}
                         {:id :drop    :label "Drop"    :kind :mutation :style :danger}]
           :in-progress (cond
                          (not parked?) []
                          ;; A parked session whose latest entry is a design decision
                          ;; is asking ONE question — the irreducible judgement the
                          ;; round could not derive. So the gate asks one question:
                          ;; grant the approval, or send it back. Done is deliberately
                          ;; absent here; settling a workstream is not an answer to
                          ;; "should we build this", and offering it beside Approve
                          ;; invites it to be read as one.
                          ;;
                          ;; No new stage: this is the SAME :in-progress gate, told
                          ;; apart by what the ledger holds last. Widening the spine
                          ;; for it would put every reader's band mapping at risk for
                          ;; something the report already distinguishes.
                          (= :design-decision report-format)
                          [{:id :approve :label "Approve" :kind :mutation :style :primary}
                           {:id :reply   :label "Reply"   :kind :resume  :style :default}]

                          ;; A blocker that named its branches is the same shape of
                          ;; gate: one question, answerable. Each option is a button;
                          ;; Reply stays for the answer that is none of them ("B, but
                          ;; keep the i18n key"). Done is absent for the reason it is
                          ;; absent above — settling the workstream is not an answer
                          ;; to the question, and beside A/B it reads as one.
                          (seq options)
                          (conj (option-actions entry-seq options)
                                {:id :reply :label "Reply" :kind :resume :style :default})

                          :else
                          [{:id :reply :label "Reply" :kind :resume :style :default}
                           {:id :done  :label "Done"  :kind :mutation :style :primary}])
           :shipping    (if parked?
                          ;; Blocked in the merge lane: any named options first (a
                          ;; drive-home halt is where they most often appear), then
                          ;; Reply to resume the agent with a note; Drop takes it off
                          ;; the queue (back to :in-progress). The usual path is to fix
                          ;; in the worktree and `nido ship` again.
                          (conj (option-actions entry-seq options)
                                {:id :reply :label "Reply" :kind :resume   :style :default}
                                {:id :drop  :label "Drop"  :kind :mutation :style :danger})
                          [])
           [])]
     (if bare?
       (filterv #(contains? workstream-less-actions (:id %)) actions)
       actions))))

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
  "Workstreams of `project` that are FINISHED and still holding ≥1 live session —
   resources you're paying for on work that is over. Never gates (:needs-you
   false); rendered as the Active tab's trailing band with one action:
   bring-down!. Empty when live-names is empty/nil.

   Finished has two carriers, and both are needed. A :closed record is nido's own
   settlement. `rows` (from list-workstreams) supplies the other: a row projecting
   :done, which is where a Notion-driven workstream lands the moment its ticket
   reaches a terminal status — Review included. That projection is stateless by
   design (it self-heals if the ticket bounces back), so it never touches the
   record, and keying this band on :closed alone would drop such a row off BOTH
   tabs while its session still held ports. That is precisely the silent loss
   tab-bands' reachability guarantee exists to prevent. Omit `rows` (or pass nil)
   for the :closed-only projection.

   A row surfaced by the projection has no :closed record to name an outcome, so
   it reports :done — the stage the board put it in."
  ([project live-names] (winding-down project live-names nil))
  ([project live-names rows]
   (let [live     (set live-names)
         done-ids (into #{} (comp (filter #(= :done (:stage %))) (map :ws-id)) rows)]
     (if (empty? live)
       []
       (->> (cws/list-ids project)
            (keep #(cws/read-ws project %))
            (filter #(or (:closed %) (contains? done-ids (:id %))))
            (keep (fn [w]
                    (let [sessions (csession/list-sessions project (:id w))
                          live-s   (filterv #(contains? live (:name %)) sessions)]
                      (when (seq live-s)
                        {:ws-id     (:id w)
                         :project   (name project)
                         :stage     :winding-down
                         :origin    (classify-origin w)
                         :label     (wsv/label w sessions)
                         :outcome   (or (get-in w [:closed :outcome]) :done)
                         :sessions  (mapv :name live-s)
                         :needs-you false}))))
            vec)))))

(defn grouped
  "Workstreams grouped along the single spine for the board:
   {:triage {:in-flight [..] :queued [..]} :ready [..] :in-progress [..]}.
   Scratch one-offs fold into :in-progress (done via list-workstreams' remap);
   :done is omitted. The board renders these groups directly."
  ([project] (grouped project nil))
  ([project live-names]
   (let [rows (list-workstreams project live-names)]
     (assoc (wsv/grouped-by-stage rows)
            ;; rows, not just the records: a Notion-driven row can be finished
            ;; without nido ever writing :closed (see winding-down).
            :winding-down (winding-down project live-names rows)))))

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
   (report/parse-event — the read contract, which accepts any shape that was
   writable when the entry was written), :seq/:at stamped from the entry (the same
   stamp cws/latest-entry applies); any other file is markdown
   (:format :markdown). A typed `.edn` that fails to read/validate degrades to a
   :markdown payload of its raw text rather than blanking the pane.

   :seq is load-bearing, not decoration: it is the ledger position a rendered
   gate binds its buttons to, so a click can be checked against the report that
   drew it (option-actions -> choose-option!)."
  [base-dir entry]
  (let [f    (str (fs/path base-dir (:file entry)))
        edn? (str/ends-with? (str (:file entry)) ".edn")]
    (or (when edn?
          (try (-> (report/parse-event (:kind entry) (io/read-edn f))
                   (assoc :seq (:seq entry) :at (:at entry)))
               (catch Throwable _ nil)))
        (let [md (when (fs/exists? f) (slurp f))]
          (cond-> {:format   :markdown
                   :kind     (:kind entry)
                   :seq      (:seq entry)
                   :at       (:at entry)
                   :title    (first-heading md)
                   :markdown md}
            ;; A typed entry that would not read is NOT freeform markdown, and
            ;; rendering it as if it were is the same conflation this codebase
            ;; keeps having to undo: the reader cannot tell a corrupt record from
            ;; one it is simply too old to understand. Unmerged stacks writing
            ;; kinds the running daemon has never heard of is a NORMAL condition
            ;; here — the daemon reads src/ once at startup — so the honest answer
            ;; is the common one, and it says which of the two it is.
            edn? (assoc :degraded
                        {:kind   (:kind entry)
                         :reason (if (contains? report/event-schemas (:kind entry))
                                   :schema-mismatch
                                   :unknown-kind)}))))))

(defn- review-detail
  "The review-loop's own report.json — target, rounds, per-round phases and their
   findings — for a `:review-report` event. The ledger event carries the verdict
   and the counts and POINTS at the report (report/ReviewReport, deliberately: the
   rounds are too big to embed), so a surface that wants the rounds hydrates them
   at read time. nil when the event points nowhere, the run dir has since been
   cleaned, or the file won't parse — the reader then shows the counts alone."
  [{:keys [report-path]}]
  (when-not (str/blank? report-path)
    (try (io/read-json report-path)
         (catch Throwable _ nil))))

(defn- hydrate
  "Attach the read-time detail a typed event points at but does not embed. Only
   `:review-report` has any today; every other format passes through untouched.
   Applied where ONE report is rendered (report-at / latest-report), never in
   index-row — an index needs titles, not every round of every review."
  [report]
  (if (= :review-report (:format report))
    (assoc report :detail (review-detail report))
    report))

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
  "The entry whose :seq is `seq`, rendered via entry->report and hydrated with
   whatever detail it points at. Callers select `seq` from the entries themselves,
   so a miss means the ledger changed under the read — nil, not the latest entry
   silently swapped in."
  [base-dir entries seq]
  (let [by-seq (into {} (map (juxt :seq identity)) entries)]
    (some->> (get by-seq seq) (entry->report base-dir) hydrate)))

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
      (hydrate (entry->report base-dir (last entries)))
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
   that no workstream covers (wsv/bare-row). Routes the row through to-spine —
   the same fold list-workstreams and work/workstream apply to every other
   row — so the pane's :stage can never disagree with the board list's band,
   including the :dismissed fold a raw bare-row read would miss.

   Carries explicit nils for the ledger/report/environment keys the full pane
   renders: there is nothing behind any of them, and the pane's :bare? branch
   skips those blocks outright. `fct` is the cache entry, passed in so the caller
   reads project-page-facts once."
  [project page-id fct]
  (let [row (to-spine (wsv/bare-row project page-id fct))]
    {:ws-id         page-id
     :project       project
     :origin        (:origin row)
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
   facet, a newest-first entry INDEX, the report of the entry `selected-seq` names
   (nil — nothing open — unless it names one), `:action-report` (the CURRENT report,
   which is what the live actions derive from — see the key), `:on-latest?` (is the
   open entry the current one — gates the pane's live actions), `:environment` (the
   one current session — `work/environment`), and its sessions on the autonomy axis.
   nil when the workstream is absent.

   Nothing is selected by default: the ledger index is the pane's resting state and
   opening an entry is the reader's choice, so `selected-seq` nil (and a seq no
   entry carries — a stale link, or one the ledger has since dropped) both read as
   'nothing open' rather than quietly falling back to the newest entry. For the
   same reason the index renders whenever there ARE entries, single-entry ledgers
   included — it is the only way to reach one."
  ([project ws-id] (workstream project ws-id nil))
  ([project ws-id selected-seq]
   (if-let [w (cws/read-ws project ws-id)]
     (let [sessions (csession/list-sessions project ws-id)
           ;; Pass the Notion cache so the pane derives stage exactly like the board
           ;; list (workstream-rows) — else the pane goes Notion-driven vs legacy and
           ;; they disagree (e.g. a promoted ticket: list :ready, pane :in-progress).
           row      (to-spine (wsv/workstream-row project w nil (notion-cache/project-page-facts project)))
           {:keys [base-dir entries]} (active-ledger project ws-id)
           sel      ((set (map :seq entries)) selected-seq)
           index    (when (seq entries)
                      (vec (reverse (mapv #(index-row base-dir %) entries))))
           latest?  (or (nil? sel) (= sel (:seq (last entries))))
           ;; The current report, derived from the snapshot `entries` ALREADY
           ;; holds rather than re-read from disk. A second read is a second
           ;; moment: an entry landing between them leaves :on-latest? true with
           ;; the viewer showing one question and the action bar — and the :seq
           ;; its option buttons carry — built from the next one, which is
           ;; precisely the card/button binding the seq check exists to enforce.
           ;; Only the empty-ledger case still reads (there is no entry to bind
           ;; to, and latest-report is where the intake-text fallback lives).
           latest-r (if (seq entries)
                      (hydrate (entry->report base-dir (last entries)))
                      (latest-report project ws-id))]
       {:ws-id        ws-id
        :project      project
        :origin       (classify-origin w)
        :stage        (:stage row)
        :label        (:label row)
        :links        (:links row)
        :ledger       (ledger-summary project (:br-id row))
        :entries      index
        :selected-seq sel
        ;; Nothing open, or the open entry is the CURRENT one. Live actions are
        ;; offered only on the current entry — older entries are an immutable
        ;; read-back, not something you act on — and the resting pane, which is
        ;; reading nothing at all, acts on the workstream as it stands.
        :on-latest?   latest?
        ;; A ledger renders only what the reader opened. With no ledger at all,
        ;; the intake text is the pane's only content, so it stands in unasked.
        :report       (if (seq entries)
                        (when sel (report-at base-dir entries sel))
                        latest-r)
        ;; What the pane's live ACTIONS derive from — which is not what the
        ;; viewer is showing. The resting pane has nothing open (:report nil) and
        ;; still acts on the workstream as it stands, so a parked blocker's
        ;; branches must reach the action bar without the reader first opening
        ;; the entry; deriving from :report there would offer generic Reply/Done —
        ;; including the Done the option branch deliberately withholds — for a
        ;; question that named its answers. nil once an OLDER entry is open:
        ;; :on-latest? is false there and no actions render at all.
        :action-report (when latest? latest-r)
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
        psess   (parked-session project (:ws-id row))
        ;; Read once: the action set is derived from what the ledger holds last,
        ;; so the buttons and the report a reader is looking at cannot disagree.
        report  (latest-report project (:ws-id row))]
    {:ws-id        (:ws-id row)
     :project      (name project)
     :origin       (:origin row)
     :stage        (:stage row)
     :label        (:label row)
     :links        (:links row)
     :report       report
     :actions      (gate-actions (:stage row) parked? (:origin row)
                                 {:report-format (:format report)
                                  :options       (:options report)
                                  :seq           (:seq report)})
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

(defn- triage-trigger
  "The project's triage trigger: the first in triggers.edn whose :skill is
   :triage-bug, or nil. On brian that is :triage-new — :smoke-new-reports watches
   the same Notion view but carries :skill :investigate-bug, so it is never
   selected. First-wins if a project ever configures two."
  [project]
  (->> (triggers/load-for-project project)
       (filter #(= :triage-bug (:skill %)))
       first))

(defn start-triage-page!
  "Force-start a triage for a bare watched-view row, whose ws-id IS the Notion
   page-id. The triage that should have run never did (the page predates the
   watcher, or its trigger was skipping), so there is no workstream and no ledger
   — this is the one action that creates them.

   Resolves the page FRESH from Notion (pickup/resolve-ref) rather than
   synthesising the payload from the cache: the cache carries no :url, and the
   trigger's own template needs {{event/title}} and {{event/page-id}}, so the
   payload has to look exactly like a poller emit.

   FORCE, deliberately: this calls spawn-and-submit! directly, bypassing the
   whole routing pipeline coordinator.core/process-envelope! normally applies
   before a spawn — trigger :filter, breaker, :dry-run?, :intake queue mode,
   and tickets/gate-decision — and reproducing only its
   ref-has-pending-session? dedup. One ticket, one deliberate click. A
   :triage-bug trigger configured with any of those other checks would still
   fire here, unfiltered.

   ref-has-pending-session? only suppresses a REPEAT click once a first spawn
   has already persisted its session — it is not a race guard: a bare row has
   no workstream on click #1 (nothing for find-by-ref to resolve), so two
   clicks landing inside spawn-records!'s own ensure-workstream! →
   create-session-for-run! window can both spawn.

   Does network I/O — callers run it off the render thread (ui.server/gate-resolve!
   already resolves gate actions on a future). Returns {:decision :triaging} |
   {:decision :no-trigger} | {:decision :already-in-flight} |
   {:decision :unresolved :error <kw>}."
  [project ws-id]
  (if-let [t (triage-trigger project)]
    (let [ref (pickup/resolve-ref project ws-id (notion/keychain-token))]
      (if (:error ref)
        {:decision :unresolved :error (:error ref)}
        (let [;; Same six-key contract create-run! expects (runs.clj:233) —
              ;; promote/start-triage! (promote.clj:90-98) builds this
              ;; identical map from the same contract; change both together.
              routed {:project         project
                      :trigger         t
                      :payload         ref
                      :priority        (or (:priority t) 0)
                      :session-profile (:session-profile t)
                      :uncapped?       (boolean (:uncapped? t))}]
          (if (spawn/ref-has-pending-session? routed)
            {:decision :already-in-flight}
            (do (cstate/ensure-dirs!)
                (spawn/spawn-and-submit! routed {:fired-at (clock/now-iso)
                                                 :fired-by "board"})
                {:decision :triaging})))))
    {:decision :no-trigger}))

(def approval-input
  "What Approve resumes the parked agent with. Built nido-side rather than sent
   from the browser, because the server only carries free text for :reply — and
   because an approval should say the same thing every time it is granted.

   It names what was approved. The decision round adjudicates claims and
   layering, never a plan, and that distinction is what keeps this gate an
   approval to proceed rather than a freeze: the most valuable design thoughts
   arrive from inside the code, and a design frozen at the gate cannot receive
   them. So the resume says so, and points at the amend path rather than leaving
   the agent to infer that the record is now untouchable."
  (str "The design decision was APPROVED by a human. The claims and the layering "
       "are what was approved — not a plan, and not a freeze. Proceed with "
       "implementation. If the shape turns out not to hold once you are inside "
       "the code, amend or supersede the design record (/design §5) rather than "
       "patching around it."))

(defn option-input
  "What choosing option `i` resumes the parked agent with. Built nido-side from
   the ledger, for the same reason as approval-input: the browser sends an id,
   never prose, so an answer cannot arrive saying something the record never
   offered.

   It repeats the branch back in full — label, summary, consequence — because the
   agent is resumed in a fresh turn that can no longer see the gate, and 'the
   human picked B' is not an instruction. The last sentence is the one that keeps
   the gate honest: an option that turns out not to hold is a NEW blocker, not a
   licence to take the other branch on the human's behalf."
  [i {:keys [label summary consequence]}]
  (str "A human answered the blocker at the gate: option " (report/option-letter i)
       " — " label ". " summary
       (when consequence (str " " consequence))
       " Proceed on that basis. Do not re-open the choice or switch to another"
       " option yourself: if this one turns out not to hold once you are inside"
       " the code, record a new :blocker naming what broke and park again."))

(defn- choose-option!
  "Resume the parked agent with the branch `action-id` selects, resolved against
   the workstream's CURRENT latest report — never against what the browser was
   showing. A click carries a letter and `entry-seq`, the ledger position of the
   report that letter was rendered from; the ledger decides what the letter meant,
   and the position decides whether that question is still the one being asked.

   Both checks are needed, and the position is the one that matters. A letter
   alone silently resolves against WHATEVER is latest at click time: if another
   tab answered the blocker and the agent parked on a new one with as many
   branches, :option-a would pick branch A of a question the human never read.
   So a click whose position is not the latest entry — or which carries none at
   all — is refused, not resolved."
  [project ws-id action-id entry-seq]
  (let [report (latest-report project ws-id)
        i      (option-index action-id)]
    (if-let [opt (and (some? entry-seq)
                      (= entry-seq (:seq report))
                      (get (vec (:options report)) i))]
      (resume/resume! project ws-id (option-input i opt))
      {:decision :option-stale})))

(defn resolve-gate!
  "Apply a gate follow-action, dispatching on `action-id`. A workstream-less ws-id
   (e.g. a bare watched-view row) is a no-op — {:decision :no-workstream} — for
   every action but :restore, :dismiss and :start-triage, which such a row
   legitimately offers (see workstream-less-actions).
     :promote -> set-stage! :in-progress   :dismiss -> off-radar (ticket + ws :dismissed)
     :drop    -> close! :dropped            :done    -> set-stage! :done
     :apply   -> apply! (ticket:complete)   :reply   -> resume! the parked agent with `payload`
     :approve -> resume! the parked agent with `approval-input` (a design gate)
     :option-a … :option-f -> resume! with the blocker branch that letter names,
                              iff `payload` still names the latest report
     :restore -> restore! (clear ticket status + reopen at :triaging)
     :start-triage -> start-triage-page! (force-spawn the triage trigger)

   `payload` is whatever the click carried besides its id, and what that is
   depends on the action: the reply text for :reply, the :seq of the report the
   button was rendered from for :option-*. Every other action resolves entirely
   nido-side and ignores it.
   Returns the resolver's result map."
  ([project ws-id action-id] (resolve-gate! project ws-id action-id nil))
  ([project ws-id action-id payload]
   (cond
     ;; Bare-row-capable actions run before the guard (see workstream-less-actions).
     (contains? workstream-less-actions action-id)
     (case action-id
       :restore      (restore! project ws-id)
       :dismiss      (dismiss! project ws-id)
       :start-triage (start-triage-page! project ws-id))

     (nil? (cws/read-ws project ws-id)) {:decision :no-workstream}

     ;; Not a `case` branch: the option ids are derived from report/option-letters,
     ;; and case needs literals — spelling six of them here is a second copy of
     ;; that vector, free to drift from the one the buttons are built from.
     (option-action? action-id) (choose-option! project ws-id action-id payload)

     :else
     (case action-id
       :promote (set-stage! project ws-id :in-progress)
       :done    (set-stage! project ws-id :done)
       :drop    (do (cws/close! project ws-id :dropped) {:decision :dropped})
       :apply   (apply! project ws-id)
       :reply   (resume/resume! project ws-id payload)
       :approve (resume/resume! project ws-id approval-input)
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
