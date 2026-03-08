(ns nido.vsdd.judge
  "Verdict parsing for the VSDD judge."
  (:require [clojure.string :as str]))

(defn parse-verdict
  "Extract verdict from judge output.
   Returns {:verdict :keyword :reasoning string}.

   Expects first line to contain one of:
     CONVERGED
     ROUTE_TO_IMPL
     ROUTE_TO_SPEC — auto-resolvable spec issue, architect handles it
     ROUTE_TO_SPEC:structural — needs human review"
  [output]
  (let [lines (str/split-lines (str output))
        first-line (or (first lines) "")
        reasoning (str/join "\n" (rest lines))]
    (cond
      (str/includes? first-line "CONVERGED")
      {:verdict :converged :reasoning reasoning}

      (str/includes? first-line "ROUTE_TO_IMPL")
      {:verdict :route-to-impl :reasoning reasoning}

      (str/includes? first-line "ROUTE_TO_SPEC")
      {:verdict :route-to-spec
       :structural? (str/includes? first-line "structural")
       :reasoning reasoning}

      :else
      {:verdict :unknown :reasoning (str output)})))

(defn build-judge-prompt
  "Build the judge prompt from a critic report."
  [{:keys [report-edn module-path iteration max-iterations]}]
  (str "DO NOT use any tools. Just read this prompt and respond with a verdict.\n"
       "\n"
       "You are the VSDD judge. You review a critic report and decide the next action.\n"
       "\n"
       "Module: " module-path "\n"
       "Iteration: " iteration " of " max-iterations "\n"
       "\n"
       "Critic report:\n"
       "---\n"
       report-edn "\n"
       "---\n"
       "\n"
       "The critic report separates findings into :findings-for-impl and :findings-for-spec.\n"
       "Use this separation to route precisely:\n"
       "- If there are actionable impl findings → ROUTE_TO_IMPL\n"
       "- If only spec findings remain → ROUTE_TO_SPEC\n"
       "- If only spec findings remain and they are structural → ROUTE_TO_SPEC:structural\n"
       "\n"
       "Convergence criteria:\n"
       "- No major findings\n"
       "- Remaining minors (if any) are edge-case hardening rather than spec violations\n"
       "- The last iteration produced no new findings (only carried-forward ones)\n"
       "- Diminishing returns: further iterations are unlikely to yield meaningful improvement\n"
       "\n"
       "Respond with EXACTLY one of these verdicts on the FIRST LINE (no other text on line 1):\n"
       "\n"
       "CONVERGED — spec, tests, and implementation are aligned\n"
       "ROUTE_TO_IMPL — implementation needs fixes, route back to implementer\n"
       "ROUTE_TO_SPEC — spec needs minor fixes the architect can handle (naming, clarity, missing detail)\n"
       "ROUTE_TO_SPEC:structural — spec needs architectural changes with trade-offs (escalate to human)\n"
       "\n"
       "Then explain your reasoning briefly on subsequent lines."))
