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
   [clojure.string :as str]
   [cognitect.transit :as transit]
   [malli.core :as m]
   [nido.platform.config :as config]
   [nido.platform.core :as core]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.lane.facets :as facets]
   [nido.coordinator.lane.findings :as findings]
   [nido.coordinator.lane.phase :as lphase]
   [nido.coordinator.source.notion-cache :as notion-cache]
   [nido.coordinator.source.start-failures :as start-failures]
   [nido.coordinator.report :as report]
   [nido.coordinator.report.ask :as ask]
   [nido.coordinator.lane.drain :as drain-lane]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.lane.start :as start]
   [nido.coordinator.record.phase :as phase]
   [nido.coordinator.record.proposal :as proposal]
   [nido.coordinator.record.records :as records]
   [nido.coordinator.record.review-queue :as review-queue]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.triggers :as triggers]
   [nido.platform.io :as io]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.drain :as rdrain]
   [nido.coordinator.record.workstream :as cws]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.view.drains :as drains-view]
   [nido.coordinator.view.recoveries :as recoveries-view]
   [nido.coordinator.view.review-queue :as review-queue-view]
   [nido.coordinator.view.activity :as act]
   [nido.coordinator.view.workstreams :as wsv]
   [nido.coordinator.work.core :as wcore]
   [nido.core.client :as core-client]
   [nido.core.domain :as domain]
   [nido.notion.client :as notion]
   [nido.notion.views :as views]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.slack.client :as slack]
   [nido.platform.process :as proc]
   [nido.platform.project :as project]
   [nido.session.casualties :as casualties]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as sstate]))

(def stages
  "The canonical spine, in order — session/lifecycle-stages, sequenced. A PR merge
   is the event that advances :shipping → :done; :shipping is the merge-pipeline
   stage entered by `nido ship`.

   These are STORED stage names, because both readers turn them into one: the TUI
   stage picker feeds set-stage! straight from this vector, and default-target
   validates a project's configured override against it. Every entry must be a
   key grouped-by-stage emits: picking a name it has no key for writes a stage
   no band holds, and the workstream drops off the board."
  [:incoming :triage :ready :in-progress :shipping :done])

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  board-bands
  "Ordered [stage rows] pairs the board renders out of a `grouped` map, empty
   bands dropped: :shipping, :in-progress (most-advanced first), :awaiting-gate —
   closed between phases, live session or not, since what they wait on is a
   person rather than a session — then :triage, the triages nido has a session on (in flight
   or parked) — then :winding-down — finished workstreams still holding live resources, whether
   nido settled them or Notion did (bring-down! is their one action). The ONE
   place the band list lives, so no surface can disagree about it.

   The board is work nido is driving, and nothing else. A triage with a session on it is
   driven work: its sessions are the ones a person picks up, and with the band off the board
   they were reachable only through the gate inbox, and not at all between a reply and the
   next park. Queued triage, :incoming and :dismissed rows are grouped but deliberately NOT
   banded — intake nido has not started is not something the surfaces offer — so a row in one
   of those is off the board; it still shows on needs-you when gated. The backlog (:ready)
   and the archive (:done) live in Notion and are never emitted by grouped-by-stage.

   Every row `grouped-rows` emits outside those is on exactly one band — the guarantee that
   nothing nido drives can be hidden (a source filter defaulting to :notion once hid every
   :in-progress row)."
  [grouped]
  (into []
        (keep (fn [[stage rows]] (when (seq rows) [stage (vec rows)])))
        [[:shipping      (:shipping grouped)]
         [:in-progress   (:in-progress grouped)]
         [:awaiting-gate (:awaiting-gate grouped)]
         [:triage        (get-in grouped [:triage :in-flight])]
         [:winding-down  (:winding-down grouped)]]))

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

(defn- lettered-ids
  "One action id per letter, `prefix`-<letter>. The vector IS the letter->position
   mapping both ways, so a letter is never stored anywhere."
  [prefix]
  (mapv #(keyword (str prefix "-" (str/lower-case %))) report/option-letters))

(def option-action-ids
  "Gate action id per blocker-option position — :option-a … :option-f. Fixed ids
   rather than an encoded payload: a click POSTs an id and nothing else, so the
   text the agent is resumed with is built HERE, from the ledger, at click time
   (choose-option! → option-input). Same reasoning as approval-input — an answer
   the browser could compose is an answer that can arrive saying anything."
  (lettered-ids "option"))

(def ^:private option-index
  "Action id → the blocker-option position it selects."
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
   report the gate answers (`gate-report`).

   It answers a question about `gate-actions`' output, so it lives beside it: a
   surface that decided for itself which ids carry a position would be holding a
   second copy of this, free to disagree — which is exactly what happened. The
   web read the position back for option buttons only, so a positioned button
   arrived with no position, took the stale branch, and nothing it answered was
   ever recorded from the dashboard.

   Anything added here needs the same two things an option button has: a :seq on
   its descriptor, and a resolver that compares it before acting."
  [action-id]
  (or (option-action? action-id)
      (contains? #{:apply :defer :land :answer-questions} action-id)))

(defn- lettered-actions
  "One button per branch of an enumerated question, lettered by position. `:kind
   :mutation` because these are resolved nido-side, like :land — the descriptor
   carries no input for the browser to send. Order is the record's order, so the
   letters here and the letters on the card cannot disagree.

   `ids` is the action-id vector the letters resolve into and `title` builds a
   branch's hover text from its position and itself: the two things that differ
   between the kinds of question this shape answers. `:recommended?` is read off the branch, so a vocabulary
   that has no such notion simply gets no primary button rather than a rule of
   its own.

   `entry-seq` is the ledger position of the report these were built from. It
   rides on every button and comes back with the click, because a LETTER ALONE
   IS NOT AN ANSWER: it only means something relative to the question that was on
   screen, and by click time the ledger may hold a different one (the agent
   appended, another tab answered). The resolver refuses on the mismatch. Omitted
   when unknown, which fails closed there rather than resolving blind.

   Branches past the letter set get no button. Both writers are capped at it, so
   this bites only on a record written before the cap."
  [ids entry-seq branches title]
  (vec (map-indexed
        (fn [i branch]
          (cond-> {:id    (nth ids i)
                   ;; The LETTER alone. The branch is spelled out on the card
                   ;; directly above, which is the whole reason the branches are
                   ;; lettered: the reader picks by matching a letter, not by
                   ;; re-reading the branch on a button. Putting the label here
                   ;; too made a row of buttons as wide as three sentences.
                   :label (report/option-letter i)
                   ;; …but a button reading "A" says nothing on its own once the
                   ;; card scrolls away, so the branch rides along as the hover
                   ;; title, where it costs no layout.
                   :title (title i branch)
                   :kind  :mutation
                   :style (if (:recommended? branch) :primary :default)}
            entry-seq (assoc :seq entry-seq)))
        (take (count ids) branches))))

(defn- option-actions
  "One button per branch of a blocker's :options, lettered. A branch carrying :grants [:merge]
   reads Land instead, as the gate's Land does at :published: picking it is the same act, the
   person's grant to land, whatever the project and whatever the branch was called."
  [entry-seq options]
  (mapv (fn [action opt]
          (cond-> action (some #{:merge} (:grants opt)) (assoc :label "Land")))
        (lettered-actions option-action-ids entry-seq options (fn [_ opt] (:label opt)))
        options))

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
   :report-format, :options, :questions AND :seq off the SAME report the reader
   is looking at, so the buttons and the card can never disagree about the
   question — and :seq is what lets the resolver notice that the question itself
   has since changed.

   `{:questions [...]}` is a triage report's questions (report/triage-questions). A
   report that asks one with directions is accepted with an answer per question, all
   sent by one Accept — so no direction is an action of its own: a direction chosen
   is page state until Accept carries it, and Defer decision accepts with every
   question left open. A report whose directions a gate cannot letter
   (report/answerable?) is offered the questionless set rather than a partial one.

   `{:awaiting <stage>}` is the stage the pipeline says a PERSON owes this
   workstream, or nil — `(:stage (:next position))` when that position's mode is
   :human. It is what offers a person's move on a workstream nobody is parked on:
   a blocker a mechanical round raised, a triage verdict, a phase gate, a landing.

   `{:several? true}` says the gate's report is a blocker asking several questions: they are
   answered together by :answer-questions, and no :reply is offered beside it.

   `{:reply-to :conductor}` says a Reply answers a conductor's open escalation rather than resuming
   a parked agent; the reply descriptors then carry `:answers :conductor`, as the free-text Answer
   offered with nobody parked carries `:answers :ledger`.

   `{:bare? true}` marks a row with no workstream behind it (wsv/bare-row). On
   :triage that swaps the accept and Reply — which need a ledger and an agent
   respectively — for :start-triage. Then,
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
  ([stage parked? _origin {:keys [bare? report-format options questions awaiting
                                  gate-criterion reply-to several? landable?]
                           entry-seq :seq}]
   (let [;; The branches of the CURRENT blocker, or [] — offered whether or not a
         ;; session is parked, because answering no longer depends on one being
         ;; alive to hear it (choose-option! records the answer on the ledger and
         ;; resumes only if there is someone to resume). Reply and Done stay gated
         ;; on parked?: each one resumes an agent, and offering it with none is a
         ;; button that can only fail.
         answers (option-actions entry-seq options)
         ;; Whether the CURRENT triage report asks anything — gated on nothing but
         ;; the report, for the reason the accept below is: apply! writes the
         ;; verdict itself and resumes nobody, so a triage whose run died is
         ;; exactly the one that most needs answering.
         asking? (and (= :triage-report report-format)
                      (some (comp seq :directions) questions)
                      (every? (comp report/answerable? :directions) questions))
         ;; A person owes the blocker an answer, whether or not anyone is parked to hear it.
         ;; With nobody parked, :reply records the words as the blocker's answer (`answer-owed!`).
         ;; So the button is "Answer": no agent hears it now, and "Reply" would say one does.
         answering?     (= :answer-blocker awaiting)
         ;; :answers says which resolver the words reach, so what the card says the button does is
         ;; what it does: here the ledger alone, nobody resumed.
         answer-action  {:id :reply :label "Answer" :kind :resume :style :default :answers :ledger}
         ;; Reply reaches a parked agent, unless a conductor's escalation is owed the answer — then
         ;; resolve-gate! records it for the conductor's next pass and resumes nobody (`reply-to`).
         reply-action   (cond-> {:id :reply :label "Reply" :kind :resume :style :default}
                          (= :conductor reply-to) (assoc :answers :conductor))
         actions
         (cond
           ;; A gate is asserted with what a person observed, so its one action
           ;; carries free text; the criterion rides on it for the surface to show.
           (= :assert-gate awaiting)
           [(cond-> {:id :assert-gate :label "Assert gate" :kind :evidence :style :primary}
              gate-criterion (assoc :criterion gate-criterion))]

           ;; Landing is a person's gesture, given as one button: it grants the landing, and the
           ;; seat lands by the procedure its project declares. Offered only where a grant has
           ;; someone to act on it (`landable?`: activated, at :published); a drain's slice lands
           ;; by its drain, and a workstream not activated by its own session.
           (= :land awaiting)
           (cond-> (if landable?
                     [(cond-> {:id :land :label "Land" :kind :mutation :style :primary}
                        entry-seq (assoc :seq entry-seq))]
                     [])
             parked? (conj reply-action))

           :else
           (case stage
           :incoming    [{:id :promote :label "Promote" :kind :mutation :style :primary}
                         {:id :drop    :label "Dismiss" :kind :mutation :style :danger}]
           :triage      (let [dismiss {:id :dismiss :label "Dismiss" :kind :mutation :style :danger}
                              ;; The forward action: accept the verdict. It executes the routed
                              ;; report to Notion nido-side (Ball Holder + App Domain, deep
                              ;; properties/callout — apply-routed!, no conversation), falling back
                              ;; to nido-only ticket:complete for legacy/Slack reports, and appends
                              ;; the acceptance to the ledger.
                              ;;
                              ;; A report that asks is accepted with its answers: Accept sends the
                              ;; choice made on each question (`:answers :questions`), and Defer
                              ;; decision accepts with every question left open — the routing
                              ;; lands and the fixes are left to whoever builds it. One that asks
                              ;; nothing has nothing to defer, so it is one Apply.
                              accept  (cond-> {:id :apply :kind :mutation
                                               :label (if asking? "Accept" "Apply")
                                               :style :primary}
                                        entry-seq (assoc :seq entry-seq)
                                        asking?   (assoc :answers :questions)
                                        ;; apply! files a Slack proposal as a new ticket
                                        ;; rather than writing a triage to its own.
                                        (#{:proposed-ticket :proposed-tickets} report-format)
                                        (assoc :answers :proposal))
                              defer   (cond-> {:id :defer :label "Defer decision"
                                               :kind :mutation :style :default}
                                        entry-seq (assoc :seq entry-seq))]
                          ;; Reply (free-text overrides/redo) resumes the agent; Dismiss takes it
                          ;; off the radar nido-side, writing nothing to Notion.
                          (cond
                            ;; A bare row has no workstream and therefore no ledger to accept a
                            ;; verdict from and no agent to Reply to — the only forward move is to
                            ;; start the triage that never ran.
                            bare?   [{:id :start-triage :label "Start triage"
                                      :kind :mutation :style :primary}
                                     dismiss]
                            ;; The accept is NOT gated on parked?, and neither is the defer.
                            ;; apply! writes the verdict itself and resumes nobody, so tying them
                            ;; to a live session ordered two things nothing orders — and left the
                            ;; ticket unappliable in exactly the case it was stuck in already, a
                            ;; triage whose run failed or was budget-killed after writing its
                            ;; report. Reply is gated, because it is the one that reaches for an
                            ;; agent.
                            :else   (cond-> answers
                                      true    (conj accept)
                                      asking? (conj defer)
                                      true    (conj dismiss)
                                      parked? (conj reply-action))))
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
                            parked?          (conj reply-action)
                            (and (not parked?) answering?) (conj answer-action))

                          ;; Halted on a blocker with nobody parked. A free-text answer is
                          ;; still owed, and the :reply resolver records it against the
                          ;; blocker. With neither, nothing here can be answered.
                          (and (not parked?) answering?) [answer-action]

                          (not parked?) []

                          ;; Done is left out while a blocker is owed an answer, for the reason
                          ;; it is left out beside the branches above: settling the workstream is
                          ;; no answer to the question it is halted on.
                          :else
                          (cond-> [reply-action]
                            (not answering?)
                            (conj {:id :done :label "Done" :kind :mutation :style :primary})))
           ;; Blocked in the merge lane: any named branches first (a drive-home
           ;; halt is where they most often appear), then Reply to resume the agent
           ;; with a note and Drop to take it off the queue (back to :in-progress).
           ;; The usual path is to fix in the worktree and `nido ship` again. The
           ;; branches stand without a parked session; Reply and Drop do not.
           :shipping    (cond
                          (seq answers) (cond-> answers
                                          parked?
                                          (into [reply-action
                                                 {:id :drop  :label "Drop"  :kind :mutation :style :danger}]))
                          parked?       [reply-action
                                         {:id :drop  :label "Drop"  :kind :mutation :style :danger}]
                          :else         [])
           []))
         ;; A blocker asking several questions is answered on its card, one answer per question,
         ;; and sent together: no whole-blocker Reply or Answer beside it, which would record words
         ;; no question could be told it answered.
         actions
         (if several?
           (conj (filterv #(not= :reply (:id %)) actions)
                 ;; :words? — the resolver records a question's answer in words, so the card offers a
                 ;; words field for a question without options. No other submit reads words back.
                 (cond-> {:id :answer-questions :label "Send answers" :kind :mutation :style :primary
                          :words? true}
                   entry-seq (assoc :seq entry-seq)))
           actions)]
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
  "Project one wsv row onto the single spine: its :source is the spine's :origin.

   The one function BOTH row paths pass through — list-workstreams (the board list) and
   work/workstream (the detail pane). Where a workstream's row sits is status's answer and is
   added by `with-status`; a bare row's band is its Notion status's, set by the view."
  [row]
  (-> row
      (assoc :origin (:source row))
      (dissoc :source)))

(def ^:private unrendered-bands
  "Bands the board does not draw a row for, and so does not need a position for.
   :done is dropped by grouped-by-stage outright; :dismissed renders its own
   muted row with one action and nothing about where the work stood; :analysed
   is not work at all — a workstream holding a review-loop analysis has no arc
   to advance and belongs to the operations surface, whose unit is the proposal
   inside it rather than the workstream around it; :drained is every drain and slice, whose own
   page is /drains; :background is a background run (`wcore/background?`), a trigger's skill
   working behind the scenes, which reaches the person only through an ask."
  #{:done :dismissed :analysed :drained :background})

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  release-candidate?
  "Whether `position` is a release candidate: its implementation review converged and it is
   landable as it stands. Land is offered on it, and the move is the person's — but it waits as a
   candidate, never as a question, so it is no gate and never in Needs you."
  [position]
  (= :release-candidate (:at position)))

(defn- release-candidates
  "Every workstream row of `groups` (all-grouped's) that is a release candidate, each with its
   :project, oldest activity first — the order they became ready in, near enough."
  [groups]
  (->> (for [{:keys [project grouped]} groups
             rows (vals grouped)
             row  rows
             :when (release-candidate? (:position row))]
         (assoc row :project project))
       (reduce (fn [acc r] (assoc acc [(:project r) (:ws-id r)] r)) {})
       vals
       (sort-by #(or (:last-activity %) ""))
       vec))

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

(def ^:private ^:dynamic *rows-memo*
  "Atom memoizing `list-workstreams` by project for the extent of one
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

(declare live-session-names replyable? held-project winding-down* gates* board-stamp*)

(defn- band-of
  "The board band of an OPEN workstream, read off its ledger: :drained for a drain or a slice,
   whatever it holds — /drains shows them, never the overview; in progress while status places it
   at an unanswered blocker; an analysis's own band; for a ledger the pipeline cannot read, the
   stage chosen for it else in progress; settled when status's position says a person
   settled it in Notion; a person's or nido's recorded stage choice, while no record of the work
   is newer than it — the intake pen among them, which queueing a report records; a one-off's own band;
   else where its position says the work is."
  [project ws-id origin position]
  (let [chosen   (standing/chosen-stage project ws-id (cws/read-ws project ws-id))
        ;; Status places a ticket settled in Notion since its last stage choice; read off the
        ;; position, never off the entry, so the band and the pane's owed-by are one answer.
        settled? (some? (:settled-in-notion position))]
    (cond
      ;; An unanswered blocker is answered only by an entry naming it, never by a stage choice
      ;; made over it nor by where the workstream came from: status places it :blocked, and the
      ;; band takes that from status. (A settlement never stands over one, so this is not ahead
      ;; of :done.)
      (contains? #{:drain :slice} origin)            :drained
      (= :blocked (:at position))                    :in-progress
      (= :review-run origin)                         :analysed
      ;; A ledger of records the pipeline cannot read is a person's reading, not a derivation:
      ;; it keeps the stage a person chose for it, and is otherwise in progress — never moved
      ;; on to a later band by a default.
      (= :unplaceable (:at position))                (or chosen :in-progress)
      settled?                                       :done
      chosen                                         chosen
      (= :scratch origin)                            :in-progress
      (= :triage-awaiting (:at position))            :triage
      ;; An assigned issue needs no triage: its body is the brief, so it waits for a person to
      ;; pick it up, as a triaged ticket does.
      (= :intake (:at position))                     (if (#{:triaged :issue} (:intake position))
                                                       :ready
                                                       :triage)
      :else                                          :in-progress)))

(def ^:private core-held-cache (atom {:at 0 :h {}}))

(defn- core-held
  "Every workstream's committed state in the core, as the feed delivered it. A process that does
   not follow the feed reads it at most every two seconds, so a board of a thousand rows reads it
   once."
  []
  (if (:following? @wcore/mirror)
    (wcore/held)
    (let [{:keys [at h]} @core-held-cache
          now (System/currentTimeMillis)]
      (if (< (- now at) 2000)
        h
        (:h (reset! core-held-cache {:at now :h (wcore/held)}))))))

(defn- core-of
  "Workstream `ws-id`'s entry in the core, as `wcore/held` holds it, or nil when the core holds
   none. Inside a pass over its project (`cws/over-project`) a process that does not follow the
   feed reads it from the pass's one read rather than reading every project's states again."
  [project ws-id]
  (if-let [st (when-not (:following? @wcore/mirror) (cws/pass-state project ws-id))]
    (wcore/of-state st)
    (wcore/held-ws (core-held) project ws-id)))

;; What a row says about its coordination — its band, who owes it, whether anything runs, whether
;; it needs the person — is one reading: `act/of` over the row's observation, built here because
;; this is the band that reaches every source it takes (records and the core).

(defn- observation
  "Workstream `ws-id`'s `act/Observation`: the record facts `facts` (`pipeline/status`, or nil for
   a settled row nobody reads it for), whether it is settled, and the core's state of it in held
   entry `cw` (nil when the core holds none) — its activity, whether it holds an open unit nobody
   holds, and the claim its turn holds while that turn is :working."
  [project ws-id cw closed? facts]
  (let [core (wcore/activity cw)]
    {:closed?  (boolean closed?)
     :owed-by  (:owed-by facts)
     :paused?  (boolean (or (:paused facts) (wcore/held-units? cw)))
     :core     core
     :movable? (boolean (some #(and (= :open (:status %)) (not (:held? %)))
                              (vals (get-in cw [:state :units]))))
     :claim    (when (and (not closed?) (= :working (:kind core))) (act/observe project ws-id))}))

(defn- coordination
  "The row fields `act/of`'s answer `a` sets: :status as the marks read it, :needs-you, :failed,
   :engagement and :doing — one answer, so no two of them can disagree."
  [a]
  {:status     (select-keys a [:owed-by :live :stalled :paused])
   :needs-you  (:needs-you a)
   :failed     (:failed a)
   :engagement (:engagement a)
   :doing      (:doing a)})

(defn- with-status
  "A spine row with its band, position and coordination.

   The band and position are the work records'. The core moves them too: the ledger reads
   :intake when it records none of the work — so does a workstream nobody activated, whose units
   the core ran all the same — and then the core's coordination says where it stands, and a
   workstream under way is out of the triage pen; a background run sits in :background; a
   workstream the core closed is :done.

   A row the ledger settled takes its band from its closure and reads no status — ~1000
   workstreams sit on a board and nearly all are settled and drawn nowhere, so skipping it is
   what keeps a refresh to the rows a person can see. It is still SHOWN, with status's position,
   when it is dismissed or a pane is open on it (`whole?`)."
  ([project row] (with-status project row false))
  ([project row whole?]
   (if (:bare? row)
     (to-spine row)
     (let [ws-id   (:ws-id row)
           w       (cws/read-ws project ws-id)
           closed  (cws/closure w)
           outcome (:outcome closed)
           spine   (to-spine row)
           cw      (core-of project ws-id)
           obs     #(act/of (observation project ws-id cw %1 %2))]
       (if (and closed (not= :between-phases outcome))
         (let [stage  (if (= :dismissed outcome) :dismissed :done)
               shown? (or whole? (= :dismissed outcome))
               st     (when shown? (pipeline/status project ws-id))]
           (cond-> (merge (assoc spine :stage stage :dismissed? (= :dismissed outcome))
                          (coordination (obs true st)))
             (not st) (dissoc :status)
             st       (assoc :position (:position st))))
         (let [st       (pipeline/status project ws-id)
               pos      (:position st)
               core-at  (when (= :intake (:at pos)) (wcore/position cw))
               core-closed? (get-in cw [:state :closed?])
               ;; The person's Land is a grant the core holds until a merge spends it, never a
               ;; ledger record: until then the candidate is landing, the seat's move on nido's
               ;; instruction.
               landing? (and (contains? (get-in cw [:state :grants]) :merge)
                             (#{:release-candidate :published} (:at pos)))
               band     (cond
                          core-closed?                           :done
                          (wcore/background? cw)                 :background
                          closed                                 :awaiting-gate
                          :else (let [b (band-of project ws-id (:origin spine) pos)]
                                  (if (and core-at (#{:triage :ready} b)) :in-progress b)))]
           (cond-> (merge (assoc spine
                                 :stage      band
                                 :dismissed? false
                                 :position   (cond-> (or core-at pos)
                                               landing? (assoc :at :land-granted
                                                               :next {:stage :land :mode :working-copy})))
                          (coordination (obs (= :done band) st)))
             (not (contains? unrendered-bands band))
             (assoc :phase (wsv/phase-progress project ws-id)))))))))

(defn- par-mapv
  "`(mapv f xs)`, folded in chunks across the processors. Each row's status is its own ledger's
   fold, independent of every other's, and a project holds thousands; the chunks run in futures,
   which convey the enclosing pass (`cws/over-project`)."
  [f xs]
  (let [xs (vec xs)
        n  (max 1 (quot (count xs) (* 2 (.availableProcessors (Runtime/getRuntime)))))]
    (if (<= (count xs) n)
      (mapv f xs)
      (into [] (mapcat deref) (mapv #(future (mapv f %)) (partition-all n xs))))))

(defn- read-rows
  "`list-workstreams` without the memo — one full read of a project's workstreams, in one request
   to the core, which every fold of the read answers from."
  [project]
  (cws/over-project project
                    (fn [_] (par-mapv #(with-status project %)
                                      (wsv/workstream-rows project)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :map]]}
  list-workstreams
  "All of a project's workstreams as enriched rows on the single spine.

   Each rendered row carries :position — where the pipeline says it is and what
   would happen next. The board used to show only which BAND a workstream was in,
   so everything between authoring an intent and opening a draft PR looked
   identical: forty rows reading :in-progress, saying nothing about which of them
   was waiting on a baseline and which on a human."
  [project]
  (if-let [memo *rows-memo*]
    (or (some-> (held-project project) :rows)
        (get @memo project)
        (let [rows (read-rows project)]
          (swap! memo assoc project rows)
          rows))
    (or (some-> (held-project project) :rows)
        (read-rows project))))

(def ^:private board-stages
  "The stages `wsv/grouped-by-stage` draws a band for."
  [:incoming :awaiting-gate :in-progress :shipping :triage :dismissed])

(declare held-winding-down)

(defn- held-grouped
  "`grouped` from `p`'s held rows: the rows of the stages the board draws, and the
   winding-down band."
  [p]
  (assoc (wsv/grouped-by-stage (into [] (mapcat #(get (:by-stage p) %)) board-stages))
         :winding-down (held-winding-down p)))

(defn- held-winding-down
  "The winding-down band from `p`'s held rows: each finished workstream one of the registry's
   live sessions belongs to, found by that session's name, so no other finished row is visited."
  [p]
  (let [live (:live p)
        ids  (distinct (keep #(get (:finished-by-session p) %) live))]
    (->> ids
         (keep (fn [id]
                 (let [w (:wind (get (:entries p) id))]
                   (when-let [s (seq (filter live (:workers w)))]
                     (-> (dissoc w :workers) (assoc :sessions (vec s) :needs-you false))))))
         (sort-by :ws-id)
         vec)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :any [:? :any]] [:vector :map]]}
  winding-down
  "Workstreams of `project` that are FINISHED and still holding ≥1 live session —
   resources you're paying for on work that is over. Never gates (:needs-you
   false); rendered as the Active tab's trailing band with one action:
   bring-down!. Empty when live-names is empty/nil.

   Finished has two carriers, and both are needed. A standing close on the ledger is
   nido's own settlement. `rows` (from list-workstreams) supplies the other: a row in
   :done, which is where a workstream lands the moment a person settles its ticket in
   Notion — Review included — without nido closing it. Keying this band on the close
   alone would drop such a row off the board while its session still held ports. That
   is precisely the silent loss board-bands' reachability guarantee exists to prevent.
   Omit `rows` (or pass nil) for closes alone.

   A row in :done with no close has no outcome to name, so it reports :done — the
   stage the board put it in."
  ([project live-names] (winding-down project live-names nil))
  ([project live-names rows]
   (if-let [p (held-project project)]
     (held-winding-down p)
     (winding-down* project live-names rows))))

(defn- winding-down*
  "`winding-down` read off the records: every finished workstream's sessions."
  [project live-names rows]
  (let [live     (set live-names)
        done-ids (into #{} (comp (filter #(= :done (:stage %))) (map :ws-id)) rows)]
    (if (empty? live)
      []
      (->> (cws/project-records project)
           (keep (fn [w]
                   (let [closed (cws/closure w)]
                     ;; Between phases is closed and not finished: it has its own band,
                     ;; and a row is on exactly one.
                     (when (or (and closed (not= :between-phases (:outcome closed)))
                               (contains? done-ids (:id w)))
                       (let [sessions (cws/sessions project (:id w))
                             live-s   (filterv #(contains? live (:name %)) sessions)]
                         (when (seq live-s)
                           {:ws-id     (:id w)
                            :project   (name project)
                            :stage     :winding-down
                            :origin    (classify-origin w)
                            :label     (wsv/label w sessions)
                            :outcome   (or (:outcome closed) :done)
                            :sessions  (mapv :name live-s)
                            :needs-you false}))))))
           vec))))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:? :any]] :map]}
  grouped
  "Workstreams grouped along the single spine for the board:
   {:triage {:in-flight [..] :queued [..]} :ready [..] :in-progress [..]}.
   Scratch one-offs fold into :in-progress (done via list-workstreams' remap);
   :done is omitted. The board renders these groups directly."
  ([project] (if-let [p (held-project project)]
                (held-grouped p)
                (grouped project (live-session-names project))))
  ([project live-names]
   (if-let [p (held-project project)]
     (held-grouped p)
     (let [rows (list-workstreams project)]
       (assoc (wsv/grouped-by-stage rows)
              ;; rows, not just the records: a Notion-driven row can be finished
              ;; without nido ever writing :closed (see winding-down).
              :winding-down (winding-down project live-names rows))))))

(defn- dir-stamps
  "Last-modified millis of `dir` and of each of its subdirectories, in listing
   order; [0] when `dir` does not exist. java.io.File rather than babashka.fs:
   this runs on every board tick, and per-entry Path wrapping was most of its
   cost."
  [^java.io.File dir]
  (into [(.lastModified dir)]
        (keep #(when (.isDirectory ^java.io.File %) (.lastModified ^java.io.File %)))
        (.listFiles dir)))

(defn- ws-files-stamp
  "The workstream's own records, as `board-stamp` reads them: its ledger and identity as the
   core holds them (`cws/ledger-stamp`), and its files — its directory, sessions/ and every
   session's, runs/, and each run it registers with that run's locks/. Directory mtimes stand
   for the files in them, which every writer creates or renames into place."
  ([project ^java.io.File wd] (ws-files-stamp project wd (cws/ledger-stamp project (.getName wd))))
  ([project ^java.io.File wd ledger]
  (let [runs (java.io.File. wd "runs")]
    [(.lastModified wd)
     ledger
     (dir-stamps (java.io.File. wd "sessions"))
     (.lastModified runs)
     (mapv (fn [^java.io.File m]
             (let [rd (java.io.File. (str (cstate/run-dir (.getName m))))]
               [(.lastModified rd)
                (.lastModified (java.io.File. rd "locks"))]))
           (.listFiles runs))])))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  board-stamp
  "A value that changes whenever what `grouped` answers for `project` may have changed,
   cheap enough to take on every board tick — so a board can skip a rebuild while it is equal.

   While this process holds its rows (`hold!`) it is their version, which moves only when a
   refresh changed a row: it reads no file, and a caller wanting the rows current makes the
   refresh first (`refresh-list!`).

   Otherwise it is read off the core and the disk: each workstream's ledger as the core holds
   it, and directory mtimes for the files in them, which every session and run writer creates or
   renames into place (`ws-files-stamp`), and
   the session registry and the Notion page facts are compared as values, because their files
   are rewritten on every poll whether or not anything changed. NOT covered, by construction: a
   session port that stops answering, which the winding-down band reads; it touches no disk, so
   a caller that must show it has to rebuild on a clock as well."
  [project]
  (if-let [p (held-project project)]
    ;; Holding, the rows are what the board reads and only a refresh moves them, so their
    ;; version is the stamp; no settled workstream is statted for it.
    [:held (:version p)]
    (board-stamp* project)))

(defn- board-stamp*
  [project]
  (let [ws-root (java.io.File. (str (cstate/workstreams-dir project)))]
    ;; Membership is the core's, and so is each ledger's stamp: one request for every
    ;; workstream's summary, whose newest seq stands for its ledger — so one the core holds with
    ;; no directory is stamped too.
    [(mapv (fn [[id ledger]] (ws-files-stamp project (java.io.File. ws-root ^String id) ledger))
           (cws/ledger-stamps project))
     (sstate/read-registry)
     (notion-cache/project-page-facts project)]))

(defn- session-facet
  "One of the workstream's sessions (`cws/sessions`) as the pane lists it: the core's turns drive
   every one, so each is autonomous, and it reads :up while the core has it ready, :down
   otherwise. No session waits on a reply — an ask is the core's — and none carries brakes."
  [s]
  {:name           (:name s)
   :autonomy-level :autonomous
   :parked?        false
   :status         (if (= :ready (:status s)) :up :down)
   :brakes         nil
   :error          nil})

(defn- ledger-summary
  "Light ledger facet for the detail view: its key (BR-#### / slack id), where its triage stands
   (`tickets/status-of` the workstream carrying it), and report count (from that workstream's
   ledger). nil when `k` is nil (the workstream carries no ledger ref)."
  [project k]
  (when k
    (let [w (cws/find-by-ref-id project k)]
      {:key          k
       :status       (tickets/status-of project w)
       :report-count (count (:entries w))})))

;; What a ledger entry and a proposal ARE now lives one band down, in
;; `record.proposal`, so the improvement source can ask the same question
;; without reaching this facade. These are the names the rest of this namespace
;; already used; aliasing them here keeps the move structural.
(def ^:private first-heading proposal/first-heading)
(def ^:private entry->report proposal/entry->report)
(def ^:private active-ledger proposal/active-ledger)

(defn ^{:malli/schema [:=> [:cat [:? [:maybe :map]]] :map]}
  session-recovery
  "Session recovery as the Operations page shows it: counts, one row per live or
   recently settled cause, and the activity feed — see view.recoveries/overview.

   The door, not the derivation. It gathers what recovery keeps — each recovery of
   every project that declares a :session-failure trigger, with what it recorded (a
   run's own records; a workstream's ledger entries, from before runs), and the
   pacing that trigger declares, so the waiting times shown are the ones the source
   fires on. Read on every call.

   Failure records are read only where the page needs them: undischarged ones,
   the newest few a shown recovery names, and ones on the feed page being asked
   for. Every
   other failure is known by its id alone. Kept failures are never pruned and
   each carries its log tails, so reading all of them on a five-second poll
   would cost more with every failure nido has ever recovered.

   `opts`: :now (an Instant, default the present) and :feed-from (the feed
   position to page from, nil for the newest)."
  ([] (session-recovery nil))
  ([{:keys [now feed-from]}]
   (let [declaring (for [[pname _] (project/list-projects)
                         :let  [pk (keyword pname)
                                t  (some #(when (= :session-failure (-> % :source :type)) %)
                                         (triggers/load-for-project pk))]
                         :when t]
                     [pk t])
         recs      (vec (for [[pk _] declaring
                              r      (start-failures/recoveries pk)]
                          (if (:run? r)
                            r
                            (assoc r :entries
                                   (into {} (for [kind [:session-diagnosis :session-restored
                                                        :blocker :merged :pr-opened]]
                                              [kind (vec (cws/entries-of pk (:ws-id r) kind))]))))))]
     (let [now   (or now (java.time.Instant/now))
           index (mapv (fn [id] {:id id :ms (failure/id-ms id)}) (failure/ids))
           gone  (start-failures/discharged-ids recs)
           need  (-> #{}
                     (into (comp (map :id) (remove gone)) index)
                     (into (recoveries-view/page-failure-ids index feed-from))
                     (into (comp (filter #(recoveries-view/shown? % now))
                                 (mapcat #(recoveries-view/row-sample (:named %))))
                           recs))]
       (recoveries-view/overview
      {:failures      (vec (keep #(some-> (failure/failure %) (as-> f (assoc f :cause (failure/cause f))))
                                 (sort need)))
       :failure-index index
       :recoveries recs
       :now        now
       :feed-from  feed-from
       :pacing     (start-failures/pacing (-> declaring first second :source))})))))

;; ---------------------------------------------------------------------------
;; Review-queue grooming

(def ^:private review-queue-log-size
  "How many groomings the log shows: two weeks of nightly runs."
  14)

(defn ^{:malli/schema [:=> [:cat] [:vector :map]]}
  review-queues
  "The latest review-queue grooming of every project whose triggers declare the
   plan trigger, as view.review-queue/overview shows one, with `:log` — its newest
   groomings, newest first, as view.review-queue/log-entry shows each. A project
   that declares it and was never groomed is there too, at `:stage :none` with an
   empty log, so the surface can offer to run it. Read on every call."
  []
  (vec (for [[pname _] (project/list-projects)
             :let  [pk (keyword pname)]
             :when (some #(= review-queue/plan-trigger (:name %))
                         (triggers/load-for-project pk))
             :let  [applies (review-queue/apply-runs-by-plan pk)
                    grooming (fn [run]
                               (let [id (:id run)]
                                 {:project   pk
                                  :plan-run  run
                                  :plan      (some-> id review-queue/read-plan)
                                  :decisions (if id (review-queue/read-decisions id) {:decisions {}})
                                  :results   (if id (review-queue/read-results id) {})
                                  :apply-run (get applies id)}))
                    runs (take review-queue-log-size (review-queue/plan-runs pk))]]
         (assoc (review-queue-view/overview (grooming (first runs)))
                :log (mapv (comp review-queue-view/log-entry grooming) runs)))))

(defn ^{:malli/schema [:=> [:cat :RunId :int :keyword] :map]}
  decide-review-item!
  "Approve or skip item `n` of plan run `run-id`. A verdict other than :approved
   or :skipped records nothing — there is no safe default for a choice nobody
   made. See record.review-queue/decide! for the refusals."
  [run-id n verdict]
  (if (#{:approved :skipped} verdict)
    (review-queue/decide! run-id n verdict)
    {:decision :no-verdict}))

(defn ^{:malli/schema [:=> [:cat :RunId [:=> [:cat :keyword :map :string] :any]] :map]}
  begin-review-apply!
  "Freeze plan run `run-id`'s decisions and queue its apply by calling `fire` with the apply
   trigger, its payload and a key naming the plan — the caller's control/fire!, since an
   envelope is something done to the daemon, which is the control facade's vocabulary rather
   than this one's. The key is what makes the apply queued once per plan: a retry after any
   stop finds it taken. Refusals are record.review-queue/apply!'s."
  [run-id fire]
  (review-queue/apply! run-id #(fire review-queue/apply-trigger
                                     (review-queue/apply-payload run-id)
                                     (review-queue/apply-key run-id))))

(defn- hold-state
  "What a hold's row — its engagement and who owes it, so the sweep and the board read one
   activity — means for the sweep it holds.
   `:idle` and `:stalled` are the ones that matter: an open workstream nothing is
   working on is one nothing will ever close, and a close is the only thing that
   releases the sweep."
  [{:keys [engagement status]}]
  (case engagement
    ;; Owed to a person by its position — a halt only their answer clears — is waiting on them
    ;; whether or not the core has raised it as an ask yet.
    :idle           (if (= :person (:owed-by status)) :waiting-on-you :stuck)
    :parked-at-gate :waiting-on-you
    (:active :queued) :working
    :stalled        :stuck
    ;; Closed under the attempt, which the next read of the attempts lets go of.
    :settled        :working))

(defn- last-session-change
  "The latest transition any of the workstream's runs recorded, or nil when there is none.
   Compared as strings: run histories are ISO-8601 UTC by construction."
  [project ws-id]
  (->> (mapcat #(map :at (:state-history %)) (runs/runs-of project ws-id))
       (remove nil?)
       sort
       last))

(defn ^{:malli/schema [:=> [:cat] [:vector :map]]}
  improvement-holds
  "What holds each project's improvement sweep, as one map per open improvement attempt — a run, or
   a workstream from before runs: `:project :ws-id :address :title :state :started-at`, and
   `:ended-at` on a `:stuck` one. Empty when nothing holds any sweep.

   `:state` is `:working`, `:waiting-on-you` (a session is parked at a gate) or
   `:stuck` (no live session, so nothing will close it). The sweep never tells
   these apart — it waits on a close whatever the session is doing — so a hold
   whose session died reads, from the sweep, exactly like one mid-edit.

   Reads the open workstreams rather than the `:held-by` the sweep's poll
   writes: those workstreams ARE the hold, and the poll's copy is up to an hour
   stale and says nothing about whether anyone is still working. Only projects
   whose triggers declare an :improvement-sweep source are read. Read on every
   call."
  []
  (vec (for [[pname _] (project/list-projects)
             :let  [pk (keyword pname)]
             :when (some #(= :improvement-sweep (-> % :source :type))
                         (triggers/load-for-project pk))
             {:keys [ws-id address open? run?] :as a} (proposal/attempts pk)
             :when open?
             :let  [w        (when-not run? (cws/read-ws pk ws-id))
                    ;; A run holds while its turn runs: nobody is waited on, and its end is
                    ;; recorded whatever its agent did, so it is never stuck.
                    state    (if run?
                               :working
                               (hold-state (with-status pk (wsv/workstream-row pk w) true)))]]
         (if run?
           {:project pname :ws-id ws-id :address address :title (:title a) :state state
            :started-at (:started-at a)}
           (cond-> {:project    pname
                  :ws-id      ws-id
                  :address    address
                  :title      (some #(when (= proposal/improvement-adapter (:adapter %)) (:title %))
                                    (:external-refs w))
                  :state      state
                  :started-at (:created-at w)}
             (= :stuck state) (assoc :ended-at (last-session-change pk ws-id)))))))

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
   they had vetoed a change that lands minutes later.

   `:disposition` is here for the same reason and answers the mirror question.
   The sweep reads a plan to know what it may still claim; the board needs it to
   know what it may stop showing. A proposal a plan disposed `:file` or `:no-op`
   is finished — nothing landed and nothing is going to — and it records no
   landing precisely because nothing landed, so the board had no way to tell it
   from a row nobody had got to and kept it in the backlog forever."
  [project]
  (let [pk       (keyword (name project))
        reserved (into #{}
                       (comp (filter :open?) (mapcat :addresses))
                       (proposal/claim-attempts pk))
        disposed (proposal/dispositions-by-address (proposal/plans-of pk))]
    (mapv (fn [p]
            (let [addr (proposal/address p)]
              (cond-> p
                (reserved addr)  (assoc :reserved? true)
                (disposed addr)  (assoc :disposition (disposed addr)))))
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
  [ledger entry]
  (let [r (entry->report ledger entry)]
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
  [ledger entries seq]
  (let [by-seq (into {} (map (juxt :seq identity)) entries)]
    (some->> (get by-seq seq) (entry->report ledger) hydrate)))

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
  "The workstream's most recent record of its work as a `:format`-tagged gate report,
   or nil. Resolves the active ledger (the workstream's own event store), reads
   its newest entry that is not a status entry — a close or a stage set says nothing
   a gate shows — and finally falls back to stored intake text so an un-triaged
   :incoming Slack report still shows its message body."
  [project ws-id]
  (let [{:keys [ledger entries]} (active-ledger project ws-id)
        newest (last (remove #(cws/status-kinds (:kind %)) entries))]
    (if newest
      (hydrate (entry->report ledger newest))
      (intake-fallback project ws-id))))

(defn- owed-seq
  "The :seq of the record the move `position` owes a person was placed on — the record its
   question was written in — or nil when the position owes a person nothing or its move reads no
   record. The position's own reading (`:placed-by`, what `pipeline/of`'s fold read to place it),
   never a second one: a later record of the same kind leaves the move owed over the earlier one."
  [position]
  (when (awaiting-human position) (:placed-by position)))

(defn- answered-report
  "The report a gate's actions answer: the record the move `position` owes a person was placed on
   (`owed-seq`) — whatever was appended after it — or `report` (the latest) when the position owes
   no such record, or its record cannot be read.

   A blocker holds the workstream until an answer names it, whatever lands on top: a review
   round, a verdict, or a close that a reopen undoes. So does every other owed move: a triage
   verdict until it is accepted. A gate built from the latest report then answered the wrong record: a note
   appended over a triage took its directions away, and Apply accepted the note.

   The one reading of which record a gate acts on. The gate builds its actions from it, the Ask
   reads it (`ask-inputs`), and every resolver a positioned button reaches re-reads it
   (`gate-report`) as its freshness check: a click is current while the record it was rendered
   from is still the one owed."
  [project ws-id report position]
  (let [{:keys [ledger entries]} (active-ledger project ws-id)
        owed (owed-seq position)]
    (if (and owed (not= owed (:seq report)))
      (or (report-at ledger entries owed) report)
      report)))

(defn- gate-report
  "The report a gate's actions answer NOW: `answered-report` over the latest report and the
   position the pipeline derives at this moment. What a resolver checks a click's :seq against."
  [project ws-id]
  (answered-report project ws-id (latest-report project ws-id) (pipeline/of project ws-id)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  environment
  "The workstream's single current environment: the session of its last open unit the core has
   ready (`cws/sessions`), or nil when no open unit's session is up yet. The gate's Land ships
   whatever this answers. Callers use (:name env) to resolve dev-state/facts."
  [project ws-id]
  (->> (cws/sessions project ws-id)
       (filter #(and (:open? %) (= :ready (:status %))))
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
     :tag           (:tag row)
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
   re-derives whether a baseline held or a design was cleared, because a second
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
        design  (standing/governing-design project ws-id)
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
                             ;; True only on a ledger holding a person's grant from
                             ;; before every design was cleared by its round.
                             :decided? (boolean (:decided? st))
                             ;; Its round's clearance, or an older grant — either says
                             ;; it may be built.
                             :cleared? (boolean (:cleared? st))
                             :blocked (:blocked st)
                             :record design}))))

(defn- plan
  "Where the workstream stands in the plan that governs it (`standing/governing-design`), phase by
   phase, or nil for an unphased one: {:current n :of m :waiting? :owed-gate <exit> :phases
   [{:n :claim :habitable :exit :undo :state :opened-by} …]}.

   `:state` is :landed below the current phase — and the current one once it has landed —
   :current while its work is underway, :ahead above it. `:opened-by` is the gate that opened
   a phase, joined by `record.phase/opened-by` — `record.phase/progress`'s reading, so the
   phase a gate is shown against is the phase it opened. `:owed-gate` is the landed phase's exit while the
   workstream waits between phases, the gate a person asserts."
  [project ws-id w]
  (when-let [d (standing/governing-design project ws-id)]
    (when-let [p (phase/progress d (:entries w))]
      (let [phases   (vec (:phases d))
            opened   (let [rows (into {} (map (juxt :seq identity))
                                      (cws/entries-of project ws-id :phase-gate))]
                       (update-vals (phase/opened-by d (:entries w)) #(get rows (:seq %) %)))
            waiting? (= :between-phases (:outcome (cws/closure w)))
            cur      (:current p)]
        {:current   cur
         :of        (:of p)
         :waiting?  waiting?
         :owed-gate (when waiting? (:exit p))
         :phases    (vec (map-indexed
                          (fn [i ph]
                            (let [n (inc i)]
                              (cond-> (assoc (select-keys ph [:claim :habitable :exit :undo]) :n n
                                             :state (cond (< n cur)       :landed
                                                          (> n cur)       :ahead
                                                          (:landed? p)    :landed
                                                          :else           :current))
                                (opened i) (assoc :opened-by (select-keys (opened i) [:evidence :at :seq])))))
                          phases))}))))

(def ^:private phase-trail-kinds
  "What a phase's own landing leaves behind — the kinds a gate puts behind the next phase."
  #{:implementation-completed :review :pr-opened :ship-submitted :merged})

(defn- current-phase-entries
  "`entries` as the arc reads them: a :phase-gate drops, and so does the implementation
   trail written before the newest one — that was the landed phase's work, and the arc
   draws the phase underway. Every entry stays in the index; the Plan section is where
   earlier phases and their gates are read."
  [entries]
  (let [g (->> entries (filter #(= :phase-gate (:kind %))) (map :seq) (reduce max 0))]
    (remove #(or (= :phase-gate (:kind %))
                 (and (< (:seq %) g) (phase-trail-kinds (:kind %))))
            entries)))

(declare gate error pause-of)

(defn- arc-at
  "The arc of stages `entries` travelled, read at `position`: the stage a person owes marked
   :awaiting — by the position, or by an ask the core holds for them (`needs-you?`) — and
   otherwise the stage `doing` has something running in marked :running."
  [entries position doing needs-you?]
  (pipeline/arc (current-phase-entries entries)
                {:at       (:stage position)
                 :active   (pipeline/stage-active position doing)
                 :awaiting (when (or (awaiting-human position) needs-you?) (:stage position))}))

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
     (let [sessions (cws/sessions project ws-id)
           ;; The same row the board list builds, with the same status over it, so the pane and
           ;; the list cannot place one workstream two ways.
           row      (with-status project
                                 (wsv/workstream-row project w (notion-cache/project-page-facts project))
                                 true)
           {:keys [ledger entries]} (active-ledger project ws-id)
           sel      ((set (map :seq entries)) selected-seq)
           index    (when (seq entries)
                      ;; mark BEFORE reversing — mark-superseded reads oldest-first
                      (vec (reverse (mark-superseded (mapv #(index-row ledger %) entries)))))
           ;; The newest record of the WORK: a close, reopen or stage move appended after a
           ;; blocker or a gate report says nothing a gate shows, so it neither displaces that
           ;; report nor makes it stale. Opening such a status entry still reads as current.
           work-e   (last (remove #(cws/status-kinds (:kind %)) entries))
           latest?  (or (nil? sel)
                        (= sel (:seq (last entries)))
                        (and work-e (= sel (:seq work-e))))
           ;; The current report, derived from the snapshot `entries` ALREADY
           ;; holds rather than re-read from disk. A second read is a second
           ;; moment: an entry landing between them leaves :on-latest? true with
           ;; the viewer showing one question and the action bar — and the :seq
           ;; its option buttons carry — built from the next one, which is
           ;; precisely the card/button binding the seq check exists to enforce.
           ;; Only a ledger with no work record still reads (there is no entry to
           ;; bind to, and latest-report is where the intake-text fallback lives).
           latest-r (if work-e
                      (hydrate (entry->report ledger work-e))
                      (latest-report project ws-id))
           ;; Read ONCE and used twice — by the heading and by the arc beneath
           ;; it. Asking pipeline/of a second time for the arc would be a second
           ;; moment, and a heading clamped back to the approval above an arc
           ;; that still called implementation done is exactly the disagreement
           ;; the clamp exists to remove.
           position (or (:position row) (pipeline/of project ws-id))]
       {:ws-id        ws-id
        ;; Who owes the next move and whether anything is live — status's, as the board row
        ;; carries it, so the pane and the row cannot say different things.
        :status       (:status row)
        ;; The person's standing pause, with the note they gave — what the pane says beside
        ;; its Resume.
        :pause        (pause-of project ws-id w row)
        :project      project
        :origin       (classify-origin w)
        :stage        (:stage row)
        :label        (:label row)
        :tag          (:tag row)
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
        :position     position
        ;; The trail, at the granularity work actually moves in. Folded from the
        ;; snapshot `entries` already holds rather than re-read, for the reason
        ;; :report is: a second read is a second moment, and an arc built from a
        ;; later ledger than the index below it would disagree with the rows a
        ;; reader is looking at.
        ;; The stage the work is in comes off the position read above, so the
        ;; heading and the arc under it are one reading and cannot disagree.
        ;; What is running rides beside it, on the stage it is running in.
        :arc          (arc-at entries position (:doing row) (:needs-you row))
        ;; This workstream's card on the needs-you list, when it holds one: its question, the
        ;; actions that answer it and the session a reply reaches, which the pane renders through
        ;; the question card. Read from the core as the list's is, so the two cannot ask different
        ;; things. A core that does not answer leaves the pane without a card — the rail badge
        ;; says the project was not read — rather than failing the whole pane.
        :gate         (try (gate project ws-id) (catch Exception _ nil))
        ;; Its card on the Errors list, when its machine stopped on a failure: what failed, and
        ;; the recovery. Read as the gate is.
        :error        (try (error project ws-id) (catch Exception _ nil))
        ;; A phased workstream's plan, phase by phase — nil for an unphased one.
        :plan         (plan project ws-id w)
        :holds        (holds project ws-id)
        :entries      index
        :selected-seq sel
        ;; Nothing open, or the open entry is the CURRENT one. Live actions are
        ;; offered only on the current entry — older entries are an immutable
        ;; read-back, not something you act on — and the resting pane, which is
        ;; reading nothing at all, acts on the workstream as it stands.
        :on-latest?   latest?
        ;; A ledger renders only what the reader opened. With no record of the work —
        ;; no ledger, or only status entries a close or stage move wrote — the intake
        ;; text is the pane's only content, so it stands in unasked.
        :report       (cond sel    (report-at ledger entries sel)
                            work-e nil
                            :else  latest-r)
        ;; What the pane's live ACTIONS derive from — which is not what the
        ;; viewer is showing. The resting pane has nothing open (:report nil) and
        ;; still acts on the workstream as it stands, so a parked blocker's
        ;; branches must reach the action bar without the reader first opening
        ;; the entry; deriving from :report there would offer generic Reply/Done —
        ;; including the Done the option branch deliberately withholds — for a
        ;; question that named its answers. nil once an OLDER entry is open:
        ;; :on-latest? is false there and no actions render at all.
        :action-report (when latest? (answered-report project ws-id latest-r position))
        :environment  (environment project ws-id)
        ;; Whether :reply reaches anyone — a conductor's open escalation counts with a parked agent.
        :replyable?   (replyable? project ws-id)
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
           ;; Holding, the coverage the rows keep answers which workstream took the page,
           ;; without reading every workstream to find it.
           now (when-let [br (:br fct)]
                 (if-let [p (held-project project)]
                   (some-> (or (get (:covered-by p) ws-id) (get (:covered-by p) br)) (#(cws/read-ws project %)))
                   (cws/find-by-ref-id project br)))]
       (cond
         now (workstream project (:id now) selected-seq)
         fct (bare-pane project ws-id fct)
         :else nil)))))


(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :boolean]}
  replyable?
  "Whether a :reply has someone to reach on the workstream: an ask the core holds open for the
   person. What every surface offering Reply reads."
  [project ws-id]
  (boolean (seq (wcore/open-asks (core-of project ws-id)))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  asks-several?
  "True when `report` is a blocker asking more than one question — answered on its card, one
   answer per question, by :answer-questions."
  [report]
  (boolean (and (= :blocker (:format report)) (next (report/blocker-questions report)))))

(def ^:private ask-kinds
  "The ledger kinds a person's question can live in beside the gate's report — a triage verdict
   or a Slack proposal, a published PR — and the intent the question is judged against. Which of
   them a question reads is the ask registry's to say; this only fetches them."
  #{:triage :proposed-ticket :proposed-tickets :pr-opened :intent})

(defn- ask-inputs
  "What `ask/ask` reads for a gate: a hydrated record of each of `ask-kinds`, oldest first — for
   the kind the owed move was placed on, that record (`owed-seq`), whatever was appended after it;
   for every other kind, its newest — the live intent (the newest :intent — an amendment is
   appended, so the newest is the one standing), and the conductor's note when its newest decision
   is an escalation that parked nothing and was recorded over the very move `position` owes — its
   :owes equal to the position's."
  [project ws-id report position actions]
  (let [{:keys [ledger entries]} (active-ledger project ws-id)
        owed     (owed-seq position)
        owed-e   (some #(when (= owed (:seq %)) %) entries)
        chosen   (->> entries
                      (filter #(ask-kinds (:kind %)))
                      (group-by :kind)
                      (map (fn [[k es]] (if (and owed-e (= k (:kind owed-e))) owed-e (last es))))
                      (sort-by :seq))
        records  (mapv #(hydrate (entry->report ledger %)) chosen)
        intent   (last (filter #(= :intent (:format %)) records))
        decision (->> entries (filter #(= :conduct-decision (:kind %))) last)
        d        (some->> decision (entry->report ledger))
        ;; Bound by identity, never by order: the escalation names the move it was written over
        ;; (:owes), and rides only on that same move.
        note     (when (and d
                            (= :escalate (first (:decision d)))
                            (nil? (:parked d))
                            (some? (:owes position))
                            (= (:owes d) (:owes position)))
                   (some-> (get-in d [:decision 1]) report/blocker-question-text str/trim not-empty
                           (->> (str "The conductor adds: "))))]
    (cond-> {:report  report
             :entries (vec (remove #(= :intent (:format %)) records))
             :actions (vec actions)}
      intent (assoc :intent intent)
      note   (assoc :note note))))

(defn- ask-options
  "An operator ask's options as {:option :consequence :recommended?}, whether its writer gave
   strings or maps. A string option recommends nothing: which one is recommended is the writer's
   to say, never its position."
  [options]
  (mapv #(if (map? %)
           (cond-> {:option (str (or (:option %) (:label %))) :consequence (some-> (:consequence %) str)}
             (true? (:recommended %)) (assoc :recommended? true))
           {:option (str %)})
        options))

(defn- core-questions
  "The questions operator ask content puts to the person, one per decision, each as
   {:question :context :options :recommendation}: its `:questions` when it asks several, else its
   one question with its options."
  [{:keys [question explored options questions recommendation]}]
  (let [q (or (some-> question str str/trim not-empty) "What should happen next?")]
    (if (seq questions)
      (vec (map-indexed (fn [i x]
                          {:question       (str/trim (str (:question x)))
                           ;; What was explored is what every question depends on: it leads.
                           :context        (vec (remove str/blank? (cond-> [(str (:context x))]
                                                                    (= 0 i) (->> (cons (str explored))))))
                           :options        (ask-options (:options x))
                           :recommendation (some-> (:recommendation x) str str/trim not-empty)})
                        questions))
      [{:question q :context (vec (remove str/blank? [(str explored)]))
        :options (ask-options options)
        :recommendation (some-> recommendation str str/trim not-empty)}])))

(defn- several? [content] (boolean (seq (:questions content))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  core-ask-card
  "The question card for operator ask `a`, in the Ask shape every card renders. Whatever its
   number of questions, the person chooses within each and sends them together by
   :answer-questions — each question its choice, a comment of their own on it, both, or left
   open — beside a free answer and a withdrawal. No option answers by itself, so a comment
   written after choosing is never lost to a click that already sent."
  [{:keys [content]}]
  (let [qs     (core-questions content)
        after  (some-> (:after content) str str/trim not-empty)
        head   (or (some-> (:question content) str str/trim not-empty) (:question (first qs)))
        card-q (fn [i {:keys [question context options recommendation]}]
                 (cond-> {:question question
                          :context  context
                          :options  (vec (map-indexed
                                          (fn [j {:keys [option consequence recommended?]}]
                                            (cond-> {:letter  (report/option-letter j)
                                                     :label   option
                                                     :summary (or consequence "")}
                                              recommended? (assoc :recommended? true)))
                                          options))}
                   recommendation                           (assoc :recommendation recommendation)
                   (and after (= i (dec (count qs))))       (assoc :after after)))]
    {:kind        :operator-ask
     :question    head
     :questions   (vec (map-indexed card-q qs))
     :submit      :answer-questions
     :answer-does [{:action :answer-questions :label "Send answers"
                    :does "Sends what you chose and wrote for each question — a choice, a comment, both, or left open; the conductor hears them."}
                   {:action :reply :label "Answer" :does "Sends your words as the answer; the conductor hears them."}
                   {:action :withdraw :label "Withdraw" :does "Takes the question back unanswered; the conductor hears that."}]}))

(defn- gate-of-asks
  "The gate of spine row `row` whose asks awaiting the person are `awaiting`: the oldest of them,
   offered for answer. nil when there are none."
  [project row awaiting]
  (when-let [a (first (sort-by :id awaiting))]
    ;; :notes? — the resolver records a comment per question, so the card offers every question
    ;; a note field beside its options.
    (let [actions [{:id :answer-questions :label "Send answers" :kind :mutation :style :primary :notes? true}
                   {:id :reply :label "Answer" :kind :resume :style :default}
                   {:id :withdraw :label "Withdraw" :kind :mutation :style :danger}]]
      {:ws-id        (:ws-id row)
       :project      (name project)
       :origin       (:origin row)
       :stage        (:stage row)
       :label        (:label row)
       :tag          (:tag row)
       :links        (:links row)
       :doing        (:doing row)
       :report       nil
       :actions      actions
       :ask          (core-ask-card a)
       :core-ask     (:id a)
       :session      nil
       :resume-error nil
       :working?     false})))

(defn- comment-lines
  "The person's comment `note` on question `n`, marked as theirs and indented under the question's
   line, every line of it."
  [n note]
  (str/join "\n" (cons (str "   The person's comment on question " n ":")
                        (map #(str "     " %) (str/split-lines note)))))

(defn- questions-answer
  "The person's answers to the questions of ask content, as the one string the asker reads, from
   `picks` — one {:letter? :note?} per question, in question order. nil when no question has an
   option or a note.

   With no note anywhere it reads as it always did: for content without :questions, the chosen
   option's label alone, as its option action answered; for content with :questions, one line per
   question, its choice or left open. Once any question carries a note, every question is a line —
   its option, that none was chosen, or left open — and a noted question's comment follows its own
   line, marked as the person's comment on that question."
  [content picks]
  (let [qs    (core-questions content)
        picks (vec picks)
        rows  (map-indexed
               (fn [i {:keys [question options]}]
                 (let [{:keys [letter note]} (get picks i)
                       j    (when letter (first (keep-indexed #(when (= letter %2) %1) report/option-letters)))
                       opt  (when j (get options j))
                       note (some-> note str str/trim not-empty)]
                   {:n (inc i) :question question :options options :opt opt :note note}))
               qs)
        noted? (some :note rows)]
    (cond
      (not (some #(or (:opt %) (:note %)) rows)) nil

      (and (not noted?) (not (several? content)))
      (str (:option (:opt (first rows))))

      :else
      (str/join "\n"
                (for [{:keys [n question options opt note]} rows]
                  (str n ". " question " — "
                       (cond opt (str (:option opt))
                             (and note (seq options)) "no option chosen"
                             note "no options were offered"
                             :else "(left open)")
                       (when note (str "\n" (comment-lines n note)))))))))

(defn- core-gate
  "The gate of spine row `row`: its oldest ask awaiting the person in the core, offered for answer.
   nil when the core holds none for it."
  [project row]
  (gate-of-asks project row (remove domain/machine-ask? (:awaiting (core-of project (:ws-id row))))))

(def ^:private failure-words
  "What each reason the core records on a failed or cancelled turn means, in a person's words."
  {:ended-without-outcome "its agent exited without reporting an outcome"
   :never-started         "it never started"
   :no-such-executor      "no executor could run it"
   :ask-without-call      "it asked without saying what"
   :not-an-outcome        "it reported something that is not an outcome"
   :deadline              "it ran past its deadline"})

(defn- turn-pos
  "Where conductor turn `t` falls among its workstream's: the position its id ends in."
  [t]
  (or (some-> (re-find #"(\d+)$" (str (:id t))) second parse-long) 0))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:vector :map]]}
  conductor-failures
  "The conductor turns of core state `state` that failed or were cancelled since one last
   completed, asked or ran, oldest first — what stopped its conductor — each as
   {:turn :outcome :why :stderr?}: :why says the recorded reason in words, :stderr the last its
   run wrote there when the core kept it."
  [state]
  (->> (vals (:turns state))
       (filter #(nil? (:unit %)))
       (sort-by turn-pos)
       reverse
       (take-while #(#{:failed :cancelled} (:status %)))
       reverse
       (mapv (fn [{:keys [id status detail ended-by]}]
               (let [reason (:reason detail)]
                 (cond-> {:turn    id
                          :outcome status
                          :why     (or (failure-words reason)
                                       (some-> reason name (str/replace "-" " "))
                                       (some-> ended-by name (str/replace "-" " ") (->> (str "cancelled: ")))
                                       (name status))}
                   (:stderr detail) (assoc :stderr (:stderr detail))))))))

(defn- error-of-asks
  "The error card of spine row `row`, whose core state is `state`, when `failures` — its open
   machine-side asks (`domain/machine-ask?`) — hold one: what failed, saying why from the turns
   that failed, and the one recovery, :wake. nil when there are none. An error asks the person
   nothing; it is on no needs-you list."
  [project row state failures]
  (when-let [a (first (sort-by :id failures))]
    (let [fs (conductor-failures state)
          n  (count fs)]
      {:ws-id    (:ws-id row)
       :project  (name project)
       :origin   (:origin row)
       :label    (:label row)
       :tag      (:tag row)
       :links    (:links row)
       :what     "Its conductor stopped"
       :because  (if (seq fs)
                   (str "The last " (if (= 1 n) "conductor turn" (str n " conductor turns"))
                        " failed — " (:why (last fs)) " (" (:turn (last fs)) ").")
                   "Its conductor turns failed, so nothing wakes it.")
       :failures fs
       :core-ask (:id a)
       :actions  [{:id :wake :label "Wake it again" :kind :mutation :style :primary
                   :title "Its next turn hears everything it has yet to hear."}]})))

(defn- asking-gate
  "What Needs you shows of workstream `ws-id`, which the core answered as asking with `held`
   ({:state :awaiting}): its gate, built from no row but what the record and its sessions say of
   it — label, tag, links and origin as the board names them, :awaiting-gate as its stage, what
   runs as the core holds it — and, for a slice, the drain it belongs to. Neither a gate nor an
   error for one its ledger settled — closed with any outcome but :between-phases, as its board
   row reads it settled — whatever the core still holds open: a settled workstream asks nothing
   and has nothing to wake."
  [project ws-id held]
  (let [w        (cws/record-of-state ws-id (:state held))
        closed   (when w (cws/closure w))
        settled? (boolean (and closed (not= :between-phases (:outcome closed))))
        sessions (when w (cws/sessions project ws-id))
        origin   (if w (classify-origin w) :notion)
        row      {:ws-id  ws-id
                  :origin origin
                  :stage  :awaiting-gate
                  :label  (if w (wsv/label w sessions) ws-id)
                  :tag    (if w (wsv/tag w sessions) ws-id)
                  :links  (if w (wsv/ref-links w) [])
                  ;; What still runs beside the ask, as every row reads it.
                  :doing  (:doing (act/of (observation project ws-id held false nil)))}]
    {:row      row
     :slice-of (when (= :slice origin) (rdrain/drain-of w))
     :gate     (when-not settled?
                 (gate-of-asks project row (remove domain/machine-ask? (:awaiting held))))
     :error    (when-not settled?
                 (error-of-asks project row (:state held) (filter domain/machine-ask? (:awaiting held))))}))

(defn- asking-reading
  "`project`'s gates and errors as the core answers them in one request (`wcore/asking`), each in
   key order: a gate per workstream whose asks put a question to the person, an error per
   workstream the machine stopped on a failure (`error-of-asks`) — so a workstream can hold one of
   each. A slice is never a card of its own: its drain's gate, when the drain is asking too, names
   it in :raised-by. Throws when the core does not answer."
  [project]
  (let [built  (for [[ws-id held] (sort-by key (wcore/asking project))]
                 (asking-gate project ws-id held))
        raised (reduce (fn [m {:keys [row slice-of gate]}]
                         (if (and slice-of gate) (update m slice-of (fnil conj []) (:ws-id row)) m))
                       {} built)]
    {:gates  (into []
                   (keep (fn [{:keys [row gate]}]
                           (when (and gate (not= :slice (:origin row)))
                             (cond-> gate
                               (get raised (:ws-id row)) (assoc :raised-by (get raised (:ws-id row)))))))
                   built)
     :errors (into [] (keep :error) built)}))

(defn- asking-gates
  "`project`'s gates as the core answers them in one request (`asking-reading`): one per
   workstream whose asks put a question to the person, in key order. Throws when the core does not
   answer."
  [project]
  (:gates (asking-reading project)))


(defn ^{:malli/schema [:=> [:cat :ProjectName] [:vector :Gate]]}
  gates
  "A project's gates: workstreams that owe a person a move now (needs-you), each hydrated
   with its report, its follow-actions and the question it asks. A PAUSED workstream is
   never a gate: a person's pause takes it off :needs-you until they lift it. A SETTLED (closed) workstream is never a gate,
   even if a stale stage-override still projects :needs-you.
   A DISMISSED row is never a gate either — and the filter is load-bearing, not
   cosmetic: Notion-driven rows ignore nido :closed for engagement, and the parked
   session is torn down asynchronously by review/sweep-resolved!, so without this a
   just-dismissed row keeps its :needs-you until the daemon next ticks."
  [project]
  (if-let [p (held-project project)]
    (:gates p)
    (gates* project)))

(defn- raised-by-slices
  "Drain ws-id -> the ws-ids of its slices among `slice-gates` (slice rows that owe a person a
   move), each slice's drain read off its own :slice birth entry."
  [project slice-gates]
  (reduce (fn [m {:keys [ws-id]}]
            (if-let [d (some-> (cws/read-ws project ws-id) rdrain/drain-of)]
              (update m d (fnil conj []) ws-id)
              m))
          {} slice-gates))

(defn- inbox-gates
  "The inbox's gates from `pairs` — each workstream's [row gate-or-nil], in listing order — and
   the one derivation both the held model and an unheld read take them from. A drain is one row:
   a slice is never a card of its own, and a slice that owes a person a move puts its drain on
   the inbox, naming it in :raised-by, whether or not the drain owes one itself — unless a
   person's pause stands on the drain."
  [project pairs]
  (let [slice? #(= :slice (:origin %))
        raised (raised-by-slices project (keep (fn [[r g]] (when (and g (slice? r)) r)) pairs))]
    (into []
          (keep (fn [[r g]]
                  (when-not (slice? r)
                    (if-let [by (get raised (:ws-id r))]
                      ;; A drain a person paused is not put back on the list by its slices.
                      (when (and (not (get-in r [:status :paused]))
                                 (or g (not= :settled (:engagement r))))
                        (some-> (or g (core-gate project r)) (assoc :raised-by by)))
                      g))))
          pairs)))

(defn- gates*
  [project]
  (let [owes? (fn [r] (and (:needs-you r)
                           ;; Between phases is closed, so it reads :settled — and is still owed
                           ;; a person's :assert-gate, which only a gate carries.
                           (not (and (= :settled (:engagement r))
                                     (not= :awaiting-gate (:stage r))))))]
    (inbox-gates project
                 (for [r (remove #(= :dismissed (:stage %)) (list-workstreams project))]
                   [r (when (owes? r) (core-gate project r))]))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  error
  "The error card the Errors list holds for workstream `ws-id` (`error-of-asks`), or nil when its
   machine has not stopped on a failure. Read from the core as `gate` is; throws when it does not
   answer."
  [project ws-id]
  (->> (:errors (asking-reading project))
       (filter #(= ws-id (:ws-id %)))
       first))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :Gate]]}
  gate
  "Full gate detail for one workstream — the card the Needs you list holds for it, from one core
   request for its project (`asking-gates`) — or nil when it holds none. Reads no held row; throws
   when the core does not answer."
  [project ws-id]
  (->> (asking-gates project)
       (filter #(= ws-id (:ws-id %)))
       first))

;; Defined below, with the session functions it reads — the liveness oracle
;; belongs beside those, and both cross-project projections need it up here.
(declare live-session-names)

(def GateReading
  "What reading every project's gates came to: the gates and errors of the projects the core
   answered for, and, by project name, why each other project could not be read. A project is in
   exactly one of them, so an unread project is never mistaken for one with nothing to ask."
  [:map
   [:gates [:vector :map]]
   [:errors [:vector :map]]
   [:unavailable [:map-of :string :string]]])

(defn ^{:malli/schema [:=> [:cat] GateReading]}
  read-gates
  "Every registered project's gates and errors, in list-projects order, each project's in key
   order — read from the core, one request per project (`asking-reading`), never from held rows
   or a ledger fold, so they answer as soon as the core does, whatever this process holds. The
   boundary where each project's core reply becomes its outcome: its gates and errors, or its key
   in :unavailable with the reason it could not be read."
  []
  (reduce (fn [acc [pname _entry]]
            (try (let [{:keys [gates errors]} (asking-reading pname)]
                   (-> acc (update :gates into gates) (update :errors into errors)))
                 (catch Throwable t
                   (assoc-in acc [:unavailable (name pname)] (or (ex-message t) (str t))))))
          {:gates [] :errors [] :unavailable {}}
          (project/list-projects)))

(defn ^{:malli/schema [:=> [:cat] [:vector :Gate]]}
  all-gates
  "The gates `read-gates` reads: every project's the core answered for. A caller that must tell an
   unread project from one with nothing to ask reads `read-gates`."
  []
  (:gates (read-gates)))

(def sweep-period-ms
  "How often the sweep runs: how long, at most, a write that moves a row no render reaches waits
   to show — a proposal decided or a reopening on a workstream at rest."
  10000)

(defonce ^:private model
  ;; nil while this process holds nothing. Holding: {:projects {"<project>" P} :casualties U},
  ;; where P is `refresh-project`'s value.
  (atom nil))

(defonce ^:private model-locks
  ;; One lock per project, so a sweep over brian never holds a render of nido.
  (atom {}))

(defn- project-lock [pname]
  (or (get @model-locks pname)
      (get (swap! model-locks #(if (contains? % pname) % (assoc % pname (Object.)))) pname)))

(declare owed-revivals)


(defn- file-mtime [path] (.lastModified (java.io.File. (str path))))

(defn- entry-stamp
  "Everything a held row is derived from, as a value: the workstream's own files, the event logs
   of the sessions nido drives under it, the report of the review its running turn holds the
   claim for, the Notion facts of its own page, and its committed state in the core, by hash.
   `files` is the files half, taken before the row was read, so a write landing during the read
   moves it next time. The core's half is what puts an ask, a turn or a closure on the row: a
   commit there writes no file, so a stamp without it kept the row as it was when the feed
   delivered one. A claim taken renames its payload into the workstream's directory, which
   `files` sees; its review rewrites the report on every event, its last among them, which is
   how the row hears both its progress and its end."
  ([project ws-id files e facts]
   (entry-stamp project ws-id files e facts (hash (:state (core-of project ws-id)))))
  ([_project _ws-id files {:keys [page-id row]} facts core-hash]
   [files
    (some-> (get-in row [:doing :report-path]) file-mtime)
    (get facts page-id)
    core-hash]))

(defn- compute-entry
  "One held row for `ws-id`, read whole from its records, or nil when it no longer exists.
   Carries what the refreshes compare it by and what the passes answer from."
  [project ws-id files facts]
  (when-let [w (cws/read-ws project ws-id)]
    (let [;; The core's half of the stamp, like the files', is taken BEFORE the row reads it: a
          ;; commit the feed applies during the read then moves the stamp next time. Taken after,
          ;; it recorded a state the row never saw, and the row kept its stale reading.
          core-hash (hash (:state (core-of project ws-id)))
          sessions  (cws/sessions project ws-id)
          row       (with-status project (wsv/workstream-row project w facts))
          settled?  (= :settled (:engagement row))
          page-id   (:page-id (wsv/notion-ref w))
          ;; The rule gates* applies: a denial alone makes no gate (all-denials notices it).
          gate?     (and (not= :dismissed (:stage row))
                         (:needs-you row)
                         ;; Between phases is closed, so it reads :settled — and is still owed
                         ;; a person's :assert-gate, which only a gate carries.
                         (not (and settled? (not= :awaiting-gate (:stage row)))))
          closed    (cws/closure w)
          finished? (or (and closed (not= :between-phases (:outcome closed)))
                        (= :done (:stage row)))
          e         {:row       row
                     :page-id   page-id
                     :covers    (set (remove nil? [page-id (:id (wsv/ledger-ref w))]))
                     :sessions  (set (map :name sessions))
                     :settled?  settled?
                     ;; At rest: only the sweep reaches it — settled history, as the core's
                     ;; engagement or the ledger's own close says (the core keeps most closed
                     ;; ledgers open), or a review-loop analysis, read off the position status
                     ;; gives it, whatever its band or gate. Decides nothing else; the gate above
                     ;; reads `settled?`.
                     :at-rest?  (or settled? finished? (= :analysed (get-in row [:position :at])))
                     :gate      (when gate? (core-gate project row))
                     ;; The winding-down band's row, less its live sessions, which are the
                     ;; registry's and change without this row being recomputed.
                     :wind      (when finished?
                                  {:ws-id   ws-id
                                   :project (name project)
                                   :stage   :winding-down
                                   :origin  (classify-origin w)
                                   :label   (wsv/label w sessions)
                                   :outcome (or (:outcome closed) :done)
                                   :workers (mapv :name (remove :conductor sessions))})}]
      (assoc e :stamp (entry-stamp project ws-id files e facts core-hash)))))

(defn- refresh-entry
  "`e`, the held row for `ws-id`, as a refresh that reaches it leaves it: recomputed when its
   stamp moved, kept otherwise. Everything a row is derived from is in its stamp, so a kept row
   is what recomputing it would answer. nil when the workstream is gone: the core no longer
   holds it."
  [project ws-id e facts]
  (let [wd    (java.io.File. (str (cstate/workstream-dir project ws-id)))
        files (ws-files-stamp project wd)]
    (if (and e (some? (second files)) (= (:stamp e) (entry-stamp project ws-id files e facts)))
      e
      (when (some? (second files))
        (compute-entry project ws-id files facts)))))

(defn- index-project
  "The selections the passes answer from, over `p`'s entries and the bare rows its page facts
   leave uncovered: every row in listing order, each band's rows, the gates, and the finished
   workstreams by session name — so a pass visits only the rows it returns."
  [project p]
  (let [entries (:entries p)
        order   (filterv #(contains? entries %) (:order p))
        covered (into #{} (mapcat :covers) (vals entries))
        bare    (into [] (keep (fn [[page-id fct]]
                                 (when-not (or (contains? covered page-id) (contains? covered (:br fct)))
                                   (to-spine (wsv/bare-row project page-id fct)))))
                      (:facts p))
        rows    (into (mapv #(:row (get entries %)) order) bare)]
    (assoc p
           :order    order
           :rows     rows
           :by-stage (reduce (fn [m r] (if (= :done (:stage r)) m (update m (:stage r) (fnil conj []) r)))
                             {} rows)
           :gates    (inbox-gates project (for [id order :let [e (get entries id)]] [(:row e) (:gate e)]))
           :covered-by (into {} (for [[id e] entries c (:covers e)] [c id]))
           :finished-by-session (into {} (for [[id e] entries :when (:wind e) s (:workers (:wind e))] [s id])))))

(declare refresh-project*)

(defn- refresh-project
  "`p`, a project's held value (nil for none), refreshed as `mode` says:
     :list   every row not :at-rest? and the rows whose own page's facts moved, plus a workstream
             new to the core;
     :sweep  every row.
   Both take the project's membership and every state from one request to the core
   (`cws/over-project`), which every row's fold answers from — so one the core newly holds is
   reached at once — and the project's page facts as a value."
  [project p mode]
  (cws/over-project project (fn [records] (refresh-project* project p mode (mapv :id records)))))

(defn- refresh-project*
  [project p mode order]
  (let [facts      (notion-cache/project-page-facts project)
        live       (live-session-names project)
        old-facts  (:facts p)
        reach?     (fn [e]
                     (or (nil? e)
                         (= :sweep mode)
                         (not (:at-rest? e))
                         (not= (get facts (:page-id e)) (get old-facts (:page-id e)))))
        entries    (reduce (fn [m id]
                             (let [e (get (:entries p) id)]
                               (if-let [e' (if (reach? e) (refresh-entry project id e facts) e)]
                                 (assoc m id e')
                                 m)))
                           {} order)]
    (index-project project (assoc p :order order :facts facts :live live
                                    :entries entries))))

(defn- refresh-one
  "`p` with the row for `ws-id` alone refreshed against its own page's facts — the selection
   refresh. A workstream not yet held is read and added; a page-id is a bare row, which only a
   list refresh or the sweep moves."
  [project p ws-id]
  (let [facts (notion-cache/project-page-facts project)
        e     (get (:entries p) ws-id)]
    (if (or e (cws/ledger-stamp project ws-id))
      (let [e' (refresh-entry project ws-id e (cond-> (:facts p)
                                                (:page-id e) (assoc (:page-id e) (get facts (:page-id e)))))]
        (index-project project
                       (cond-> (assoc p :entries (if e' (assoc (:entries p) ws-id e') (dissoc (:entries p) ws-id)))
                         (and e' (not (some #{ws-id} (:order p)))) (update :order conj ws-id))))
      p)))

(defn- swap-project!
  "Replace `project`'s held value with `(f project old)` under the project's lock; answers the
   new value. A no-op answering nil when this process holds nothing."
  [project f]
  (let [pname (name project)]
    (when @model
      (locking (project-lock pname)
        (let [p  (get-in @model [:projects pname])
              p' (f project p)
              ;; What `board-stamp` reads while holding: it moves only when a row did.
              p' (assoc p' :version (if (and p (= (:rows p) (:rows p'))) (:version p 0) (inc (:version p 0))))]
          (swap! model #(when % (assoc-in % [:projects pname] p')))
          p')))))

(def ^:private snapshot-schema-version 1)

(def HeldEntry
  "One held row as the snapshot carries it: the row the board draws, what a refresh compares
   it by, and what the passes answer from."
  [:map
   [:row :map]
   [:page-id [:maybe :string]]
   [:covers [:set :string]]
   [:sessions [:set :string]]
   [:settled? :boolean]
   [:at-rest? :boolean]
   [:gate [:maybe :map]]
   [:wind [:maybe :map]]
   [:stamp [:vector :any]]])

(def HeldSnapshot
  "A project's held rows as `write-snapshot!` writes them. Nothing in it is a record: every part
   is recomputed from the record files when it is missing, malformed or from another revision."
  [:map
   [:format [:= :held-rows]]
   [:schema [:= snapshot-schema-version]]
   [:revision :string]
   [:project :string]
   [:held [:map
           [:order [:vector :string]]
           [:facts [:maybe :map]]
           [:live [:set :string]]
           [:entries [:map-of :string HeldEntry]]]]])

(def ^:private valid-snapshot? (m/validator HeldSnapshot))

(def ^:private source-revision
  ;; Read once per process: the source a process runs cannot change under it.
  (delay
    (let [root (fs/path (core/nido-source-dir) "src")
          md   (java.security.MessageDigest/getInstance "SHA-256")]
      (doseq [f (sort (map str (fs/glob root "**.clj")))]
        (.update md (.getBytes (str (fs/relativize root f) "\n") "UTF-8"))
        (.update md ^bytes (fs/read-all-bytes f)))
      (apply str (map #(format "%02x" %) (.digest md))))))

(defn- snapshot-path
  "Where `project`'s snapshot lives: under the nido home's cache/, outside every workstream
   directory, so writing it never moves a stamp. Deleting it costs one cold build."
  [project]
  (fs/path (core/nido-root) "cache" (name project) "held-rows.transit.json"))

(def ^:private snapshot-keys [:order :facts :live :entries])

(defn- write-snapshot!
  "Write `p`, `project`'s held value, as its snapshot: the rows as they were last computed.
   Written whole to a temporary file and renamed into place, so a
   reader sees one snapshot or the previous one, never a torn one. Answers whether it was
   written; a value transit cannot carry is a snapshot not written, never an error."
  [project p]
  (try
    (let [path (snapshot-path project)
          tmp  (fs/path (fs/parent path) (str ".held-rows." (System/nanoTime) ".tmp"))]
      (fs/create-dirs (fs/parent path))
      (with-open [out (java.io.FileOutputStream. (str tmp))]
        (transit/write (transit/writer out :json)
                       {:format   :held-rows
                        :schema   snapshot-schema-version
                        :revision @source-revision
                        :project  (name project)
                        :held     (select-keys p snapshot-keys)}))
      (fs/move tmp path {:replace-existing true :atomic-move true})
      true)
    (catch Throwable _ false)))

(defn- read-snapshot
  "`project`'s held value seeded from its snapshot, or nil when there is none usable: one
   that does not parse, does not conform to `HeldSnapshot`, or was written by another nido
   source revision is discarded whole. A seeded row is kept until a refresh that reaches it
   finds its stamp moved."
  [project]
  (let [path (snapshot-path project)]
    (when (fs/exists? path)
      (let [snap (try (with-open [in (java.io.FileInputStream. (str path))]
                        (transit/read (transit/reader in :json)))
                      (catch Throwable _ nil))]
        (if (and (valid-snapshot? snap)
                 (= (name project) (:project snap))
                 (= @source-revision (:revision snap)))
          (index-project project (:held snap))
          (do (try (fs/delete-if-exists path) (catch Throwable _ nil))
              nil))))))

(defn- held-project
  "The held value for `project`, seeding it from its snapshot or else building it — one full
   read — under its lock, when this process holds and has neither; nil when this process
   holds nothing."
  [project]
  (when-let [m @model]
    (or (get-in m [:projects (name project)])
        (swap-project! project (fn [pr p] (or p (read-snapshot pr) (refresh-project pr nil :list)))))))

(defn ^{:malli/schema [:=> [:cat] [:vector :map]]}
  held-candidates
  "`release-candidates` over only the rows this process already holds, building and refreshing
   none: what a screen with no board (Needs you, the rail) shows of them. Empty before the first
   build, and as fresh as the last refresh — a candidate asks nothing, so a late one costs a wait,
   never a missed question."
  []
  (release-candidates
   (vec (for [[pname p] (:projects @model) :when p]
          {:project (name pname) :grouped (held-grouped p)}))))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  holding?
  "Whether this process holds its rows (`hold!`)."
  []
  (some? @model))

(defn ^{:malli/schema [:=> [:cat [:? :ProjectName]] :any]}
  refresh-list!
  "The list refresh, for `project` or every registered project: what the gate inbox, the
   Workstreams overview without a selection and the TUI board make before they render. Reaches
   every workstream not at rest, the rows whose own page's Notion facts moved and any workstream
   new to the core, and recomputes the ones whose stamp moved. At rest is settled history and finished review-loop
   analyses; only the sweep reaches those. A no-op when this process holds nothing.

   A core that cannot be reached leaves the held rows as they were: it is down for seconds on
   every landing while a release swaps, and a surface refreshing then renders what it last read
   rather than failing."
  ;; A project that cannot be read keeps the rows it had, as `all-gates` and `all-grouped` skip
  ;; it: one unreadable project must not cost every other project's board.
  ([] (doseq [[pname _] (project/list-projects)]
        (try (refresh-list! pname) (catch Throwable _ nil))))
  ([project]
   (when (holding?)
     (try
       (held-project project)
       (swap-project! project (fn [pr p] (refresh-project pr p :list)))
       (catch clojure.lang.ExceptionInfo e
         (when-not (= :unreachable (:core (ex-data e))) (throw e))))
     nil)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  refresh-selected!
  "The selection refresh: what a request selecting one workstream makes before it renders —
   that workstream's row alone, against its own page's facts; every other row is drawn as
   held. A no-op when this process holds nothing."
  [project ws-id]
  (when (holding?)
    (held-project project)
    (swap-project! project (fn [pr p] (refresh-one pr p ws-id)))
    nil))

(defn- sweep-project!
  "The sweep's refresh of `project`, read outside the project's lock so no render waits on it,
   then installed under it. A row some render refreshed meanwhile keeps that render's reading;
   the sweep's stands for every row nothing else moved. A project not yet built is built under
   the lock, as a render's first read would."
  [project]
  (let [pname (name project)
        p0    (get-in @model [:projects pname])]
    (if (nil? p0)
      (held-project project)
      (let [p1 (refresh-project project p0 :sweep)]
        (swap-project! project
                       (fn [pr cur]
                         (if (identical? cur p0)
                           p1
                           (index-project
                            pr (update cur :entries
                                       (fn [es]
                                         (reduce (fn [m id]
                                                   (if (identical? (get m id) (get (:entries p0) id))
                                                     (if-let [e (get (:entries p1) id)] (assoc m id e) (dissoc m id))
                                                     m))
                                                 es (keys (:entries p0)))))))))))))

(defn- persist!
  "Write `project`'s snapshot when its rows moved since this process last wrote it — the
   sweep's, after each run, so a process starting next seeds from rows at most one run old."
  [project]
  (let [pname (name project)
        p     (get-in @model [:projects pname])]
    (when (and p (not= (:version p) (get-in @model [:written pname])))
      (when (write-snapshot! pname p)
        (swap! model #(when % (assoc-in % [:written pname] (:version p))))))))

(defn ^{:malli/schema [:=> [:cat] :any]}
  sweep!
  "One run of the sweep: every project refreshed as `refresh-project` :sweep says, then the
   held casualty reading recomputed whole."
  []
  (doseq [[pname _] (project/list-projects)]
    (try (sweep-project! pname)
         (persist! pname)
         (catch Throwable t
           (binding [*out* *err*] (println "[nido] sweep of" (name pname) "failed:" (ex-message t))))))
  (let [u (try (owed-revivals) (catch Throwable _ nil))]
    (when u (swap! model #(when % (assoc % :casualties u))))))

(defn ^{:malli/schema [:=> [:cat [:? :map]] :any]}
  hold!
  "Make this process hold its rows, and start the sweep that keeps them. Idempotent. Every
   board reading answers from what is held from now on; a surface decides when to refresh
   (`refresh-list!`, `refresh-selected!`). The first build runs on the sweep's thread — seeded
   from each project's snapshot when it has a usable one (`read-snapshot`) — and a render
   before it finishes waits for it. `{:sweep? false}` holds with no sweep running, for
   a caller that drives `sweep!` itself — a test."
  ([] (hold! {}))
  ([{:keys [sweep?] :or {sweep? true}}]
   (let [held {:projects {} :hold (Object.)}]
     ;; Coordination arrives on the core's feed: each workstream a commit changed is refreshed as
     ;; it lands, so what is held never waits for the sweep to show an ask or a closure.
     (when (and (compare-and-set! model nil held) sweep?)
       (wcore/follow! {:on-change    (fn [k] (when-let [[p ws] (wcore/split-key k)]
                                               (when (some #(= p (name (key %))) (project/list-projects))
                                                 (refresh-selected! p ws))))
                       ;; A catch-up is applied whole and then refreshes once, off the feed's
                       ;; thread so live messages keep arriving: every row a build read before
                       ;; the mirror held its workstream is recomputed by its stamp.
                       :on-caught-up #(future (refresh-list!))}))
     (when (and (identical? @model held) sweep?)
       ;; The thread belongs to this hold: it ends once the model is no longer the one it
       ;; started with, so a release followed by a new hold never leaves two sweeps running.
       (let [mine? #(identical? (:hold @model) (:hold held))
             t     (Thread. ^Runnable
                            (fn []
                              (loop []
                                (when (mine?)
                                  (let [t0 (System/currentTimeMillis)]
                                    (sweep!)
                                    (Thread/sleep (long (max 0 (- sweep-period-ms (- (System/currentTimeMillis) t0)))))
                                    (recur)))))
                            "nido-held-rows-sweep")]
         (.setDaemon t true)
         (.start t))))
   nil))

(defn ^{:malli/schema [:=> [:cat] :any]}
  release!
  "Stop holding: drop every held row and let the sweep end. Readings read the disk again."
  []
  (reset! model nil)
  nil)

(defn ^{:malli/schema [:=> [:cat] :Unsettled]}
  shown-revivals
  "`owed-revivals` as a render shows it: the held copy, which only the sweep computes, while
   this process holds; computed directly otherwise. The daemon's attention notifier and the
   revive command call `owed-revivals` and never read this."
  []
  (if-let [m @model]
    (or (:casualties m)
        (let [u (owed-revivals)] (swap! model #(when % (assoc % :casualties u))) u))
    (owed-revivals)))

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

(declare core-resolve! start-ticket! resolve-ref open-fire!)

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :keyword] :any]}
  set-stage!
  "Move a workstream to `target` stage — the single mutation behind the
   new/promote/done surface verbs. A workstream-less ws-id (e.g. a bare
   watched-view row) is a no-op: {:decision :no-workstream}. Dispatch:
     :in-progress → the person's start, opening its unit in the core (`core-resolve!` :promote)
     :done        → close it in the core (`core-resolve!` :done); a drain through lane-drain's
                    close, and a slice is refused, since it is done only by landing
     other        → advance the stored stage only (no autonomous leg)
   Returns {:decision <kw>}: :promoted, :done or :advanced, the core's refusal, or
   {:decision :refused :message …} for a slice or a drain whose exit is not observed.

   A person's choice is recorded even when it names the stage already stored — `advance-stage!`
   records every `:person` choice, validated like any other move — because status reads the
   choice off the ledger: a ticket Notion settled since, or one minted at the stage and never
   chosen there, is moved only by an entry, so a choice reported :advanced must be one."
  [project ws-id target]
  (if-let [w (cws/read-ws project ws-id)]
    (case target
      :in-progress (core-resolve! project ws-id :promote nil)
      ;; A slice is done only by landing, and a drain only by lane-drain's close on its observed
      ;; exit — so neither is closed done here on the person's word alone.
      :done        (cond
                     (rdrain/slice? w)
                     {:decision :refused
                      :message  "A slice is done when it lands — land it from the drains page."}
                     (rdrain/drain? w)
                     (try (drain-lane/close! (keyword (name project)) ws-id) {:decision :done}
                          (catch clojure.lang.ExceptionInfo e
                            {:decision :refused :message (ex-message e)}))
                     :else
                     (core-resolve! project ws-id :done nil))
      (do (cws/advance-stage! project ws-id target :person)
          {:decision :advanced}))
    {:decision :no-workstream}))

(defn- bare-row-br
  "The BR-#### behind a bare watched-view row, whose synthetic ws-id IS the Notion
   page-id (workstreams-view/bare-row). nil when the page is not in the project's
   watched-view cache, or carries no unique-id."
  [project page-id]
  (get-in (notion-cache/project-page-facts project) [page-id :br]))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  dismiss!
  "Take a workstream off the triage radar: settle it :dismissed — which frees its trigger's
   in-flight slot, removes it from the queue and is what makes its ticket read :dismissed, so
   auto-re-triage skips it — then dismiss its ticket, if it has one.

   A workstream-less ws-id is a bare watched-view row: dismissing its ticket mints the workstream
   that carries the dismissal, and only a page with no BR is a genuine no-op
   ({:decision :no-workstream}). Returns {:decision :dismissed} otherwise."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    (do
      (cws/close! project ws-id :dismissed nil :person)
      (when-let [br (:id (wsv/ledger-ref w))]
        (tickets/dismiss! project br))
      {:decision :dismissed})
    (if-let [br (bare-row-br project ws-id)]
      (do (tickets/dismiss! project br) {:decision :dismissed})
      {:decision :no-workstream})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  restore!
  "Undo a dismiss: reopen the workstream, by a person. The reopen is what returns its ticket to
   triage — nothing recorded before the dismissal counts toward the ticket's status any more
   (`standing/restored-at`), so the auto-triage gate treats it as never triaged and the row lands
   in the triage queue. Dismiss lets the daemon sweep tear the parked triage session down, so the
   verdict it left is deliberately not revived: it would name a conversation that no longer
   exists.

   Reopens at :triaging, which names no board stage, so the reopen chooses none and the row's band
   comes from where its ledger places it.

   A ws-id with no workstream is a bare watched-view row's page-id: dismissing one minted the
   workstream that carries its ticket, so the restore is that workstream's. A page whose ticket no
   workstream carries has nothing to restore: {:decision :no-workstream}.

   A workstream closed :between-phases is refused, {:decision :between-phases}: it was never
   dismissed, and reopening it would start its next phase with no gate asserted. Its ways back
   are a findings round and advance-phase!."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    (if (= :between-phases (:outcome (cws/closure w)))
      {:decision :between-phases}
      (do
        (cws/reopen! project ws-id :triaging :person)
        {:decision :restored}))
    (if-let [covering (some->> (bare-row-br project ws-id) (cws/find-by-ref-id project))]
      (restore! project (:id covering))
      {:decision :no-workstream})))

(defn- slack-ref-of
  "The workstream's :slack-message external-ref map, or nil."
  [w]
  (some #(when (= :slack-message (:adapter %)) %) (:external-refs w)))

(def ^:private slack-ticket-owner
  "Who a Slack-born ticket is handed to: its Ball Holder, and who a doubt is put to."
  :jaap)

(def ^:private engineering-notes
  "The toggle an accepted proposal's technical analysis goes into. The QA-brief step folds a
   page's engineering record into a toggle of this title too, so both writers share one."
  "Engineering notes & history")

(defn- slack-thread
  "{:slack-id :channel :ts} of the thread the workstream's Slack ref names — its message, its
   thread or, for a ticket proposed from a thread, that thread — or nil. :ts is the thread's root,
   which is what a reply is threaded under."
  [w]
  (let [slack-id (:id (slack-ref-of w))]
    (when-let [{:keys [channel ts]} (slack/parse-id slack-id)]
      {:slack-id slack-id :channel channel :ts ts})))

(defn- post-in-thread!
  "Post `text` as a reply in the Slack thread. Returns true on {:ok true}; never throws —
   a Slack write acknowledges work and must not fail it."
  [{:keys [channel ts]} text]
  (boolean (:ok (try (slack/post-message channel (slack/keychain-token)
                                         {:text text :thread-ts ts})
                     (catch Throwable _ nil)))))

(defn- link-filed!
  "Post the filed page's URL in the thread unless the slack-keyed record says it already is,
   recording it once Slack confirms. Returns :posted, :already or :failed; a :failed link is
   posted by the next conclude! or apply!."
  [project {:keys [slack-id] :as thread} url]
  (cond
    (:link-posted-at (tickets/read-meta project slack-id)) :already
    (post-in-thread! thread (str "Ticket filed: " url))
    (do (tickets/mark! project slack-id :link-posted-at (clock/now-iso)) :posted)
    :else :failed))

(defn- outcome-unknown?
  "Whether a failed create may still have made the page: the request never got a definite
   answer (:network, which covers a timeout) or Notion failed on its side (:server). An :auth
   or :http error is Notion refusing the request, so nothing was created."
  [error]
  (contains? #{:network :server} error))

(defn- create-once!
  "Create the workstream's one Notion page with `fields` and add it as the :notion ref, guarded
   so no workstream ever gets two: a :filing-started-at mark goes on the slack-keyed record
   BEFORE the create and is cleared only by a definite refusal, so a mark with no :notion ref
   means a create that may have made a page nobody recorded — that refuses rather than risk a
   second one, and a person has to check Notion. The caller has already checked for a ref.
   Returns {:br … :page-id … :url …} or {:error kw [:status n]}."
  [project ws-id slack-id fields]
  (let [record (tickets/read-meta project slack-id)]
    (cond
      (nil? record)               {:error :no-ticket-record}
      (:filing-started-at record) {:error :filing-interrupted}
      :else
      (let [ntok    (notion/keychain-token)
            ds      (notion/resolve-data-source-id (:database (views/load-registry project)) ntok)
            _       (tickets/mark! project slack-id :filing-started-at (clock/now-iso))
            created (notion/create-page! ds ntok fields)]
        (if (:error created)
          (do (when-not (outcome-unknown? (:error created))
                (tickets/unmark! project slack-id :filing-started-at))
              (select-keys created [:error :status]))
          (do (cws/add-ref! project ws-id {:adapter :notion :id (:id created)
                                           :page-id (:page-id created) :url (:url created)})
              {:br (:id created) :page-id (:page-id created) :url (:url created)}))))))

(defn- error-decision
  "A create-once! error as a verb's decision. Carries :status through — an :http error is only
   actionable with the code (a 400 is a payload we built wrong; a 404 a sharing problem)."
  [{:keys [error status]}]
  (cond-> {:decision :error :error error}
    status (assoc :status status)))

(defn- ensure-filed!
  "File `proposal`'s product page for the Slack workstream `w` (at most once — see
   create-once!), unless it is already filed, and make sure its link is in the thread. Filing
   makes the page a ref of the workstream whose unaccepted proposal then holds it
   (tickets/held-by-gate?), so no triage or pickup starts on it before acceptance.
   Returns {:decision :filed|:already-filed :br … :url … :link :posted|:already|:failed} or
   {:decision :error :error kw [:status n]}."
  [project ws-id proposal w]
  (let [{:keys [slack-id] :as thread} (slack-thread w)
        filed (wsv/notion-ref w)]
    (cond
      (nil? thread) {:decision :error :error :no-slack-ref}
      filed         {:decision :already-filed :br (:id filed) :url (:url filed)
                     :link (link-filed! project thread (:url filed))}
      :else
      (let [page (create-once! project ws-id slack-id
                   {:title       (:title proposal)
                    :type        (:ticket-type proposal)
                    :priority    (:priority proposal)
                    :status      "Needs verification"
                    :lifecycle   "Development"
                    :app-domain  (:app-domain proposal)
                    :ball-holder (report/owner->user-id slack-ticket-owner)
                    :children    [(notion/callout-block (report/product-page proposal))]})]
        (if (:error page)
          (error-decision page)
          (do (tickets/open! project (:br page) {:notion-page-id (:page-id page) :url (:url page)
                                                  :title (:title proposal) :opened-by :slack-proposal})
              {:decision :filed :br (:br page) :url (:url page)
               :link (link-filed! project thread (:url page))}))))))

(defn- file-legacy-proposal!
  "Accept a proposal written before :product/:doubt existed, as such proposals were accepted
   when written: one page at Not started carrying the gate card, linked in the thread — filed at
   most once, like every Slack page (create-once!)."
  [project ws-id proposal w]
  (let [{:keys [slack-id] :as thread} (slack-thread w)
        filed (wsv/notion-ref w)
        page  (cond
                (nil? thread) {:error :no-slack-ref}
                filed         {:br (:id filed) :url (:url filed)}
                :else         (create-once! project ws-id slack-id
                                {:title       (:title proposal)
                                 :description (report/report->markdown proposal)
                                 :type        (:ticket-type proposal)
                                 :status      "Not started"
                                 :priority    (:priority proposal)}))]
    (if (:error page)
      (error-decision page)
      (do (link-filed! project thread (:url page))
          {:decision :created :br (:br page)}))))

(defn- thread-workstream?
  "Whether `ws-id` came to exist for a Slack thread: it holds a unit a fire of a :slack-events
   trigger opened. Read off how the workstream came to be, never its ref's text or its newest
   verdict — a once-per-ref fire opens a unit only on a workstream its own fire made."
  [project ws-id]
  (let [opened (set (keep :trigger (cws/units project ws-id)))]
    (boolean (and (seq opened)
                  (some #(and (opened (name (:name %))) (= :slack-events (-> % :source :type)))
                        (triggers/load-for-project project))))))

(defn- apply-proposed!
  "Accept a Slack proposal: make sure its product page is filed, add the technical analysis
   to that page's engineering notes, record the acceptance, and only then claim the ticket and
   start it, as a person's start. The acceptance comes first because a claim is a stage choice, and
   a record appended after a choice moves the work past it (standing/chosen-stage). A failed
   Notion write starts nothing and leaves the gate to retry; a doubt is refused, because
   overriding one means a revised proposal, not an accept.
   Returns {:decision :created :br …} | {:decision :error :error kw}."
  [project ws-id proposal w]
  (cond
    ;; A thread's verdict is a :proposed-tickets; its own workstream files nothing.
    (thread-workstream? project ws-id) {:decision :error :error :wrong-kind}
    (:doubt proposal)          {:decision :error :error :doubted}
    (nil? (:product proposal)) (file-legacy-proposal! project ws-id proposal w)
    :else
    (let [filed (ensure-filed! project ws-id proposal w)]
      (if (= :error (:decision filed))
        filed
        (let [w'   (cws/read-ws project ws-id)
              page (wsv/notion-ref w')
              res  (notion/append-to-toggle! (:page-id page) engineering-notes
                                             (report/analysis-sections proposal)
                                             (notion/keychain-token))]
          (if (:error res)
            {:decision :error :error (:error res)}
            (do (tickets/complete! project (:slack-id (slack-thread w')))
                (start-ticket! project {:id (:id page) :page-id (:page-id page)
                                        :url (:url page) :title (:title proposal)})
                {:decision :created :br (:id page)})))))))

(declare conclude!)

(defn- ensure-ticket-workstream!
  "The workstream of the ticket proposed under `key` from the thread workstream `w`, brought to
   initialised: found by its ticket-scoped Slack id or minted with it, its ticket record opened,
   and `ticket` appended as its :proposed-ticket — each only where missing, so an interrupted
   fan-out is completed by running it again and never doubled. Returns the workstream."
  [project w {:keys [key ticket]}]
  (let [tid  (slack/ticket-id (:slack-id (slack-thread w)) key)
        ref  {:adapter :slack-message :id tid :title (:title ticket) :url (:source-url ticket)}
        tw   (or (cws/find-by-ref project :slack-message tid)
                 (cws/create! project {:external-refs [ref] :activated? true}))]
    (when-not (tickets/read-meta project tid)
      (tickets/open! project tid {:url (:source-url ticket) :title (:title ticket)
                                  :opened-by :slack-thread}))
    (when-not (seq (cws/entries-of project (:id tw) :proposed-ticket))
      (cws/append-entry! project (:id tw) {:kind :proposed-ticket} (pr-str ticket)))
    (cws/read-ws project (:id tw))))

(defn- conclude-thread!
  "Carry out a Slack thread's verdict (`report/ProposedTickets`). A doubt is put to the owner in
   the thread and nothing is minted. Tickets each get a workstream of their own
   (ensure-ticket-workstream!), which is then concluded as any single proposal is — filed for
   verification and linked in the thread — so each is gated, accepted and driven on its own. The
   thread workstream files nothing and is closed once every ticket's workstream is initialised;
   a ticket whose filing failed is retried by its own workstream's gate, not by this one.
   Returns {:decision :doubted} | {:decision :fanned-out :tickets [{:key :ws-id :decision …}]} |
   {:decision :error :error kw}."
  [project ws-id {:keys [doubt tickets]} w]
  (if-let [thread (slack-thread w)]
    (if doubt
      (if (post-in-thread! thread (str (report/slack-mention slack-ticket-owner) " " doubt))
        {:decision :doubted}
        {:decision :error :error :slack-failed})
      (let [results (mapv (fn [t]
                            (let [tw (ensure-ticket-workstream! project w t)]
                              (merge {:key (:key t) :ws-id (:id tw)}
                                     (select-keys (conclude! project (:id tw))
                                                  [:decision :error :br :url]))))
                          tickets)]
        (cws/close! project ws-id :done nil :nido)
        {:decision :fanned-out :tickets results}))
    {:decision :error :error :no-slack-ref}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  conclude!
  "Carry the workstream's concluded Slack proposal out before anyone accepts it. A :doubt is
   put to the owner as a thread reply mentioning them, and nothing is filed. A :product is
   filed for verification and linked in the thread (see ensure-filed!); on a workstream already
   filed it only retries a link that failed. Never starts a drive — that is apply!'s, after a
   person has read the analysis.
   Returns {:decision :doubted|:filed|:already-filed …} or {:decision :error :error kw}."
  [project ws-id]
  (if-let [w (cws/read-ws project ws-id)]
    (let [proposal (latest-report project ws-id)]
      (cond
        (= :proposed-tickets (:format proposal)) (conclude-thread! project ws-id proposal w)
        (not= :proposed-ticket (:format proposal)) {:decision :error :error :no-proposal}
        (thread-workstream? project ws-id)       {:decision :error :error :wrong-kind}
        (:doubt proposal)
        (if-let [thread (slack-thread w)]
          (if (post-in-thread! thread (str (report/slack-mention slack-ticket-owner) " "
                                           (:doubt proposal)))
            {:decision :doubted}
            {:decision :error :error :slack-failed})
          {:decision :error :error :no-slack-ref})
        (:product proposal) (ensure-filed! project ws-id proposal w)
        :else               {:decision :error :error :no-conclusion}))
    {:decision :error :error :no-workstream}))

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
          ;; write with incomplete data (a start's Participants write in nido.coordinator.lane.start
          ;; keeps the same additive contract).
          {:decision :notion-failed :error (:error page)}
          (let [current (keep :name (get-in page [:properties (keyword "App Domain") :multi_select]))
                res     (notion/update-page-properties! page-id (triage-notion-props report current) token)]
            (if (:error res)
              {:decision :notion-failed :error (:error res)}
              (let [callout (when-let [desc (get-in report [:notion-writes :description-prepend])]
                              (prepend-enriched-callout! page-id br desc token))]
                (when br
                  (try (facets/refresh-for-ticket! project br) (catch Throwable _ nil)))
                (cond-> {:decision :applied}
                  (= :warn callout) (assoc :callout :warn))))))))))

(def ^:private deferred-size-note
  "The sentence an acceptance leaves on the Notion page when no size lands.

   It is what keeps the seam visible. `:squirrel` is not a value the Effort select
   holds, so triage-notion-props writes no Effort at all and the page keeps the
   size it already carried — which reads exactly like a size nobody got round to
   setting. This says the size is open on purpose.

   It is about the SIZE and names no branch, because both ways of reaching it are
   the same fact about the ticket: nobody deferred, and a chosen branch that defers
   its own size, both leave the sizing to the design record."
  "Effort left open at triage — the size follows from the design.")

(def ^:private effort-order
  "Concrete sizes, smallest first — what `largest` compares by."
  [:XS :S :M :L :XL])

(defn- size-note
  "The sentence an acceptance adds when the chosen fixes differ in size: the Effort written is
   the largest of them, and these are all their sizes. Names no branch, for the reason
   `deferred-size-note` names none."
  [efforts]
  (str "Effort is the largest of the chosen fixes (" (str/join ", " (map report/effort-label efforts)) ")."))

(defn- add-note [report note]
  (update-in report [:notion-writes :description-prepend]
             (fn [d] (str (when-not (str/blank? d) (str d "\n\n")) note))))

(defn- accepted-report
  "`report` as the acceptance makes it — the ONE route by which a human's choices
   reach Notion.

   The chosen `directions` (one per answered question; open questions contribute
   nothing) decide the size, and that is the whole of what a choice changes here.
   WHICH branches were taken is the decision itself and lives in the acceptance
   entry; writing their labels into the callout too would put them in a second
   durable place nothing afterwards can correct.

   The Effort is the largest concrete size among the chosen fixes, because the
   ticket has to hold all of them. When the chosen sizes differ the callout says so
   and lists them. When every chosen fix defers its size, or none was chosen and the
   report itself defers it, no Effort lands and the page says the size is open on
   purpose. With none chosen, the report's own size is the one written.

   A report with no `:notion-writes` is returned untouched — there is nothing for
   either half to land on."
  [report directions]
  (if-not (:notion-writes report)
    report
    (let [chosen   (keep :effort directions)
          concrete (filter (set effort-order) chosen)
          largest  (when (seq concrete) (apply max-key #(.indexOf ^java.util.List effort-order %) concrete))
          effort   (cond largest      largest
                         (seq chosen) :squirrel
                         :else        (get-in report [:notion-writes :effort]))]
      (cond-> (if (= :squirrel effort)
                (add-note report deferred-size-note)
                (assoc-in report [:notion-writes :effort] effort))
        (< 1 (count (distinct chosen))) (add-note (size-note chosen))))))

(defn- execute-report!
  "Write `report` out and finalize the ticket. Three paths:

   • Slack proposal (`:proposed-ticket`) → file its page if conclude! has not, add the
     technical analysis, start the drive (apply-proposed!). Returns {:decision :created …}
     | {:decision :error …}.
   • Routed Notion triage (`:triage-report` with :routing, on a :notion-backed ws) →
     execute the routing outcome to Notion (apply-routed!): Ball Holder + App Domain,
     deep properties, deep callout. Returns {:decision :applied [:callout :warn]} or
     {:decision :notion-failed :error <kw>} (ticket left parked to retry).
   • Legacy / Slack-triage (any other report, or a ref-less ws) → finalize the ticket
     :triaged/:applied nido-side only. Returns {:decision :applied}."
  [project ws-id report w]
  (cond
    (= :proposed-ticket (:format report))
    (apply-proposed! project ws-id report w)

    ;; A thread's verdict is never accepted on the thread: a doubt is overridden by a Reply, and
    ;; tickets are carried out by the fan-out, each then accepted on its own workstream.
    (= :proposed-tickets (:format report))
    (if (:doubt report)
      {:decision :error :error :doubted}
      (conclude-thread! project ws-id report w))

    (and (= :triage-report (:format report)) (:routing report) (wsv/notion-ref w))
    (apply-routed! project ws-id report w)

    :else
    (do
      (when-let [br (:id (wsv/ledger-ref w))]
        (try (facets/refresh-for-ticket! project br)
             (catch Throwable _ nil)))
      {:decision :applied})))

(defn- answers-for
  "The acceptance's answers for `report`'s questions given `letters` — one letter or nil per
   question, in order — or nil when they do not answer this report: a different count, or a letter
   that names no direction of its question. nil `letters` leave every question open."
  [report letters]
  (let [qs      (report/triage-questions report)
        letters (if (nil? letters) (repeat (count qs) nil) letters)]
    (when (= (count qs) (count letters))
      (reduce (fn [acc [{:keys [question directions]} l]]
                (let [i (when-not (str/blank? l) (.indexOf ^java.util.List report/option-letters (str/upper-case l)))
                      d (when (and i (<= 0 i)) (get (vec directions) i))]
                  (if (and (not (str/blank? l)) (nil? d))
                    (reduced nil)
                    (conj acc (cond-> {}
                                (not (str/blank? question)) (assoc :question question)
                                d (assoc :direction {:letter (report/option-letter i)
                                                     :label  (:label d)
                                                     :shape  (:shape d)
                                                     :effort (:effort d)}))))))
              [] (map vector qs letters)))))

(defn- accept!
  "Execute the report the gate answers (`gate-report`) — the verdict a person owes an acceptance,
   whatever was appended after it, else the latest — and record that a human accepted it.

   `at-seq`, when given, is the ledger position the click was rendered at, and the
   acceptance is refused unless that is still the report the gate answers.
   `letters` is one direction letter or nil per question of that report, in order —
   nil altogether for an acceptance that answers none (Defer, the CLI).

   Shared by both of apply!'s arities, which differ only in `checked?` — see there
   for why the two writes happen in the order they do."
  [project ws-id at-seq letters checked?]
  (if-let [w (cws/read-ws project ws-id)]
    (let [report  (gate-report project ws-id)
          ;; Resolved against the CURRENT report, never against what the browser
          ;; was showing — the same discipline choose-option! holds, and for the
          ;; same reason: the ledger decides what each letter meant, and the
          ;; position decides whether those questions are still being asked.
          answers (answers-for report letters)]
      (cond
        ;; Equality, not presence: a click that carried NO position is stale
        ;; against a ledger that holds a positioned report, and is not stale
        ;; against one that holds none — an intake-text fallback has no :seq, and
        ;; a ledger with nothing in it cannot have moved past what was on screen.
        (and checked? (not= at-seq (:seq report))) {:decision :triage-stale}
        (nil? answers)                             {:decision :triage-stale}
        ;; A second click on a report already accepted answers a question nobody is asking any
        ;; more, and would carry the verdict out again. The CLI path is not a click and stays
        ;; repeatable; a failed write records no acceptance, so its retry is not refused here.
        (and checked?
             (let [v (standing/verdict project ws-id w)]
               (and (:accepted? v) (= (:seq v) (:seq report)))))
        {:decision :triage-stale}

        :else
        (let [chosen  (keep :direction answers)
              outcome (execute-report! project ws-id (accepted-report report chosen) w)]
          ;; Only what landed is recorded. :notion-failed and :error leave the
          ;; gate exactly as they found it, which is what keeps the click
          ;; repeatable. An acceptance cites the report it accepted; on an empty
          ;; ledger the workstream's only content is its intake text
          ;; (latest-report's fallback), which has no :seq, and the acceptance
          ;; cites nothing — the ticket accepted as reported. One already standing
          ;; is not recorded twice: a Slack accept records its own before it
          ;; claims the drive (apply-proposed!).
          (when (and (contains? #{:applied :created} (:decision outcome))
                     (not (:accepted? (standing/verdict project ws-id (cws/read-ws project ws-id)))))
            (cws/append-entry!
             project ws-id {:kind :triage-accepted}
             (pr-str (cond-> {:format :triage-accepted}
                       (int? (:seq report)) (assoc :triage-seq (:seq report))
                       (seq answers)        (assoc :answers answers)))))
          outcome)))
    {:decision :applied}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:? :map]] :any]}
  apply!
  "ACCEPT a triage verdict — execute it, then record that a human accepted it.
   Resumes nobody: the verdict is already written and nido is what carries it out.

   `opts` is what a GATE click carries and a CLI apply does not:

     :at-seq  — the ledger position the button was rendered at. This arity
                CHECKS it: anything but the report the gate answers
                (`gate-report`) — a stale position,
                or none where the ledger holds one — is refused
                ({:decision :triage-stale}) and nothing is written, as is a
                click on a report whose acceptance already stands. The
                two-argument arity is the CLI path and checks nothing: the agent
                executing its own verdict inside its own turn has no
                render-and-click gap for a position to guard.
     :answers — one direction letter, or nil for a question left open, per
                question of that report, in order. Absent leaves every question
                open, which is what Defer decision sends. A count that is not the
                report's question count, or a letter naming no direction of its
                question, is refused as stale, writing nothing.

   The acceptance is appended AFTER the write, which is the opposite order to
   choose-option!. The difference is what the second write IS. A resume is a
   notification the decision stands without, so the record goes first there. The
   Notion write is the decision being CARRIED OUT, it can fail, and a failed apply
   leaves the ticket parked to retry — so appending first would make the
   acceptance the ledger's latest entry and the position check above would then
   refuse the very retry that contract promises.

   The daemon's sweep settles the now-resolved parked session."
  ([project ws-id] (accept! project ws-id nil nil false))
  ([project ws-id {:keys [at-seq answers]}]
   (accept! project ws-id at-seq answers true)))

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
  "Start a triage for a bare watched-view row, whose ws-id IS the Notion page-id: the triage
   that should have run never did, so there is no workstream yet. The page is resolved fresh from
   Notion, so the payload is what a poll would have emitted, and the fire is opened in the core as
   intake opens one (`open-fire!`) — past the trigger's filter, breaker, dry run, queue mode and
   ticket gate, which one deliberate click is. Does network I/O; callers run it off the render
   thread. Returns {:decision :triaging} | {:decision :no-trigger} | {:decision
   :already-in-flight} | {:decision :unresolved :error <kw>}."
  [project ws-id]
  (if-let [t (triage-trigger project)]
    (let [ref (resolve-ref project ws-id (notion/keychain-token))]
      (if (:error ref)
        {:decision :unresolved :error (:error ref)}
        (case (open-fire! {:project project :trigger t :payload ref})
          :opened  {:decision :triaging}
          :running {:decision :already-in-flight}
          {:decision :unresolved :error :core-refused})))
    {:decision :no-trigger}))









(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:maybe :string]] :map]}
  advance-phase!
  "Assert a phased workstream's gate with `evidence` and open its next phase.
   Returns {:decision :phase-opened :opened <claim> :phase n :of m}, or
   {:decision :phase-refused :because <why>} — a gate that will not open is an
   answer a person reads, not a failure of the click."
  [project ws-id evidence]
  (try
    ;; The plan whose gate this is — the one the close names (`standing/governing-design`),
    ;; the same one the board showed — and only while it still stands: a
    ;; retracted design supplies no gate to open.
    (when-let [d (standing/governing-design project ws-id)]
      (when-not (:cleared? (standing/of-design project ws-id d))
        (throw (ex-info (str "the design at entry " (:seq d) " no longer stands")
                        {:ws-id ws-id}))))
    (assoc (lphase/advance! project ws-id {:evidence evidence}) :decision :phase-opened)
    (catch clojure.lang.ExceptionInfo e
      {:decision :phase-refused :because (ex-message e)})))

(defn- core-decision
  "A resolver's answer for core outcome `o`, deciding `decision` when it was admitted."
  [o decision]
  (if (wcore/admitted? o)
    {:decision decision}
    {:decision :core-refused
     :because  (str (or (some-> (get-in o [:refused :reason]) name) (:error o) "refused"))}))

(defn- intent-goal
  "Workstream `ws-id`'s goal as a unit opened for it states it: its board label."
  [project ws-id]
  (or (some-> (cws/read-ws project ws-id) (wsv/label nil) str not-empty) ws-id))

(defn ^{:malli/schema [:=> [:cat :map :string :string] :map]}
  fire-unit
  "The :open-unit command a trigger's fire `routed` ({:project :trigger :payload}) opens as `unit`
   toward `goal`: its first turn is the trigger's skill on the rendered payload, run by the
   trigger's agent once the unit's session is up."
  [{:keys [project trigger payload]} unit goal]
  {:command    :open-unit :unit unit :project (name project) :goal goal
   :trigger    (name (:name trigger))
   :first-turn {:spec {:executor     (or (:agent trigger) :claude)
                       :instructions (str "/" (name (:skill trigger)) " "
                                          (triggers/render-payload (:payload trigger) payload))}}})

;; ── arrivals: a fire, a pen, a pickup ─────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  external-ref
  "The external ref an event payload names, or nil when it carries no usable id: its :adapter
   (default :notion) and :id, with :title, :url and :page-id where present."
  [payload]
  (let [id (:id payload)]
    (when (and id (not (str/blank? id)))
      (cond-> {:adapter (or (:adapter payload) :notion) :id id}
        (:title payload)   (assoc :title (:title payload))
        (:url payload)     (assoc :url (:url payload))
        (:page-id payload) (assoc :page-id (:page-id payload))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map [:? :boolean]] :Workstream]}
  ensure-workstream!
  "The workstream an arrival's `payload` is for: the one its ref already names, or a new one
   stamped with the payload's facets, born running nido's recipe unless `own-recipe?` says the
   arrival's skill carries its own. Not atomic: arrivals are dispatched one at a time."
  ([project payload] (ensure-workstream! project payload false))
  ([project payload own-recipe?]
   (let [facets (facets/select-facets (views/facet-properties project) payload)
         base   {:facets facets :activated? (not own-recipe?)}]
     (if-let [ref (external-ref payload)]
       (or (cws/find-by-ref project (:adapter ref) (:id ref))
           (cws/create! project (assoc base :external-refs [ref])))
       (cws/create! project (assoc base :external-refs []))))))

(declare core-open?)

(defn- run-briefing
  "What a run's turn is told of where it stands: its run, and where its status and artifacts go."
  [run-id]
  (str "This is nido run " run-id ". It has no workstream: write to no ledger. Its status file is "
       (cstate/run-status-path run-id) " and its artifacts directory " (cstate/run-artifacts-dir run-id)
       "; whatever this run leaves for others to read goes there."))

(defn ^{:malli/schema [:=> [:cat :map] :keyword]}
  start-run!
  "Start the run a fire `routed` ({:project :trigger :payload}) of a `:run?` trigger asks for: its
   id the trigger's session-name prefix (else its name) and a fresh suffix, its artifacts
   directory made, and `:start-run` sent to the core keyed by the trigger and the payload's id, so
   a fire re-emitted is answered rather than run twice. Its turn runs the trigger's skill on the
   rendered payload, offered no ask tool, in a session of the trigger's profile. Answers :opened or
   :refused."
  [{:keys [project trigger payload]}]
  (let [tname (name (:name trigger))
        id    (str (or (:session-name-prefix trigger) (str tname "-")) (subs (str (random-uuid)) 0 8))
        goal  (str/trim (str (name (:skill trigger)) " " (or (:title payload) (:id payload) "")))]
    (fs/create-dirs (cstate/run-artifacts-dir id))
    (if (wcore/admitted?
         (wcore/request!
          {:op      :command
           :ws      (core-client/run-key project id)
           :key     (str "run/" (name project) "/" tname "/" (or (:id payload) id))
           :command {:command :start-run
           :run     (cond-> {:id id :project (name project) :trigger tname :goal goal
                             :payload payload :fired-at (str (java.time.Instant/now))}
                      (:session-profile trigger) (assoc :profile (:session-profile trigger)))
           :spec    {:executor     (or (:agent trigger) :claude)
                     :ask?         false
                     :instructions (str "/" (name (:skill trigger)) " "
                                        (triggers/render-payload (:payload trigger) payload)
                                        "\n\n" (run-briefing id))}}}))
      :opened
      :refused)))

(defn ^{:malli/schema [:=> [:cat :map] :keyword]}
  open-fire!
  "Open the unit a spawning fire `routed` ({:project :trigger :payload}) asks for: its workstream
   found by the event's ref or made, then the trigger's unit opened in the core, keyed by the fire
   so a re-emit is answered rather than repeated. Answers :opened, :running (a unit of this
   trigger is still open there), :settled (the source works a ref once, and it has a workstream)
   or :refused."
  [routed]
  (let [project (:project routed)
        ref     (external-ref (:payload routed))
        pre     (when ref (cws/find-by-ref project (:adapter ref) (:id ref)))
        tname   (name (-> routed :trigger :name))
        units   #(vals (:units (wcore/state project %)))]
    (cond
      (and pre (true? (:once-per-ref? (:payload routed)))) :settled
      (and pre (some #(and (= :open (:status %)) (= tname (:trigger %))) (units (:id pre)))) :running
      :else
      (let [own? (true? (-> routed :trigger :own-recipe?))
            w    (or pre (ensure-workstream! project (:payload routed) own?))
            ;; A skill carrying its own procedure, on a workstream nobody put on the recipe, is
            ;; background work: off the board, and over when its turn completes.
            bg?  (and own? (not= :activated (cws/activation (cws/read-ws project (:id w)))))
            unit (str tname "-" (subs (str (random-uuid)) 0 8))
            goal (str/trim (str (name (-> routed :trigger :skill)) " "
                                (or (-> routed :payload :title) (-> routed :payload :id) "")))
            k    (str "fire/" (wcore/ws-key project (:id w)) "/" tname "/"
                      (or (:id ref) (random-uuid)) "/" (count (units (:id w))))]
        ;; A run that finished closed its workstream; a later fire on the same ref reopens it.
        (when (:closed? (wcore/state project (:id w)))
          (wcore/act! project (:id w) {:command :reopen-workstream} {:key (str k "/reopen")}))
        (if (wcore/admitted? (wcore/act! project (:id w)
                                         (cond-> (fire-unit routed unit goal) bg? (assoc :background? true))
                                         {:key k}))
          :opened
          :refused)))))

(defn ^{:malli/schema [:=> [:cat :map] :Workstream]}
  enqueue-inbox!
  "Pen a queue-mode fire for the person: its workstream, deduped on the payload's ref, made with
   the trigger and payload under :intake so a promote opens what the fire would have, and placed
   at :incoming. The core is told nothing until the person promotes it."
  [{:keys [project payload trigger]}]
  (let [ref (external-ref payload)
        pen! #(cws/append-entry! project % {:kind :stage-set}
                                 (pr-str {:format :stage-set :stage :incoming :by :nido}))]
    (if-let [existing (when ref (cws/find-by-ref project (:adapter ref) (:id ref)))]
      (if (empty? (:entries existing))
        (do (pen! (:id existing)) (cws/read-ws project (:id existing)))
        existing)
      (let [w (cws/create! project {:activated?    true
                                    :external-refs (if ref [ref] [])
                                    :facets (facets/select-facets (views/facet-properties project) payload)
                                    :intake {:trigger (:name trigger) :payload payload}})]
        (pen! (:id w))
        (cws/read-ws project (:id w))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :int :int] [:vector :string]]}
  expire-stale!
  "Drop every penned workstream of `project` still at :incoming and older than `max-age-ms` at
   `now-ms`, in its work records and, where the core holds it, there. Answers their ids."
  [project max-age-ms now-ms]
  (->> (cws/project-records project)
       (filter (fn [w] (and (nil? (cws/closure w))
                            (= :incoming (standing/chosen-stage project (:id w) w))
                            (>= (- now-ms (if-let [c (:created-at w)] (.toEpochMilli (java.time.Instant/parse c)) now-ms))
                                max-age-ms))))
       (mapv (fn [w]
               (cws/close! project (:id w) :dropped)
               (try (when (core-open? project (:id w)) (core-resolve! project (:id w) :drop nil))
                    (catch Exception _ nil))
               (:id w)))))

(defn- resolve-ref
  "Resolve `input` — a Notion URL, page id or BR-#### — to {:id :page-id :url :title}, or
   {:error <kw>}."
  [project input token]
  (let [norm #(select-keys (notion/normalise-page %) [:id :page-id :url :title])]
    (cond
      (str/blank? token) {:error :no-token}
      (notion/extract-page-id input)
      (let [page (notion/retrieve-page (notion/extract-page-id input) token)]
        (cond (:error page) {:error (:error page)}
              (str/blank? (:id (norm page))) {:error :not-a-ticket}
              :else (norm page)))
      :else
      (if-let [[_ n] (re-matches #"(?i)(?:BR-)?(\d+)" (str/trim (str input)))]
        (try
          (let [ds   (notion/resolve-data-source-id (:database (views/load-registry project)) token)
                resp (notion/data-source-query ds token {:filter {:property "ID" :unique_id {:equals (parse-long n)}}})]
            (cond (:error resp) {:error (:error resp)}
                  (first (:results resp)) (norm (first (:results resp)))
                  :else {:error :not-found}))
          (catch Exception _ {:error :notion-error}))
        {:error :unrecognized-input}))))

(defn- start-ticket!
  "Start ticket `r` ({:id :page-id :url :title}) as the person's: its ticket record opened, its
   work records written (`start/start-ticket!`), and its unit opened in the core, which provisions
   its session. Answers {:decision :driving :ref :continuing? :ws-id} or {:decision :unresolved
   :error <why> :ref}."
  [project {:keys [id page-id url title] :as r}]
  (let [existing (cws/find-by-ref-id project id)]
    (tickets/open! project id {:notion-page-id page-id :url url :title title :opened-by :pickup})
    (let [w (or existing (ensure-workstream! project {:id id :page-id page-id :url url :title title}))
          s (start/start-ticket! project {:ws-id (:id w)})
          d (when (= :started (:decision s)) (core-resolve! project (:id w) :promote nil))]
      (if (= :promoted (:decision d))
        {:decision :driving :ref r :continuing? (some? existing) :ws-id (:id w)}
        {:decision :unresolved :error (or (when-not d (:decision s)) (:because d) :core-refused) :ref r}))))

(defn- penned-fire
  "The fire a workstream penned by queue-mode intake was held back from, rebuilt from what the pen
   stored, or nil when it holds none or its trigger is gone."
  [project ws-id]
  (when-let [{:keys [trigger payload]} (:intake (cws/read-ws project ws-id))]
    (when-let [t (triggers/find-by-name (triggers/load-for-project project) trigger)]
      {:project project :trigger t :payload payload})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :boolean]}
  core-open?
  "Whether the core holds workstream `ws-id` open: it has heard of it, and has not closed it."
  [project ws-id]
  (let [s (wcore/state project ws-id)]
    (boolean (and (seq (:units s)) (not (:closed? s))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :string] :boolean]}
  core-merged!
  "Tell the core that what `ws-id`'s open units built has landed — `landing` names the landing, so
   a retry is answered rather than repeated — and close it. Whether it is closed."
  [project ws-id landing]
  (let [k (wcore/ws-key project ws-id)]
    (doseq [u (vals (:units (wcore/state project ws-id))) :when (= :open (:status u))]
      (wcore/act! project ws-id {:command :record-merge :unit (:id u)}
                  {:key (str "merged/" k "/" (:id u) "/" landing)}))
    (wcore/admitted? (wcore/act! project ws-id {:command :close-workstream}
                                 {:key (str "merged/" k "/close/" landing)}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any [:? :any]] [:maybe :map]]}
  core-resolve!
  "Do what gate action `action-id` asks of the core, as the person's keyed command; nil when the
   action is not one the core answers. An option answers the oldest open ask that is a question
   (never a machine failure) with its option, a reply with the person's words, Send answers with
   the one string `questions-answer` renders from a pick per question — its option, the person's
   comment on it, both — a withdrawal takes it back; Wake answers the machine's stop,
   the one recovery an error offers; Land grants the landing; Promote is
   the person's start, opening the workstream's unit; Done and Drop retract its open units and
   close it."
  [project ws-id action-id payload]
  (let [w    (wcore/state project ws-id)
        open (sort-by :id (filter #(= :open (:status %)) (vals (:operator-asks w))))
        ask  (first (remove domain/machine-ask? open))
        act! #(wcore/act! project ws-id %)
        text (when-not (map? payload) (some-> payload str str/trim not-empty))]
    (cond
      (= :wake action-id)
      (if-let [stop (first (filter domain/machine-ask? open))]
        (core-decision (act! {:command :answer-operator-ask :ask (:id stop) :answer "Wake it" :by :person})
                       :woken)
        {:decision :core-refused :because "its conductor is not stopped"})

      (option-action? action-id)
      (let [opt (nth (ask-options (get-in ask [:content :options])) (option-index action-id) nil)]
        (cond (nil? ask) {:decision :core-refused :because "nothing is asked"}
              (nil? opt) {:decision :option-stale}
              :else (core-decision (act! {:command :answer-operator-ask :ask (:id ask)
                                          :answer (:option opt) :by :person})
                                   :answered)))

      (= :answer-questions action-id)
      (let [said (when ask (questions-answer (:content ask) (:answers payload)))]
        (cond (nil? ask) {:decision :core-refused :because "nothing is asked"}
              (nil? said) {:decision :core-refused :because "every question was left open"}
              :else (core-decision (act! {:command :answer-operator-ask :ask (:id ask) :answer said :by :person})
                                   :answered)))

      (= :reply action-id)
      (if ask
        (if text
          (core-decision (act! {:command :answer-operator-ask :ask (:id ask) :answer text :by :person})
                         :answered)
          {:decision :core-refused :because "an answer needs words"})
        (when text
          (core-decision (act! {:command :post-chat :message (str "m-" (random-uuid)) :text text})
                         :posted)))

      (= :withdraw action-id)
      (if ask
        (core-decision (act! {:command :withdraw-operator-ask :ask (:id ask)
                              :reason (or text "withdrawn by the person") :by :person})
                       :withdrawn)
        {:decision :core-refused :because "nothing is asked"})

      ;; Land is the person's grant, and only over a change that is landable as it stands: a
      ;; release candidate, or a PR published before candidates were read. A grant given
      ;; anywhere else would be spent on whatever the arc reaches next.
      (= :land action-id)
      (if (#{:release-candidate :published} (:at (pipeline/of project ws-id)))
        (core-decision (act! {:command :give-grant :grant :merge :by "person"}) :granted)
        {:decision :nothing-to-land})

      ;; A workstream the pen held back starts as its fire would have.
      ;; The workstream's first unit is named for it; a later arc's, after its own count.
      (= :promote action-id)
      (let [unit (if (contains? (:units w) ws-id) (str ws-id "-" (count (:units w))) ws-id)]
        (core-decision (act! (if-let [fire (penned-fire project ws-id)]
                               (fire-unit fire unit (intent-goal project ws-id))
                               {:command :open-unit :unit unit :project (name project)
                                :goal (intent-goal project ws-id)}))
                       :promoted))

      (#{:done :drop} action-id)
      (do (doseq [u (vals (:units w)) :when (= :open (:status u))]
            (act! {:command :retract-unit-goal :unit (:id u)}))
          (core-decision (act! {:command :close-workstream}) (if (= :done action-id) :done :dropped)))

      :else nil)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:maybe :string] [:maybe [:sequential :keyword]]
                           [:? [:maybe [:sequential :string]]]]
                     :map]}
  person-says!
  "What a person said to workstream `ws-id` outside a gate — from a bb verb, or passed on by the
   workstream's chat — as their commands to the core: `words` answer the oldest question awaiting
   them, or, when none does, are posted to the conductor's chat — and wake a conductor that
   stopped on a failure, so it hears them; each of `grants` is given. `images`
   (digests, kept in the core's image store) go with the words to the conductor's chat — with an
   answer, on a chat message carrying the answer's words and saying which ask they answered. Answers
   {:answered ask-id-or-nil :posted bool :granted [kind] :refused [reason]}."
  ([project ws-id words grants] (person-says! project ws-id words grants nil))
  ([project ws-id words grants images]
   (let [w      (wcore/state project ws-id)
         open   (sort-by :id (filter #(= :open (:status %)) (vals (:operator-asks w))))
         ;; Words answer a question, never a machine failure: on a stopped conductor they are
         ;; posted, and the stop is then woken so its next turn hears them.
         ask    (first (remove domain/machine-ask? open))
         stop   (first (filter domain/machine-ask? open))
         images (vec images)
         post!  #(wcore/act! project ws-id (cond-> {:command :post-chat :message (str "m-" (random-uuid))
                                                    :text %}
                                             (seq images) (assoc :images images)))
         said   (when-not (str/blank? (str words))
                  (if ask
                    [:answered (wcore/act! project ws-id {:command :answer-operator-ask :ask (:id ask)
                                                          :answer (str words) :by :person})]
                    [:posted (post! (str words))]))
         ;; Images that went with an answer reach the conductor beside it, said to be its.
         shown  (when (and (= :answered (first said)) (seq images))
                  (post! (str words "\n\n(The person's answer to ask " (:id ask) ", with the images it carried.)")))
         woken  (when (and stop (= :posted (first said)) (wcore/admitted? (second said)))
                  (wcore/act! project ws-id {:command :answer-operator-ask :ask (:id stop)
                                             :answer "Wake it" :by :person}))
         gs     (for [g grants] [g (wcore/act! project ws-id {:command :give-grant :grant g :by "person"})])
         outs   (cond-> (vec (map second gs)) said (conj (second said)) shown (conj shown)
                  woken (conj woken))]
     {:answered (when (and (= :answered (first said)) (wcore/admitted? (second said))) (:id ask))
      :posted   (boolean (or (and (= :posted (first said)) (wcore/admitted? (second said)))
                             (and shown (wcore/admitted? shown))))
      :granted  (vec (for [[g o] gs :when (wcore/admitted? o)] g))
      :refused  (vec (for [o outs :when (not (wcore/admitted? o))]
                       (or (some-> (get-in o [:refused :reason]) name) (str (:error o)))))})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :any [:? :any]] :map]}
  resolve-gate!
  "Apply a gate follow-action, dispatching on `action-id`. A workstream-less ws-id
   (e.g. a bare watched-view row) is a no-op — {:decision :no-workstream} — for
   every action but :restore, :dismiss and :start-triage, which such a row
   legitimately offers (see workstream-less-actions).
     :promote -> set-stage! :in-progress   :dismiss -> off-radar (ticket + ws :dismissed)
     :drop    -> close! :dropped            :done    -> set-stage! :done
     :apply   -> apply! the verdict with the answers `payload` carries and record the
                 acceptance, iff `payload` still names the latest report
     :defer   -> apply! it with every question left open, under the same check
     :reply   -> answer a conductor's escalation with `payload`, else resume! the
                 parked agent with it
     :option-a … :option-f -> resume! with the blocker branch that letter names,
                              iff `payload` still names the latest report
     :answer-questions -> record one answer per question of a blocker asking several, and
                          resume with them all, iff `payload` {:seq :answers} still names it
     :assert-gate -> advance-phase! with `payload` as the gate's evidence
     :land    -> queue the published PR on the merge lane, iff `payload` still names
                 the record the landing is owed over
     :restore -> restore! (clear ticket status + reopen at :triaging)
     :start-triage -> start-triage-page! (force-spawn the triage trigger)

   `payload` is whatever the click carried besides its id, and what that is
   depends on the action: the reply text for :reply, the evidence for :assert-gate,
   and the :seq of the report the button was rendered from for every id
   position-carrying-action? names — for :apply, {:seq <that> :answers [<letter or
   nil per question>]} when the gate asked questions, else the :seq alone.
   Every other action resolves entirely nido-side and ignores it.
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

     ;; Coordination is the core's: every action it answers is the person's command to it.
     (contains? #{:reply :withdraw :wake :land :promote :done :drop :answer-questions} action-id)
     (or (core-resolve! project ws-id action-id payload) {:decision :core-refused :because "nothing to do"})

     ;; Not a `case` branch: the id set is derived from report/option-letters, and case needs
     ;; literals — spelling six of them here is a second copy of that vector.
     (option-action? action-id)
     (core-resolve! project ws-id action-id payload)

     :else
     (case action-id
       ;; An Accept answers every question it was shown, open ones as nil: one that carried no
       ;; answers answers none, so a report that asks is refused it — only Defer leaves all open.
       :apply   (apply! project ws-id (if (map? payload)
                                        {:at-seq (:seq payload) :answers (vec (:answers payload))}
                                        {:at-seq payload :answers []}))
       :defer   (apply! project ws-id {:at-seq payload})
       :assert-gate  (advance-phase! project ws-id payload)
       (throw (ex-info "Unknown gate action" {:action-id action-id :ws-id ws-id}))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:? [:map [:brief {:optional true} [:maybe :string]]
                                                                   [:unit {:optional true} :string]]]] :map]}
  activate!
  "Hand `ws-id` to the core for a person: their :activated entry on its ledger — the grant every
   recipe gate reads (the core's code hold, the conductor's recipe lines, nido:owed, review,
   land:check) — then their command opening its unit — named `:unit`, else for the workstream —
   whose session the core provisions, named for the unit, never one already up. Given `:brief`,
   that is the unit's first turn, run once its session is ready; without one the conductor hears
   the session ready and decides what it is for. The entry is written first, so that first turn
   already starts under the recipe's hold. Activating twice changes nothing, but records the
   grant when an earlier activation left the ledger without it. Answers {:ws-id :already?};
   throws when the workstream is closed or the core refuses."
  [project ws-id & [{:keys [brief unit] :or {unit ws-id}}]]
  (let [p        (keyword (name project))
        _        (cws/activate! p ws-id nil)
        already? (contains? (:units (wcore/state p ws-id)) unit)
        o        (when-not already?
                   (wcore/act! p ws-id (cond-> {:command :open-unit :unit unit :project (name p)
                                                :goal (intent-goal p ws-id)}
                                         brief (assoc :first-turn {:spec {:executor :claude :instructions brief}}))))]
    (when (and o (not (wcore/admitted? o)))
      (throw (ex-info (str "the core refused — " (or (some-> (get-in o [:refused :reason]) name) (:error o)))
                      {:ws-id ws-id :outcome o})))
    {:ws-id ws-id :already? already?}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName :string] :WorkstreamId]}
  start-goal!
  "Start work on a goal: a workstream minted for it, handed to the core with `brief` as its unit's
   first turn, in a session named `session` that the core provisions. The way an exploration
   becomes nido's work — its findings go in the brief, never a session already up. Answers the
   workstream's id; throws when the core refuses, leaving the minted workstream behind."
  [project session brief]
  (let [p  (keyword (name project))
        id (:id (cws/create! p {:external-refs []}))]
    (activate! p id {:brief brief :unit session})
    id))

(defn- pause-of
  "The person's pause the pane shows, or nil when none stands: the ledger's standing pause as
   {:at :note}, else {} when the board row `row` reads paused all the same — the core holds every
   open unit — so the pane offers Resume exactly when the board reads Paused."
  [project ws-id w row]
  (if-let [p (cws/pause w)]
    {:at (:at p) :note (:note (cws/entry-at-seq project ws-id (:seq p)))}
    (when (get-in row [:status :paused]) {})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:maybe :string]] :map]}
  pause!
  "Pause `ws-id` for a person — their choice not to push it forward for now. Both records of it
   are written together: the ledger's :paused, with `note`, which the pane and the pipeline read,
   and each open unit held by their command to the core, so no turn starts on it. Answers {:ws-id}."
  [project ws-id note]
  (cws/pause! project ws-id note)
  (doseq [u (vals (:units (wcore/state project ws-id))) :when (= :open (:status u)) :when (not (:held? u))]
    (wcore/act! project ws-id {:command :hold-unit :unit (:id u)}))
  {:ws-id ws-id})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  resume!
  "Lift the person's pause on `ws-id`, whichever record carries it: each held unit released by
   their command to the core, and a standing ledger :paused answered with :resumed — including one
   written before the core held anything. Answers {:ws-id}."
  [project ws-id]
  (doseq [u (vals (:units (wcore/state project ws-id))) :when (:held? u)]
    (wcore/act! project ws-id {:command :release-unit :unit (:id u)}))
  (cws/resume! project ws-id)
  {:ws-id ws-id})

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId [:maybe :string]] :map]}
  deactivate!
  "Take nido's recipe off `ws-id` for a person: their :deactivated entry on its ledger — so its
   designs stop gating its landing and nothing is owed on it — and each open unit held by their
   command to the core, so no turn starts on it until they release it (`resume!`). Answers {:ws-id
   :was?}, :was? false when no open unit was running free."
  [project ws-id session]
  (cws/deactivate! project ws-id session)
  (let [open (filter #(and (= :open (:status %)) (not (:held? %)))
                     (vals (:units (wcore/state project ws-id))))]
    (doseq [u open] (wcore/act! project ws-id {:command :hold-unit :unit (:id u)}))
    {:ws-id ws-id :was? (boolean (seq open))}))



(defn ^{:malli/schema [:=> [:cat :ProjectName :SessionName :map] :any]}
  start!
  "Bring a person's own session `session-name` up: no workstream's, and recorded as no work — a
   workstream's session is the core's to provision. Heavy and slow: surfaces wrap it in their own
   progress UI. Idempotent on a live session. `project` may be a keyword or string; `opts` are
   lifecycle/up!'s."
  [project session-name opts]
  (lifecycle/up! session-name (assoc opts :project (name project))))


(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  open-target
  "Where `open` lands for a workstream: the session of an open unit the core has ready, else of
   any unit it has ready, else nil — the latest unit first. Returns {:project :session}."
  [project ws-id]
  (let [ss   (rseq (cws/sessions project ws-id))
        up?  #(= :ready (:status %))
        pick (or (first (filter #(and (:open? %) (up? %)) ss)) (first (filter up? ss)))]
    (when pick {:project project :session (:name pick)})))



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
   independent traversal work_test.clj's board-bands-covers-every-driven-row-exactly-once
   cross-checks board-bands against, so a one-sided edit to either breaks that test.
   Do not delete as 'unused'."
  [grouped]
  (concat (:incoming grouped)
          (get-in grouped [:triage :in-flight])
          (get-in grouped [:triage :queued])
          (:in-progress grouped)
          (:shipping grouped)
          (:awaiting-gate grouped)
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
  (let [rows (if-let [p (held-project project)]
               (into [] (mapcat val) (:by-stage p))
               (remove #(= :done (:stage %)) (list-workstreams project)))
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

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  live-session-names
  "Set of session names for `project` that are actually up, as `sstate/session-live?` reads each one's
   registry entry — a `:lite` session included. THE liveness oracle: the TUI board, the web
   grouping, the adopter, the runtime migration and the winding-down band all read this one fn."
  [project]
  (let [registry (sstate/read-registry)]
    (->> (lifecycle/list-all-data {:project (name project)})
         :sessions
         (filter #(sstate/session-live? (get registry (:worktree %))))
         (map :name)
         set)))

(def ^:private registry-prune-grace-ms
  "Never prune an entry younger than this. `:created-at` is restamped on every
   `up!` (engine/start-session!), so this only ever shields a session that just
   started — belt and braces on top of the fact that a full entry is written
   AFTER its services are listening, and a boot placeholder is not prunable
   while its boot runs."
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
   records :app-port/:nrepl-port nil) — is live while its entry stands, under sstate/session-live?'s
   definition, so judging it dead on liveness grounds is a category error: it
   would be pruned while in active use, and reclaim/orphan-instance-dirs would
   then delete its state dir.

   A boot placeholder records no port either, but it IS a candidate once the
   background boot it names has ended: a boot that finished replaced it, one
   that failed removed it, so one still here belongs to a boot that was killed."
  [entry]
  (case (sstate/entry-phase entry)
    :serving true
    :booting (not (proc/process-alive? (:boot-pid entry)))
    false))

(defn- prune-veto?
  "Keep an entry whose ports do not answer but whose JVM is still running.
   `sstate/session-live?` ignores :repl-pid on purpose — a recycled PID would make a
   dead session read live forever, the wrong failure for the ORACLE. Deleting is
   the opposite trade: a false `dead` costs a PGDATA via reclaim, a recycled PID
   costs only a delayed prune. So a live PID vetoes the DELETE without ever
   making the oracle report `live`."
  [entry]
  (boolean (and (pos-int? (:repl-pid entry))
                (proc/process-alive? (:repl-pid entry)))))

(defn- registered-since-start?
  "Whether the entry was registered after this process started — so it cannot have died with the
   host, whatever the boot reading says."
  [entry]
  (boolean
   (when-let [started (some-> (java.lang.ProcessHandle/current) .info .startInstant (.orElse nil))]
     (when-let [at (try (java.time.Instant/parse (str (:created-at entry))) (catch Exception _ nil))]
       (.isAfter at started)))))

(defn ^{:malli/schema [:=> [:cat [:? :int]] :any]}
  prune-dead-registry!
  "Drop every registry entry whose session no longer holds a port, and return
   their instance-ids. The registry is otherwise only cleaned by a graceful
   `down!` (engine/stop-session! → state/remove-from-registry!), so a reboot, a
   JVM crash or a `kill` leaves an entry — and its phantom Winding-down row —
   behind forever. Entries inside the grace window are left alone.

   Two extra guards keep this DELETE path safe, distinct from sstate/session-live?'s
   ORACLE contract (a false `live` there is the expensive mistake — see
   sstate/session-live?'s docstring):
     prunable?    — an entry with no recorded port (a `:lite` session) is never
                    a candidate; it is live while its entry stands, and judging
                    it dead would be a category error, not a liveness result.
     prune-veto?  — an entry whose :repl-pid is still running is kept even
                    though its ports don't answer; a false `dead` here costs a
                    PGDATA via reclaim, so the JVM's liveness (not just its
                    ports) gets a say before deletion.

   A host boot reads as a crash to every guard above, so before any entry goes, every entry
   registered before the boot — removable or not — is held in the boot's casualty record, the one
   account of what was up that outlives this prune. An entry the record could not take is not
   removed: when the record cannot be written, or the host boot cannot be read, only entries
   registered since this process started — which cannot have died with the host — are removed.

   Registry-global, not per-project: one call covers every project."
  ([] (prune-dead-registry! (System/currentTimeMillis)))
  ([now-ms]
   (let [registry (sstate/read-registry)
         holding  (casualties/record! (vec (vals registry)))
         dead (->> registry
                   (remove (fn [[_ entry]]
                             (or (not (prunable? entry))
                                 (sstate/session-live? entry)
                                 (prune-veto? entry)
                                 (when-let [age (entry-age-ms entry now-ms)]
                                   (< age registry-prune-grace-ms)))))
                   (filter (fn [[_ entry]]
                             (or (= :held (:outcome holding))
                                 (registered-since-start? entry))))
                   vec)]
     (sstate/remove-many-from-registry! (map first dead))
     (mapv (fn [[k entry]] (or (:instance-id entry) k)) dead))))

(defn ^{:malli/schema [:=> [:cat] :Unsettled]}
  owed-revivals
  "The unsettled casualties a person is owed a revive of — one per workstream. A casualty whose
   runtime key names a workstream is owed while that workstream is open and holds a session a
   person works in, and carries that session — the newest such, with its worktree — whatever
   session the runtime was last registered under: a workstream only Runs worked is the daemon's
   to account for, and its conductor is the conduct pass's to start. A casualty whose workstream
   cannot be read, or that names none, is owed nothing: every revive offered is known to be an
   open workstream holding a person's session."
  []
  (let [{:keys [casualties] :as unsettled} (casualties/unsettled)
        owed (keep (fn [{:keys [project runtime-key] :as c}]
                     (let [p (keyword project)
                           w (when runtime-key (try (cws/read-ws p runtime-key) (catch Exception _ nil)))]
                       (cond
                         (nil? w)          nil
                         (cws/closure w)   nil
                         :else
                         (when-let [s (->> (try (csession/list-sessions p runtime-key) (catch Exception _ nil))
                                           (filter csession/person?)
                                           (sort-by #(str (:created-at %)))
                                           last)]
                           (let [wt (try (:wt-path (lifecycle/session-coords (:name s) {:project project}))
                                         (catch Exception _ nil))]
                             (cond-> (assoc c :session (:name s))
                               wt (assoc :worktree (str wt))))))))
                   casualties)]
    (assoc unsettled :casualties
           (->> owed
                (reduce (fn [[seen acc] c]
                          (let [k [(:project c) (or (:runtime-key c) (:instance-id c))]]
                            (if (seen k) [seen acc] [(conj seen k) (conj acc c)])))
                        [#{} []])
                second))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :any]}
  bring-down!
  "Down every live session of a workstream — the winding-down band's one action.
   Synchronous and slow (lifecycle/down! per session); callers own async + UI
   optimism. Returns {:downed [names]}."
  [project ws-id]
  (let [live  (live-session-names project)
        names (->> (cws/sessions project ws-id)
                   (map :name)
                   (filterv live))]
    (doseq [n names]
      (lifecycle/down! n {:project (name project)}))
    {:downed names}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :Path] [:vector :map]]}
  machine-rows
  "Machine facts for every worktree of one project: registry entry, liveness as `sstate/session-live?` reads it,
   RSS for the repl JVM + PG, and the configured heap ceiling. No UI-optimistic
   state — that is a surface concern injected by callers that need it.

   The RSS is the runtime's, which every session on it shares, and the PG's, which in shared mode
   every session of the project shares: :runtime and :pg-pid name what each was read from, so a
   sum across rows counts each once."
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
                        live?    (sstate/session-live? entry)
                        iid      (:instance-id entry)
                        repl-rss (when (and live? (:repl-pid entry))
                                   (proc/rss-bytes (:repl-pid entry)))
                        session  (when (and live? iid) (sstate/read-session iid))
                        pg-pid   (when session
                                   (get-in session [:service-states :pg :pg-pid]))
                        pg-rss   (when (and live? pg-pid) (proc/rss-bytes pg-pid))
                        heap-max (when session
                                   (get-in session [:context :session :jvm :heap-max]))]
                    {:name nm :wt-path wt-path :entry entry :live? live?
                     :runtime iid :pg-pid pg-pid
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
          (for [{:keys [name entry live? runtime pg-pid repl-rss pg-rss heap-max]}
                (machine-rows (clojure.core/name project) dir)
                :when (contains? keep name)]
            [name {:live? live? :url (:url entry)
                   :pg-port (:pg-port entry) :nrepl-port (:nrepl-port entry)
                   :app-port (:app-port entry) :runtime runtime :pg-pid pg-pid
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

(defn- improvement-journal [project]
  (records/journal project proposal/journal-name))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :map] :map]}
  decide-proposal!
  "Record a human decision about one proposal, held by `holder` (`<holder>/<seq>.<obs>` its
   address). Returns {:decision :recorded} or {:decision :stale :latest <n>} when the improvement
   journal no longer holds the `at-seq` records the reader was looking at.

   The whole act is the append to the journal: nothing is resumed and nothing is spawned. Which
   means an approval is a commitment and not a completion, and the surface has to be able to say
   so. `record-landing!` is what discharges one.

   `at-seq` is compared by the core's writer, so two people deciding from the same page cannot both
   be recorded: the second is told the page moved."
  [project holder {:keys [analysis-seq observation verdict at-seq note]}]
  (records/decide-and-append!
   (improvement-journal project)
   (fn [rs]
     (if (not= at-seq (count rs))
       {:answer {:decision :stale :latest (count rs)}}
       {:append (cond-> {:kind         :improvement-decision
                         :holder       holder
                         :analysis-seq analysis-seq
                         :observation  observation
                         :verdict      verdict
                         :at-seq       at-seq
                         :decided-by   @decided-by}
                  (not (str/blank? (str note))) (assoc :note note))
        :then {:decision :recorded :seq (inc (count rs))}}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :map] :map]}
  record-landing!
  "Record that an approved proposal is now carried by `rev`. Returns
   {:landed :recorded :seq <seq>}.

   No `at-seq` guard, unlike `decide-proposal!`. That guard exists because a
   decision is an answer to a page, and answering a page that has moved is not
   answering what is there. This is not an answer to anything — it is a fact
   about the repository, true whatever the journal has grown since — so refusing
   it on a stale position would reject a true record for a reason that does not
   apply to it.

   Nothing checks that the proposal was approved first, and nothing should. A
   proposal implemented before anyone got round to deciding on it is a normal
   sequence here, and a record that refused to describe it would leave the code
   landed and the surface saying nothing happened.

   What IS checked is that the address names a proposal: a landing recorded
   against a seq or an index no analysis made discharges nothing, and the
   proposal it was meant for would stay owed behind a reported success."
  [project holder {:keys [analysis-seq observation rev note]}]
  (when-not (some #(= [holder analysis-seq observation]
                      [(:ws-id %) (:analysis-seq %) (:observation %)])
                  (proposal/of-project (keyword (name project))))
    (throw (ex-info (str "No proposal at " holder "/" analysis-seq "." observation)
                    {:holder holder :analysis-seq analysis-seq :observation observation})))
  (records/decide-and-append!
   (improvement-journal project)
   (fn [rs]
     {:append (cond-> {:kind         :improvement-landed
                       :holder       holder
                       :analysis-seq analysis-seq
                       :observation  observation
                       :landed-by    @decided-by}
                (not (str/blank? (str rev)))  (assoc :rev rev)
                (not (str/blank? (str note))) (assoc :note note))
      :then {:landed :recorded :seq (inc (count rs))}})))

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
               (try {:project (name pname) :grouped (grouped pname)}
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
     :groups  (all-grouped)  :gates / :errors / :unavailable (read-gates)  :pending (#{\"project/ws-id\"} optimistic bridge keys)
     :winddown-pending (#{\"project/ws-id\"} optimistic bridge keys for pending bring-down!s).
   Selection detail is attached by the caller (needs work/workstream + dev-states).

   NO row filtering: the board selects BANDS (board-bands), never rows —
   filtering here is what hid every :in-progress row behind the source chip's
   `source=notion` default."
  [{:keys [scope] :or {scope "all"}}
   {:keys [groups gates errors unavailable pending winddown-pending]
    :or {groups [] gates [] errors [] pending #{} winddown-pending #{}}}]
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
     :groups      scoped
     :gates       kept-gates
     ;; The projects whose gates could not be read (`read-gates`), so Needs you says so rather
     ;; than reading as nothing waiting.
     :unavailable (or unavailable {})
     ;; Every project's, not the scope's: Needs you spans all projects whatever
     ;; a view is focused on, and this is the count its badge shows.
     :needs-count (count gates)
     ;; Every project's machine-side failures, apart from Needs you: an error asks the person
     ;; nothing, it says what failed and offers its recovery. Each marked :pending? while its
     ;; recovery is in flight, as a gate is.
     :errors      (mapv (fn [e] (assoc e :pending? (contains? pending (str (:project e) "/" (:ws-id e)))))
                        errors)
     :error-count (count errors)
     ;; Every project's release candidates, for the same reason: their own list and badge,
     ;; apart from Needs you, since a candidate asks nobody anything.
     :candidates  (release-candidates groups)}))

;; ── the acts a surface performs on the work plane ────────────────────────────
;;
;; The TUI and the dashboard reached `lane.pickup` and `lane.findings` directly for these. Each
;; one IS work-plane vocabulary — resolve a pasted ref into a workstream, file a findings round on
;; one — so it belongs at this altitude
;; rather than behind a require of the lane that happens to implement it today.

(def arc-stages
  "The stages a unit of work travels, in order. The plane naming its own spine — a surface
   deciding whether a key is a stage should ask the work plane, not the lane that happens to
   compute the arc today."
  pipeline/unit-stages)

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :NotionToken] :map]}
  pickup!
  "Resolve a pasted Notion URL / page-id / BR-#### and start its ticket: its workstream
   activated, its impl session brought up."
  [project input token]
  (let [r (resolve-ref project input token)]
    (cond
      (:error r) {:decision :unresolved :error (:error r)}
      ;; An unaccepted Slack proposal holds the ticket: only accepting it at its gate starts it.
      (tickets/held-by-gate? project (:id r)) {:decision :unresolved :error :awaiting-gate :ref r}
      :else (start-ticket! project r))))

(def start-intent-trigger
  "The leg a described intent is enqueued at. Named here because a surface asking whether a
   description would actually be processed is asking about this specific trigger's breaker, and
   neither side should hardcode the answer."
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
   :note? :session?}. Appends the event, seeds the tracker, reopens to :in-progress, and — for a
   ticket it started again — reopens it in the core and opens its unit there."
  [project ws-id opts]
  (let [r (findings/file! project ws-id opts)]
    (when (= :started (get-in r [:start :decision]))
      (wcore/act! project ws-id {:command :reopen-workstream})
      (core-resolve! project ws-id :promote nil))
    r))

;; ── What the improvement sweep writes ───────────────────────────────────────

(defn- run-payload
  "The payload the fire of run `run-key` carried."
  [run-key]
  (get-in (core-client/value! (core-client/state run-key) :state) [:run :payload]))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :map] :map]}
  record-plan!
  "Record one day's plan as what plan run `run-key` did, or refuse it. Returns
   {:plan :recorded :seq <seq>} or {:plan :refused :defect <map>}.

   The refusal is the point. A plan claims to partition what is owed, and a record's schema cannot
   check that — it validates the shape and has no access to the project's derived owed set — so
   this derives the owed set ITSELF and refuses a grouping that does not cover it exactly once.

   Not ordered against a decision made between the derivation and the append: the plan is the
   run's record and a decision the journal's. What makes that safe is the frontier — the sweep
   re-derives against it before acting, so a plan that a later decision moved past is re-planned."
  [project run-key {:keys [date claims frontier]}]
  (let [pk     (keyword (name project))
        ow     (proposal/owed (proposal/of-project pk) (proposal/plans-of pk)
                              (proposal/claim-attempts pk))
        defect (proposal/partition-defect claims ow)]
    (if defect
      {:plan :refused :defect defect}
      {:plan :recorded
       :seq (records/append-to-run! run-key {:kind :improvement-plan :date date
                                             :frontier frontier :claims (vec claims)})})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :map] :map]}
  reserve-claim!
  "Fix the veto deadline of the claim claim run `run-key` carries, or refuse it. Returns
   {:reserved :recorded} or {:reserved :vetoed :declined [<address> ...]}.

   The deadline is a record in the improvement journal rather than a moment inside an agent,
   because otherwise the board cannot answer the one question a late decline raises: did this stop
   anything. A reservation is what a later decline is compared against.

   Eligibility is decided from the journal's records as read, and the reservation appended after
   exactly those: a decline the core's writer committed in between makes the append stale, and the
   journal is read and the claim decided again. So a decline either comes first and is seen, or
   comes after and finds the reservation standing.

   A refused claim ends the run's claim here, `:vetoed` — not left to the caller, because \"a
   refused claim releases the slot\" is an invariant, and :vetoed rather than :dropped because the
   two mean opposite things to the owed set: a dropped claim's addresses are tried, a vetoed
   claim's survivors are owed again, and only the declined one leaves."
  [project run-key {:keys [plan-seq claim addresses]}]
  (let [pk     (keyword (name project))
        holder (:plan-ws (run-payload run-key))
        r      (records/decide-and-append!
                (improvement-journal pk)
                (fn [rs]
                  (let [hit (filterv (proposal/declined pk rs) addresses)]
                    (if (seq hit)
                      {:answer {:reserved :vetoed :declined hit}}
                      {:append {:kind :improvement-claim-reserved :holder (str holder)
                                :plan-seq plan-seq :claim claim :addresses (vec addresses)}
                       :then {:reserved :recorded}}))))]
    (when (= :vetoed (:reserved r))
      (records/append-to-run! run-key {:kind :closed :outcome :vetoed :by :nido}))
    r))

(def ^:private push-landed
  "Push outcomes that mean nido's main holds the revision.

   Both record. `:already-there` is not a failure and treating it as one is the
   subtler bug: a process that dies between a successful push and its first
   ledger append leaves a retry finding the remote already at that revision, so
   a rule of `record only if it moved` would leave that landing unwritten
   forever."
  #{:advanced :already-there})

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :map] :map]}
  discharge-claim!
  "Push `rev` for the claim claim run `run-key` carries and record what it carried, or refuse.
   Returns {:discharged :recorded :addresses [...]}, {:discharged :unreserved} or
   {:discharged :not-pushed :outcome <kw> :detail <string>}.

   The only path by which the sweep reaches nido's main. A revision arriving any
   other way records no landing and ends no claim, which leaves the claim owed — visible, and
   re-runnable.

   Refuses without a standing reservation, which is what makes the veto a
   restriction rather than an instruction: a claim refused at `reserve-claim!`
   has no reservation, so there is nothing here that will push for it.

   The deadline the reservation fixed is what a decline arriving now is answered against.

   Re-runnable, because it has to be: an interruption between the push and the last append leaves
   the claim unended, and running it again appends only for the addresses that do not already carry
   a landing at this revision — the same skip that makes backfill-landings! re-runnable — then
   records the claim done. The push it re-attempts reports `:already-there` and records exactly as
   the first would."
  [project run-key {:keys [worktree bookmark rev addresses]}]
  (let [pk (keyword (name project))
        {:keys [plan-ws plan-seq claim]} (run-payload run-key)
        reserved? (boolean (some #(and (= :improvement-claim-reserved (:kind %))
                                       (= (str plan-ws) (:holder %))
                                       (= (str plan-seq) (str (:plan-seq %)))
                                       (= (str claim) (str (:claim %))))
                                 (records/of (improvement-journal pk))))]
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
            (records/append-to-run! run-key {:kind :closed :outcome :done :by :nido})
            {:discharged :recorded :addresses (vec addresses)}))))))

;; ── drains ──────────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] [:vector :map]]}
  drains
  "Every open drain, as the drains page shows it (`view.drains`), across every project — or the one
   `scope` names, when it names one other than \"all\". A project whose drains cannot be read
   shows none."
  [scope]
  (vec (mapcat (fn [[pname _]]
                 (when (or (nil? scope) (= "all" scope) (= scope pname))
                   (try (drains-view/drains (keyword pname)) (catch Throwable _ []))))
               (project/list-projects))))

(defn ^{:malli/schema [:=> [:cat :string :map] :map]}
  start-drain!
  "The person starting a drain from the drains page in `project`: `:exit` the exit criterion, and
   what established the pattern — `:established-by-ws` a workstream, else `:established-by-commit`
   a landed commit. Answers {:ok ws-id} or {:refused message}, as `drain-act!` does."
  [project {:keys [exit established-by-ws established-by-commit]}]
  (let [blank? #(str/blank? (str %))]
    (cond
      (blank? project) {:refused "Name the project the drain belongs to."}
      (blank? exit)    {:refused "Name the drain's exit criterion."}
      (and (blank? established-by-ws) (blank? established-by-commit))
      {:refused "Name the workstream or the landed commit that established the pattern."}
      :else
      (try
        {:ok (drain-lane/start-drain!
              (keyword (name project))
              {:exit (str/trim (str exit))
               :established-by (if-not (blank? established-by-ws)
                                 {:ws-id (str/trim (str established-by-ws))}
                                 {:commit (str/trim (str established-by-commit))})})}
        (catch clojure.lang.ExceptionInfo e
          {:refused (ex-message e)})))))

(def ^:private drain-core
  "How lane-drain reaches the core: a drain's state, a keyed post to its conductor, a slice's unit
   opened on its brief, and a slice's landing granted."
  {:state       (fn [project ws-id] (wcore/state project ws-id))
   :post!       (fn [project ws-id k text]
                  (wcore/act! project ws-id {:command :post-chat :message k :text text}
                              {:key (str "drain/" (name project) "/" ws-id "/" k)}))
   :open-slice! (fn [project slice-id unit brief]
                  (let [r (wcore/act! project slice-id
                                      {:command :open-unit :unit unit :project (name project)
                                       :goal (str "slice " slice-id)
                                       :first-turn {:spec {:executor :claude :instructions brief}}}
                                      {:key (str "slice/" (name project) "/" slice-id "/" unit)})]
                    (when-not (wcore/admitted? r)
                      (throw (ex-info (str "the core refused the slice's unit: " (pr-str (or (:refused r) (:error r))))
                                      {:reason :core-refused})))
                    r))
   :land!       (fn [project slice-id] (:decision (core-resolve! project slice-id :land nil)))})

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  drain-fire!
  "Carry out one fire a drain source emitted (`lane-drain/handle-fire!`), reaching the core."
  [fire]
  (drain-lane/handle-fire! drain-core fire))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  drain-wakes!
  "Wake each open drain's coordinator for each event it has not been woken for."
  [project]
  (drain-lane/wakes! project drain-core))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  drain-land!
  "The person landing slice `slice-id` (`lane-drain/land!`)."
  [project slice-id]
  (drain-lane/land! drain-core project slice-id))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :keyword [:maybe :string]] :map]}
  drain-act!
  "Carry out one of the person's acts on drain `ws-id` from the drains page: :approve the recipe
   proposal whose seq is `arg`, :land the slice `arg`, or :close the drain. Answers {:ok result}
   or {:refused message}: a refusal names what the act's records did not support."
  [project ws-id act arg]
  (let [project (keyword (name project))]
    (try
      {:ok (case act
             :approve (drain-lane/approve! project ws-id (parse-long (str arg)))
             :land    (:slice (drain-land! project (str arg)))
             :close   (drain-lane/close! project ws-id))}
      (catch clojure.lang.ExceptionInfo e
        {:refused (ex-message e)}))))
