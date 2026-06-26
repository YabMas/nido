(ns nido.review.prompts
  "Prompt text for the review loop's codex + claude stages."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))   ; used by judge-prompt / fix-prompt (Tasks 5–6)

(def review-prompt
  "codex review-guidelines prompt (lifted from codex's review template)."
  (slurp (io/resource "review/review_prompt.md")))

(defn judge-prompt
  "Build the judge prompt. findings: normalized findings this round.
   history: prior rounds digest. design-doc: path string or nil."
  [{:keys [findings history design-doc]}]
  (str
   "You are the JUDGE in an automated code-review loop. Decide whether the\n"
   "current review findings warrant another fix pass.\n\n"
   "Return EXACTLY one fenced ```json block, nothing after it, matching:\n"
   "{\"decision\": \"continue|stop|escalate\", \"reason\": \"...\", \"fix_findings\": [0,2]}\n\n"
   "- continue: there are meaningful issues worth fixing now. fix_findings = the\n"
   "  indices (into the findings list below) worth fixing; omit to fix all.\n"
   "- stop: the change is essentially clean; remaining items are nits.\n"
   "- escalate: the findings point to a FUNDAMENTAL design problem better solved\n"
   "  by a higher-level redesign than by incremental patching.\n\n"
   (when design-doc (str "Design doc for context: " design-doc "\n\n"))
   "History of prior rounds (findings + what was fixed):\n"
   (pr-str history) "\n\n"
   "This round's findings (index: [priority] title — body):\n"
   (->> findings
        (map-indexed (fn [i f]
                       (str i ": [P" (:priority f) "] " (:title f) " — " (:body f))))
        (str/join "\n"))))
