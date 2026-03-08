(ns nido.vsdd.judge
  "Verdict parsing and severity classification for the VSDD judge."
  (:require [clojure.string :as str]))

(def verdicts
  "All recognized verdict keywords."
  #{:converged :route-to-impl :route-to-spec})

(def severity-levels
  "Severity levels for ROUTE_TO_SPEC, ordered from least to most severe."
  #{:cosmetic :clarification :structural})

(defn parse-verdict
  "Extract verdict and optional severity from judge output.
   Returns {:verdict :keyword :severity :keyword-or-nil :reasoning string}.

   Expects first line to contain one of:
     CONVERGED
     ROUTE_TO_IMPL
     ROUTE_TO_SPEC
     ROUTE_TO_SPEC:cosmetic
     ROUTE_TO_SPEC:clarification
     ROUTE_TO_SPEC:structural"
  [output]
  (let [lines (str/split-lines (str output))
        first-line (or (first lines) "")
        reasoning (str/join "\n" (rest lines))]
    (cond
      (str/includes? first-line "CONVERGED")
      {:verdict :converged :severity nil :reasoning reasoning}

      (str/includes? first-line "ROUTE_TO_IMPL")
      {:verdict :route-to-impl :severity nil :reasoning reasoning}

      (str/includes? first-line "ROUTE_TO_SPEC")
      (let [severity (cond
                       (str/includes? first-line "cosmetic")      :cosmetic
                       (str/includes? first-line "clarification") :clarification
                       (str/includes? first-line "structural")    :structural
                       :else                                      :structural)]
        {:verdict :route-to-spec :severity severity :reasoning reasoning})

      :else
      {:verdict :unknown :severity nil :reasoning (str output)})))

(defn auto-resolvable?
  "Can the architect auto-resolve this severity without human intervention?"
  [severity severity-gate]
  (let [gate (or severity-gate
                 {:cosmetic :auto
                  :clarification :auto-logged
                  :structural :escalate})]
    (not= :escalate (get gate severity :escalate))))

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
       "Respond with EXACTLY one of these verdicts on the FIRST LINE (no other text on line 1):\n"
       "\n"
       "CONVERGED — spec, tests, and implementation are aligned\n"
       "ROUTE_TO_IMPL — implementation needs fixes, route back to implementer\n"
       "ROUTE_TO_SPEC:cosmetic — spec needs trivial fixes (naming, formatting, missing doc)\n"
       "ROUTE_TO_SPEC:clarification — spec needs clarification but no structural changes\n"
       "ROUTE_TO_SPEC:structural — spec needs architectural changes with trade-offs\n"
       "\n"
       "Then explain your reasoning briefly on subsequent lines."))
