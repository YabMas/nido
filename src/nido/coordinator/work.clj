(ns nido.coordinator.work
  "The work-plane core: the single vocabulary every surface (TUI, web) wraps.

   Sits ABOVE the coordinator record layer (nido.coordinator.record.workstream/.session/
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
   [nido.platform.config :as config]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.lane.facets :as facets]
   [nido.coordinator.lane.findings :as findings]
   [nido.coordinator.source.notion-cache :as notion-cache]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.lane.pickup :as pickup]
   [nido.coordinator.lane.promote :as promote]
   [nido.coordinator.report :as report]
   [nido.coordinator.lane.resume :as resume]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.lane.scratch :as scratch]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.lane.spawn :as spawn]
   [nido.coordinator.record.proposal :as proposal]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.triggers :as triggers]
   [nido.platform.io :as io]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as cws]
   [nido.coordinator.view.workstreams :as wsv]
   [nido.notion.client :as notion]
   [nido.notion.views :as views]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.slack.client :as slack]
   [nido.platform.process :as proc]
   [nido.platform.project :as project]
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

(defn ^{:malli/schema [:=> [:cat :keyword :map] :any]}
  tab-bands
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

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  option-action?
  "True for an :option-<letter> gate action id."
  [action-id]
  (contains? option-index action-id))

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  position-carrying-action?
  "True for a gate action whose button is rendered with the ledger position it
   was read at, and whose resolver refuses a click that no longer names the
   ledger's latest entry.

   It answers a question about `gate-actions`' output, so it lives beside it: a
   surface that decided for itself which ids carry a position would be holding a
   second copy of this, free to disagree — which is exactly what happened. The
   web read the position back for option buttons only, so :approve arrived nil
   at `approve!`, took the stale branch, and no design was ever approved from the
   dashboard.

   Anything added here needs the same two things an option button has: a :seq on
   its descriptor, and a resolver that compares it before acting."
  [action-id]
  (or (option-action? action-id)
      (= :approve action-id)))

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
                   ;; The LETTER alone. The branch is spelled out on the card
                   ;; directly above, which is the whole reason the options are
                   ;; lettered: the reader picks by matching a letter, not by
                   ;; re-reading the branch on a button. Putting the label here
                   ;; too made a row of buttons as wide as three sentences.
                   :label (report/option-letter i)
                   ;; …but a button reading "A" says nothing on its own once the
                   ;; card scrolls away, so the branch rides along as the hover
                   ;; title, where it costs no layout.
                   :title label
                   :kind  :mutation
                   :style (if recommended? :primary :default)}
            entry-seq (assoc :seq entry-seq)))
        options)))

(defn ^{:malli/schema [:=> [:cat :keyword :boolean [:? :any] [:? :map]] :any]}
  gate-actions
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

   `{:awaiting <stage>}` is the stage the pipeline says a PERSON owes this
   workstream, or nil — `(:stage (:next position))` when that position's mode is
   :human. It is what lets a design decision be granted: the round that writes
   one is mechanical and parks nobody, so `parked?` is false exactly when the
   grant is due.

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
  ([stage parked? _origin {:keys [bare? report-format options awaiting grantable?] entry-seq :seq}]
   (let [;; The branches of the CURRENT blocker, or [] — offered whether or not a
         ;; session is parked, because answering no longer depends on one being
         ;; alive to hear it (choose-option! records the answer on the ledger and
         ;; resumes only if there is someone to resume). Reply, Apply and Done
         ;; stay gated on parked?: each one resumes an agent, and offering it
         ;; with none is a button that can only fail.
         answers (option-actions entry-seq options)
         ;; Approve is not one of those, which is what `awaiting` is for. It asks
         ;; the PIPELINE whether a person owes this workstream a decision, and
         ;; the board stage cannot answer that: the round that produces a design
         ;; decision is :mechanical, so it runs as a task with no agent, and the
         ;; workstream that most needs this button is exactly the one no session
         ;; is parked on. approve! has always accepted a grant with nobody to
         ;; wake — it records it and answers :approved-unresumed, because the
         ;; ledger is what the next session reads — so withholding the button was
         ;; the only thing keeping the grant out of reach.
         ;;
         ;; Composed here rather than inside the case because it belongs to the
         ;; position rather than to a band: the same question is owed whatever
         ;; stage the board projects the workstream into.
         ;;
         ;; Approve carries the ledger position it was rendered from, exactly as
         ;; an option button does and for the same reason one round further on: a
         ;; grant made against a stale page is not a grant about what is there
         ;; now. Omitted when unknown, which fails closed in approve! rather than
         ;; granting blind.
         ;;
         ;; Done is deliberately absent beside it; settling a workstream is not
         ;; an answer to "should we build this", and offering it there invites it
         ;; to be read as one.
         ;; Gated on `grantable?`, which is the SAME reading approve! re-asks —
         ;; passed in because this is pure and cannot read a ledger. Only :proceed
         ;; is a question for a human: a round that answered :recut or :amend
         ;; judged the RECORD rather than whether to build it, and the next move
         ;; is the author's, so offering Approve there grants the design that
         ;; round had just sent back.
         ;;
         ;; Read off the LEDGER rather than off the report the button was
         ;; rendered from. The two are usually the same entry and come apart
         ;; exactly where it matters — a design told to proceed and later sent
         ;; back leaves a report whose recommendation is stale about what may now
         ;; be granted.
         approving? (and (= :design-decision report-format)
                         grantable?
                         (or parked? (= :approve-design awaiting)))
         actions
         (if approving?
           (cond-> [(cond-> {:id :approve :label "Approve"
                             :kind :mutation :style :primary}
                      entry-seq (assoc :seq entry-seq))]
             parked? (conj {:id :reply :label "Reply" :kind :resume :style :default}))
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
                            parked? (into answers
                                          [{:id :apply :label "Apply" :kind :mutation :style :primary}
                                           dismiss
                                           {:id :reply :label "Reply" :kind :resume :style :default}])
                            :else   (into answers [dismiss])))
           ;; The nido-side veto, reversible: Restore clears the ticket status so the row
           ;; rejoins the triage queue and the auto-triage gate can pick it up again.
           :dismissed   [{:id :restore :label "Restore" :kind :mutation :style :default}]
           :ready       [{:id :promote :label "Promote" :kind :mutation :style :primary}
                         {:id :drop    :label "Drop"    :kind :mutation :style :danger}]
           :in-progress (cond
                          ;; A blocker that named its branches is the same shape of
                          ;; gate: one question, answerable. Each option is a button;
                          ;; Reply stays for the answer that is none of them ("B, but
                          ;; keep the i18n key") — and only while someone is parked to
                          ;; hear it. Done is absent for the reason it is absent above:
                          ;; settling the workstream is not an answer to the question,
                          ;; and beside A/B it reads as one.
                          (seq answers)
                          (cond-> answers
                            parked? (conj {:id :reply :label "Reply" :kind :resume :style :default}))

                          (not parked?) []

                          :else
                          [{:id :reply :label "Reply" :kind :resume :style :default}
                           {:id :done  :label "Done"  :kind :mutation :style :primary}])
           ;; Blocked in the merge lane: any named branches first (a drive-home
           ;; halt is where they most often appear), then Reply to resume the agent
           ;; with a note and Drop to take it off the queue (back to :in-progress).
           ;; The usual path is to fix in the worktree and `nido ship` again. The
           ;; branches stand without a parked session; Reply and Drop do not.
           :shipping    (cond
                          (seq answers) (cond-> answers
                                          parked?
                                          (into [{:id :reply :label "Reply" :kind :resume   :style :default}
                                                 {:id :drop  :label "Drop"  :kind :mutation :style :danger}]))
                          parked?       [{:id :reply :label "Reply" :kind :resume   :style :default}
                                         {:id :drop  :label "Drop"  :kind :mutation :style :danger}]
                          :else         [])
           []))]
     (if bare?
       (filterv #(contains? workstream-less-actions (:id %)) actions)
       actions))))

(defn ^{:malli/schema [:=> [:cat :Workstream] :keyword]}
  classify-origin
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
                 ;; A workstream that holds a reading rather than work. It folds
                 ;; to a band the board does not draw, which is not the same as
                 ;; hiding it: it is reachable, and only reachable, from the
                 ;; operations surface, whose unit is the proposal inside the
                 ;; analysis rather than the workstream around it. Below
                 ;; :dismissed and :settled so a dismissed or closed one still
                 ;; reads that way — those say something about a human's
                 ;; decision, and this only says what kind of record it is.
                 (= :review-run origin)         :analysed
                 :else                          (:stage row))]
    (-> row
        (assoc :origin origin :stage stage)
        (dissoc :source))))

(def ^:private unrendered-bands
  "Bands the board does not draw a row for, and so does not need a position for.
   :done is dropped by grouped-by-stage outright; :dismissed renders its own
   muted row with one action and nothing about where the work stood; :analysed
   is not work at all — a workstream holding a review-loop analysis has no arc
   to advance and belongs to the operations surface, whose unit is the proposal
   inside it rather than the workstream around it."
  #{:done :dismissed :analysed})

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :keyword]]}
  awaiting-human
  "The stage a PERSON owes this position, or nil when the next move is nido's.

   One reading of `:next`, named because three surfaces need exactly it: the
   spine marks the workstream as wanting you, the gate offers the decision, and
   the pane draws the same button beside the report. Spelling it three times is
   how they come to disagree about which workstreams are waiting.

   A workstream stops at a :human stage whether or not an agent is asleep inside
   it — the rounds that hand work back to a person run as tasks and park nobody —
   so this, and not a session phase, is what says a person is holding things up."
  [position]
  (let [{:keys [stage mode]} (:next position)]
    (when (= :human mode) stage)))

(defn- with-position
  "Stamp the pipeline position on a row the board will actually render.

   Here rather than in `to-spine`, which both row paths share: to-spine is pure,
   and the detail pane already asks pipeline/of for itself — stamping there would
   make every pane read the ledger twice to answer one question.

   Skipped for the bands nothing draws. It costs a ledger read and a standing
   closure per row (~4ms measured across brian's 45 open workstreams, ~190ms for
   the board), which is affordable against a multi-second poll and worth not
   paying 150 times over for rows in :done."
  [project row]
  (if (contains? unrendered-bands (:stage row))
    row
    (let [position (pipeline/of project (:ws-id row))]
      (cond-> (assoc row :position position)
        ;; A workstream whose next move is a person's IS a gate, and nothing else
        ;; was going to say so. :needs-you is projected from session phases, and
        ;; the rounds that hand work back to a human run as tasks that park no
        ;; session — so a design decision waiting to be granted read as nobody
        ;; waiting, and never reached the inbox that is supposed to carry it.
        ;;
        ;; Only ever set, never cleared: the session-parked reading underneath is
        ;; a different way to be a gate and stays exactly as true as it was.
        (awaiting-human position) (assoc :needs-you true)))))

(def ^:private ^:dynamic *rows-memo*
  "Atom memoizing `list-workstreams` by [project live-names] for the extent of one
   `with-shared-rows` call, or nil when nothing is sharing.

   Scoped, never global. The rows are a projection of the ledger on disk, so a
   memo outliving the render it serves is a board that shows a decision taken in
   another tab only once something invalidates it — and nothing here invalidates.
   Nil by default, so every caller outside a render reads the disk as it always
   has."
  nil)

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  with-shared-rows
  "Call `f` with one `list-workstreams` memo shared across it, so a render asking
   two questions of the same project reads that project's workstreams once.

   The board asks exactly two — `all-grouped` for the bands, `all-gates` for the
   queue — and they are two folds of the same rows. Reading per question costs a
   full ledger pass per project per question, which is the bulk of a render, paid
   twice over for one screen. Both must be inside the SAME call for the second to
   hit the first's rows; wrapping them separately shares nothing."
  [f]
  (binding [*rows-memo* (atom {})] (f)))

(defn- read-rows
  "`list-workstreams` without the memo — one full read of a project's workstreams."
  [project live-names]
  (mapv #(with-position project (to-spine %))
        (wsv/workstream-rows project live-names)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :boolean]}
  grantable?
  "May a human grant this workstream's design right now?

   `pipeline/design-decided?` under a WorkPlane name, and that is the whole of it. Two surfaces
   render the Approve button — the gate inbox and the workstream pane — and the pane is Surface,
   which may not reach Lane. Without this the pane would have to ask a different question, and it
   did: it read the recommendation off the report the button was rendered from, which is a
   different answer the moment a design told to proceed is later sent back.

   Both halves of the acceptance rule, not just the recommendation. `approve!` also refuses a
   design whose standing is not decidable — retracted, or citing a baseline no round found
   sufficient — so a predicate that asked only about the recommendation would offer a button the
   resolver then refused. The one thing it cannot answer is staleness, because the position it
   would compare against is the thing the button carries.

   So: offered implies accepted, for a click made against the position it was rendered at."
  [project ws-id]
  (boolean (and (pipeline/design-decided? project ws-id)
                (when-let [d (cws/latest-entry project ws-id :design)]
                  (:decidable? (standing/of-design project ws-id d))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:? :any]] [:vector :map]]}
  list-workstreams
  "All of a project's workstreams as enriched rows on the single spine. `live-names`
   (optional set of session names actually holding ports) is threaded into the
   engagement projection — pass it so a downed one-off reads idle.

   Each rendered row carries :position — where the pipeline says it is and what
   would happen next. The board used to show only which BAND a workstream was in,
   so everything between authoring an intent and opening a draft PR looked
   identical: forty rows reading :in-progress, saying nothing about which of them
   was waiting on a baseline and which on a human."
  ([project] (list-workstreams project nil))
  ([project live-names]
   (if-let [memo *rows-memo*]
     (let [k [project live-names]]
       (or (get @memo k)
           (let [rows (read-rows project live-names)]
             (swap! memo assoc k rows)
             rows)))
     (read-rows project live-names))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :any [:? :any]] [:vector :map]]}
  winding-down
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

(defn ^{:malli/schema [:=> [:cat :ProjectName [:? :any]] :map]}
  grouped
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

;; What a ledger entry and a proposal ARE now lives one band down, in
;; `record.proposal`, so the improvement source can ask the same question
;; without reaching this facade. These are the names the rest of this namespace
;; already used; aliasing them here keeps the move structural.
(def ^:private first-heading proposal/first-heading)
(def ^:private entry->report proposal/entry->report)
(def ^:private active-ledger proposal/active-ledger)

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  proposals
  "Every proposal this project's review-loop analyses have made, newest analysis
   first, each with whatever was decided about it and whatever became of it.

   The derivation is `record.proposal/of-project`; this is the surface's door to
   it, and the reason the door exists is that a surface wraps this vocabulary
   and not the records beneath it.

   `:reserved?` is added here rather than in the derivation because it is a fact
   the SURFACE needs and no scheduling rule does. Once a claim covering an
   address has been reserved, a decline recorded against it is late: the veto
   deadline has passed and the landing will happen anyway. Without this the board
   would render such a decline exactly like one that stopped something, which is
   the honest reading of silence being the wrong one — a reader would believe
   they had vetoed a change that lands minutes later."
  [project]
  (let [pk       (keyword (name project))
        reserved (into #{}
                       (comp (filter :open?) (mapcat :addresses))
                       (proposal/claim-attempts pk))]
    (mapv (fn [p] (cond-> p (reserved (proposal/address p)) (assoc :reserved? true)))
          (proposal/of-project pk))))

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
  "Lightweight index entry for the pane list: {:seq :kind :at :title :supersedes}.
   Title is the typed report's :title / markdown's first heading / first line / the
   kind name — never blank. :supersedes is the seq this entry AMENDS (nil for the
   ordinary entry); the report is already hydrated here, so reading it costs
   nothing."
  [base-dir entry]
  (let [r (entry->report base-dir entry)]
    {:seq        (:seq entry)
     :kind       (:kind entry)
     :at         (:at entry)
     :supersedes (:seq (:supersedes r))
     :title      (or (report/report-title r)
                     (not-empty (:title r))
                     (first-line (:markdown r))
                     (name (:kind entry)))}))

(defn- mark-superseded
  "Stamp :superseded-by on every row a LATER row amends (report/Supersedes).

   The amend path appends rather than edits — a superseded design record stays in
   the ledger on purpose (/design §5), so the reasoning that failed stays
   recoverable. But the index renders kind + day + title, which makes an amended
   design and its replacement two rows reading `design`, with the `:supersedes`
   pointer buried in the body of the one that did the amending. Two identical
   rows read as a double-write; you had to open the newer entry to learn the
   older one was dead.

   The back-reference is derived here rather than rendered, because it is a
   relation between two entries and no single row can see it. `rows` must be
   OLDEST-FIRST: a target amended more than once keeps its newest superseder,
   which is the one a reader would follow."
  [rows]
  (let [by-target (into {} (keep (fn [{:keys [seq supersedes]}]
                                   (when supersedes [supersedes seq])))
                        rows)]
    (mapv #(assoc % :superseded-by (get by-target (:seq %))) rows)))

(defn- report-at
  "The entry whose :seq is `seq`, rendered via entry->report and hydrated with
   whatever detail it points at. Callers select `seq` from the entries themselves,
   so a miss means the ledger changed under the read — nil, not the latest entry
   silently swapped in."
  [base-dir entries seq]
  (let [by-seq (into {} (map (juxt :seq identity)) entries)]
    (some->> (get by-seq seq) (entry->report base-dir) hydrate)))

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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  latest-report
  "The workstream's most recent ledger entry as a `:format`-tagged gate report,
   or nil. Resolves the active ledger (the workstream's own event store), reads
   its latest entry, and finally falls back to stored intake text so an un-triaged
   :incoming Slack report still shows its message body."
  [project ws-id]
  (let [{:keys [base-dir entries]} (active-ledger project ws-id)]
    (if (seq entries)
      (hydrate (entry->report base-dir (last entries)))
      (intake-fallback project ws-id))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  environment
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  holds
  "The three records that CURRENTLY hold — what this workstream is for, what it
   found, and what it committed to — each with how many revisions it took to get
   there.

   The pane used to answer this by rendering every revision of each and leaving
   the reader to work out which one was live. On a workstream whose baseline took
   seven rounds that is fourteen rows saying one thing, and the one thing is the
   only thing a reader wanted. So: the standing record, plus a count of what it
   supersedes, and the log underneath for anyone who needs the trail.

   :verified? and :decided? are asked of the modules that own them — nothing here
   re-derives whether a baseline held or a design was granted, because a second
   implementation of that join is a second answer to it."
  [project ws-id]
  (let [entries (:entries (cws/read-ws project ws-id))
        ;; Counted by the ledger KIND, never by the record's :format — a triage
        ;; entry is kind :triage carrying format :triage-report, and counting by
        ;; the latter silently matches nothing.
        n-of    (fn [kind] (count (filter #(= kind (:kind %)) entries)))
        [i-kind
         intent] (if-let [i (cws/latest-entry project ws-id :intent)]
                   [:intent i]
                   [:triage (cws/latest-entry project ws-id :triage)])
        baseline (cws/latest-entry project ws-id :baseline)
        design  (cws/latest-entry project ws-id :design)
        st      (when design (standing/of-design project ws-id design))]
    (cond-> {}
      intent (assoc :intent {:seq (:seq intent) :at (:at intent)
                             :kind i-kind
                             :revisions (n-of i-kind)
                             :record intent})
      baseline (assoc :baseline {:seq (:seq baseline) :at (:at baseline)
                                 :revisions (n-of :baseline)
                                 :verified? (boolean (pipeline/baseline-verified?
                                                      project ws-id))
                                 :record baseline})
      design (assoc :design {:seq (:seq design) :at (:at design)
                             :revisions (n-of :design)
                             ;; The premise this design cites, which need not be
                             ;; the baseline above: a workstream can baseline again
                             ;; after deciding, and the design was judged against
                             ;; the one it named.
                             :premise (:seq (:premise st))
                             :decided? (boolean (:decided? st))
                             :blocked (:blocked st)
                             :record design}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:? :any]] :map]}
  workstream
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
                      ;; mark BEFORE reversing — mark-superseded reads oldest-first
                      (vec (reverse (mark-superseded (mapv #(index-row base-dir %) entries)))))
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
        ;; What is underway right now, taken from the row rather than re-derived:
        ;; a second read is a second moment, and a pane whose heading disagreed
        ;; with the board row beside it would be the disagreement this facade
        ;; exists to prevent.
        :doing        (:doing row)
        :ledger       (ledger-summary project (:br-id row))
        ;; What the pane LEADS with: where this is and what currently holds. The
        ;; entry index below stays exactly as it was — it is the log underneath,
        ;; not the answer.
        :position     (pipeline/of project ws-id)
        ;; The trail, at the granularity work actually moves in. Folded from the
        ;; snapshot `entries` already holds rather than re-read, for the reason
        ;; :report is: a second read is a second moment, and an arc built from a
        ;; later ledger than the index below it would disagree with the rows a
        ;; reader is looking at.
        ;; :closed? is passed rather than re-derived: it is the same fact
        ;; pipeline/place reads to answer :shipped, from the same record, so the
        ;; heading and the arc under it cannot disagree about whether this is over.
        :arc          (pipeline/arc entries {:closed? (some? (:closed w))})
        :holds        (holds project ws-id)
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
     ;; A gate is a workstream too, and the one whose activity is easiest to
     ;; miss: a claim outranks session state, so a workstream parked on a human
     ;; can ALSO have a round running against it. Taken from the row rather than
     ;; re-derived, for the reason `workstream` takes it from the row — the
     ;; inbox and the board sit on one screen, and a second read is a second
     ;; moment.
     :doing        (:doing row)
     :report       report
     :actions      (gate-actions (:stage row) parked? (:origin row)
                                 {:report-format (:format report)
                                  :grantable?    (grantable? project (:ws-id row))
                                  :options       (:options report)
                                  ;; From the row, not re-derived: the spine used
                                  ;; this same reading to decide the workstream is
                                  ;; a gate at all, and a second read is a second
                                  ;; moment.
                                  :awaiting      (awaiting-human (:position row))
                                  :seq           (:seq report)})
     :session      (:name psess)
     :resume-error (get-in psess [:autonomy :error])
     :working?     (resuming? project (:ws-id row))}))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:? :any]] [:vector :Gate]]}
  gates
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :Gate]]}
  gate
  "Full gate detail for one workstream, or nil when it is absent or not a gate."
  [project ws-id]
  (->> (gates project)
       (filter #(= ws-id (:ws-id %)))
       first))

;; Defined below, with the session functions it reads — the liveness oracle
;; belongs beside those, and both cross-project projections need it up here.
(declare live-session-names)

(defn ^{:malli/schema [:=> [:cat] [:vector :Gate]]}
  all-gates
  "Gates across every registered project, needs-you/newest-first within each.
   Mirrors the dashboard's cross-project aggregation (see all-machine-rows).
   `->gate` canonicalizes each gate's :project to a string, so the raw
   list-projects key threads straight through. A project that can't be read
   contributes no gates rather than failing the board.

   Threads `live-session-names` for the same reason `all-grouped` does: engagement
   is projected from it, and a downed one-off reads :active without it. It cannot
   change WHICH rows are gates — :settled is a function of :closed alone, and the
   liveness reconciliation only ever moves a row from :active toward :idle — so
   what it buys is that the two cross-project projections describe the same
   workstream the same way, from one read of it."
  []
  (->> (project/list-projects)
       (mapcat (fn [[pname _entry]]
                 (try (gates pname (live-session-names pname))
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword] :keyword]}
  default-target
  "Default target stage for a `new`/`promote` gesture in `project`. `action` is
   :promote or :new. A value configured under the project's :workstream-defaults
   is honored only when it names a spine stage; otherwise the canonical default."
  [project action]
  (let [configured (get-in (project-entry (config/read-projects) project)
                           [:workstream-defaults action])]
    (if (some #{configured} stages)
      configured
      (canonical-default-target action))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :keyword] :any]}
  set-stage!
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  dismiss!
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  restore!
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
          ;; write with incomplete data (see nido.coordinator.daemon.notify/merged-participants
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  apply!
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  start-triage-page!
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

(defn ^{:malli/schema [:=> [:cat :int :map] :string]}
  option-input
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
  "ANSWER the blocker, then tell whoever is listening. The answer is written to
   the ledger first (a :blocker-answered entry) and only then does this try to
   resume a parked agent, because the two have different lifetimes: the question
   is a durable ledger entry, the session that asked it is not.

   That order is the whole point. Four of five blockers on this box outlived
   their session — runs fail, budgets kill, sessions get torn down — and an
   answer that exists only as a resume argument is available exactly when it is
   least needed. Recorded, the decision stands with nobody listening: the next
   session picking the workstream up reads it (/continue-ticket), and the gate
   stops asking, because the latest entry is now the answer rather than the
   question. It also means a resume that dies mid-turn cannot silently swallow a
   human decision.

   The branch is resolved against the CURRENT latest report, never against what
   the browser was showing. A click carries a letter and `entry-seq`, the ledger
   position the letter was rendered from: the ledger decides what the letter
   meant, and the position decides whether that question is still the one being
   asked. A letter alone would silently resolve against WHATEVER is latest — if
   another tab answered and the agent parked on a new blocker with as many
   branches, :option-a would pick branch A of a question the human never read.
   A click whose position is not the latest entry, or which carries none, is
   refused. After answering, the answer IS the latest entry, so a double-click
   refuses on the same check."
  [project ws-id action-id entry-seq]
  (let [report (latest-report project ws-id)
        i      (option-index action-id)]
    (if-let [opt (and (some? entry-seq)
                      (= entry-seq (:seq report))
                      (get (vec (:options report)) i))]
      (let [parked (parked-session project ws-id)]
        (cws/append-entry!
         project ws-id {:kind :blocker-answered}
         (pr-str {:format      :blocker-answered
                  :blocker-seq entry-seq
                  :letter      (report/option-letter i)
                  :label       (:label opt)
                  :summary     (:summary opt)
                  :resumed     (:name parked)}))
        (if parked
          (assoc (resume/resume! project ws-id (option-input i opt)) :decision :answered)
          ;; Nobody to tell. The answer is on the ledger and the gate has stopped
          ;; asking; this is a real outcome, not a failure, so it must not read as
          ;; one — see ui.server/resolve-failure-msg.
          {:decision :answered-unresumed}))
      {:decision :option-stale})))

(defn- approve!
  "GRANT the design, then tell whoever is listening.

   Same order and the same reason as choose-option!: the grant is written to the
   ledger first and only then is a parked agent resumed, because the two have
   different lifetimes. Approval used to be an argument to a resume and nothing
   more — so `was this decided` was nowhere a reader could ask it, a landing
   gate had nothing to read, and a resume that died mid-turn swallowed the
   decision. Recorded, the grant stands with nobody listening: the next session
   picking the workstream up reads it, the way an answered blocker already is.

   Two refusals, and they are different questions.

   The POSITION, exactly as an option click is checked: an Approve carries the
   entry it was rendered from, and one whose position is no longer the ledger's
   latest is refused. A decision made against a stale page is not a decision
   about what is there now — a retraction appended since, another tab's
   approval, or a second click of the same button all land here. After granting,
   the approval IS the latest entry, so the double-click refuses on the same
   check.

   The PREMISE, which an option click has no equivalent of: the gate is asked
   again at the moment of the grant. The design round said this design could be
   decided when it ran, and standing is a statement about now — so a design
   whose baseline was retracted between the round and the click is refused here
   rather than granted, and no approval is ever written for a premise that is
   already gone."
  [project ws-id entry-seq]
  (let [design (cws/latest-entry project ws-id :design)]
    (cond
      (nil? design) {:decision :no-design}

      (or (nil? entry-seq)
          (not= entry-seq (:seq (latest-report project ws-id))))
      {:decision :approval-stale}

      :else
      (let [st (standing/of-design project ws-id design)]
        (if-not (grantable? project ws-id)
          ;; ONE predicate, and the button reads it too — a resolver reachable by
          ;; posting an id must not trust that its only caller was a button
          ;; rendered under the same rule, but it must not apply a DIFFERENT rule
          ;; either, or the button offers grants this refuses.
          {:decision :approval-refused
           :because  (or (:blocked st)
                         {:reason :not-recommended
                          :detail (str "the decision round did not recommend proceeding "
                                       "on this design — re-run bb nido:review:design, "
                                       "or supersede the design first")})}
          (let [parked (parked-session project ws-id)]
            (cws/append-entry!
             project ws-id {:kind :design-approved}
             (pr-str {:format :design-approved
                      :design {:seq (:seq design)}
                      :at-seq entry-seq}))
            (if parked
              (assoc (resume/resume! project ws-id approval-input) :decision :approved)
              ;; Nobody to tell, and that is a real outcome rather than a
              ;; failure: the grant is on the ledger and the next session reads
              ;; it — see ui.server/resolve-failure-msg.
              {:decision :approved-unresumed})))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any [:? :any]] :map]}
  resolve-gate!
  "Apply a gate follow-action, dispatching on `action-id`. A workstream-less ws-id
   (e.g. a bare watched-view row) is a no-op — {:decision :no-workstream} — for
   every action but :restore, :dismiss and :start-triage, which such a row
   legitimately offers (see workstream-less-actions).
     :promote -> set-stage! :in-progress   :dismiss -> off-radar (ticket + ws :dismissed)
     :drop    -> close! :dropped            :done    -> set-stage! :done
     :apply   -> apply! (ticket:complete)   :reply   -> resume! the parked agent with `payload`
     :approve -> record the grant (:design-approved) and resume, iff `payload`
                 still names the latest report AND the design still stands
     :option-a … :option-f -> resume! with the blocker branch that letter names,
                              iff `payload` still names the latest report
     :restore -> restore! (clear ticket status + reopen at :triaging)
     :start-triage -> start-triage-page! (force-spawn the triage trigger)

   `payload` is whatever the click carried besides its id, and what that is
   depends on the action: the reply text for :reply, the :seq of the report the
   button was rendered from for :option-* and :approve. Every other action
   resolves entirely nido-side and ignores it.
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
       :approve (approve! project ws-id payload)
       (throw (ex-info "Unknown gate action" {:action-id action-id :ws-id ws-id}))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName] :any]}
  new!
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  open-target
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :boolean]}
  reclaimed?
  "True iff `session` under `ws-id` is owned by a Run whose ephemeral
   session-home was reclaimed — i.e. it CAN be re-hydrated but isn't landable
   right now. Cheap (a run lookup + a symlink stat); the interactive open path
   uses it to decide whether to re-provision before landing. False for a session
   with no owning Run (nothing to re-hydrate from) or whose home is present."
  [project ws-id session]
  (boolean
   (when-let [run (runs/find-for-session project ws-id session)]
     (not (runs/home-present? run)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any] :any]}
  ensure-open!
  "Make `session` under `ws-id` landable, re-provisioning its session-home when
   the Run that owns it had the home reclaimed. Returns true if it re-hydrated,
   false if nothing was needed (home present, or no owning Run). SLOW when it
   re-provisions (brings the session back up) — callers run it off the render
   thread. Throws (tagged `:rehydrate-failed`) if re-provisioning fails."
  [project ws-id session]
  (boolean
   (when-let [run (runs/find-for-session project ws-id session)]
     (runs/ensure-session-home! run))))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:? :any]] :any]}
  facet-dimensions
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

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  grouped-rows
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword] :any]}
  facet-values
  "Ordered distinct values present for facet `k` across the project's non-done
   workstreams, with :unclassified appended when any such row lacks a value."
  [project k]
  (let [rows (remove #(= :done (:stage %)) (list-workstreams project))
        present (->> rows (mapcat #(facet-row-values k %)) distinct vec)
        any-missing? (some #(nil? (facet-row-values k %)) rows)]
    (cond-> present any-missing? (conj :unclassified))))

(defn ^{:malli/schema [:=> [:cat :map :map] :boolean]}
  facet-match?
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

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  session-live?
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

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  live-session-names
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

(defn ^{:malli/schema [:=> [:cat [:? :int]] :any]}
  prune-dead-registry!
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  bring-down!
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

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  orphan-live-sessions
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
         (filter (fn [w] (and (scratch/scratch? w)
                              (empty? (:external-refs w))
                              (empty? (:entries w)))))
         (keep (fn [w]
                 (let [sess (csession/list-sessions project (:id w))]
                   (when (and (= 1 (count sess))
                              (some #(not= (:id w) (:id %))
                                    (owners-of (:name (first sess)))))
                     (cws/delete! project (:id w))
                     (:id w)))))
         vec)))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  adopt-orphans!
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :Path] [:vector :map]]}
  machine-rows
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :any] :map]}
  machine-facts
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

(defn ^{:malli/schema [:=> [:cat [:? :any] [:? :any]] [:vector :map]]}
  all-machine-rows
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

(def ^:private decided-by
  "Who a decision is recorded as. Read from the environment nido runs in, never
   from a request: a browser sends an action id and nothing else, so a decision
   cannot arrive claiming an author. Same source the daemon stamps :fired-by a
   hand-fired trigger with."
  (delay (or (System/getenv "USER") "unknown")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  decide-proposal!
  "Record a human decision about one proposal. Returns {:decision :recorded} or
   {:decision :stale :latest <seq>} when the position no longer names the
   ledger's latest entry.

   The whole act is the append: nothing is resumed and nothing is spawned,
   because nothing acts on a decision in this landing (FU-32). That makes this
   the simplest possible version of the shape every other answer on this ledger
   has — write the durable record, and let whatever wants it read it later.

   Which means an approval is a commitment and not a completion, and the surface
   has to be able to say so. `record-landing!` is what discharges one.

   `at-seq` is what the reader was looking at. It is compared inside the append
   lock by cws/append-entry-at!, so two people deciding the same proposal from
   the same page cannot both be recorded: the second is told the page moved."
  [project ws-id {:keys [analysis-seq observation verdict at-seq note]}]
  (let [res (cws/append-entry-at!
             (keyword (name project)) ws-id at-seq
             {:kind :improvement-decision}
             (pr-str (cond-> {:format       :improvement-decision
                              :analysis-seq analysis-seq
                              :observation  observation
                              :verdict      verdict
                              :at-seq       at-seq
                              :decided-by   @decided-by}
                       (not (str/blank? (str note))) (assoc :note note))))]
    (if (map? res)
      {:decision :stale :latest (:latest res)}
      {:decision :recorded :file res})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  record-landing!
  "Record that an approved proposal is now carried by `rev`. Returns
   {:landed :recorded :file <path>}.

   No `at-seq` guard, unlike `decide-proposal!`. That guard exists because a
   decision is an answer to a page, and answering a page that has moved is not
   answering what is there. This is not an answer to anything — it is a fact
   about the repository, true whatever the ledger has grown since — so refusing
   it on a stale position would reject a true record for a reason that does not
   apply to it.

   Nothing checks that the proposal was approved first, and nothing should. A
   proposal implemented before anyone got round to deciding on it is a normal
   sequence here, and a record that refused to describe it would leave the code
   landed and the surface saying nothing happened."
  [project ws-id {:keys [analysis-seq observation rev note]}]
  {:landed :recorded
   :file (cws/append-entry!
          (keyword (name project)) ws-id
          {:kind :improvement-landed}
          (pr-str (cond-> {:format       :improvement-landed
                           :analysis-seq analysis-seq
                           :observation  observation
                           :landed-by    @decided-by}
                    (not (str/blank? (str rev)))  (assoc :rev rev)
                    (not (str/blank? (str note))) (assoc :note note))))})

(def ^:private note-means-landed
  "Note prefixes from before `:improvement-landed` existed that record an
   outcome rather than a judgement.

   Sixty-one proposals were carried out while `:approved` was the whole of the
   ledger's vocabulary for it, and the account went in the note. This is the
   only place that reads one — a migration reading the record its era produced,
   once, into the vocabulary that replaced it. No reader sniffs prose."
  ["implemented" "already closed" "CORRECTION" "no change proposed"])

(defn ^{:malli/schema [:=> [:cat :ProjectName] :map]}
  backfill-landings!
  "Discharge every approval whose note already says what became of it. Returns
   {:landed <n> :skipped <n>}.

   Idempotent: an address that already carries a landing is left alone, so this
   can be re-run without stacking duplicates on a ledger that has no delete.

   No `:rev`. The notes name behaviours — `the warden keys on :handle` — and
   the changes that carry them are not recoverable from that without guessing.
   The note is copied across verbatim instead, which is the account that era
   actually wrote, and a reader gets it in full rather than a change id nobody
   checked."
  [project]
  (let [pk (keyword (name project))]
    (reduce
     (fn [acc {:keys [ws-id analysis-seq observation decision landed]}]
       (let [note (str (:note decision))]
         (if (or landed
                 (not= :approved (:verdict decision))
                 (not (some #(str/starts-with? note %) note-means-landed)))
           (update acc :skipped inc)
           (do (record-landing! pk ws-id
                                {:analysis-seq analysis-seq
                                 :observation  observation
                                 :note (str "recorded from the decision note, which "
                                            "predates this record — " note)})
               (update acc :landed inc)))))
     {:landed 0 :skipped 0}
     (proposals pk))))

(defn ^{:malli/schema [:=> [:cat] [:vector :map]]}
  all-grouped
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

(defn ^{:malli/schema [:=> [:cat :map] :Screen]}
  screen
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

;; ── the acts a surface performs on the work plane ────────────────────────────
;;
;; The TUI and the dashboard reached `lane.pickup`, `lane.findings` and `lane.scratch` directly
;; for these. Each one IS work-plane vocabulary — resolve a pasted ref into a workstream, file a
;; findings round on one, tell the plane a session came up — so it belongs at this altitude
;; rather than behind a require of the lane that happens to implement it today.

(def arc-stages
  "The stages a workstream travels, in order. The plane naming its own spine — a surface deciding
   whether a key is a stage should ask the work plane, not the lane that happens to compute the
   arc today."
  pipeline/arc-stages)

(def pickup-trigger
  "The trigger name a pickup enqueues under. Surfaces need it to ask whether a pickup would
   actually be processed, which is a question about that specific trigger's breaker."
  pickup/trigger)

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :NotionToken] :map]}
  pickup!
  "Resolve a pasted Notion URL / page-id / BR-#### into a workstream and queue its provisioning."
  [project input token]
  (pickup/pickup! project input token))

(def start-intent-trigger
  "The leg a described intent is enqueued at. Named here for the same reason `pickup-trigger`
   is: a surface asking whether a description would actually be processed is asking about this
   specific trigger's breaker, and neither side should hardcode the answer."
  :start-intent)

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :map]}
  start-intent!
  "Start work from a description: enqueue it at the project's `:start-intent` leg. The daemon
   resolves the envelope into a ref-less workstream and a session holding the text as its first
   message, where the agent establishes the workstream's `:intent` — or halts on a `:blocker`
   asking what the description did not say.

   Resolves the leg BEFORE writing anything. A project declaring no `:start-intent` trigger is
   refused here, with the leg named, rather than by an envelope the daemon drains, routes to
   nothing and drops to stderr — where the person who typed the description would never see it.
   That refusal is also the whole of phase one: the bar ships before any project declares the
   leg, and answering `:no-leg` is what makes that state habitable rather than half-built.

   Writes no workstream and no `:intent`. Both belong to what the envelope starts, not to the
   act of starting it."
  [project text]
  (let [text (str/trim (str text))]
    (cond
      (str/blank? text)
      {:decision :blank :trigger start-intent-trigger}

      (nil? (triggers/find-by-name (triggers/load-for-project project) start-intent-trigger))
      {:decision :no-leg :trigger start-intent-trigger}

      :else
      {:decision :queued
       :trigger  start-intent-trigger
       :queued   (queue/enqueue! {:target  {:project (keyword (name project))
                                            :trigger start-intent-trigger}
                                  :payload {:description text}})})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  file-findings!
  "File a staging findings round on a shipped workstream. `opts` = {:items […] :staging-ref?
   :note? :session?}. Appends the event, seeds the tracker, reopens to :in-progress."
  [project ws-id opts]
  (findings/file! project ws-id opts))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName] :any]}
  session-started!
  "Tell the work plane a session came up: births its loose workstream, idempotently — a no-op
   when the session already belongs to one.

   The weight lookup rode in the TUI before, which meant the surface knew both that a scratch
   workstream exists and how a session is weighed. It needs to know neither."
  [project session-name]
  (scratch/birth! (keyword project) session-name
                  (lifecycle/session-weight session-name {:project project})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName] :any]}
  session-destroyed!
  "Tell the work plane a session was destroyed: reaps its loose workstream, sparing one that
   carries a ref."
  [project session-name]
  (scratch/reap! (keyword project) session-name))

;; ── What the improvement sweep writes ───────────────────────────────────────

(defn- with-append-locks
  "Run `f` holding the append lock of every workstream in `ws-ids`, taken in a
   fixed order, then release them all.

   Sorted, and that is the whole of the deadlock argument: two verbs asking for
   overlapping sets in the same order cannot hold what the other is waiting for.

   The locks are never held across a push. They bound a local ledger append —
   bounded by disk — and are gone before any network call, because
   append-lock-path is per workstream precisely so that no writer queues behind
   whichever one is slowest, and a hanging push inside one would recreate the
   global lock the ledger deliberately does not have.

   Re-entering is safe for a DIFFERENT workstream and only for a different one:
   cws/append-entry! takes its own target's lock, so appending to a workstream
   not in `ws-ids` under these locks is fine, and appending to one that is would
   deadlock on a lock this already holds."
  [project ws-ids f]
  (let [paths (->> ws-ids distinct sort
                   (map #(cws/append-lock-path (keyword (name project)) %)))]
    ((reduce (fn [thunk path] #(io/with-file-lock path thunk)) f paths))))

(defn- ws-of-address
  "The workstream an address `<ws-id>/<seq>.<obs>` names."
  [address]
  (second (re-matches #"(.+)/\d+\.\d+" (str address))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  record-plan!
  "Append one day's plan, or refuse it. Returns {:plan :recorded :file <path>}
   or {:plan :refused :defect <map>}.

   The refusal is the point. A plan claims to partition what is owed, and the
   generic ledger boundary cannot check that — it validates the shape of an
   entry and has no access to the project's derived owed set — so a plan written
   through `bb nido:workstream:entry:add` would satisfy every schema and none of
   the claim. This derives the owed set ITSELF and refuses a grouping that does
   not cover it exactly once.

   Derivation and append happen under the append locks of every workstream the
   frontier names, so nothing can be decided or landed between deciding what is
   owed and writing down the partition of it. Without that the record would
   claim a state that had already moved by the time it was durable.

   Not exclusive, and the invariant does not claim it is: `entry-add` still
   accepts any registered kind, which is what lets a person append a record the
   code has no verb for. What makes that safe is the frontier — the sweep
   re-derives against it before acting, so a plan written another way is either
   consistent with the ledger or refused."
  [project ws-id {:keys [date claims frontier]}]
  (let [pk       (keyword (name project))
        frontier-ws (mapv :ws-id (:proposals frontier))]
    (with-append-locks
      pk frontier-ws
      (fn []
        (let [ow     (proposal/owed (proposal/of-project pk)
                                    (proposal/plans-of pk)
                                    (proposal/claim-attempts pk))
              defect (proposal/partition-defect claims ow)]
          (if defect
            {:plan :refused :defect defect}
            {:plan :recorded
             :file (cws/append-entry!
                    pk ws-id {:kind :improvement-plan}
                    (pr-str {:format :improvement-plan :date date
                             :frontier frontier :claims (vec claims)}))}))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  reserve-claim!
  "Fix a claim's veto deadline, or refuse it. Returns {:reserved :recorded} or
   {:reserved :vetoed :declined [<address> ...]}.

   The deadline has to be a ledger entry rather than a moment inside an agent,
   because otherwise the board cannot answer the one question a late decline
   raises: did this stop anything. A reservation is what a later decline is
   compared against.

   Eligibility is re-derived under the append lock of every workstream the
   claim's addresses live in, and the reservation is appended while they are
   still held — to the CLAIM's own workstream, which is not among them, so the
   append takes a lock this does not hold. A decline goes through
   decide-proposal!, which takes its own workstream's lock, so the two cannot
   interleave: a decline either completes before this acquires, in which case it
   is seen and the claim is refused, or it waits and finds a reservation
   standing."
  [project claim-ws-id {:keys [plan-seq claim addresses]}]
  (let [pk (keyword (name project))]
    (with-append-locks
      pk (keep ws-of-address addresses)
      (fn []
        (let [declined (->> (proposal/of-project pk)
                            (filter #(= :declined (get-in % [:decision :verdict])))
                            (map proposal/address)
                            set)
              hit      (filterv declined addresses)]
          (if (seq hit)
            ;; Closed here, not left to the caller: "a refused claim releases the
            ;; slot" is an invariant, and an invariant discharged by whoever
            ;; remembers to call close! is an instruction. :vetoed rather than
            ;; :dropped because the two mean opposite things to the owed set —
            ;; a dropped claim's addresses are tried, a vetoed claim's survivors
            ;; are owed again, and only the declined one leaves.
            (do (cws/close! pk claim-ws-id :vetoed)
                {:reserved :vetoed :declined hit})
            (do (cws/append-entry!
                 pk claim-ws-id {:kind :improvement-claim-reserved}
                 (pr-str {:format :improvement-claim-reserved
                          :plan-seq plan-seq :claim claim
                          :addresses (vec addresses)}))
                {:reserved :recorded})))))))

(def ^:private push-landed
  "Push outcomes that mean nido's main holds the revision.

   Both record. `:already-there` is not a failure and treating it as one is the
   subtler bug: a process that dies between a successful push and its first
   ledger append leaves a retry finding the remote already at that revision, so
   a rule of `record only if it moved` would leave that landing unwritten
   forever."
  #{:advanced :already-there})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  discharge-claim!
  "Push `rev` and record what it carried, or refuse. Returns
   {:discharged :recorded :addresses [...]}, {:discharged :unreserved} or
   {:discharged :not-pushed :outcome <kw> :detail <string>}.

   The only path by which the sweep reaches nido's main. A revision arriving any
   other way records no landing and closes no workstream, which leaves the claim
   owed and the slot held — visible, and re-runnable.

   Refuses without a standing reservation, which is what makes the veto a
   restriction rather than an instruction: a claim refused at `reserve-claim!`
   has no reservation, so there is nothing here that will push for it.

   The push happens under NO lock. `reserve-claim!` released them before
   returning, and the deadline it fixed is what a decline arriving now is
   answered against.

   Re-runnable, because it has to be: an interruption between the push and the
   last append leaves the workstream open, and running it again appends only for
   the addresses that do not already carry a landing at this revision — the same
   skip that makes backfill-landings! re-runnable — then closes. The push it
   re-attempts reports `:already-there` and records exactly as the first would."
  [project claim-ws-id {:keys [worktree bookmark rev addresses]}]
  (let [pk (keyword (name project))
        reserved? (->> (:entries (cws/read-ws pk claim-ws-id))
                       (some #(= :improvement-claim-reserved (:kind %)))
                       boolean)]
    (if-not reserved?
      {:discharged :unreserved}
      (let [{:keys [outcome detail]} (lifecycle/advance-remote! worktree bookmark rev)]
        (if-not (push-landed outcome)
          {:discharged :not-pushed :outcome outcome :detail detail}
          (let [carried (->> (proposal/of-project pk)
                             (filter #(= rev (get-in % [:landed :rev])))
                             (map proposal/address)
                             set)]
            (doseq [a addresses
                    :when (not (carried a))
                    :let [[_ ws-id seq-n obs] (re-matches #"(.+)/(\d+)\.(\d+)" (str a))]]
              (record-landing! pk ws-id {:analysis-seq (parse-long seq-n)
                                         :observation  (parse-long obs)
                                         :rev          rev}))
            (cws/close! pk claim-ws-id :done)
            {:discharged :recorded :addresses (vec addresses)}))))))
