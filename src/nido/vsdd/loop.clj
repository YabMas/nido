(ns nido.vsdd.loop
  "Generic VSDD orchestration loop.

   Runs: critic → judge → implementer → critic → ...
   Critic assesses, judge routes, implementer acts on findings.
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
     :prompts         — {:implementer (fn [module-path critic-report] ...)
                          :critic      (fn [module-path run-dir iteration] ...)
                          :architect   (fn [module-path findings] ...)}"
  (:require [clojure.java.io :as jio]
            [clojure.string :as str]
            [nido.core :as core]
            [nido.io :as io]
            [nido.vsdd.agent :as agent]
            [nido.vsdd.judge :as judge]
            [nido.vsdd.manifest :as manifest]
            [nido.vsdd.prompts :as prompts]))

;; ---------------------------------------------------------------------------
;; Defaults

(def ^:private default-max-iterations 99)

(defn- module-slug [module-path]
  (-> module-path
      (str/replace #"/$" "")
      (str/split #"/")
      last))

;; ---------------------------------------------------------------------------
;; Prompt defaults

(defn- default-implementer-prompt [module-path critic-report run-dir iteration]
  (str "MODULE: " module-path "\n"
       "\n"
       "The critic has reviewed this module and produced the report below. "
       "Focus on :findings-for-impl — these are the findings you can address. "
       "Ignore :findings-for-spec (those are for the architect, not you).\n"
       "\n"
       "Your goal: every spec rule has a test, every test passes, "
       "no code exists without spec justification.\n"
       "\n"
       "CRITIC REPORT:\n"
       "---\n"
       critic-report "\n"
       "---\n"
       "\n"
       "When you are done, write a completion summary to:\n"
       run-dir "/" (module-slug module-path) "/impl-report-" iteration ".edn\n"
       "\n"
       "The summary must be an EDN map with this structure:\n"
       "{:files-modified\n"
       " [{:path \"src/example/file.clj\"\n"
       "   :changes [\"Description of change 1\" \"Description of change 2\"]}]\n"
       " :findings-addressed [1 2 3]\n"
       " :findings-skipped [{:finding 4 :reason \"spec-level, cannot modify spec\"}]}\n"
       "\n"
       ":findings-addressed is a vector of finding numbers from :findings-for-impl "
       "that you addressed. :findings-skipped lists findings you did not address with "
       "a reason for each. :files-modified lists every file you touched with a "
       "brief description of each change."))


(defn- format-routing-history
  "Format iteration routing history for the critic prompt."
  [manifest]
  (when (seq (:iterations manifest))
    (str "\nROUTING HISTORY:\n"
         (str/join "\n"
                   (map (fn [{:keys [iteration judge]}]
                          (str "  Iteration " iteration " → "
                               (when-let [v (:verdict judge)]
                                 (name v))))
                        (:iterations manifest)))
         "\n")))

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

(defn- invoke-role
  "Invoke an agent for a VSDD role. Uses project agent if configured,
   otherwise uses nido's built-in prompt for the role."
  [config role prompt]
  (let [agent-name (get-in config [:roles role :agent])]
    (if agent-name
      ;; Project-local agent
      (agent/invoke-agent
       {:agent-name   agent-name
        :prompt       prompt
        :env          (:env config)
        :working-dir  (:working-dir config)
        :display-name (name role)})
      ;; Nido-managed agent
      (agent/invoke-agent
       {:system-prompt (prompts/load-agent-prompt role)
        :allowed-tools (or (get-in config [:roles role :tools])
                           (prompts/tools-for-role role))
        :prompt        prompt
        :env           (:env config)
        :working-dir   (:working-dir config)
        :display-name  (name role)}))))

(defn- run-implementer
  "Run the implementer agent with a critic report. Returns agent result map."
  [config module-path critic-report run-dir iteration]
  (let [prompt-fn (get-prompt-fn config :implementer)]
    (print-phase "Implementer" module-path)
    (invoke-role config :implementer
                 (prompt-fn module-path critic-report run-dir iteration))))

(defn- run-critic
  "Run the critic agent. Returns agent result map."
  [config module-path run-dir iteration manifest]
  (let [prompt-fn (get-prompt-fn config :critic)
        slug-dir (str run-dir "/" (module-slug module-path))
        config (update config :env merge {"VSDD_RUN_DIR" run-dir})
        base-prompt (prompt-fn module-path run-dir iteration)
        prompt (str base-prompt (format-routing-history manifest))]
    (.mkdirs (jio/file slug-dir))
    (print-phase "Critic" module-path)
    (invoke-role config :critic prompt)))

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
  (let [prompt-fn (get-prompt-fn config :architect)]
    (print-phase "Architect" module-path)
    (invoke-role config :architect
                 (prompt-fn module-path findings))))

;; ---------------------------------------------------------------------------
;; Unresolved spec finding collection

(defn- collect-unresolved-spec-findings
  "Scan all critic reports and find spec findings that were never routed
   to the architect. Returns a vector of finding maps with :iteration added."
  [manifest run-dir module-path]
  (let [slug (module-slug module-path)
        spec-routed-iters (set (keep (fn [{:keys [iteration judge architect]}]
                                       (when (or (= :route-to-spec (:verdict judge))
                                                 (:auto-resolved architect))
                                         iteration))
                                     (:iterations manifest)))]
    (->> (:iterations manifest)
         (remove #(spec-routed-iters (:iteration %)))
         (mapcat (fn [{:keys [iteration]}]
                   (let [report-file (str run-dir "/" slug "/critic-report-" iteration ".edn")
                         report (io/read-edn report-file)]
                     (when (seq (:findings-for-spec report))
                       (map #(assoc % :from-iteration iteration)
                            (:findings-for-spec report))))))
         vec)))

;; ---------------------------------------------------------------------------
;; Judge + route handling (extracted for reuse by resume)

(defn- handle-judge-and-route
  "Run judge on the critic report, then route based on verdict.
   Returns [action updated-manifest & args] where action is
   :converged, :route-to-impl, :escalated, or :unknown.
   For :route-to-impl, args contains [manifest critic-report-edn]."
  [config module-path run-dir iteration critic-result mfst]
  (let [report-edn (or (read-critic-report run-dir module-path iteration)
                       "{:findings [] :verdict :converged}")
        judge-result (run-judge config report-edn module-path iteration)
        report-path (str run-dir "/" (module-slug module-path)
                         "/critic-report-" iteration ".edn")
        iteration-record {:iteration iteration
                          :critic {:session-id (:session-id critic-result)
                                   :report-path report-path}
                          :judge {:verdict (:verdict judge-result)
                                  :structural? (:structural? judge-result)
                                  :session-id (:session-id judge-result)}
                          :implementer nil
                          :architect nil}
        mfst (manifest/add-iteration mfst iteration-record)]

    (case (:verdict judge-result)
      :converged
      (do
        (println (str "Module " module-path " converged on iteration " iteration))
        (let [unresolved (collect-unresolved-spec-findings mfst run-dir module-path)
              mfst (assoc mfst :unresolved-spec-findings unresolved)
              final (manifest/finalize mfst :converged)]
          (when (seq unresolved)
            (println)
            (println (str "  ⚠ " (count unresolved) " unresolved spec finding(s):"))
            (doseq [{:keys [rule description from-iteration]} unresolved]
              (println (str "    - [iter " from-iteration "] "
                            (when rule (str rule ": "))
                            description))))
          (manifest/save! final)
          [:converged final]))

      :route-to-impl
      (do
        (println "  Routing to implementer with critic report")
        (manifest/save! mfst)
        [:route-to-impl mfst report-edn])

      :route-to-spec
      (let [structural? (:structural? judge-result)
            architect-disabled? (get-in config [:roles :architect :disabled?])]
        (if (and (not architect-disabled?) (not structural?))
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
                  (println "  Architect done — routing back to critic")
                  (manifest/save! mfst)
                  ;; After architect fixes spec, re-run critic to re-assess
                  [:route-to-critic mfst])
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
  "Run the critic→judge→implementer loop for a module.
   Accepts optional start-phase to resume mid-iteration.
   start-phase: :critic (default), :judge, or :implementer
   Returns the final manifest."
  [config module-path run-dir initial-manifest
   & {:keys [start-iteration start-phase start-critic-report]
      :or {start-iteration 1 start-phase :critic start-critic-report nil}}]
  (let [max-iter (or (:max-iterations config) default-max-iterations)]
    (loop [iteration start-iteration
           phase start-phase
           mfst initial-manifest
           ;; Carry forward partial results for mid-iteration resume
           critic-result nil
           critic-report start-critic-report]

      (if (> iteration max-iter)
        (do (println)
            (println (str "CIRCUIT BREAKER: " module-path " did not converge after "
                          max-iter " iterations."))
            (let [unresolved (collect-unresolved-spec-findings mfst run-dir module-path)
                  mfst (assoc mfst :unresolved-spec-findings unresolved)
                  final (manifest/finalize mfst :exhausted)]
              (when (seq unresolved)
                (println (str "  ⚠ " (count unresolved) " unresolved spec finding(s):"))
                (doseq [{:keys [rule description from-iteration]} unresolved]
                  (println (str "    - [iter " from-iteration "] "
                                (when rule (str rule ": "))
                                description))))
              (manifest/save! final)
              final))

        (do
          (when (= phase :critic)
            (println)
            (println (str "---- Iteration " iteration "/" max-iter
                          " for " module-path " ----")))

          (case phase
            :critic
            (let [result (run-critic config module-path run-dir iteration mfst)]
              (when-not (zero? (:exit result))
                (let [final (manifest/finalize mfst :error)]
                  (manifest/save! final)
                  (throw (ex-info "Critic agent failed"
                                  {:module module-path :exit (:exit result)}))))
              (recur iteration :judge mfst result nil))

            :judge
            (let [[action & args] (handle-judge-and-route
                                    config module-path run-dir iteration
                                    critic-result mfst)]
              (case action
                :converged (first args)
                :escalated (first args)
                :unknown (first args)
                :route-to-impl
                (let [[updated-mfst report-edn] args]
                  (recur iteration :implementer updated-mfst
                         critic-result report-edn))
                :route-to-critic
                (let [[updated-mfst] args]
                  (recur (inc iteration) :critic updated-mfst nil nil))))

            :implementer
            (let [result (run-implementer config module-path critic-report run-dir iteration)]
              (when-not (zero? (:exit result))
                (let [final (manifest/finalize mfst :error)]
                  (manifest/save! final)
                  (throw (ex-info "Implementer agent failed"
                                  {:module module-path :exit (:exit result)}))))
              ;; Record implementer in the current iteration
              (let [slug (module-slug module-path)
                    report-path (str run-dir "/" slug "/impl-report-" iteration ".edn")
                    updated-record (assoc (last (:iterations mfst))
                                          :implementer
                                          {:session-id (:session-id result)
                                           :report-path report-path})
                    mfst (update mfst :iterations
                                 #(conj (vec (butlast %)) updated-record))]
                (manifest/save! mfst)
                (recur (inc iteration) :critic mfst nil nil)))))))))

;; ---------------------------------------------------------------------------
;; Resume analysis

(defn- analyze-resume-point
  "Determine where to resume a run from its manifest.
   Flow is critic→judge→implementer, so resume accordingly.
   Returns {:iteration N :phase :critic|:judge|:implementer}
   or nil if the run cannot be resumed."
  [manifest]
  (when (#{:in-progress :interrupted} (:status manifest))
    (let [iterations (:iterations manifest)]
      (if (empty? iterations)
        ;; No iterations completed — start from scratch
        {:iteration 1 :phase :critic}
        ;; Check the last iteration
        (let [last-iter (last iterations)
              n (:iteration last-iter)
              has-critic? (get-in last-iter [:critic :session-id])
              has-judge? (get-in last-iter [:judge :verdict])
              has-impl? (get-in last-iter [:implementer :session-id])]
          (cond
            ;; Full iteration done (impl completed) — start next from critic
            has-impl?
            {:iteration (inc n) :phase :critic}

            ;; Judge routed to impl but impl didn't run — resume from implementer
            (and has-judge? (= :route-to-impl (get-in last-iter [:judge :verdict])))
            {:iteration n :phase :implementer
             :critic-report-path (get-in last-iter [:critic :report-path])}

            ;; Judge ran but not route-to-impl — start fresh next iteration
            has-judge?
            {:iteration (inc n) :phase :critic}

            ;; Critic completed but judge didn't — resume from judge
            has-critic?
            {:iteration n :phase :judge}

            ;; Nothing completed — restart this iteration
            :else
            {:iteration n :phase :critic}))))))

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
                     ;; Read latest manifest from disk — the loop may have
                     ;; saved iterations before the error was thrown.
                     (let [latest (or (manifest/load-manifest run-dir) mfst)
                           final (manifest/finalize latest :error)]
                       (manifest/save! (assoc final :error (ex-message e)))
                       (throw e))))]

      ;; Summary
      (println)
      (core/log-step "VSDD Summary")
      (println (str "  " module-path ": "
                    (case (:status result)
                      :converged "CONVERGED"
                      :escalated "NEEDS SPEC REVIEW"
                      :exhausted "EXHAUSTED (did not converge)"
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
                          (not= :critic (:phase resume-point)))
                   (update mfst :iterations #(vec (butlast %)))
                   mfst)
            ;; Recover critic report from disk if resuming into implementer phase
            critic-report (when-let [path (:critic-report-path resume-point)]
                            (io/read-text path))]

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
                                  :start-critic-report critic-report)
                       (catch Exception e
                         (let [latest (or (manifest/load-manifest run-dir) mfst)
                               final (manifest/finalize latest :error)]
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
