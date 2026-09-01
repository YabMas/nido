;; src/nido/review/conformance.clj
(ns nido.review.conformance
  "The round's mechanical reviewer: the project's own declared design, checked, and reported as
   findings alongside the ones the agents wrote.

   It joins the fan-out rather than sitting beside the loop as a separate gate, and that is the
   point. A finding here gets a handle, an owner layer, a disposition and a fixer for the same
   reason every other finding does — and the convergence machinery then notices a design
   violation the loop cannot shift, which a gate run once at the end never can.

   What it does NOT get is the last word. The warden may close one as a false positive, and the
   checker has no opinions to be wrong about — so the landing gate re-asks the question with no
   agent in the loop. That is the division: the review loop TRIES to fix it, the gate GUARANTEES
   it. Neither has to be perfect for the pair to be."
  (:require
   [clojure.string :as str]
   [nido.design.check :as design]
   [nido.review.digest :as digest]))

(def ^:private rows-shown
  "How many offending rows a finding's body carries before it says how many more there are.

   A law broken in sixty places is one problem told sixty times; the fixer needs enough rows to
   see the shape and the count to know it is not done when the listed ones are gone."
  12)

(defn- body
  [{:keys [law vars offenders]} files]
  (let [shown (take rows-shown offenders)
        more  (- (count offenders) (count shown))]
    (str "The project's declared design says: " law ".\n\n"
         "It does not hold here:\n\n"
         (str/join "\n" (for [row shown] (str "    " (design/offender-line vars row))))
         (when (pos? more) (str "\n    … and " more " more"))
         "\n\nEither the code moves or the declaration does — one of them is wrong, and\n"
         "leaving them to disagree is not an option: `bb nido:land:check` refuses the\n"
         "landing while they do. The declaration is in " (str/join ", " files) ".\n\n"
         "Changing the declaration is a legitimate fix, but it is a DESIGN change: say\n"
         "in the commit why the rule was wrong, rather than widening it until the code\n"
         "fits. If that call is not yours to make, this is a `park` — it contradicts a\n"
         "named invariant of the design, which is the case park is for.\n\n"
         "This finding is MECHANICAL: a checker read the declaration and the extracted\n"
         "call graph. It is not a false positive — there is no reviewer here to have\n"
         "misread anything. What can be wrong is the declaration.")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :Path] [:vector :Finding]]}
  findings
  "Design violations in `worktree` as review findings — one per broken LAW, not one per
   offending row.

   Per law because a law broken in forty places is almost always one root cause, and forty
   findings would be forty handles, forty rulings and forty fixer turns for it. Per law also
   gives the finding a title that is stable across rounds — the law's own description — which is
   what lets `no-progress?` see that the loop has stopped shifting it.

   An undecidable check is itself a finding. A checker that will not run is a real defect in the
   branch, and reporting nothing would let the round pass on an answer nobody gave."
  [project worktree]
  (let [result (design/check project worktree)
        files  (:files result)
        mk     (fn [title body]
                 {:title      title
                  :body       body
                  :priority   1
                  :confidence 1.0
                  :reach      :repo
                  :file       nil
                  :line-start nil
                  :id         (digest/short-id (str "design|" title))})]
    (case (:status result)
      (:unmodelled :satisfied) []
      :undecidable [(mk "design: the design check did not complete"
                        (str "`bb nido:design:check` could not decide whether this branch obeys\n"
                             "the project's declared design: " (:error result) "\n\n"
                             "This is not a clean bill of health, it is nobody being able to tell.\n"
                             "Fix the checker."))]
      :violated (mapv (fn [v] (mk (str "design: " (:law v)) (body v files)))
                      (:violations result)))))
