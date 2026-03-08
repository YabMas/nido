(ns nido.vsdd.loop
  "Generic VSDD orchestration loop.

   Runs: implementer → critic → judge → route
   With optional architect invocation for spec-level findings.

   Project-specific behavior is injected via the config map:
     :working-dir     — project root (where .claude/agents/ lives)
     :module-path     — module to verify (e.g. \"src/fukan/projection/\")
     :artifacts-dir   — base directory for run artifacts (e.g. \".vsdd\")
     :roles           — {:implementer {:agent \"module-owner\"}
                          :critic      {:agent \"critic\"}
                          :architect   {:agent \"architect\" :budget 2}}
     :max-iterations  — circuit breaker (default 3)
     :judge           — {:model \"haiku\"
                          :severity-gate {:cosmetic :auto
                                          :clarification :auto-logged
                                          :structural :escalate}}
     :env             — additional env vars passed to all agents
     :prompts         — {:implementer (fn [module-path feedback] ...)
                          :critic      (fn [module-path run-dir iteration] ...)
                          :architect   (fn [module-path findings] ...)}"
  (:require [clojure.java.io :as jio]
            [clojure.string :as str]
            [nido.core :as core]
            [nido.io :as io]
            [nido.vsdd.agent :as agent]
            [nido.vsdd.judge :as judge]
            [nido.vsdd.manifest :as manifest]))

;; ---------------------------------------------------------------------------
;; Defaults

(def ^:private default-max-iterations 3)
(def ^:private default-architect-budget 2)

(def ^:private default-severity-gate
  {:cosmetic      :auto
   :clarification :auto-logged
   :structural    :escalate})

;; ---------------------------------------------------------------------------
;; Prompt defaults

(defn- default-implementer-prompt [module-path feedback]
  (str "MODULE: " module-path "\n"
       "\n"
       "Assess the spec-implementation gap for this module and bring them into alignment.\n"
       "Read the spec, existing tests, and implementation. Determine the situation "
       "(green field, partial implementation, spec changed, broader than spec, or seemingly aligned) "
       "and act accordingly. Your goal: every spec rule has a test, every test passes, "
       "no code exists without spec justification."
       (when feedback
         (str "\n\n"
              "FEEDBACK FROM CRITIC (fix these issues):\n"
              "---\n"
              feedback "\n"
              "---"))))

(defn- module-slug [module-path]
  (-> module-path
      (str/replace #"/$" "")
      (str/split #"/")
      last))

(defn- default-critic-prompt [module-path run-dir iteration]
  (str "MODULE: " module-path "\n"
       "RUN_DIR: " run-dir "\n"
       "\n"
       "Review the spec, tests, and implementation for this module. "
       "Check all three alignments (spec<->test, test<->impl, spec<->impl). "
       "Write your structured report to "
       run-dir "/" (module-slug module-path) "/critic-report-" iteration ".edn"))

(defn- default-architect-prompt [module-path findings]
  (str "MODULE: " module-path "\n"
       "\n"
       "The VSDD critic found spec-level issues that need your attention. "
       "Review the findings below and make minimal, targeted refinements to the spec.\n"
       "\n"
       "CONSTRAINTS:\n"
       "- You may REFINE existing spec rules (clarify wording, fix naming, add detail)\n"
       "- You may NOT add new rules or remove existing rules\n"
       "- Keep changes minimal — only address the specific findings\n"
       "\n"
       "FINDINGS:\n"
       "---\n"
       findings "\n"
       "---"))

;; ---------------------------------------------------------------------------
;; Internal helpers

(defn- get-prompt-fn [config role]
  (or (get-in config [:prompts role])
      (case role
        :implementer default-implementer-prompt
        :critic      default-critic-prompt
        :architect   default-architect-prompt)))

(defn- print-phase [phase-name module]
  (println)
  (println (str "=== " phase-name " (" module ") ==="))
  (println))

(defn- read-critic-report [run-dir module-path iteration]
  (let [slug (module-slug module-path)
        report-file (str run-dir "/" slug "/critic-report-" iteration ".edn")]
    (io/read-text report-file)))

;; ---------------------------------------------------------------------------
;; Phase runners

(defn- run-implementer
  "Run the implementer agent. Returns agent result map."
  [config module-path feedback]
  (let [prompt-fn (get-prompt-fn config :implementer)
        agent-name (get-in config [:roles :implementer :agent])]
    (print-phase "Implementer" module-path)
    (agent/invoke-agent
     {:agent-name  agent-name
      :prompt      (prompt-fn module-path feedback)
      :env         (:env config)
      :working-dir (:working-dir config)})))

(defn- run-critic
  "Run the critic agent. Returns agent result map."
  [config module-path run-dir iteration]
  (let [prompt-fn (get-prompt-fn config :critic)
        agent-name (get-in config [:roles :critic :agent])
        slug-dir (str run-dir "/" (module-slug module-path))]
    (.mkdirs (jio/file slug-dir))
    (print-phase "Critic" module-path)
    (agent/invoke-agent
     {:agent-name  agent-name
      :prompt      (prompt-fn module-path run-dir iteration)
      :env         (merge (:env config)
                          {"VSDD_RUN_DIR" run-dir})
      :working-dir (:working-dir config)})))

(defn- run-judge
  "Run the judge. Returns parsed verdict map."
  [config report-edn module-path iteration]
  (let [max-iter (or (:max-iterations config) default-max-iterations)
        model (get-in config [:judge :model] "haiku")]
    (print-phase "Judge" module-path)
    (let [prompt (judge/build-judge-prompt
                  {:report-edn     report-edn
                   :module-path    module-path
                   :iteration      iteration
                   :max-iterations max-iter})
          result (agent/invoke-judge
                  {:prompt      prompt
                   :model       model
                   :working-dir (:working-dir config)})
          verdict (judge/parse-verdict (:result result))]
      (println (str "  Judge verdict: " (name (:verdict verdict))
                    (when (:severity verdict)
                      (str " (" (name (:severity verdict)) ")"))))
      (println (:reasoning verdict))
      (assoc verdict
             :session-id (:session-id result)))))

(defn- run-architect
  "Run the architect agent for auto-resolvable spec issues.
   Returns agent result map."
  [config module-path findings]
  (let [prompt-fn (get-prompt-fn config :architect)
        agent-name (get-in config [:roles :architect :agent])]
    (print-phase "Architect" module-path)
    (agent/invoke-agent
     {:agent-name  agent-name
      :prompt      (prompt-fn module-path findings)
      :env         (:env config)
      :working-dir (:working-dir config)})))

;; ---------------------------------------------------------------------------
;; Main loop

(defn- run-cycle
  "Run the implementer→critic→judge loop for a module.
   Returns the final manifest."
  [config module-path run-dir initial-manifest]
  (let [max-iter (or (:max-iterations config) default-max-iterations)
        architect-budget (or (get-in config [:roles :architect :budget])
                             default-architect-budget)
        severity-gate (or (get-in config [:judge :severity-gate])
                          default-severity-gate)]
    (loop [iteration 1
           feedback nil
           architect-uses 0
           mfst initial-manifest]

      (when (> iteration max-iter)
        (println)
        (println (str "CIRCUIT BREAKER: " module-path " did not converge after "
                      max-iter " iterations."))
        (let [final (manifest/finalize mfst :error)]
          (manifest/save! final)
          (throw (ex-info "Circuit breaker: max iterations reached"
                          {:module module-path :iterations max-iter}))))

      (println)
      (println (str "---- Iteration " iteration "/" max-iter
                    " for " module-path " ----"))

      ;; Phase 1: Implementer
      (let [impl-result (run-implementer config module-path feedback)]
        (when-not (zero? (:exit impl-result))
          (let [final (manifest/finalize mfst :error)]
            (manifest/save! final)
            (throw (ex-info "Implementer agent failed"
                            {:module module-path :exit (:exit impl-result)}))))

        ;; Phase 2: Critic
        (let [critic-result (run-critic config module-path run-dir iteration)]
          (when-not (zero? (:exit critic-result))
            (let [final (manifest/finalize mfst :error)]
              (manifest/save! final)
              (throw (ex-info "Critic agent failed"
                              {:module module-path :exit (:exit critic-result)}))))

          ;; Phase 3: Judge
          (let [report-edn (or (read-critic-report run-dir module-path iteration)
                               "{:findings [] :verdict :converged}")
                judge-result (run-judge config report-edn module-path iteration)
                iteration-record {:iteration iteration
                                  :implementer {:session-id (:session-id impl-result)}
                                  :critic {:session-id (:session-id critic-result)
                                           :report-path (str run-dir "/"
                                                             (module-slug module-path)
                                                             "/critic-report-"
                                                             iteration ".edn")}
                                  :judge {:verdict (:verdict judge-result)
                                          :severity (:severity judge-result)
                                          :session-id (:session-id judge-result)}
                                  :architect nil}
                mfst (manifest/add-iteration mfst iteration-record)]

            (case (:verdict judge-result)
              :converged
              (do
                (println (str "Module " module-path " converged on iteration " iteration))
                (let [final (manifest/finalize mfst :converged)]
                  (manifest/save! final)
                  final))

              :route-to-impl
              (do
                (println "  Routing back to implementer with critic feedback")
                (manifest/save! mfst)
                (recur (inc iteration) (:result judge-result) architect-uses mfst))

              :route-to-spec
              (let [severity (:severity judge-result)
                    can-auto? (and (get-in config [:roles :architect :agent])
                                   (judge/auto-resolvable? severity severity-gate)
                                   (< architect-uses architect-budget))]
                (if can-auto?
                  ;; Architect auto-resolves
                  (do
                    (println (str "  Spec issue (" (name severity)
                                  ") — invoking architect (use "
                                  (inc architect-uses) "/" architect-budget ")"))
                    (when (= severity :clarification)
                      (println "  [logged] Architect clarification — review in manifest"))
                    (let [arch-result (run-architect config module-path report-edn)
                          updated-record (assoc-in (last (:iterations mfst))
                                                   [:architect]
                                                   {:session-id (:session-id arch-result)
                                                    :severity severity
                                                    :auto-resolved true})
                          mfst (update mfst :iterations
                                       #(conj (vec (butlast %)) updated-record))]
                      (if (zero? (:exit arch-result))
                        (do
                          (println "  Architect done — routing back to implementer")
                          (manifest/save! mfst)
                          (recur (inc iteration) nil (inc architect-uses) mfst))
                        (do
                          (println "  Architect failed — escalating to human")
                          (let [final (manifest/finalize mfst :route-to-spec)]
                            (manifest/save! final)
                            final)))))
                  ;; Escalate to human
                  (do
                    (println (str "Module " module-path
                                  " needs spec review — "
                                  (if (and (not can-auto?)
                                           (>= architect-uses architect-budget))
                                    "architect budget exhausted"
                                    (str "severity: " (name (or severity :structural))))))
                    (let [final (manifest/finalize mfst :route-to-spec)]
                      (manifest/save! final)
                      final))))

              ;; Unknown verdict
              (do
                (println "Unknown judge verdict — halting for review")
                (let [final (manifest/finalize mfst :unknown)]
                  (manifest/save! final)
                  final)))))))))

;; ---------------------------------------------------------------------------
;; Public API

(defn run
  "Run the VSDD loop for a module.

   Config map:
     :working-dir    — project root (required)
     :module-path    — module to verify (required)
     :artifacts-dir  — base dir for artifacts (default \".vsdd\")
     :roles          — agent role config (required, at minimum :implementer and :critic)
     :max-iterations — circuit breaker limit (default 3)
     :judge          — {:model \"haiku\" :severity-gate {...}}
     :env            — extra env vars for agents
     :prompts        — custom prompt functions per role

   Returns the final manifest."
  [config]
  (let [module-path (:module-path config)
        ;; Auto-set MODULE_PATH env var for hook scripts
        config (update config :env merge {"MODULE_PATH" module-path})
        artifacts-dir (or (:artifacts-dir config) ".vsdd")
        run-id (.format (java.time.LocalDateTime/now)
                        (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
        run-dir (str (:working-dir config) "/" artifacts-dir "/" run-id)]

    (.mkdirs (jio/file run-dir))

    (core/log-step "VSDD Run")
    (println (str "  Run ID:  " run-id))
    (println (str "  Module:  " module-path))
    (println (str "  Run dir: " run-dir))

    (let [mfst (manifest/create {:run-id run-id
                                 :module-path module-path
                                 :run-dir run-dir})
          _ (manifest/save! mfst)
          result (try
                   (run-cycle config module-path run-dir mfst)
                   (catch Exception e
                     (let [final (manifest/finalize mfst :error)]
                       (manifest/save! (assoc final :error (ex-message e)))
                       (throw e))))]

      ;; Summary
      (println)
      (core/log-step "VSDD Summary")
      (println (str "  " module-path ": "
                    (case (:status result)
                      :converged "CONVERGED"
                      :escalated "NEEDS SPEC REVIEW"
                      :error "ERROR"
                      (str (:status result)))))
      (println (str "  Iterations: " (count (:iterations result))))
      (println (str "  Artifacts:  " run-dir))
      result)))
