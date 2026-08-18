(ns nido.review.prompts
  "Prompt text for the review loop's codex + claude stages."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))   ; used by judge-prompt / fix-prompt (Tasks 5–6)

(def review-prompt
  "codex review-guidelines prompt (lifted from codex's review template)."
  (slurp (io/resource "review/review_prompt.md")))

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
  "The design record, rendered for the judge. This is the yardstick: findings are
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
   registers; it cannot be violated by a line of code, and a judge that cites it
   against one is inventing specificity it does not have."
  [stance]
  (str "PROJECT STANCE — background framing only. Use it to reason about whether a\n"
       "boundary is carrying its weight or whether code is intent or drift. It is\n"
       "NOT a checklist: never cite it against a specific finding.\n"
       stance "\n"))

(defn judge-prompt
  "Build the judge prompt. findings: normalized findings this round.
   history: prior rounds digest. design: this workstream's :design record (or nil).
   stance: the project's stance text (or nil). The judge is report-only (no tools),
   so everything it reasons from is inlined here — it cannot read the code, and in
   particular cannot infer the current design for itself."
  [{:keys [findings history design stance]}]
  (str
   "You are the JUDGE in an automated code-review loop. Decide whether the\n"
   "current review findings warrant another fix pass.\n\n"
   "Return EXACTLY one fenced ```json block, nothing after it, matching:\n"
   "{\"decision\": \"continue|stop|escalate\", \"reason\": \"...\", \"fix_findings\": [0,2]}\n\n"
   "- continue: there are meaningful issues worth fixing now. fix_findings = the\n"
   "  indices (into the findings list below) worth fixing; omit to fix all.\n"
   "- stop: the change is essentially clean; remaining items are nits.\n"
   "- escalate: the findings CONTRADICT A NAMED INVARIANT of the design below —\n"
   "  the design is in question, not its execution. Name the invariant in your\n"
   "  reason. Do not escalate because findings merely feel fundamental.\n\n"
   (if design
     (str (design-block design) "\n")
     (str "No design record on this workstream. Judge the findings on their own\n"
          "merits, and do NOT escalate: with no stated invariant there is nothing\n"
          "for a finding to contradict.\n\n"))
   (when stance (str (stance-block stance) "\n"))
   "History of prior rounds (findings + what was fixed):\n"
   (pr-str history) "\n\n"
   "This round's findings (index: [priority] title — body):\n"
   (->> findings
        (map-indexed (fn [i f]
                       (str i ": [P" (:priority f) "] " (:title f) " — " (:body f))))
        (str/join "\n"))))
