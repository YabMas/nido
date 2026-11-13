(ns nido.ui.components.state
  "A workstream's state as a reader sees it: the words each facet is said in, and the mark that
   says it.

   One table per facet — the position, the next stage it owes, who owes it, the arc stage and the
   mark for an arc stage's record state — and every surface that says a facet asks here rather
   than wording it. That is what stops two pages describing one workstream two ways, and it is
   why the tables are private: what this publishes is the mark, never the word.

   Reads nothing. Each function takes its facet's own value, and the one call out of this level
   is `wsv/doing-label` — the single wording of what a workstream is doing, a pure function of
   the value it is given and shared with the TUI. It is a call rather than an argument for
   exactly that reason: a caller handed the wording could word it differently, and then the
   board and the pane would disagree about one workstream."
  (:require [clojure.string :as str]
            [nido.coordinator.view.workstreams :as wsv]))

;; ---------------------------------------------------------------------------
;; Where it came from, and which band it sits in

(defn ^{:malli/schema [:=> [:cat :keyword] :any]}
  origin-badge [origin]
  (let [[ch cls] (case origin
                   :notion ["N" "b-notion"] :github ["G" "b-github"]
                   :slack ["S" "b-slack"]   ["·" "b-scratch"])]
    [:span {:class (str "badge " cls)} ch]))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  stage-chip
  "A board stage as a chip. The band a row is grouped under is the board's own word rather than
   one of the workstream's own facets, which is why it is not held to a table the way a position
   is: there is nothing else that says it."
  [stage]
  [:span {:class (str "chip c-" (name stage))} (name stage)])

;; ---------------------------------------------------------------------------
;; Where the pipeline says it is

(def ^:private position-label
  "What each position reads as to a person. The keyword is the value's, this is
   the reader's — a pane that printed `:premise-retracted` would be leaking a
   vocabulary at somebody who only wants to know what is going on."
  {:intake            "Not started"
   :intent-stated     "Intent stated"
   :baselined          "Baseline written"
   :baseline-verified "Baseline verified"
   :designed          "Design written"
   :design-decided    "Decision made"
   :design-cleared    "Cleared"
   :design-approved   "Approved"
   :implemented       "Implemented"
   :implementing      "Implementing"
   :triage-awaiting   "Triage to accept"
   :reviewed          "Reviewed"
   :awaiting-gate     "Awaiting gate"
   :published         "Draft PR open"
   :shipped           "Merged"
   :findings-open     "Findings open"
   :blocked           "Blocked"
   :premise-retracted "Premise retracted"
   :design-retracted  "Design retracted"
   :intent-retracted  "Intent retracted"
   :design-invalidated "Design invalidated"
   :goal-superseded   "Goal moved"
   :unplaceable       "Cannot place"})

(def ^:private stage-label
  {:establish-intent      "establish intent"
   :write-baseline        "write the baseline"
   :verify-baseline       "verify the baseline"
   :design                "write the design"
   :decide-design         "decide on it"
   :approve-design        "your approval"
   :implement             "implement it"
   :review-implementation "review the diff"
   :publish-draft-pr      "open the draft PR"
   :rebaseline            "re-do the baseline"
   :address-findings      "address findings"
   :answer-blocker        "your answer"
   :assert-gate           "your gate"
   :land                  "your landing"
   :accept-triage         "your triage call"
   :acknowledge-invalidation "your call on the verdict"})

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:? :any]] :any]}
  position-chip
  "Where the pipeline says this row is, and what would move it — the one thing a
   board row could not say before.

   The band alone could not say it. Everything between authoring an intent and
   opening a draft PR is :in-progress, so forty rows read identically while one
   waited on a baseline, one on a decision and one on a person. The chip is muted
   by default and lit only when the next move is a HUMAN's, because that is the
   distinction a board is scanned for.

   Renders nothing for a row with no position — a bare watched page, or a band
   the projection is not computed for."
  ([position] (position-chip position nil))
  ([{:keys [at next] :as position} tip]
   (when position
     (let [human? (= :human (:mode next))]
       [:span (cond-> {:class (str "pos" (when human? " pos-you"))}
                (or next tip)
                (assoc :title (str/join " · " (remove nil? [(when next (str "next: " (get stage-label (:stage next) (name (:stage next)))))
                                                            tip]))))
        [:span.pos-at (get position-label at (name at))
         (when-let [{:keys [done of]} (:layers position)] (str " " done "/" of))]]))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :any]}
  phase-chip
  "Where a phased workstream is in its plan — `phase 2/3` — or nothing for an
   unphased one. Beside the position chip rather than inside it: the position is
   where this landing's work stands, the phase is which landing it is."
  [{:keys [current of]}]
  (when current
    [:span.phase-chip {:title "phase of the design's plan"} (str "phase " current "/" of)]))

;; ---------------------------------------------------------------------------
;; Whether anything is happening in it

(def ^:private owed-label
  {:person "you" :agent "agent" :machine "nido" :nobody "nobody"})

(defn- doing-badge
  "What a workstream is doing right now — nothing when nothing is. The ONE
   rendering of current activity, so a shipping row says `merging · driving`
   once rather than in two vocabularies.

   Muted text, not a filled badge: the row's emphasis belongs to the position
   chip lighting when the next move is a human's, and a lit slab here outshouts
   it on rows that need nobody. The exception is a merge phase's severity class,
   so a stuck merge still reads red on a board that is scanned rather than read.

   It is often the last span of a `.gate-sub`, which flexes to fill the line —
   a background here would paint that whole stretch."
  [doing]
  (when-let [d (wsv/doing-label doing)]
    [:span {:class (str "doing-badge"
                        (when-let [phase (and (= :merge (:source doing)) (:phase doing))]
                          (str " ship-" (name phase))))}
     d]))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:maybe :map]] :any]}
  state-mark
  "Whether anything is happening in a workstream, as one mark and no words: a dot in the running
   colour while something is live; ‖ in the dormant colour while a person's pause stands; ⚠ in
   the failed colour when it is stalled — an agent owes the move, nothing runs, no question was
   recorded and nobody paused it, which is a fault and never a resting state; nothing otherwise
   (a workstream waiting on a person says so by its lit position chip). What runs, and who owes
   the next move, are the mark's title. A merge keeps its words (`doing-badge`), because a
   blocked merge has to read red on a board that is scanned rather than read."
  [{:keys [owed-by live stalled paused]} doing]
  (let [what (wsv/doing-label doing)
        tip  (str/join " · " (remove nil? [what (when owed-by (str "owed by " (owed-label owed-by (name owed-by))))]))]
    (cond
      (= :merge (:source doing)) (doing-badge doing)
      paused                     [:span.st-mark.paused {:title "paused by you"} "‖"]
      stalled                    [:span.st-mark.stalled {:title (str "stalled — nothing runs and nothing asks you"
                                                                     (when (seq tip) (str " · " tip)))} "⚠"]
      (or live what)             [:span.st-mark.live {:title (if (seq tip) tip "running")} "●"])))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :string]]}
  round-label
  "The words beside the running stage row: where a review round has got (`design round 3 ·
   judging · 0 findings`), and nothing for any other activity. Which agent runs and on what is
   detail the person does not scan a workstream for — the running mark already says something
   runs — so it stays in the state mark's title."
  [doing]
  (when (= :claim (:source doing)) (wsv/doing-label doing)))

;; ---------------------------------------------------------------------------
;; The status section's heading

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  status-heading
  "The Status section's heading, carrying the position itself.

   The position was a bold word at the top of the section body, immediately under
   a heading that said nothing. Two lines to say one thing, and the heading was
   the emptier of them — so the position moved into it and the body kept only
   what comes next."
  [{:keys [at]}]
  (if at
    (str "Status — " (get position-label at (name at)))
    "Status"))

;; ---------------------------------------------------------------------------
;; The arc

(def ^:private arc-label
  "Arc stage -> its heading. A separate vocabulary from `stage-label`, which names
   the NEXT ACTION (`:verify-baseline`, `:decide-design`) rather than the place a
   record belongs to. They read alike and answer different questions, so they are
   kept apart rather than merged into a table that would have to mean both."
  {:intent         "Intent"
   :baseline       "Baseline"
   :design         "Design"
   :implementation "Implementation"})

(def ^:private arc-glyph
  "Stage state -> its mark. Five marks because there are five states, and no two
   of them mean the same thing: `⊘` is a stage the work went past without writing
   one, `·` is one it has not reached, and `↺` is one it wrote records for that
   the ledger no longer stands behind — owed again rather than behind you, which
   is the distinction a ✓ there would destroy."
  {:done "✓" :current "●" :stale "↺" :skipped "⊘" :ahead "·"})

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  arc-name
  "One arc stage's heading."
  [stage]
  (get arc-label stage (name stage)))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  arc-mark
  "The mark for one arc stage: what is happening there right now if anything is — a person being
   waited on, or something running — and otherwise what the stage's records amount to. `now`
   outranks `state` because a stage waiting on a person is the one fact a reader scans an arc
   for."
  [state now]
  (case now
    :awaiting [:span.arc-wait {:title "waiting on you"} "◆"]
    :running  [:span.arc-spin]
    (get arc-glyph state "·")))
