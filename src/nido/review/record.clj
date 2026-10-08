(ns nido.review.record
  "Judgment passes over a LEDGER RECORD rather than over a diff.

   The engine in nido.review.loop already runs a stage pipeline until terminal,
   and it names no stage — the pipeline is injected. So a round of a different
   kind is a different pipeline, not a different engine. What differs here is
   what is judged and against what:

     baseline round — VERIFICATION. Is this baseline true, and complete enough to
       decide against? Near-mechanical: every property and health observation
       carries file:line evidence, so checking one is 'go read it'.

     design round — DECISION. Given that baseline, the goals of the task and the
       work-distribution guidelines, should we execute on this? The only point in
       the lifecycle where 'don't build this' is cheap, and the only round that
       ends at a human.

   Three properties hold of both, and each is enforced rather than requested:

   1. NO WRITES. Both run through the diff review's reviewer, under its
      read-only posture — `codex exec -s read-only`, or claude restricted to
      reading (see `nido.review.claude`). A pre-implementation round producing
      edits would make it a fix loop, which is the thing the design round must
      not be.

   2. EVERY FINDING CITES. The schema requires :cites non-empty. A round with no
      diff to be wrong about will otherwise produce fluent, unfalsifiable
      findings forever.

   3. NO EVIDENCE, NO ROUND — for the baseline. `baseline-round-worth-running?`
      refuses a baseline that recorded nothing checkable — the same rule
      verdict-worth-running? already applies, for the same reason: paying an agent
      to conclude nothing makes the answer noise rather than signal. The decision
      round is never skipped: a design's declarations decide whether a person's
      grant is additionally owed, never whether the round runs.

   The design round is deliberately SINGLE-PASS. It emits a decision, not
   findings to iterate on, so it never reaches the engine's no-progress check —
   which identifies a finding by [:file :line-start :title] and could not judge
   one that carries neither."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [malli.core :as m]
   [malli.error :as me]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.report :as report]
   [nido.coordinator.report.model :as model]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.design.check :as design-check]
   [nido.platform.core :as core]
   [nido.review.codex :as codex]
   [nido.review.loop :as rloop]
   [nido.review.retreat :as retreat]
   [nido.review.settled :as settled]
   [nido.review.stages :as stages]
   [nido.review.tree :as tree]))

;; ── Whether a round is worth running ────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  baseline-round-worth-running?
  "A baseline is worth verifying when there is something checkable in it. That is
   any claim — a load-bearing property, or a claim of its model — or any health
   observation: each names what would refute it, so each is something the code can
   refute.

   A baseline with none is not a baseline that passed; it is one that recorded
   nothing to check, and a round over it could only produce prose."
  [baseline]
  (boolean (and baseline
                (or (seq (:load-bearing baseline))
                    (seq (get-in baseline [:model :claims]))
                    (seq (:health baseline))))))

(defn ^{:malli/schema [:=> [:cat [:maybe [:tuple :keyword :string]] :map] [:maybe :map]]}
  discover-intent
  "The intent the design CITED, projected to what a goal may contain. nil when
   the design cites none — a pre-intent record — which the prompt states rather
   than papering over.

   Only an :intent entry is a goal. The append boundary refuses a design whose
   :intent names any other kind, so a citation resolving to something else was
   never written through it, and it projects nothing.

   Never throws. The prompt is built as an argument to run-round!, so anything
   that throws here escapes the round's only catch and takes the task down
   instead of degrading."
  [[project ws-id] design]
  (try
    (when-let [n (get-in design [:intent :seq])]
      (when project
        (let [e (ws/entry-at-seq project ws-id n)]
          (when (= :intent (:format e))
            {:goal (:goal e) :done-when (:done-when e)}))))
    (catch Throwable _ nil)))

;; ── Prompt construction ─────────────────────────────────────────────────────

(defn- bullets [xs] (str/join "\n" (map #(str "- " %) xs)))

(defn- invariant-lines
  "Invariants, each with the moment it holds. A record written before phasing
   carries plain strings and means :always by them; a phased one carries the
   Invariant map.

   The distinction has to reach the round, because an :on-completion invariant is
   deliberately false for the whole middle of a plan. A judge shown it without the
   qualifier reads a designed intermediate state as a broken design and escalates
   a decision that was already made — which is the reason :holds exists at all."
  [invariants]
  (bullets (map (fn [i]
                  (if (string? i)
                    i
                    (str (:invariant i)
                         (case (:holds i)
                           :on-completion "  [holds ON COMPLETION — not yet true mid-plan, and NOT a finding]"
                           "  [holds always]"))))
                invariants)))

(defn- readings-lines
  [readings indent]
  (apply str (for [{:keys [lens verdict because]} readings]
               (str "\n" indent "read as " (namespace lens) "/" (name lens)
                    " = " (name verdict) " — " because))))

(defn ^{:malli/schema [:=> [:cat :map :map] :string]}
  settled-block
  "The subjects of `record` that `settled` names, in front of the judge as part of
   the record and not as checks.

   Shown at all because the record-level derivations are made against the whole
   record, and a judge cannot derive against what it was not shown. Shown WITHOUT
   the counterexample each claim carries, because that is what a judge is sent
   looking for. And shown without a word about why a subject is here: a judge told
   a subject was checked before, or that the rest are what changed, is judging a
   delta — the reason `judged-alone` exists. The bracketed id stays, so a finding
   about one can still name it."
  [settled record]
  (let [ids   (set (keys settled))
        pick  #(filter (comp ids :id) %)
        lines (concat
               (when (ids "shape") [(str "[shape] " (:shape record))])
               (for [{:keys [id module hides interface readings]} (pick (:modules record))]
                 (str "[" id "] " module "\n    hides:     " hides
                      "\n    interface: " interface (readings-lines readings "    ")))
               (when (ids "composition") [(str "[composition] " (:composition record))])
               (for [{:keys [id property readings]} (pick (:load-bearing record))]
                 (str "[" id "] " property (readings-lines readings "    ")))
               ;; A baseline in the shared model: its elements and claims, as the checks show
               ;; them less the counterexample and what checks them.
               (for [{:keys [id sort hides interface plays readings]} (pick (get-in record [:model :elements]))]
                 (str "[" id "] (" (name sort) ")"
                      (when (seq plays) (str "\n    played by: " (str/join ", " plays)))
                      (when hides (str "\n    hides:     " hides))
                      (when interface (str "\n    interface: " interface))
                      (readings-lines readings "    ")))
               (for [{:keys [id statement about readings]} (pick (get-in record [:model :claims]))]
                 (str "[" id "] " statement "\n    about:      " (str/join ", " about)
                      (readings-lines readings "    ")))
               (for [{:keys [id axis observation]} (pick (:health record))]
                 (str "[" id "] [" (name axis) "] " observation)))]
    (when (seq lines)
      (str "\nOUTSIDE THIS ROUND'S CHECKS. These are part of the record, and the four\n"
           "derivations are made against them as much as against anything else in this\n"
           "prompt, so read them. They are not what you are checking: do not go looking for\n"
           "counterexamples to them, and do not list them in confirmed. If making a\n"
           "derivation shows you one of them is false, report it like any other\n"
           "finding, naming its id.\n"
           (str/join "\n" (map #(str "- " %) lines)) "\n"))))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  disputes-block
  "What an earlier round's amender said back, put in front of the judge.

   This is the whole appeal channel. Without it a dispute is a note in a file
   nobody reads and the judge repeats itself forever; with it the judge has to
   do one of exactly two things, and both of them move.

   Never phrased as a correction. The amender is not an authority here — it may
   be the one that is wrong — so what crosses is its counter-evidence, and the
   judge is told to go look rather than to defer."
  [disputes]
  (when (seq disputes)
    (str "\nDISPUTED — an earlier round of yours produced these, and the pass that\n"
         "would have acted on them says they are wrong about the code. It is not an\n"
         "authority and may itself be mistaken. Go and look, then do ONE of:\n"
         "  - withdraw the finding, by not reporting it again; or\n"
         "  - report it again with evidence that answers the objection.\n"
         "Reporting it again unchanged is the one answer that helps nobody.\n\n"
         (str/join
          "\n\n"
          (map (fn [{:keys [claim because evidence]}]
                 (str "- you found: " claim "\n"
                      "  objection: " because
                      (when (seq evidence)
                        (str "\n  they cite: " (str/join ", " evidence)))))
               disputes))
         "\n")))

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  prior-findings-block
  "The last finding an earlier run made against each subject this round checks, put in front of
   the judge — `nido.review.settled/prior-findings`, less the subjects the round does not ask.

   A judge reading a record cold has reversed its own held/broken on text nobody touched, and the
   reversal was recorded as if nothing had been said before. Shown, a confirmation is judged
   against the finding it overturns. Shown as a finding and not as a verdict: it may be the one
   that was wrong, so the judge is told to look, not to defer. Whether what it found against was
   restated since — the subject, and for a relation-honest finding the id's place under :breaks —
   is said, and how it was is not — the judge still reads the record as it stands
   (`judged-alone`)."
  [prior]
  (when (seq prior)
    (str "\nFOUND AGAINST BEFORE — an earlier run's judge found against these subjects, and\n"
         "no judge has confirmed them past it since. That finding is not an authority and may\n"
         "have been wrong. But confirming one of them now overturns it, so confirm one only\n"
         "when what you read answers the finding, and cite what answers it in that id's\n"
         "evidence. If the finding still holds, report it again under the same id.\n\n"
         (str/join
          "\n\n"
          (for [[id {n :seq :keys [finding restated?]}] (sort-by key prior)]
            (str "- [" id "] found at entry " n
                 (if restated? " — what it found against has been restated since" " — what it found against reads the same now")
                 "\n  it found: " (:claim finding)
                 (when (seq (:cites finding)) (str "\n  citing: " (str/join ", " (:cites finding))))
                 (when (seq (:evidence finding)) (str "\n  evidence: " (str/join ", " (:evidence finding)))))))
         "\n")))

(defn ^{:malli/schema [:=> [:cat] :string]}
  lens-block
  "The perspectives in play, with their verdicts and where each comes from.

   Put in front of the judge rather than assumed, because a verdict is only
   checkable if both sides are reading it the same way: `accidental` in Out of
   the Tar Pit's sense is a claim about what the PROBLEM required, not a
   criticism of the code."
  []
  (str "\nTHE PERSPECTIVES THIS BASELINE IS READ THROUGH. Each is one source's view\n"
       "of one subject, with a closed set of verdicts. A reading is checkable: it\n"
       "says which verdict holds and why, and you can go and find out.\n\n"
       (str/join
        "\n"
        (for [[lens {:keys [source question verdicts applies-to]}] (sort-by key report/lenses)]
          (str "  " (namespace lens) "/" (name lens) " — " question "\n"
               ;; WHICH SUBJECT, stated first. The registry has always known a
               ;; lens reads either a claim or a module and the prompt never said
               ;; so, and an amender that guesses wrong has its whole record
               ;; refused: a claim lens on a module is an invalid dispatch, not a
               ;; bad field.
               "    reads: " (case applies-to
                               :claim   "a LOAD-BEARING CLAIM (never a module)"
                               :module  "a MODULE (never a claim)"
                               :stratum "a STRATUM (never a module or a claim)"
                               (name applies-to)) "\n"
               "    from " source "\n"
               (str/join "\n"
                         (for [[v d] (sort-by key verdicts)]
                           (str "    " (name v) ": " d))))))
       "\n"))

(defn- module-block
  [modules]
  (when (seq modules)
    (str "\nMODULES — the decomposition claimed. A module is what it HIDES; its\n"
         "interface is WHAT IS DEPENDED ON FROM OUTSIDE — Parnas's `what others\n"
         "may assume` — not what the namespace makes public. A var that is public\n"
         "and called by nobody outside is a visibility choice, not interface, and\n"
         "an interface list that omits one is only wrong if some caller actually\n"
         "leans on it. Enumerating exports is not this round's work: two earlier\n"
         "runs were spent on that disagreement, one side counting callers and the\n"
         "other counting defns, and it settles here in favour of callers.\n"
         (str/join "\n"
                   (map (fn [{:keys [id module hides interface readings]}]
                          (str "- " (when id (str "[" id "] ")) module "\n"
                               "    hides:     " hides "\n"
                               "    interface: " interface
                               (readings-lines readings "    ")))
                        modules))
         "\n")))

(defn- claim-block
  [load-bearing]
  (str/join
   "\n"
   (map (fn [{:keys [id property falsified-by readings evidence]}]
          (str "- " (when id (str "[" id "] ")) property
               ;; Only when there is one. A baseline written before counterexamples
               ;; were required carries none, and printing the label with nothing
               ;; after it tells the judge a claim is refutable in a way its
               ;; author never committed to.
               (when-not (str/blank? (str falsified-by))
                 (str "\n    refuted by: " falsified-by))
               (readings-lines readings "    ")
               (when (seq evidence)
                 (str "\n    read from:  " (str/join ", " evidence)))))
        load-bearing)))

(defn- evidence-line
  "What checks a claim, as a judge reads it."
  [{:keys [by tests law]}]
  (case by
    :round "a round's judgement"
    :test  (str "tests " (str/join ", " tests))
    :law   (str "the law " law)
    (str by)))

(defn- model-block
  "A record's shared model as a judge reads it: the elements, then every claim with the ids it is
   about, the counterexample that would refute it and what checks it. Every subject carries its
   bracketed id, because an id the judge is never shown is one it cannot confirm or refute by.

   `holds` is a phased design's map of claim id to when it holds. A claim false ON PURPOSE mid-plan
   says so beside it, or a judge reads a designed intermediate state as a broken design."
  [{:keys [elements claims]} holds]
  ;; Each section headed only when it lists something: a round whose claims are all settled
  ;; has none left here, and a header over an empty list reads as a record that claims nothing.
  (str (when (seq elements)
         (str "\nELEMENTS — what the claims below are about, by the ids the declared design gives\n"
              "them. A module is what it HIDES; its interface is what is depended on from outside:\n"
              (str/join "\n"
                        (map (fn [{:keys [id sort hides interface plays readings]}]
                               (str "- [" id "] (" (name sort) ")"
                                    (when (seq plays) (str "\n    played by: " (str/join ", " plays)))
                                    (when hides (str "\n    hides:     " hides))
                                    (when interface (str "\n    interface: " interface))
                                    (readings-lines readings "    ")))
                             elements))
              "\n"))
       (when (seq claims)
         (str "\nCLAIMS — each with the elements it is about and the counterexample that would\n"
              "refute it:\n"
              (str/join "\n"
                        (map (fn [{:keys [id about statement falsified-by evidence readings read-at]}]
                               (str "- [" id "] " statement
                                    "\n    about:      " (str/join ", " about)
                                    "\n    refuted by: " falsified-by
                                    "\n    checked by: " (evidence-line evidence)
                                    (when (= :on-completion (get holds id))
                                      "\n    holds ON COMPLETION — not yet true mid-plan, and NOT a finding")
                                    (readings-lines readings "    ")
                                    (when (seq read-at)
                                      (str "\n    read from:  " (str/join ", " read-at)))))
                             claims))
              "\n"))))

(defn- level-reminder
  "The last thing in the window before control passes back.

   Every one of these prompts states its level near the top — and by the time a
   record with eight modules and six claims has been printed after it, that
   statement is thousands of tokens back. What an agent is holding when it
   starts is the end of the prompt, so the level is repeated there. Short on
   purpose: it is a tone-setter, not a second specification, and anything long
   enough to re-teach the rules is long enough to be skimmed."
  ([] (level-reminder :decomposition))
  ([variant]
   (str "\n\n---\n"
        (case variant
          :decomposition
          (str "Before you start: the subject is the DECOMPOSITION — which modules\n"
               "exist, what design decision each hides, what the rest may assume of\n"
               "it, and how they compose to produce the behaviour. Not the\n"
               "correctness of any line. A true statement that does not change one\n"
               "of those is not for this round.\n")
          :commitment
          (str "Before you start: the subject is the COMMITMENT — what this change\n"
               "claims about structure, and whether that claim stands against the\n"
               "area as the baseline describes it. Not whether the code is right,\n"
               "and not whether the baseline is: a defect in either is a different\n"
               "round's finding.\n")))))

(defn- judged-alone
  "A record with its provenance taken off, for a prompt that JUDGES it.

   A judging round is shown the current record and the current foundation, and is
   never shown that either replaced an earlier one. A round told it is looking at
   an amendment judges the delta — it reads a small change as a small change, and
   stops asking whether the whole record still serves the goal that moved. The
   delta is what an AUTHORING handler needs and exactly what a judging one must
   not see, which is why `amend-prompt` and `design-amend-prompt` do not go
   through here — and why the loop, not either author, sets `:supersedes`.

   By NAME, and that is the whole of what this adds: both judging prompts already
   omitted the field, but they omitted it by transcribing a record field by field
   and never reaching for it. Two independent transcriptions of one shape keep a
   rule only until someone adds a field to both, and an amendment is the first
   thing on this arc that carries a delta at all."
  [record]
  (dissoc record :supersedes))

(defn- checks-of
  "`record` without the subjects `ids` names — what a round still puts to its judge
   as checks. A field the record never had stays absent, so a baseline from before
   the decomposition renders exactly as it did."
  [record ids]
  (let [unnamed (fn [xs] (vec (remove (comp ids :id) xs)))]
    (cond-> record
      (contains? record :modules)      (update :modules unnamed)
      (contains? record :load-bearing) (update :load-bearing unnamed)
      (contains? record :health)       (update :health unnamed)
      (contains? record :model)        (update :model #(-> % (update :elements unnamed)
                                                             (update :claims unnamed)))
      (ids "shape")                    (dissoc :shape)
      (ids "composition")              (dissoc :composition))))

(defn- owed-rulings
  "The ids of `record` a round owes its judge's ruling on: every subject `settled` does not name,
   less those carrying nothing a judge could check (`settled/nothing-to-check?`). Sorted, because
   the prompt restates them to the judge as the list its answer is drawn from."
  [record settled]
  (let [subjects (settled/subjects record)]
    (into (sorted-set)
          (remove #(or (contains? settled %) (settled/nothing-to-check? (subjects %))))
          (keys subjects))))

(defn- subject-text
  "What carries a subject id, in a line: a claim's statement, a module's name, an observation, an
   element's sort, or the text of [shape] and [composition]."
  [content]
  (str/join " / " (map #(if (string? %)
                          %
                          (or (:statement %) (:property %) (:module %) (:observation %)
                              (some-> (:sort %) name)))
                       content)))

(defn- moved-block
  "What a record whose every subject is settled differs by from the last verified one — the
   question its round asks, since nothing in it is a check. `moved` is {:from :dropped :fields}, or
   nil when no verified record precedes it on the ledger."
  [{:keys [from dropped fields] :as moved}]
  (if moved
    (str "\nWHAT MOVED — this record reads as the one at entry " from ", which was\n"
         "checked and found sufficient, except for:\n"
         (if (or (seq dropped) (seq fields))
           (bullets (concat (for [[id content] dropped]
                              (str "dropped [" id "] " (subject-text content)))
                            (for [k fields] (str (name k) " reads differently"))))
           "- nothing a subject id or a field names: only how its lists are arranged")
         "\n")
    (str "\nNo verified record precedes this one on the ledger, so there is nothing\n"
         "narrower to ask than the whole record.\n")))

(def ^:private decomposable-check
  "The decomposition check put to a design written before strata: the cut it states, held to the
   level test, and never blocking — the cut does not survive the landing."
  (str
   "  decomposable      — can the cut be stated, and does it follow the levels\n"
   "                      of abstraction? A layer is a LEVEL: it provides\n"
   "                      something — a function, a type, a schema — that the\n"
   "                      layers above are written in, by its interface alone,\n"
   "                      and the baseline's modules and composition say which\n"
   "                      levels the area already has. For each layer, name what\n"
   "                      it provides to a layer above. Three boundaries are\n"
   "                      legitimate although they are not levels: removing what\n"
   "                      a new abstraction replaces (always on top), a\n"
   "                      behaviour-preserving refactor below the change it\n"
   "                      enables, and a mechanical sweep kept apart from\n"
   "                      judgment. Any other split — by size, by review mode,\n"
   "                      by pipeline stage, or one module's secret across two\n"
   "                      layers — is a cut against the grain of the area.\n"
   "                      Vertically: layers ordered by dependency, one claim\n"
   "                      each with no \"and\". Temporally, IF the design is\n"
   "                      phased: each phase a state the system can be left in,\n"
   "                      with an exit criterion that is an observation rather\n"
   "                      than a to-do.\n"
   "                      A design whose cut cannot be stated is not decomposed\n"
   "                      yet, and there is nothing to approve. A design with no\n"
   "                      phase plan is the ordinary case and is NOT a finding.\n"
   "                      Hold the layers to the level test BOTH ways. A layer\n"
   "                      that provides nothing — one that only forwards what\n"
   "                      the layer below already accepts — or a level cut in\n"
   "                      two is a defect in the cut, not a small layer; a large\n"
   "                      layer that builds a level AND writes the program in it\n"
   "                      has hidden a boundary. The test does not ratchet the\n"
   "                      way `can it be separated?` does, so if the layer count\n"
   "                      rose while the levels did not, say so.\n"
   "                      AND IT NEVER BLOCKS. Layers are a development-time\n"
   "                      device: the stack is collapsed into one commit before\n"
   "                      it lands, and the code lands whole whichever way it\n"
   "                      was cut, so a cut that misreads the levels costs only\n"
   "                      the reading it was drawn for.\n"
   "                      So whatever you conclude here, it does not change your\n"
   "                      recommendation: say it in `asks`, mark the check, and\n"
   "                      recommend on the other three. `amend` and `recut` are\n"
   "                      for a defect in the COMMITMENT. There is no exception\n"
   "                      for a cut you cannot state at all — `:layers` is an\n"
   "                      optional field, a record without it is valid, and what\n"
   "                      you are approving is the commitment, not the\n"
   "                      packaging. Two soft bars against over-splitting have\n"
   "                      already failed here; this one is not a matter of\n"
   "                      degree.\n"))

(def ^:private stratified-check
  "The decomposition check put to a design that names its strata. It judges where the change sits
   among levels that survive the landing, so unlike the cut it replaced it can hold a design: a
   misplaced change is what trunk keeps.

   FIT is the question that keeps a round from accepting today's levels by default. It is bounded
   twice — to a levelling the judge can state concretely, and to one that bears on THIS change — and
   a reasoned rejection settles it, so it asks for a decision about restructuring, never for the
   restructuring itself."
  (str
   "  stratified        — does the change sit where its levels say it should? Four\n"
   "                      questions of the COMMITMENT, none of how the work will be\n"
   "                      cut into layers:\n"
   "                      PLACEMENT — each part of the change is written in the\n"
   "                      vocabulary of the stratum it sits in. A part that has to\n"
   "                      reach past the stratum below it, or that says in one\n"
   "                      level what belongs to another, is placed wrong.\n"
   "                      BARRIER — a stratum's interface grows only where the\n"
   "                      design says why combining what it already provides could\n"
   "                      not express the need. Compare each stratum the design\n"
   "                      restates with the baseline's; growth with no such reason\n"
   "                      is broken.\n"
   "                      FIT — is there a levelling of this area that would make\n"
   "                      THIS change markedly simpler? If you can state one\n"
   "                      concretely — which strata, what each would provide, what\n"
   "                      in this change it removes — the design must already have\n"
   "                      answered it: adopted it, rejected it with a reason that\n"
   "                      still holds, or deferred it as a seam with a ref. If it\n"
   "                      has not, the check is broken; if it has, it is held — a\n"
   "                      reasoned decision not to restructure is a decision, not\n"
   "                      a defect. Only a levelling that bears on THIS change\n"
   "                      counts: the area's other tensions are the baseline's\n"
   "                      health, and the routes above already answer them.\n"
   "                      LEVEL — every stratum the design declares or restates is\n"
   "                      a level: something resting on it is written in its\n"
   "                      vocabulary, or it is the one level the program is written\n"
   "                      at. A feature split off the top level, a helper, an\n"
   "                      output sink, or a cut made only to satisfy a dependency\n"
   "                      law is no level, and declaring it one is broken. A level\n"
   "                      judge's not-a-level is evidence you weigh here.\n"
   "                      A design touching no declared stratum is judged on FIT\n"
   "                      alone. Temporally, IF the design is phased: each phase a\n"
   "                      state the system can be left in, with an exit criterion\n"
   "                      that is an observation rather than a to-do; a design with\n"
   "                      no phase plan is the ordinary case and is NOT a finding.\n"
   "                      This check blocks like the others.\n"))

(def ^:private compound-clause-rule
  "What a record judge owes a subject that says several things. A judge left to itself stops at
   the first counterexample it finds, the amender repairs exactly what the finding names, and a
   clause the judge had already read false becomes the next round's finding on text it was shown
   — a whole round spent on nothing new. One finding per subject, not one per clause: findings
   are keyed on the subject, so a second finding against the same id would be the same identity."
  (str "A SUBJECT THAT SAYS SEVERAL THINGS IS RULED CLAUSE BY CLAUSE. A statement, the\n"
       "counterexample it names, an interface and what it hides, each exclusivity it\n"
       "asserts — check every one, not until the first falls. Report ONE finding for\n"
       "the subject naming EVERY clause you found false, each with its counterexample:\n"
       "the amender repairs what the finding names, so a false clause you saw and did\n"
       "not report costs a whole round to find again. Hold each stated counterexample\n"
       "against the record's own text as well — one the record itself already\n"
       "satisfies is a contradiction, and a false clause like any other.\n\n"))

(defn- strata-era?
  "Whether `record` names its strata — the field that marks a record written since strata entered
   the model, and so the one a round reads to pick the yardstick it holds the record to."
  [record]
  (contains? record :strata))

(defn- about-strata
  "The strata a health observation names, as the suffix a judge reads it with; empty for one that
   names none."
  [{:keys [about]}]
  (if (seq about) (str " [about " (str/join ", " about) "]") ""))

(defn- strata-block
  "The strata a baseline lists, with what the round checks of each: the reading of its level is a
   claim like any other, and a tension it finds has to reach the design as health."
  [strata]
  (str "\nSTRATA — the levels this area declares, floor first"
       (if (seq strata) (str ":\n" (bullets strata) "\n") ": none declared within the bound.\n")
       "Each is an element listed in this prompt, saying what it provides and read through\n"
       "stratified/level. That reading is refutable like any other: `sound` is\n"
       "refuted by one of its modules written in another's vocabulary (mixed), by code\n"
       "resting on it that reaches past it (bypassed), by an interface offering\n"
       "what nothing above is written in, or could build by combining (wide), or by\n"
       "nothing resting on it being written in it at all when it is not the level the\n"
       "program is written at (not-a-level). Any verdict but sound is a tension, and a health observation names that stratum\n"
       "under :about so the design has to route it. A declared stratum holding a\n"
       "module this area lists, which the survey does not list, blocks stratified.\n"))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  baseline-prompt
  "The verification prompt.

   Pitched at the decomposition, and deliberately so. Asked to check a baseline
   against a large subsystem, a judge will find true things about it forever —
   an implementation has no fixed point to converge on, while a decomposition
   does. What makes that bound real is that each claim arrives with the
   counterexample that would refute it, so the question is never `is this any
   good` but `does that specific thing exist`.

   Only what `settled` does not name is put to the judge as a check, and the checks come LAST,
   after the settled subjects (`settled-block`) and ahead of the instructions that restate them by
   id: a judge shown the settled block after its checks confirmed ten of those ids and skipped one
   of its two checks. The settled subjects are shown at all because the record-level derivations
   are made against the whole record. Below, `full` is the record and `baseline` is its checks.

   A round over a record whose every subject is settled owes no ruling at all, and is asked only
   whether the four derivations can still be made — against what `moved` names, the difference
   from the last verified record (`moved-block`). That is the one prompt told what changed: with no
   subject to judge there is no delta to read a subject through, and the delta is the question."
  [{:keys [baseline disputes settled stance prior moved]}]
  (let [full     (judged-alone baseline)
        baseline (checks-of full (set (keys settled)))
        owed     (owed-rulings full settled)
        ;; Nothing owed and something settled: the judge checks no subject. A record with nothing
        ;; settled and nothing owed claims nothing, and keeps the prompt it always had.
        derive?  (and (seq settled) (empty? owed))]
   (str
   (if derive?
     (str "You are checking whether a BASELINE of an area is ENOUGH. None of it is put\n"
          "to you as a check of whether it is TRUE, so you are not verifying any claim,\n"
          "module or observation against the code. You are NOT designing anything, you\n"
          "are not reviewing the code for defects, and you are not judging whether the\n"
          "area is good.\n\n")
     (str "You are checking whether a BASELINE of an area is TRUE, and whether it is\n"
          "ENOUGH. You are NOT designing anything, you are not reviewing the code for\n"
          "defects, and you are not judging whether the area is good.\n\n"))
   "ENOUGH FOR WHAT — this is the whole of the second question, and it is not\n"
   "completeness. A baseline exists so a later round can derive four things about a\n"
   "proposed change:\n"
   "  relation-honest   does the change stand where it says it stands, against\n"
   "                    this area's modules and load-bearing properties?\n"
   "  goal-served       does it serve the goal and only the goal — would a\n"
   "                    smaller change do?\n"
   (if (strata-era? full)
     (str "  stratified        does each part of the change sit in the level whose\n"
          "                    vocabulary expresses it, against the strata this area\n"
          "                    declares and the reading of each?\n")
     (str "  decomposable      can its cut be stated, and does it follow the levels\n"
          "                    this area's modules compose into?\n"))
   "  routing-coherent  do this area's health observations belong to one story?\n\n"
   "The baseline is ENOUGH when those can be derived against it. It does not have\n"
   "to be everything true about the area, and it never will be: measured against\n"
   "a real system there is always another true thing to add, so a round that\n"
   "reports what is missing rather than what is BLOCKED never finishes and never\n"
   "helps. If you notice something true that blocks none of the four, it is not a\n"
   "finding. Leave it.\n\n"
   "THE LEVEL THIS OPERATES AT. The baseline describes the area as a DECOMPOSITION:\n"
   "which modules exist, what design decision each one hides, and how their\n"
   "composition produces the required behaviour. Judge it there. A defect in one\n"
   "line of SQL is not a finding here however real it is — that belongs to code\n"
   "review, and reporting it here is how a round finds true things forever\n"
   "without ever answering the question that was asked.\n\n"
   (when-not derive?
     (str "EVERY CLAIM CARRIES WHAT WOULD REFUTE IT. That is what you go looking for.\n"
          "Not `does this feel right` — `does that specific counterexample exist in the\n"
          "code`. Report a finding only when you found one, and say what it is.\n\n"
          "A COUNTEREXAMPLE THAT NEEDS AN AUDIT OF EVERY CALLER IS THE WRONG ONE, and\n"
          "when a claim you are checking names one, that is itself the finding — never\n"
          "for a subject outside this round's checks, which only a counterexample you\n"
          "actually found can refute. A property about what a\n"
          "MODULE promises is checked by reading that module. A property phrased as\n"
          "`every client does X` is not a decomposition claim at all — it is a\n"
          "conformance claim over N implementations, it has a counterexample for every\n"
          "client that deviates, and chasing those is how this round finds true things\n"
          "forever. Where a client deviates from a contract the baseline states, the\n"
          "deviation belongs in HEALTH — :implementation when the contract is right and\n"
          "the code drifted, :design when the deviation shows the contract itself is\n"
          "wrong. Say which, and say it once.\n\n"))
   ;; Gated on the baseline being ABLE to carry readings, not on it having any.
   ;; Gating on presence hid the vocabulary from exactly the baseline that needed
   ;; it — one with a decomposition and no analysis — so nothing ever told anyone
   ;; the perspectives existed. The first real baseline written in this shape
   ;; carried five modules and zero readings.
   (when (or (contains? baseline :modules) (contains? baseline :model))
     (str (if (some (comp seq :readings)
                    (concat (model/claims full) (model/elements full)))
            (when-not derive?
              (str "A READING IS A CLAIM TOO, and refutable on its own terms. State read\n"
                   "as essential is refuted by a derivation that computes it. An ordering\n"
                   "read as required is refuted by showing the two things commute. A module\n"
                   "read as deep is refuted by an interface that costs about what it hides.\n"
                   "A dependency read as on-interface is refuted by a caller reaching past\n"
                   "it. Check the readings as well as the properties.\n"))
            (str "THIS BASELINE READS NOTHING THROUGH ANY PERSPECTIVE. It could — the\n"
                 "vocabulary is below and the record is in a shape that carries readings.\n"
                 "A decomposition recorded with no reading of it is structure without\n"
                 "analysis: it says what the parts are and never says whether the problem\n"
                 "required them, whether an ordering was imposed, or whether a boundary pays.\n"
                 "Report that as INSUFFICIENT, blocking relation-honest — whether a change\n"
                 "stands where it says cannot be derived from structure nobody has read —\n"
                 "and name the claims most worth reading and through which lens.\n"))
          (lens-block)))
   "AREA: " (:area baseline) "\n"
   "BOUNDED BY: " (:bounded-by baseline) "\n"
   ;; What the record says that no id names — context for the checks, never one of them — ahead
   ;; of the settled subjects, so that everything after those is a check.
   (when (strata-era? full)
     (strata-block (:strata full)))
   (when-let [u (seq (:unknowns baseline))]
     (str "\nDECLARED NOT DETERMINED — already honest, not findings:\n" (bullets u) "\n"))
   (settled-block (if derive?
                    ;; Every subject, bare elements included: the derivations are made against them all.
                    (merge (zipmap (keys (settled/subjects full)) (repeat {})) settled)
                    settled)
                  full)
   (if derive?
     (str (moved-block moved)
          "\nTHE QUESTION THIS ROUND ANSWERS: can the four derivations still be made\n"
          "against this record as it stands"
          (when moved ", without what it dropped and with what moved")
          "? Every\n"
          "subject listed in this prompt is part of the record and none is a check.\n\n"
          "Return sufficient when all four can be made. Return INSUFFICIENT when one\n"
          "cannot: say WHICH (`blocks`) and what the baseline would have to say for it\n"
          "to be makeable (`needs`) — the specific missing claim, not `more detail`.\n"
          "Return FALSIFIED only when making a derivation showed you a subject is\n"
          "false: name it by id and show the counterexample, with where it is.\n\n"
          "Every finding MUST cite what it is about — the exact property, module or\n"
          "composition text. A finding that cites nothing is not a finding.\n\n"
          "Leave confirmed and unchecked empty. Nothing here is put to you as a check,\n"
          "so a confirmation is not asked for and is not counted.")
     (str
      "\nWHAT YOU ARE CHECKING — every subject from here to the instructions below, and\n"
      "no other:\n"
      ;; Bracketed like every other subject. An id the judge is never shown is one
      ;; it cannot cite back, and these two are the ones a decomposition round
      ;; challenges most.
      (when-let [s (:shape baseline)] (str "SHAPE: [shape] " s "\n"))
      (module-block (:modules baseline))
      (when-let [c (:composition baseline)]
        (str "\nCOMPOSITION — how those are claimed to produce the behaviour:\n"
             "[composition] " c "\n"))
      ;; A baseline in the shared model states its properties as claims about its elements, shown
      ;; for what is still to check; the survey shape it replaces lists load-bearing properties,
      ;; headed only while there are claims to check — a record whose every claim is settled has
      ;; none here, and a header over an empty list reads as a baseline that claims nothing.
      (if (contains? baseline :model)
        (model-block (:model baseline) nil)
        (when (or (seq (:load-bearing baseline)) (empty? (:load-bearing full)))
          (str "\nLOAD-BEARING — what is claimed to break if violated"
               (if (some :falsified-by (:load-bearing full))
                 ", each with the\ncounterexample that would refute it:\n"
                 (str ".\n\nThis baseline predates the rule that a claim must name its own\n"
                      "counterexample, so none of them do. Judge the claims as stated, and treat\n"
                      "a claim you cannot see any way to refute as a finding in its own right.\n"))
               (claim-block (:load-bearing baseline)) "\n")))
      (when-let [h (seq (:health baseline))]
        (str "\nHEALTH — claimed about whether what holds is sound. :design means a\n"
             "weak design cleanly executed; :implementation means a strong design\n"
             "shakily executed. A mis-axed observation is a finding:\n"
             ;; Its id first, and it was the one subject with an id that the prompt
             ;; never printed — so a judge asked to confirm by id could not confirm
             ;; a health observation at all.
             (bullets (map #(str (when (:id %) (str "[" (:id %) "] "))
                                 "[" (name (:axis %)) "] " (:observation %)
                                 " [" (str/join ", " (:evidence %)) "]"
                                 (about-strata %))
                           h))
             "\n"))
      "\nTwo distinct failures, and they have different remedies:\n"
      "1. FALSIFIED — a claim's own counterexample EXISTS. The module hides a\n"
      "   decision something outside depends on; the `essential` fact is derivable\n"
      "   from something else the system holds; the `derived` value is also stored\n"
      "   and edited independently; the composition does not produce the behaviour\n"
      "   claimed. Show the counterexample, with where it is.\n"
      "2. INSUFFICIENT — one of the four derivations cannot be made against this\n"
      "   baseline. Say WHICH (`blocks`) and what the baseline would have to say for it\n"
      "   to be makeable (`needs`) — the specific missing claim, not `more detail`.\n"
      "   A gap that blocks none of the four is not a finding here; at most it is a\n"
      "   health observation, and more often it is the next baseline's business.\n\n"
      "SUFFICIENT IS THE EXPECTED OUTCOME on any baseline that has done its job. It is\n"
      "not a high bar and it is not praise — it means a decision can be made against\n"
      "this, which is all a baseline is for.\n\n"
      "Every finding MUST cite what it is about — the exact property, module or\n"
      "composition text. A finding that cites nothing is not a finding; do not\n"
      "report it. Neither is a finding that reports a bug in code the baseline\n"
      "correctly describes.\n\n"
      "Populate confirmed with what you actually went and checked and found to hold —\n"
      "each by its id, the bracketed slug without the brackets, drawn only from the\n"
      "ids listed below, with the file:line references you read that show it holds.\n"
      "A confirmation citing nothing you read is not counted.\n\n"
      "CONFIRMED MEANS EVERY SENTENCE HELD. A clause of a subject that you found\n"
      "false is a finding against that subject's id — a falsification — even when\n"
      "the counterexample the subject states does not name that clause. Leave the id\n"
      "out of confirmed, and do not park the correction in reason: nothing reads it\n"
      "there.\n\n"
      compound-clause-rule
      "Ids, not sentences. A confirmation worded differently each round cannot be\n"
      "matched to the claim it is about, so the next round cannot tell what is\n"
      "settled and checks it again instead of checking what nobody has looked at\n"
      "yet.\n\n"
      "Return sufficient when they held and the four derivations are makeable. Do\n"
      "not manufacture findings to look thorough — on this round, thoroughness is\n"
      "checking the claims that are there, not finding more to say.\n\n"
      ;; Restated by id, last among the instructions: the list the judge's answer is drawn from,
      ;; and the thing it is holding when it begins.
      "RULE ON EVERY SUBJECT YOU ARE ASKED TO CHECK, and on no other. They are exactly\n"
      "these ids:\n"
      (if (seq owed) (bullets owed) "- none: this record names no subject a judge could check")
      "\nEach one ends up confirmed, named by a finding, or in unchecked with why it\n"
      "cannot be checked here: its evidence is not in the code (production data, a\n"
      "deploy history). A subject you leave without a ruling is not counted as held:\n"
      "the round is asked again, and a sufficient verdict over it does not stand."))
   (when-not derive?
     (prior-findings-block (apply dissoc prior (keys settled))))
   (disputes-block disputes)
   ;; Last thing in the window before the judge starts work. The level is stated
   ;; near the top, thousands of tokens back by the time the record has been
   ;; printed; restating it here is what the judge is holding when it begins.
   (when stance
     (str "\n\nPROJECT STANCE — the yardstick this area was designed under, framing\n"
          "only. It tells you what the project considers essential versus\n"
          "accidental and what a boundary is FOR. Read the decomposition through\n"
          "it; never cite it against a line of code.\n\n" stance "\n"))
   (level-reminder))))

(defn- baseline-block
  "The baseline the design was made against, at the level the design round needs it.

   Three of the four derivations are made AGAINST this record: relation-honest
   reads the modules and the extension points, decomposable reads the module
   boundaries — stratified the strata and their readings — and routing-coherent
   reads the health observations the design's routes cite by id. So it is printed the way the baseline round prints it, rather than
   summarised.

   It used to print :bounded-by and the load-bearing properties and nothing else,
   while the derivations below asked for module boundaries, extension points and
   health observations by name. A judge cannot tell `the baseline does not say`
   from `I was not shown it`, and the only recommendation that fits the first is
   :resurvey — so every round recommended :resurvey, and one of them reported a
   module missing that the baseline had named all along."
  [baseline]
  (when baseline
    (str "\nTHE AREA AS BASELINED, before this was designed. Three of the four\n"
         "derivations are made against THIS. Where the baseline states something,\n"
         "derive against what it states — a baseline that is wrong about the area is\n"
         "a finding, but a baseline you did not read is not one.\n\n"
         "AREA: " (:area baseline) "\n"
         "BOUNDED BY: " (:bounded-by baseline) "\n"
         (when-let [s (:shape baseline)] (str "SHAPE: " s "\n"))
         (module-block (:modules baseline))
         (when-let [c (:composition baseline)]
           (str "\nCOMPOSITION — how those are claimed to produce the behaviour:\n" c "\n"))
         (when-let [e (seq (:extension-points baseline))]
           (str "\nEXTENSION POINTS — where the design already admits extension. A\n"
                "change landing on one of these EXTENDS the design; one that needs a\n"
                "point not listed here is asking for the design to be revisited. This\n"
                "is the yardstick for the declared relation:\n"
                (bullets (map #(str (:at %) " — " (:how %)) e)) "\n"))
         (if (contains? baseline :model)
           (model-block (:model baseline) nil)
           (str "\nLOAD-BEARING — what the baseline claims breaks if violated:\n"
                (claim-block (:load-bearing baseline)) "\n"))
         (when-let [h (seq (:health baseline))]
           (str "\nHEALTH OBSERVED — the design's routes cite these BY ID, and\n"
                "routing-coherent is derived against them. :design means a weak design\n"
                "cleanly executed; :implementation means a strong design shakily\n"
                "executed. An observation marked invisibly-incomplete cannot leave the\n"
                "branch that touches it:\n"
                (str/join
                 "\n"
                 (map (fn [{:keys [id observation axis evidence invisibly-incomplete?] :as h}]
                        (str "- " id
                             (when axis (str " [" (name axis) "]"))
                             (when invisibly-incomplete? " [invisibly incomplete]")
                             (about-strata h) "\n"
                             "    " observation
                             (when (seq evidence)
                               (str "\n    read from: " (str/join ", " evidence)))))
                      h))
                "\n"))
         (when-let [u (seq (:unknowns baseline))]
           (str "\nDECLARED NOT DETERMINED by the baseline. Already honest — not a\n"
                "finding, and not on its own grounds to call the premise wrong:\n"
                (bullets u) "\n")))))

(defn- qualifiers
  "The fields that make a declared relation checkable, under the relation that
   names it.

   A relation is a one-word claim and the fields beside it are the whole of its
   substance: a :revisit says WHICH load-bearing properties it breaks, an
   :extends says WHERE it lands, and both carry the note that argues for it. The
   prompt rendered the keyword alone — so `relation-honest`, the check that is
   entirely about these two declarations, derived over a word.

   Watched: a design declared :revisit and named the two properties it breaks,
   in the field the closed schema requires them in — it could not have been
   appended without them. The round reported it as failing to name them, three
   times, and sent the amender to write the same two strings into the field they
   already occupied. Four rounds, partly spent on an artifact of this function
   not existing.

   Third time today this shape has cost a round: health observations were shown
   by their axis rather than their id, :shape and :composition carried no id at
   all, and now this. A pass asked to derive something over a prompt that does
   not show it reports the record as missing what the record contains."
  [m]
  (apply str
         (concat
          (when-let [b (seq (:breaks m))]
            [(str "\n    breaks: " (str/join "; " b))])
          (when-let [a (:at m)] [(str "\n    at: " a)])
          (when-let [p (seq (:principles m))]
            [(str "\n    principles: " (str/join "; " p))])
          (when-let [n (:note m)] [(str "\n    because: " n)]))))

(defn- relation-subjects
  "Every id relation-honest is ruled on, as `[kind id]` with kind `:claim`, `:module` or
   `:stratum`, in that order: the baseline's claims (load-bearing properties, in either shape), its
   modules and its strata. Each once; empty for no baseline.

   An enumeration and not a description, because a judge asked to sweep `each claim and each module`
   sweeps what it happens to read: one design's :breaks grew six, eight, twelve over three rounds,
   each round finding ids the last had not, and strata were never named to it at all."
  [baseline]
  (let [m      (:model baseline)
        of     (fn [sort] (keep #(when (= sort (:sort %)) (:id %)) (:elements m)))
        tagged (concat (map #(vector :claim %) (keep :id (concat (:claims m) (:load-bearing baseline))))
                       (map #(vector :module %) (concat (of :module) (keep :id (:modules baseline))))
                       (map #(vector :stratum %) (concat (of :stratum) (:strata baseline))))]
    (first (reduce (fn [[acc seen] [k id]]
                     (let [id (str id)]
                       (if (or (str/blank? id) (seen id))
                         [acc seen]
                         [(conj acc [k id]) (conj seen id)])))
                   [[] #{}]
                   tagged))))

(defn- relation-yardstick
  "The ids `design` is ruled on per id: `baseline`'s (`relation-subjects`), when the design declares
   a relation to it. A design from before the baseline event declares none and has no :breaks to hold
   to them, so it gets none."
  [design baseline]
  (if (get-in design [:baseline :relation]) (relation-subjects baseline) []))

(defn- relation-ids-lines
  "`subjects` (`relation-subjects`) as a judge or an amender reads them: one line per kind, each
   prefixed by `indent`. `rulings`, by id, is printed beside each id when given."
  [subjects indent rulings]
  (str/join
   (for [[k label] [[:claim "claims"] [:module "modules"] [:stratum "strata"]]
         :let [ids (keep (fn [[kind id]] (when (= k kind) id)) subjects)]
         :when (seq ids)]
     (str indent label ":\n"
          (str/join (for [id ids
                          :let [{:keys [ruling reason]} (get rulings id)]]
                      (str indent "  [" id "]"
                           (when ruling (str " " (name ruling) (when-not (str/blank? reason) (str " — " reason))))
                           "\n")))))))

(defn- levels-block
  "What each named stratum's own judge concluded, for the deciding judge: the level's declared
   vocabulary beside its verdict, or the outcome that stood in for one. Evidence, never the decision."
  [levels]
  (when (seq levels)
    (str "WHAT EACH LEVEL SAID — before you, each declared stratum above was read by a judge given\n"
         "only that level: its vocabulary, its modules, its neighbours, and what this design asks of\n"
         "it. Weigh these under the stratified check; they are evidence, not verdicts you inherit, and\n"
         "a level judge that failed tells you nothing about its level:\n"
         (bullets (for [{:keys [stratum vocabulary reading]} levels]
                    (str stratum " — provides: " (str/replace (str vocabulary) #"\s+" " ")
                         (if (:verdict reading)
                           (str "\n    its judge: " (name (:verdict reading)) " — " (:reason reading))
                           (str "\n    its judge did not answer (" (name (:outcome reading)) ")")))))
         "\n")))

(defn- clauses
  "`statement` cut at each sentence or semicolon, trimmed — the unit a grant is compared in."
  [statement]
  (into [] (comp (map str/trim) (remove str/blank?))
        (str/split (str statement) #"(?<=[.;])\s+")))

(defn- clause-delta
  "What one claim's text and :about lost and gained between `was` and `is` — {:granted :now}, each
   only the clauses, or :about ids, the other side lacks. A clause both sides carry is absent: it
   reads as it did when granted."
  [was is]
  (let [only (fn [a b] (let [b (set b)] (vec (remove b a))))
        [wc ic] [(clauses (:statement was)) (clauses (:statement is))]
        [wa ia] [(:about was) (:about is)]]
    (cond-> {:granted (only wc ic) :now (only ic wc)}
      (not= (set wa) (set ia)) (assoc :about {:granted (only wa ia) :now (only ia wa)}))))

(defn- claim-delta
  "Which claims `design` adds, changes and drops against `granted`, by id, with :clauses holding
   `clause-delta` per changed id — nil when either lists invariants with no ids, which leave
   nothing to compare by."
  [granted design]
  (let [by-id   (fn [d] (into {} (map (juxt :id #(select-keys % [:statement :about])))
                              (get-in d [:model :claims])))
        was     (by-id granted)
        is      (by-id design)
        changed (vec (sort (filter #(and (was %) (not= (was %) (is %))) (keys is))))]
    (when (and (seq was) (seq is))
      (cond-> {:added   (vec (sort (remove was (keys is))))
               :changed changed
               :dropped (vec (sort (remove is (keys was))))}
        (seq changed) (assoc :clauses (into (sorted-map)
                                            (map (fn [id] [id (clause-delta (was id) (is id))]))
                                            changed))))))

(defn- subject-delta
  "Which subjects (`settled/subjects`) an amendment `record` adds, changes and drops against `prev`,
   the record it repairs, by id — only the non-empty of the three, and nil when it moves none. A
   subject whose text an amendment left alone is still settled next round; these are the ones the
   next judge is asked about again.

   A design's :baseline citation — its relation and :breaks — counts as one more subject, keyed
   \"baseline\", because extending :breaks is how an amendment repairs relation-honest and a delta
   without it reports that repair as no change. It is here and not in `settled/subjects`, which
   decides what a judge owes a ruling on: the citation is judged by relation-honest, never by id."
  [prev record]
  (let [with-citation #(cond-> (settled/subjects %)
                         (some? (:baseline %)) (update "baseline" (fnil conj []) (:baseline %)))
        was (with-citation prev)
        is  (with-citation record)]
    (not-empty
     (into {} (filter (comp seq val))
           {:added   (vec (sort (remove was (keys is))))
            :changed (vec (sort (filter #(and (was %) (not= (was %) (is %))) (keys is))))
            :dropped (vec (sort (remove is (keys was))))}))))

(declare lineage-of loop-why-prefix)

(defn- written-by-person?
  "Whether the design `d` was written by somebody other than a review loop's amender — a person, or
   a session acting for one. `amended` is `ws/amended-seqs` of its ledger.

   The :why test is what still recognises an amender's record appended before the index stamp
   existed: the loop writes the :why on every record it leaves the amender to cite, and tells the
   amender to omit its own."
  [amended d]
  (and (not (amended (:seq d)))
       (not (some-> (get-in d [:supersedes :why]) (str/starts-with? loop-why-prefix)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  answered
  "What a person has already answered about `design`, read off the ledger for its judge's `asks`:

     :grant  the nearest design in its :supersedes chain a person granted, itself included —
             {:seq :self? :note :delta :carried? :earlier} — or absent when nobody has granted any
             of them. A :design-approved grants the design it names; a design a person wrote grants
             itself with its :supersedes :granted, and is then :carried? :earlier is every grant
             further up the chain, nearest first, as {:seq :note :carried?}: a newer grant is often
             narrower, and does not withdraw the scope an older one decided
     :asked  {:seq :of :asks}, the newest decision on this workstream — at :seq, of the design at
             :of — when it asked and neither a grant nor a reply has been written since; a question
             put to a person and not yet answered
     :answered-since  {:seq :asks :by [{:seq :kind :why}]}, when the newest decision asked and a
             person replied after it by writing the record rather than a grant: a design in the
             chain they wrote, or the intent the design cites, each by its :supersedes :why
     :rewritten  {:seq :why :supersedes :approved?}, the nearest design in the chain a person
             wrote, by its :supersedes :why, when that is not already a reply above — and whether
             the design it replaced was approved
     :asked-for  the :findings entries a person filed after the design they last granted (after
             the chain's root, when nothing was granted), oldest first — {:seq :round :note :items}
     :asked-of  per claim `design` carries, each earlier :ask decision in its lineage that filed a
             finding under it, oldest first — {id [{:seq :asks :granted {:seq :note}
             :answered {:seq :why}}]}, :granted the first approval written after that ask and
             :answered, when there is none, the first reply

   Only what bears on the ask. The judge still derives every check over the whole record, and is
   told so beside this: a grant or a reply is evidence that a person decided, never that a claim
   holds."
  [project ws-id design]
  (let [amended   (ws/amended-seqs project ws-id)
        person?   #(written-by-person? amended %)
        chain     (loop [d design, seen #{}, acc []]
                    (if (or (nil? d) (seen (:seq d)))
                      acc
                      (recur (some->> (get-in d [:supersedes :seq]) (ws/entry-at-seq project ws-id))
                             (conj seen (:seq d)) (conj acc d))))
        approvals (ws/entries-of project ws-id :design-approved)
        approved  (into {} (map (juxt #(get-in % [:design :seq]) identity)) approvals)
        grants    (filterv #(or (approved (:seq %))
                                    (and (person? %) (get-in % [:supersedes :granted])))
                           chain)
        granted   (first grants)
        note-of   #(if-let [a (approved (:seq %))]
                     (:note a)
                     (get-in % [:supersedes :granted]))
        note      (note-of granted)
        earlier   (mapv #(cond-> {:seq (:seq %)}
                           (note-of %) (assoc :note (note-of %))
                           (not (approved (:seq %))) (assoc :carried? true))
                        (rest grants))
        ;; The intent is no amender's to write: a review loop amends baselines and designs.
        intent    (some->> (get-in design [:intent :seq]) (ws/entry-at-seq project ws-id))
        reply     (fn [kind r] {:seq (:seq r) :kind kind :why (get-in r [:supersedes :why])})
        written   (filterv #(and (:seq %) (person? %) (get-in % [:supersedes :why])) chain)
        replies   (sort-by :seq (cond-> (mapv #(reply :design %) written)
                                  (and (:seq intent) (get-in intent [:supersedes :why]))
                                  (conj (reply :intent intent))))
        after     (fn [n] (filterv #(> (long (:seq %)) (long (or n 0))) replies))
        last-ask  (last (ws/entries-of project ws-id :design-decision))
        asking?   (and last-ask (not (str/blank? (:asks last-ask))))
        since-ask (when asking? (after (:seq last-ask)))
        approved? (some #(> (long (or (:seq %) 0)) (long (or (:seq last-ask) 0))) approvals)
        rewrite   (first written)
        asked-for (let [from (long (or (:seq granted) (:seq (peek chain)) 0))]
                    (into [] (comp (filter #(> (long (or (:seq %) 0)) from))
                                   (map #(select-keys % [:seq :round :note :items])))
                          (ws/entries-of project ws-id :findings)))
        carried   (set (keys (settled/subjects design)))
        asked-of  (reduce (fn [acc {n :seq :as d}]
                            (let [grant (some #(when (> (long (or (:seq %) 0)) (long n)) %) approvals)
                                  reply (when-not grant (first (after n)))
                                  ask   (cond-> {:seq n :asks (str (:asks d))}
                                          grant (assoc :granted (select-keys grant [:seq :note]))
                                          reply (assoc :answered (select-keys reply [:seq :why])))]
                              (reduce #(update %1 %2 (fnil conj []) ask) acc
                                      (into (sorted-set)
                                            (comp (keep #(not-empty (str (:claim-id %)))) (filter carried))
                                            (:findings d)))))
                          (sorted-map)
                          (filter #(and (= :ask (:recommend %)) (:seq %))
                                  (lineage-of project ws-id :design-decision :design-seq design)))]
    (cond-> {}
      (seq asked-of) (assoc :asked-of asked-of)
      granted (assoc :grant (cond-> {:seq (:seq granted) :self? (= (:seq granted) (:seq design))}
                              note (assoc :note note)
                              (not (approved (:seq granted))) (assoc :carried? true)
                              (claim-delta granted design)
                              (assoc :delta (claim-delta granted design))
                              (seq earlier) (assoc :earlier earlier)))
      (seq since-ask)
      (assoc :answered-since {:seq (:seq last-ask) :asks (:asks last-ask) :by since-ask})
      (and asking? (not approved?) (empty? since-ask))
      (assoc :asked (cond-> {:seq (:seq last-ask) :asks (:asks last-ask)}
                      (:design-seq last-ask) (assoc :of (:design-seq last-ask))))
      (and rewrite (not-any? #(= (:seq rewrite) (:seq %)) since-ask))
      (assoc :rewritten {:seq        (:seq rewrite)
                         :why        (get-in rewrite [:supersedes :why])
                         :supersedes (get-in rewrite [:supersedes :seq])
                         :approved?  (boolean (approved (get-in rewrite [:supersedes :seq])))})
      (seq asked-for) (assoc :asked-for asked-for))))

(defn- answered-block
  "Who reads `asks`, and what is already off the table for it: the design's :open notes, a grant a
   person gave, a reply they wrote into the record, what they asked for, a question already put to
   one. `owes?` is `report/owes-a-person?` of the design, `judged` its :seq — nil on a record not
   yet on the ledger, which no earlier ask can have been of."
  [owes? judged open {:keys [grant asked answered-since rewritten asked-for asked-of]}]
  (str
   (if owes?
     (str "\nWHO READS asks: a person. This design declares a move they must grant\n"
          "(:challenges or :revisit), so a proceed stops for them and asks is what they read.\n")
     (str "\nWHO READS asks: NOBODY, on a proceed. This design declares nothing a person\n"
          "must grant, so a proceed clears it and the build starts; asks is recorded and\n"
          "no one is stopped to read it. A question the build must not start without —\n"
          "what the intent means, whether this scope is the one wanted, whether it is\n"
          "worth its cost — is recommend ask, never a proceed with the question in asks.\n"))
   (when (seq open)
     (str "\nTHE RECORD'S OPEN NOTES — what its author states is decided, or left open on purpose:\n"
          (bullets open) "\n"
          "A note stating a decision is answered: asks does not pose it again. A finding\n"
          "that sets the intent against one is a question for a person — ask, not amend.\n"))
   (when grant
     (str "\nALREADY GRANTED. "
          (cond
            (:carried? grant)
            (str "A person wrote "
                 (if (:self? grant) "this design" (str "entry " (:seq grant) ", which this design replaces"))
                 ", carrying what they had already granted")
            (:self? grant) "A person approved this design"
            :else (str "A person approved entry " (:seq grant) ", which this design replaces"))
          (when (:note grant) (str " — \"" (:note grant) "\""))
          ".\nThe grant is not evidence for any structural check: derive every one over the\n"
          "whole record as if it were new. What it does answer is a finding whose substance\n"
          "is the decision it made — that a person has not chosen this scope, this cost or\n"
          "this move. It bears on asks, which poses only what the grant does not already cover"
          (if-let [{:keys [added changed dropped clauses]} (:delta grant)]
            (str " — the claims changed since it:"
                 (when (seq added) (str "\n  added: " (str/join ", " added)))
                 (when (seq changed) (str "\n  changed: " (str/join ", " changed)))
                 (str/join (for [[id {g :granted n :now about :about}] clauses]
                             (str "\n    [" id "]"
                                  (str/join (for [c g] (str "\n      granted: " c)))
                                  (str/join (for [c n] (str "\n      now:     " c)))
                                  (when about
                                    (str "\n      about, granted: " (str/join ", " (:granted about))
                                         "\n      about, now:     " (str/join ", " (:now about)))))))
                 (when (seq dropped) (str "\n  dropped: " (str/join ", " dropped)))
                 (when-not (or (seq added) (seq changed) (seq dropped)) " none")
                 "\n"
                 (when (seq clauses)
                   (str "A changed claim's text not shown under it reads as it did when granted:\n"
                        "asks does not reopen it, and calls no claim \"revised\" without naming the\n"
                        "clause that changed.\n"))
                 (when (or (seq added) (seq changed))
                   (str "A question raised by an added or changed claim names that claim by what it\n"
                        "commits to, never by its id, so the\n"
                        "person can tell the change raised it and their grant was complete when given.\n"
                        "When the change since the grant is the one its note ordered, asks says so —\n"
                        "\"the change since entry " (:seq grant) " is the one your note ordered\" — and\n"
                        "poses nothing further about it.\n")))
            ".\n")
          (when-let [earlier (seq (:earlier grant))]
            (str "Earlier grants up the same chain, nearest first. A newer grant is often\n"
                 "narrower and withdraws nothing an older one decided — a scope one of these\n"
                 "settled is still settled unless this record retracts it:\n"
                 (str/join (for [{n :seq :keys [note carried?]} earlier]
                             (str "  entry " n (when carried? " (written by a person, carrying a grant)")
                                  (when note (str " — \"" note "\"")) "\n")))))))
   (when answered-since
     (str "\nANSWERED SINCE. A person was asked at entry " (:seq answered-since) ":\n  "
          (:asks answered-since) "\n"
          "and answered by writing the record rather than a grant:\n"
          (str/join (for [{n :seq kind :kind why :why} (:by answered-since)]
                      (str "  entry " n " (" (name kind) "): " why "\n")))
          "Do not pose that question again; asks poses only what the answer leaves open.\n"
          "The answer is evidence that a person decided, never that a claim holds. A record\n"
          "still inconsistent with it — a claim, an open note or a field written before it —\n"
          "is a defect the amender repairs by carrying the answer in: amend, not ask. A\n"
          "finding that a person has not chosen what the answer chose is answered.\n"))
   (when rewritten
     (str "\nWHY ITS AUTHOR LAST REWROTE THIS RECORD — entry " (:seq rewritten)
          ", replacing entry " (:supersedes rewritten)
          (when (:approved? rewritten) ", which a person approved") ":\n  "
          (:why rewritten) "\n"
          "Every check is still derived over the whole record. This bears on asks: a\n"
          "question the reason already settles is not asked again"
          (if (:approved? rewritten)
            (str ", and with its predecessor\napproved, asks is about what changed since — not "
                 "the worth of the whole change.\n")
            ".\n")))
   (when (seq asked-for)
     (str "\nWHAT A PERSON HAS ASKED FOR SINCE — findings they filed on this workstream:\n"
          (str/join
           (for [{n :seq :keys [round note items]} asked-for]
             (str "  entry " n (when round (str " (round " round ")")) (when note (str ": " note)) "\n"
                  (str/join (for [{:keys [id summary]} items]
                              (str "    - [" id "] " summary "\n"))))))
          "It bears on asks alone and is evidence for no check: scope a person asked for is\n"
          "not a question to put back to them.\n"))
   (when asked
     (str "\nASKED BEFORE, AND NO GRANT WRITTEN SINCE — at entry " (:seq asked)
          (when (:of asked) (str ", of entry " (:of asked))) ":\n  " (:asks asked) "\n"
          (when (and judged (:of asked) (not= judged (:of asked)))
            (str "The record has changed since: this round judges entry " judged ", not entry "
                 (:of asked) ".\nPose only what entry " judged " still leaves open — drop a "
                 "precondition it already\nmeets and any clause it retracted, and never carry "
                 "the question forward word\nfor word.\n"))
          "If what you find can only be repaired by answering this, recommend ask: an\n"
          "amender handed it answers it without the person, and the next round refutes\n"
          "the answer.\n"
          (when owes?
            (str "If you find nothing, recommend proceed: a proceed on this design already stops\n"
                 "for the person, and asks carries this question to them.\n"))))
   (when (seq asked-of)
     (str "\nCLAIMS A PERSON HAS ALREADY BEEN ASKED ABOUT, AND WHAT THEY GRANTED:\n"
          (str/join
           (for [[id earlier] asked-of
                 {n :seq question :asks granted :granted reply :answered} earlier]
             (str "  [" id "] entry " n ": " question "\n"
                  (cond
                    granted
                    (str "      granted at entry " (:seq granted)
                         (when (:note granted) (str " — \"" (:note granted) "\"")) "\n")
                    reply
                    (str "      answered at entry " (:seq reply) " — \"" (:why reply) "\"\n")
                    :else "      no grant written since\n"))))
          "A question about one of these claims is not a new question, and asking for one\n"
          "more exception to it spends the person on the same decision again. If asks has to\n"
          "raise one, cite the entries above and pose it at the CLASS level — what kind of case\n"
          "the claim admits or excludes, named so that one answer also covers the next instance.\n"))))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  design-prompt
  "The decision prompt. Derives what can be derived; hands the rest over.

   `:relation-prior` is `relation-prior`'s: the last decision's rulings, shown beside the ids to rule
   on with whether the record moved under each since."
  [{:keys [design baseline stance intent disputes settled levels prior answers relation-prior]}]
  (let [owes?        (report/owes-a-person? design)
        design       (judged-alone design)
        baseline     (judged-alone baseline)
        relation-ids (relation-yardstick design baseline)]
   (str
   "You are deciding whether a change should be EXECUTED, before any code is\n"
   "written. This is the last cheap moment to say it should not be.\n\n"
   "You do not make the decision. A human does. Your job is to derive\n"
   "everything derivable so that what reaches them is only the judgement that\n"
   "cannot be derived — is this worth doing, now, at this cost.\n\n"
   "Read the code where you need to.\n\n"
   (if intent
     (str "WHAT THE TASK IS FOR — stated before this was designed:\n"
          (:goal intent) "\n"
          (when-let [d (seq (:done-when intent))]
            (str "Done when:\n" (bullets d) "\n"))
          "\n")
     (str "NO STATED INTENT. This design cites none — it predates the intent\n"
          "record. You cannot derive goal-served against a goal nobody wrote\n"
          "down: report that check as UNDERIVABLE, say the record is missing,\n"
          "and do NOT infer the goal from the design. An inferred goal is the\n"
          "one the design serves, so the check could never fail.\n\n"))
   "THE DESIGN" (when (:seq design) (str " — entry " (:seq design))) ":\n"
   "Summary: " (:summary design) "\n"
   "Shape: " (:shape design) "\n"
   "Effort: " (name (:effort design)) "\n"
   ;; A design in the shared model commits to claims, each about the elements it names; the
   ;; shape it replaces lists bare invariants.
   ;; Settled claims follow the checks rather than standing among them, as a baseline
   ;; round shows its settled subjects: the four derivations are still made against the
   ;; whole design.
   (if (contains? design :model)
     (str "Claims — what this design commits to, by id:"
          (model-block (:model (checks-of design (set (keys settled)))) (:holds design))
          (settled-block settled design))
     (str "Invariants:\n" (invariant-lines (:invariants design)) "\n"))
   "Declared against the stance: " (name (get-in design [:standing :relation]))
   (qualifiers (:standing design)) "\n"
   ;; A design written before the baseline event existed carries no :baseline at
   ;; all, and it still qualifies for a round — its absent relation is not
   ;; :within. Saying so is the degrade this area takes everywhere else; calling
   ;; `name` on the nil would throw HERE, while building the prompt, which is
   ;; outside run-round!'s catch and so would take the whole task down.
   (if-let [r (get-in design [:baseline :relation])]
     (str "Declared against the baseline: " (name r)
          (qualifiers (:baseline design)) "\n")
     (str "Declared against the baseline: NOTHING. This design predates the\n"
          "baseline event, so it was judged against no baseline. Weigh its claims\n"
          "on their own merits; the absence is not itself a finding.\n"))
   (when-let [r (seq (:rejected design))]
     (str "\nALREADY REJECTED. A finding re-proposing one of these is ANSWERED,\n"
          "not evidence — unless you can show the stated reason no longer holds:\n"
          (bullets (map #(str (:alternative %) " — because " (:why-not %)) r)) "\n"))
   (if (strata-era? design)
     (str "\nSTRATA THIS CHANGE TOUCHES, floor first — the levels it is written in or\n"
          "adds to, each an element above. It states no cut: how the work is split\n"
          "into layers is drawn from these later, and is not yours to judge.\n"
          (if (seq (:strata design)) (bullets (:strata design)) "  none declared\n") "\n"
          (levels-block levels))
     (when-let [l (seq (:layers design))]
       (str "\nCLAIMED DECOMPOSITION, VERTICAL — one claim per layer, ordered by\n"
            "dependency; all of it lands in one go:\n"
            (bullets (map #(str (:claim %) " (" (name (:mode %)) ")") l)) "\n")))
   (when-let [ph (seq (:phases design))]
     (str "\nCLAIMED DECOMPOSITION, TEMPORAL — each of these is a separate landing\n"
          "the running system has to live in, so judge them as states rather than\n"
          "as steps:\n"
          (bullets (map #(str (:claim %)
                              " — habitable: " (:habitable %)
                              " — exit: " (get-in % [:exit :criterion])) ph))
          "\n"))
   (when-let [rt (seq (:routes design))]
     (str "\nHEALTH OBSERVATIONS ROUTED:\n"
          (bullets (map #(str (:health-id %) " → " (name (:to %))
                              (when (:why %) (str " — " (:why %)))) rt)) "\n"))
   (baseline-block baseline)
   (when stance (str "\nPROJECT STANCE — framing only:\n" stance "\n"))
   "\nDERIVE THESE FOUR. Each is decidable; none is a matter of taste:\n\n"
   "  relation-honest   — does it stand where it says it stands? A design\n"
   "                      declaring :within whose own shape needs a load-bearing\n"
   "                      property to move is not what it says it is. Read this\n"
   "                      against the baseline's MODULES: a change that moves a\n"
   "                      module boundary, or asks a module to stop hiding what\n"
   "                      it hides, is :revisit however small its diff — and\n"
   "                      the baseline line it moves (that module's interface\n"
   "                      or what it hides, or the property) is what the\n"
   "                      :revisit names under :breaks. Say which line.\n"
   (if (seq relation-ids)
     (str "                      Derive it PER BASELINE ID, never as one reading of\n"
          "                      the relation and its note. Rule on EVERY id below in\n"
          "                      relation_rulings: `breaks` when this design stops it\n"
          "                      being true, `stands` with why it is still true.\n"
          "                      Every id ruled breaks belongs under :breaks, whatever\n"
          "                      the relation is called, and an id under :breaks you\n"
          "                      rule stands is a :breaks claiming too much; either\n"
          "                      is relation-honest broken, and nido holds the check\n"
          "                      to your rulings. A note that itself says the design\n"
          "                      moves something the baseline records is that id\n"
          "                      broken, not a lenient reading to hold.\n"
          (relation-ids-lines relation-ids "                        " nil)
          (when (seq relation-prior)
            (str "                      THE LAST DECISION RULED THEM SO, each marked by whether\n"
                 "                      the record moved under it since — its baseline\n"
                 "                      statement, this design's text naming it, or :breaks\n"
                 "                      moving away from that ruling. Rule afresh; a ruling\n"
                 "                      that differs from the last says in `cause` what moved\n"
                 "                      and makes the last one wrong. On an UNMOVED id a\n"
                 "                      reversal with no cause is not taken: the last ruling\n"
                 "                      stands, and the reversal is reported as yours.\n"
                 (relation-ids-lines (filterv (comp relation-prior second) relation-ids)
                                     "                        "
                                     (update-vals relation-prior
                                                  (fn [{:keys [moved? reason] :as p}]
                                                    (assoc p :reason (str (if moved? "(moved since) " "(unmoved) ")
                                                                          reason))))))))
     (str "                      The baseline names no ids to rule on one by one, so\n"
          "                      relation_rulings stays empty; derive it from the\n"
          "                      relation and what the baseline states.\n"))
   "  goal-served       — does it serve the goal, and ONLY the goal? Under-\n"
   "                      serving is easy to see. OVER-serving is the common\n"
   "                      one: the goal plus a good deal more, every piece\n"
   "                      individually defensible. Check whether a strictly\n"
   "                      smaller design would do, including one already\n"
   "                      rejected for a reason that no longer holds.\n"
   (if (strata-era? design) stratified-check decomposable-check)
   "  routing-coherent  — do the routed health observations keep this ONE story?\n"
   "                      Observations routed to fix-here that belong to a\n"
   "                      different story make this two changes.\n\n"
   "Then recommend:\n"
   "  proceed  — nothing derivable blocks it.\n"
   "  ask      — stop for a person. What you found can only be repaired by a\n"
   "             decision the record may not make for itself: the scope, what the\n"
   "             intent means, whether it is worth its cost. An amender handed it\n"
   "             settles it by guessing — narrowing a goal the person wanted, or\n"
   "             reversing a decision the record states. Findings are optional.\n"
   "             List every finding anyway, and mark for_person the ones only the\n"
   "             question repairs. The rest are repaired before the person is\n"
   "             asked, so a derivable defect you leave out is one they are\n"
   "             asked over.\n"
   "             A finding whose only repair is outside the record is for_person:\n"
   "             filing or naming a follow-up ref, deciding scope, editing the\n"
   "             intent. So is one about a claim your asks decides — name every\n"
   "             such claim id in asks_about.\n"
   "  amend    — a derivable defect in the record itself, which the record can\n"
   "             repair without anyone deciding anything new.\n"
   "             Which record contradicts a recorded decision decides between the\n"
   "             two. A DESIGN contradicting what the record already decided or\n"
   "             rejected is derivable: amend it back to the decision, for_person\n"
   "             false — never ask the person to reverse a decision they made. The\n"
   "             INTENT contradicting one is the person's: ask.\n"
   "             When the repair is a choice between strengthening a guarantee and\n"
   "             narrowing it, and an earlier decision on this design refuted the\n"
   "             strengthened form, the choice left is the narrowing, and that is\n"
   "             scope: ask.\n"
   "  recut    — the decomposition does not hold.\n"
   "  resurvey — the PREMISE is wrong, not the commitment. A design can be sound\n"
   "             on a baseline that was not. Redesign and re-survey are different\n"
   "             instructions and saying the wrong one is worse than saying\n"
   "             nothing.\n\n"
   "Every finding MUST cite what it falsifies. A finding that cites nothing is\n"
   "not a finding. A finding about one claim names its id in claim-id. A finding\n"
   "that shows one of the four broken names it in check; one that bears on none\n"
   "of them leaves check empty — a record contradicting itself or a claim it\n"
   "rests on is a defect under no derivation, and it is repaired like any other.\n\n"
   ;; A host fact read off a man page or a package page describes SOME host, not this one; one
   ;; such finding broke goal-served on a tool that was installed, and cost a round to withdraw.
   "A finding that a tool or command is MISSING on this host, or BEHAVES differently\n"
   "on it, cites what you observed on the host — the output of running it here\n"
   "(`command -v <tool>`, `<tool> --version`). Documentation, a man page or a\n"
   "package page says what is true of some host, not of this one; it is not\n"
   "evidence for a host finding. Cannot run it? Leave the claim unchecked with why.\n\n"
   compound-clause-rule
   "Populate confirmed with the claims you checked and found to hold — each by its\n"
   "id, the bracketed slug without the brackets, with the file:line references you\n"
   "read, and at_this_tree: `holds` when the code you read already makes the claim\n"
   "true, `owed` when it is a sound commitment the code you read does not meet yet.\n"
   "A claim about what this change will build is owed until it is built — and the\n"
   "tree you are reading may already hold the change, so look rather than assume.\n"
   "Leave confirmed empty when the design lists invariants with no ids. Ids, not\n"
   "sentences: a confirmation worded differently each round cannot be matched to\n"
   "the claim it is about.\n\n"
   "RULE ON EVERY CLAIM YOU ARE ASKED TO CHECK — the claims above, and none listed\n"
   "as outside this round's checks. Each one ends up confirmed, named by a finding,\n"
   "or in unchecked with why it cannot be checked here. A claim left without a\n"
   "ruling is asked again, and a proceed over it does not stand.\n\n"
   "asks is REQUIRED whatever you recommend: state what the human still has to\n"
   "answer, with everything you derived already taken off the table — and with it\n"
   "what the record's open notes state is decided and what a grant or a person's\n"
   "answer already covers.\n"
   "ONE question per decision they must make, each in one or two sentences, worded\n"
   "as what changes for the users or the operator — never as a claim id or the\n"
   "mechanism. A person who must learn the record's vocabulary to follow a question\n"
   "answers one they did not understand.\n"
   "Gather EVERY goal or scope gap the record raises into this one asks, a deferral\n"
   "it states openly included: a part of the goal the record says it leaves for\n"
   "later is scope a person grants now. A gap you leave out is a second interruption\n"
   "on the next run.\n"
   "Where the record settles a stratified or relation finding by recording a known\n"
   "violation rather than removing it, asks names how many dependencies it keeps\n"
   "and every cycle they form — \"accounted for\" grants a count the person never saw.\n"
   "Never answer it yourself. On a proceed it is unconditional: a question opening\n"
   "\"after X is done\" is met by this record — drop the condition — or names a repair,\n"
   "and then the recommendation is not proceed. A correction the record needs is a\n"
   "finding for the amender, never asks: asks holds only what a person must decide.\n"
   "On amend, recut or resurvey, too, asks carries no \"after the corrections…\"\n"
   "preamble: the repair is your findings', and it is shown to the person beside\n"
   "asks already. Pose the scope or worth question so it stands whatever the repair\n"
   "writes — a person can grant that now, and need not wait for a record that is\n"
   "still changing. Name a correction in asks only when the question depends on it.\n"
   (answered-block owes? (:seq design) (:open design) answers)
   (prior-findings-block (apply dissoc prior (keys settled)))
   (disputes-block disputes)
   (level-reminder :commitment))))

(def ^:private unruled-pass
  "Why a re-ask asks, when the decision left ids unruled."
  (str "THIS PASS ASKS ONE THING. The decision on this design is already made, and\n"
       "it ruled on relation-honest per baseline id — but not on every id. Rule on\n"
       "EACH id below, exactly as the relation-honest instruction above says, and\n"
       "answer relation_rulings only; every other field above is not asked here.\n"
       "Still to rule on:\n"))

(defn- contradicted-pass
  "Why a re-ask asks, when the decision called relation-honest held over rulings :breaks does not
   match. `breaks` is what :breaks lists."
  [breaks]
  (str "THIS PASS ASKS ONE THING. The decision on this design is already made, and\n"
       "it contradicts itself: it called relation-honest HELD — :breaks names exactly\n"
       "what this design stops being true — and ruled the ids below so that :breaks\n"
       "does not match them, ruled breaks and not listed, or listed and ruled stands.\n"
       ":baseline :breaks lists: " (if (seq breaks) (str/join ", " breaks) "nothing") "\n"
       "One of the two readings is wrong. Rule on EACH id below again, exactly as the\n"
       "relation-honest instruction above says, and answer relation_rulings only. A\n"
       "ruling you keep says in its reason why the held reading was wrong.\n"
       "To rule on again:\n"))

(defn- relation-reask-prompt
  "The decision prompt over the same records, closed by `pass` — why this pass asks — and a block
   asking only for rulings on `missing`, ids of `subjects`, beside the `rulings` the decision made
   on the rest. The whole prompt rather than the ids alone, because a ruling is a reading of the
   design against the baseline, and a judge launched fresh has neither in front of it otherwise."
  [opts subjects missing rulings pass]
  (let [missing (set missing)]
    (str (design-prompt opts)
         "\n\n" pass
         (relation-ids-lines (filterv (comp missing second) subjects) "  " nil)
         (when (seq rulings)
           (str "Already ruled, for context — not to rule again:\n"
                (relation-ids-lines (remove (comp missing second) subjects) "  "
                                    (into {} (map (juxt :id identity)) rulings)))))))

;; ── Running a round ─────────────────────────────────────────────────────────

(def ^:private schemas
  "Each round kind's output schema, read once as this namespace loads.

   Load time, not round time, because the prompt that describes the answer and
   the parser that reads it are fixed when this namespace loads. Read per round,
   a schema is whatever the disk holds that minute, and an answer shaped by a
   schema newer than its parser is dropped without an error — the ledger then
   records a judge that confirmed nothing."
  (update-vals {:stratum-reading "review/stratum_reading_schema.json"
                :baseline-review "review/baseline_review_schema.json"
                :design-decision "review/design_decision_schema.json"}
               #(slurp (jio/resource %))))

(defn- exactly-these-rulings
  "`rulings` — a relation_rulings array schema — admitting exactly `ids`: each item's id one of them,
   and as many items as there are ids. Empty `ids` admits an empty array, since an enum of nothing is
   no schema a provider accepts.

   The count and the enum together still let one id be ruled twice and another not at all — strict
   output has no uniqueItems — so what the judge returns is checked by id afterwards
   (`unruled-relation-ids`), and this only makes the omission the unlikely answer rather than the free one."
  [rulings ids]
  (let [n (count ids)]
    (cond-> (assoc rulings :minItems n :maxItems n)
      (pos? n) (assoc-in [:items :properties :id :enum] (vec ids)))))

(defn- check-names
  "`derivations` in the answer's underscore spelling, as a schema enum lists them."
  [derivations]
  (mapv #(str/replace (name %) "-" "_") derivations))

(defn- design-decision-schema
  "The decision round's output schema for `design`, ruled on per id over `subjects`
   (`relation-yardstick`): relation_rulings must name every one of them, and a check — on the
   checks list or on a finding — must be one of the four the design's era is judged by
   (`report/derivations-of`), the same four `design-prompt` defines.

   Generated per round because the fixed schema let a judge told to rule on seventeen ids return
   the six claims and skip every module and stratum, round after round — and an id left unruled
   stops the proceed (`relation-held`), so a lineage whose judge always skips them can never proceed.
   The check enum is per round for the same reason: offered `decomposable` beside a prompt that
   defines only `stratified`, a judge answers the check it was never asked, and a `broken` on it
   reads as the advisory check `report/proceeds?` waives."
  [design subjects]
  (let [checks (check-names (report/derivations-of design))]
    (-> (json/parse-string (schemas :design-decision) true)
        (update-in [:properties :relation_rulings] exactly-these-rulings (mapv second subjects))
        (assoc-in [:properties :checks :items :properties :check :enum] checks)
        (assoc-in [:properties :findings :items :properties :check :enum] (conj checks ""))
        json/generate-string)))

(defn- relation-reask-schema
  "The output schema for re-asking a judge only the relation ids it left unruled: one ruling each,
   in the decision schema's ruling shape."
  [ids]
  (let [decision (json/parse-string (schemas :design-decision) true)]
    (json/generate-string
     {:type                 "object"
      :additionalProperties false
      :required             ["relation_rulings"]
      :properties           {:relation_rulings (exactly-these-rulings
                                                (get-in decision [:properties :relation_rulings])
                                                ids)}})))

(def ^:private derivation-keys
  "Every derivation a round may answer about, of either era. What an answer names is kept only if it
   is one of these; which of them a round asks about is the record's era."
  (into #{} (concat report/derivations report/strata-derivations)))

(defn- slug
  "An id as the judge wrote it, stripped of the brackets the prompt renders around every id. A judge
   shown `[engine-names-no-stage] the engine …` cites it back with them, and an id that is sometimes
   bracketed and sometimes not is no identity at all."
  [x]
  (-> (str x) str/trim (str/replace "[" "") (str/replace "]" "")))

(defn- parse-confirmed
  "A judge's confirmed list as `{:confirmed [id …] :checked-at {id [file:line …]} :owed [id …]
   :owed-at {id [file:line …]}}`.

   An entry is `{id evidence at_this_tree}`; a bare string is an id with no evidence, which is what an
   answer in the older shape gives, and it confirms nothing that settles. An id the judge says the
   code does not meet yet is :owed rather than :confirmed — a sound commitment, not a fact of the
   tree it read — and what it read to say so is :owed-at, kept because an owed ruling can reverse
   an earlier finding (`with-readings`' :overturns) and the reversal is only as good as its evidence."
  [raw]
  (reduce (fn [acc e]
            (let [m?  (map? e)
                  id  (slug (if m? (:id e) e))
                  ev  (when m? (into [] (comp (map str) (remove str/blank?)) (:evidence e)))
                  at  (when m? (str (or (:at_this_tree e) (:at-this-tree e))))]
              (cond
                (str/blank? id) acc
                (= "owed" at)   (cond-> (update acc :owed (fnil conj []) id)
                                  (seq ev) (assoc-in [:owed-at id] ev))
                :else           (cond-> (update acc :confirmed (fnil conj []) id)
                                  (seq ev) (assoc-in [:checked-at id] ev)))))
          {}
          (distinct (if (sequential? raw) raw []))))

(defn- parse-unchecked
  "The subjects a judge said it could not check here, each with its reason; one per id."
  [raw]
  (into [] (comp (keep (fn [e] (when (map? e)
                                 (let [id (slug (:id e))]
                                   (when-not (str/blank? id) {:id id :reason (str (:reason e))})))))
                 (distinct))
        (if (sequential? raw) raw [])))

(defn- with-ruling
  "`record` carrying the judge's confirmed and unchecked lists from the answer `m`, as the round
   filters them next (`rule`)."
  [record m]
  (let [{:keys [confirmed checked-at owed owed-at]} (parse-confirmed (:confirmed m))
        unchecked (parse-unchecked (:unchecked m))]
    (cond-> record
      (seq confirmed)  (assoc :confirmed (vec (distinct confirmed)))
      (seq checked-at) (assoc :checked-at checked-at)
      (seq owed)       (assoc :owed (vec (distinct owed)))
      (seq owed-at)    (assoc :owed-at owed-at)
      (seq unchecked)  (assoc :unchecked unchecked))))

(declare refuted-ids)

(defn- rule
  "A parsed judgement, its ruling held to what the round asked: `checks` are the subjects put to the
   judge, `asked` those of them it owes a ruling on.

   A confirmation counts only for a check, only with the file:line it was read at, and only when no
   finding of the same judgement refutes that id (`refuted-ids`) — a judge that both found against a
   subject and confirmed it has found. A gap naming the id refutes nothing, so the confirmation
   stands beside it. An id outside the checks is dropped whatever the judge said of it: it was
   shown as settled, or it is no subject of this record. What is left of `asked` without a ruling —
   confirmed, named by a finding, :owed, or declared :unchecked — is :unruled, which is what stops the judgement
   holding (`nido.coordinator.report/review-holds?`, `nido.coordinator.report/proceeds?`).

   `breaks` is what the design's `:baseline :breaks` lists. A check named there that the judge
   confirmed holds is :owed, never confirmed: what it read is the code the design says it stops
   being true, and a confirmation would end that subject's refutation run (`refuted-running`) on a
   fact about the tree before the change. What the judge read to confirm it moves to :owed-at with it."
  ([result checks asked] (rule result checks asked nil))
  ([result checks asked breaks]
    (let [found     (into #{} (mapcat refuted-ids) (:findings result))
          named     (into #{} (keep :claim-id) (:findings result))
          held?     #(and (contains? checks %) (not (found %)))
          broken    (into #{} (map slug) breaks)
          confirmed (filterv #(and (held? %) (not (broken %)) (seq (get-in result [:checked-at %])))
                             (:confirmed result))
          owed      (filterv held? (distinct (concat (:owed result) (filter broken (:confirmed result)))))
          unchecked (filterv #(held? (:id %)) (:unchecked result))
          ruled     (into (set confirmed) (concat owed (map :id unchecked) named))
          unruled   (vec (sort (remove ruled asked)))
          owed-at   (select-keys (merge (select-keys (:checked-at result) (filter broken owed))
                                        (:owed-at result))
                                 owed)]
      (cond-> (dissoc result :confirmed :checked-at :owed :owed-at :unchecked :unruled)
        (seq confirmed) (assoc :confirmed confirmed
                               :checked-at (select-keys (:checked-at result) confirmed))
        (seq owed)      (assoc :owed owed)
        (seq owed-at)   (assoc :owed-at owed-at)
        (seq unchecked) (assoc :unchecked unchecked)
        (seq unruled)   (assoc :unruled unruled)))))

(defn- normalize-findings
  "`raw` findings with their cites, kept only if they cite something. A finding's :check is kept
   only if it is one of `asked` (default: any derivation of either era); a finding under any other
   check is kept check-less, since the defect it names may be real whatever check it was filed under."
  ([raw] (normalize-findings raw derivation-keys))
  ([raw asked]
   (into [] (keep (fn [f]
                    (let [cites (into [] (remove str/blank?) (map str (:cites f)))]
                      (when (seq cites)
                        (cond-> {:cites cites :claim (str (:claim f))}
                      (not (str/blank? (str (:claim-id f))))
                      ;; Stripped of the brackets the prompt renders around an id.
                      ;; A judge shown `[engine-names-no-stage] the engine …` cites
                      ;; it back with them, and an id that is sometimes bracketed
                      ;; and sometimes not is no identity at all.
                      (assoc :claim-id (str/replace (str/trim (str (:claim-id f)))
                                                    #"^\[|\]$" ""))
                      ;; Only one of the derivations the round asked. A check named in the answer's
                      ;; underscore spelling is the same check; anything else ties the finding
                      ;; to nothing, and is dropped rather than kept as a check the round never derived.
                      (some #{(keyword (str/replace (str/trim (str (:check f))) "_" "-"))}
                            asked)
                      (assoc :check (keyword (str/replace (str/trim (str (:check f))) "_" "-")))
                          (seq (:evidence f))
                          (assoc :evidence (mapv str (:evidence f)))
                          (true? (:for_person f))
                          (assoc :for-person true))))))
         raw)))

(def ^:private derivation-names
  (into #{} (map name) derivation-keys))

(defn- normalize-blocked
  "An insufficient finding, kept only if it names a derivation it blocks and what
   the baseline would have to say. Those two are the bound: without them
   `insufficient` is the unbounded bucket `underscoped` was, and the round that
   produced twenty-four non-repeating findings would simply produce them under a
   new name."
  [raw]
  (into []
        (keep (fn [f]
                (let [blocks (str (:blocks f))
                      cites  (into [] (remove str/blank?) (map str (:cites f)))]
                  ;; "none" is what a FALSIFIED finding carries — strict output
                  ;; mode requires every property on every finding, so the field
                  ;; exists on both kinds and this is where it means something.
                  (when (and (derivation-names blocks)
                             (seq cites)
                             (not (str/blank? (str (:needs f)))))
                    (cond-> {:blocks (keyword blocks)
                             :cites  cites
                             :claim  (str (:claim f))
                             :needs  (str (:needs f))}
                      (seq (:evidence f)) (assoc :evidence (mapv str (:evidence f)))
                      (not (str/blank? (slug (:claim-id f)))) (assoc :claim-id (slug (:claim-id f)))))))
              raw)))

(defn ^{:malli/schema [:=> [:cat :string :any] :map]}
  parse-baseline-review
  "Codex JSON -> a :baseline-review ledger record, or nil when the answer is
   unusable. nil is a non-answer, never a fabricated :sufficient — the caller
   records nothing rather than inventing trust."
  [json-str baseline-seq]
  (try
    (let [m (json/parse-string json-str true)
          v (keyword (str (:verdict m)))]
      (when (#{:sufficient :falsified :insufficient} v)
        (let [findings (case v
                         :falsified    (normalize-findings (:findings m))
                         :insufficient (normalize-blocked (:findings m))
                         [])]
          ;; A non-sufficient verdict with nothing usable behind it is exactly the
          ;; theatre this round guards against — read it as no answer.
          (when (or (= :sufficient v) (seq findings))
            (cond-> (with-ruling {:format :baseline-review
                                  :verdict v
                                  :baseline-seq baseline-seq
                                  :reason (str (:reason m))}
                                 m)
              (not= :sufficient v) (assoc :findings findings))))))
    (catch Exception _ nil)))

(defn- parse-rulings
  "A judge's relation_rulings as `[{:id :ruling :reason}]`, brackets off, with :cause when the judge
   gave one for reversing its previous ruling. One ruling per id — the first, so a repeated id cannot
   overturn itself in one answer — and only `breaks` or `stands`."
  [raw]
  (->> (if (sequential? raw) raw [])
       (keep (fn [r]
               (let [id (slug (:id r))
                     k  (keyword (str (:ruling r)))]
                 (when (and (not (str/blank? id)) (#{:breaks :stands} k))
                   (cond-> {:id id :ruling k :reason (str (:reason r))}
                     (not (str/blank? (str (:cause r)))) (assoc :cause (str/trim (str (:cause r)))))))))
       (reduce (fn [acc r] (if (some #(= (:id r) (:id %)) acc) acc (conj acc r))) [])))

(defn- id-pattern
  "Matches `id` written as a word in prose: case aside, and with its separators as hyphens,
   underscores or spaces, because a judge or an amender writes `derivative-cache` as `derivative
   cache` as often as not."
  [id]
  (re-pattern (str "(?i)(?<![\\w-])"
                   (str/join "[-_ ]" (map #(java.util.regex.Pattern/quote %) (str/split id #"[-_ ]+")))
                   "(?![\\w-])")))

(defn- health-ids
  "The ids of the health observations `baseline` records and `design` routes, brackets off — what a
   finding about routing cites."
  [design baseline]
  (into #{} (comp (map slug) (remove str/blank?))
        (concat (keep :id (:health baseline)) (keep :health-id (:routes design)))))

(defn- routing-attributed
  "`finding` filed under routing-coherent when it cites one of `health` (`health-ids`) and the judge
   filed it under no check or under that one, with any claim-id other than the observation's own
   dropped.

   A judge citing a route beside the claim whose code the route runs through has found the route
   wrong, not the claim. Left on it, the claim-id counts as that claim refuted — in `rule`, in the
   refutation runs and in the figures — while the same decision confirmed it. A finding under any
   other check keeps it: the judge said which question the defect answers."
  [health finding]
  (let [cited? (fn [id] (some #(re-find (id-pattern id) %)
                              (cons (str (:claim-id finding)) (:cites finding))))]
    (if (and (contains? #{nil :routing-coherent} (:check finding)) (some cited? health))
      (cond-> (assoc finding :check :routing-coherent)
        (not (health (:claim-id finding))) (dissoc :claim-id))
      finding)))

(defn- off-held-checks
  "`findings` with each one filed under a check `checks` rules :held made check-less, the check it
   was filed under kept as :filed-under.

   The judge said both that the check holds and that this defect breaks it. Kept, the ledger reads
   as both; dropped, a defect that may be real goes unrepaired. Check-less is what the prompt asks
   of a finding that breaks none of the checks, and the ruling is the judge's considered answer to
   the check where the filing is one line of one finding."
  [findings checks]
  (let [held (into #{} (comp (filter #(= :held (:status %))) (map :check)) checks)]
    (mapv #(if (held (:check %)) (-> % (dissoc :check) (assoc :filed-under (:check %))) %)
          findings)))

(def ^:private quote-words
  "How many consecutive words a cite must share with the intent's text to be quoting it. Long enough
   that a phrase every record in an area uses is not a quote; short enough that a cite prefixed with
   a label of its own (`Task done condition: …`) or cut with an ellipsis still is."
  6)

(defn- words
  [s]
  (vec (re-seq #"[\p{L}\p{N}]+" (str/lower-case (str s)))))

(defn- quotes?
  "Whether `cite` quotes `passage`: shares `quote-words` consecutive words with it, or holds the
   whole of a passage shorter than that."
  [cite passage]
  (let [c (words cite) p (words passage)
        n (min quote-words (count p))
        grams (fn [ws] (into #{} (partition n 1 ws)))]
    (and (pos? n) (>= (count c) n)
         (boolean (some (grams p) (partition n 1 c))))))

(defn- person-marked
  "On an :ask, `findings` with every one only a person can repair marked :for-person — beside the
   judge's own marks, never instead of them.

   Two cases need no judgement, and the judge marked both derivable often enough to cost a round
   each: a finding on a claim the person's answer decides (`about`, the ids the judge named for
   :asks), which an amender would settle by deciding the question; and one citing the `intent`'s
   goal or a done-when (`quotes?`), whose remainder is an edit of a record no amender may write. The
   second also takes a derivable finding that quotes the goal to show the design under-serves it —
   on an :ask, a person is being asked already, and the next run re-derives anything they leave."
  [findings about intent]
  (let [passages (into [] (remove str/blank?) (cons (:goal intent) (:done-when intent)))]
    (mapv (fn [f]
            (cond-> f
              (or (contains? about (:claim-id f))
                  (some (fn [c] (some #(quotes? c %) passages)) (:cites f)))
              (assoc :for-person true)))
          findings)))

(def ^:private decomposition-checks
  "The checks that rule on the decomposition — what a :recut asks to redo."
  #{:decomposable :stratified :routing-coherent})

(defn ^{:malli/schema [:=> [:cat :string :any [:set :keyword] [:? [:set :string]] [:? :any]] :map]}
  parse-design-decision
  "Codex JSON -> a :design-decision ledger record, or nil when unusable.

   `asked` is the checks the round's prompt defined (`report/derivations-of` its design). A
   check outside it is one the judge was never asked: it is dropped from :checks and named under
   :unasked-checks, and a finding filed under it is kept check-less. Kept, it would reach the
   ledger, the figures and `report/proceeds?` — where a `broken` on a strata-era `decomposable`
   reads as the advisory check, and waves through the defect it carries.

   `health` is the health ids the design's record names (`health-ids`); a finding citing one is
   routing-coherent's (`routing-attributed`). A finding under a check the same answer rules held is
   made check-less (`off-held-checks`). A :recut with no decomposition check broken is an :amend,
   the judge's word kept as :judge-recommended: an amender told the decomposition is wrong when
   only the goal broke redraws the strata to repair what a record edit would.

   `intent` is the intent the round's prompt stated. On an :ask it and the answer's `asks_about`
   mark findings for the person whatever the judge marked (`person-marked`)."
  [json-str design-seq asked & [health intent]]
  (try
    (let [m (json/parse-string json-str true)
          named  (keep (fn [c]
                         (let [k (keyword (str/replace (str (:check c)) "_" "-"))]
                           (when (derivation-keys k) [k c])))
                       (:checks m))
          unasked (into [] (comp (map first) (remove asked) (distinct)) named)
          checks (into [] (keep (fn [[k c]]
                                    (when (asked k)
                                      {:check  k
                                       :status (let [st (keyword (str (:status c)))]
                                                 (if (#{:held :broken :underivable} st)
                                                   st
                                                   ;; a judge that answered in the
                                                   ;; old shape is still answering
                                                   (if (:held c) :held :broken)))
                                       :note   (str (:note c))})))
                       named)
          findings (off-held-checks (mapv (partial routing-attributed (set health))
                                          (normalize-findings (:findings m) asked))
                                    checks)
          said     (keyword (str (:recommend m)))
          r        (if (and (= :recut said)
                            (not-any? #(and (decomposition-checks (:check %)) (= :broken (:status %)))
                                      checks))
                     :amend
                     said)
          rulings  (parse-rulings (:relation_rulings m))
          asks     (str (:asks m))]
      ;; An :ask needs no finding: what it hands on is the question, and a doubt the build must not
      ;; start without is one whether or not it breaks a check.
      (when (and (#{:proceed :amend :recut :resurvey :ask} r)
                 (seq checks)
                 (not (str/blank? asks))
                 (or (#{:proceed :ask} r) (seq findings)))
        (cond-> (with-ruling {:format :design-decision
                              :recommend r
                              :design-seq design-seq
                              :reason (str (:reason m))
                              :checks checks
                              :asks asks}
                             m)
          ;; Only an ask has a question for a finding to be the person's: on any other
          ;; recommendation every finding is the amender's, whatever the judge marked.
          (and (not= :proceed r) (seq findings))
          (assoc :findings (if (= :ask r)
                             (person-marked findings
                                            (into #{} (comp (map slug) (remove str/blank?))
                                                  (:asks_about m))
                                            intent)
                             (mapv #(dissoc % :for-person) findings)))
          (seq rulings)                          (assoc :relation-rulings rulings)
          (seq unasked)                          (assoc :unasked-checks unasked)
          (not= said r)                          (assoc :judge-recommended said))))
    (catch Exception _ nil)))

(defn- mismatched
  "The ids of `ids` whose ruling in `ruled` (by id) disagrees with `breaks`, the slugs :breaks lists:
   `[omitted overclaim]` — ruled breaks and not listed, and listed but ruled stands. An id not ruled
   is in neither."
  [ruled breaks ids]
  [(filterv #(and (= :breaks (get-in ruled [% :ruling])) (not (breaks %))) ids)
   (filterv #(and (= :stands (get-in ruled [% :ruling])) (breaks %)) ids)])

(defn- mentions
  "Every part of `x` that names `id` (`id-pattern`), as a sorted set of strings: a map carrying an
   :id — a claim, an element — whole, and of any other string the sentences that name it. Sentences
   rather than whole strings, because :shape is one paragraph and a rewrite of one sentence in it is
   not a rewrite of what it says about every id."
  [x id]
  (let [pat (id-pattern id)]
    (letfn [(walk [x]
              (cond (and (map? x) (contains? x :id)) (when (re-find pat (pr-str x)) [(pr-str x)])
                    (map? x)    (mapcat walk (vals x))
                    (coll? x)   (mapcat walk x)
                    (string? x) (filter #(re-find pat %) (str/split x #"(?<=[.;])\s+|\n+"))
                    :else       nil))]
      (into (sorted-set) (walk x)))))

(defn- baseline-statement
  "What `baseline` states about its id `id`: every claim, property, module, element or stratum it
   lists under that id, as a sorted set of strings."
  [baseline id]
  (let [m (:model baseline)]
    (into (sorted-set)
          (concat (keep #(when (= id (slug (:id %))) (pr-str %))
                        (concat (:claims m) (:elements m) (:load-bearing baseline) (:modules baseline)))
                  (keep #(when (= id (slug %)) id) (:strata baseline))))))

(defn- relation-prior
  "The previous decision's relation rulings, by id, each `{:ruling :reason :moved? bool}`: whether
   the record that ruling read moved under that id before the record being ruled on now. `then` and
   `now` are each `[design baseline]`; `rulings` the earlier decision's :relation-rulings.

   An id moved when the baseline's statement of it changed, when the design's text naming it changed
   (`mentions`, :breaks and the citation's bookkeeping aside), or when its :breaks membership moved
   AWAY from the earlier ruling. Moving toward it is the amender doing what that ruling asked, and is
   no ground for reversing it: counted as a move, every ruling an amender obeyed could be undone the
   next round for free, which is the ping-pong this exists to stop."
  [rulings [then then-baseline] [now now-baseline]]
  (let [text     #(-> (ws/unstamp %) (dissoc :supersedes) (update :baseline dissoc :breaks))
        listed   #(into #{} (map slug) (get-in % [:baseline :breaks]))
        then-txt (text then)
        now-txt  (text now)
        then-in  (listed then)
        now-in   (listed now)]
    (into {}
          (for [{:keys [id ruling reason]} rulings]
            [id {:ruling ruling
                 :reason (str reason)
                 :moved? (boolean
                          (or (not= (baseline-statement then-baseline id) (baseline-statement now-baseline id))
                              (not= (mentions then-txt id) (mentions now-txt id))
                              (and (not= (contains? then-in id) (contains? now-in id))
                                   (not= (contains? now-in id) (= :breaks ruling)))))}]))))

(defn- relation-prior!
  "`relation-prior` for `design`, ruled on against `baseline`, from the newest decision on the
   workstream and the design it read. nil when there is none, or it ruled on no id."
  [project ws-id design baseline]
  (let [last (ws/latest-entry project ws-id :design-decision)
        then (when-let [n (:design-seq last)] (ws/entry-at-seq project ws-id n))]
    (when (and then (seq (:relation-rulings last)))
      (relation-prior (:relation-rulings last)
                      [then (stages/cited-baseline [project ws-id] then)]
                      [design baseline]))))

(defn- held-to-prior
  "`decision` with every ruling that reverses `prior` (`relation-prior`) on an id the record did not
   move, and gives no :cause, replaced by the prior ruling — and the reversal not taken kept under
   :relation-reversals.

   The ruling is the judge's reading of a record, and a record that did not move cannot be read two
   ways by a judge with both readings in front of it. Taken, a reversal on an unmoved id is judge
   variance that `relation-held` turns into an amend round, and the next round's variance undoes it.
   Not taken, it is still recorded: a reversal is the judge's inconsistency, and a reader of the run
   is owed it."
  [decision prior]
  (let [unexplained (fn [{:keys [id ruling cause]}]
                      (when-let [p (get prior id)]
                        (and (not= ruling (:ruling p)) (not (:moved? p)) (str/blank? cause))))
        rulings     (:relation-rulings decision)
        reversed    (filterv unexplained rulings)
        already     (into #{} (map :id) (:relation-reversals decision))]
    (if (empty? reversed)
      decision
      (-> decision
          (assoc :relation-rulings
                 (mapv #(if (unexplained %)
                          (select-keys (assoc (get prior (:id %)) :id (:id %)) [:id :ruling :reason])
                          %)
                       rulings))
          (update :relation-reversals (fnil into [])
                  (comp (remove (comp already :id)) (map #(select-keys % [:id :ruling :reason])))
                  reversed)))))

(defn- relation-misread
  "The ids `breaks` lists that `decision`'s relation-honest reading says are absent from it: a
   sentence of the check's note, or of a finding filed under it, naming the id beside words of
   absence. Only when the check is broken — a held one claims no omission."
  [decision breaks]
  (when (some #(and (= :relation-honest (:check %)) (= :broken (:status %))) (:checks decision))
    (let [absent    #"(?i)\babsent\b|\bomit|\bmissing\b|\bnot (?:listed|named|included|under|in)\b|\bleaves? out\b|\black|does not (?:list|name|include)"
          sentences (->> (concat (keep #(when (= :relation-honest (:check %)) (:note %)) (:checks decision))
                                 (keep #(when (= :relation-honest (:check %)) (:claim %)) (:findings decision)))
                         (mapcat #(str/split (str %) #"(?<=[.;])\s+|\n+"))
                         (filter #(re-find absent %)))]
      (filterv (fn [id] (some #(re-find (id-pattern id) %) sentences)) breaks))))

(defn- relation-held
  "`decision` held to its own per-id rulings on relation-honest, against the design's `:breaks`.

   An id it never ruled on is :relation-unruled, and stops the proceed as an :unruled claim does.
   Its own key, because the same id can also be a design element `rule` read — confirmed, :owed or
   :unruled there — and one list holding both questions cannot say which one an id left open. An id ruled `breaks` that :breaks omits, or named under :breaks and ruled
   `stands`, is relation-honest broken whatever the judge called the check — the ruling and the
   check are its two answers to one question, and the ruling is the one made id by id. A :proceed
   over such a mismatch becomes :amend, with one finding listing the ids and the judge's reasons.

   Rulings and :breaks agreeing, a relation-honest the judge broke for omitting an id :breaks already
   lists (`relation-misread`) keeps its status — what else it says is the judge's — but its note says
   the record lists the id, so the amender disputes the line rather than re-adding what is there.

   `subjects` empty — a design with no baseline relation, or a baseline naming no ids — leaves the
   decision as it is, and so does a relation-honest the judge could not derive: its rulings answer a
   check with no yardstick, and holding them would turn the :underivable it reported into :unruled."
  [decision subjects breaks]
  (if (or (empty? subjects)
          (some #(and (= :relation-honest (:check %)) (= :underivable (:status %))) (:checks decision)))
    decision
    (let [ruled     (into {} (map (juxt :id identity)) (:relation-rulings decision))
          breaks    (into #{} (map slug) breaks)
          ids       (mapv second subjects)
          unruled   (remove ruled ids)
          [omitted overclaim] (mismatched ruled breaks ids)
          wrong     (concat omitted overclaim)
          misread   (when (and (empty? wrong) (empty? unruled))
                      (relation-misread decision (filter breaks ids)))
          why       (str "ruled per baseline id"
                         (when (seq omitted)
                           (str "; ruled breaks, absent from :breaks: " (str/join ", " omitted)))
                         (when (seq overclaim)
                           (str "; under :breaks, ruled standing: " (str/join ", " overclaim))))
          check     {:check :relation-honest :status :broken :note why}
          checks    (if (some #(= :relation-honest (:check %)) (:checks decision))
                      (mapv #(if (= :relation-honest (:check %))
                               (update check :note (fn [n] (str (:note %) " — " n)))
                               %)
                            (:checks decision))
                      (conj (vec (:checks decision)) check))]
      (cond-> decision
        (seq unruled) (assoc :relation-unruled (vec unruled))
        (seq misread) (-> (assoc :relation-misread misread)
                          (update :checks
                                  (partial mapv #(if (= :relation-honest (:check %))
                                                   (update % :note str
                                                           " — nido: :baseline :breaks already lists "
                                                           (str/join ", " misread)
                                                           ", and every per-id ruling agrees with :breaks;"
                                                           " a line resting only on their absence is a misreading to dispute")
                                                   %))))
        (seq wrong)   (-> (assoc :checks checks)
                          (update :findings (fnil conj [])
                                  (cond-> {:check :relation-honest
                                           :cites (vec wrong)
                                           :claim (str ":breaks does not match the per-id rulings — " why)}
                                    (seq (keep #(:reason (ruled %)) wrong))
                                    (assoc :evidence (mapv #(str % ": " (:reason (ruled %))) wrong)))))
        (and (seq wrong) (= :proceed (:recommend decision))) (assoc :recommend :amend)))))

(def ^:private judge-reading
  "How a record judge runs jj, ahead of every prompt `run-round!` sends. A judge reads the
   predecessor revision with jj unprompted; without the flag jj snapshots the working copy first,
   the read-only sandbox denies that lock write, and the claim it was reading for comes back
   unchecked for a reason that has nothing to do with the claim. First rather than last, because
   the prompts end on the list the judge's answer is drawn from."
  (str "Run every jj command with --ignore-working-copy (`jj --ignore-working-copy log …`,\n"
       "`jj --ignore-working-copy file show -r <rev> -- <path>`), or jj tries to snapshot\n"
       "the working copy and the read-only sandbox denies the lock write.\n\n"))

(defn- run-round!
  "One read-only reviewer pass over a record — `:reviewer`, or codex, with
   codex's stand-in when codex has run out of quota (`codex/run-reviewer!`).
   Returns {:ok <json-string> :judged-by <map>} or {:outcome <kw> :detail <str>} — never nil,
   and never throws. :judged-by is `codex/run-reviewer!`'s, naming a stand-in when one answered.

   The outcome is tagged rather than collapsed because a judgment surface cannot
   afford one confusion above all others: a round that never ran must not read
   like a round that ran and found nothing to say. Silence from a judge is
   evidence; silence from a missing binary is not, and a reader who cannot tell
   them apart draws the wrong conclusion from the same blank line.

   `:schema`, the schema's JSON text, replaces `kind`'s for a round whose answer is shaped by what it
   was shown; `kind` still names the parse."
  [{:keys [cwd run-id kind prompt label reviewer schema]}]
  (try
    (let [dir (cstate/run-dir run-id)
          _   (fs/create-dirs dir)
          ;; :label names the ARTIFACTS, :kind selects the schema and the parse.
          ;; They are the same thing for a one-shot round and must not be for a
          ;; loop: every iteration is the same :kind, and sharing a basename
          ;; would leave each round reading the previous round's answer after a
          ;; codex failure that wrote nothing.
          n   (or label (name kind))
          schema-path (str (fs/path dir (str n "-schema.json")))
          out-path    (str (fs/path dir (str n "-out.json")))
          log-path    (str (fs/path dir (str n ".log")))]
      (spit schema-path (or schema (schemas kind)))
      (let [{:keys [exit judged-by unavailable] ran-log :log-path}
            (codex/run-reviewer! {:reviewer reviewer :cwd cwd :schema-path schema-path
                                  :out-path out-path :log-path log-path
                                  :prompt (str judge-reading prompt)})
            who (name (:reviewer judged-by))]
        ;; :codex-failed names the outcome whichever reviewer ran: it is the
        ;; lanes' vocabulary for a judge that did not answer, and the detail is
        ;; where the reviewer is named. A judge its vendor would not run is
        ;; told apart, with the vendor's line verbatim — the wait or the login
        ;; it calls for is in that line and nowhere else.
        (cond
          unavailable                 {:outcome :reviewer-unavailable
                                       :detail (str who " could not be run: " (:message unavailable))}
          (not (zero? exit))          {:outcome :codex-failed
                                       :detail (str who " exited " exit " — see " ran-log)}
          (not (fs/exists? out-path)) {:outcome :no-output
                                       :detail (str who " wrote no answer to " out-path)}
          :else                       (cond-> {:ok (slurp out-path)}
                                        judged-by (assoc :judged-by judged-by)))))
    (catch Throwable t
      {:outcome :round-crashed :detail (or (ex-message t) (str (class t)))})))

(defn- judged
  "Apply `parse` to a round result, keeping the outcome tagged the whole way.
   An answer that will not parse is its own outcome — the judge spoke and was
   unusable, which is a different fact from the judge never speaking.

   A judgement — a parse carrying :format — keeps who answered as :judged-by, so a stand-in's
   ruling is appended and reported as the stand-in's."
  [result parse]
  (if-let [json (:ok result)]
    (if-let [parsed (parse json)]
      (let [{:keys [reviewer instead-of because]} (:judged-by result)]
        ;; Only the shape the ledger's closed schema admits: an append it refuses is lost whole.
        (cond-> parsed
          (and (:format parsed) (keyword? reviewer))
          (assoc :judged-by (cond-> {:reviewer reviewer}
                              (keyword? instead-of) (assoc :instead-of instead-of)
                              (string? because)     (assoc :because because)))))
      {:outcome :unusable-answer
       :detail "the answer did not satisfy what a round must return"})
    result))

(defn- unruled-relation-ids
  "The ids of `subjects` `decision` gave no relation ruling, in `subjects`' order — none when its
   relation-honest was underivable, since `relation-held` does not hold such a decision to rulings."
  [decision subjects]
  (if (some #(and (= :relation-honest (:check %)) (= :underivable (:status %))) (:checks decision))
    []
    (let [ruled (into #{} (map :id) (:relation-rulings decision))]
      (into [] (comp (map second) (remove ruled)) subjects))))

(defn- rule-unruled-relations!
  "`decision` with rulings on every id of `subjects` it left unruled, asked of a judge once more
   before the decision is held to them. `round` is `run-round!`'s options for the decision round,
   `prompt-opts` `design-prompt`'s.

   One more ask, not a loop: what is still unruled after it stays so, and `relation-held` names it
   :relation-unruled. A re-ask that fails or answers nothing usable leaves `decision` as it was, for the same
   reason — the decision is the judge's, and a failed second ask adds nothing to it. Only the asked
   ids are taken from the answer, so it cannot overturn a ruling the decision already made."
  [decision subjects round prompt-opts]
  (let [missing (unruled-relation-ids decision subjects)]
    (if (or (not (:format decision)) (empty? missing))
      decision
      (let [asked  (set missing)
            answer (:ok (run-round! (assoc round
                                           :label  (str (or (:label round) "design-decision") "-unruled-relations")
                                           :schema (relation-reask-schema missing)
                                           :prompt (relation-reask-prompt prompt-opts subjects missing
                                                                          (:relation-rulings decision)
                                                                          unruled-pass))))
            added  (when answer
                     (try (filterv (comp asked :id)
                                   (parse-rulings (:relation_rulings (json/parse-string answer true))))
                          (catch Exception _ nil)))]
        (cond-> decision
          (seq added) (update :relation-rulings (fnil into []) added))))))

(defn- contradicted-relation-ids
  "The ids of `subjects` whose ruling `decision` contradicts its own relation-honest reading: it
   called the check held, and ruled them so `breaks` does not match (`mismatched`). None unless the
   check was called held."
  [decision subjects breaks]
  (if (some #(and (= :relation-honest (:check %)) (= :held (:status %))) (:checks decision))
    (into [] cat (mismatched (into {} (map (juxt :id identity)) (:relation-rulings decision))
                             (into #{} (map slug) breaks)
                             (mapv second subjects)))
    []))

(defn- reconcile-relation-reading!
  "`decision` with the ids whose rulings contradict its own held relation-honest
   (`contradicted-relation-ids`) ruled on once more, the contradiction in front of the judge, and
   named under :relation-contradicted. `prior` is `relation-prior`'s, which the new rulings are held
   to as the first ones were.

   Asked rather than resolved by nido, because each reading is the judge's and nido has no ground
   to prefer either: enforcing the ruling turns the judge's own disagreement into an amend round,
   and trusting the check proceeds over a :breaks its rulings say is wrong. What the second answer
   rules is held to like any other ruling; a re-ask that fails leaves the rulings as they were, and
   `relation-held` holds :breaks to them."
  [decision subjects breaks round prompt-opts prior]
  (let [ids (contradicted-relation-ids decision subjects breaks)]
    (if (or (not (:format decision)) (empty? ids))
      decision
      (let [asked  (set ids)
            answer (:ok (run-round! (assoc round
                                           :label  (str (or (:label round) "design-decision") "-contradicted-relations")
                                           :schema (relation-reask-schema ids)
                                           :prompt (relation-reask-prompt prompt-opts subjects ids
                                                                          (:relation-rulings decision)
                                                                          (contradicted-pass (map slug breaks))))))
            again  (when answer
                     (try (into {} (comp (filter (comp asked :id)) (map (juxt :id identity)))
                                (parse-rulings (:relation_rulings (json/parse-string answer true))))
                          (catch Exception _ nil)))]
        (cond-> (assoc decision :relation-contradicted ids)
          (seq again) (-> (update :relation-rulings (partial mapv #(get again (:id %) %)))
                          (held-to-prior prior)))))))

(defn- self-contradicted-ids
  "The ids `decision` both lists as confirmed and names in a finding's claim-id, in confirmed order."
  [decision]
  (let [found (into #{} (keep :claim-id) (:findings decision))]
    (filterv found (:confirmed decision))))

(defn- self-contradiction-block
  "What a judge whose answer confirmed and refuted the same ids is told when it is asked again."
  [decision ids]
  (str "\nYOUR LAST ANSWER CONTRADICTED ITSELF. It listed each id below in confirmed AND\n"
       "filed a finding naming it in claim-id:\n"
       (str/join "\n" (for [id ids
                            f  (:findings decision)
                            :when (= id (:claim-id f))]
                        (str "- [" id "] found: " (:claim f))))
       "\nOne reading cannot both hold a claim and refute it. Answer the whole decision\n"
       "again. For each id, either confirm it or file against it, not both. A finding\n"
       "whose defect is in something the claim cites — a route, a health observation —\n"
       "names that instead, and leaves the claim's id out of claim-id.\n"))

(defn- reask-self-contradicted!
  "`decision`, or the judge's second answer when its first both confirmed ids and filed findings
   against them (`self-contradicted-ids`), with those ids named under :self-contradicted.
   `round` is `run-round!`'s options for the decision round, `prompt` the prompt it was asked with,
   `parse` the decision parse.

   Sent back rather than resolved by nido, as `reconcile-relation-reading!` is: the confirmation and
   the finding are both the judge's, and nido has no ground to prefer either. One more ask, not a
   loop. A second answer that still contradicts itself is held by `rule`, where the finding wins; a
   re-ask that fails or answers nothing usable leaves the first answer."
  [decision round prompt parse]
  (let [ids (when (:format decision) (self-contradicted-ids decision))]
    (if (empty? ids)
      decision
      (let [again (judged (run-round! (assoc round
                                             :label  (str (or (:label round) "design-decision") "-self-contradicted")
                                             :prompt (str prompt (self-contradiction-block decision ids))))
                          parse)]
        (assoc (if (:format again) again decision) :self-contradicted ids)))))

(defn ^{:malli/schema [:=> [:cat :map :DeclaredElements] [:vector :string]]}
  unresolved-subjects
  "The subjects `record`'s claims are about that `listing` does not hold under the sort the record
   gives them, each once, in the order the claims name them. Empty when every subject resolves.

   Only a record written in the shared model has subjects to resolve. An older record read as
   claims still names things — a composition is about every module — but by the prose names its
   survey gave them, which no declaration was ever asked to hold, so it is judged as it always was.

   The SORT is part of resolving. A record describing `canvas.order/total` as a module where the
   declaration holds an operation by that id describes something the design does not declare, and
   a judge handed it would check a claim about the wrong thing. An id the listing holds more than
   once keeps every sort it is listed under, and resolves under any of them — keeping one row per
   id would drop the others silently, and a subject declared as exactly what the record says would
   read as undeclared.

   The strata a record names are subjects too, after its claims' — a record saying which levels it
   touches names levels the declaration has to hold, whether or not a claim is about them.

   Given the listing rather than reading it, so the round that reads it once can say why when it
   could not be read. A listing that is not `:listed` holds nothing, so nothing resolves against it."
  [record listing]
  (let [{:keys [elements claims]} (:model record)
        recorded (into {} (map (juxt :id :sort)) elements)
        declared (when (= :listed (:status listing))
                   (reduce (fn [m {id :id s :sort}]
                             (update m id (fnil conj #{}) (keyword (str/lower-case (name s)))))
                           {} (:elements listing)))]
    (into []
          (comp (distinct)
                (remove #(contains? (get declared % #{}) (recorded %))))
          (concat (mapcat :about claims) (:strata record)))))

(defn ^{:malli/schema [:=> [:cat :map :DeclaredElements] [:vector :string]]}
  misplayed-roles
  "The roles `record`'s model holds that `listing` declares as a Role with other players, in the
   order the model lists them. Empty when every declared role is played as recorded.

   A role's membership is authored twice — in the record a round judges and in the declaration
   the claim lives on in — and a judge checking a claim about a role whose two memberships differ
   checks it against players the design does not declare. Compared as sets: players have no
   order. A role the listing does not declare at all is `unresolved-subjects`' to name, not this;
   a listing that is not `:listed` declares no Role, so nothing here is misplayed against it."
  [record listing]
  (let [declared (when (= :listed (:status listing))
                   (into {} (keep (fn [{:keys [id sort refs]}]
                                    (when (= "role" (str/lower-case (name sort)))
                                      [id (set (:plays refs))])))
                         (:elements listing)))]
    (into []
          (comp (filter #(= :role (:sort %)))
                (filter #(contains? declared (:id %)))
                ;; A role written before players were authored has none to compare: its
                ;; absence is not an empty membership, and reading it as one stops a round
                ;; over a record that was reviewable before.
                (filter #(contains? % :plays))
                (remove #(= (set (:plays %)) (get declared (:id %))))
                (map :id))
          (get-in record [:model :elements]))))

(defn- undeclared-subjects
  "Why a round over `record` launches no judge because of what its claims are about, or nil when
   nothing stops it. A claim about something the design does not declare is not one a judge can
   check, and the listing that says so is in reach before a judge is paid for.

   Only a project that declares a design is held to it. One that declares none writes the same
   model under element ids of its own, and its claims name the elements its record lists — which
   the append already holds them to — so there is no declaration left to resolve them against.

   Two outcomes, kept apart for the reason every outcome here is tagged: a subject the listing does
   not hold says something about the record, and a listing fukan could not produce says nothing
   about it. A record with no subject to resolve — every record from before the shared model —
   asks fukan nothing, so a round over one starts no JVM to learn nothing.

   A role the declaration plays differently stops the round under the first outcome, named apart
   from an undeclared subject: both say the record describes what the design does not declare.

   `listing` is the one a stage already read at this tree, when it read one; nil reads it here."
  [project worktree record listing]
  (when (seq (mapcat :about (get-in record [:model :claims])))
    (let [listing (or listing (design-check/elements project worktree))]
      (case (:status listing)
        :unmodelled nil

        :undecidable
        {:outcome :declaration-unreadable
         :detail  (str "the declared design could not be listed at " worktree ": "
                       (:error listing))}

        (let [missing   (seq (unresolved-subjects record listing))
              misplayed (seq (misplayed-roles record listing))]
          (when (or missing misplayed)
            {:outcome :subjects-undeclared
             :detail  (str "the declared design at " worktree " "
                           (str/join "; "
                                     (cond-> []
                                       missing   (conj (str "holds no element of the recorded sort for: "
                                                            (str/join ", " missing)))
                                       misplayed (conj (str "declares other players for the roles: "
                                                            (str/join ", " misplayed))))))}))))))

(defn- reading-for
  "What a judge about to read `worktree` would read `record` against: the tree's identity, and — in
   a project that declares a design, over a record whose claims name subjects — each declared
   element's identity, from a listing read once here and handed on, so resolving the record's
   subjects asks fukan nothing more.

   The tree is read FIRST. A declaration edited while the listing is read then moves the tree the
   round compares against as its judge returns, instead of pairing an old digest with a tree that
   already holds the new one.

   A tree with no identity is read again once a stale working copy is healed (`tree/heal-stale!`),
   and otherwise keeps why under `:unreadable` — what `banking` tells the report."
  [project worktree record]
  (let [read    (settled/code-identity worktree)
        healing (when-not read (tree/heal-stale! worktree))
        tree    (or read (when (:healed healing) (settled/code-identity worktree)))
        why     (when-not tree
                  (if (false? (:healed healing))
                    (:line healing)
                    (:unreadable (settled/tree-reading worktree))))
        listing (when (and project (seq (mapcat :about (get-in record [:model :claims]))))
                  (design-check/elements project worktree))]
    {:listing listing
     :reading (cond-> {:code-identity      tree
                       :subject-identities (settled/subject-identities listing worktree)}
                why (assoc :unreadable why))}))

(defn- tree-moved
  "What a judge's round saw move, when the tree read as it launched (`before`) is not the one read as
   it returned: both identities, and the paths whose content differs between them when both
   listings are still held (`settled/moved-paths`) — so a person editing the live worktree can be
   told apart from an agent the loop launched. Nil when nothing moved, or no identity was read at
   launch, which is `banking`'s unreadable case and not a move."
  [before after]
  (when (and before (not= before after))
    (let [paths (settled/moved-paths before after)]
      (cond-> {:code-identity-before before :code-identity-after after}
        paths (assoc :moved-paths paths)))))

(def ^:private provenance
  "What a baseline carries about where it came from rather than what it says of the area: the
   reader's stamps, the record it corrects, and the goal it cites."
  [:seq :at :supersedes :intent])

(defn- provenance-free [record] (apply dissoc record provenance))

(defn- last-verified
  "The newest baseline on `ledger` (`settled/ledger`) that a review found to hold and nobody
   retracted, with the newest such review, as {:baseline :review} — or nil."
  [{:keys [reviews baselines retractions]}]
  (let [retracted (into #{} (map #(get-in % [:retracts :seq])) retractions)
        holding   (fn [b] (last (filter #(and (= (:seq b) (:baseline-seq %)) (report/review-holds? %))
                                        reviews)))]
    (some (fn [b] (when-let [r (and (not (retracted (:seq b))) (holding b))]
                    {:baseline b :review r}))
          (rseq (vec baselines)))))

(defn- moved-since
  "How `record` differs from `verified`, for a round whose every subject is settled — so no
   surviving subject moved, and what can have is what settlement cannot see: a subject dropped, or a
   field no id names (:area, :bounded-by, :drift, :unknowns…). As `moved-block` reads it."
  [record verified]
  (let [was       (settled/subjects verified)
        is        (settled/subjects record)
        subjected #{:modules :load-bearing :health :model :shape :composition}
        a         (provenance-free record)
        b         (provenance-free verified)]
    {:from    (:seq verified)
     :dropped (vec (for [[id content] (sort-by key was) :when (not (contains? is id))] [id content]))
     :fields  (vec (sort (for [k (distinct (concat (keys a) (keys b)))
                               :when (and (not (subjected k)) (not= (get a k) (get b k)))]
                           k)))}))

(defn- carried-review
  "A sufficient review of `baseline` appended with no judge, restating `verified`'s: every subject
   is settled at this tree and nothing else in the record moved, so a judge would be asked nothing.
   :carried-from names the review a judge actually reached, across any number of carries."
  [baseline {:keys [review] v :baseline}]
  (let [from (or (:carried-from review) (:seq review))]
    {:format       :baseline-review
     :verdict      :sufficient
     :baseline-seq (:seq baseline)
     :reason       (str "Every subject is settled at this tree, and the record reads as entry " (:seq v)
                        " in all but where it came from, so the review at entry " from
                        " that found it sufficient stands for it; no judge was launched.")
     :carried-from from}))

(defn- ledger-of
  "The `[project ws-id]` a record run reads and writes, or nil: `opts`' `:ledger`, resolved once
   when the run started, and resolved from `:cwd` only for a run started without one. A run's
   config and a round's opts both carry the two keys, and are both read through this.

   Once, because resolving a session from a directory can answer nil mid-run — the registry
   being rewritten, a session restarting — and a run resolving it again per round would end
   :no-workstream on a workstream it had been judging, or judge one it then could not write its
   decision to."
  [opts]
  (if (contains? opts :ledger)
    (:ledger opts)
    (stages/project+ws-from-cwd (:cwd opts))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  baseline-review!
  "Verify a baseline against the code. Returns the ledger record, or
   {:outcome <kw> :detail <str>} saying why there is none — never a bare nil, so
   a caller can always tell a skipped round from a failed one.

   `:baseline` names WHICH record to verify, and a loop must supply it. The
   newest entry is only the right answer for a one-shot round on a workstream
   with one baseline. A workstream can hold baselines of DIFFERENT areas — a narrow
   follow-up written beside the broad one it came out of — and a loop that
   re-reads `latest` repairs whichever was appended last, which need not be the
   one anybody asked about. It then cannot converge by construction: the design
   citing the other baseline is never answered however long it runs.

   The review carries `:code-identity` only when the tree read as the judge
   launched is the tree read as it returned. The judge reads the live tree, so
   that equality is all that ties its confirmations to code; a round that saw
   the tree move records none, and settles nothing (see `nido.review.settled`).

   `:settled` and the `:code-identity` they were settled at come from the stage
   that chose them; a caller passing neither settles nothing and reads the
   identity itself. So do the `:listing` the stage read and the
   `:subject-identities` in it, recorded beside the tree's identity only when
   the tree did not move — they are what a model claim is settled on in a
   project that declares a design, and only those the baseline's subjects rest on. Settled subjects
   are shown and are not checks; the review's ruling is held to its checks by `rule`, so a check the
   judge left without one is named under :unruled. `:prior` is what earlier runs found against
   the subjects it checks (`prior-findings-block`). A round whose tree moved is still appended,
   settled subjects or not: its findings and its rulings on its own checks are a judgment of
   code, and only banking them forward needs the one tree it lacks — which the missing
   `:code-identity` already withholds. Such a round's review carries `:tree-moved` (`tree-moved`),
   which is the report's and not the ledger's: the caller takes it off before appending.

   Its claims' subjects are resolved against the declared design at the tree the
   judge reads before a judge is launched, and before either identity is read —
   see `undeclared-subjects`.

   A round whose every subject is settled owes its judge no ruling, and asks one only when the record
   moved where settlement cannot see — a subject dropped, a field no id names — against the last
   verified record on this workstream's ledger: its judge is asked whether the derivations still
   hold (`moved-since`). A record reading as that one in all but its provenance launches no judge,
   and is answered by the verdict that one reached (`carried-review`)."
  [{:keys [cwd code-cwd run-id label disputes baseline settled listing subject-identities
           reviewer prior] :as opts}]
  (if-let [[project ws-id] (ledger-of opts)]
    (if-let [baseline (or baseline (ws/latest-entry project ws-id :baseline))]
      (if (baseline-round-worth-running? baseline)
        (or (undeclared-subjects project (or code-cwd cwd) baseline listing)
            (let [code-cwd (or code-cwd cwd)
                  settled  (or settled {})
                  asked    (owed-rulings baseline settled)
                  verified (when (and (seq settled) (empty? asked))
                             (some-> (settled/ledger project ws-id) last-verified))]
              (if (and verified (:seq baseline)
                       (= (provenance-free baseline) (provenance-free (:baseline verified))))
                (carried-review baseline verified)
                (let [before   (if (contains? opts :code-identity)
                                 (:code-identity opts)
                                 (settled/code-identity code-cwd))
                      result   (judged (run-round! {:cwd code-cwd :run-id run-id :kind :baseline-review
                                                    :label label :reviewer reviewer
                                                    :prompt (baseline-prompt {:baseline baseline
                                                                              :disputes disputes
                                                                              :settled settled
                                                                              :prior prior
                                                                              :moved (some->> (:baseline verified)
                                                                                              (moved-since baseline))
                                                                              :stance (stages/read-stance project)})})
                                       #(parse-baseline-review % (:seq baseline)))
                      after    (settled/code-identity code-cwd)
                      one-tree (when (= before after) before)
                      checks   (set (keys (apply dissoc (settled/subjects baseline) (keys settled))))
                      rests-on (settled/rested-on baseline baseline)]
              (if-not (:format result)
                result
                (let [kept  (select-keys subject-identities rests-on)
                      moved (tree-moved before after)]
                  (cond-> (rule result checks asked)
                    one-tree                  (assoc :code-identity one-tree)
                    (and one-tree (seq kept)) (assoc :subject-identities kept)
                    moved                     (assoc :tree-moved moved))))))))
        {:outcome :nothing-to-check
         :detail "the baseline records no load-bearing property and no health observation"})
      {:outcome :no-record :detail "this workstream has no :baseline entry"})
    {:outcome :no-workstream :detail (str "cwd resolves to no nido session: " cwd)}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] [:maybe :map]]}
  unverified-premise
  "Why this design cannot be judged yet, or nil when nothing stops it.

   Three of the decision round's four derivations are made AGAINST the baseline.
   On an unverified one the judge cannot tell `the baseline is wrong` from `the
   baseline is right and the area is not what I would have described`, and only
   the first has a remedy. That is not hypothetical: the first workstream to run
   this loop recommended :resurvey seven times out of seven and never once
   reached a decision, each round paying for a full derivation to rediscover
   that nobody had verified the premise.

   So the order is stated rather than discovered, and it costs nothing to state:
   nido can read the answer out of its own ledger before it spends a judge on
   it. Verify the baseline, then decide against it.

   The join used to live here, and lives in `standing` now — which asks two
   questions where this asked one: has the baseline been verified, and has anyone
   RETRACTED it since. A design decided on a baseline somebody later found false
   is the case this round could not see, and the reason it could not is that a
   verdict is a permanent record of a past reading while a retraction is a
   statement about now.

   A design citing NO baseline is still not gated: there is no premise to check,
   the prompt says exactly that, and the round is judged on the design's own
   merits."
  [project ws-id design]
  (when (get-in design [:baseline :seq])
    (let [st (standing/of-design project ws-id design)]
      (when-not (:decidable? st)
        {:outcome (or (:reason (:blocked st)) :premise-unverified)
         :detail  (:detail (:blocked st))}))))

(defn- effective-design
  "`design` with its model laid over the model of the baseline it cites — what a round resolves
   subjects and roles over. A design restates nothing it keeps, so a role it carries unchanged from
   its baseline is still one its claims bind, and a declaration playing that role otherwise has to
   stop the round as surely as one playing a restated role. The design as written when either
   record carries no model. `ledger` is the `[project ws-id]` the design is on."
  [ledger design]
  (let [baseline (stages/cited-baseline ledger design)]
    (if (and (:model design) (:model baseline))
      (assoc design :model (model/overlay (:model baseline) (:model design)))
      design)))

(defn- stratum-row? [row] (= "stratum" (some-> (:sort row) name str/lower-case)))

(defn- level-of
  "One declared stratum as the listing describes it: its vocabulary, its modules, the strata it rests
   on and those resting on it. nil for an id the listing holds as no stratum."
  [listing id]
  (let [strata (filter stratum-row? (:elements listing))]
    (when-let [row (some #(when (= id (:id %)) %) strata)]
      {:id         id
       :vocabulary (:doc row)
       :modules    (vec (get-in row [:refs :provided-by]))
       :rests-on   (vec (get-in row [:refs :rests-on]))
       :resting    (into [] (comp (filter #(some #{id} (get-in % [:refs :rests-on]))) (map :id)) strata)})))

(defn- touching
  "The parts of `design` that touch `level`: its elements that are the stratum or one of its modules,
   and its claims about any of them."
  [design {:keys [id modules]}]
  (let [ids    (into #{id} modules)
        {:keys [elements claims]} (:model design)]
    {:elements (filterv #(ids (:id %)) elements)
     :claims   (filterv #(some ids (:about %)) claims)}))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  stratum-prompt
  "The prompt for one stratum's judge. It is shown that level and nothing wider — its declared
   vocabulary, its modules, the strata it rests on and those resting on it — with the design's summary
   and the parts of it that touch the level, and asked the three questions only that level can answer.
   Read-only, like every record judge: it reads code to answer, and writes nothing."
  [{:keys [level design]}]
  (let [{:keys [id vocabulary modules rests-on resting]} level
        {:keys [elements claims]} (touching design level)]
    (str
     "You are ONE LEVEL of a codebase, judging a proposed change from where you sit. You are not\n"
     "deciding whether it should be built — another judge does that, and will read what you say as\n"
     "evidence. You answer only what this level can.\n\n"
     "THE LEVEL: " id "\n"
     "What it provides, as declared: " (str/replace (str vocabulary) #"\s+" " ") "\n"
     "Its modules: " (if (seq modules) (str/join ", " modules) "none listed") "\n"
     "It rests on: " (if (seq rests-on) (str/join ", " rests-on) "nothing declared") "\n"
     "Resting on it: " (if (seq resting) (str/join ", " resting) "nothing declared") "\n\n"
     "THE CHANGE: " (:summary design) "\n"
     "Shape: " (:shape design) "\n\n"
     "WHAT IT ASKS OF THIS LEVEL:\n"
     (if (or (seq elements) (seq claims))
       (str (bullets (concat (map #(str "[" (:id %) "] " (name (:sort %))
                                        (when-let [i (:interface %)] (str " — provides: " i)))
                                  elements)
                             (map #(str "[" (:id %) "] " (:statement %)) claims)))
            "\n\n")
       "  nothing of this level is named in the design's model; read its shape for what touches it\n\n")
     "ANSWER FOUR QUESTIONS OF THIS LEVEL, AND ONLY THIS LEVEL, IN THIS ORDER:\n"
     "  0. Is this a level at all? Name what the strata resting on it are written in — which of\n"
     "     its operations they compose. If nothing rests on it, is it the one level the program\n"
     "     is written at, or a feature split off that level? A helper everything calls, an output\n"
     "     sink the program emits into, or a cut made only to satisfy a dependency law is no\n"
     "     level, however clean its edges.\n"
     "  1. Can what the change needs from this level be built from what it already provides —\n"
     "     by combining its primitives — or does it need something new?\n"
     "  2. If it needs something new, does the design say why combining what the level provides\n"
     "     could not do? A level's vocabulary grows only for a stated reason.\n"
     "  3. Does each part the design places in this level belong here — or is it written in the\n"
     "     vocabulary of a level above, or does it reach past the level below?\n\n"
     "Verdict — the FIRST that holds: not-a-level (question 0 fails), misplaced (a part placed\n"
     "here belongs in another level), widens (it asks the level for something outside its\n"
     "vocabulary, unargued), or fits (the need is built from what the level provides, or it\n"
     "grows with a stated reason). Say why in the level's own terms, and cite the design's ids and\n"
     "the code you read. How the change will be cut into layers is not yours to judge.")))

(defn ^{:malli/schema [:=> [:cat :string :string] [:maybe :map]]}
  parse-stratum-reading
  "A stratum judge's JSON as the reading a decision records, or nil when its verdict is outside the
   closed four or it gives no reason."
  [json-str stratum]
  (try
    (let [m (json/parse-string json-str true)
          v (keyword (str (:verdict m)))]
      (when (and (#{:fits :widens :misplaced :not-a-level} v) (not (str/blank? (str (:reason m)))))
        {:stratum stratum :verdict v :reason (str (:reason m))}))
    (catch Exception _ nil)))

(defn- read-levels!
  "Read each declared stratum `design` names through a judge of its own, concurrently, before the
   deciding judge runs. Returns one `{:stratum :vocabulary :reading}` per named stratum the listing
   declares, in the design's order; a judge that did not answer leaves its outcome as the reading, so
   the round decides without it and says so. Nothing here writes."
  [{:keys [code-cwd run-id label reviewer design listing]}]
  (let [levels (keep #(level-of listing %) (:strata design))]
    (->> levels
         (mapv (fn [{:keys [id] :as level}]
                 (future
                   (let [n      (last (str/split id #"/"))
                         result (judged (run-round! {:cwd code-cwd :run-id run-id :kind :stratum-reading
                                                     :label (str (or label "design-decision") "-stratum-" n)
                                                     :reviewer reviewer
                                                     :prompt (stratum-prompt {:level level :design design})})
                                        #(parse-stratum-reading % id))]
                     {:stratum    id
                      :vocabulary (:vocabulary level)
                      :reading    (if (:verdict result)
                                    result
                                    (cond-> {:stratum id :outcome (:outcome result)}
                                      (:detail result) (assoc :detail (:detail result))))}))))
         (mapv deref))))

(defn- decision-asked
  "The ids of `design` a decision round owes a ruling on: its claims `settled` does not name. Only
   the claims, because the prompt asks the judge to confirm claims by id and shows the elements and
   fields as what they are about."
  [design settled]
  (let [subjects (settled/subjects design)]
    (into #{} (filter #(some :about (subjects %))) (keys (apply dissoc subjects (keys settled))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  design-decision!
  "Run the decision round over this workstream's latest design record. Returns
   the ledger record, or {:outcome <kw> :detail <str>} saying why there is none.

   Single-pass on purpose: it emits a decision, not findings to iterate on.

   One judge decides. Before it, each declared stratum the design names is read by a judge of its
   own (`read-levels!`), whose conclusion the deciding judge is shown and the decision records under
   :strata-read — evidence from each level, never a second decision.

   Two of the no-verdict outcomes are read out of the records before a judge is
   launched, and cost nothing: no design, and a design standing on a baseline
   nobody verified. The second is the one that was previously discovered by
   paying for the round — see `unverified-premise`. A third is read out of the
   declared design: a claim about something it does not declare — see
   `undeclared-subjects`.

   A design declaring it moves nothing structural is NOT one of them. The round
   is never skipped: what the declarations decide is whether a person's grant is
   additionally owed, and that is read after a proceeding decision, by the
   clearance `append!` writes. Skipping the round here would leave exactly the
   designs that owe nobody a grant with no decision to clear them on.

   Settlement works as it does for a baseline round. The `:design`, the `:settled`
   claims, the `:listing` and the identities they were settled at come from the
   stage that chose them. Settled claims are shown apart and are not checks; the
   decision's ruling is held to its checks by `rule`, and a claim it was handed and
   left without a ruling is named under :unruled. A baseline id relation-honest is ruled on per id,
   whose rulings the decision is then held to (`relation-held`), left without one is named under
   :relation-unruled. The judge is handed a schema admitting exactly those ids, and asked once more
   for any it still skipped (`rule-unruled-relations!`), so :relation-unruled names what two asks
   left open. Each ruling is shown the
   last decision's beside it and held to it where the record did not move (`held-to-prior`), and ids
   whose rulings contradict the judge's own held relation-honest are asked once more
   (`reconcile-relation-reading!`), so what reaches `relation-held` is the judge's reading and not
   its variance. It carries `:code-identity`, and
   the `:subject-identities` of what the design's subjects rest on beside it, only when the tree read
   as the judge launched is the tree read as it returned — and a round holding
   settled claims whose tree moved appends nothing, answering
   {:outcome :code-moved :answer <the decision>}. Either way a round whose tree moved carries
   `:tree-moved` (`tree-moved`), for the report; the caller takes it off before appending."
  [{:keys [cwd code-cwd run-id label disputes design settled listing subject-identities
           reviewer prior] :as opts}]
  (if-let [[project ws-id :as ledger] (ledger-of opts)]
    (if-let [design (or design (ws/latest-entry project ws-id :design))]
      (or (unverified-premise project ws-id design)
          (undeclared-subjects project (or code-cwd cwd) (effective-design ledger design) listing)
          (let [code-cwd  (or code-cwd cwd)
                effective (effective-design ledger design)
                baseline  (stages/cited-baseline ledger design)
                settled  (or settled {})
                before   (if (contains? opts :code-identity)
                           (:code-identity opts)
                           (settled/code-identity code-cwd))
                ;; Each named stratum read by a judge of its own, before the deciding one, so what
                ;; each level concluded is in front of it. A design naming none reads none.
                levels   (when (seq (:strata design))
                           (read-levels! {:code-cwd code-cwd :run-id run-id :label label
                                          :reviewer reviewer :design design
                                          :listing  (or listing (design-check/elements project code-cwd))}))
                relation (relation-yardstick design baseline)
                breaks   (get-in design [:baseline :breaks])
                prior-rs (when (seq relation) (relation-prior! project ws-id design baseline))
                asking   {:design   design
                          :baseline baseline
                          :stance   (stages/read-stance project)
                          :intent   (discover-intent ledger design)
                          :disputes disputes
                          :settled  settled
                          :prior    prior
                          :levels   levels
                          :answers  (answered project ws-id design)
                          :relation-prior prior-rs}
                round    {:cwd code-cwd :run-id run-id :kind :design-decision
                          :label label :reviewer reviewer
                          :schema (design-decision-schema design relation)}
                prompt   (design-prompt asking)
                parse    #(parse-design-decision % (:seq design) (set (report/derivations-of design))
                                                 (health-ids design baseline) (:intent asking))
                result   (-> (run-round! (assoc round :prompt prompt))
                             (judged parse)
                             (reask-self-contradicted! round prompt parse)
                             (rule-unruled-relations! relation round asking)
                             (held-to-prior prior-rs)
                             (reconcile-relation-reading! relation breaks round asking prior-rs))
                after    (settled/code-identity code-cwd)
                one-tree (when (= before after) before)
                subjects (settled/subjects design)
                checks   (set (keys (apply dissoc subjects (keys settled))))
                asked    (decision-asked design settled)
                rests-on (settled/rested-on design effective)]
            (cond
              (not (:format result)) result

              (and (seq settled) (nil? one-tree))
              (cond-> {:outcome :code-moved
                       :detail  (str "the tree changed while the judge read it, with " (count settled)
                                     " claim(s) outside its checks, so its decision was not appended")
                       :answer  result}
                (tree-moved before after) (assoc :tree-moved (tree-moved before after)))

              :else
              (let [kept  (select-keys subject-identities rests-on)
                    moved (tree-moved before after)]
                (cond-> (-> (rule result checks asked breaks)
                            (relation-held relation breaks))
                  (seq levels)              (assoc :strata-read (mapv :reading levels))
                  one-tree                  (assoc :code-identity one-tree)
                  (and one-tree (seq kept)) (assoc :subject-identities kept)
                  moved                     (assoc :tree-moved moved))))))
      {:outcome :no-record :detail "this workstream has no :design entry"})
    {:outcome :no-workstream :detail (str "cwd resolves to no nido session: " cwd)}))

(defn- cleared?
  "Does a `:design-cleared` on this workstream name the design at `design-seq`?

   Asked AFTER the decision is appended, because that append is what may have
   written one. It is the difference between a round that owed a person and one
   that did not, and the driver has no other way to tell them apart: a `:proceed`
   is the design round's ask, and an ask nobody is owed is not an escalation."
  [[project ws-id] design-seq]
  (boolean
   (when project
     (some #(= design-seq (get-in % [:design :seq]))
           (ws/entries-of project ws-id :design-cleared)))))

(defn- clear-if-owed-nobody!
  "Append a `:design-cleared` for the design a decision just recommended
   proceeding on, when that design's own declarations owe nobody a grant.

   TAKES ITS SUBJECT, and keeps it across retries. Reading `latest-entry :design`
   instead would clear whatever is newest: if D2 is appended after D1's
   proceeding decision but before this reads, it would check D2's declarations
   and clear D2, which no round has judged. `standing`'s `:decidable?` does not
   ask whether a decision exists — that is the round's own answer — so nothing
   downstream would have caught it.

   READ AND WRITE AS ONE OPERATION, over the boundary the ledger already has for
   exactly this question. Appending the answer at the moment it is reached is not
   yet reporting a moment that happened: the standing reading and the write are
   two operations with a ledger between them, and a verdict landing in that gap
   would leave a clearance naming a design something has already reached. So the
   write goes through `append-entry-at!`, which compares the position INSIDE the
   append lock and refuses as :stale rather than writing. The position it
   compares is the ENTRY COUNT, which is not the last entry's `:seq` — the
   sequence is sparse by design, and passing the wrong one refuses every
   clearance for ever on any ledger that has a gap.

   A REFUSAL IS NOT A FAILURE, and re-asking is the whole of the answer to it.
   Only standing can have moved: the decision is already appended and no round
   re-runs, and the declarations are frozen in the record being cleared. So the
   retry is the same operation repeated. Interference must not decide anything —
   a bounded budget that gave up would let five unrelated notes create the human
   gate this exists to remove — so the loop ends on an ANSWER: cleared, or
   standing come back unclean, and then no clearance is owed because a design
   that does not stand is not implementable on any reading. The cap is a
   runaway guard rather than a policy, and reaching it answers `:contended` —
   the question still open, never a grant owed — which the round reports as a
   status the clearance stage (`clear!`) takes up rather than one it parks for a
   person."
  [project ws-id design-seq]
  (loop [attempts 0]
    (let [w      (ws/read-ws project ws-id)
          design (ws/entry-at-seq project ws-id design-seq)
          at     (count (:entries w))]
      (cond
        (nil? design) nil
        (report/owes-a-person? design) nil
        (> attempts 100)
        (do (binding [*out* *err*]
              (println (str "clearance: gave up re-reading " ws-id " for the design at "
                            design-seq " after 100 interruptions — it still owes nobody"
                            " a grant, so the clearance is to be asked again")))
            :contended)
        :else
        (let [st (standing/of-design project ws-id design)]
          (when (:decidable? st)
            (let [res (ws/append-entry-at!
                       project ws-id at {:kind :design-cleared}
                       (pr-str {:format :design-cleared
                                :design {:seq design-seq}}))]
              (when (= :stale (:refused res))
                (recur (inc attempts))))))))))

(defn ^{:malli/schema [:=> [:cat [:maybe [:tuple :keyword :string]] :map] :any]}
  append!
  "Append a round's record to `ledger`, a `[project ws-id]`. Never throws: a round that produced
   an answer must not turn into a crash because the record could not be written. Whether an
   unwritten record is a failure is the caller's to say — a round that goes on writes again
   next round, and one that ends the run cannot (`run-design-judge-stage`'s `final!`).

   A decision that proceeds (`report/proceeds?`) may also CLEAR the design, and
   that happens here rather than in either loop so that no round can append a
   decision and forget the clearance it implies.

   Returns `{:seq n}`, the :seq the ledger gave the record — the only answer to which entry this
   round wrote, since the newest entry once the lock is released may be another writer's — plus
   `:contended true` when a clearance was still owed and could not be written. nil when nothing
   was appended: an outcome, no ledger, or a write that threw. Nothing this returns means `a
   person is owed the grant`, so a caller asks the design itself, as `proceeding-status` does."
  [ledger record]
  (try
    (when (:format record)
      (when-let [[project ws-id] ledger]
        (let [n (ws/seq-of-path (ws/append-entry! project ws-id {:kind (:format record)} (pr-str record)))]
          (cond-> {:seq n}
            (and (= :design-decision (:format record))
                 (report/proceeds? record)
                 (= :contended (clear-if-owed-nobody! project ws-id (:design-seq record))))
            (assoc :contended true)))))
    (catch Exception _ nil)))

(defn- with-appended
  "`ctx` naming the entry `append!` answered it wrote, as `:appended-seq` — or, for a judgement the
   round took from the ledger (`reused-answer`), the entry it took, as `:reused-seq`. Untouched
   when it wrote none."
  [ctx answer]
  (cond
    (:reused answer) (assoc ctx :reused-seq (:seq answer))
    (:seq answer)    (assoc ctx :appended-seq (:seq answer))
    :else            ctx))

(defn- reused-answer
  "What a round answers in place of `append!` for `judgement`, a ledger entry it took as its own
   (`standing-judgement`): the entry, which it does not write a second time."
  [judgement]
  {:seq (:seq judgement) :reused true})

(defn- proceeding-status
  "The status a decision that `report/proceeds?` ends on, once the clearance it
   may imply has been asked for; `answer` is shaped as `append!`'s, whose `:contended` says it was
   asked for and could not be written.

     :cleared              a clearance names the design
     :proceed              a person is owed the grant — the round's ask
     :clearance-contended  it owes nobody and the clearance is still unwritten
     standing's reason     it owes nobody, but no longer stands

   Only :proceed parks for a person. A clearance still owed is a write, not an
   ask, whatever kept it from landing — contention, or a write that threw inside
   `append!` — and the clearance stage makes it. A design that no longer stands
   owes nobody anything until what moved under it is repaired, so it goes where
   standing says rather than onto a person's gate."
  [[project ws-id :as ledger] record answer]
  (let [n               (:design-seq record)
        design          (when project (ws/entry-at-seq project ws-id n))]
    (cond
      (cleared? ledger n)                               :cleared
      (:contended answer)                               :clearance-contended
      (or (nil? design) (report/owes-a-person? design)) :proceed
      :else (let [st (standing/of-design project ws-id design)]
              (if (:decidable? st)
                :clearance-contended
                (or (:reason (:blocked st)) :premise-unverified))))))

(defn ^{:malli/schema [:=> [:cat :Path] :keyword]}
  clear!
  "Write the clearance a proceeding decision already on the ledger implies, and
   nothing else — the stage a round that ended :clearance-contended hands on to.

   No round runs, no decision is appended and nothing becomes a grant: the
   decision is recorded and the declarations are frozen in the design, so the
   one thing left open is the write. Its subject is the design the latest
   proceeding decision names, and only while that is the newest design — a
   design appended since has had no round, and clearing it would clear work
   nobody judged. Anything else answers :nothing-to-clear: the ledger moved on
   after the stage was fired, and the next reading of it says where."
  [cwd]
  (if-let [[project ws-id :as ledger] (stages/project+ws-from-cwd cwd)]
    (let [design   (ws/latest-entry project ws-id :design)
          decision (some->> (ws/entries-of project ws-id :design-decision)
                            (filter #(= (:seq design) (:design-seq %)))
                            last)]
      (cond
        (or (nil? design)
            (not (report/proceeds? decision))
            (report/owes-a-person? design)) :nothing-to-clear
        (cleared? ledger (:seq design))     :cleared
        :else (proceeding-status ledger decision
                                 {:contended (= :contended (clear-if-owed-nobody! project ws-id (:seq design)))})))
    :no-workstream))

;; ── The baseline round as a loop ────────────────────────────────────────────
;;
;; Two stages on the shared engine: JUDGE (codex, read-only, the same pass the
;; one-shot round ran) and AMEND (claude, correcting the baseline). The engine
;; drives them until the code stops refuting the record, or until something says
;; stop that a human has to hear about.
;;
;; What is deliberately absent is a warden. The diff loop needs one because a
;; finding has to be attributed to a layer before anyone may act on it; a
;; baseline has no layers, and the ruling a record loop would actually want — is
;; this finding true of the code — is one the diff warden could not make anyway,
;; holding no tools. Phase 2 gives that its own channel. Phase 1 has none, which
;; is why the prompt below tells an amender who thinks a finding is wrong to
;; sharpen the property's evidence rather than to argue: sharper evidence is a
;; repair the next round can read, and an argument in phase 1 has nowhere to go.

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  baseline-finding-base-key
  "What makes two baseline findings the same finding: the derivation a gap blocks, else the id of
   the claim a refutation names, else the code it cites, else the record text it quotes — first
   present wins, each tagged so no two kinds of key collide.

   The claim's id, because it is the only handle that survives the claim being amended. Measured
   before ids existed: over five rounds on one baseline, of six claims one kept its text and none
   kept an evidence reference — so a claim that was fixed and is STILL WRONG produced a key nothing
   had seen, and the stall detector could not fire on the one case it exists for. A different
   counterexample to an amended claim therefore keys the same as the first; what tells the two
   apart is `record-round-changed?`, not this.

   The code, for a finding that names no claim, because the amender does not move it: amending
   rewrites the property text `:cites` quotes, while `src/x.clj:41` still says what it said. The
   quoted text is the degenerate last resort — the schema requires evidence — and keyed on the
   unstable thing deliberately: a finding that cites no code is one the loop should stop on early."
  [f]
  (cond
    ;; A GAP is named by the derivation it blocks, which is one of four closed
    ;; values and the only handle on it that no amendment can move — the same
    ;; reason the design round keys on its check names. Two rounds both saying
    ;; `decomposable cannot be derived` are the same finding however differently
    ;; they word it or whatever they cite, and collapsing them is the point: a
    ;; key that never collides is a stall detector that never fires.
    (:blocks f)                            [:blocks (:blocks f)]
    ;; Not under the subject's id alone: a refutation of the same id is a different finding, and
    ;; one following this one is the amender's rewording being read — not this one repeating.
    (:unsettled f)                         [:unsettled (str (:claim-id f))]
    (not (str/blank? (str (:claim-id f)))) [:claim-id (str (:claim-id f))]
    (seq (:evidence f))                    [:evidence (vec (sort (:evidence f)))]
    :else                                  [:cites (vec (sort (:cites f)))]))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  dispute-aware
  "Fold how many times a finding has been disputed into its identity.

   Without this the appeal channel cannot complete a single round trip. A
   dispute changes no record, so the judge that answers it by RESTATING the
   finding produces a set identical to last round's — which `no-progress?`
   reads as a stalled loop and ends, before the judge has been disputed the
   second time that would escalate it.

   Restating a finding after a new objection is not the loop going nowhere. It
   is the judge answering, which is exactly what the channel asked it to do."
  [base-key]
  (fn [f] [(base-key f) (:disputed-n f 0)]))

(def baseline-finding-key (dispute-aware baseline-finding-base-key))

(defn- sites
  "The code a finding points at — its :evidence, else the record text its :cites quote — as a set.
   Empty when it points at neither, which is a finding nothing can tell apart from another."
  [f]
  (set (or (seq (:evidence f)) (:cites f))))

(defn- same-defect?
  "Whether two site sets (`sites`) can be one defect: they share a site, or either is empty and so
   cannot say. Overlap rather than equality, because a judge restating a defect re-reads the code
   and rarely cites the identical lines; one sharing none is a different counterexample."
  [a b]
  (or (empty? a) (empty? b) (boolean (some a b))))

(defn ^{:malli/schema [:=> [:cat :any :any :map] :int]}
  disputed-n
  "How many times this run has objected to `f` itself: disputes under its identity (`base-key`)
   whose disputed finding pointed at the same code (`same-defect?`).

   Not every objection under the identity. An identity is a claim or a check, and a claim can be
   wrong in several ways: an objection to the first counterexample does not answer the second, and
   counting it against one the amender accepted and repaired escalates a correct finding as twice
   disputed."
  [history base-key f]
  (let [k (base-key f) s (sites f)]
    (count (filter #(and (= k (:key %)) (same-defect? (set (:sites %)) s))
                   (mapcat :disputes history)))))

(def ^:private withdrawable-after
  "How many consecutive readings must refute a claim before its amender may remove it. Two, because
   the second refutation came after a rewording made for the first: rewording has been tried once,
   and was itself the next finding."
  2)

(defn ^{:malli/schema [:=> [:cat [:maybe [:map-of :string :int]]] [:map-of :string :int]]}
  spent
  "The subjects `refuted-running` counts at `withdrawable-after` or more, as a sorted `{id n}`: each
   reworded at least once and refuted again, so a rewording at the same strength is no longer a
   repair. Named on the judgement (:spent), the run's figures and the analysis headline, because
   the count runs ACROSS runs and the amender's withdrawal offer reaches none of them when a capped
   run never reaches its amend stage and the record is amended by hand between runs."
  [running]
  (into (sorted-map) (filter #(>= (val %) withdrawable-after)) running))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe [:sequential :string]]]}
  spent-at-cap
  "A record run's `run-loop` :spent: the `spent` subjects of a round that still owes an amendment,
   which a capped run ends :unfixable on instead of :max-iters. nil for a round that owes none."
  [ctx]
  (when (seq (:findings ctx))
    (seq (keys (spent (:refuted-running ctx))))))

(declare refuted-ids check-statuses claim-finding?)

(defn- run-keys
  "What one finding of a judgement counts against in `refuted-running`. A finding under a check the
   judgement ruled broken (or underivable) counts against that check, keyed as the check keyword's
   string — `\":goal-served\"`, which no slug can be — and not against the claim id the judge
   anchored it to: one check broken over several scope questions is not one claim refuted that many
   times. Any other finding counts against the claims it refutes (`refuted-ids`). A :for-person
   finding is a question for a person, not a falsification, and counts against nothing."
  [statuses f]
  (cond
    (:for-person f)                  []
    (or (:blocks f) (:unsettled f))  []
    (claim-finding? statuses f)      (refuted-ids f)
    :else                            [(str (:check f))]))

(defn ^{:malli/schema [:=> [:cat [:maybe :map] [:sequential :map]] [:map-of :string :int]]}
  refuted-running
  "How many readings in a row have refuted each subject of `subject` — the record being judged —
   counting back from its newest: `{id n}` for every id `subject` carries (its claims, modules,
   observations and elements, and its derivation checks keyed as `run-keys` keys them) whose newest
   reading in `reviews`, oldest first, refuted it. nil `subject` restricts nothing.

   `reviews` must already be the subject's own lineage (`lineage-of`): an id is a slug, and the
   same slug in another record's history is another claim.

   A reading of a claim is a judgement that ruled on it: confirmed it, held it :owed, ruled it in a
   relation ruling, or filed a refutation under it. One that did none — the claim was settled, or
   left unruled — neither breaks nor extends a run; any of the others ends one, except that
   - a confirmation on a first reading (:read-once) ends nothing until a second reading agrees,
     since a refutation of that second reading is the run going on;
   - an :owed ruling of an id `subject` lists under `:baseline :breaks` ends nothing, since what it
     held is the code the design says it stops being true (`rule`).
   A check's run is ended by the check ruled :held. A gap (:blocks), an :unsettled subject and a
   :for-person question refute nothing (`run-keys`)."
  [subject reviews]
  (let [broken (into #{} (map slug) (get-in subject [:baseline :breaks]))
        runs   (reduce (fn [runs {:keys [confirmed owed read-once findings relation-rulings] :as r}]
                         (let [statuses (check-statuses r)
                               refuted  (into #{} (mapcat #(run-keys statuses %)) findings)
                               ended    (concat (remove (set read-once) confirmed)
                                                (remove broken owed)
                                                (map :id relation-rulings)
                                                (keep (fn [[c s]] (when (= :held s) (str c))) statuses))]
                           (as-> runs rs
                             (apply dissoc rs (remove refuted ended))
                             (reduce #(update %1 %2 (fnil inc 0)) rs refuted))))
                       {} reviews)]
    (if subject
      (select-keys runs (concat (keys (settled/subjects subject))
                                (map str (report/derivations-of subject))))
      runs)))

(defn- lineage-of
  "The judgements of `judged` — `:baseline-review`s or `:design-decision`s on `ws-id`, keyed back to
   what they judged by `seq-key` — whose judged record is `subject`'s own lineage: reachable from it
   through :supersedes, or citing the same :intent. Every other record on the workstream is another
   area or another goal, whatever ids it shares."
  [project ws-id judged seq-key subject]
  (let [kind   (case judged :baseline-review :baseline :design-decision :design)
        intent (get-in subject [:intent :seq])
        chain  (loop [d subject, seen #{}]
                 (if (or (nil? d) (nil? (:seq d)) (seen (:seq d)))
                   seen
                   (recur (some->> (get-in d [:supersedes :seq]) (ws/entry-at-seq project ws-id))
                          (conj seen (:seq d)))))
        seqs   (into chain (when intent
                             (keep #(when (= intent (get-in % [:intent :seq])) (:seq %))
                                   (ws/entries-of project ws-id kind))))]
    (filterv (comp seqs seq-key) (ws/entries-of project ws-id judged))))

(defn- withdrawable
  "The claims of `baseline` that `findings` refute and `refuted-running` counts at
   `withdrawable-after` or more, as a sorted `{claim-id n}` — the claims its amender may remove with
   a reason. A refuted module or health observation is never one: its removal is measured as what it
   is, and offering it would promise a withdrawal the report will not show."
  [baseline findings refuted-running]
  (let [claims (retreat/claim-ids baseline)]
    (into (sorted-map)
          (keep (fn [id] (let [n (get refuted-running id 0)]
                           (when (and (claims id) (>= n withdrawable-after)) [id n]))))
          (distinct (keep :claim-id (remove #(or (:blocks %) (:unsettled %) (:for-person %)) findings))))))

(defn- withdrawal-block
  "What an amender is told about the claims in `spent` (`withdrawable`), or nil when there are none."
  [spent]
  (when (seq spent)
    (str "\n\nA CLAIM NO REWORDING HAS SETTLED. "
         (str/join ", " (for [[id n] spent] (str "[" id "] has been refuted " n " readings running")))
         ",\neach time after a rewording of it. Restating it at the same strength is off the\n"
         "table: that has been tried, and it was the next finding. Two honest repairs are\n"
         "left, and you choose between them:\n\n"
         "  - WEAKEN it to what the cited code guarantees, including on its failure path —\n"
         "    what the code tries, not what it achieves when every call succeeds.\n"
         "  - REMOVE it, when no other claim, element or derivation in the record rests on\n"
         "    it, and give the reason under :withdrawn. That removal is the repair, not a\n"
         "    retreat from one: it is reported to a human as a withdrawal carrying your\n"
         "    reason. A removal without a reason is reported as a claim dropped.")))

(defn- design-spent
  "The subjects a design round's `raised` lines refute that `refuted-running` counts at
   `withdrawable-after` or more, as a sorted `{id n}`. Claims and elements alike: a design states
   both, and an element refuted that many readings running is an :interface being extended one
   member at a time (`design-withdrawal-block`)."
  [raised refuted-running]
  (into (sorted-map)
        (keep (fn [id] (let [n (get refuted-running id 0)]
                         (when (>= n withdrawable-after) [id n]))))
        (distinct (mapcat refuted-ids (remove :for-person raised)))))

(defn- enumerated-ids
  "The ids of elements carrying an :interface, in `design`'s model or the `baseline` it cites — the
   design may restate an element by id alone and leave its interface where the baseline wrote it."
  [design baseline]
  (into #{}
        (comp (filter #(seq (str (:interface %)))) (keep :id))
        (concat (get-in design [:model :elements])
                (get-in baseline [:model :elements])
                (:modules baseline))))

(defn- design-withdrawal-block
  "What a design amender is told about the subjects in `spent` (`design-spent`), or nil when there
   are none. `enumerated` (`enumerated-ids`) says which of them are an :interface rather than a claim.

   Not the baseline's `withdrawal-block`: a design has no :withdrawn — dropping a claim from one is
   a retreat, measured as one — so what is left to offer is weakening, a residual stated as a class,
   or the person. Each repair names the CLASS of the cases, because a rewording that answers one
   counterexample is what produced the run."
  [spent enumerated]
  (let [interfaces (filter (comp enumerated key) spent)
        claims     (remove (comp enumerated key) spent)
        running    #(str/join ", " (for [[id n] %] (str "[" id "] has been refuted " n " readings running")))]
    (str
     (when (seq claims)
       (str "\n\nA CLAIM NO REWORDING HAS SETTLED. " (running claims)
            ",\neach time after a rewording of it. Restating it at the same strength is off the\n"
            "table: that has been tried, and it was the next finding — a rewording that answers\n"
            "this round's counterexample leaves the next one standing. Three honest repairs are\n"
            "left, and you choose between them:\n\n"
            "  - WEAKEN it to what the cited code guarantees, including on its failure path —\n"
            "    what the code tries, not what it achieves when every call succeeds. A claim\n"
            "    bundling several promises keeps those the code keeps, and gives up the rest.\n"
            "  - STATE THE RESIDUAL AS ONE CLASS. Keep what the code guarantees as the claim,\n"
            "    and name the cases it does not cover by what they share — one class, never\n"
            "    this round's counterexample — as an accepted limit of this design or of its\n"
            "    phase.\n"
            "  - ASK, when whether that class is acceptable is the intent's question and not\n"
            "    yours: dispute the numbered line as one only a person can answer, naming the\n"
            "    class. The judge is asked again with it, and may stop for the person."))
     (when (seq interfaces)
       (str "\n\nAN INTERFACE NO ENUMERATION HAS SETTLED. " (running interfaces)
            ",\neach time by a member its :interface did not list. Do NOT add the member this\n"
            "round found: the enumeration is what keeps being refuted, and the next round finds\n"
            "the next member. Restate the :interface by category — what kind of var it\n"
            "publishes and which callers compose it — so a member the code adds is already\n"
            "described. Where no category holds them, ask instead: dispute the numbered line,\n"
            "naming why.")))))

(defn- refuted-ids
  "The claims a record finding refutes: a design finding's :claim-ids, a baseline refutation's
   :claim-id. A gap (:blocks) refutes nothing, and neither does a subject no reading could rule on
   (:unsettled)."
  [f]
  (if (or (:blocks f) (:unsettled f))
    []
    (into [] (keep (comp not-empty str)) (or (seq (:claim-ids f)) [(:claim-id f)]))))

(def ^:private unsettled-after
  "How many readings running a subject may come back unchecked before its amender is handed it. Two:
   one is a judge that could not reach the evidence this time; a second is a record asking for
   evidence no reading of the code has, which a third reading will not have either."
  2)

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] [:map-of :string :map]]}
  unchecked-running
  "How many readings in a row have declared each subject unchecked, counting back from its newest,
   with the reason each gave — `{id {:n n :reasons [reason ...]}}`, reasons oldest first, for every
   id whose newest reading in `judgements`, oldest first, declared it unchecked.

   A reading of a subject is a judgement that ruled on it: confirmed it, held it :owed, found against
   it, or declared it unchecked. One that did none — it was settled, or left unruled — neither breaks
   nor extends a run; any other ruling ends one. The reasons are carried and never compared: no two
   judges word one reason alike, so a match on them would never fire, and whether they are one
   reason is for whoever is handed them to say."
  [judgements]
  (reduce (fn [runs {:keys [confirmed owed findings unchecked]}]
            (let [why   (into {} (map (juxt :id :reason)) unchecked)
                  ruled (concat confirmed owed (mapcat refuted-ids findings))]
              (as-> runs rs
                (apply dissoc rs (remove why ruled))
                (reduce-kv (fn [rs id reason]
                             (-> rs
                                 (update-in [id :n] (fnil inc 0))
                                 (update-in [id :reasons] (fnil conj []) reason)))
                           rs why))))
          {} judgements))

(defn ^{:malli/schema [:=> [:cat :map [:map-of :string :map]] [:vector :map]]}
  unsettled-findings
  "A finding for each subject `record` declared unchecked that `running` (`unchecked-running`, this
   judgement included) counts at `unsettled-after` or more — handed to the amender to rule on,
   because the judge cannot and would be asked again every round while the record is called
   sufficient over it.

   :unsettled is the run's length and :reasons each reading's; :cites is empty, since what the
   finding points at is the subject's absence of evidence, which no site holds."
  [record running]
  (vec (for [{:keys [id]} (:unchecked record)
             :let [{:keys [n reasons]} (get running id)]
             :when (and n (>= n unsettled-after))]
         {:claim-id  id
          :claim     (str "[" id "] has come back unchecked " n " readings running")
          :unsettled n
          :reasons   (vec reasons)
          :cites     []})))

(defn- unsettled-claims
  "The claims of `baseline` among `findings` that no reading could rule on (`unsettled-findings`),
   as a sorted `{claim-id n}` — what its amender may also remove with a reason. A module or health
   observation is never one, for the reason `withdrawable` gives."
  [baseline findings]
  (let [claims (retreat/claim-ids baseline)]
    (into (sorted-map)
          (keep (fn [{:keys [claim-id unsettled]}]
                  (when (and unsettled (claims claim-id)) [claim-id unsettled])))
          findings)))

(defn- unsettled-block
  "What an amender is told about the subjects among `findings` no reading could rule on, or nil when
   there are none."
  [findings]
  (when-let [ids (seq (keep #(when (:unsettled %) (:claim-id %)) findings))]
    (str "\n\nA SUBJECT NO READING CAN RULE ON. "
         (str/join ", " (map #(str "[" % "]") ids))
         " came back unchecked reading after\n"
         "reading: what it asserts is not in the code — production history, the contents of a\n"
         "database — so the judge cannot settle it, asks again every round, and the record is\n"
         "called sufficient over it unruled. A further reading will not have the evidence\n"
         "either. Rule on it here, one of three ways:\n\n"
         "  - REWORD it to what the code shows, so the next reading can confirm or refute it.\n"
         "  - MOVE what the code cannot show into :unknowns — declared, not determined —\n"
         "    keeping in the subject only what the code shows, or removing it if that is nothing.\n"
         "  - DROP it, when no other claim, element or derivation in the record rests on it.\n\n"
         "A claim removed either way is named under :withdrawn with why, and is reported to a\n"
         "human as a withdrawal carrying your reason. Removed without one, it is reported as a\n"
         "claim dropped.")))

(defn ^{:malli/schema [:=> [:cat :any :map :any] :boolean]}
  record-round-changed?
  "Whether a record round that repeats the last round's findings, by `base-key`, is still moving —
   the engine's `:changed?`, which vetoes `nido.review.loop/no-progress?`.

   A record finding is keyed on the claim it refutes, so a claim amended for one counterexample and
   refuted by another keys the same both times; without this, one repeat of a claim id ends the
   run, after one amendment. True when the round before this one amended the record and every
   repeated identity points at code disjoint from what it pointed at last round (`sites`) — a new
   counterexample to a rewritten claim, not the old one surviving its rewrite. An identity whose
   sites overlap, or that either round points at nothing for, is no evidence of movement.

   Bounded twice. `unfixable` still gives up on a claim raised in four consecutive rounds, which
   this does not touch. And the veto YIELDS once a repeated claim has been refuted more than
   `withdrawable-after` readings running (`:refuted-running` on the round's ctx): its amender has
   then reworded it twice and been refuted each time — after being offered its withdrawal on a
   baseline, its weakening on a design — and a claim no rewording settles wants a person or a drop, not a third rewording."
  [base-key ctx prior]
  (let [prev    (last (filter #(= (dec (:iter ctx)) (:iter %)) prior))
        was     (group-by base-key (:findings prev))
        now     (group-by base-key (:findings ctx))
        site-of #(into #{} (mapcat sites) %)
        moved?  (fn [[k fs]]
                  (let [a (site-of (get was k)) b (site-of fs)]
                    (and (seq a) (seq b) (not-any? a b))))
        spent?  (fn [id] (> (get (:refuted-running ctx) id 0) withdrawable-after))
        ;; A design round's broken check is carried as the check itself, its :status on it.
        statuses (into {} (keep #(when (:status %) [(:check %) (:status %)])) (:findings ctx))]
    (boolean (and (:amended? prev)
                  (every? moved? (filter (comp was key) now))
                  (not-any? spent? (into #{} (comp (filter (comp was base-key)) (mapcat #(run-keys statuses %)))
                                         (:findings ctx)))))))

(defn ^{:malli/schema [:=> [:cat :map :any] :boolean]}
  baseline-round-changed?
  "`record-round-changed?` for the baseline loop."
  [ctx prior]
  (record-round-changed? baseline-finding-base-key ctx prior))

;; ── The appeal channel ──────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :any :any :any] :map]}
  parse-amend-answer
  "What an amender may hand back: an amended record, objections to what it was
   asked to amend for, or both — and, with the record, the reason for each claim it
   removed on purpose (:withdrawn). With either, :stale: the ids of subjects it was
   told to leave alone and believes the findings it accepted have made false.

   A bare record — no wrapper — is still accepted, because that is what the
   answer was before there was anything to say back, and a shape change is not a
   reason to stop reading records already written.

   Disputes are made BY NUMBER against the findings as they were listed. The
   amender never computes a key, and nothing has to match text back to text: a
   number is either in range or it is not. One out of range is dropped rather
   than guessed at, along with one that objects without saying why — an
   objection with no reason cannot be answered and is not an appeal.

   :out-of-reach is read the same way: a line whose repair, or what is left of it, lies in no record
   the amender may write. Each carries the line it names as :finding, since what it is handed to
   is a person rather than a judge holding the round's findings."
  [raw findings base-key]
  (when (map? raw)
    (let [record   (if (:format raw) raw (:record raw))
          numbered (fn [entries]
                     (vec (keep (fn [{:keys [finding because evidence]}]
                                  (let [i (dec (long (or finding 0)))
                                        f (when (and (nat-int? i) (< i (count findings)))
                                            (nth findings i))]
                                    (when (and f (not (str/blank? (str because))))
                                      [f {:key      (base-key f)
                                          ;; What `disputed-n` tells this defect from another under the key by.
                                          :sites    (vec (sort (sites f)))
                                          :claim    (or (:claim f)
                                                        (some-> (:check f) name)
                                                        (str f))
                                          :because  (str because)
                                          :evidence (vec (map str evidence))}])))
                                (when (sequential? entries) (filter map? entries)))))]
      {:record   (when (map? record) record)
       :disputes (mapv second (numbered (when-not (:format raw) (:disputes raw))))
       :out-of-reach (mapv (fn [[f o]] (assoc (dissoc o :sites :evidence) :finding f))
                           (numbered (when-not (:format raw) (:out-of-reach raw))))
       ;; `{id reason}`. Which of these a round honours is its caller's to say; one without a
       ;; reason is not a withdrawal, only a claim dropped.
       :withdrawn (into {} (keep (fn [{:keys [id because]}]
                                   (when-not (or (str/blank? (str id)) (str/blank? (str because)))
                                     [(str id) (str because)])))
                        (when-not (:format raw)
                          (let [w (:withdrawn raw)] (when (sequential? w) (filter map? w)))))
       ;; Not checked against the record here: an id no subject carries unsettles nothing.
       :stale (into #{} (comp (map #(str/trim (str %))) (remove str/blank?))
                    (when-not (:format raw)
                      (let [s (:stale raw)] (when (sequential? s) s))))})))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  disputes-for-judge
  "Every standing objection, oldest first, for the next judge prompt."
  [history]
  (vec (mapcat :disputes history)))

(defn- element-id-rule
  "The rule for an element's id given to every amender re-stating a record in the shared model. One
   text for both, because an id an amender invents is resolved by the next round against one
   listing whichever record it was invented on."
  [declared?]
  (if declared?
    (str "This project declares its design, so an element's id is its canvas identity,\n"
         "exactly as `clojure -M:fukan -m fukan.cli elements` lists it — never the id an\n"
         "older record gave it. The next round resolves every claim subject\n"
         "against that listing, and an id it does not hold stops the round.\n")
    (str "This project declares no design, so an element keeps the id the record already\n"
         "gave it, and an element new to the record takes an id of the record's own.\n")))

(defn- relation-shapes
  "The `:baseline` citation's variants as the write schema states them, one line each — read off
   `report/BaselineRelation` so the prompt and the ledger cannot disagree about a key."
  []
  (str/join
   "\n"
   (for [[relation _ variant] (m/children (m/schema report/BaselineRelation))]
     (str "    " relation " — "
          (str/join ", " (for [[k props s] (m/children variant)
                               :when (not= :relation k)]
                           (str k " " (pr-str (m/form s))
                                (when (:optional props) " (optional)"))))))))

(defn- coupled-edits
  "The write rules that tie one field of a `kind` record to another, for whoever amends it — nil
   for a baseline from before strata, which none of them bind. The ledger refuses a record that
   satisfies one side and not the other, and an amender changing one field has no reason to look
   at the second."
  [kind record]
  (case kind
    :baseline
    (when (strata-era? record)
      (str "EDITS THAT TRAVEL TOGETHER. A stratum whose :stratified/level reading is\n"
           "anything but :sound must be named, under :about, by a :health observation in\n"
           "the same record — and a health observation's :about names only strata the\n"
           "record lists. Demoting a reading and adding the observation that names it\n"
           "is ONE edit; the ledger refuses a record that makes only the first.\n"
           "Every stratum is listed twice, once by id under :strata and once as a\n"
           ":sort :stratum element of :model, and that element says what it provides\n"
           "(:interface) and carries a :stratified/level reading. A stratum you add\n"
           "needs all four; one short of any is refused.\n\n"))
    :design
    (str "EDITS THAT TRAVEL TOGETHER. :baseline names the relation, and each relation\n"
         "carries exactly these keys beside :relation, no others:\n"
         (relation-shapes) "\n"
         "A :revisit names under :breaks each line of the cited baseline it stops making\n"
         "true — a load-bearing property, or, for a moved module boundary, that module's\n"
         "interface or what it hides. A move that stops no baseline line being true is\n"
         "not a :revisit. Changing the relation means changing its keys with it: a\n"
         ":within keeps no :breaks, not even an empty one.\n"
         (when (contains? record :model)
           (str "A design takes an element or claim out of the\n"
                "model it is laid over under :model :removed {:elements [ids] :claims [ids]} —\n"
                "inside :model, never at the record's top level — and never also states an id\n"
                "it removes. An id the design simply leaves out is carried unchanged, not\n"
                "removed.\n"
                "An element the baseline does not already describe is described here: a\n"
                "module it adds says what it :hides and its :interface; a stratum it adds\n"
                "says what it provides (:interface) and carries a :stratified/level reading.\n"))
         "A :spin-out's :ref names a follow-up that already exists. You cannot file one\n"
         "from here, and a number you guess names someone else's: where a remainder has\n"
         "no ref yet, route it otherwise and say under :open that it needs filing.\n\n")))

(defn- strata-finding?
  "Whether finding `f` against `baseline` concerns its strata: a gap blocking the stratified
   derivation, or a finding about a stratum the baseline lists."
  [baseline f]
  (or (= :stratified (:blocks f))
      (contains? (set (:strata baseline)) (:claim-id f))))

(def ^:private strata-shape
  "Every field a stratum is written in, literally, for an amender answering a finding about strata.
   `coupled-edits` says which fields must agree; this says what they look like, which an amender
   adding the first stratum to a record has nowhere in the record to copy from — and went to read
   nido's schema to learn. It also says what a stratum does NOT carry, because the field it looked
   for was a membership list."
  (str "STRATA, AS THE LEDGER TAKES THEM. A finding below concerns strata, and this is\n"
       "every field a stratum is written in — there is no other:\n\n"
       "  :strata [\"<id>\" ...]   floor first; [] says none is declared, not an omission\n"
       "  under :model :elements, one for each of those ids:\n"
       "    {:id \"<id>\" :sort :stratum\n"
       "     :interface \"<the vocabulary this level provides to what is written in it>\"\n"
       "     :readings [{:lens :stratified/level :verdict :sound :because \"<why>\"}]}\n"
       "  and, for a verdict other than :sound, under :health:\n"
       "    {:id \"<slug>\" :observation \"...\" :axis :design :evidence [\"path:line\"]\n"
       "     :about [\"<the stratum's id>\"]}\n\n"
       "A stratum names no members, and nothing names what it rests on: which modules\n"
       "sit in a level is what its :interface and the claims :about it say. Its id\n"
       "follows the element id rule below.\n\n"))

(def ^:private amender-reading
  "How an amender reads, which the launch enforces (`stages/amender-tools`): told here, so its
   first shell read is not spent finding out."
  (str "Read code with the Read, Grep and Glob tools. Your shell runs read-only jj\n"
       "(jj file show, jj diff, jj log, jj show, jj st), the check command and plain\n"
       "reads; any command that writes is denied, and so is a write to any file but\n"
       "your answer. Run every jj command with --ignore-working-copy (`jj\n"
       "--ignore-working-copy log …`): without it jj tries to snapshot the working copy,\n"
       "and is refused.\n\n"))

(defn- check-block
  "How an author checks its answer file against the ledger before handing it over, or nil with no
   command to offer. The append still decides; this only lets the author find out first."
  [check-cmd]
  (when check-cmd
    (str "CHECK IT BEFORE YOU FINISH. Once the file is written, run\n\n  " check-cmd "\n\n"
         "It makes every check the append will make, with the citation the loop will set,\n"
         "and writes nothing. It prints what the ledger would refuse; repair that and run\n"
         "it again until it prints ok.\n\n")))

(defn- sound-rewrite-rules
  "What makes an amender's rewrite of a refuted claim true beyond the one counterexample it was
   handed. Every clause answers a way a rewrite has read as a repair and been the next round's
   finding: the cited site repaired while its siblings stayed false, an `only` exchanged for a new
   closed list false over code the amender had just read, a :falsified-by the record itself
   satisfies, and — for a design, which `baseline?` says is shown one — a widened quantifier that
   crosses a property the baseline already holds of the code."
  [baseline?]
  (str "REPAIR THE CLASS, NOT THE INSTANCE. A counterexample is one member of the\n"
       "failure it exhibits. Name that class — not the launcher the judge cited but\n"
       "every process that can create the resource; not the writer it found but every\n"
       "writer of the table — find its other members, and restate the claim so it is\n"
       "true of all of them. A claim repaired at the cited site alone is refuted next\n"
       "round by the member beside it.\n\n"
       "NO UNIVERSAL YOU HAVE NOT CHECKED. `only`, `alone`, `none`, `no other`,\n"
       "`every`, `exactly when`, and any closed list of members are claims about\n"
       "everything the code does, and one missing member refutes them. Write one only\n"
       "after the search that establishes it, and name that search in :read-at beside\n"
       "the sites it found — the grep over every writer, every caller, every public fn\n"
       "of the namespaces surveyed — so the next reader can run it again. When a missing\n"
       "member refuted a closed list, do not write a new list: state the promise the\n"
       "list stood for, or check every member of the surveyed namespaces against the\n"
       "new one first. A universal the code you just read contradicts is not a\n"
       "correction.\n\n"
       ":falsified-by COVERS EVERY UNIVERSAL THE STATEMENT MAKES. A statement with two\n"
       "exclusivities needs a counterexample for each; a statement widened while its\n"
       ":falsified-by is narrowed leaves the new clause unfalsifiable. And check it\n"
       "against the record's own elements, interfaces and claims before you write it:\n"
       "a counterexample the record itself states is a contradiction, not a claim.\n\n"
       "STAY TRUE BESIDE WHAT THE RECORD ALREADY SAYS. A sentence that assigns a\n"
       "responsibility — who stores, reads, writes or owns something — must agree with\n"
       "the record's module claims about that thing"
       (if baseline?
         (str ", and a quantifier you widen must not\n"
              "cross a property the baseline holds of the code: a design claim over `every`\n"
              "child the run spawns is false if a baseline property says one path spawns\n"
              "outside the run.\n")
         ".\n")
       "Where this prompt has a list headed WHAT YOUR REWRITE MUST STAY TRUE BESIDE,\n"
       "check against it, not against what you remember of the record.\n\n"))

(defn- cited-files
  "The files `citations` point at: every `path:line` citation's path, and every token naming a path
   under a directory. What lets a finding and a claim be recognised as about the same code when
   neither names the other's id."
  [citations]
  (into #{}
        (mapcat #(concat (map second (re-seq #"([\w.-]+(?:/[\w.-]+)*\.\w+):\d" (str %)))
                         (re-seq #"(?:[\w.-]+/)+[\w.-]+\.\w+" (str %))))
        citations))

(defn- bearing-subjects
  "What an amendment answering `findings` about `record` has to stay true beside, as
   `{:id :from :subject :settled}` rows: `:from` is `:record` or `:baseline`, `:subject` the element
   or claim as the record states it, `:settled` the `{:seq}` of the judgement that settled it — from
   `settled`, the round's `{id {:seq}}` — or nil.

   A finding's subjects are the ids it names — :claim-id, :claim-ids, or `[id]` quoted in its text.
   What bears on them is each element they are about, and every other claim of `record` — and, for a
   design, of the `baseline` it cites — that is about one of those elements, is one of those ids, or
   was read at a file the finding or a subject cites. A claim about every module (a composition)
   therefore bears on the whole model; the listing is then the record's model, which is what such a
   claim has to agree with. Elements stating neither what they hide nor an interface say nothing to
   contradict and are left out. The subjects themselves are left out: they are what is being
   rewritten."
  [{:keys [record baseline findings settled]}]
  (let [elements (model/elements record)
        claims   (filter :id (model/claims record))
        own      (into {} (map (juxt :id identity)) (concat elements claims))
        texts    (mapcat #(cons (:claim %) (:cites %)) findings)
        quoted   (filter (fn [id] (some #(str/includes? (str %) (str "[" id "]")) texts)) (keys own))
        targets  (into (set quoted)
                       (comp (mapcat #(cons (:claim-id %) (:claim-ids %)))
                             (keep #(some-> % str not-empty)))
                       findings)
        stated   (keep #(let [s (own %)] (when (contains? s :statement) s)) targets)
        abouts   (into (into #{} (filter (set (map :id elements))) targets) (mapcat :about stated))
        files    (cited-files (concat (mapcat :cites findings) (mapcat :evidence findings)
                                      (mapcat :read-at stated)))
        bears?   (fn [c] (or (some abouts (:about c)) (targets (:id c))
                             (some files (cited-files (:read-at c)))))
        row      (fn [from s] {:id (:id s) :from from :subject s
                               :settled (when (= :record from) (get settled (:id s)))})]
    (vec (concat
          (for [e elements
                :when (and (abouts (:id e)) (not (targets (:id e))) (or (:hides e) (:interface e)))]
            (row :record e))
          (for [c claims :when (and (not (targets (:id c))) (bears? c))]
            (row :record c))
          (for [c (filter :id (model/claims baseline)) :when (bears? c)]
            (row :baseline c))))))

(defn- bearing-block
  "The rows of `bearing-subjects` as an amender reads them, or nil when there are none — a heading
   over an empty list reads as a record with nothing to agree with."
  [rows]
  (when (seq rows)
    (str "WHAT YOUR REWRITE MUST STAY TRUE BESIDE. These are the statements that share\n"
         "an element, an id or a cited file with what the round found wrong. They stand\n"
         "as written — a settled one was confirmed against the code and is not judged\n"
         "again — so a responsibility you assign, a quantifier you widen or a\n"
         ":falsified-by you write that one of them contradicts or satisfies is a new\n"
         "defect, not a repair:\n\n"
         (str/join
          "\n"
          (for [{:keys [id from subject settled]} rows
                :let [{element-sort :sort :keys [hides interface statement falsified-by]} subject]]
            (str "- [" id "] "
                 (cond (= :baseline from) "(the baseline's, load-bearing) "
                       settled            (str "(settled by entry " (:seq settled) ") ")
                       :else              "")
                 (if element-sort
                   (str "(" (name element-sort) ")"
                        (when hides (str "\n    hides:      " hides))
                        (when interface (str "\n    interface:  " interface)))
                   (str statement
                        (when falsified-by (str "\n    refuted by: " falsified-by)))))))
         "\n\n")))

(defn- amend-check-cmd
  "The shell command that dry-runs the answer at `out-path` as a `kind` record on this ledger —
   through nido's own bb.edn, because an amender runs in the reviewed project's tree."
  [project ws-id kind out-path]
  (str "bb --config " (fs/path (core/nido-source-dir) "bb.edn")
       " nido:review:amend:check :project " (name project) " :ws-id " ws-id
       " :kind " (name kind) " :file " out-path))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  refusal-prompt
  "Instruction to repair an amended record the ledger refused, handed to the same kind of amender
   that wrote it: the refusal, the record as it was offered to the ledger, and a fresh answer file.

   Narrower than the amend prompts on purpose. The judge's findings are not repeated, because the
   record already answers them and this is not another round: the only thing wrong with it is
   what the ledger named."
  [{:keys [kind record refusal out-path check-cmd]}]
  (str "The ledger refused the " (name kind) " record you wrote for this round, so nothing\n"
       "was appended and the amendment has not taken effect.\n\n"
       "WHY IT WAS REFUSED:\n\n  " refusal "\n\n"
       "Repair exactly that, and change nothing the refusal does not name: every other\n"
       "field comes back as it is below. Where the rule ties two fields together, make\n"
       "the field you were missing agree with the one you changed — undoing the change\n"
       "you made for the judge would get the record accepted and make it false again.\n\n"
       (coupled-edits kind record)
       "THE RECORD THE LEDGER REFUSED:\n\n"
       (pr-str (ws/unstamp record))
       "\n\nWrite EDN to:\n\n  " out-path "\n\n"
       "  {:record <the COMPLETE repaired record — every field, not a diff>}\n\n"
       amender-reading
       (check-block check-cmd)
       "nido reads this file, validates it and appends it. Do not append it yourself, do\n"
       "not commit anything, and do NOT edit any source file."))

(defn- amender-launch
  "What the report keeps of how an amender's agent ended — `agent/launch!`'s `:exit-code`,
   `:timed-out?` and `:num-turns`, and the transcript it wrote — with `:failed` set to why it did
   not complete, or absent when it did.

   It did not complete when it was killed on its budget, exited non-zero, or ended with no `result`
   event. The last is read off `:num-turns`, which is taken from that event and so is nil exactly
   when none arrived: claude died mid-turn, and whatever the amender meant to write went with it.
   An amender that completed and wrote nothing has declined; one that did not complete has said
   nothing at all, and only this tells the two apart once the answer file is found absent."
  [{:keys [exit-code timed-out? num-turns]} transcript]
  (let [failed (cond timed-out?                          "killed on its budget"
                     (and exit-code (not (zero? exit-code))) (str "exited " exit-code)
                     (nil? num-turns)                    "ended with no result event")]
    (cond-> {:exit-code exit-code :timed-out? (boolean timed-out?) :num-turns num-turns
             :log transcript}
      failed (assoc :failed failed))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:maybe :string]]}
  amender-account
  "One line on how an amender's agent ended, from the amend phase's `:launch` (`amender-launch`) —
   whether it completed, its exit code, and its turns or that no result event arrived — or nil when
   no amender was launched."
  [{:keys [exit-code timed-out? num-turns failed] :as launch}]
  (when launch
    (str (if failed "did not complete — " "completed — ")
         (str/join " · " (cond-> []
                           (some? exit-code) (conj (str "exit " exit-code))
                           timed-out?        (conj "killed on its budget")
                           (some? num-turns) (conj (str num-turns " turn" (when (not= 1 num-turns) "s")))
                           (nil? num-turns)  (conj "no result event"))))))

(defn- unanswered-stop
  "End a round whose amender left no record and no objection. `:amend-noop` only when its agent
   completed — it read the findings and declined; `:amend-launch-failed` when the agent did not,
   with the reason and transcript as `:amend-error`, since that round's question was never answered
   and re-running it is a different remedy from overruling a refusal."
  [ctx {:keys [failed log]}]
  (if failed
    (assoc ctx :control :stop :status :amend-launch-failed
           :amend-error (str "the amender " failed " before answering — nothing was appended; its"
                             " transcript is " log))
    (assoc ctx :control :stop :status :amend-noop)))

(defn- launch-amender!
  "Launch an amender on `first-message` over the round's code tree, and say what it did: `:trespass`
   is `stages/amender-trespass` over a reading of that tree either side, with `permitted` as the
   dirs its writes are allowed in, and `:launch` is how the agent itself ended (`amender-launch`).

   Confined by `stages/amender-tools`: it may write `out-path` and under `permitted`, and its
   shell runs only reads and `check-cmd` (the command string, or nil). That confinement is what
   lets `amender-trespass` ignore the shell.

   The amender's transcript goes to `<label>.log` in the run dir rather than the run's shared
   agent.log, because it is the evidence: a moved path is held against the amender only when its
   own writes reach it, and its lines interleaved with a judge's could not be told apart.

   `first-message` goes beside it as `<label>-prompt.txt`, because claude's stream-json transcript
   does not carry it: without the file, what the amender was told — the rules it then ignored —
   cannot be read from the run."
  [ctx {:keys [label first-message permitted out-path check-cmd]}]
  (let [{:keys [cwd run-id budget]} (:config ctx)
        code-cwd   (or (:code-cwd (:config ctx)) cwd)
        dir        (cstate/run-dir run-id)
        transcript (str (fs/path dir (str label ".log")))
        before     (stages/working-copy-state code-cwd)]
    (fs/create-dirs dir)
    (fs/delete-if-exists transcript)
    (spit (str (fs/path dir (str label "-prompt.txt"))) first-message)
    (let [launched (agent/launch!
                    (merge {:run-id run-id :cwd code-cwd :budget budget
                            :first-message first-message
                            :err-file (str (fs/path dir (str label ".err.log")))
                            :out-file transcript}
                           (stages/amender-tools {:cwd code-cwd :out-path out-path
                                                  :permitted permitted :check-cmd check-cmd})))]
      {:launch   (amender-launch launched transcript)
       :trespass (stages/amender-trespass {:before before :after (stages/working-copy-state code-cwd)
                                           :transcript transcript :cwd code-cwd
                                           :permitted permitted})})))

(defn- amendment-state
  "What the answer file at `out-path` held, for a round that will not append it: `:absent`,
   `:unreadable`, `:record` when it carries an amendment, `:disputes-only` when it carries none."
  [out-path answer]
  (cond (not (fs/exists? out-path)) :absent
        (nil? answer)               :unreadable
        (:record answer)            :record
        :else                       :disputes-only))

(defn- amend-tree
  "What the report keeps of an amend round's tree when anything in it moved: the trespass reading
   plus the answer file, so a reader can tell an amender that wrote from a tree that moved under it
   and find the amendment either way. Nil when nothing moved and nothing was unreadable."
  [trespass out-path answer]
  (when (or (seq (:moved trespass)) (:unreadable trespass))
    (assoc trespass :amendment {:path out-path :state (amendment-state out-path answer)})))

(defn- short-id
  "A tree identity cut to a length a person reads in a sentence; the report keeps it whole."
  [id]
  (if id (subs id 0 (min 12 (count id))) "unread"))

(defn- trespass-stop
  "End the round on an amender whose own writes reached paths that moved. Whatever it wrote is left
   in place for a human, and the reason names those paths and the unappended answer, since that
   answer is the first thing the human will want and nothing else points at it."
  [ctx trespass out-path answer]
  (let [tree (amend-tree trespass out-path answer)]
    (assoc ctx :control :stop :status :amend-touched-code
           :amend-tree tree
           :amend-error (str "the amender's own writes reached "
                             (str/join ", " (:attributed trespass))
                             (when-let [others (seq (remove (set (:attributed trespass))
                                                            (:moved trespass)))]
                               (str " (also moved, not by it: " (str/join ", " others) ")"))
                             "; tree " (short-id (:before trespass)) " → " (short-id (:after trespass))
                             "; its answer, not appended, is " out-path
                             " (" (name (get-in tree [:amendment :state])) ")"))))

(def ^:private amend-reasks
  "How many times one round hands a refused amendment back to its amender before the round ends
   on the refusal. A refusal names its rule, so one repair is usually enough; the second is for a
   record that broke two rules, which the ledger reports one check at a time."
  2)

(defn- append-amendment!
  "Offer an amended `record` to the ledger through `append`, and hand each refusal back to the
   amender to repair — up to `amend-reasks` times — so a refused amendment is repaired rather than
   lost.

   `append` takes a record and returns `{:path p :record r}` when the ledger took it or
   `{:err refusal :record r}` when it did not, `r` being the record as offered. `stem` names this
   round's answer files. Returns what the accepting `append` returned plus `:refusals`, every
   refusal the round was re-asked over, oldest first; or, when the round ends here, `{:status s}`
   with `:refusals` and — for `:amend-invalid` — the last refusal as `:amend-error` and the answer
   file holding the last record refused as `:unappended`. A re-ask answered with no readable
   record ends the round on the refusal it failed to repair, and one whose own writes reached a
   path that moved ends it `:amend-touched-code`, with that stop's `:amend-error` and
   `:amend-tree`. `permitted` is the dirs a repair may write in, as for the amendment it repairs.
   `path` is the answer file `record` was read from; `check-cmd`, given an answer file, is the
   command that dry-runs it, or nil to offer none."
  [ctx {:keys [kind stem record path append permitted check-cmd]}]
  (let [dir     (cstate/run-dir (:run-id (:config ctx)))
        invalid (fn [err refusals path]
                  {:status :amend-invalid :amend-error err :refusals refusals :unappended path})]
    (loop [record record, path path, refusals []]
      (let [{:keys [err] :as written} (append record)]
        (cond
          (nil? err)
          (assoc written :refusals refusals)

          (= amend-reasks (count refusals))
          (invalid err refusals path)

          :else
          (let [refusals (conj refusals err)
                label    (str stem "-reask-" (count refusals))
                out-path (str (fs/path dir (str label ".edn")))
                _        (fs/delete-if-exists out-path)
                check    (when check-cmd (check-cmd out-path))
                {:keys [trespass]}
                         (launch-amender!
                          ctx {:label label :permitted permitted
                               :out-path out-path :check-cmd check
                               :first-message (refusal-prompt {:kind kind :record (:record written)
                                                               :refusal err :out-path out-path
                                                               :check-cmd check})})
                raw      (when (fs/exists? out-path)
                           (try (edn/read-string (slurp out-path)) (catch Exception _ nil)))
                answer   (parse-amend-answer raw [] nil)
                again    (:record answer)]
            (cond
              (seq (:attributed trespass))
              (-> (trespass-stop {} trespass out-path answer)
                  (select-keys [:status :amend-error :amend-tree])
                  (assoc :refusals refusals))

              (nil? again)
              (invalid err refusals path)

              :else
              (recur again out-path refusals))))))))

(defn- refused-stop
  "End the round on what `append-amendment!` ended it on, keeping what the amender said.

   The answer's `disputes` are kept whatever stopped the round: they are objections to the judge,
   not part of the record the ledger refused, and a stopped run is the one a person reads next. An
   amendment the ledger refused is named by its file, since nothing else holds it."
  [ctx written disputes]
  (cond-> (assoc ctx :control :stop :status (:status written)
                 :disputes disputes
                 :amend-refusals (:refusals written))
    (:amend-error written) (assoc :amend-error (:amend-error written))
    (:unappended written)  (assoc :amend-unappended (:unappended written))
    (:amend-tree written)  (assoc :amend-tree (:amend-tree written))))

(def ^:private stale-rule
  "What an amender does with a claim it was told to leave alone and now believes false. Without a
   channel it can only keep the claim or break the rule, and a claim kept word for word stays
   settled on a confirmation made before the finding it contradicts — the judge is never asked
   again. Naming it changes no text, so the rule it answers to still holds."
  (str "IF A FINDING YOU ACCEPT MAKES A CLAIM NOBODY CHALLENGED FALSE, NAME IT.\n"
       "Leave its text exactly as it stands — the rule above still holds — and list its\n"
       "id under :stale. It will not be treated as settled next round: the judge is\n"
       "asked to check it. Name only a claim a finding you accepted contradicts, never\n"
       "one you would merely word differently.\n\n"))

(def ^:private stale-field
  "The answer-file line for `stale-rule`."
  "Add :stale [\"id\" ...] only for a claim you left unchanged and believe false.\n")

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  amend-prompt
  "Instruction to repair a baseline the round found wanting.

   States the one thing that makes the loop worth running at all: the job is to
   make the record TRUE, and making the findings go away is not the same job.
   The distinction has a cheap wrong answer — delete the property, drop the
   observation, clear the flag — which is why nido measures the result rather
   than trusting this paragraph (see `retreat`).

   Two shapes, because the round has two failures and they want opposite repairs.
   :falsified says a stated property is not true of the code; :insufficient says
   every property IS true and a named derivation still cannot be made. An amender
   handed the second under the first's wording corrects a claim that was already
   right — and the fields that say which it is, :blocks and :needs, were on the
   record and printed nowhere. Only the gap branch is new; the refutation wording
   is the one that converged and is left alone.

   `refuted-running` is what `refuted-running` counts over the baseline's own
   lineage of reviews, this round's included. A refuted claim it counts at `withdrawable-after` or more has already
   been reworded and refuted again, so its amender is told that a restatement is
   off the table and offered removal with a reason (`withdrawable`).

   A finding carrying :unsettled is neither: no reading could rule on its subject
   (`unsettled-findings`), and the amender is asked to rule on it instead, with removal of
   a claim on offer the same way. A round that found nothing else was judged sufficient,
   which the opening says rather than calling it a refutation."
  [{:keys [baseline findings out-path stance declared? check-cmd settled refuted-running]}]
  (let [gaps?    (boolean (some :blocks findings))
        unruled? (and (seq findings) (every? :unsettled findings))
        spent    (withdrawable baseline findings refuted-running)
        offered  (merge (unsettled-claims baseline findings) spent)]
   (str
   (cond
     unruled?
     (str "A read-only judge checked this workstream's BASELINE — the baseline of how the\n"
          "area works today — against the code. It found true everything it could check,\n"
          "and has been unable to check what is named below, reading after reading.\n\n")
     gaps?
     (str "A read-only judge checked this workstream's BASELINE — the baseline of how\n"
          "the area works today — against the code. It found the baseline TRUE, and\n"
          "found it does not say enough to derive something a decision needs.\n\n"
          "So nothing below is a correction. Each one names a derivation that cannot\n"
          "be made against the baseline as it stands, and what the baseline would have to\n"
          "say for it to be. Answer THOSE. Do not restate, sharpen or re-evidence a\n"
          "claim the judge did not name: it already holds, and a baseline grows without\n"
          "limit if completeness is the target — which is the failure the named\n"
          "derivation exists to bound. Add what the derivation needs and stop.\n\n")
     :else
     (str "A read-only judge checked this workstream's BASELINE — the baseline of how the\n"
          "area works today — against the code, and refuted part of it.\n\n"))
   (if gaps?
     (str "Your job is to make the baseline SAY ENOUGH. That is not the same job as\n"
          "making the findings go away, and the difference is the whole point of\n"
          "this pass:\n\n"
          "  - A derivation that cannot be made should be ANSWERED by stating what\n"
          "    the baseline was missing, with evidence, at the level it is written at.\n"
          "  - Nothing here says a claim is wrong. Correct one only if reading the\n"
          "    code for this gap showed you it is, and say so.\n"
          "  - Deleting a property, dropping a module, dropping a health observation,\n"
          "    or clearing an :invisibly-incomplete? flag is measured and reported to\n"
          "    a human. A round that asked for MORE and got less is the loudest\n"
          "    version of that, and the one most likely to be an accident.\n\n")
     (str "Your job is to make the baseline TRUE. That is not the same job as making the\n"
          "findings go away, and the difference is the whole point of this pass:\n\n"
          "  - A property the code does not have should be CORRECTED to what the code\n"
          "    actually does, and keep pointing at the evidence that shows it.\n"
          "  - A property that is right but stated so loosely the judge misread it\n"
          "    should get SHARPER evidence, not softer wording.\n"
          "  - Deleting a property, dropping a module, dropping a health observation,\n"
          "    or clearing an :invisibly-incomplete? flag makes the next round quieter\n"
          "    without making the record truer. So does reclassifying a claim to dodge\n"
          "    its counterexample — calling essential state `accidental` because a\n"
          "    derivation was found, when what that means is the claim was wrong.\n"
          "    Every one of those is measured and reported to a human. Do it only where\n"
          "    the baseline was genuinely wrong, and expect to have said why.\n\n"))
   "Stay at the level the baseline is written at: modules, what each hides, and how\n"
   "their composition produces the behaviour. A finding about one line of code\n"
   "matters here only insofar as it refutes a claim about the decomposition.\n"
   "Prefer a claim that stays true as the code moves — what a module HIDES — over\n"
   "one that counts what it currently contains.\n\n"
   "KEEP EVERY :id EXACTLY AS IT IS. An id is not a label, it is how the next\n"
   "round knows whether a claim you corrected is now right or still wrong. Change\n"
   "one and the correction reads as a new claim nobody has judged, which is how a\n"
   "loop stops being able to end. New claims get new ids; existing ones keep\n"
   "theirs however much their wording changes.\n\n"
   ;; The amender is the pass that WRITES readings, and it was the one pass never
   ;; told what a reading may say. It invented verdicts outside a lens's
   ;; vocabulary and lenses outside the registry; the ledger refused the record,
   ;; and an otherwise good amendment was thrown away whole.
   (when (or (contains? baseline :modules) (contains? baseline :model))
     (str "A reading may only use its own lens's verdicts, and a lens only reads the\n"
          "subject it is about. The ledger refuses anything else and the whole record\n"
          "is lost with it, so use these and nothing else:\n"
          (lens-block)))
   (coupled-edits :baseline baseline)
   (when (and (strata-era? baseline) (some #(strata-finding? baseline %) findings))
     strata-shape)
   (if gaps?
     (str "CHANGE ONLY WHAT WAS ASKED FOR. A claim nobody named must come back\n"
          "unchanged — not restated, not sharpened, not made more precise.\n\n")
     (str (if unruled? "CHANGE ONLY WHAT IS NAMED BELOW." "CHANGE ONLY WHAT WAS REFUTED.")
          " A claim nobody challenged this round must come\n"
          "back unchanged — not restated, not sharpened, not made more precise.\n\n"))
   stale-rule
   ;; The rule above says WHICH statements to touch. This one says HOW, and its
   ;; absence is what actually ran this loop out. Measured over six rounds on
   ;; one baseline: the composition went 405 characters to 4091 and the shape 271
   ;; to 2671, because every correction was appended as an exception to the
   ;; sentence that was wrong rather than replacing it. Each appended clause was
   ;; then the next round's finding — the same field was refuted three rounds
   ;; running, for three DIFFERENT reasons, each one about something the
   ;; previous amendment had added.
   "AND WHEN YOU CORRECT SOMETHING, REPLACE IT. Re-state the claim so that it is\n"
   "simply true. Do not keep the sentence that was wrong and hang an exception\n"
   "off it, and do not add a paragraph explaining the case the judge found.\n\n"
   "A record that grows every round cannot converge, and not because anyone is\n"
   "sloppy: every clause you add is another statement the next round has to\n"
   "check, so a correction made by appending REPLACES one finding with several.\n"
   "If a claim needs a page of qualification to stay true, it was the wrong\n"
   "claim — say the simpler true thing instead, even if it says less. Expect a\n"
   "corrected record to be about the length it was.\n\n"
   "That rule is what lets this end. A sharper claim is a bigger target: a baseline\n"
   "saying `publishes ten vars` or `folds five event types` invites the next round\n"
   "to count, and counting is always available. Rounds have gone by watching one\n"
   "claim get sharper while another, freshly sharpened, became the next finding.\n"
   (if gaps?
     "Answer what was asked; leave the rest exactly as it stands.\n\n"
     "Fix what was refuted; leave the rest exactly as it stands.\n\n")
   (sound-rewrite-rules false)
   "Read the cited code before you change a word of the record.\n\n"
   amender-reading
   "Do NOT edit any source file. This pass writes one file and nothing else.\n\n"
   "THE CURRENT BASELINE:\n\n"
   (pr-str (ws/unstamp baseline))
   (cond
     gaps?    "\n\nWHAT THE JUDGE COULD NOT DERIVE — numbered, and you answer them by number:\n\n"
     unruled? "\n\nWHAT NO JUDGE COULD RULE ON — numbered, and you answer them by number:\n\n"
     :else    "\n\nWHAT THE JUDGE REFUTED — numbered, and you answer them by number:\n\n")
   (str/join
    "\n\n"
    (map-indexed
     (fn [i f]
       (str (inc i) ". "
            ;; A gap and a refutation are different answers and want different
            ;; repairs, and the two fields that say which — :blocks and :needs —
            ;; were on the record and printed nowhere. An amender shown a gap
            ;; under the word "refutes" corrects a claim that was already true.
            (cond
              (:blocks f)
              (str "blocks:  " (name (:blocks f)) "\n"
                   "   needs:   " (:needs f) "\n"
                   "   about:   " (:claim f) "\n"
                   "   in:      " (str/join "; " (:cites f)))

              (:unsettled f)
              (str "unruled: [" (:claim-id f) "] — unchecked " (:unsettled f)
                   " readings running, because:\n"
                   (str/join "\n" (map #(str "     - " %) (:reasons f)))
                   "\n   see A SUBJECT NO READING CAN RULE ON")

              :else
              (str "refutes: " (str/join "; " (:cites f)) "\n"
                   "   claim:   " (:claim f)))
            (when (seq (:evidence f))
              (str "\n   evidence: " (str/join ", " (:evidence f))))
            (when-let [n (and (not (:blocks f)) (not (:unsettled f)) (get spent (:claim-id f)))]
              (str "\n   running: refuted " n " readings in a row — see A CLAIM NO REWORDING HAS SETTLED"))))
     findings))
   (withdrawal-block spent)
   (unsettled-block findings)
   (some->> (bearing-block (bearing-subjects {:record baseline :findings findings :settled settled}))
            (str "\n\n") str/trimr)
   (if gaps?
     "\n\nIF A DERIVATION CAN BE MADE ALREADY, SAY SO INSTEAD OF ADDING FOR IT.\n"
     "\n\nIF A FINDING IS WRONG ABOUT THE CODE, SAY SO INSTEAD OF AMENDING FOR IT.\n")
   "You do not settle it — the judge is asked again with your objection in front\n"
   "of it, and has to withdraw the finding or answer your evidence. An objection\n"
   "with no reason is dropped, because it cannot be answered. Amending a record\n"
   "you believe is already right, to quiet a finding you believe is wrong, is the\n"
   "worst available answer: it makes the record false AND ends the argument.\n\n"
   "Write EDN to:\n\n  " out-path "\n\n"
   "  {:record   <the COMPLETE corrected baseline — every field, not a diff>\n"
   (if (seq offered)
     (str "   :disputes [{:finding 2 :because \"...\" :evidence [\"src/x.clj:41\"]}]\n"
          "   :withdrawn [{:id \"" (key (first offered)) "\" :because \"...\"}]}\n\n")
     "   :disputes [{:finding 2 :because \"...\" :evidence [\"src/x.clj:41\"]}]}\n\n")
   "Omit :record entirely if every finding is disputed and the baseline needs no\n"
   "change. Omit :disputes if you accepted all of them.\n"
   stale-field
   (when (seq offered)
     (str "Omit :withdrawn unless you removed a claim named under "
          (str/join " or " (cond-> []
                             (seq spent)             (conj "A CLAIM NO REWORDING HAS SETTLED")
                             (some :unsettled findings) (conj "A SUBJECT NO READING CAN RULE ON")))
          ".\n"))
   "\n"
   "Write the record in the shared model — :model {:elements :claims} in place of\n"
   ":modules, :composition and :load-bearing — whatever shape the current one is in.\n"
   "Re-stating an older one changes nothing it says: each module becomes an element\n"
   "with :sort :module; each load-bearing property a claim keeping its id, :about\n"
   "the modules it concerns, with {:by :round} evidence and its evidence as\n"
   ":read-at; the composition a claim about the modules it composes.\n"
   (element-id-rule declared?)
   (some->> (check-block check-cmd) (str "\n"))
   "nido reads this file, validates it, and appends it as the superseding baseline.\n"
   "Do not append it yourself and do not commit anything.\n\n"
   ;; The design amender is handed its :seq with a note that an amender shown no
   ;; :seq is being asked to guess the one field the citation is checked against.
   ;; The baseline amender was told nothing at all, so it guessed — reliably the
   ;; entry it read the findings from, which is the review — and the loop's own
   ;; correct default only fires when the amender supplied nothing, deferring to
   ;; the guess. Say the field is not the amender's, and the loop now sets it.
   "OMIT :supersedes. The loop sets it to the record you were asked to repair,\n"
   "which is what you want in every ordinary round. Your record is handed to you\n"
   "unstamped — :seq is the ledger's to give — so a number you write there is a\n"
   "guess, and it is checked: the usual guess is the entry the findings came\n"
   "from, which is the REVIEW, and a baseline may only supersede a baseline.\n"
   "Write one only to name a DIFFERENT baseline of this workstream deliberately,\n"
   "and only after reading the ledger to confirm the entry is one."
   (when stance
     (str "\n\nPROJECT STANCE — the yardstick this area was designed under, framing\n"
          "only. What the project holds essential versus accidental, and what a\n"
          "boundary is FOR. Correct the decomposition through it.\n\n" stance "\n"))
   (level-reminder))))

(def ^:private loop-why-prefix
  "How every :supersedes :why the loop writes begins — the mark `authored-why` reads it by."
  "corrected against the code after round ")

(def ^:private carried-marker
  "Where a loop-written :why carries the authored :why of the record it corrects."
  " — carried from the record it corrects: ")

(defn- authored-why
  "The part of a :supersedes :why that its author wrote, or nil when the loop wrote all of it.

   A loop-written :why carries the authored one after `carried-marker`, so the author's reason —
   often the only record of which base revision the chain was surveyed at — travels down every
   amendment instead of being replaced by the first."
  [why]
  (when why
    (if (str/starts-with? why loop-why-prefix)
      (let [i (str/index-of why carried-marker)]
        (when i (subs why (+ i (count carried-marker)))))
      why)))

(defn ^{:malli/schema [:=> [:cat :keyword [:maybe :map] :map :map] :map]}
  cite-corrected
  "`record`, a `kind` amendment of `prev`, citing under :supersedes the record it corrects.

   The loop sets the citation, not the author: an amender is handed `prev` unstamped, so a :seq it
   writes is a guess, and the guesses it makes are the entry it read its findings from and the
   citation it copied off `prev` — the one a real record of the right kind, which the ledger takes
   and which forks the lineage. So the citation becomes `prev` whenever the author wrote none,
   named an entry `resolve` finds is not a `kind` record, or named `prev`'s own predecessor. Any
   other citation is the author's and stands: it names a different record of this kind on
   purpose. One `resolve` cannot find stands too — an unreadable ledger is not evidence the author
   was wrong.

   The author's :why survives a replaced :seq; a :why merely copied off `prev` does not, and in its
   place goes one naming this round, carrying `prev`'s own authored :why. `prev` with no :seq —
   a record from outside this ledger — leaves `record` as it is. The last argument names the round
   (`iter`, `run-id`) and how to read the ledger (`resolve`, a :seq to its entry or nil)."
  [kind prev record {:keys [iter run-id resolve]}]
  (let [cited     (get-in record [:supersedes :seq])
        resolved  (when cited (resolve cited))
        stale?    (and cited (= cited (get-in prev [:supersedes :seq])))
        misnamed? (and resolved (not= kind (:format resolved)))
        why       (get-in record [:supersedes :why])
        own-why   (when (not= why (get-in prev [:supersedes :why])) why)]
    (if (and (:seq prev) (or (nil? cited) misnamed? stale?))
      (assoc record :supersedes
             {:seq (:seq prev)
              :why (or own-why
                       (str loop-why-prefix iter " of run " run-id
                            (some->> (authored-why (get-in prev [:supersedes :why]))
                                     (str carried-marker))))})
      record)))

(defn- humanized-lines
  "The humanized explain `h`, one line per error, each prefixed by where in the record it is."
  [h path]
  (let [at (if (seq path) (pr-str path) "top level")]
    (cond
      (map? h)        (mapcat (fn [[k v]] (humanized-lines v (conj path k))) h)
      (sequential? h) (concat (keep #(when (string? %) (str at ": " %)) h)
                              (mapcat (fn [i v] (when (coll? v) (humanized-lines v (conj path i))))
                                      (range) h))
      (some? h)       [(str at ": " h)])))

(defn- ledger-refusal
  "Why the ledger refused a record, in a form somebody can act on.

   `ex-message` alone is \"Invalid event report\", which names no field and tells
   an amender nothing it could fix. The explain data is already on the exception;
   this is only a matter of reading it out — each error with its path, because a key
   legal one level down reads, unlocated, as a contradiction of the record the
   amender was shown."
  [e]
  (let [explain (:explain (ex-data e))]
    (str (or (ex-message e) "the ledger refused it")
         (when explain
           (str " — " (str/join "; " (humanized-lines (me/humanize explain) [])))))))

(defn ^{:malli/schema [:=> [:cat :keyword :string :keyword :map] [:maybe :string]]}
  amendment-refusal
  "What the ledger would refuse of `record` appended now as a `kind` amendment, citing what the
   loop would have it cite — or nil when it would be taken. Writes nothing. For an amender to check
   its answer before handing it over; see `nido.coordinator.record.workstream/check-entry` for how
   far \"now\" holds."
  [project ws-id kind record]
  (let [record (cite-corrected kind (ws/latest-entry project ws-id kind) record
                               {:iter "n" :run-id "(dry run)"
                                :resolve #(ws/entry-at-seq project ws-id %)})]
    (try (ws/check-entry project ws-id {:kind kind} (pr-str (ws/unstamp record)))
         nil
         (catch Exception e (ledger-refusal e)))))

(defn- appended
  "The record as a READER will find it: what was just written, stamped with the
   :seq the ledger gave it.

   An amender hands back a record with no :seq — it cannot know one, and the
   write schemas are closed so it must not invent one. But everything downstream
   of the append identifies the record BY that number: the review verdict is
   labelled `:baseline-seq`, a design cites its baseline as `:baseline {:seq n}`,
   and the precondition that a design's premise was verified is a join between
   the two. Carrying the unstamped record forward makes all three unanswerable
   about the record actually on disk.

   Degrades to the unstamped record rather than throwing. A stamp that could not
   be read back is a worse answer than no stamp, not a reason to lose a round
   whose work is already committed to the ledger."
  [project ws-id path record]
  (or (some->> path fs/file-name (re-find #"^(\d+)-") second parse-long
               (ws/entry-at-seq project ws-id))
      record))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  append-stopped-answer!
  "Append the amendment a stopped run left unappended, as its amender wrote it: `answer` is the
   answer file's path, `prev` the `kind` record round `iter` of `run-id` was amending. It goes
   through the loop's own append — the citation `cite-corrected` sets, the ledger's checks — so
   a person who agrees with the answer has no reason to re-type it.

   Returns `{:record r :retreats [...]}`, `r` stamped as appended, or `{:err why}` with nothing
   written: the file holds no readable :record, or the ledger refused it. The retreats are
   measured without the round's findings, so a claim withdrawn on purpose or a citation re-pointed
   onto the judge's evidence reads as a weakening: louder than the loop, never quieter."
  [{:keys [project ws-id kind prev answer run-id iter]}]
  (let [raw    (when (fs/exists? answer)
                 (try (edn/read-string (slurp answer)) (catch Exception _ nil)))
        record (:record (parse-amend-answer raw [] nil))]
    (if-not record
      {:err (str answer " holds no readable {:record ...}")}
      (let [record (cite-corrected kind prev record
                                   {:iter iter :run-id run-id
                                    :resolve #(ws/entry-at-seq project ws-id %)})]
        (try
          (let [path (ws/append-entry! project ws-id {:kind kind :amended-by (str run-id)}
                                         (pr-str (ws/unstamp record)))]
            {:record   (appended project ws-id path record)
             :retreats (if (= :design kind)
                         (retreat/design-retreats prev record)
                         (retreat/baseline-retreats prev record))})
          (catch Exception e {:err (ledger-refusal e)}))))))

(defn- stamp-run
  "`record` naming the run that appended it, the revision its judge read (`:judged-tree`,
   `nido.review.tree/stamp`) when a judge read one, and — for a re-survey — the design run it is
   nested in. An outcome is not a record and is left alone."
  [record {:keys [run-id within-run judged-tree]}]
  (cond-> record
    (and (:format record) run-id)            (assoc :run-id (str run-id))
    (and (:format record) within-run)        (assoc :within-run (str within-run))
    (and (:format record) (seq judged-tree)
         (nil? (:carried-from record)))      (assoc :tree judged-tree)))

(defn- banking
  "What the report says about whether a round's confirmations can settle anything: how many subjects
   the judge owed a ruling on, and — when something it confirmed cannot settle — why not. `asked` is
   the set its prompt listed (`owed-rulings`, `decision-asked`), never every unsettled subject: the
   report reads `:checks` against what the judge ruled on, so counting a subject it was never asked
   about shows a gap no judge left. `reading` is the one taken as the judge launched, `record` what
   the round returned.

   :unbanked only over a round that confirmed something: one that confirmed nothing lost nothing,
   and saying it did sends a reader after a loss that never happened. When the tree had no
   identity, :identity-unreadable is why (`reading-for`), and :unbanked says it too — the generic
   sentence alone cannot tell a stale workspace, which a person can heal, from a repository nido
   cannot read."
  [asked reading record]
  (let [lost (count (:confirmed record))
        why  (or (:unreadable reading) "no reason was recorded")]
    (cond-> {:checks (count asked)}
      (and (pos? lost) (nil? (:code-identity reading)))
      (assoc :unbanked (str "no identity could be read for this tree (" why "), so the " lost
                            " subject(s) this round confirmed cannot settle")
             :identity-unreadable why)

      (and (pos? lost) (:code-identity reading) (:format record) (nil? (:code-identity record))
           (nil? (:carried-from record)))
      (assoc :unbanked (str "the tree moved while the judge read it, so the " lost
                            " subject(s) this round confirmed cannot settle")))))

(defn- unruled-stop
  "A round that would have ended the run with checks it never ruled on. The first time, the run goes
   on to another judgement and nothing is amended: what this round did confirm is settled by then,
   so the next judge is handed only what was left. The second time, the run ends :unruled — the
   judge was asked and still did not rule, and nothing lets the record hold over that."
  [ctx]
  (if (get-in ctx [:carry :reasked-unruled])
    (assoc ctx :control :stop :status :unruled)
    (-> ctx (assoc :control :next-round) (assoc-in [:carry :reasked-unruled] true))))

(defn- judge-inputs
  "What a judge stage reads off the ledgers for `subject`, a `kind` of record, at `reading`, before
   it launches a judge:
   :standing, every subject whose latest judgement at the key confirmed it; :single, the ids of it
   confirmed once only (`settled/single-readings`); :settled, the part of it the judge is not
   asked — those confirmed twice running, less the ids the last amender called `stale`; and
   :prior, what earlier runs found against its subjects and nothing has answered since.
   `effective` as `settled/settled` takes it."
  [project ws-id kind subject reading effective run-id stale]
  (let [ls       (when (and project subject) (settled/ledgers project ws-id subject))
        standing (if (and project subject) (settled/settled ls subject reading effective) {})
        single   (if (and project subject) (settled/single-readings ls subject reading effective) #{})]
    {:standing standing
     :single   single
     :settled  (apply dissoc standing (concat single stale))
     :prior    (when subject (settled/prior-findings ls kind subject run-id))}))

(defn- newest-judgement
  "The newest `judged` entry on the ledger naming `subject` under `seq-key`, or nil."
  [[project ws-id] judged seq-key subject]
  (when (and project (:seq subject))
    (last (filter #(= (:seq subject) (seq-key %)) (ws/entries-of project ws-id judged)))))

(defn- why-judged-again
  "Why round 1 asks a judge about `subject` rather than taking `j`, the newest judgement of it on
   the ledger, as its own — nil when it takes it (`standing-judgement`). One keyword, the first that
   applies:

     :not-round-1   the run is past round 1, or repairing what it refuted
     :no-identity   the tree has no identity, so no reading of it can be matched
     :tree-moved    `j` was read at another tree
     :carried       `j` restated an earlier verdict with no judge launched
     :unruled       `j` left a check, or a baseline id, without a ruling
     :read-once     `j` rested on a first reading, which is owed a second
     :superseded    a later record answers `subject`
     :retracted     `j`'s subject was retracted
     :passed        `j` would let the record through — a proceed, a review that holds — which is
                    never taken: what it may clear is a judge's to decide afresh

   :passed is the reason a design already proceeded on, at an unmoved tree, is judged again."
  [ctx [project ws-id] subject reading j stands?]
  (let [n (:seq subject)]
    (cond
      (or (not= 1 (:iter ctx)) (get-in ctx [:carry :under-repair])) :not-round-1
      (nil? (:code-identity reading))                                :no-identity
      (not= (:code-identity reading) (:code-identity j))             :tree-moved
      (:carried-from j)                                              :carried
      (or (seq (:unruled j)) (seq (:relation-unruled j)))            :unruled
      (or (seq (:read-once j)) (:amendment-read-once j))             :read-once
      (some #(= n (get-in % [:supersedes :seq])) (ws/entries-of project ws-id (:format subject)))
      :superseded
      (some #(= n (get-in % [:retracts :seq])) (ws/entries-of project ws-id :retraction))
      :retracted
      (not (stands? j))                                              :passed)))

(defn- rejudged
  "What a judge phase records of `j`, the judgement already on the ledger that round 1 asked a judge
   about again, and why (`why-judged-again`) — or nil when there was none, or it was taken, or the
   round is not round 1, where every run judges what it amended."
  [ctx ledger subject reading j stands?]
  (when j
    (when-let [why (why-judged-again ctx ledger subject reading j stands?)]
      (when (not= :not-round-1 why)
        (cond-> {:seq (:seq j) :because (name why)}
          (:recommend j) (assoc :was (name (:recommend j)))
          (:verdict j)   (assoc :was (name (:verdict j))))))))

(defn- standing-judgement
  "The judgement of `subject` already on the ledger that round 1 of a run takes as its own instead
   of launching a judge, or nil. `judged` is :baseline-review or :design-decision, `seq-key` the
   field naming the record it judged, `stands?` what else it must say to be taken.

   Only the newest judgement of `subject`, and only while it still stands where the judge would
   read: read at `reading`'s code-identity, nothing yet answers it — no record supersedes `subject`
   and nobody retracted it — and it is a complete ruling, nothing left :unruled or on a first
   reading. A judge launched there is asked what that one already answered: it can only restate it,
   a round spent, or contradict it at the same tree, and the run would keep the later word with
   nothing saying there were two. So the round hands that judgement on — to its amender when it
   found something — and the judge the run launches next is asked only what is still owed a
   reading, as after any amendment.

   Round 1 only: from then on the run judges what it amended, which no earlier run has read."
  [ctx ledger judged seq-key subject reading stands?]
  (let [j (newest-judgement ledger judged seq-key subject)]
    (when (and j (nil? (why-judged-again ctx ledger subject reading j stands?)))
      j)))

(defn- carried-readings
  "What an earlier quiet round of this run read of `subject`, the same record, since a quiet round
   is followed by no amendment: `{id :holds|:owed}`, the ruling it gave each id it confirmed or held
   :owed. What pairs a reading when the tree has no identity to pair it on the ledger, and the only
   thing that pairs an :owed one, which settles nothing and so stands on no ledger."
  [ctx subject]
  (let [q (get-in ctx [:carry :quiet])]
    (when (= (:seq subject) (:seq q)) (:read q))))

(defn- rulings
  "`{id :holds|:owed}` for each id `record` read: confirmed with where it read it, or held :owed."
  [record]
  (merge (zipmap (:owed record) (repeat :owed))
         (zipmap (settled/checked-confirmations record) (repeat :holds))))

(defn- with-readings
  "`record` — what the judge returned, before it is appended — carrying :overturns, each earlier
   run's finding (`prior`) against an id it read; :overrides-settled, the confirmation that
   settled (`settled`) each id it was shown as outside its checks and refuted anyway; :splits and
   :unpaired, where it and the reading before it disagree; and, when `holds?` says it would end
   the run, :read-once, what of `asked` it read on a first reading, and :amendment-read-once when
   `unread-amendment?`.

   An id is read when it is confirmed (:holds) or held :owed. An owed ruling is a judge's word that
   the claim is sound, and a run resting on one such word is resting on a sample however the code
   stands: a design not yet built holds every claim owed, and would otherwise end on one reading.

   The reading before is the one this run carried from a quiet round of the same record
   (`carried`, `{id ruling}`), else a confirmation standing at the key it was read at (`standing`,
   taken at that key, so only when the record read one tree). A reading pairs with it only when
   the two gave the same ruling. One that gave the other — holds then owed, or owed then holds —
   is a judge disagreeing with itself on one record at one tree: it pairs nothing, so the id is
   read again, and it is kept as :unpaired `[{:id :was :now}]`, since otherwise the run keeps the
   later ruling and nothing shows there were two. An id the reading before held or owed and this
   one refutes is a :split `[{:id :was}]` — unless it was settled, which :overrides-settled says.
   An id the reading before read and this one declared unchecked stays :read-once: it was read
   once, and a run must not end on it.

   `asked` is the ids the round owes a ruling on. :read-once holds no other, because the reading
   after it pairs them only by reading them again, and an id it was not asked about can go
   unread without being :unruled — so the second reading would clear it unread.

   Where the reading before is a confirmation the ledger holds on ONE reading (`single`, the ids
   of `standing` that `settled/single-readings` names), this reading is what pairs it or denies
   it, and the record says which. :paired-with `[{:id :seq :ws-id :run-id}]` names, per id this
   one confirmed, the single confirmation it completes — the run that confirmed it first may be
   another, dead run, and nothing else joins the two. :contradicts-read-once `[{:id :seq
   :ws-id}]` names, per id this one refuted, the single confirmation it denies: a :split whose
   earlier reading is on the ledger, said with the entry, because :overturns runs the other way
   only and a refutation of a read-once id is otherwise a bare finding. Both only while `record`
   read the tree the confirmation is keyed on."
  [record holds? {:keys [standing single settled carried prior asked unread-amendment?]}]
  (if-not (:format record)
    record
    (let [ruled     (rulings record)
          found     (into #{} (mapcat refuted-ids) (:findings record))
          overrides (vec (for [[id {:keys [ws-id] n :seq}] (sort-by key settled)
                               :when (found id)]
                           {:id id :seq n :ws-id ws-id}))
          before    (merge {}
                           (when (:code-identity record) (zipmap (keys standing) (repeat :holds)))
                           carried)
          once-held (when (:code-identity record)
                      (into (sorted-map) (filter (comp (set single) key)) standing))
          paired    (vec (for [[id {:keys [ws-id run-id] n :seq}] once-held
                               :when (= :holds (ruled id))]
                           (cond-> {:id id :seq n :ws-id ws-id} run-id (assoc :run-id run-id))))
          denied    (vec (for [[id {:keys [ws-id] n :seq}] once-held
                               :when (found id)]
                           {:id id :seq n :ws-id ws-id}))
          unpaired  (vec (for [[id now] (sort-by key ruled)
                               :let [was (before id)]
                               :when (and was (not= was now))]
                           {:id id :was was :now now}))
          splits    (vec (for [[id was] (sort-by key before)
                               :when (and (found id) (not (contains? settled id)))]
                           {:id id :was was}))
          unchecked (into #{} (map :id) (:unchecked record))
          holds     (holds? record)
          once      (when holds
                      (vec (sort (filter (set asked)
                                         (concat (remove #(= (ruled %) (before %)) (keys ruled))
                                                 (filter unchecked (keys before)))))))
          overturns (vec (for [[id {:keys [ws-id] n :seq}] (sort-by key prior)
                               :when (ruled id)]
                           {:id id :seq n :ws-id ws-id}))]
      (cond-> record
        (seq once)                       (assoc :read-once once)
        (and holds unread-amendment?)    (assoc :amendment-read-once true)
        (seq overturns)                  (assoc :overturns overturns)
        (seq overrides)                  (assoc :overrides-settled overrides)
        (seq splits)                     (assoc :splits splits)
        (seq unpaired)                   (assoc :unpaired unpaired)
        (seq paired)                     (assoc :paired-with paired)
        (seq denied)                     (assoc :contradicts-read-once denied)))))

(defn- run-status
  "The status a review run `run-id` ended in, read off its report in the run dir, or nil when there
   is no report to read. A run's own account of itself — `interrupted`, `max-iters`, `sufficient` —
   which is what a reader joining two runs by hand went to the run dir for."
  [run-id]
  (let [path (fs/path (cstate/run-dir (str run-id)) "report.json")]
    (when (fs/exists? path)
      (try (some-> (json/parse-string (slurp (str path)) true) :status str)
           (catch Exception _ nil)))))

(defn- continues
  "The earlier runs whose judgements of this subject round 1 of run `run-id` read, as
   `[{:run-id :status}]` sorted by id: the run behind each confirmation standing at the key
   (`standing`, as `judge-inputs` gives it) and behind each refutation that `refuted-running`
   still counts (`running`, over `lineage`). A run that starts where another stopped owes a
   reader that join — its first round pairs, inherits and re-judges what those runs left, and
   the ledger's :run-id stamps are otherwise the only trace. :status is each run's own
   (`run-status`), or \"unknown\" when its report is gone. Empty past round 1, where the run
   reads its own amendments, and for a run with no id."
  [run-id iter standing running lineage]
  (when (and run-id (= 1 iter))
    (let [counted (set (keys running))
          refuters (for [j lineage
                         :when (some #(or (counted (some-> (:claim-id %) str))
                                          (counted (some-> (:check %) name)))
                                     (:findings j))]
                     (:run-id j))]
      (->> (concat (map :run-id (vals standing)) refuters)
           (remove nil?)
           (remove #{(str run-id)})
           distinct
           sort
           (mapv (fn [id] {:run-id id :status (or (run-status id) "unknown")}))))))

(defn- unread-amendment?
  "Is the record this round reads one this run amended, with no quiet round of the run since the
   newest amendment? Settlement is per subject, so an amendment that rewrote only what no subject
   carries — the prose every check reads — leaves the record fully settled, and its one clean
   reading would end the run. Told by round and not by :seq, which a record the ledger never
   stamped does not carry. An amendment is made after its round's judgement, so a quiet round
   (`second-reading`) at a later :iter has read it."
  [ctx]
  (let [amended (keep #(when (:amended? %) (:iter %)) (:history ctx))
        read-at (get-in ctx [:carry :quiet :iter])]
    (boolean (and (seq amended) (or (nil? read-at) (<= read-at (apply max amended)))))))

(defn- read-once?
  "Would `record` have ended the run — held, proceeded, or stopped for a person — but on a first
   reading of a subject or of an amendment, so the run reads it again (`second-reading`)?"
  [record]
  (boolean (or (seq (:read-once record)) (:amendment-read-once record))))

(defn- second-reading
  "A round that would have ended the run clean on a first reading (`read-once?`). The run goes on to
   another judgement with nothing amended; the subjects it read once are not settled, so the
   next judge is handed them again, cold — it is not told it is a second reading — and a subject it
   gives the same ruling is paired, one it leaves without a ruling is :unruled. What this round
   read is carried, with its ruling, for the pairing the ledger cannot give (`carried-readings`) —
   less what it declared unchecked, which a later reading has nothing to pair with — and the round
   it was read in, so an amendment is read a second time and not a third (`unread-amendment?`).
   Bounded: each such round pairs what the one before read alike, so it recurs only for a subject
   the judge keeps ruling differently, and the engine's cap holds either way."
  [ctx subject record]
  (-> ctx
      (assoc :control :next-round
             :owes-reading (cond-> {:seq (:seq subject)}
                             (seq (:read-once record))      (assoc :read-once (vec (:read-once record)))
                             (:amendment-read-once record)  (assoc :amendment true)))
      (assoc-in [:carry :quiet]
                {:seq       (:seq subject)
                 :iter      (:iter ctx)
                 :read      (merge (apply dissoc (carried-readings ctx subject)
                                          (map :id (:unchecked record)))
                                   (rulings record))})))

(defn- ledger-lost
  "The outcome a round answers in place of a judgement when its run's ledger resolved to nothing
   after an earlier round of the same run resolved it — `:ledger` in `:carry` — or nil. Its own
   status, because `:no-workstream` says there was never a ledger to write to, and this run had
   one: what is owed is a re-run once the session resolves again, not a workstream. A run pinned at
   start (`ledger-of`) cannot reach it."
  [ctx ledger]
  (when-let [[project ws-id] (when (nil? ledger) (get-in ctx [:carry :ledger]))]
    {:outcome :ledger-lost
     :detail  (str "this run's ledger " (name project) "/" ws-id
                   " stopped resolving from its cwd mid-run — re-run it once the session is back up")}))

(defn- run-judge-stage
  [ctx]
  (let [{:keys [cwd code-cwd run-id reviewer]} (:config ctx)
        ;; The record this run is repairing: the one it was pointed at, then
        ;; each amendment it makes itself. Never re-read as "the latest",
        ;; which another session — or an earlier round of a different baseline —
        ;; can change underneath a run in flight. It rides in :carry because
        ;; that is the only part of the ctx that outlives a round.
        target (or (:under-repair (:carry ctx)) (:baseline (:config ctx)))
        ;; What the judge is not asked to check, read at the tree it is about to
        ;; read. From the ledger rather than this run's history, whose carry was
        ;; keyed on the id alone — and this run's own earlier reviews are on the
        ;; ledger too, at a tree that does not move within a run.
        [project ws-id :as ledger] (ledger-of (:config ctx))
        subject (or target (when project (ws/latest-entry project ws-id :baseline)))
        {:keys [listing reading]} (reading-for project (or code-cwd cwd) subject)
        ;; A re-survey's trigger (`resurvey-refutations`), while the record it refuted is the one
        ;; read: never settled, so no round can answer it without a judge, and shown that judge as
        ;; what a confirmation would overturn. Once amended, the record is not the one it refuted.
        refuted (when-not (get-in ctx [:carry :under-repair]) (:refuted (:config ctx)))
        {:keys [standing single settled prior]}
        (-> (judge-inputs project ws-id :baseline subject reading subject run-id
                          (concat (get-in ctx [:carry :stale]) (keys refuted)))
            (update :prior merge refuted))
        stands? #(and (seq (:findings %)) (not (report/review-holds? %)))
        lost    (ledger-lost ctx ledger)
        reused  (standing-judgement ctx ledger :baseline-review :baseline-seq subject reading stands?)
        again   (rejudged ctx ledger subject reading
                          (newest-judgement ledger :baseline-review :baseline-seq subject) stands?)
        asked   (cond reused #{} subject (owed-rulings subject settled))
        judged  (when-not (or lost reused)
                  (baseline-review!
                   {:cwd cwd :ledger ledger :code-cwd code-cwd :run-id run-id :reviewer reviewer
                    :baseline subject
                    :settled settled
                    :prior prior
                    :listing listing
                    :code-identity (:code-identity reading)
                    :subject-identities (:subject-identities reading)
                    :label (str "baseline-review-round-" (:iter ctx))
                    :disputes (disputes-for-judge (:history ctx))}))
        record (or lost
                   reused
                   (-> (dissoc judged :tree-moved)
                       (stamp-run (:config ctx))
                       (with-readings report/review-holds?
                                      {:standing standing :single single :settled settled :prior prior
                                       :carried (carried-readings ctx subject)
                                       :asked asked
                                       :unread-amendment? (unread-amendment? ctx)})))
        ;; Read before this round's record is appended, and this round added by hand, so it is
        ;; counted exactly once whether or not the best-effort append lands. A reused review is on
        ;; the ledger already, and is not added twice.
        mine      (when-not reused [record])
        reviews   (when (:format record)
                    (into (if project (vec (ws/entries-of project ws-id :baseline-review)) []) mine))
        lineage   (if (and project subject)
                    (lineage-of project ws-id :baseline-review :baseline-seq subject)
                    [])
        running   (when reviews (refuted-running subject (into lineage mine)))
        unsettled (if reviews (unsettled-findings record (unchecked-running reviews)) [])
        record    (cond-> record (seq (spent running)) (assoc :spent (spent running)))
        ;; An amender's :stale speaks for the one round after it.
        ctx    (merge (-> (assoc ctx :settled settled :refuted-running running)
                          (update :carry dissoc :stale)
                          (cond-> ledger (assoc-in [:carry :ledger] ledger)))
                      (when (:seq subject) {:judged-seq (:seq subject)})
                      (when-let [m (:tree-moved judged)] {:tree-moved m})
                      (when again {:rejudged again})
                      (when-let [c (seq (continues run-id (:iter ctx) standing running lineage))]
                        {:continues (vec c)})
                      (when subject (banking asked reading record)))]
    (let [answer (if reused (reused-answer reused) (append! ledger record))]
      (with-appended
       (cond
        (:outcome record)
        (assoc ctx :record record :status (:outcome record))

        (and (= :sufficient (:verdict record)) (seq (:unruled record)))
        (unruled-stop (assoc ctx :record record :findings []))

        ;; A sufficient verdict over a subject no reading can rule on is not one the run may end
        ;; on: it goes to the amender with the rest below, and is read again after.
        (and (= :sufficient (:verdict record)) (empty? unsettled) (read-once? record))
        (second-reading (assoc ctx :record record :findings []) subject record)

        (and (= :sufficient (:verdict record)) (empty? unsettled))
        (assoc ctx :record record :findings [] :control :stop :status :sufficient)

        :else
        (let [findings (mapv #(assoc % :disputed-n
                                     (disputed-n (:history ctx) baseline-finding-base-key %))
                             (into (vec (:findings record)) unsettled))]
          ;; Twice objected to and stated a third time. Neither side is giving
          ;; way and neither can settle it: the judge cannot be overruled by the
          ;; pass it is judging, and that pass may not amend a record it believes
          ;; is already true. That is a human's call, not another round's.
          (if (some #(>= (:disputed-n %) 2) findings)
            (assoc ctx :record record :findings findings
                   :control :escalate :status :disputed)
            (assoc ctx :record record :findings findings))))
       answer))))

(def judge-stage
  "The same read-only pass the one-shot round ran, with its verdict appended to
   the ledger exactly as before.

   Every non-verdict outcome is terminal and keeps its own name. That is the
   rule the one-shot round already held — a round that could not run must never
   read like a round that ran and found nothing — and a loop makes it matter
   more, not less: `:codex-failed` on round three of an otherwise converging run
   is not convergence.

   A sufficient verdict ends the run only over checks it ruled on. One leaving
   some :unruled is judged once more, with no amendment between; still unruled,
   the run ends :unruled (`unruled-stop`). And only on a second reading: one
   confirming a subject no confirmation at its key preceded, or reading a record
   this run amended for the first time, is appended as :read-once or
   :amendment-read-once, holds nothing, and is judged again (`second-reading`).
   Nor over a subject it declared unchecked for the second reading running
   (`unsettled-findings`): that one is the amender's to rule on, whatever the verdict."
  {:name :judge
   :run
   run-judge-stage})

(defn- under-repair
  "The baseline a round's amendment repairs: the one this run last amended, else the one it was
   pointed at, else the newest."
  [ctx]
  (or (:under-repair (:carry ctx))
      (:baseline (:config ctx))
      (when-let [[project ws-id] (ledger-of (:config ctx))]
        (ws/latest-entry project ws-id :baseline))))

(defn- baseline-amend-message
  "What the baseline amender is told for this round's findings against `prev`, answering into
   `out-path`."
  [ctx prev out-path]
  (let [{:keys [cwd code-cwd]} (:config ctx)
        [project ws-id] (ledger-of (:config ctx))
        check (when (and project ws-id) (amend-check-cmd project ws-id :baseline out-path))]
    (amend-prompt {:baseline  prev
                   :findings  (:findings ctx)
                   :out-path  out-path
                   :check-cmd check
                   :settled   (:settled ctx)
                   :refuted-running (:refuted-running ctx)
                   :stance    (stages/read-stance project)
                   :declared? (some? (design-check/design-of project (or code-cwd cwd)))})))

(defn- run-amend-stage
  [ctx]
  (let [{:keys [run-id dry-run?]} (:config ctx)]
    (if dry-run?
      (assoc ctx :control :stop :status :dry-run)
      (let [[project ws-id] (ledger-of (:config ctx))
            prev      (under-repair ctx)
            dir       (cstate/run-dir run-id)
            out-path  (str (fs/path dir (str "amend-round-" (:iter ctx) ".edn")))
            check-cmd (when (and project ws-id) #(amend-check-cmd project ws-id :baseline %))]
        (fs/create-dirs dir)
        ;; "The file is there" is the whole test for whether the amender
        ;; answered, so the round must start with it absent. A leftover from
        ;; an earlier run under this run-id would otherwise be read as this
        ;; round's answer and appended to the ledger as a superseding record.
        (fs/delete-if-exists out-path)
        (let [{:keys [trespass launch]}
                       (launch-amender!
                        ctx {:label (str "amend-round-" (:iter ctx))
                             :out-path out-path
                             :check-cmd (when check-cmd (check-cmd out-path))
                             :first-message (baseline-amend-message ctx prev out-path)})
              ;; Read before the tree is judged, so an answer the round will not
              ;; append is still described where the stop is.
              raw      (when (fs/exists? out-path)
                         (try (edn/read-string (slurp out-path)) (catch Exception _ nil)))
              answer   (parse-amend-answer raw (:findings ctx) baseline-finding-base-key)
              ctx      (cond-> (assoc ctx :stale (vec (sort (:stale answer))) :amend-launch launch)
                         (amend-tree trespass out-path answer)
                         (assoc :amend-tree (amend-tree trespass out-path answer))
                         (seq (:stale answer))
                         (assoc-in [:carry :stale] (:stale answer)))]
          (cond
            (seq (:attributed trespass))
            (trespass-stop ctx trespass out-path answer)

            (not (fs/exists? out-path))
            (unanswered-stop ctx launch)

            :else
            (let [{:keys [record disputes]} answer
                  entry  (fn [retreats amended?]
                           {:iter (:iter ctx)
                            :verdict (get-in ctx [:record :verdict])
                            ;; Whether this round repaired anything. Read after
                            ;; the run to tell an amender that stopped working
                            ;; from one that worked and was refuted anyway —
                            ;; and the terminal ctx cannot answer it, because a
                            ;; run that ends on a judgement never reaches an
                            ;; amend stage to set it.
                            :amended? (boolean amended?)
                            :findings (:findings ctx)
                            :retreats retreats
                            :disputes disputes})]
              (cond
                (nil? answer)
                (assoc ctx :control :stop :status :amend-unreadable)

                ;; Objections and no amendment is a COMPLETE answer, not an
                ;; empty one: the amender read the code and says the record is
                ;; already right. The ledger is untouched and the next round puts
                ;; the objection in front of the judge.
                (and (nil? record) (seq disputes))
                (assoc ctx :disputes disputes :retreats []
                       :history (conj (vec (:history ctx)) (entry [] false)))

                (nil? record)
                (unanswered-stop ctx launch)

                :else
                ;; The correction names what it corrects, and this round is the
                ;; only thing that knows the pair: `prev` is the record it was
                ;; asked to repair and `record` is the repair. Written rather than
                ;; derived, because taking the newest baseline instead is exactly
                ;; the recency the ledger's citations exist to refuse.
                (let [cite    #(cite-corrected :baseline prev %
                                               {:iter (:iter ctx) :run-id run-id
                                                :resolve (fn [n] (ws/entry-at-seq project ws-id n))})
                      written (append-amendment!
                               ctx {:kind :baseline
                                    :stem (str "amend-round-" (:iter ctx))
                                    :record record
                                    :path out-path
                                    :check-cmd check-cmd
                                    :append (fn [record]
                                              (let [record (cite record)]
                                                (try {:record record
                                                      :path (ws/append-entry!
                                                             project ws-id {:kind :baseline :amended-by (str run-id)}
                                                             (pr-str (ws/unstamp record)))}
                                                     (catch Exception e
                                                       {:record record :err (ledger-refusal e)}))))})
                      record  (:record written)]
                  (if (:status written)
                    (refused-stop ctx written disputes)
                    (let [;; Only the claims the prompt offered removal for: a reason
                          ;; given for any other drop does not make it a withdrawal.
                          offered  (merge (unsettled-claims prev (:findings ctx))
                                          (withdrawable prev (:findings ctx) (:refuted-running ctx)))
                          retreats (retreat/baseline-retreats
                                    prev record (select-keys (:withdrawn answer) (keys offered))
                                    (:findings ctx))
                          ;; Stamped, not as the amender wrote it. The judge
                          ;; labels its verdict with the :seq of the record it
                          ;; read, and the design loop's re-survey hands this
                          ;; record on to be CITED by :seq — neither of which a
                          ;; record still in the shape it was written in can
                          ;; answer, since :seq is the ledger's to give.
                          stamped  (appended project ws-id (:path written) record)
                          ctx' (assoc ctx
                                      :retreats retreats
                                      :disputes disputes
                                      :amend-refusals (:refusals written)
                                      :amend-delta (subject-delta prev record)
                                      :history (conj (vec (:history ctx)) (entry retreats true)))]
                      ;; :as-authored is set once and never overwritten — it is
                      ;; the record the RUN started from, which is what growth
                      ;; is measured against. Set here rather than at run start
                      ;; because this is the first place that has it, and
                      ;; `prev` on the first round is exactly it.
                      (-> (assoc ctx' :amended? true)
                          (update :carry #(-> (or % {})
                                              (assoc :under-repair stamped)
                                              (update :as-authored (fn [x] (or x prev)))))
                          (cond-> (not (baseline-round-worth-running? record))
                            (assoc :control :stop :status :retreated))))))))))))))

(def amend-stage
  "Correct the baseline, then measure what the correction cost.

   Three things are checked after the amender exits, and they are three
   different failures:

     the pass wrote code, which no record loop may do — a path that moved while it
       ran and that its own writes reached (`stages/amender-trespass`). A path
       somebody else moved in a live worktree is reported, not held against it.
       Terminal, and loud: whatever it wrote is still there for a human, and the
       stop names it and the answer that was not appended.
     no usable record came back — the ledger untouched, and terminal under one of
       two statuses (`unanswered-stop`): :amend-noop when the amender's agent
       completed and declined, mirroring the diff loop's :fix-noop, and
       :amend-launch-failed when it never completed, as the diff loop's
       :fix-launch-failed is.
     the record came back smaller — reported always, and terminal when it fell
       below the point its own round would still run. That last one is the whole
       reason this stage measures rather than trusts: a loop that converges by
       deleting what it was asked to defend would otherwise report success."
  {:name :amend
   :run
   run-amend-stage})

(def baseline-pipeline
  "judge -> amend. No warden, no fix: nothing here touches the working copy."
  [judge-stage amend-stage])

;; ── The design round as a loop ──────────────────────────────────────────────
;;
;; Same two stages and the same appeal channel, over a different record and with
;; one addition the baseline loop has no use for: this round can conclude that
;; the BASELINE is wrong rather than the design, and when it does the repair is a
;; different loop. So :resurvey descends into the baseline pipeline and comes
;; back, which is what makes the pair a state machine rather than two loops that
;; happen to share an engine.
;;
;; The terminal state is an escalation, not a convergence. That is not a
;; limitation — it is the round's whole purpose: everything derivable is derived
;; so that what reaches a human is only the judgement that cannot be.

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  broken-checks
  "The derivations that failed — the design round's findings, at the granularity
   the round can actually decide at.

   The prose findings say more, but they quote the record and so are rewritten
   by every amendment. The check vocabulary is four closed values that no
   amendment can move, which is what a stall detector and an appeal both need."
  [record]
  (vec (filter #(= :broken (:status %)) (:checks record))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  underivable-checks
  "The derivations the round could not make at all.

   Never findings, and the distinction is the reason :status has three values.
   A check with no yardstick — nido's own work has no stance document, so
   relation-honest has nothing to check against — is not a defect in the record,
   and an amender told to fix one would amend a true record until the complaint
   went away."
  [record]
  (vec (filter #(= :underivable (:status %)) (:checks record))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  design-finding-base-key
  "What tells one design finding from another across rounds: the check that broke, and the claim
   ids the round's findings name. Both are handles no amendment can move — a check is one of four
   closed values, a claim id is stable across amendments — so a check broken against one claim
   and then against another is two findings rather than a stalled loop, and the same claim found
   again under the same check is one.

   A finding that shows a check broken is carried under that check, which holds the claim ids of
   every finding naming it, as `:claim-ids` — never a claim another check was broken against, whose
   withdrawal would otherwise move this finding's identity and reset its disputes. Order and
   repetition among them mean nothing.

   A finding may break NONE of the four — a record that contradicts itself or a claim it rests on
   is a defect under no derivation — and such a finding is carried under no check, so the claim it
   is about is the whole of its identity. That is why nil is a legal check here rather than an
   accident: a record whose only defect is check-less would otherwise have no handle at all, and a
   finding with no handle cannot be disputed, counted as stalled, or given up on. A finding filed
   under a check the same decision ruled held broke none of the four either, and is carried the
   same way (`claim-finding?`).

   A finding naming NO claim — a goal the record does not serve is about a commitment it never
   made — is told apart by the code it cites, as `baseline-finding-base-key` falls back: its
   :evidence, else its :cites. On the check alone every such finding would share one handle:
   unrelated defects would pool their disputes and their give-up count, and two rounds whose only
   findings are different goal gaps would read as a stall."
  [c]
  (let [ids (vec (sort (distinct (:claim-ids c))))]
    (cond-> [:check (:check c) :claims ids]
      (and (empty? ids) (seq (:evidence c))) (conj :evidence (vec (sort (distinct (:evidence c)))))
      (and (empty? ids) (empty? (:evidence c)) (seq (:cites c))) (conj :cites (vec (sort (distinct (:cites c))))))))
(def design-finding-key (dispute-aware design-finding-base-key))

(defn ^{:malli/schema [:=> [:cat :map :any] :boolean]}
  design-round-changed?
  "`record-round-changed?` for the design loop."
  [ctx prior]
  (record-round-changed? design-finding-base-key ctx prior))

(defn- design-finding-label
  "What to call one of a round's findings in a line a person or an amender reads.

   The check it broke, when it broke one — four closed values, and the vocabulary the whole round
   is conducted in. A finding that broke none is named by the claim it is about, which is the only
   handle it has; one about no single claim has nothing left but the record itself."
  [{:keys [check claim-ids]}]
  (cond
    check           (name check)
    (seq claim-ids) (str/join ", " claim-ids)
    :else           "the record"))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  trajectory
  "The run, as the human reading the escalated decision needs it. Rounds that
   found nothing and gave up nothing are still listed: a round that passed
   quietly is evidence about the ones that did not."
  [history]
  (vec (map-indexed
        (fn [i {:keys [findings asked retreats disputes amended?]}]
          (cond-> {:round (inc i)}
            (seq findings) (assoc :found (mapv design-finding-label findings))
            (seq asked) (assoc :asked (mapv design-finding-label asked))
            (some? amended?) (assoc :amended (boolean amended?))
            (seq retreats) (assoc :weakened (mapv #(str (name (:what %)) " — " (:detail %)) retreats))
            (seq disputes) (assoc :disputed (mapv :claim disputes))))
        history)))

(def ^:private judge-outcomes
  "The outcomes only a LAUNCHED judge yields: it exited non-zero, wrote no answer, crashed the
   round, answered unusably, or answered over code that moved under it. Every other outcome is a
   round stopped before a judge — no ledger, no record, nothing checkable, a subject or a
   declaration it could not resolve, a premise `standing` refused, or a judge its vendor would not
   run (`:reviewer-unavailable`), which read nothing and is a quota or a credential rather than
   loop behaviour.

   Listed this way round because the other list is the open one. Every new reason `standing` grows
   for refusing a premise is one more outcome reached without a judge, and a deny-list that did not
   name it counted the refused run as judged and spent an analysis session on it."
  #{"codex-failed" "no-output" "round-crashed" "unusable-answer" "code-moved"})

(defn- judge-launched?
  "Whether a judge phase launched a judge: it reached a verdict no judge was carried from and took
   no earlier run's judgement as its own (:reused-seq), it carries no outcome — a design round's
   judgement is not folded as a verdict — or its outcome is one of `judge-outcomes`."
  [ph]
  (let [outcome (some-> (:outcome ph) name)]
    (boolean (and (nil? (:carried-from ph)) (nil? (:reused-seq ph))
                  (or (:verdict ph) (nil? outcome) (judge-outcomes outcome))))))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :int]}
  judges-launched
  "How many of a record run's rounds launched a judge, read off the run's report — see
   `judge-launched?`. Zero means the run judged nothing, whatever status it ended in."
  [report]
  (count (for [round (:rounds report)
               ph    (:phases round)
               :when (= "judge" (some-> (:phase ph) name))
               :when (judge-launched? ph)]
           ph)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :int]}
  rounds-judged
  "How many of a record run's rounds a judge it launched JUDGED: `judge-launched?`, and a verdict or a
   design recommendation was parsed from it. Never more than `judges-launched`; the difference is
   the judges that ran and left no judgement — a codex failure, an answer nothing could read."
  [report]
  (count (for [round (:rounds report)
               ph    (:phases round)
               :when (= "judge" (some-> (:phase ph) name))
               :when (judge-launched? ph)
               :when (or (:verdict ph) (:recommend ph))]
           ph)))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:vector :map]]}
  unreadable-rounds
  "Each round of a record run's report whose confirmations could not settle because its tree had no
   identity, as `{:round n :why <the judge phase's :identity-unreadable>}` — read off the report, so
   a finished run's headline says it as the run did. Empty for a run every round of which could
   bank, or whose unbankable rounds confirmed nothing."
  [report]
  (vec (for [round (:rounds report)
             ph    (:phases round)
             :when (= "judge" (some-> (:phase ph) name))
             :when (:identity-unreadable ph)]
         {:round (:round round) :why (:identity-unreadable ph)})))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :map]}
  continued
  "What a record run's report says it continued of earlier readings and runs, read off its judge
   phases so a finished run's headline says it as the run did:
   :paired, per entry whose single confirmations this run's readings completed, `{seq [id ...]}`
   over every round; :contradicted, each read-once confirmation a round denied, `[{:id :seq}]`;
   and :continues, round 1's `[{:run-id :status}]`. Each absent when the run did none of it."
  [report]
  (let [judges (for [round (:rounds report)
                     ph    (:phases round)
                     :when (= "judge" (some-> (:phase ph) name))]
                 ph)
        paired (reduce (fn [m {:keys [id] n :seq}] (update m n (fnil conj []) id))
                       (sorted-map)
                       (mapcat :paired-with judges))
        denied (vec (for [{:keys [id] n :seq} (mapcat :contradicts-read-once judges)] {:id id :seq n}))
        runs   (vec (mapcat :continues judges))]
    (cond-> {}
      (seq paired) (assoc :paired paired)
      (seq denied) (assoc :contradicted denied)
      (seq runs)   (assoc :continues runs))))

(defn- supersedes-reaches?
  "Whether `entry`'s :supersedes chain on the ledger reaches the entry at `seq-n`. Every link
   names an older entry, so a link that does not — or names nothing readable — ends the walk
   at no rather than looping."
  [project ws-id entry seq-n]
  (loop [e entry]
    (let [n (get-in e [:supersedes :seq])]
      (cond
        (nil? n)                            false
        (= (long n) (long seq-n))           true
        (>= (long n) (long (or (:seq e) 0))) false
        :else                               (recur (ws/entry-at-seq project ws-id n))))))

(defn ^{:malli/schema [:=> [:cat [:maybe [:tuple :keyword :string]] [:enum :baseline :design]
                            [:maybe :map]]
                       [:maybe :map]]}
  subject-at-start
  "What a `kind` record run is about to judge, relative to `ledger` as it starts — nil when the
   ledger is unresolved or holds no entry of that kind. `named` is the entry the caller pointed
   the run at, or nil for the newest.

   :seq is the subject; :named? whether the caller chose it; :newest-at-start the newest entry
   of that kind as the run began, which a named run may have been pointed past. When it was,
   :skipped names that entry and whether its :supersedes chain reaches the subject — judging the
   subject then forks the lineage, leaving the newer entry with no review naming it, which is
   the fact nothing in the run's rounds otherwise records. A newer entry of another area is a
   neighbour, not a skip, and the flag says which.

   For a baseline, :premise is the standing design's citation — the newest :design record and
   the baseline :seq it names — with :moved? when the subject is newer than what it cites: the
   design was decided on a reading this run is no longer judging, so its re-citation or
   re-survey is owed rather than implied. Absent when no design stands or it cites nothing.

   Read ONCE, before round 1. An unnamed run re-reads the newest entry each round; this says
   what it started on."
  [[project ws-id] kind named]
  (when project
    (let [newest  (ws/latest-entry project ws-id kind)
          subject (or named newest)]
      (when (:seq subject)
        (let [s      (long (:seq subject))
              n      (long (or (:seq newest) s))
              design (when (= :baseline kind) (ws/latest-entry project ws-id :design))
              cites  (get-in design [:baseline :seq])]
          (cond-> {:seq s :named? (some? named) :newest-at-start n}
            (> n s) (assoc :skipped {:seq n
                                     :supersedes-subject? (supersedes-reaches? project ws-id newest s)})
            cites   (assoc :premise (cond-> {:design-seq (:seq design) :cites (long cites)}
                                      (> s (long cites)) (assoc :moved? true)))))))))

(defn- tally
  "Per key, in how many of `rounds` it was broken, in how many it was the only thing broken, and
   whether it was broken in the last of them. `rounds` is each round's set of broken keys, in order.
   `asked`, when given, is each round's set of keys it asked a person about instead — never broken
   in that round — and a key asked in any round also carries :asked, how many, and :asked-at-end,
   whether in the last: outstanding at the end, as a broken one is, but the person's to answer."
  ([rounds] (tally rounds nil))
  ([rounds asked]
   (let [ks (into (sorted-set) cat (concat rounds asked))
         end (or (last rounds) #{})
         asked-end (or (last asked) #{})]
     (into (sorted-map)
           (for [k ks
                 :let [n (count (filter #(contains? % k) asked))]]
             [k (cond-> {:broken (count (filter #(contains? % k) rounds))
                         :alone  (count (filter #(= #{k} %) rounds))
                         :at-end (contains? end k)}
                  (pos? n) (assoc :asked n :asked-at-end (contains? asked-end k)))])))))

(defn- check-statuses
  "Each check a design decision derived, by name, with its status in either era's shape — :status on
   a current one, :held? on one from before the third outcome existed."
  [decision]
  (into {} (map (fn [{:keys [check status held?]}]
                  [check (cond status        status
                               (false? held?) :broken
                               :else          :held)]))
        (:checks decision)))

(defn- claim-finding?
  "Whether one of a design decision's findings is carried under the claim it is about rather than
   under a check. `statuses` is the decision's `check-statuses`.

   True when the finding names no check, or names one the same decision ruled held: either way it
   broke none of the four, and the check it names — if any — is the judge filing a defect under a
   derivation it then passed. Read off the decision's own statuses and not off the name, because a
   finding dropped for naming a held check is still repaired by the amender, which is handed the
   judge's raw findings, and a repair nobody counted is invisible to the report, the figures and
   every stall and dispute identity. False under a broken check, which carries it, and under an
   underivable one, which has no yardstick for an amender to answer to."
  [statuses {:keys [check]}]
  (not (#{:broken :underivable} (get statuses check))))

(defn- for-person?
  "Whether `f`, a finding a design round hands on from `decision` — a broken check, or a finding
   carried under its claim (`claim-finding?`) — is a person's to answer rather than an amender's to
   repair. A broken check is the person's when at least one finding is filed under it and every one
   is. One with none filed is the amender's: its :note is the defect, and withheld from the amender
   it reaches neither the amender nor the asks."
  [decision f]
  (if (:status f)
    (let [filed (filter #(= (:check f) (:check %)) (:findings decision))]
      (boolean (and (seq filed) (every? :for-person filed))))
    (boolean (:for-person f))))

(defn- asks-only?
  "Whether `decision` stops for a person with nothing an amender could repair first: it recommends
   :ask and every broken check and claim finding is the person's (`for-person?`). Such a round ends
   the run as a proceed does, so it rests on two readings as a proceed does (`with-readings`)."
  [decision]
  (let [statuses (check-statuses decision)]
    (and (= :ask (:recommend decision))
         (every? #(for-person? decision %)
                 (concat (broken-checks decision)
                         (filter #(claim-finding? statuses %) (:findings decision)))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  outstanding
  "What one design decision leaves open, split by whose it is to close:
   `{:broken #{key} :asked #{key}}`, each key `[:check c]` or `[:claim id]`.

   :broken is the amender's: each broken check, and each claim found against under none of the four
   derivations (`claim-finding?`) by its id, or \"the record\" for one naming none. :asked is the
   person's, and only on an :ask — what the question is about, so not a defect that went unrepaired:
   a broken check or a claim finding that is theirs (`for-person?`), and every underivable check,
   which an :ask carries because nothing else can derive it. A claim-less finding for the person is
   in neither: it is the ask itself, which `:asks` says and a subject key cannot."
  [decision]
  (let [statuses (check-statuses decision)
        person?  (if (= :ask (:recommend decision)) #(for-person? decision %) (constantly false))
        claims   (filter #(claim-finding? statuses %) (:findings decision))
        claim-of (fn [f] [:claim (or (not-empty (str (:claim-id f))) "the record")])]
    {:broken (-> #{}
                 (into (comp (remove person?) (map (fn [c] [:check (:check c)]))) (broken-checks decision))
                 (into (comp (remove person?) (map claim-of)) claims))
     :asked  (-> #{}
                 (into (comp (filter person?) (map (fn [c] [:check (:check c)]))) (broken-checks decision))
                 (into (comp (filter person?) (filter (comp not-empty str :claim-id)) (map claim-of)) claims)
                 (into (when (= :ask (:recommend decision))
                         (map (fn [c] [:check (:check c)]) (underivable-checks decision)))))}))

(defn- judge-name
  "Who answered a judgement, as the figures count it — the stand-in named with whom it stood in for.
   nil for a judgement appended before who answered was kept."
  [{:keys [reviewer instead-of]}]
  (when reviewer
    (str (name reviewer) (when instead-of (str " for " (name instead-of))))))

(defn- relation-flips
  "Per baseline id, how often `decisions` — one run's, in ledger order — ruled it the other way from
   the decision before: `{id {:stands->breaks n :breaks->stands n}}`, ids never flipped absent. Read
   as the judge ruled, so a reversal the round did not take (:relation-reversals) still counts: it
   is the judge's variance being measured, not what :breaks was held to."
  [decisions]
  (let [said (fn [d] (merge (into {} (map (juxt :id :ruling)) (:relation-rulings d))
                            (into {} (map (juxt :id :ruling)) (:relation-reversals d))))]
    (->> (map said decisions)
         (partition 2 1)
         (mapcat (fn [[before after]]
                   (for [[id r] after
                         :let [b (get before id)]
                         :when (and b (not= b r))]
                     [id (keyword (str (name b) "->" (name r)))])))
         (reduce (fn [acc [id k]] (update-in acc [id k] (fnil inc 0))) (sorted-map)))))

(defn- reading-flips
  "Per subject, how often `judgements` — one run's, in ledger order — ruled it holds where the
   judgement before it of the same record at the same tree ruled it owed, or the reverse:
   `{id {:holds->owed n :owed->holds n}}`, ids never flipped absent. Read as the judge ruled, so a
   flip counts whether or not the round paired anything over it: it is the judge's variance on
   at_this_tree being measured, which :unpaired records only where a pairing was at stake. A
   judgement with no :code-identity read no one tree, and is compared with nothing."
  [judgements]
  (->> (vals (group-by (juxt :format :baseline-seq :design-seq) judgements))
       (mapcat #(partition 2 1 %))
       (filter (fn [[a b]] (and (:code-identity a) (= (:code-identity a) (:code-identity b)))))
       (mapcat (fn [[a b]]
                 (let [was (rulings a)]
                   (for [[id now] (rulings b)
                         :let [w (was id)]
                         :when (and w (not= w now))]
                     [id (keyword (str (name w) "->" (name now)))]))))
       (reduce (fn [acc [id k]] (update-in acc [id k] (fnil inc 0))) (sorted-map))))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] :map]}
  run-figures
  "What one record run's rounds did, from the entries it appended, in the ledger's order: its design
   decisions give each derived check's figures and each claim refuted with no check broken, and its
   baseline reviews — a baseline run's own, or a design run's re-survey — give each derivation's gaps
   and the claims found false.

     {:decisions n :checks      {check {:derived n :held n :broken n :underivable n
                                        :alone n :at-end bool}}
                   :claims      {claim-id {:broken n :alone n :at-end bool}}
                   :asks        {:rounds n :at-end bool}
                   :strata      {stratum {:read n :fits n :widens n :misplaced n :not-a-level n :failed n}}
      :reviews   n :derivations {derivation {:broken n :alone n :at-end bool}}
                   :falsified   {claim-id n}
      :confirmed {id n}
      :owed      {id n}
      :judged-by {reviewer n}
      :unruled   {id n}
      :unchecked {id n}
      :still-unchecked [id]
      :relation-unruled {id n}
      :settled-then-found {id n}
      :relation-flips     {id {:stands->breaks n :breaks->stands n}}
      :relation-reversals {id n}
      :reading-flips      {id {:holds->owed n :owed->holds n}}
      :splits             {id n}
      :spent              {id n}}

   Every check a decision derived has a row, so a run whose checks all held says what it answered
   rather than printing an empty map. :alone and :at-end are over every defect a decision found — a
   broken check or a check-less refutation — so a check is never `alone` in a round that also
   refuted a claim. A gap is counted under the derivation it :blocks, whatever the review's verdict
   and whatever claim it cites; :falsified counts the findings of a falsified review that block none.

   What a decision asked a person about is not a defect (`outstanding`): a check or claim the
   question is about carries :asked n and :asked-at-end beside :broken, and is never counted broken
   in that round, so a run that ended on a question does not read as one that ended unrepaired.
   :asks counts the decisions that recommended :ask, and says whether the last did — the one figure
   for a round that asked and filed nothing the question could be keyed on.

   :confirmed counts, per subject, the judgements of either kind that confirmed it, and :owed the
   decisions that held it sound but not yet met at the tree they read — so a claim read every round
   is not absent from the figures because the build had not reached it. :judged-by counts
   judgements per reviewer that answered, a stand-in as `claude for codex`, so a comparison across
   runs can hold the instrument fixed; a judgement from before that was kept counts in neither.
   :unruled counts, per subject, the judgements handed it as a check that left it without a ruling;
   :relation-unruled, per baseline id, the decisions that left it without a relation ruling.
   :unchecked counts, per subject, the judgements that declared it unchecked, and :still-unchecked
   names the subjects whose last reading in the run did (`unchecked-running`) — what the run ended
   without anyone ruling on, which a sufficient status does not say.
   :settled-then-found counts, per subject, the judgements that found against it while it was
   settled (`:overrides-settled`) — beside :confirmed, the rate at which settlement shields a false
   confirmation. Each of these eight is present only when non-empty.

   :relation-flips counts, per baseline id, the decisions that ruled it the other way from the one
   before (`relation-flips`), and :relation-reversals those of the flips the round did not take, for
   a reversal on a record that had not moved. The first is the judge's variance on relation-honest,
   the second how much of it nido kept from becoming an amend round. Both present only when non-empty.

   :reading-flips counts, per subject, the judgements that ruled it holds where the one before of
   the same record at the same tree ruled it owed, or the reverse (`reading-flips`), and :splits
   the judgements that refuted it after such a reading held or owed it (`:splits`): the judge's
   variance on one record at one tree, which the run's last ruling hides. Both present only when
   non-empty.

   :spent is the run's last judgement's :spent: each subject still refuted at the end, with how many
   readings running its lineage has refuted it — across runs, so a claim refuted for the eleventh
   time does not read as a new defect beside :claims. Present only when non-empty.

   Read from the decisions rather than the run's report, because a decision holds every check's
   status and it outlives the run dir. Derived on every read; nothing stores it."
  [entries]
  (let [decisions (filterv #(= :design-decision (:format %)) entries)
        reviews   (filterv #(= :baseline-review (:format %)) entries)
        judgements (concat decisions reviews)
        unruled   (frequencies (mapcat :unruled judgements))
        unchecked (frequencies (mapcat #(map :id (:unchecked %)) judgements))
        ;; By :seq across both kinds, because `entries` arrive kind by kind and a design run's
        ;; re-survey interleaves its reviews with the decisions. Stable, so entries with none keep
        ;; the order they came in.
        still     (keys (unchecked-running (sort-by :seq judgements)))
        spent-end (:spent (last (sort-by :seq judgements)))
        relation-unruled (frequencies (mapcat :relation-unruled decisions))
        confirmed (frequencies (mapcat :confirmed judgements))
        owed      (frequencies (mapcat :owed decisions))
        overridden (frequencies (mapcat #(map :id (:overrides-settled %)) judgements))
        judges    (frequencies (keep (comp judge-name :judged-by) judgements))
        flips     (relation-flips decisions)
        reversals (frequencies (mapcat #(map :id (:relation-reversals %)) decisions))
        rflips    (reading-flips (sort-by :seq judgements))
        splits    (frequencies (mapcat #(map :id (:splits %)) judgements))
        statuses  (mapv check-statuses decisions)
        ;; One round's defects and what it asked about, each tagged by the tally it belongs to: a
        ;; check keyword and a claim id do not compare, and `alone` has to see both.
        open      (mapv outstanding decisions)
        defect-tally (tally (mapv :broken open) (mapv :asked open))
        asking    (mapv #(= :ask (:recommend %)) decisions)
        of-kind   (fn [kind] (into (sorted-map)
                                   (keep (fn [[[k v] figures]] (when (= kind k) [v figures])))
                                   defect-tally))
        derived   (fn [c status] (count (filter #(= status (get % c)) statuses)))]
    (cond-> {}
      (seq unruled)   (assoc :unruled (into (sorted-map) unruled))
      (seq unchecked) (assoc :unchecked (into (sorted-map) unchecked))
      (seq still)     (assoc :still-unchecked (vec (sort still)))
      (seq relation-unruled) (assoc :relation-unruled (into (sorted-map) relation-unruled))
      (seq confirmed) (assoc :confirmed (into (sorted-map) confirmed))
      (seq owed)      (assoc :owed (into (sorted-map) owed))
      (seq overridden) (assoc :settled-then-found (into (sorted-map) overridden))
      (seq judges)    (assoc :judged-by (into (sorted-map) judges))
      (seq flips)     (assoc :relation-flips flips)
      (seq reversals) (assoc :relation-reversals (into (sorted-map) reversals))
      (seq rflips)    (assoc :reading-flips rflips)
      (seq splits)    (assoc :splits (into (sorted-map) splits))
      (seq spent-end) (assoc :spent (into (sorted-map) spent-end))

      (seq decisions)
      (assoc :decisions (count decisions)
             :checks    (let [broken (of-kind :check)]
                          (into (sorted-map)
                                (for [c (into (sorted-set) (mapcat keys) statuses)]
                                  [c (merge {:derived     (count (filter #(contains? % c) statuses))
                                             :held        (derived c :held)
                                             :underivable (derived c :underivable)}
                                            (get broken c {:broken 0 :alone 0 :at-end false}))]))))

      (seq (of-kind :claim))
      (assoc :claims (of-kind :claim))

      (some true? asking)
      (assoc :asks {:rounds (count (filter true? asking)) :at-end (last asking)})

      (some :strata-read decisions)
      (assoc :strata (reduce (fn [acc {:keys [stratum verdict]}]
                               (update acc stratum
                                       #(-> (or % {:read 0 :fits 0 :widens 0 :misplaced 0 :not-a-level 0 :failed 0})
                                            (update :read inc)
                                            (update (or verdict :failed) inc))))
                             (sorted-map)
                             (mapcat :strata-read decisions)))
      (seq reviews)
      (assoc :reviews     (count reviews)
             :derivations (tally (mapv #(into #{} (keep :blocks) (:findings %)) reviews))
             :falsified   (into (sorted-map)
                                (frequencies
                                 (for [r reviews :when (= :falsified (:verdict r))
                                       f (:findings r) :when (and (:claim-id f) (nil? (:blocks f)))]
                                   (:claim-id f))))))))

(defn- numbered-line
  "One of `raised` as the amender's numbered line, over `findings`, the judge's findings it was
   handed. A broken check with findings filed under it is named by their claim ids and reads as
   their claims: those are the case the amender answers, and the check's :note may be about a claim
   that went to the person — an amender shown it is handed the person's question as a repair. A
   check with none filed reads as its note; any other line as its claim."
  [{:keys [check claim-ids note claim status] :as f} findings]
  (let [ids   (set claim-ids)
        filed (when status
                (filter #(and (= check (:check %))
                              (or (empty? ids) (contains? ids (:claim-id %))))
                        findings))]
    (str (design-finding-label f)
         (when (and check (seq claim-ids)) (str " [" (str/join ", " claim-ids) "]"))
         " — "
         (if (seq filed) (str/join "; " (map :claim filed)) (or note claim)))))

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  design-amend-prompt
  "Instruction to repair a design record the derivation found wanting.

   :recut and :amend are given different jobs, because saying the wrong one is
   worse than saying nothing: a decomposition that does not hold is not fixed by
   restating claims, and a claim that is wrong is not fixed by re-cutting layers.

   `:raised` is what the round hands over, numbered — a broken derivation, or a
   finding that broke none of the four and is about a claim instead. Both are
   answerable and both are disputable, and the number is how: the amender
   objects by ordinal and never by matching text. `:findings` is the judge's own
   prose beneath them, which says more and is keyed to nothing. `:rulings` is the judge's per-id
   relation-honest ruling, printed beside each baseline id the amender has to hold :breaks to.
   `:refuted-running` is `refuted-running` over the design's lineage, this round included: a subject
   a line refutes that it counts at `withdrawable-after` or more is handed over as spent, and may not
   be reworded at the same strength again. `:resurvey-outcome` (`resurvey-outcome`) is what a
   :resurvey's nested loop did to the premise: only a :corrected one may be called corrected, and
   only it moves the citation."
  [{:keys [design baseline recommend reason asks raised findings rulings out-path declared? check-cmd
           settled refuted-running resurvey-outcome]}]
  (str
   "A read-only judge derived what could be derived about this DESIGN record,\n"
   "before any code is written, and it did not come out clean.\n\n"
   (case recommend
     :recut (if (strata-era? design)
              (str "It says the DECOMPOSITION does not hold. Re-place it. The claims may be\n"
                   "right; which strata the change sits in, or what it adds to them, is what\n"
                   "is wrong, and restating the claims will not fix it.\n\n")
              (str "It says the DECOMPOSITION does not hold. Re-cut it. The claims may be\n"
                   "right; how the change is split into layers or phases is what is wrong,\n"
                   "and restating the claims will not fix it.\n\n"))
     :resurvey (case resurvey-outcome
                 :held
                 (str "It said the PREMISE was wrong. The baseline was re-surveyed: a judge read it\n"
                      "against the code — every baseline claim a finding below names among what it\n"
                      "was asked — and it held as written. Nothing was corrected; the baseline below\n"
                      "is the one the design already cites, so keep :baseline :seq where it is.\n\n"
                      "The findings below still stand against this design. Where one is right about\n"
                      "the design, repair the design. Where one says a baseline claim is false, the\n"
                      "re-survey's judge read that claim and upheld it: dispute the numbered line,\n"
                      "citing the re-survey's review.\n\n")
                 :carried
                 (str "It said the PREMISE was wrong, and the re-survey launched NO judge: every\n"
                      "subject of the baseline was settled and no finding named one of its claims,\n"
                      "so an earlier review was carried forward. The baseline below has NOT been\n"
                      "re-checked against this round's findings, and does not hold because of them.\n\n"
                      "Repair the design where a finding is about the design. Where one is about the\n"
                      "baseline, dispute the numbered line saying which baseline claim it falsifies:\n"
                      "the baseline has to be re-surveyed by hand, and no amendment of the design can\n"
                      "make it true.\n\n")
                 (str "It said the PREMISE was wrong — and the baseline has since been\n"
                      "re-surveyed and corrected. The corrected baseline is below.\n\n"
                      "Re-state this design against it. That is not a re-citation: the\n"
                      "design's claims were made about a reading of the area that has\n"
                      "changed, so each one has to be checked against the new baseline and\n"
                      "may not survive it. "
                      ;; The number, not "the corrected baseline". The record below
                      ;; is printed unstamped — :seq is the ledger's and a record
                      ;; carrying one is refused on write — so an amender told to
                      ;; cite it by :seq and shown no :seq anywhere is being asked
                      ;; to guess the one field the citation is checked against.
                      (if-let [n (:seq baseline)]
                        (str "Set :baseline :seq to " n " — that is the entry the\n"
                             "corrected baseline was appended as.\n\n")
                        "Point :baseline :seq at the corrected baseline.\n\n")
                      "If a claim no longer holds under the corrected baseline, change it.\n"
                      "Re-pointing the citation while leaving the claims untouched asserts\n"
                      "the design still stands on a premise nobody has re-checked it\n"
                      "against, which is the failure this whole round exists to catch.\n\n"))
     (str "It says the RECORD has a derivable defect. Repair the record — the\n"
          "commitment may well be sound, and its decomposition with it.\n\n"))
   "Your job is to make the record TRUE and coherent. It is NOT to make the\n"
   "checks pass. Lowering the effort, softening :revisit to :within, dropping\n"
   "invariants or routing work away from :fix-here all quiet a check without\n"
   "making anything truer; every one of those is measured and reported to the\n"
   "human this decision escalates to.\n\n"
   "WHY: " reason "\n\n"
   "THE CURRENT DESIGN:\n\n" (pr-str (ws/unstamp design))
   (when baseline
     (str "\n\nTHE BASELINE IT CITES:\n\n" (pr-str (ws/unstamp baseline))))
   "\n\nWHAT THE ROUND FOUND WANTING — numbered, and you answer them by number.\n"
   "A line naming one of the four derivations is that derivation failing; any\n"
   "other line is a defect in the record that breaks none of them:\n\n"
   (str/join
    "\n"
    (let [spent (design-spent raised refuted-running)]
      (map-indexed (fn [i f]
                     (str (inc i) ". " (numbered-line f findings)
                          (when-let [ns (seq (keep #(some->> (get spent %) (vector %)) (refuted-ids f)))]
                            (str "\n   running: "
                                 (str/join ", " (for [[id n] ns] (str "[" id "] refuted " n " readings in a row")))
                                 " — see what follows the numbered lines"))))
                   raised)))
   (design-withdrawal-block (design-spent raised refuted-running) (enumerated-ids design baseline))
   (when (seq findings)
     (str "\n\nWHAT THE DERIVATION FOUND:\n\n"
          (str/join
           "\n\n"
           (map (fn [f]
                  (str "- refutes: " (str/join "; " (:cites f)) "\n"
                       "  claim:   " (:claim f)
                       (when (seq (:evidence f))
                         (str "\n  evidence: " (str/join ", " (:evidence f))))))
                findings))))
   (some->> (bearing-block (bearing-subjects {:record design :baseline baseline :settled settled
                                              :findings (concat raised findings)}))
            str/trimr (str "\n\n"))
   (when-let [ids (seq (relation-yardstick design baseline))]
     (str "\n\nTHE BASELINE'S IDS, which relation-honest is ruled on one by one"
          (if (seq rulings) ", as the judge ruled them" " — the judge ruled on none")
          ".\n"
          "Whatever else you change, :baseline :breaks names exactly the ids this record stops\n"
          "being true of — each one ruled breaks, none ruled stands — and the next judge rules on\n"
          "every id again and holds :breaks to it. Go through the whole list, not only the ids a\n"
          "line above names: a :breaks repaired one omission at a time takes a round per omission.\n"
          "Where a ruling is wrong about the code, dispute the relation-honest line instead.\n"
          (relation-ids-lines ids "  " (into {} (map (juxt :id identity)) rulings))))
   ;; A design in the shared model states elements of its own, and they and its claims carry
   ;; readings — which the ledger refuses outside the registry whichever record they are on.
   (when (contains? design :model)
     (str "\n\nA reading may only use its own lens's verdicts, and a lens only reads the\n"
          "subject it is about. The ledger refuses anything else and the whole record\n"
          "is lost with it, so use these and nothing else:\n"
          (lens-block)))
   (some->> (coupled-edits :design design) str/trimr (str "\n\n"))
   "\n\nCHANGE ONLY WHAT WAS REFUTED. Every check is re-derived over the WHOLE\n"
   "record each round, so a part nobody challenged that you restate anyway is a\n"
   "fresh chance for a check that held to stop holding. Rounds have gone by\n"
   "watching one derivation get answered while a rewritten neighbour became the\n"
   "next one to fail. Fix what failed; leave the rest exactly as it stands.\n\n"
   (str/trimr stale-rule) "\n"
   "\n" (str/trimr (sound-rewrite-rules (some? baseline))) "\n"
   (when-not (str/blank? (str asks))
     (str "\n\nWHAT THE JUDGE LEFT FOR A PERSON — not yours to answer:\n  " asks "\n"))
   "\n\nIF A NUMBERED LINE IS WRONG ABOUT THE CODE, SAY SO INSTEAD OF AMENDING FOR IT,\n"
   "under :disputes. The judge is asked again with your objection in front of it.\n\n"
   "IF A NUMBERED LINE'S REPAIR — or what is left of it once you have repaired what\n"
   "this record can — LIES OUTSIDE THIS RECORD, put it under :out-of-reach: an edit\n"
   "of the intent, a follow-up ref someone has to file, a scope decision, the\n"
   "question left for a person. Narrowing the scope, dropping what the intent asks\n"
   "for, or reversing a decision the record's :open states is answering it, and you\n"
   "do not settle it. nido stops and asks the person with that line attached; no\n"
   "judge round is spent re-deriving what you already said.\n"
   "An objection with no reason is dropped, under either key.\n\n"
   "Write EDN to:\n\n  " out-path "\n\n"
   "  {:record       <the COMPLETE superseding design — every field, not a diff>\n"
   "   :disputes     [{:finding 1 :because \"...\" :evidence [\"src/x.clj:41\"]}]\n"
   "   :out-of-reach [{:finding 2 :because \"...\"}]}\n\n"
   "Omit :record if the design needs no change.\n"
   stale-field "\n"
   "Write it in the shared model — :model {:elements :claims} in place of\n"
   ":invariants, with :holds keyed by claim id when the design is phased — whatever\n"
   "shape the current one is in. An invariant becomes a claim with an id, the\n"
   "elements it is about, what would falsify it and {:by :round} evidence.\n"
   (element-id-rule declared?)
   (when declared?
     (str "So where the corrected design moves or adds an element, change its declaration\n"
          "under canvas/ to match: that edit is part of this amendment, and the next round\n"
          "judges it with the record. Any other file in the tree you only read — a write\n"
          "there is denied.\n"))
   "\n" amender-reading
   (check-block check-cmd)
   "nido reads this file, validates it, and appends it as the superseding design.\n"
   "Do not append it yourself and do not commit anything.\n\n"
   ;; The record above is printed unstamped, so an amender asked for this field
   ;; guesses — the entry it read the findings from, or the citation already on
   ;; the record — and either guess loses the amendment or forks the lineage.
   "LEAVE :supersedes :seq TO THE LOOP. It sets it to "
   (if-let [n (:seq design)] (str "entry " n ", ") "")
   "the design shown above,\n"
   "which is what you are replacing. Give :supersedes {:why \"...\"} only to say why\n"
   "in words of your own; that is kept.\n"
   (level-reminder :commitment)))

(defn- run-design-judge-stage
  [ctx]
  (let [{:keys [cwd code-cwd run-id reviewer]} (:config ctx)
        ;; What the judge is not asked to check, read at the tree it is about to read, from
        ;; every ledger the design's unit reaches — never this run's history. A role's players
        ;; are the effective model's, as the round resolves them: a role kept from the baseline
        ;; is not restated, and its players are still what a claim about it rests on.
        [project ws-id :as ledger] (ledger-of (:config ctx))
        design  (when project (ws/latest-entry project ws-id :design))
        {:keys [listing reading]} (reading-for project (or code-cwd cwd) design)
        {:keys [standing single settled prior]}
        (judge-inputs project ws-id :design design reading (when design (effective-design ledger design)) run-id
                      (get-in ctx [:carry :stale]))
        ;; A decision that would not proceed, standing at this tree: the round points at it rather
        ;; than asking a judge again, and goes on as that decision sends it — to the amender, or to
        ;; the person it asks.
        stands? (complement report/proceeds?)
        lost    (ledger-lost ctx ledger)
        reused  (standing-judgement ctx ledger :design-decision :design-seq design reading stands?)
        again   (rejudged ctx ledger design reading
                          (newest-judgement ledger :design-decision :design-seq design) stands?)
        asked   (cond reused #{} design (decision-asked design settled))
        judged  (when-not (or lost reused)
                  (design-decision!
                   {:cwd cwd :ledger ledger :code-cwd code-cwd :run-id run-id :reviewer reviewer
                    :design design :settled settled :prior prior :listing listing
                    :code-identity (:code-identity reading)
                    :subject-identities (:subject-identities reading)
                    :label (str "design-decision-round-" (:iter ctx))
                    :disputes (disputes-for-judge (:history ctx))}))
        record (or lost
                   reused
                   (-> (dissoc judged :tree-moved)
                       (stamp-run (:config ctx))
                       (with-readings #(or (report/proceeds? %) (asks-only? %))
                                      {:standing standing :single single :settled settled :prior prior
                                       :carried (carried-readings ctx design)
                                       :asked asked
                                       :unread-amendment? (unread-amendment? ctx)})))
        ;; As the baseline round counts it, over the design's own lineage and this decision — which,
        ;; reused, the lineage already holds.
        lineage (if (and project design)
                  (lineage-of project ws-id :design-decision :design-seq design)
                  [])
        running (when-not (:outcome record)
                  (refuted-running design (into lineage (when-not reused [record]))))
        ;; Every append below writes `record`; a reused one is on the ledger already.
        put!    (fn [ledger record] (if reused (reused-answer reused) (append! ledger record)))
        record (cond-> record (seq (spent running)) (assoc :spent (spent running)))
        ctx    (merge (-> (assoc ctx :settled settled :refuted-running running)
                          (update :carry dissoc :stale)
                          (cond-> ledger (assoc-in [:carry :ledger] ledger)))
                      (when (:seq design) {:judged-seq (:seq design)})
                      (when-let [m (:tree-moved judged)] {:tree-moved m})
                      (when again {:rejudged again})
                      (when-let [c (seq (continues run-id (:iter ctx) standing running lineage))]
                        {:continues (vec c)})
                      (when design (banking asked reading record)))
        traj   (trajectory (:history ctx))
        ;; The status a run ends on is a claim that the ledger holds this decision: an :asked
        ;; whose question was never written is a question no grant can answer and no later round
        ;; reads. So an append that wrote nothing ends the run :unrecorded, saying what it would
        ;; have ended as, rather than as the status the ledger cannot back.
        final! (fn [c]
                 (let [answer (put! ledger (cond-> record (seq traj) (assoc :trajectory traj)))]
                   (if (:seq answer)
                     (with-appended c answer)
                     (assoc c :status :unrecorded :control :escalate
                            :unrecorded {:would-have-ended (:status c)
                                         :ledger (when project (str (name project) "/" ws-id))}))))]
    (cond
      (:outcome record)
      (with-appended (assoc ctx :record record :status (:outcome record))
                     (put! ledger record))

      ;; Would proceed, or stop for a person over nothing derivable (`asks-only?`), on claims it read
      ;; once or on an amendment's first reading: appended as the reading it is — it does not
      ;; proceed, so it clears nothing — and read again before a person is asked, so a person is not
      ;; asked over a derivable defect one judge missed.
      (read-once? record)
      (let [answer (put! ledger record)]
        (with-appended (second-reading (assoc ctx :record record :findings []
                                              :underivable (underivable-checks record))
                                       design record)
                       answer))

      ;; The judge's own recommendation, or — whatever it recommended — a round
      ;; whose only broken check is the advisory one: `report/proceeds?`, the
      ;; same answer the clearance, the position and the grant read. The second
      ;; is a guard and not a courtesy: the routing lives in the prompt, so
      ;; without it the rule is a third soft bar beside the two that have already
      ;; failed here. The broken check stays on the record, so the human still
      ;; reads the complaint; what it stops doing is spending a round on an
      ;; amender.
      (report/proceeds? record)
      ;; Appending a proceeding decision is what may clear the design, so the
      ;; escalation is decided AFTER the append: a design whose own
      ;; declarations owed nobody has just been cleared, and parking it for a
      ;; person would put back the gate the clearance removed — the driver maps
      ;; :proceed to :escalate and would append a blocker that outranks the
      ;; clearance on the next tick. A clearance still owed and never written is
      ;; a write, not an ask, for the same reason — see `proceeding-status`.
      (let [answer (put! ledger (cond-> record (seq traj) (assoc :trajectory traj)))
            status (proceeding-status ledger record answer)]
        (with-appended (assoc ctx :record record :findings []
                              :underivable (underivable-checks record)
                              :status status
                              :control (case status
                                         :cleared :advance
                                         :proceed :escalate
                                         :stop))
                       answer))

      :else
      (let [filed     (fn [{c :check}] (filter #(= c (:check %)) (:findings record)))
            ;; A broken check's filings by whose they are, each half its own handle: [person? filings].
            ;; Handed whole, a check mixing the two names the person's claims to the amender, which
            ;; then disputes a line it half repaired, and the dispute is keyed on the claim it
            ;; conceded. Only an :ask marks a finding the person's, so on any other recommendation
            ;; this is the whole check, as one. A check with nothing filed is the amender's
            ;; (`for-person?`).
            halves    (fn [c]
                        (let [all (filed c)
                              person (filterv :for-person all)
                              derivable (filterv (complement :for-person) all)]
                          (cond-> []
                            (or (seq derivable) (empty? all)) (conj [false derivable])
                            (seq person)                      (conj [true person]))))
            claims-of #(into [] (comp (keep (fn [f] (not-empty (str (:claim-id f))))) (distinct)) %)
            ;; What a broken check with no claim to name is told apart by, and what tells one
            ;; counterexample to it from the next: the code its findings cite.
            evidence-of #(into [] (comp (mapcat :evidence) (distinct)) %)
            handle    (fn [f] (assoc f :disputed-n (disputed-n (:history ctx) design-finding-base-key f)))
            ;; A finding that broke none of the four, carried under the claim it is about.
            ;; `amend` is `a derivable defect in the record itself` and `resurvey` is `the
            ;; PREMISE is wrong`, and neither has a check a finding could name, so every
            ;; finding motivating one names none BY CONSTRUCTION — which is also the shape
            ;; `settled-block` asks the judge for when a settled claim turns out false. A
            ;; round that carried only its broken checks would hand an amender nothing on
            ;; exactly the two recommendations that mean repair the record. One filed under a
            ;; check this decision ruled held is carried the same way, the check it named kept
            ;; as :filed-under: the amender is handed it regardless, so leaving it out here
            ;; only hides the repair from the report and from every identity read off these.
            statuses       (check-statuses record)
            claim-findings (into [] (comp (filter #(claim-finding? statuses %))
                                          (map #(handle (cond-> (-> %
                                                                    (dissoc :check)
                                                                    (assoc :claim-ids
                                                                           (into [] (keep (comp not-empty str))
                                                                                 [(:claim-id %)])))
                                                          (:check %) (assoc :filed-under (:check %))))))
                                 (:findings record))
            findings  (into (vec (for [c (broken-checks record)
                                       [person? fs] (halves c)]
                                   (handle (cond-> (assoc c :claim-ids (claims-of fs))
                                             (seq (evidence-of fs)) (assoc :evidence (evidence-of fs))
                                             person?                (assoc :for-person true)))))
                            claim-findings)
            disputed?   #(>= (:disputed-n %) 2)]
        (cond
          ;; The judge's own stop for a person. What reaches the amender first is only what the
          ;; question does not cover — a derivable defect left for the person would be asked over,
          ;; and re-found by the run that resumes after the answer. Nothing derivable left, or a
          ;; derivable finding already objected to twice, and the round stops for the person
          ;; holding every finding it made. Going on to the amender, the round keeps the person's
          ;; as :asked-findings — out of :findings, which is what the amender answers and what
          ;; the loop counts attempts and stalls on, but in the round's history (`trajectory`).
          (= :ask (:recommend record))
          (let [derivable (filterv (complement :for-person) findings)]
            (if (and (seq derivable) (not-any? disputed? derivable))
              (with-appended (cond-> (assoc ctx :record record :findings derivable
                                            :underivable (underivable-checks record))
                               (some :for-person findings)
                               (assoc :asked-findings (filterv :for-person findings)))
                             (put! ledger record))
              (final! (assoc ctx :record record :findings findings
                             :underivable (underivable-checks record)
                             :control :escalate :status :asked))))

          (some disputed? findings)
          (final! (assoc ctx :record record :findings findings
                         :underivable (underivable-checks record)
                         :control :escalate :status :disputed))

          (seq findings)
          (with-appended (assoc ctx :record record :findings findings
                                :underivable (underivable-checks record))
                         (put! ledger record))

          ;; Nothing to repair, and claims or relation ids it was handed that it neither confirmed
          ;; nor refuted: a proceed over them does not proceed (`report/proceeds?`), so the round is
          ;; asked again before a person is.
          (or (seq (:unruled record)) (seq (:relation-unruled record)))
          (let [c (unruled-stop (assoc ctx :record record :findings []
                                       :underivable (underivable-checks record)))]
            (if (= :unruled (:status c))
              (final! (assoc c :control :escalate))
              (with-appended c (put! ledger record))))

          ;; Nothing an amender could repair, and a check the round could not derive at
          ;; all: what is left is the missing yardstick. An amender told to fix one would
          ;; amend a true record until the complaint stopped. Read off the CHECKS and
          ;; never off the absence of findings — a round can find nothing for reasons
          ;; that have nothing to do with a yardstick, and this status is a claim about
          ;; the yardstick.
          (seq (underivable-checks record))
          (final! (assoc ctx :record record :findings []
                         :underivable (underivable-checks record)
                         :control :escalate :status :underivable))

          ;; The round will not proceed, derived every check, broke none, and made no
          ;; finding. It has contradicted itself, so there is nothing to hand an amender and
          ;; no yardstick to blame. The decision is appended and its reason is what the
          ;; person reads.
          :else
          (final! (assoc ctx :record record :findings []
                         :underivable []
                         :control :escalate :status :nothing-to-amend)))))))

(def design-judge-stage
  "Derive everything derivable, and stop the moment nothing is left.

   Six ways to end here and only one of them is convergence-shaped. :asked
   escalates whoever the design owes: the judge said the repair is a person's
   decision, and an amender would make it for them. An ask that also found a
   derivable defect — a finding not marked :for-person — is a round first: that
   defect is the amender's, so the person is asked over a record already repaired
   of everything they were not needed for. :proceed
   escalates because the ask is the point — unless nobody is owed one: a design
   the round cleared advances, and one whose clearance is still unwritten ends
   :clearance-contended, which the clearance stage finishes without re-running
   this round. A round holding nothing to repair and a check it could not derive
   ends :underivable, because there is nothing an amender could do about a
   missing yardstick. One holding nothing to repair and no such check ends
   :nothing-to-amend: it would not proceed and named nothing, which is the judge
   contradicting itself. A finding stated a third time after two objections
   escalates. A round that would proceed but left claims it was handed :unruled,
   or baseline ids :relation-unruled, does not proceed: it is judged once more, and still unruled it escalates
   :unruled. Nor does one that would proceed on a claim it confirmed on a first
   reading: it is appended :read-once and judged again (`second-reading`), so a
   proceed rests on two consecutive clean readings — and so does an :ask with
   nothing derivable, the other round that ends the run. Everything else is another
   round.

   What reaches the amender is every finding the round made, whether it broke one
   of the four derivations or none — one filed under a check the round ruled held
   broke none. `Nothing broke` is not `nothing to repair`:
   an amend or a resurvey has no check its findings could name, so on exactly the
   recommendations that mean repair the record, the checks say nothing."
  {:name :judge
   :run
   run-design-judge-stage})

(defn- resurvey-refutations
  "What the design round in `ctx` found against `cited`'s own claims, as `settled/prior-findings`
   shapes it — `{id {:ws-id :seq :finding :restated? false}}` — for the nested baseline loop's
   `:refuted`. Only ids `cited` carries as subjects: a finding about the design's own claims is not
   the baseline's to answer. `:seq` is the decision that found it, nil when none was appended."
  [ctx ws-id cited]
  (let [subjects (settled/subjects cited)
        n        (or (:appended-seq ctx) (:reused-seq ctx))]
    (into {} (for [f  (:findings ctx)
                   id (:claim-ids f)
                   :when (contains? subjects id)]
               [id {:ws-id ws-id :seq n :restated? false
                    :finding {:claim-id id
                              :claim    (or (:claim f) (:note f))
                              :cites    (vec (:cites f))
                              :evidence (vec (:evidence f))}}]))))

(defn- resurvey-outcome
  "What a :sufficient nested baseline loop `out` did to the premise: :corrected when it appended a
   baseline, :held when a judge read the cited one and it stood, :carried when the run ended on a
   review no judge reached (`carried-review`) — the premise was not re-checked at all."
  [out]
  (cond
    (get-in out [:carry :under-repair])  :corrected
    (get-in out [:record :carried-from]) :carried
    :else                                :held))

(defn- resurvey!
  "Repair the premise by running the baseline loop, then come back.

   The nested loop is handed what this round found against the cited baseline's claims
   (`resurvey-refutations`), which it may not leave settled: a re-survey ordered over a claim must
   put that claim to a judge, whatever its confirmations said before. What came of it travels as
   `:resurvey-outcome`, because the amender is told different things for a corrected premise, one
   a judge upheld, and one nobody read.

   The nested loop emits nothing into this run's report: its rounds are not this
   run's rounds, and folding them in would renumber both. What it does write is
   the ledger — every baseline review and every superseding baseline — so the
   trajectory survives where a reader looks for it.

   Any non-:sufficient outcome is terminal HERE. A design round cannot proceed on
   a baseline the baseline loop could not make true, and re-judging the design
   against it would produce a decision built on the premise that just failed.

   Uncapped. A re-survey is only half a repair — the design is re-stated against
   the corrected baseline afterwards — so every cycle changes the record the next
   round judges, and the engine's own stall detector is what ends a run that has
   stopped getting anywhere. A count would stop it while it was still making
   progress, which is the one thing a convergence loop must not do."
  [ctx]
  (let [{:keys [cwd survey-cwd run-id budget reviewer]} (:config ctx)
        [project ws-id] (ledger-of (:config ctx))
        n   (count (filter :resurveyed (:history ctx)))
        nested-id (str run-id "-resurvey-" (inc n))
        ;; The baseline the design was JUDGED against, which is the only one whose
        ;; repair can change the verdict. A workstream may hold several — a
        ;; narrow follow-up written beside the broad baseline it came out of — and
        ;; repairing the newest instead would leave the cited one untouched
        ;; however many rounds it ran.
        cited (stages/cited-baseline [project ws-id] (ws/latest-entry project ws-id :design))
        ;; Not the design round's tree: that one carries the design's declaration,
        ;; which a baseline describing the area before the change must not be
        ;; judged against. A tree the caller named is read as given.
        reading (if survey-cwd {:dir survey-cwd} (tree/reading :baseline project cwd))
        survey (fn [dir]
                 (rloop/run-loop {:cwd cwd
                                  :ledger (when project [project ws-id])
                                  :code-cwd dir
                                  :judged-tree (tree/stamp reading dir)
                                  :run-id nested-id
                                  ;; Named, not left in the id's suffix: its reviews are read as this
                                  ;; run's by this field, never by parsing the id they were given.
                                  :within-run run-id
                                  :budget budget
                                  :reviewer reviewer
                                  :emit (fn [_])
                                  :baseline    cited
                                  :refuted     (not-empty (resurvey-refutations ctx ws-id cited))
                                  :pipeline    baseline-pipeline
                                  :judged-after :judge
                                  :finding-key baseline-finding-key
                                  :changed?    baseline-round-changed?}))
        out (if survey-cwd
              (survey survey-cwd)
              (tree/with-reading! cwd reading nested-id
                                  (str (fs/path (cstate/run-dir nested-id) "tree")) survey))]
        (if (= :sufficient (:status out))
          ;; No history entry here. The re-survey is only HALF the repair — the
          ;; design still cites the baseline that was wrong — so the round is not
          ;; over, and the amendment that finishes it records them together.
          ;;
          ;; The corrected baseline travels as a VALUE. Reading it back as "the
          ;; latest baseline" would hand the design amender whatever was
          ;; appended last, which is how the citation came to point at a baseline
          ;; of a different area in the first place.
          (assoc ctx :resurveyed (:status out)
                 :resurvey-outcome (resurvey-outcome out)
                 :resurveyed-baseline (or (:under-repair (:carry out)) cited))
          ;; The nested failure's DETAIL travels with its status. Without it the
          ;; terminal says :resurvey-amend-invalid and stops — the one shape a
          ;; judgment surface must not take, since a reader cannot act on a
          ;; refusal whose reason stayed inside a loop they never saw.
          (cond-> (assoc ctx :resurveyed (:status out)
                         :amend-error (:amend-error out)
                         :history (conj (vec (:history ctx))
                                        {:iter (:iter ctx) :findings (:findings ctx)
                                         :retreats [] :disputes [] :resurveyed (:status out)})
                         :control :stop
                         :status (keyword (str "resurvey-" (name (:status out)))))
            (:amend-tree out)   (assoc :amend-tree (:amend-tree out))
            (:amend-launch out) (assoc :amend-launch (:amend-launch out))))))

(defn- design-amend-message
  "What the design amender is told to repair `prev` for this round's `recommend`, shown `baseline`
   beside it and answering into `out-path`."
  [ctx prev recommend baseline out-path declared?]
  (let [[project ws-id] (ledger-of (:config ctx))]
    (design-amend-prompt
     {:design prev
      :baseline baseline
      :recommend recommend
      :reason (get-in ctx [:record :reason])
      :asks (get-in ctx [:record :asks])
      :raised (:findings ctx)
      ;; An ask's findings for the person are its question's,
      ;; and the prompt tells the amender that is not its to answer.
      :findings (vec (remove :for-person (get-in ctx [:record :findings])))
      :rulings (get-in ctx [:record :relation-rulings])
      :out-path out-path
      :check-cmd (when (and project ws-id) (amend-check-cmd project ws-id :design out-path))
      :settled (:settled ctx)
      :refuted-running (:refuted-running ctx)
      :resurvey-outcome (:resurvey-outcome ctx)
      :declared? declared?})))

(defn- amend-design!
  "Launch the amender against the design record and take in what it hands back.

   `baseline` is what the amender is shown alongside the design: the CITED one
   for an ordinary amendment, and the corrected one when this is finishing a
   re-survey. That difference is the whole of the re-survey repair — the
   amendment is what moves the citation."
  [ctx recommend baseline]
  (let [{:keys [cwd run-id]} (:config ctx)
        code-cwd (or (:code-cwd (:config ctx)) cwd)
        [project ws-id] (ledger-of (:config ctx))
        prev     (ws/latest-entry project ws-id :design)
        dir      (cstate/run-dir run-id)
        out-path (str (fs/path dir (str "design-amend-round-" (:iter ctx) ".edn")))
        declared (design-check/design-of project code-cwd)
        ;; The declaration is the design's other half: an element the amendment
        ;; moves or adds has an id only once the canvas lists it, so the edit the
        ;; element-id rule requires is part of the answer, and the next round
        ;; judges it with the rest.
        permitted (:spec-dirs declared)
        check-cmd (when (and project ws-id) #(amend-check-cmd project ws-id :design %))]
    (fs/create-dirs dir)
    (fs/delete-if-exists out-path)
    (let [{:keys [trespass launch]}
                   (launch-amender!
                    ctx {:label (str "design-amend-round-" (:iter ctx))
                         :permitted permitted
                         :out-path out-path
                         :check-cmd (when check-cmd (check-cmd out-path))
                         :first-message (design-amend-message ctx prev recommend baseline out-path
                                                              (some? declared))})
          raw      (when (fs/exists? out-path)
                     (try (edn/read-string (slurp out-path)) (catch Exception _ nil)))
          answer   (parse-amend-answer raw (:findings ctx) design-finding-base-key)
          ctx      (cond-> (assoc ctx :stale (vec (sort (:stale answer))) :amend-launch launch)
                     (amend-tree trespass out-path answer)
                     (assoc :amend-tree (amend-tree trespass out-path answer))
                     (seq (:stale answer))
                     (assoc-in [:carry :stale] (:stale answer)))]
      (cond
        (seq (:attributed trespass))
        (trespass-stop ctx trespass out-path answer)

        (not (fs/exists? out-path))
        (unanswered-stop ctx launch)

        :else
        (let [{:keys [record disputes out-of-reach]} answer
              entry  (fn [retreats amended?]
                       (cond-> {:iter (:iter ctx) :findings (:findings ctx)
                                :retreats retreats :disputes disputes :amended? amended?}
                         (seq (:asked-findings ctx)) (assoc :asked (:asked-findings ctx))
                         (:resurveyed ctx) (assoc :resurveyed (:resurveyed ctx))))
              out    (cond
                (nil? answer)
                (assoc ctx :control :stop :status :amend-unreadable)

                ;; Nothing moved: the design is unamended, the baseline was carried rather than read,
                ;; and every finding is objected to. The next judge would read the same design against
                ;; the same baseline at the same tree and find the same thing, so the run ends here.
                (and (nil? record) (= :carried (:resurvey-outcome ctx)) (seq (:findings ctx))
                     (every? (into #{} (map :key) disputes)
                             (map design-finding-base-key (:findings ctx))))
                (assoc ctx :disputes disputes :retreats []
                       :history (conj (vec (:history ctx)) (entry [] false))
                       :control :stop :status :no-progress
                       :unfixable (vec (distinct (map design-finding-key (:findings ctx)))))

                (and (nil? record) (or (seq disputes) (seq out-of-reach)))
                (assoc ctx :disputes disputes :retreats []
                       :history (conj (vec (:history ctx)) (entry [] false)))

                (nil? record)
                (unanswered-stop ctx launch)

                :else
                (let [cite    #(cite-corrected :design prev %
                                               {:iter (:iter ctx) :run-id run-id
                                                :resolve (fn [n] (ws/entry-at-seq project ws-id n))})
                      written (append-amendment!
                               ctx {:kind :design
                                    :stem (str "design-amend-round-" (:iter ctx))
                                    :permitted permitted
                                    :record record
                                    :path out-path
                                    :check-cmd check-cmd
                                    :append (fn [record]
                                              (let [record (cite record)]
                                                (try (ws/append-entry! project ws-id {:kind :design :amended-by (str run-id)}
                                                                       (pr-str (ws/unstamp record)))
                                                     {:record record}
                                                     (catch Exception e
                                                       {:record record :err (ledger-refusal e)}))))})
                      record  (:record written)]
                  (if (:status written)
                    (refused-stop ctx written disputes)
                    (let [retreats (retreat/design-retreats prev record)]
                      (assoc ctx
                             :amended? true
                             :retreats retreats
                             :disputes disputes
                             :amend-refusals (:refusals written)
                             :amend-delta (subject-delta prev record)
                             :history (conj (vec (:history ctx)) (entry retreats true)))))))]
          (cond-> out
            ;; A line the amender says only a record it may not write repairs is the person's, and
            ;; the decision already on the ledger holds their question. Asked now, with the line
            ;; attached, rather than after a judge round re-derives what the amender said — the
            ;; amendment it made beside it, if any, is appended first and judged on the next run.
            (and (seq out-of-reach) (not (:status out)))
            (assoc :out-of-reach out-of-reach :control :escalate :status :asked)))))))

(defn- run-design-amend-stage
  [ctx]
  (let [{:keys [dry-run?]} (:config ctx)
        recommend (get-in ctx [:record :recommend])
        [project ws-id :as ledger] (ledger-of (:config ctx))]
    (cond
      dry-run?
      (assoc ctx :control :stop :status :dry-run)

      (= :resurvey recommend)
      (let [ctx' (resurvey! ctx)]
        (if (:status ctx')
          ctx'
          (amend-design! ctx' :resurvey (:resurveyed-baseline ctx'))))

      :else
      (amend-design! ctx recommend
                     (stages/cited-baseline
                      ledger (ws/latest-entry project ws-id :design))))))

(def design-amend-stage
  "Repair whatever the recommendation named — the record, the cut, or the
   premise.

   The premise takes two steps, and skipping the second is how the loop fails to
   converge. A re-survey repairs the BASELINE, but the design still cites the
   baseline that was wrong, and `cited-baseline` resolves the citation rather
   than the newest entry — deliberately, so a later baseline cannot silently change
   what an already-judged design was judged against. So a re-survey alone changes
   nothing the next round can see: it would judge the same design against the
   same stale baseline, reach the same verdict, and re-survey again until the
   cap. The design is re-stated against the corrected baseline here, and only that
   finishes the repair."
  {:name :amend
   :run
   run-design-amend-stage})

(def design-pipeline
  "judge -> amend, where amend may be a whole baseline loop."
  [design-judge-stage design-amend-stage])

;; ── What a capped record run owes ───────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  owed-reading
  "A record run's `run-loop` :owes-reading: what a round sent on to its second reading
   (`second-reading`) read once — `{:seq the record, :read-once ids, :amendment true}`, the last
   when it was the first reading of an amendment this run made — or nil for any other round."
  [ctx]
  (when (= :next-round (:control ctx))
    (:owes-reading ctx)))

(defn- one-reading
  "`record` held on a first reading only, as the words every surface uses for it: 'sufficient on
   one reading — second reading owed', naming what is owed (`owed`, as `owed-reading`)."
  [record {n :seq :keys [read-once amendment]}]
  (str (when n (str "entry " n " is "))
       (if (= :design-decision (:format record)) "proceeding" "sufficient")
       " on one reading — second reading owed"
       (cond (seq read-once) (str " of " (str/join ", " read-once))
             amendment       " of the amendment this run made")))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :string]]}
  cap-account
  "What the cap ended a record run between, read off its terminal ctx `final`, or nil when the cap
   did not end it. The terminal line and the analysis headline both print this, so a run that ended
   holding no findings never reads as one that left findings open, and a second reading that
   overturned a sufficient verdict is not reported as an ordinary cap."
  [final]
  (let [{:keys [status iter findings amended? owed-reading amend-prompt]} final
        n           (count findings)
        quiet       (get-in final [:carry :quiet :iter])
        overturned? (and (pos? n) quiet (= quiet (dec iter)))
        counted     (str n " finding" (when (not= 1 n) "s"))]
    (case status
      :owed-second-reading
      (str "the cap fell between a reading and its second: " (one-reading (:record final) owed-reading)
           " — it does not stand until a reading agrees; re-run with no amendment between")

      :max-iters
      (str "the cap you passed was reached — this is not convergence"
           (when overturned?
             (str "; round " iter " was the second reading of round " quiet
                  "'s sufficient verdict and overturned it, " counted " open"))
           (cond
             amended?
             (str "; round " iter "'s amendment"
                  (when-let [s (get-in final [:carry :under-repair :seq])] (str " (entry " s ")"))
                  " answers " (if overturned? "them" (str "the " counted " below"))
                  " and has not been read — re-run to judge it")
             (pos? n)
             (str ", and the " counted " below " (if (= 1 n) "was" "were")
                  " still open with no amendment"
                  (when amend-prompt (str " — the amend prompt is " amend-prompt)))))

      nil)))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :string]]}
  write-owed-amend-prompt!
  "The prompt the last round's amender would have been given, written to the run dir as
   `amend-prompt-round-<n>.md` when the run ended at its cap holding findings that round never
   amended — the cap fell on a `spent` subject and ended :unfixable at the judgement — and its
   path; nil when the run owes no amendment. A person who corrects the record by hand then works
   under the rules the amender would have, the withdrawal offer above all, rather than rewording it
   once more. A design round that recommended a re-survey has no prompt: its repair is a loop."
  [final]
  (let [{:keys [status findings amended? record iter]} final
        run-id   (get-in final [:config :run-id])
        design?  (= :design-decision (:format record))
        owed?    (and run-id (seq findings) (not amended?)
                      (or (= :max-iters status)
                          (and (= :unfixable status) (spent-at-cap final)))
                      (not (and design? (= :resurvey (:recommend record)))))]
    (when owed?
      (let [dir      (cstate/run-dir run-id)
            out-path (str (fs/path dir (str (when design? "design-") "amend-round-" iter ".edn")))
            path     (str (fs/path dir (str "amend-prompt-round-" iter ".md")))
            cwd      (get-in final [:config :cwd])
            message  (if design?
                       (let [[project ws-id :as ledger] (ledger-of (:config final))]
                         (design-amend-message
                          final (ws/latest-entry project ws-id :design) (:recommend record)
                          (stages/cited-baseline ledger (ws/latest-entry project ws-id :design))
                          out-path
                          (some? (design-check/design-of project (or (get-in final [:config :code-cwd]) cwd)))))
                       (baseline-amend-message final (under-repair final) out-path))]
        (fs/create-dirs dir)
        (spit path message)
        path))))
