(ns nido.review.prompts
  "Prompt text for the review loop's codex + claude stages."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))   ; used by arbiter-prompt / fix-prompt (Tasks 5–6)

(def review-prompt
  "codex review-guidelines prompt (lifted from codex's review template)."
  (slurp (io/resource "review/review_prompt.md")))

(defn- brief-field
  [label v]
  (when-not (str/blank? (str v))
    (str label ": " v "\n")))

(defn layer-brief-block
  "The bounding preamble for a review aimed at ONE layer of a stack, built from
   that layer's `/stack` §5 brief. nil when there is no brief — a whole-stack
   review is not bounded by one, and saying nothing is better than saying
   \"no brief\", which reads as an instruction to go wide.

   `Out of scope` is the field that makes bounded review work: without it every
   reviewer re-derives the whole change and the stack's benefit is lost. It is
   stated as a prohibition here rather than as context, because a reviewer given
   it as context flags the item anyway and lets the reader sort it out."
  [{:keys [subject mode claims verify lane out-of-scope]}]
  (when (or claims verify out-of-scope)
    (str
     "THIS REVIEW IS BOUNDED TO ONE LAYER OF A STACKED CHANGE.\n\n"
     (brief-field "Layer" subject)
     (brief-field "Review mode" (some-> mode name))
     (brief-field "Claims" claims)
     (brief-field "Verify" verify)
     (brief-field "Lane" lane)
     (brief-field "Out of scope" out-of-scope)
     "\nHow to use this:\n"
     "- Review ONLY what this layer changes. The range below is its whole diff.\n"
     "- Out of scope is a PROHIBITION, not context. Do not flag those items —\n"
     "  they belong to another layer or another ticket, and someone has already\n"
     "  decided where. Flagging them re-derives the whole change, which is the\n"
     "  cost this layering exists to avoid.\n"
     "- Claims is what this layer asserts about itself. A finding that shows a\n"
     "  claim is FALSE is the most valuable thing you can return — say plainly\n"
     "  which claim, and what you saw that contradicts it.\n"
     "- Verify names the concrete checks this layer should pass. Discharge them\n"
     "  and report any that fail.\n"
     "- A `mechanical` layer asserts uniformity: your job is to find the one\n"
     "  place that got special handling, not to reopen the decision. A\n"
     "  `judgment` layer owns a decision: weigh it.\n\n")))

(defn fix-prompt
  "Instruction to fix the given findings. Do NOT commit — the engine commits."
  [{:keys [findings]}]
  (str
   "Fix the following code-review findings in this working directory. Make the\n"
   "MINIMAL change that resolves each. Do NOT commit — the orchestrator commits.\n\n"
   (->> findings
        (map (fn [f]
               (str "- [P" (:priority f) "] " (:title f) "\n"
                    "  file: " (:file f) ":" (:line-start f) "-" (:line-end f) "\n"
                    "  " (:body f))))
        (clojure.string/join "\n\n"))))

(defn- bullets [xs] (str/join "\n" (map #(str "- " %) xs)))

(defn- design-block
  "The design record, rendered for the arbiter. This is the yardstick: findings are
   judged against these invariants and nothing else. :rejected is included because
   a finding that re-proposes a rejected alternative is *answered* rather than new."
  [{:keys [shape invariants rejected standing]}]
  (str "THE DESIGN THIS CHANGE COMMITTED TO — judge the findings against this:\n"
       "Shape: " shape "\n"
       "Invariants:\n" (bullets invariants) "\n"
       (when (seq rejected)
         (str "Already considered and rejected. A finding that re-proposes one of\n"
              "these is ANSWERED, not new — unless it gives a reason the rejection\n"
              "no longer holds:\n"
              (bullets (map #(str (:alternative %) " — rejected because "
                                  (:why-not %)) rejected))
              "\n"))
       (when standing
         (str "Relation to the project's stance: " (name (:relation standing))
              (when-let [n (:note standing)] (str " — " n)) "\n"))))

(defn- stance-block
  "The project's stance, as framing only. It primes reasoning about boundaries and
   registers; it cannot be violated by a line of code, and an arbiter that cites it
   against one is inventing specificity it does not have."
  [stance]
  (str "PROJECT STANCE — background framing only. Use it to reason about whether a\n"
       "boundary is carrying its weight or whether code is intent or drift. It is\n"
       "NOT a checklist: never cite it against a specific finding.\n"
       stance "\n"))

(defn arbiter-prompt
  "Build the arbiter prompt. findings: normalized findings this round.
   history: prior rounds digest. design: this workstream's :design record (or nil).
   stance: the project's stance text (or nil). The arbiter is report-only (no tools),
   so everything it reasons from is inlined here — it cannot read the code, and in
   particular cannot infer the current design for itself."
  [{:keys [findings history design stance]}]
  (str
   "You are the ARBITER in an automated code-review loop. Decide whether the\n"
   "current review findings warrant another fix pass.\n\n"
   "Return EXACTLY one fenced ```json block, nothing after it, matching:\n"
   "{\"decision\": \"continue|stop|escalate\", \"reason\": \"...\", \"fix_findings\": [0,2]}\n\n"
   "- continue: there are meaningful issues worth fixing now. fix_findings = the\n"
   "  indices (into the findings list below) worth fixing; omit to fix all.\n"
   "- stop: the change is essentially clean; remaining items are nits.\n"
   "- escalate: the findings CONTRADICT A NAMED INVARIANT of the design below —\n"
   "  the design is in question, not its execution. Name the invariant in your\n"
   "  reason. Do not escalate because findings merely feel fundamental.\n\n"
   "Each finding carries a reach the reviewer assigned: local (a defect inside\n"
   "the current design), structural (about where a boundary sits — the reviewer\n"
   "could see shape but not intent), or unclear. It is not a severity.\n"
   "A structural finding is where escalate lives, and it is the one you must NOT\n"
   "hand to the fixer when it contradicts an invariant below: patching a design\n"
   "question makes it disappear without anyone deciding it. Leave such findings\n"
   "out of fix_findings and escalate instead.\n\n"
   (if design
     (str (design-block design) "\n")
     (str "No design record on this workstream. Judge the findings on their own\n"
          "merits, and do NOT escalate: with no stated invariant there is nothing\n"
          "for a finding to contradict.\n\n"))
   (when stance (str (stance-block stance) "\n"))
   "History of prior rounds (findings + what was fixed):\n"
   (pr-str history) "\n\n"
   "This round's findings (index: [priority/reach @reported-by] title — body).\n"
   "@reported-by is the layer whose review surfaced it, or `stack` for the pass\n"
   "over the whole change. It is where the finding was SEEN, which is not\n"
   "necessarily the layer that caused it.\n"
   (->> findings
        (map-indexed (fn [i f]
                       (str i ": [P" (:priority f) "/"
                            (name (or (:reach f) :unclear))
                            (when-let [l (:from-layer f)] (str " @" l)) "] "
                            (:title f) " — " (:body f))))
        (str/join "\n"))))
