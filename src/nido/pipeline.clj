;; src/nido/pipeline.clj
(ns nido.pipeline
  "Where a workstream is in its life, and what should happen to it next.

   Derived on every read and stored nowhere — the same contract
   `nido.coordinator.standing` holds, for the same reason. A stored position is
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
   [nido.coordinator.session :as csession]
   [nido.coordinator.standing :as standing]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as ws]
   [nido.coordinator.workstreams-view :as wsv]))

;; ── The vocabulary ──────────────────────────────────────────────────────────

(def positions
  "Every position a workstream can be at, in the order `position` tests them.

   Closed, and ordered by PRECEDENCE rather than by progress: the first six are
   halts and terminals that outrank whatever the record trail would otherwise
   say. A workstream whose survey was retracted is at :premise-retracted even
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
   :survey-verified
   :surveyed
   :intent-stated
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

(defn intake-kind
  "How this workstream came to exist, which is what decides how its intent gets
   established.

   Split out from `position` because the two questions are orthogonal and were
   collapsed. The routing table's first rows ask this one — a bare pickup, a
   triaged Notion ticket, a Slack proposal each establish intent differently —
   and its later rows ask where to resume. Reading a resume point told you
   nothing about the first, which is why the table needed a row per combination.

   :triaged   a :triage entry states the goal, and a design may cite it directly
   :proposal  a Slack proposal, scoped in twenty minutes by an agent that had
              not surveyed — a proposal, never a decision
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
    :review :review-analysis
    :blocker :blocker-answered :retraction
    :findings :pr-opened :ship-submitted :merged})

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
                         (ws/entries-of project ws-id :blocker-answered))]
      (->> (ws/entries-of project ws-id :blocker)
           (remove #(contains? answered (:seq %)))
           last
           :seq))))

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

(defn survey-verified?
  "True when a review found the workstream's newest survey sufficient.

   Public because the surface asks it too, and there must be one answer to it.
   A second implementation beside this one is how `verified` on a card and
   `:survey-verified` in the fold come to disagree about the same ledger.

   Keyed on the survey's :seq, never on recency: a workstream can hold several
   surveys and several reviews of them, and the review that matters is the one
   naming the survey you are standing on."
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
    verified?                      :survey-verified
    (contains? ks :baseline)       :surveyed
    (or (contains? ks :intent)
        (contains? ks :triage))    :intent-stated

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
   :intent-stated     {:stage :survey                :mode :authoring}
   :surveyed          {:stage :verify-survey         :mode :mechanical}
   :survey-verified   {:stage :design                :mode :authoring}
   :designed          {:stage :decide-design         :mode :mechanical}
   :design-decided    {:stage :approve-design        :mode :human}
   :design-approved   {:stage :implement             :mode :working-copy}
   :implemented       {:stage :review-implementation :mode :mechanical}
   :reviewed          {:stage :publish-draft-pr      :mode :working-copy}
   :premise-retracted {:stage :resurvey              :mode :authoring}
   :findings-open     {:stage :address-findings      :mode :working-copy}
   ;; The next phase is an implementation, and which one is read off the design's
   ;; :phases against how many :merged entries the ledger holds — a count, not a
   ;; guess. Naming the stage is this module's job; picking the phase is the work
   ;; of the turn that runs it.
   :phase-landed      {:stage :implement             :mode :working-copy}
   :blocked           {:stage :answer-blocker        :mode :human}
   :published         nil
   :shipped           nil
   :unplaceable       nil})

(defn next-action
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

;; ── The arc, at stage granularity ───────────────────────────────────────────

(def ^:private stage-of-kind
  "Which stage of the arc each entry kind belongs to.

   The same correspondence `place` folds over, read the other way round: `place`
   asks what the newest records make true NOW, this asks which stage a given
   record was part of. Both are the one secret this module keeps, so they live
   together — a second copy elsewhere would be a second answer to `is a
   baseline-review part of surveying`, and the two would drift.

   A kind absent here has no stage, and `history` drops it rather than inventing
   one. That is the same refusal `:unplaceable` makes, at row granularity."
  {:ticket          :intent
   :triage          :intent
   :proposed-ticket :intent
   :intent          :intent
   :baseline        :survey
   :baseline-review :survey
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

(defn stage-of
  "The arc stage an entry of `kind` belongs to, or nil for one this vocabulary
   does not place."
  [kind]
  (get stage-of-kind kind))

(defn history
  "The workstream's arc, one row per stage rather than one per entry.

   This is what makes a ledger readable. A baseline loop appends one review and
   one superseding survey PER ROUND, so a survey that converged in four rounds is
   eight rows of log — and read as a log, the eight say nothing the one says
   better. Collapsed, it is `survey · 4 revisions · verified`, and the eight
   entries are still underneath for anyone who wants them.

   Consecutive entries of one stage collapse into a single row; a stage RE-ENTERED
   later opens a new row rather than merging with the earlier one, because
   returning to a stage is the most interesting thing a ledger can record and
   merging the two would erase the return. That is why this folds over the entries
   in order instead of grouping by stage."
  [project ws-id]
  (when-let [w (ws/read-ws project ws-id)]
    (->> (:entries w)
         (keep (fn [e] (when-let [s (stage-of (:kind e))] (assoc e :stage s))))
         (reduce (fn [rows {:keys [stage seq at kind]}]
                   (let [last-row (peek rows)]
                     (if (= stage (:stage last-row))
                       (conj (pop rows)
                             (-> last-row
                                 (assoc :to seq :at at)
                                 (update :entries conj kind)))
                       (conj rows {:stage stage :from seq :to seq :at at
                                   :entries [kind]}))))
                 [])
         vec)))

(defn of
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
                         :verified?      (survey-verified? project ws-id)})
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
