;; src/nido/pipeline.clj
(ns nido.coordinator.lane.pipeline
  "Where a workstream is in its life, and what should happen to it next.

   Derived on every read and stored nowhere — the same contract
   `nido.coordinator.record.standing` holds, for the same reason. A stored position is
   an index over a ledger three separate processes append to, and an index that
   drifts is a failure this project has already paid for more than once. There is
   no field here to keep in step, so there is nothing to fall out of step.

   This is NOT a fifth position vocabulary. Four already exist and each keeps its
   job: the board's lifecycle stage, a Run's state, a ticket's status, a session's
   autonomy phase. What was missing is not a better one but a value saying how
   they correspond, and how the ledger's own records relate to all four. So every
   answer carries `:read` — the sources it was derived from — and a reader can see
   which of them spoke rather than having to guess.

   Two questions live here and they are deliberately one secret. Where a
   workstream IS is a fold over its ledger; what happens NEXT is that position
   paired with how the work arrived. Those were one nine-row table in
   continue-ticket/SKILL.md, read by a language model at session start, which is
   the whole reason nothing could act on it: a table is not a value.

   What this does NOT do is decide anything `standing` decides. Whether a design
   is live, whether its premise was verified, whether a human granted it — those
   are asked of standing here exactly as the landing gate and the approval action
   ask them, so there is one answer to that question in the system and not two."
  (:require
   [clojure.string :as str]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.view.workstreams :as wsv]))

;; ── The vocabulary ──────────────────────────────────────────────────────────

(def positions
  "Every position a workstream can be at, in the order `position` tests them.

   Closed, and ordered by PRECEDENCE rather than by progress: the first six are
   halts and terminals that outrank whatever the record trail would otherwise
   say. A workstream whose baseline was retracted is at :premise-retracted even
   though it also holds a design — the retraction is the fact that matters, and
   reporting it as :designed would send the driver forward over a premise
   somebody found untrue.

   :unplaceable is not a position the work reaches; it is this module refusing.
   A ledger it cannot read, or a shape it does not recognise, is reported as
   such and left for a human — never advanced on a default. That refusal is an
   invariant of the design this implements, and it is why the vocabulary needs
   a name for `I do not know`."
  [:shipped
   :findings-open
   :blocked
   :premise-retracted
   :phase-landed
   :published
   :reviewed
   :implemented
   :design-approved
   :design-decided
   :designed
   :baseline-verified
   :baselined
   :intent-stated
   :analysed
   :intake
   :unplaceable])

(def modes
  "How a stage reaches the session home, and therefore how the driver runs it.

   :mechanical    a task the driver invokes — the record loops, squash, align,
                  the landing check. No agent turn of the driver's own.
   :authoring     a bounded turn that writes RECORDS and may not touch the
                  working copy — the constraint both pre-implementation rounds
                  already enforce by running read-only.
   :working-copy  a bounded turn that may change the tree. Implementation and
                  the draft-PR publication, and nothing else.
   :human         not a stage at all: the next move is a person's. Named rather
                  than left nil so that `nothing can be fired here` and `nothing
                  remains to be done` stay different answers — a terminal
                  position carries no next action, and this carries one nobody
                  but a human can take."
  #{:mechanical :authoring :working-copy :human})

;; ── How the work arrived ────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :Workstream :any] [:maybe :keyword]]}
  intake-kind
  "How this workstream came to exist, which is what decides how its intent gets
   established.

   Split out from `position` because the two questions are orthogonal and were
   collapsed. The routing table's first rows ask this one — a bare pickup, a
   triaged Notion ticket, a Slack proposal each establish intent differently —
   and its later rows ask where to resume. Reading a resume point told you
   nothing about the first, which is why the table needed a row per combination.

   :triaged   a :triage entry states the goal, and a design may cite it directly
   :proposal  a Slack proposal, scoped in twenty minutes by an agent that had
              not baselined — a proposal, never a decision
   :issue     a GitHub issue, whose body is the brief
   :pickup    handed to nido directly, bypassing triage on purpose: the ticket
              body is the only statement of scope anywhere
   :scratch   a one-off with no external ref at all"
  [w kinds]
  (cond
    (= :scratch (:stage w))     :scratch
    (contains? kinds :triage)   :triaged
    (contains? kinds :proposed-ticket) :proposal
    (= :github (wsv/ws-source w)) :issue
    :else                       :pickup))

;; ── Reading the ledger ──────────────────────────────────────────────────────

(defn- kinds
  "The set of entry kinds this ledger holds. Read from the INDEX rather than by
   parsing every entry: the index is what `append-entry!` maintains, and a
   presence question does not need a payload."
  [w]
  (into #{} (map :kind) (:entries w)))

(def ^:private legible-kinds
  "Every entry kind this vocabulary knows how to read — whether it carries a
   position itself, evidences how the work arrived, or records a round taken
   over one of those.

   Its job is to tell an EMPTY ledger from an ILLEGIBLE one, which the fold
   cannot otherwise do: both fall past every clause, and answering `:intake` for
   both means telling a workstream that has already been implemented to go and
   establish its intent. That is advancing on a default, which is the one thing
   the refusal invariant forbids.

   Live ledgers hold kinds from before this vocabulary — `:impl`, `:decision`,
   `:resolution` — stored as verbatim markdown because they are absent from
   report/event-schemas. Almost all of them are on closed workstreams and place
   as :shipped from `:closed` alone, never reaching here. One open workstream
   does not, and it is the reason this set exists rather than a hypothetical."
  #{:ticket :triage :proposed-ticket :intent
    :baseline :baseline-review
    :design :design-decision :design-verdict :design-approved
    :implementation-plan :implementation-completed
    :review :review-analysis :improvement-decision :improvement-landed
    :blocker :blocker-answered :retraction
    :findings :pr-opened :ship-submitted :merged})

(def ^:private stage-of-kind
  "Which stage of the arc each entry kind belongs to.

   The same correspondence `place` folds over, read the other way round: `place`
   asks what the newest records make true NOW, this asks which stage a given
   record was part of. Both are the one secret this module keeps, so they live
   together — a second copy elsewhere would be a second answer to `is a
   baseline-review part of the baseline stage`, and the two would drift.

   A kind absent here has no stage, and `arc` drops it rather than inventing one.
   That is the same refusal `:unplaceable` makes, at row granularity."
  {:ticket          :intent
   :triage          :intent
   :proposed-ticket :intent
   :intent          :intent
   :baseline        :baseline
   :baseline-review :baseline
   :design          :design
   :design-decision :design
   :design-verdict  :design
   :design-approved :approval
   :retraction      :retraction
   :blocker         :halt
   :blocker-answered :halt
   :implementation-plan      :implementation
   :implementation-completed :implementation
   :review          :review
   :review-analysis :review
   :findings        :findings
   :pr-opened       :publication
   :ship-submitted  :shipping
   :merged          :shipping})

(defn ^{:malli/schema [:=> [:cat :keyword] [:maybe :keyword]]}
  stage-of
  "The arc stage an entry of `kind` belongs to, or nil for one this vocabulary
   does not place.

   Two callers, asking it for different reasons. `arc` groups a ledger by stage so
   a reader sees the trail at the granularity work actually moves in.
   `unanswered-blocker` asks whether anything since a halt was a stage rather than
   another halt, which is how it decides that work moved on without the gate ever
   being clicked.

   An earlier single-line rendering of the arc was removed for telling a reader
   less than the index beneath it. That was a fact about one line, not about the
   grouping: what failed was restating the position in different words, and what
   a stage carries — its records, its re-entries, whether it was skipped — is
   nowhere else on the page."
  [kind]
  (get stage-of-kind kind))

(def arc-stages
  "The stages of the arc, in the order a workstream travels them.

   The spine only — somewhere work arrives, does something and leaves a record
   behind. What is NOT here is in `off-arc`, and the distinction is the reason
   this is a vector rather than the key set of `stage-of-kind`: a halt is
   something that happens TO a workstream, not a place it got to, and a line that
   put it in sequence would say a blocked workstream had advanced to blocked."
  [:intent :baseline :design :approval :implementation :review :publication :shipping])

(def ^:private off-arc
  "Stages that interrupt the arc rather than lying on it. Reported beside it and
   never in it — see `arc-stages`."
  #{:halt :retraction :findings})

(defn ^{:malli/schema [:=> [:cat :any [:? :map]] :any]}
  arc
  "One workstream's ledger read as the arc it travelled:

     {:stages     [{:stage :entries :visits :state (:last-seq :last-at :seqs)} …]
      :excursions [{:stage :entries :last-seq :last-at :seqs} …]}

   Pure over the entry index the ledger already keeps — the same `{:kind :seq
   :at}` maps the pane lists one per row — so it reads nothing of its own and
   cannot come to disagree with the index it sits above. Entries of a kind this
   vocabulary does not place are dropped rather than bucketed somewhere.

   `:visits` is the field that earns this over a list. It counts how many times
   the workstream ENTERED a stage, not how many records the stage holds, and it
   is counted across the spine alone so that a halt in the middle of a design
   does not read as having left design and come back. A design its decision round
   sent back to the baseline shows two visits to baseline; nine designs and nine
   decisions inside one uninterrupted stretch of design show one. Ordering by
   sequence number cannot show either, and neither can anything shaped like a
   progress bar, because both describe a walk that only goes forward.

   `:state` is what a stage cell renders:

     :done     it holds records and is not where the trail ends
     :current  the newest record carrying any stage belongs to it
     :skipped  it holds no record and the trail is already past it
     :ahead    it holds no record and the trail has not reached it

   :skipped is neither a defect nor an error — a workstream can reach a draft PR
   having never written an implementation-plan record — but it is a fact a reader
   should see, and folding it into :ahead would claim a stage is still owed when
   the work went past it. When the trail ends on an excursion no spine stage is
   current, and nothing is skipped: what a blocked workstream was in the middle of
   is a question the position answers, not one to guess at from a record order.

   `closed?` is the one fact the arc cannot read off the ledger and cannot do
   without. Closure lives on the workstream record, so a merged workstream whose
   last entry was a design leaves the record trail ending mid-arc — and the arc
   would then mark every later stage as one the work has not reached, on a
   workstream that has finished. The pane showed exactly that: `Status — Merged`
   above an arc claiming Shipping was still ahead. It is the same fact `place`
   reads to answer :shipped, taken from the same place, so the two cannot come
   apart."
  ([entries] (arc entries {}))
  ([entries {:keys [closed?]}]
  (let [staged  (keep (fn [e]
                        (when-let [st (stage-of (:kind e))]
                          (assoc e :stage st)))
                      entries)
        spine   (remove #(off-arc (:stage %)) staged)
        current (:stage (last staged))
        idx     (zipmap arc-stages (range))
        ;; nil — never false — when the trail ends off the arc, so `past?` cannot
        ;; report a stage as skipped on the strength of an excursion.
        past?   (fn [st] (when-let [c (idx current)]
                           (< (idx st) c)))
        visits  (frequencies (map first (partition-by identity (map :stage spine))))
        held    (group-by :stage staged)
        facet   (fn [st es]
                  (cond-> {:stage st :entries (count es) :visits (get visits st 0)}
                    (seq es) (assoc :last-seq (:seq (last es))
                                    :last-at  (:at (last es))
                                    :seqs     (mapv :seq es))))]
    {:stages (mapv (fn [st]
                     (let [es (get held st)]
                       (assoc (facet st es)
                              ;; A closed workstream has no current stage and
                              ;; nothing still ahead of it: it is over, whatever
                              ;; the last record happened to be about.
                              :state (cond (seq es)                     (if (and (= st current)
                                                                                 (not closed?))
                                                                          :current :done)
                                           (or closed? (past? st))      :skipped
                                           :else                        :ahead))))
                   arc-stages)
     :excursions (->> (filter #(off-arc (:stage %)) staged)
                      (group-by :stage)
                      (mapv (fn [[st es]] (facet st es)))
                      (sort-by :last-seq)
                      vec)})))

(defn- open-findings?
  "True when a findings round left items nobody has resolved.

   Findings live on the workstream record rather than only in the ledger, so
   this reads the tracker the board's badge and the round's completeness both
   read — not a fifth copy of the same fact."
  [w]
  (boolean (seq (remove :resolved-by (vals (:items (:findings w)))))))

(defn- unanswered-blocker
  "The :seq of a blocker nothing has answered, or nil.

   A blocker is answered by a later :blocker-answered naming it. Comparing seqs
   rather than counting entries, because a workstream can halt more than once
   and the second halt is not answered by the first answer."
  [project ws-id w]
  (when (contains? (kinds w) :blocker)
    ;; :blocker-seq, flat — an answer names the position it answers, and does not
    ;; nest it the way a design nests its :baseline. Reading it as nested matched
    ;; nothing, so every answered blocker stayed a halt forever.
    (let [answered (into #{} (keep :blocker-seq)
                         (ws/entries-of project ws-id :blocker-answered))
          latest   (->> (ws/entries-of project ws-id :blocker)
                        (remove #(contains? answered (:seq %)))
                        last)]
      (when latest
        ;; …and nothing since it shows the work went on anyway.
        ;;
        ;; A :blocker-answered is written when somebody clicks the gate button,
        ;; and that is not the only way a halt gets answered. Far more often the
        ;; question is settled in the session chat and the work simply continues,
        ;; leaving a blocker nobody ever formally closed. Requiring the record
        ;; made every such workstream permanently :blocked — BR-5099 was halted
        ;; on 2026-08-21 and then baselined, designed, reviewed clean and had
        ;; three PRs opened on 2026-08-26, and still read as waiting on a human.
        ;;
        ;; So the ledger answers it: a stage record appended AFTER the halt is
        ;; the work having moved past it, which is the same evidence a person
        ;; reading the timeline would use. Halts themselves do not count — a
        ;; later blocker is a new question, not an answer to the old one, and it
        ;; is already the one this returns.
        (when-not (some (fn [e]
                          (and (> (:seq e) (:seq latest))
                               (when-let [st (stage-of (:kind e))]
                                 (not= :halt st))))
                        (:entries w))
          (:seq latest))))))

(defn- live-retraction
  "The :seq of a retraction whose subject still stands unrepaired, or nil.

   A retraction is repaired by a later record superseding what it retracted —
   which is exactly the walk `standing` already does for a design's premise. So
   the question asked here is narrow: is there a retraction the ledger has not
   moved past? Anything finer is standing's business and is asked of standing."
  [project ws-id w]
  (when (contains? (kinds w) :retraction)
    (let [rs        (ws/entries-of project ws-id :retraction)
          latest    (last rs)
          retracted (:seq (:retracts latest))]
      (when (and retracted
                 ;; Repaired when something was appended AFTER the retraction
                 ;; that supersedes the entry it named. Nothing later at all
                 ;; means nobody has answered it yet.
                 (not (some #(= retracted (:seq (:supersedes %)))
                            (concat (ws/entries-of project ws-id :baseline)
                                    (ws/entries-of project ws-id :design)))))
        (:seq latest)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :boolean]}
  baseline-verified?
  "True when a review found the workstream's newest baseline sufficient.

   Public because the surface asks it too, and there must be one answer to it.
   A second implementation beside this one is how `verified` on a card and
   `:baseline-verified` in the fold come to disagree about the same ledger.

   Keyed on the baseline's :seq, never on recency: a workstream can hold several
   baselines and several reviews of them, and the review that matters is the one
   naming the baseline you are standing on."
  [project ws-id]
  (when-let [b (ws/latest-entry project ws-id :baseline)]
    (boolean (some #(and (= (:seq b) (:baseline-seq %))
                         (contains? #{:sufficient :accurate} (:verdict %)))
                   (ws/entries-of project ws-id :baseline-review)))))

(defn- design-decided?
  "True when a decision round recommended proceeding on the newest design.

   Only :proceed counts. A round that answered :recut or :amend reached a
   judgement about the record, not about whether to build it, and treating one
   as a decision would advance a design its own round sent back."
  [project ws-id]
  (when-let [d (ws/latest-entry project ws-id :design)]
    (boolean (some #(and (= (:seq d) (:design-seq %))
                         (= :proceed (:recommend %)))
                   (ws/entries-of project ws-id :design-decision)))))

;; ── The fold ────────────────────────────────────────────────────────────────

(defn- place
  "The position, given everything already read. Pure — every argument is a fact
   the caller gathered, so the precedence order is readable in one screen and
   testable without a ledger on disk.

   The order is the claim. Halts and terminals first, because they outrank the
   record trail underneath them; then implementation backwards from published;
   then the record arc. Each clause names a fact that is true of the ledger, so
   a position is always answerable by pointing at an entry."
  [{:keys [closed? findings-open? blocker-seq retraction-seq ks decided? approved?
           verified?]}]
  (cond
    ;; :closed is the authority on `done`, and a :merged entry is NOT. They come
    ;; apart on exactly the case a phase plan creates: reopen! clears :closed for
    ;; the next landing while every :merged entry stays in the ledger forever, so
    ;; reading the entry as terminal would strand a phased workstream at :shipped
    ;; after its first phase and leave the rest of the plan unreachable.
    closed?                        :shipped
    findings-open?                 :findings-open
    blocker-seq                    :blocked
    retraction-seq                 :premise-retracted
    ;; Merged and open again: a landing completed and somebody reopened it. That
    ;; is the phase plan working, and the next act is the next phase — which is
    ;; why this outranks :published, whose :pr-opened entry belongs to the
    ;; landing that just finished.
    (contains? ks :merged)         :phase-landed
    (contains? ks :pr-opened)      :published
    (contains? ks :review)         :reviewed
    (contains? ks :implementation-completed) :implemented
    approved?                      :design-approved
    decided?                       :design-decided
    (contains? ks :design)         :designed
    verified?                      :baseline-verified
    (contains? ks :baseline)       :baselined
    (or (contains? ks :intent)
        (contains? ks :triage))    :intent-stated

    ;; A workstream that exists to HOLD a reading, not to do work. The review
    ;; loop mints one per analysed run and files one analysis into it; decisions
    ;; about the proposals in that analysis accumulate beside it. There is no
    ;; intent to establish and no arc to advance, so every clause above is
    ;; silent and the :else below would answer :intake — which is what put ten
    ;; of these on the board asking to be triaged. Placed after the record arc
    ;; rather than before it so that a workstream which somehow grew a real arc
    ;; is read by that arc, not by how it started.
    (and (contains? ks :review-analysis)
         (empty? (disj (set (filter legible-kinds ks))
                       :review-analysis :improvement-decision :improvement-landed)))
    :analysed

    ;; An empty ledger IS :intake — that is a pickup, and its own row in the
    ;; routing table. A ledger with entries none of which this vocabulary reads
    ;; is a different fact and must not collapse into the same answer.
    (and (seq ks) (empty? (filter legible-kinds ks)))
    :unplaceable

    :else                          :intake))

(def ^:private next-by-position
  "What each position hands to next: the stage, and the mode that stage runs in.

   A stage's mode is fixed by its position, which is why naming it here asks for
   no derivation the projection was not already making — and why the mode, not
   the disposition, is what the driver stamps onto a Run. Several stages run in
   :mechanical mode and all of them follow an :advance, so a disposition could
   never select one.

   The two terminal positions map to nil, and that is the answer rather than a
   gap: a published draft PR ends this arc — landing it is `nido ship` and stays
   a human's gesture — and a merged workstream is done. Distinguish that from
   :human, which is a next action nobody but a person can take."
  {:intake            {:stage :establish-intent      :mode :authoring}
   :intent-stated     {:stage :write-baseline        :mode :authoring}
   :baselined          {:stage :verify-baseline         :mode :mechanical}
   :baseline-verified   {:stage :design                :mode :authoring}
   :designed          {:stage :decide-design         :mode :mechanical}
   :design-decided    {:stage :approve-design        :mode :human}
   :design-approved   {:stage :implement             :mode :working-copy}
   :implemented       {:stage :review-implementation :mode :mechanical}
   :reviewed          {:stage :publish-draft-pr      :mode :working-copy}
   :premise-retracted {:stage :rebaseline              :mode :authoring}
   :findings-open     {:stage :address-findings      :mode :working-copy}
   ;; The next phase is an implementation, and which one is read off the design's
   ;; :phases against how many :merged entries the ledger holds — a count, not a
   ;; guess. Naming the stage is this module's job; picking the phase is the work
   ;; of the turn that runs it.
   :phase-landed      {:stage :implement             :mode :working-copy}
   :blocked           {:stage :answer-blocker        :mode :human}
   :published         nil
   :shipped           nil
   ;; Terminal, and not for want of a stage: the analysis is complete when it is
   ;; filed. What happens to its proposals is a decision, which is a human's and
   ;; has no stage to run.
   :analysed          nil
   :unplaceable       nil})

(defn ^{:malli/schema [:=> [:cat :any :keyword] [:maybe :map]]}
  next-action
  "The stage to run next and the mode it runs in, or nil at a terminal position.

   `intake-kind` changes only the first step, and only its shape rather than its
   name: establishing intent from a triage entry, a Slack proposal, an issue body
   or a bare ticket are four different readings of the same stage. The stage is
   therefore the same value and the kind rides beside it, so a driver dispatches
   on one thing and the authoring turn is told which reading to make."
  [position kind]
  (when-let [a (get next-by-position position)]
    (cond-> a
      (= :establish-intent (:stage a)) (assoc :from kind))))

;; ── What a finished stage means ─────────────────────────────────────────────

(def dispositions
  "What a driver may do about a stage that has finished.

   :advance     the stage did its job — take the next position
   :retry       the machinery failed, not the work; the same stage again
   :route-back  an EARLIER record is at fault, and that stage is where to go
   :escalate    a human has to decide; nothing further is derivable

   Four, and the classification into them must be total: this is the join that
   turns a terminal status into a cause. Before it there was exactly one place in
   the tree where a status became something that happened next — analysis.clj
   enqueuing an envelope — and everywhere else a loop reported to a human's
   terminal and stopped."
  #{:advance :retry :route-back :escalate})

(def ^:private disposition-of-status
  "Every terminal a stage can end on, and what it means for the workstream.

   The set is not invented here. It is what the loops actually produce: the diff
   engine's statuses, the record pipelines', and the round outcomes that become
   statuses when a round could not run at all. Keeping them in ONE table is the
   point — a reader can see that :sufficient and :codex-failed are not the same
   kind of silence, which is the distinction `outcome-tagged` exists to preserve
   and which a driver would otherwise have to re-derive at every call site."
  {;; ── the work succeeded ──
   :sufficient           :advance   ; the baseline holds; a design may be decided against it
   :clean                :advance   ; the diff review found nothing
   :converged            :advance   ; it found things and they were all fixed
   :not-worth-running    :advance   ; the records say this round would not pay — nothing to do

   ;; ── the machinery failed, not the work ──
   :codex-failed         :retry
   :no-output            :retry
   :round-crashed        :retry
   :unusable-answer      :retry
   :review-failed        :retry

   ;; ── an earlier record is at fault, and it is nameable ──
   :premise-unverified   :route-back  ; go verify the baseline this design cites
   :premise-retracted    :route-back
   :design-retracted     :route-back
   :no-premise           :route-back  ; the design cites no baseline at all
   :nothing-to-check     :route-back  ; the baseline recorded nothing checkable; it is too thin
   ;; The review's exact analogue: every diff was empty, so no reviewer read a
   ;; line. Not :advance — advancing would spend the loop's clean bill on a
   ;; review that never happened, which is the one reading the status exists to
   ;; prevent.
   :nothing-to-review    :route-back
   ;; The diff warden's escalate: a finding CONTRADICTS a named invariant, so the
   ;; design is in question rather than its execution. That derivation was being
   ;; made every round already and consumed by nothing — this is the wire.
   :escalated            :route-back

   ;; ── only a human can settle it ──
   :proceed              :escalate  ; the design round's ask; the whole point of it
   :disputed             :escalate  ; judge and amender deadlocked
   :underivable          :escalate  ; no yardstick to derive against
   :unfixable            :escalate  ; raised every round and never moved
   :no-progress          :escalate
   :unresolved           :escalate  ; the run ended still holding findings
   :retreated            :escalate  ; the record shrank below its own worth
   :amend-touched-code   :escalate  ; a record round wrote code; whatever it wrote is still there
   :amend-noop           :escalate
   :amend-invalid        :escalate
   :amend-unreadable     :escalate
   ;; Split from the single :fix-noop, which meant three different things. No
   ;; finding reached a fixable layer; and fixers ran and every one left the tree
   ;; alone. Both stop the run and both want a human, but they want different
   ;; things from one — the first is a routing question, the second is a fixer
   ;; saying no with a reason now recorded beside it.
   :fix-noop             :escalate
   :fix-unrouted         :escalate
   :fix-declined         :escalate
   ;; The fix stage's own rebase left the stack conflicted. A human has to
   ;; resolve it — nothing here can, and a retry would land more fixes onto a
   ;; branch that already does not parse.
   :fix-conflicted       :escalate
   ;; Somebody moved the working copy under a round in flight. Nothing is wrong
   ;; with the branch; the round just reviewed a state that stopped being
   ;; current, so the answer is to run it again, not to ask a human anything.
   :workspace-drifted    :retry
   :max-iters            :escalate  ; a cap somebody asked for was reached
   :warden-indeterminate :escalate
   :arbiter-indeterminate :escalate  ; pre-rename, still readable in old ledgers
   :judge-indeterminate  :escalate
   :no-record            :escalate  ; misconfigured: nothing of that kind to judge
   :no-workstream        :escalate
   :unreadable-ledger    :escalate  ; standing is indeterminate, so nothing may proceed
   ;; A driver never asks for one, so a dry run reaching this table means
   ;; something upstream is misconfigured — which is a human's to see, not a
   ;; thing to advance past.
   :dry-run              :escalate})

(defn ^{:malli/schema [:=> [:cat :keyword] :keyword]}
  disposition
  "What `status` means for the workstream it finished on.

   An unrecognised status is :escalate, and that is the fail-safe direction: a
   terminal nobody classified is exactly one a person should look at. The
   alternative — treating it as :advance — would step the pipeline forward on an
   outcome no one has understood."
  [status]
  (get disposition-of-status status :escalate))

;; ── The arc, at stage granularity ───────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  of
  "Where workstream `ws-id` is, and what should happen to it next.

   Returns {:at <position> :next {:stage :mode (:from)} :intake <kind>
            :read {…}} — or {:at :unplaceable :why …} when the ledger cannot be
   read at all.

   `:read` names the sources this answer was derived from, which is the point:
   the four position vocabularies keep their jobs and this relates them, so a
   reader must be able to see which of them spoke. It is diagnostic, never an
   input to anything downstream — a caller that branched on it would be making
   this a fifth vocabulary rather than the function that relates the four."
  [project ws-id]
  (if-let [w (ws/read-ws project ws-id)]
    (let [ks     (kinds w)
          br     (some #(when (= :notion (:adapter %)) (:id %)) (:external-refs w))
          design (ws/latest-entry project ws-id :design)
          st     (when design (standing/of-design project ws-id design))
          pos    (place {:closed?        (some? (:closed w))
                         :findings-open? (open-findings? w)
                         :blocker-seq    (unanswered-blocker project ws-id w)
                         :retraction-seq (live-retraction project ws-id w)
                         :ks             ks
                         ;; Approval is standing's answer, never re-derived
                         ;; here: it is a statement about NOW, joining the
                         ;; grant with any retraction since, and a second
                         ;; implementation of it is a second answer.
                         :approved?      (boolean (:decided? st))
                         :decided?       (design-decided? project ws-id)
                         :verified?      (baseline-verified? project ws-id)})
          kind   (intake-kind w ks)]
      (cond->
       {:at     pos
        :next   (next-action pos kind)
        :intake kind
        :read   {:ledger        (count (:entries w))
                :kinds         ks
                :board-stage   (:stage w)
                :ticket-status (when br (tickets/status project br))
                 :sessions      (mapv (juxt :name #(get-in % [:autonomy :phase]))
                                      (csession/list-sessions project ws-id))
                 :standing      (when st (select-keys st [:live? :decidable? :decided?]))}}

        (= :unplaceable pos)
        (assoc :why (str "this ledger's " (count (:entries w)) " entr"
                         (if (= 1 (count (:entries w))) "y is" "ies are")
                         " all of kinds the pipeline vocabulary does not read ("
                         (str/join ", " (sort (map name ks)))
                         ") — it predates this vocabulary, so where it stands is "
                         "a human's reading, not a derivation"))))
    {:at :unplaceable
     :why (str "no workstream " ws-id " under project " (name project))}))
