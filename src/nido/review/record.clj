(ns nido.review.record
  "Judgment passes over a LEDGER RECORD rather than over a diff.

   The engine in nido.review.loop already runs a stage pipeline until terminal,
   and it names no stage — the pipeline is injected. So a round of a different
   kind is a different pipeline, not a different engine. What differs here is
   what is judged and against what:

     baseline round — VERIFICATION. Is this survey true, and complete enough to
       decide against? Near-mechanical: every property and health observation
       carries file:line evidence, so checking one is 'go read it'.

     design round — DECISION. Given that baseline, the goals of the task and the
       work-distribution guidelines, should we execute on this? The only point in
       the lifecycle where 'don't build this' is cheap, and the only round that
       ends at a human.

   Three properties hold of both, and each is enforced rather than requested:

   1. NO WRITES. Both run through `codex exec -s read-only`, the same sandbox the
      diff review uses. A pre-implementation round producing edits would make it
      a fix loop, which is the thing the design round must not be.

   2. EVERY FINDING CITES. The schema requires :cites non-empty. A round with no
      diff to be wrong about will otherwise produce fluent, unfalsifiable
      findings forever.

   3. NO EVIDENCE, NO ROUND. `worth-running?` refuses where the records say a
      round would not pay — the same rule verdict-worth-running? already applies,
      for the same reason: paying an agent to conclude nothing makes the answer
      noise rather than signal.

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
   [malli.error :as me]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.report :as report]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.review.codex :as codex]
   [nido.review.loop :as rloop]
   [nido.review.retreat :as retreat]
   [nido.review.stages :as stages]))

;; ── Whether a round is worth running ────────────────────────────────────────

(defn baseline-round-worth-running?
  "A survey is worth verifying when there is something checkable in it. That is
   any load-bearing property or health observation — every one carries evidence,
   so every one is a claim the code can refute.

   A baseline with neither is not a survey that passed; it is one that recorded
   nothing to check, and a round over it could only produce prose."
  [baseline]
  (boolean (and baseline
                (or (seq (:load-bearing baseline))
                    (seq (:health baseline))))))

(defn design-round-worth-running?
  "The decision round costs a human's attention at the end of it, so it runs
   where the records themselves say it would pay.

   The trigger is read from what the design already declares, rather than from a
   new heuristic: a change declaring :within on the baseline AND :conforms on the
   stance is claiming it moves nothing structural — cheap to spot-check, not
   worth a decision round. :extends, :revisit, :challenges, a large effort, or
   any health observation routed anywhere other than :fix-here are each a reason
   the answer is not obvious.

   Deliberately readable off the record: a reader can see from the design itself
   why a round did or did not run."
  [design]
  (boolean
   (and design
        (or (not= :within   (get-in design [:baseline :relation]))
            (not= :conforms (get-in design [:standing :relation]))
            (contains? #{:L :XL} (:effort design))
            (some #(not= :fix-here (:to %)) (:routes design))))))

(defn discover-intent
  "The intent the design CITED, projected to what a goal may contain. nil when
   the design cites none — a pre-intent record — which the prompt states rather
   than papering over.

   A cited :triage entry projects its title and summary only. Its :directions
   carry a proposed shape and an effort, and feeding those into the goal
   yardstick would put the answer inside the question: goal-served exists to
   catch a design that over-serves or that a smaller design would satisfy, and it
   could never fail honestly against a goal that already names the solution.

   Never throws. The prompt is built as an argument to run-round!, so anything
   that throws here escapes the round's only catch and takes the task down
   instead of degrading."
  [cwd design]
  (try
    (when-let [n (get-in design [:intent :seq])]
      (when-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
        (let [e (ws/entry-at-seq project ws-id n)]
          (case (:format e)
            :intent        {:goal (:goal e) :done-when (:done-when e)}
            :triage-report {:goal (:title e) :done-when [] :summary (:summary e)}
            nil))))
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

(defn disputes-block
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

(defn- readings-lines
  [readings indent]
  (apply str (for [{:keys [lens verdict because]} readings]
               (str "\n" indent "read as " (namespace lens) "/" (name lens)
                    " = " (name verdict) " — " because))))

(defn lens-block
  "The perspectives in play, with their verdicts and where each comes from.

   Put in front of the judge rather than assumed, because a verdict is only
   checkable if both sides are reading it the same way: `accidental` in Out of
   the Tar Pit's sense is a claim about what the PROBLEM required, not a
   criticism of the code."
  []
  (str "\nTHE PERSPECTIVES THIS SURVEY IS READ THROUGH. Each is one source's view\n"
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
                               :claim  "a LOAD-BEARING CLAIM (never a module)"
                               :module "a MODULE (never a claim)"
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
         "interface is what the rest of the system may depend on:\n"
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
               ;; Only when there is one. A survey written before counterexamples
               ;; were required carries none, and printing the label with nothing
               ;; after it tells the judge a claim is refutable in a way its
               ;; author never committed to.
               (when-not (str/blank? (str falsified-by))
                 (str "\n    refuted by: " falsified-by))
               (readings-lines readings "    ")
               (when (seq evidence)
                 (str "\n    read from:  " (str/join ", " evidence)))))
        load-bearing)))

(defn baseline-prompt
  "The verification prompt.

   Pitched at the decomposition, and deliberately so. Asked to check a survey
   against a large subsystem, a judge will find true things about it forever —
   an implementation has no fixed point to converge on, while a decomposition
   does. What makes that bound real is that each claim arrives with the
   counterexample that would refute it, so the question is never `is this any
   good` but `does that specific thing exist`."
  [{:keys [baseline disputes]}]
  (str
   "You are checking whether a SURVEY of an area is TRUE, and whether it is\n"
   "ENOUGH. You are NOT designing anything, you are not reviewing the code for\n"
   "defects, and you are not judging whether the area is good.\n\n"
   "ENOUGH FOR WHAT — this is the whole of the second question, and it is not\n"
   "completeness. A survey exists so a later round can derive four things about a\n"
   "proposed change:\n"
   "  relation-honest   does the change stand where it says it stands, against\n"
   "                    this area's modules and load-bearing properties?\n"
   "  goal-served       does it serve the goal and only the goal — would a\n"
   "                    smaller change do?\n"
   "  decomposable      can its cut be stated, and does it follow this area's\n"
   "                    module boundaries?\n"
   "  routing-coherent  do this area's health observations belong to one story?\n\n"
   "The survey is ENOUGH when those can be derived against it. It does not have\n"
   "to be everything true about the area, and it never will be: measured against\n"
   "a real system there is always another true thing to add, so a round that\n"
   "reports what is missing rather than what is BLOCKED never finishes and never\n"
   "helps. If you notice something true that blocks none of the four, it is not a\n"
   "finding. Leave it.\n\n"
   "THE LEVEL THIS OPERATES AT. The survey describes the area as a DECOMPOSITION:\n"
   "which modules exist, what design decision each one hides, and how their\n"
   "composition produces the required behaviour. Judge it there. A defect in one\n"
   "line of SQL is not a finding here however real it is — that belongs to code\n"
   "review, and reporting it here is how a round finds true things forever\n"
   "without ever answering the question that was asked.\n\n"
   "EVERY CLAIM CARRIES WHAT WOULD REFUTE IT. That is what you go looking for.\n"
   "Not `does this feel right` — `does that specific counterexample exist in the\n"
   "code`. Report a finding only when you found one, and say what it is.\n\n"
   ;; Gated on the survey being ABLE to carry readings, not on it having any.
   ;; Gating on presence hid the vocabulary from exactly the survey that needed
   ;; it — one with a decomposition and no analysis — so nothing ever told anyone
   ;; the perspectives existed. The first real survey written in this shape
   ;; carried five modules and zero readings.
   (when (contains? baseline :modules)
     (str (if (some (comp seq :readings)
                    (concat (:load-bearing baseline) (:modules baseline)))
            (str "A READING IS A CLAIM TOO, and refutable on its own terms. State read\n"
                 "as essential is refuted by a derivation that computes it. An ordering\n"
                 "read as required is refuted by showing the two things commute. A module\n"
                 "read as deep is refuted by an interface that costs about what it hides.\n"
                 "A dependency read as on-interface is refuted by a caller reaching past\n"
                 "it. Check the readings as well as the properties.\n")
            (str "THIS SURVEY READS NOTHING THROUGH ANY PERSPECTIVE. It could — the\n"
                 "vocabulary is below and the record is in a shape that carries readings.\n"
                 "A decomposition recorded with no reading of it is structure without\n"
                 "analysis: it says what the parts are and never says whether the problem\n"
                 "required them, whether an ordering was imposed, or whether a seam pays.\n"
                 "Report that as UNDERSCOPED, and name the claims most worth reading and\n"
                 "through which lens.\n"))
          (lens-block)))
   "AREA: " (:area baseline) "\n"
   "BOUNDED BY: " (:bounded-by baseline) "\n"
   "SHAPE: " (:shape baseline) "\n"
   (module-block (:modules baseline))
   (when-let [c (:composition baseline)]
     (str "\nCOMPOSITION — how those are claimed to produce the behaviour:\n" c "\n"))
   "\nLOAD-BEARING — what is claimed to break if violated"
   (if (some :falsified-by (:load-bearing baseline))
     ", each with the\ncounterexample that would refute it:\n"
     (str ".\n\nThis survey predates the rule that a claim must name its own\n"
          "counterexample, so none of them do. Judge the claims as stated, and treat\n"
          "a claim you cannot see any way to refute as a finding in its own right.\n"))
   (claim-block (:load-bearing baseline)) "\n"
   (when-let [h (seq (:health baseline))]
     (str "\nHEALTH — claimed about whether what holds is sound. :design means a\n"
          "weak design cleanly executed; :implementation means a strong design\n"
          "shakily executed. A mis-axed observation is a finding:\n"
          (bullets (map #(str "[" (name (:axis %)) "] " (:observation %)
                              " [" (str/join ", " (:evidence %)) "]")
                        h))
          "\n"))
   (when-let [u (seq (:unknowns baseline))]
     (str "\nDECLARED NOT DETERMINED — already honest, not findings:\n" (bullets u) "\n"))
   "\nTwo distinct failures, and they have different remedies:\n"
   "1. FALSIFIED — a claim's own counterexample EXISTS. The module hides a\n"
   "   decision something outside depends on; the `essential` fact is derivable\n"
   "   from something else the system holds; the `derived` value is also stored\n"
   "   and edited independently; the composition does not produce the behaviour\n"
   "   claimed. Show the counterexample, with where it is.\n"
   "2. INSUFFICIENT — one of the four derivations cannot be made against this\n"
   "   survey. Say WHICH (`blocks`) and what the survey would have to say for it\n"
   "   to be makeable (`needs`) — the specific missing claim, not `more detail`.\n"
   "   A gap that blocks none of the four is not a finding here; at most it is a\n"
   "   health observation, and more often it is the next survey's business.\n\n"
   "SUFFICIENT IS THE EXPECTED OUTCOME on any survey that has done its job. It is\n"
   "not a high bar and it is not praise — it means a decision can be made against\n"
   "this, which is all a survey is for.\n\n"
   "Every finding MUST cite what it is about — the exact property, module or\n"
   "composition text. A finding that cites nothing is not a finding; do not\n"
   "report it. Neither is a finding that reports a bug in code the survey\n"
   "correctly describes.\n\n"
   "Populate confirmed with the claims you actually went and checked, and return\n"
   "sufficient when they held and the four derivations are makeable. Do not\n"
   "manufacture findings to look thorough — on this round, thoroughness is\n"
   "checking the claims that are there, not finding more to say."
   (disputes-block disputes)))

(defn- survey-block
  "The survey the design was made against, at the level the design round needs it.

   Three of the four derivations are made AGAINST this record: relation-honest
   reads the modules and the extension points, decomposable reads the module
   boundaries, routing-coherent reads the health observations the design's routes
   cite by id. So it is printed the way the baseline round prints it, rather than
   summarised.

   It used to print :bounded-by and the load-bearing properties and nothing else,
   while the derivations below asked for module boundaries, extension points and
   health observations by name. A judge cannot tell `the survey does not say`
   from `I was not shown it`, and the only recommendation that fits the first is
   :resurvey — so every round recommended :resurvey, and one of them reported a
   module as unsurveyed that the survey had named all along."
  [baseline]
  (when baseline
    (str "\nTHE AREA AS SURVEYED, before this was designed. Three of the four\n"
         "derivations are made against THIS. Where the survey states something,\n"
         "derive against what it states — a survey that is wrong about the area is\n"
         "a finding, but a survey you did not read is not one.\n\n"
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
         "\nLOAD-BEARING — what the survey claims breaks if violated:\n"
         (claim-block (:load-bearing baseline)) "\n"
         (when-let [h (seq (:health baseline))]
           (str "\nHEALTH OBSERVED — the design's routes cite these BY ID, and\n"
                "routing-coherent is derived against them. :design means a weak design\n"
                "cleanly executed; :implementation means a strong design shakily\n"
                "executed. An observation marked invisibly-incomplete cannot leave the\n"
                "branch that touches it:\n"
                (str/join
                 "\n"
                 (map (fn [{:keys [id observation axis evidence invisibly-incomplete?]}]
                        (str "- " id
                             (when axis (str " [" (name axis) "]"))
                             (when invisibly-incomplete? " [invisibly incomplete]") "\n"
                             "    " observation
                             (when (seq evidence)
                               (str "\n    read from: " (str/join ", " evidence)))))
                      h))
                "\n"))
         (when-let [u (seq (:unknowns baseline))]
           (str "\nDECLARED NOT DETERMINED by the survey. Already honest — not a\n"
                "finding, and not on its own grounds to call the premise wrong:\n"
                (bullets u) "\n")))))

(defn design-prompt
  "The decision prompt. Derives what can be derived; hands the rest over."
  [{:keys [design baseline stance intent disputes]}]
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
          (when-let [sm (:summary intent)] (str sm "\n"))
          (when-let [d (seq (:done-when intent))]
            (str "Done when:\n" (bullets d) "\n"))
          "\n")
     (str "NO STATED INTENT. This design cites none — it predates the intent\n"
          "record. You cannot derive goal-served against a goal nobody wrote\n"
          "down: report that check as UNDERIVABLE, say the record is missing,\n"
          "and do NOT infer the goal from the design. An inferred goal is the\n"
          "one the design serves, so the check could never fail.\n\n"))
   "THE DESIGN:\n"
   "Summary: " (:summary design) "\n"
   "Shape: " (:shape design) "\n"
   "Effort: " (name (:effort design)) "\n"
   "Invariants:\n" (invariant-lines (:invariants design)) "\n"
   "Declared against the stance: " (name (get-in design [:standing :relation])) "\n"
   ;; A design written before the baseline event existed carries no :baseline at
   ;; all, and it still qualifies for a round — its absent relation is not
   ;; :within. Saying so is the degrade this area takes everywhere else; calling
   ;; `name` on the nil would throw HERE, while building the prompt, which is
   ;; outside run-round!'s catch and so would take the whole task down.
   (if-let [r (get-in design [:baseline :relation])]
     (str "Declared against the baseline: " (name r) "\n")
     (str "Declared against the baseline: NOTHING. This design predates the\n"
          "baseline event, so it was judged against no survey. Weigh its claims\n"
          "on their own merits; the absence is not itself a finding.\n"))
   (when-let [r (seq (:rejected design))]
     (str "\nALREADY REJECTED. A finding re-proposing one of these is ANSWERED,\n"
          "not evidence — unless you can show the stated reason no longer holds:\n"
          (bullets (map #(str (:alternative %) " — because " (:why-not %)) r)) "\n"))
   (when-let [l (seq (:layers design))]
     (str "\nCLAIMED DECOMPOSITION, VERTICAL — one claim per layer, ordered by\n"
          "dependency; all of it lands in one go:\n"
          (bullets (map #(str (:claim %) " (" (name (:mode %)) ")") l)) "\n"))
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
   (survey-block baseline)
   (when stance (str "\nPROJECT STANCE — framing only:\n" stance "\n"))
   "\nDERIVE THESE FOUR. Each is decidable; none is a matter of taste:\n\n"
   "  relation-honest   — does it stand where it says it stands? A design\n"
   "                      declaring :within whose own shape needs a load-bearing\n"
   "                      property to move is not what it says it is. Read this\n"
   "                      against the baseline's MODULES: a change that moves a\n"
   "                      module boundary, or asks a module to stop hiding what\n"
   "                      it hides, is :revisit however small its diff.\n"
   "  goal-served       — does it serve the goal, and ONLY the goal? Under-\n"
   "                      serving is easy to see. OVER-serving is the common\n"
   "                      one: the goal plus a good deal more, every piece\n"
   "                      individually defensible. Check whether a strictly\n"
   "                      smaller design would do, including one already\n"
   "                      rejected for a reason that no longer holds.\n"
   "  decomposable      — can the cut be stated, and does it follow the module\n"
   "                      boundaries the baseline names? A layer that spans two\n"
   "                      modules, or that splits one module's secret across two\n"
   "                      layers, is a cut against the grain of the area.\n"
   "                      Vertically: layers ordered by\n"
   "                      dependency, one claim each with no \"and\", one review\n"
   "                      mode each. Temporally, IF the design is phased: each\n"
   "                      phase a state the system can be left in, with an exit\n"
   "                      criterion that is an observation rather than a to-do.\n"
   "                      A design whose cut cannot be stated is not decomposed\n"
   "                      yet, and there is nothing to approve. A design with no\n"
   "                      phase plan is the ordinary case and is NOT a finding.\n"
   "  routing-coherent  — do the routed health observations keep this ONE story?\n"
   "                      Observations routed to fix-here that belong to a\n"
   "                      different story make this two changes.\n\n"
   "Then recommend:\n"
   "  proceed  — nothing derivable blocks it.\n"
   "  amend    — a derivable defect in the record itself.\n"
   "  recut    — the decomposition does not hold.\n"
   "  resurvey — the PREMISE is wrong, not the commitment. A design can be sound\n"
   "             on a survey that was not. Redesign and re-survey are different\n"
   "             instructions and saying the wrong one is worse than saying\n"
   "             nothing.\n\n"
   "Every finding MUST cite what it falsifies. A finding that cites nothing is\n"
   "not a finding.\n\n"
   "asks is REQUIRED whatever you recommend: state the question the human still\n"
   "has to answer, in one or two sentences, with everything you derived already\n"
   "taken off the table. Never answer it yourself."
   (disputes-block disputes)))

;; ── Running a round ─────────────────────────────────────────────────────────

(def ^:private schema-resources
  {:baseline-review "review/baseline_review_schema.json"
   :design-decision "review/design_decision_schema.json"})

(defn- normalize-findings
  [raw]
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
                         (seq (:evidence f))
                         (assoc :evidence (mapv str (:evidence f))))))))
        raw))

(def ^:private derivation-names
  (into #{} (map name) report/derivations))

(defn- normalize-blocked
  "An insufficient finding, kept only if it names a derivation it blocks and what
   the survey would have to say. Those two are the bound: without them
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
                      (seq (:evidence f)) (assoc :evidence (mapv str (:evidence f)))))))
              raw)))

(defn parse-baseline-review
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
            (cond-> {:format :baseline-review
                     :verdict v
                     :baseline-seq baseline-seq
                     :reason (str (:reason m))}
              (seq (:confirmed m)) (assoc :confirmed (mapv str (:confirmed m)))
              (not= :sufficient v) (assoc :findings findings))))))
    (catch Exception _ nil)))

(defn parse-design-decision
  "Codex JSON -> a :design-decision ledger record, or nil when unusable."
  [json-str design-seq]
  (try
    (let [m (json/parse-string json-str true)
          r (keyword (str (:recommend m)))
          checks (into [] (keep (fn [c]
                                  (let [k (keyword (str/replace (str (:check c)) "_" "-"))]
                                    (when (#{:relation-honest :goal-served
                                             :decomposable :routing-coherent} k)
                                      {:check  k
                                       :status (let [st (keyword (str (:status c)))]
                                                 (if (#{:held :broken :underivable} st)
                                                   st
                                                   ;; a judge that answered in the
                                                   ;; old shape is still answering
                                                   (if (:held c) :held :broken)))
                                       :note   (str (:note c))}))))
                       (:checks m))
          findings (normalize-findings (:findings m))
          asks     (str (:asks m))]
      (when (and (#{:proceed :amend :recut :resurvey} r)
                 (seq checks)
                 (not (str/blank? asks))
                 (or (= :proceed r) (seq findings)))
        (cond-> {:format :design-decision
                 :recommend r
                 :design-seq design-seq
                 :reason (str (:reason m))
                 :checks checks
                 :asks asks}
          (not= :proceed r) (assoc :findings findings))))
    (catch Exception _ nil)))

(defn- run-round!
  "One read-only codex pass over a record. Returns {:ok <json-string>} or
   {:outcome <kw> :detail <str>} — never nil, and never throws.

   The outcome is tagged rather than collapsed because a judgment surface cannot
   afford one confusion above all others: a round that never ran must not read
   like a round that ran and found nothing to say. Silence from a judge is
   evidence; silence from a missing binary is not, and a reader who cannot tell
   them apart draws the wrong conclusion from the same blank line."
  [{:keys [cwd run-id kind prompt label]}]
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
      (spit schema-path (slurp (jio/resource (schema-resources kind))))
      (let [{:keys [exit]} (codex/run-codex! {:cwd cwd :schema-path schema-path
                                              :out-path out-path :log-path log-path
                                              :prompt prompt})]
        (cond
          (not (zero? exit))          {:outcome :codex-failed
                                       :detail (str "codex exited " exit " — see " log-path)}
          (not (fs/exists? out-path)) {:outcome :no-output
                                       :detail (str "codex wrote no answer to " out-path)}
          :else                       {:ok (slurp out-path)})))
    (catch Throwable t
      {:outcome :round-crashed :detail (or (ex-message t) (str (class t)))})))

(defn- judged
  "Apply `parse` to a round result, keeping the outcome tagged the whole way.
   An answer that will not parse is its own outcome — the judge spoke and was
   unusable, which is a different fact from the judge never speaking."
  [result parse]
  (if-let [json (:ok result)]
    (or (parse json)
        {:outcome :unusable-answer
         :detail "the answer did not satisfy what a round must return"})
    result))

(defn baseline-review!
  "Verify a baseline against the code. Returns the ledger record, or
   {:outcome <kw> :detail <str>} saying why there is none — never a bare nil, so
   a caller can always tell a skipped round from a failed one.

   `:baseline` names WHICH record to verify, and a loop must supply it. The
   newest entry is only the right answer for a one-shot round on a workstream
   with one survey. A workstream can hold surveys of DIFFERENT areas — a narrow
   follow-up written beside the broad one it came out of — and a loop that
   re-reads `latest` repairs whichever was appended last, which need not be the
   one anybody asked about. It then cannot converge by construction: the design
   citing the other survey is never answered however long it runs."
  [{:keys [cwd code-cwd run-id label disputes baseline]}]
  (if-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
    (if-let [baseline (or baseline (ws/latest-entry project ws-id :baseline))]
      (if (baseline-round-worth-running? baseline)
        (judged (run-round! {:cwd (or code-cwd cwd) :run-id run-id :kind :baseline-review
                             :label label
                             :prompt (baseline-prompt {:baseline baseline
                                                       :disputes disputes})})
                #(parse-baseline-review % (:seq baseline)))
        {:outcome :nothing-to-check
         :detail "the baseline records no load-bearing property and no health observation"})
      {:outcome :no-record :detail "this workstream has no :baseline entry"})
    {:outcome :no-workstream :detail (str "cwd resolves to no nido session: " cwd)}))

(def ^:private verified-verdicts
  "A verdict that says somebody checked this survey against the code and it held.

   :accurate is the same answer under the question the round asked before it
   asked about sufficiency. The survey was still checked, and re-checking it
   under the newer question is the baseline loop's business — not a reason to
   refuse to decide against it."
  #{:sufficient :accurate})

(defn unverified-premise
  "Why this design cannot be judged yet, when the survey it stands on has never
   been checked — or nil when it has.

   Three of the decision round's four derivations are made AGAINST the survey. On
   an unverified one the judge cannot tell `the survey is wrong` from `the survey
   is right and the area is not what I would have described`, and the only
   recommendation that fits the first is :resurvey. That is not a hypothetical:
   the first workstream to run this loop recommended :resurvey seven times out of
   seven and never once reached a decision, each round paying for a full
   derivation to rediscover that nobody had verified the premise.

   So the order is stated rather than discovered, and it costs nothing to state:
   nido can read the answer out of its own ledger before it spends a judge on it.
   Verify the survey, then decide against it.

   A design citing NO survey is not gated. There is no premise to check, the
   prompt says exactly that, and the round is judged on the design's own merits."
  [project ws-id design]
  (when-let [n (get-in design [:baseline :seq])]
    (when-not (some #(and (= n (:baseline-seq %))
                          (verified-verdicts (:verdict %)))
                    (ws/entries-of project ws-id :baseline-review))
      {:outcome :premise-unverified
       :detail (str "the design cites the survey at entry " n
                    ", and no round has found that survey sufficient")})))

(defn design-decision!
  "Run the decision round over this workstream's latest design record. Returns
   the ledger record, or {:outcome <kw> :detail <str>} saying why there is none.

   Single-pass on purpose: it emits a decision, not findings to iterate on.

   Three of the no-verdict outcomes are read out of the records before a judge is
   launched, and cost nothing: no design, a design that says it moves nothing
   structural, and a design standing on a survey nobody verified. Only the last
   is new, and it is the one that was previously discovered by paying for the
   round — see `unverified-premise`."
  [{:keys [cwd code-cwd run-id label disputes]}]
  (if-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
    (if-let [design (ws/latest-entry project ws-id :design)]
      (if (design-round-worth-running? design)
        (or (unverified-premise project ws-id design)
            (judged (run-round!
                     {:cwd (or code-cwd cwd) :run-id run-id :kind :design-decision
                      :label label
                      :prompt (design-prompt
                               {:design   design
                                :baseline (stages/discover-baseline cwd design)
                                :stance   (stages/read-stance project)
                                :intent   (discover-intent cwd design)
                                :disputes disputes})})
                    #(parse-design-decision % (:seq design))))
        {:outcome :not-worth-running
         :detail "the design declares :within on its baseline and :conforms on the stance at a modest effort, with nothing routed away from :fix-here"})
      {:outcome :no-record :detail "this workstream has no :design entry"})
    {:outcome :no-workstream :detail (str "cwd resolves to no nido session: " cwd)}))

(defn append!
  "Append a round's record to the workstream ledger. Best-effort, for the same
   reason the review path's appends are: a round that produced an answer must not
   turn into a failure because the side record could not be written."
  [cwd record]
  (try
    (when (:format record)
      (when-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
        (ws/append-entry! project ws-id {:kind (:format record)} (pr-str record))))
    (catch Exception _ nil)))

;; ── The baseline round as a loop ────────────────────────────────────────────
;;
;; Two stages on the shared engine: JUDGE (codex, read-only, the same pass the
;; one-shot round ran) and AMEND (claude, correcting the survey). The engine
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

(defn baseline-finding-base-key
  "What makes two baseline findings the same finding.

   The claim's ID when the finding names one, because that is the only handle
   that survives the claim being amended. Measured before ids existed: over five
   rounds on one survey, of six claims one kept its text and none kept an
   evidence reference — so a claim that was fixed and is STILL WRONG produced a
   key nothing had seen, and the stall detector could not fire on the one case it
   exists for.

   Keyed on the CODE the finding cites, because that is the one thing the
   amender does not move: amending a record rewrites `:cites` — it quotes the
   property text being refuted — while `src/x.clj:41` still says what it said.
   Keying on the quoted text instead would make every round look like new
   findings and no-progress? would never fire.

   Tagged, so an evidence key can never collide with the text key a finding
   without evidence falls back to. The schema requires evidence, so that branch
   is the degenerate one, and it is keyed on the unstable thing deliberately —
   a finding that cites no code is one the loop should stop on early."
  [f]
  (cond
    (not (str/blank? (str (:claim-id f)))) [:claim-id (str (:claim-id f))]
    (seq (:evidence f))                    [:evidence (vec (sort (:evidence f)))]
    :else                                  [:cites (vec (sort (:cites f)))]))

(defn dispute-aware
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

;; ── The appeal channel ──────────────────────────────────────────────────────

(defn parse-amend-answer
  "What an amender may hand back: an amended record, objections to what it was
   asked to amend for, or both.

   A bare record — no wrapper — is still accepted, because that is what the
   answer was before there was anything to say back, and a shape change is not a
   reason to stop reading records already written.

   Disputes are made BY NUMBER against the findings as they were listed. The
   amender never computes a key, and nothing has to match text back to text: a
   number is either in range or it is not. One out of range is dropped rather
   than guessed at, along with one that objects without saying why — an
   objection with no reason cannot be answered and is not an appeal."
  [raw findings base-key]
  (when (map? raw)
    (let [record   (if (:format raw) raw (:record raw))
          disputes (when-not (:format raw) (:disputes raw))]
      {:record   (when (map? record) record)
       :disputes (vec (keep (fn [{:keys [finding because evidence]}]
                              (let [i (dec (long (or finding 0)))
                                    f (when (and (nat-int? i) (< i (count findings)))
                                        (nth findings i))]
                                (when (and f (not (str/blank? (str because))))
                                  {:key      (base-key f)
                                   :claim    (or (:claim f)
                                                 (some-> (:check f) name)
                                                 (str f))
                                   :because  (str because)
                                   :evidence (vec (map str evidence))})))
                            disputes))})))

(defn dispute-counts
  "How many times each finding has been objected to across the whole run."
  [history]
  (frequencies (map :key (mapcat :disputes history))))

(defn disputes-for-judge
  "Every standing objection, oldest first, for the next judge prompt."
  [history]
  (vec (mapcat :disputes history)))

(defn amend-prompt
  "Instruction to correct a survey the code refuted.

   States the one thing that makes the loop worth running at all: the job is to
   make the record TRUE, and making the findings go away is not the same job.
   The distinction has a cheap wrong answer — delete the property, drop the
   observation, clear the flag — which is why nido measures the result rather
   than trusting this paragraph (see `retreat`)."
  [{:keys [baseline findings out-path]}]
  (str
   "A read-only judge checked this workstream's BASELINE — the survey of how the\n"
   "area works today — against the code, and refuted part of it.\n\n"
   "Your job is to make the survey TRUE. That is not the same job as making the\n"
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
   "    the survey was genuinely wrong, and expect to have said why.\n\n"
   "Stay at the level the survey is written at: modules, what each hides, and how\n"
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
   (when (contains? baseline :modules)
     (str "A reading may only use its own lens's verdicts, and a lens only reads the\n"
          "subject it is about. The ledger refuses anything else and the whole record\n"
          "is lost with it, so use these and nothing else:\n"
          (lens-block)))
   "CHANGE ONLY WHAT WAS REFUTED. A claim nobody challenged this round must come\n"
   "back unchanged — not restated, not sharpened, not made more precise.\n\n"
   "That rule is what lets this end. A sharper claim is a bigger target: a survey\n"
   "saying `publishes ten vars` or `folds five event types` invites the next round\n"
   "to count, and counting is always available. Rounds have gone by watching one\n"
   "claim get sharper while another, freshly sharpened, became the next finding.\n"
   "Fix what was refuted; leave the rest exactly as it stands.\n\n"
   "Read the cited code before you change a word of the record.\n\n"
   "Do NOT edit any source file. This pass writes one file and nothing else.\n\n"
   "THE CURRENT BASELINE:\n\n"
   (pr-str (ws/unstamp baseline))
   "\n\nWHAT THE JUDGE REFUTED — numbered, and you answer them by number:\n\n"
   (str/join
    "\n\n"
    (map-indexed
     (fn [i f]
       (str (inc i) ". refutes: " (str/join "; " (:cites f)) "\n"
            "   claim:   " (:claim f)
            (when (seq (:evidence f))
              (str "\n   evidence: " (str/join ", " (:evidence f))))))
     findings))
   "\n\nIF A FINDING IS WRONG ABOUT THE CODE, SAY SO INSTEAD OF AMENDING FOR IT.\n"
   "You do not settle it — the judge is asked again with your objection in front\n"
   "of it, and has to withdraw the finding or answer your evidence. An objection\n"
   "with no reason is dropped, because it cannot be answered. Amending a record\n"
   "you believe is already right, to quiet a finding you believe is wrong, is the\n"
   "worst available answer: it makes the record false AND ends the argument.\n\n"
   "Write EDN to:\n\n  " out-path "\n\n"
   "  {:record   <the COMPLETE corrected baseline — every field, not a diff>\n"
   "   :disputes [{:finding 2 :because \"...\" :evidence [\"src/x.clj:41\"]}]}\n\n"
   "Omit :record entirely if every finding is disputed and the survey needs no\n"
   "change. Omit :disputes if you accepted all of them. The record must satisfy\n"
   "the same schema the current one does; nido reads this file, validates it, and\n"
   "appends it as the superseding baseline. Do not append it yourself and do not\n"
   "commit anything."))

(defn- ledger-refusal
  "Why the ledger refused a record, in a form somebody can act on.

   `ex-message` alone is \"Invalid event report\", which names no field and tells
   an amender nothing it could fix. The explain data is already on the exception;
   this is only a matter of reading it out."
  [e]
  (let [explain (:explain (ex-data e))]
    (str (or (ex-message e) "the ledger refused it")
         (when explain (str " — " (pr-str (me/humanize explain)))))))

(def judge-stage
  "The same read-only pass the one-shot round ran, with its verdict appended to
   the ledger exactly as before.

   Every non-verdict outcome is terminal and keeps its own name. That is the
   rule the one-shot round already held — a round that could not run must never
   read like a round that ran and found nothing — and a loop makes it matter
   more, not less: `:codex-failed` on round three of an otherwise converging run
   is not convergence."
  {:name :judge
   :run
   (fn [ctx]
     (let [{:keys [cwd code-cwd run-id]} (:config ctx)
           counts (dispute-counts (:history ctx))
           ;; The record this run is repairing: the one it was pointed at, then
           ;; each amendment it makes itself. Never re-read as "the latest",
           ;; which another session — or an earlier round of a different survey —
           ;; can change underneath a run in flight.
           target (or (:under-repair ctx) (:baseline (:config ctx)))
           record (baseline-review!
                   {:cwd cwd :code-cwd code-cwd :run-id run-id
                    :baseline target
                    :label (str "baseline-review-round-" (:iter ctx))
                    :disputes (disputes-for-judge (:history ctx))})]
       (append! cwd record)
       (cond
         (:outcome record)
         (assoc ctx :record record :status (:outcome record))

         (= :sufficient (:verdict record))
         (assoc ctx :record record :findings [] :control :stop :status :sufficient)

         :else
         (let [findings (mapv #(assoc % :disputed-n
                                      (get counts (baseline-finding-base-key %) 0))
                              (:findings record))]
           ;; Twice objected to and stated a third time. Neither side is giving
           ;; way and neither can settle it: the judge cannot be overruled by the
           ;; pass it is judging, and that pass may not amend a record it believes
           ;; is already true. That is a human's call, not another round's.
           (if (some #(>= (:disputed-n %) 2) findings)
             (assoc ctx :record record :findings findings
                    :control :escalate :status :disputed)
             (assoc ctx :record record :findings findings))))))})

(def amend-stage
  "Correct the survey, then measure what the correction cost.

   Three things are checked after the amender exits, and they are three
   different failures:

     the working copy went dirty — the pass wrote code, which no record loop may
       do. Terminal, and loud: whatever it wrote is still there for a human.
     no usable record came back — the amender declined or failed. Terminal as
       :amend-noop, the ledger untouched, mirroring the diff loop's :fix-noop.
     the record came back smaller — reported always, and terminal when it fell
       below the point its own round would still run. That last one is the whole
       reason this stage measures rather than trusts: a loop that converges by
       deleting what it was asked to defend would otherwise report success."
  {:name :amend
   :run
   (fn [ctx]
     (let [{:keys [cwd code-cwd run-id budget dry-run?]} (:config ctx)
           code-cwd (or code-cwd cwd)]
       (if dry-run?
         (assoc ctx :control :stop :status :dry-run)
         (let [[project ws-id] (stages/project+ws-from-cwd cwd)
               prev      (or (:under-repair ctx)
                             (:baseline (:config ctx))
                             (ws/latest-entry project ws-id :baseline))
               dir       (cstate/run-dir run-id)
               out-path  (str (fs/path dir (str "amend-round-" (:iter ctx) ".edn")))
               ;; Dirty BEFORE, not dirty after: a session worktree may already
               ;; carry a human's uncommitted work, and calling that a violation
               ;; would halt every loop run outside a clean tree.
               before (stages/working-copy-state code-cwd)]
           (fs/create-dirs dir)
           ;; "The file is there" is the whole test for whether the amender
           ;; answered, so the round must start with it absent. A leftover from
           ;; an earlier run under this run-id would otherwise be read as this
           ;; round's answer and appended to the ledger as a superseding record.
           (fs/delete-if-exists out-path)
           (agent/launch!
            {:run-id run-id :cwd code-cwd :budget budget
             :first-message (amend-prompt {:baseline prev
                                           :findings (:findings ctx)
                                           :out-path out-path})
             :err-file (str (fs/path dir (str "amend-round-" (:iter ctx) ".err.log")))})
           (cond
             (not= before (stages/working-copy-state code-cwd))
             (assoc ctx :control :stop :status :amend-touched-code)

             (not (fs/exists? out-path))
             (assoc ctx :control :stop :status :amend-noop)

             :else
             (let [raw    (try (edn/read-string (slurp out-path)) (catch Exception _ nil))
                   answer (parse-amend-answer raw (:findings ctx)
                                              baseline-finding-base-key)
                   {:keys [record disputes]} answer
                   entry  (fn [retreats]
                            {:iter (:iter ctx)
                             :verdict (get-in ctx [:record :verdict])
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
                        :history (conj (vec (:history ctx)) (entry [])))

                 (nil? record)
                 (assoc ctx :control :stop :status :amend-noop)

                 :else
                 ;; A sentinel, not the return value: append-entry! answers with
                 ;; the path it wrote, so "it came back a string" is what SUCCESS
                 ;; looks like here.
                 (let [err (try (ws/append-entry! project ws-id
                                                  {:kind :baseline}
                                                  (pr-str (ws/unstamp record)))
                                nil
                                (catch Exception e (ledger-refusal e)))]
                   (if err
                     (assoc ctx :control :stop :status :amend-invalid
                            :amend-error err)
                     (let [retreats (retreat/baseline-retreats prev record)
                           ctx' (assoc ctx
                                       :retreats retreats
                                       :disputes disputes
                                       :history (conj (vec (:history ctx)) (entry retreats)))]
                       (if (baseline-round-worth-running? record)
                         (assoc ctx' :amended? true :under-repair record)
                         (assoc ctx' :amended? true :under-repair record
                                :control :stop :status :retreated))))))))))))})

(def baseline-pipeline
  "judge -> amend. No warden, no fix: nothing here touches the working copy."
  [judge-stage amend-stage])

;; ── The design round as a loop ──────────────────────────────────────────────
;;
;; Same two stages and the same appeal channel, over a different record and with
;; one addition the baseline loop has no use for: this round can conclude that
;; the SURVEY is wrong rather than the design, and when it does the repair is a
;; different loop. So :resurvey descends into the baseline pipeline and comes
;; back, which is what makes the pair a state machine rather than two loops that
;; happen to share an engine.
;;
;; The terminal state is an escalation, not a convergence. That is not a
;; limitation — it is the round's whole purpose: everything derivable is derived
;; so that what reaches a human is only the judgement that cannot be.

(defn broken-checks
  "The derivations that failed — the design round's findings, at the granularity
   the round can actually decide at.

   The prose findings say more, but they quote the record and so are rewritten
   by every amendment. The check vocabulary is four closed values that no
   amendment can move, which is what a stall detector and an appeal both need."
  [record]
  (vec (filter #(= :broken (:status %)) (:checks record))))

(defn underivable-checks
  "The derivations the round could not make at all.

   Never findings, and the distinction is the reason :status has three values.
   A check with no yardstick — nido's own work has no stance document, so
   relation-honest has nothing to check against — is not a defect in the record,
   and an amender told to fix one would amend a true record until the complaint
   went away."
  [record]
  (vec (filter #(= :underivable (:status %)) (:checks record))))

(defn design-finding-base-key [c] [:check (:check c)])
(def design-finding-key (dispute-aware design-finding-base-key))

(defn trajectory
  "The run, as the human reading the escalated decision needs it. Rounds that
   found nothing and gave up nothing are still listed: a round that passed
   quietly is evidence about the ones that did not."
  [history]
  (vec (map-indexed
        (fn [i {:keys [findings retreats disputes amended?]}]
          (cond-> {:round (inc i)}
            (seq findings) (assoc :found (mapv #(name (:check %)) findings))
            (some? amended?) (assoc :amended (boolean amended?))
            (seq retreats) (assoc :weakened (mapv #(str (name (:what %)) " — " (:detail %)) retreats))
            (seq disputes) (assoc :disputed (mapv :claim disputes))))
        history)))

(defn design-amend-prompt
  "Instruction to repair a design record the derivation found wanting.

   :recut and :amend are given different jobs, because saying the wrong one is
   worse than saying nothing: a decomposition that does not hold is not fixed by
   restating claims, and a claim that is wrong is not fixed by re-cutting layers."
  [{:keys [design baseline recommend reason checks findings out-path]}]
  (str
   "A read-only judge derived what could be derived about this DESIGN record,\n"
   "before any code is written, and it did not come out clean.\n\n"
   (case recommend
     :recut (str "It says the DECOMPOSITION does not hold. Re-cut it. The claims may be\n"
                 "right; how the change is split into layers or phases is what is wrong,\n"
                 "and restating the claims will not fix it.\n\n")
     :resurvey (str "It said the PREMISE was wrong — and the survey has since been\n"
                    "re-run and now holds against the code. The corrected baseline is\n"
                    "below.\n\n"
                    "Re-state this design against it. That is not a re-citation: the\n"
                    "design's claims were made about a reading of the area that has\n"
                    "changed, so each one has to be checked against the new survey and\n"
                    "may not survive it. Point :baseline :seq at the corrected baseline\n"
                    "and set :supersedes to the design you are replacing.\n\n"
                    "If a claim no longer holds under the corrected survey, change it.\n"
                    "Re-pointing the citation while leaving the claims untouched asserts\n"
                    "the design still stands on a premise nobody has re-checked it\n"
                    "against, which is the failure this whole round exists to catch.\n\n")
     (str "It says the RECORD has a derivable defect. Repair the record — the\n"
          "commitment may well be sound, and the layering with it.\n\n"))
   "Your job is to make the record TRUE and coherent. It is NOT to make the\n"
   "checks pass. Lowering the effort, softening :revisit to :within, dropping\n"
   "invariants or routing work away from :fix-here all quiet a check without\n"
   "making anything truer; every one of those is measured and reported to the\n"
   "human this decision escalates to.\n\n"
   "WHY: " reason "\n\n"
   "THE CURRENT DESIGN:\n\n" (pr-str (ws/unstamp design))
   (when baseline
     (str "\n\nTHE BASELINE IT CITES:\n\n" (pr-str (ws/unstamp baseline))))
   "\n\nWHAT FAILED TO DERIVE — numbered, and you answer them by number:\n\n"
   (str/join
    "\n"
    (map-indexed (fn [i {:keys [check note]}]
                   (str (inc i) ". " (name check) " — " note))
                 checks))
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
   (when (contains? design :modules)
     (str "\n\nA reading may only use its own lens's verdicts, and a lens only reads the\n"
          "subject it is about. The ledger refuses anything else and the whole record\n"
          "is lost with it, so use these and nothing else:\n"
          (lens-block)))
   "\n\nIF A CHECK IS WRONGLY MARKED BROKEN, SAY SO INSTEAD OF AMENDING FOR IT.\n"
   "You do not settle it — the judge is asked again with your objection in front\n"
   "of it. An objection with no reason is dropped.\n\n"
   "Write EDN to:\n\n  " out-path "\n\n"
   "  {:record   <the COMPLETE superseding design — every field, not a diff>\n"
   "   :disputes [{:finding 1 :because \"...\" :evidence [\"src/x.clj:41\"]}]}\n\n"
   "Omit :record if every check is disputed and the design needs no change.\n"
   "It must satisfy the same schema the current one does; nido reads this file,\n"
   "validates it, and appends it as the superseding design. Do not append it\n"
   "yourself and do not commit anything."))

(def design-judge-stage
  "Derive everything derivable, and stop the moment nothing is left.

   Four ways to end here and only one of them is convergence-shaped. :proceed
   escalates because the ask is the point. A run whose only remaining checks are
   underivable also escalates, because there is nothing an amender could do about
   a missing yardstick. A finding stated a third time after two objections
   escalates. Everything else is another round."
  {:name :judge
   :run
   (fn [ctx]
     (let [{:keys [cwd code-cwd run-id]} (:config ctx)
           counts (dispute-counts (:history ctx))
           record (design-decision!
                   {:cwd cwd :code-cwd code-cwd :run-id run-id
                    :label (str "design-decision-round-" (:iter ctx))
                    :disputes (disputes-for-judge (:history ctx))})
           traj   (trajectory (:history ctx))
           final! (fn [c] (append! cwd (cond-> record (seq traj) (assoc :trajectory traj))) c)]
       (cond
         (:outcome record)
         (do (append! cwd record)
             (assoc ctx :record record :status (:outcome record)))

         (= :proceed (:recommend record))
         (final! (assoc ctx :record record :findings []
                        :underivable (underivable-checks record)
                        :control :escalate :status :proceed))

         :else
         (let [findings (mapv #(assoc % :disputed-n
                                      (get counts (design-finding-base-key %) 0))
                              (broken-checks record))]
           (cond
             (some #(>= (:disputed-n %) 2) findings)
             (final! (assoc ctx :record record :findings findings
                            :underivable (underivable-checks record)
                            :control :escalate :status :disputed))

             ;; Nothing derivable failed, yet the round will not say proceed —
             ;; so what is left is a yardstick it could not reach. An amender
             ;; asked to fix that would amend a true record until the complaint
             ;; stopped.
             (empty? findings)
             (final! (assoc ctx :record record :findings []
                            :underivable (underivable-checks record)
                            :control :escalate :status :underivable))

             :else
             (do (append! cwd record)
                 (assoc ctx :record record :findings findings
                        :underivable (underivable-checks record))))))))})

(defn- resurvey!
  "Repair the premise by running the baseline loop, then come back.

   The nested loop emits nothing into this run's report: its rounds are not this
   run's rounds, and folding them in would renumber both. What it does write is
   the ledger — every baseline review and every superseding baseline — so the
   trajectory survives where a reader looks for it.

   Any non-:sufficient outcome is terminal HERE. A design round cannot proceed on
   a survey the baseline loop could not make true, and re-judging the design
   against it would produce a decision built on the premise that just failed.

   Uncapped. A re-survey is only half a repair — the design is re-stated against
   the corrected survey afterwards — so every cycle changes the record the next
   round judges, and the engine's own stall detector is what ends a run that has
   stopped getting anywhere. A count would stop it while it was still making
   progress, which is the one thing a convergence loop must not do."
  [ctx]
  (let [{:keys [cwd code-cwd run-id budget]} (:config ctx)
        [project ws-id] (stages/project+ws-from-cwd cwd)
        n   (count (filter :resurveyed (:history ctx)))
        ;; The survey the design was JUDGED against, which is the only one whose
        ;; repair can change the verdict. A workstream may hold several — a
        ;; narrow follow-up written beside the broad survey it came out of — and
        ;; repairing the newest instead would leave the cited one untouched
        ;; however many rounds it ran.
        cited (stages/discover-baseline cwd (ws/latest-entry project ws-id :design))
        out (rloop/run-loop {:cwd cwd
                             :code-cwd code-cwd
                             :run-id (str run-id "-resurvey-" (inc n))
                             :budget budget
                             :emit (fn [_])
                             :baseline    cited
                             :pipeline    baseline-pipeline
                             :finding-key baseline-finding-key})]
        (if (= :sufficient (:status out))
          ;; No history entry here. The re-survey is only HALF the repair — the
          ;; design still cites the survey that was wrong — so the round is not
          ;; over, and the amendment that finishes it records them together.
          ;;
          ;; The corrected survey travels as a VALUE. Reading it back as "the
          ;; latest baseline" would hand the design amender whatever was
          ;; appended last, which is how the citation came to point at a survey
          ;; of a different area in the first place.
          (assoc ctx :resurveyed (:status out)
                 :resurveyed-baseline (or (:under-repair out) cited))
          ;; The nested failure's DETAIL travels with its status. Without it the
          ;; terminal says :resurvey-amend-invalid and stops — the one shape a
          ;; judgment surface must not take, since a reader cannot act on a
          ;; refusal whose reason stayed inside a loop they never saw.
          (assoc ctx :resurveyed (:status out)
                 :amend-error (:amend-error out)
                 :history (conj (vec (:history ctx))
                                {:iter (:iter ctx) :findings (:findings ctx)
                                 :retreats [] :disputes [] :resurveyed (:status out)})
                 :control :stop
                 :status (keyword (str "resurvey-" (name (:status out))))))))

(defn- amend-design!
  "Launch the amender against the design record and take in what it hands back.

   `baseline` is what the amender is shown alongside the design: the CITED one
   for an ordinary amendment, and the corrected one when this is finishing a
   re-survey. That difference is the whole of the re-survey repair — the
   amendment is what moves the citation."
  [ctx recommend baseline]
  (let [{:keys [cwd run-id budget]} (:config ctx)
        code-cwd (or (:code-cwd (:config ctx)) cwd)
        [project ws-id] (stages/project+ws-from-cwd cwd)
        prev     (ws/latest-entry project ws-id :design)
        dir      (cstate/run-dir run-id)
        out-path (str (fs/path dir (str "design-amend-round-" (:iter ctx) ".edn")))
        before (stages/working-copy-state code-cwd)]
    (fs/create-dirs dir)
    (fs/delete-if-exists out-path)
    (agent/launch!
     {:run-id run-id :cwd code-cwd :budget budget
      :first-message (design-amend-prompt
                      {:design prev
                       :baseline baseline
                       :recommend recommend
                       :reason (get-in ctx [:record :reason])
                       :checks (:findings ctx)
                       :findings (get-in ctx [:record :findings])
                       :out-path out-path})
      :err-file (str (fs/path dir (str "design-amend-round-" (:iter ctx) ".err.log")))})
    (cond
      (not= before (stages/working-copy-state code-cwd))
      (assoc ctx :control :stop :status :amend-touched-code)

      (not (fs/exists? out-path))
      (assoc ctx :control :stop :status :amend-noop)

      :else
      (let [raw    (try (edn/read-string (slurp out-path)) (catch Exception _ nil))
            answer (parse-amend-answer raw (:findings ctx) design-finding-base-key)
            {:keys [record disputes]} answer
            entry  (fn [retreats amended?]
                     (cond-> {:iter (:iter ctx) :findings (:findings ctx)
                              :retreats retreats :disputes disputes :amended? amended?}
                       (:resurveyed ctx) (assoc :resurveyed (:resurveyed ctx))))]
        (cond
          (nil? answer)
          (assoc ctx :control :stop :status :amend-unreadable)

          (and (nil? record) (seq disputes))
          (assoc ctx :disputes disputes :retreats []
                 :history (conj (vec (:history ctx)) (entry [] false)))

          (nil? record)
          (assoc ctx :control :stop :status :amend-noop)

          :else
          (let [err (try (ws/append-entry! project ws-id
                                           {:kind :design}
                                           (pr-str (ws/unstamp record)))
                         nil
                         (catch Exception e (ledger-refusal e)))]
            (if err
              (assoc ctx :control :stop :status :amend-invalid :amend-error err)
              (let [retreats (retreat/design-retreats prev record)
                    ctx' (assoc ctx
                                :retreats retreats
                                :disputes disputes
                                :history (conj (vec (:history ctx)) (entry retreats true)))]
                (if (design-round-worth-running? record)
                  (assoc ctx' :amended? true)
                  (assoc ctx' :amended? true :control :stop :status :retreated))))))))))

(def design-amend-stage
  "Repair whatever the recommendation named — the record, the cut, or the
   premise.

   The premise takes two steps, and skipping the second is how the loop fails to
   converge. A re-survey repairs the BASELINE, but the design still cites the
   survey that was wrong, and `discover-baseline` resolves the citation rather
   than the newest entry — deliberately, so a later survey cannot silently change
   what an already-judged design was judged against. So a re-survey alone changes
   nothing the next round can see: it would judge the same design against the
   same stale baseline, reach the same verdict, and re-survey again until the
   cap. The design is re-stated against the corrected survey here, and only that
   finishes the repair."
  {:name :amend
   :run
   (fn [ctx]
     (let [{:keys [cwd dry-run?]} (:config ctx)
           recommend (get-in ctx [:record :recommend])
           [project ws-id] (stages/project+ws-from-cwd cwd)]
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
                        (stages/discover-baseline
                         cwd (ws/latest-entry project ws-id :design))))))})

(def design-pipeline
  "judge -> amend, where amend may be a whole baseline loop."
  [design-judge-stage design-amend-stage])
