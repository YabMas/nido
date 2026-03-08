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
                          :architect   {:agent \"architect\"}}
     :max-iterations  — circuit breaker (default 10)
     :judge           — {:model \"haiku\"}
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

(def ^:private default-max-iterations 10)

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
;; Judge + route handling (extracted for reuse by resume)

(defn- handle-judge-and-route
  "Run judge, then route based on verdict. Returns [action updated-manifest] where
   action is :converged, :route-to-impl, :escalated, or :unknown.
   For :route-to-impl, the manifest has the iteration appended but is not finalized."
  [config module-path run-dir iteration impl-result critic-result mfst]
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
                                  :structural? (:structural? judge-result)
                                  :session-id (:session-id judge-result)}
                          :architect nil}
        mfst (manifest/add-iteration mfst iteration-record)]

    (case (:verdict judge-result)
      :converged
      (do
        (println (str "Module " module-path " converged on iteration " iteration))
        (let [final (manifest/finalize mfst :converged)]
          (manifest/save! final)
          [:converged final]))

      :route-to-impl
      (do
        (println "  Routing back to implementer with critic feedback")
        (manifest/save! mfst)
        [:route-to-impl mfst (:result judge-result)])

      :route-to-spec
      (let [structural? (:structural? judge-result)
            has-architect? (get-in config [:roles :architect :agent])]
        (if (and has-architect? (not structural?))
          ;; Architect auto-resolves
          (do
            (println "  Spec issue — invoking architect")
            (let [arch-result (run-architect config module-path report-edn)
                  updated-record (assoc (last (:iterations mfst))
                                        :architect
                                        {:session-id (:session-id arch-result)
                                         :auto-resolved true})
                  mfst (update mfst :iterations
                               #(conj (vec (butlast %)) updated-record))]
              (if (zero? (:exit arch-result))
                (do
                  (println "  Architect done — routing back to implementer")
                  (manifest/save! mfst)
                  [:route-to-impl mfst nil])
                (do
                  (println "  Architect failed — escalating to human")
                  (let [final (manifest/finalize mfst :route-to-spec)]
                    (manifest/save! final)
                    [:escalated final])))))
          ;; Escalate to human
          (do
            (println (str "Module " module-path " needs spec review — "
                          (if structural? "structural change required" "no architect configured")))
            (let [final (manifest/finalize mfst :route-to-spec)]
              (manifest/save! final)
              [:escalated final]))))

      ;; Unknown
      (do
        (println "Unknown judge verdict — halting for review")
        (let [final (manifest/finalize mfst :unknown)]
          (manifest/save! final)
          [:unknown final])))))

;; ---------------------------------------------------------------------------
;; Main loop

(defn- run-cycle
  "Run the implementer→critic→judge loop for a module.
   Accepts optional start-phase to resume mid-iteration.
   start-phase: :implementer (default), :critic, or :judge
   Returns the final manifest."
  [config module-path run-dir initial-manifest
   & {:keys [start-iteration start-phase start-feedback]
      :or {start-iteration 1 start-phase :implementer start-feedback nil}}]
  (let [max-iter (or (:max-iterations config) default-max-iterations)]
    (loop [iteration start-iteration
           phase start-phase
           feedback start-feedback
           mfst initial-manifest
           ;; Carry forward partial results for mid-iteration resume
           impl-result nil
           critic-result nil]

      (when (> iteration max-iter)
        (println)
        (println (str "CIRCUIT BREAKER: " module-path " did not converge after "
                      max-iter " iterations."))
        (let [final (manifest/finalize mfst :error)]
          (manifest/save! final)
          (throw (ex-info "Circuit breaker: max iterations reached"
                          {:module module-path :iterations max-iter}))))

      (when (= phase :implementer)
        (println)
        (println (str "---- Iteration " iteration "/" max-iter
                      " for " module-path " ----")))

      (case phase
        :implementer
        (let [result (run-implementer config module-path feedback)]
          (when-not (zero? (:exit result))
            (let [final (manifest/finalize mfst :error)]
              (manifest/save! final)
              (throw (ex-info "Implementer agent failed"
                              {:module module-path :exit (:exit result)}))))
          (recur iteration :critic feedback mfst result nil))

        :critic
        (let [result (run-critic config module-path run-dir iteration)]
          (when-not (zero? (:exit result))
            (let [final (manifest/finalize mfst :error)]
              (manifest/save! final)
              (throw (ex-info "Critic agent failed"
                              {:module module-path :exit (:exit result)}))))
          (recur iteration :judge feedback mfst impl-result result))

        :judge
        (let [[action & args] (handle-judge-and-route
                                config module-path run-dir iteration
                                impl-result critic-result mfst)]
          (case action
            :converged (first args)
            :escalated (first args)
            :unknown (first args)
            :route-to-impl
            (let [[updated-mfst new-feedback] args]
              (recur (inc iteration) :implementer new-feedback
                     updated-mfst nil nil))))))))

;; ---------------------------------------------------------------------------
;; Resume analysis

(defn- analyze-resume-point
  "Determine where to resume a run from its manifest.
   Returns {:iteration N :phase :implementer|:critic|:judge :feedback string|nil}
   or nil if the run cannot be resumed."
  [manifest]
  (when (#{:in-progress :interrupted} (:status manifest))
    (let [iterations (:iterations manifest)]
      (if (empty? iterations)
        ;; No iterations completed — start from scratch
        {:iteration 1 :phase :implementer :feedback nil}
        ;; Check the last iteration
        (let [last-iter (last iterations)
              n (:iteration last-iter)
              has-impl? (get-in last-iter [:implementer :session-id])
              has-critic? (get-in last-iter [:critic :session-id])
              has-judge? (get-in last-iter [:judge :verdict])]
          (cond
            ;; Last iteration fully completed with route-to-impl — start next iteration
            (and has-judge? (= :route-to-impl (get-in last-iter [:judge :verdict])))
            {:iteration (inc n) :phase :implementer :feedback nil}

            ;; Judge ran but with unknown/other result — retry from implementer
            has-judge?
            {:iteration (inc n) :phase :implementer :feedback nil}

            ;; Critic completed but judge didn't — resume from judge
            has-critic?
            {:iteration n :phase :judge :feedback nil}

            ;; Implementer completed but critic didn't — resume from critic
            has-impl?
            {:iteration n :phase :critic :feedback nil}

            ;; Nothing completed in last iteration — restart that iteration
            :else
            {:iteration n :phase :implementer :feedback nil}))))))

;; ---------------------------------------------------------------------------
;; Public API

(defn run
  "Run the VSDD loop for a module.

   Config map:
     :working-dir    — project root (required)
     :module-path    — module to verify (required)
     :artifacts-dir  — base dir for artifacts (default \".vsdd\")
     :roles          — agent role config (required, at minimum :implementer and :critic)
     :max-iterations — circuit breaker limit (default 10)
     :judge          — {:model \"haiku\"}
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

(defn resume
  "Resume an interrupted VSDD run.

   Config map: same as `run`, plus:
     :run-id — the run ID to resume (required)

   Picks up from the last incomplete phase."
  [config]
  (let [module-path (:module-path config)
        config (update config :env merge {"MODULE_PATH" module-path})
        artifacts-dir (or (:artifacts-dir config) ".vsdd")
        run-id (:run-id config)
        run-dir (str (:working-dir config) "/" artifacts-dir "/" run-id)
        mfst (manifest/load-manifest run-dir)]

    (when-not mfst
      (throw (ex-info "No manifest found for run" {:run-id run-id :run-dir run-dir})))

    (let [resume-point (analyze-resume-point mfst)]
      (when-not resume-point
        (throw (ex-info "Run is not resumable (already completed or no manifest)"
                        {:run-id run-id :status (:status mfst)})))

      ;; Update PID and status for the resumed run
      (let [mfst (assoc mfst
                        :pid (.pid (java.lang.ProcessHandle/current))
                        :status :in-progress)
            ;; If resuming mid-iteration, pop the incomplete iteration record
            ;; so the loop can rebuild it cleanly
            mfst (if (and (seq (:iterations mfst))
                          (not= :implementer (:phase resume-point)))
                   (update mfst :iterations #(vec (butlast %)))
                   mfst)]

        (manifest/save! mfst)

        (core/log-step "VSDD Resume")
        (println (str "  Run ID:    " run-id))
        (println (str "  Module:    " module-path))
        (println (str "  Resuming:  iteration " (:iteration resume-point)
                      ", phase " (name (:phase resume-point))))
        (println (str "  Run dir:   " run-dir))

        (let [result (try
                       (run-cycle config module-path run-dir mfst
                                  :start-iteration (:iteration resume-point)
                                  :start-phase (:phase resume-point)
                                  :start-feedback (:feedback resume-point))
                       (catch Exception e
                         (let [final (manifest/finalize mfst :error)]
                           (manifest/save! (assoc final :error (ex-message e)))
                           (throw e))))]

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
          result)))))
