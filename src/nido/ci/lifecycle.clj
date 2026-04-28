(ns nido.ci.lifecycle
  "Top-level local-CI orchestrator.

   Two entry points:
     * `execute-step-run!`  — one step under full isolation (services,
                              command, process group, step_run_timeout).
                              Internal: called only from execute-run!.
     * `execute-run!`       — the user-facing aggregate: mints run-id,
                              resolves the step selection, fans out
                              step-runs in parallel (respecting :after),
                              owns the signal handlers, writes the
                              aggregate manifest, rolls up outcomes.

   The aggregate is the sole owner of signal handlers and the shared
   abort-promise; step-runs cooperate by checking the promise during
   long blocking operations (healthcheck polling, command wait)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.java.io :as jio]
   [nido.ci.isolated-app :as isolated-app]
   [nido.ci.isolated-pg :as isolated-pg]
   [nido.ci.isolation :as isolation]
   [nido.ci.local-edn :as local-edn]
   [nido.ci.lockfile :as lockfile]
   [nido.ci.manifest :as manifest]
   [nido.ci.paths :as paths]
   [nido.ci.run :as run]
   [nido.ci.services :as services]
   [nido.ci.step-run :as step-run]
   [nido.ci.template :as ci-template]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.context :as ctx]
   [nido.session.engine :as engine]))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn- start-timeout-watchdog!
  "Daemon thread that fires `on-timeout` after timeout-ms unless
   `done-promise` is delivered first."
  [timeout-ms done-promise on-timeout]
  (when (and timeout-ms (pos? timeout-ms))
    (doto (Thread.
           ^Runnable
           (fn []
             (when (= ::timed-out (deref done-promise timeout-ms ::timed-out))
               (try (on-timeout) (catch Throwable _ nil)))))
      (.setDaemon true)
      (.start))))

(defn- kill-command!
  [step-run-snapshot]
  (try
    (if-let [pgid (:command-pgid step-run-snapshot)]
      (proc/stop-process-group! pgid)
      (when-let [pid (:command-pid step-run-snapshot)]
        (proc/stop-process! pid)))
    (catch Exception _ nil)))

;; ---------------------------------------------------------------------------
;; StepRun orchestration (was: execute-run! in the pre-aggregate world)
;; ---------------------------------------------------------------------------

(defn- resolve-step-with-defaults [step config]
  (-> step
      (update :services #(or % []))
      (update :env      #(or % {}))
      (assoc :_defaults (:defaults config))))

(defn- step-substitution-context
  "Build the template context exposed to a step's :command, :env, etc.
   Keeps the namespace flat: `{{session.name}}`, `{{session.app-port}}`,
   `{{project.name}}`, `{{step.app-port}}`, `{{step.pg-port}}`, etc.

   When called pre-isolation (no `isolation` arg) the `:step` keys
   resolve to nil and `ctx/substitute` leaves the placeholders
   verbatim — a second pass, after isolation has allocated ports,
   fills them in. Two-phase substitution lets ci.edn declare
   `{{step.app-port}}` in `:env` even though the port doesn't exist
   yet at config-load time."
  [{:keys [session isolation]}]
  {:project {:name (:project-name session)}
   :session {:name (:session-name session)
             :worktree-path (:worktree-path session)
             :instance-id (:instance-id session)
             :app-port (:app-port session)
             :pg-port (:pg-port session)}
   :step {:app-port (:app-port isolation)
          :pg-port (:pg-port isolation)}})

(defn- sr-run-command!
  "Spawn the step's command under the step-run's isolation and block
   until exit. Returns the exit code.

   Output redirection note: passing a `java.io.File` for :out / :err
   plumbs the kernel-level Redirect.appendTo, so the child writes
   directly to the log on disk. Earlier this used an OutputStream which
   forces babashka.process to spin a JVM pump thread reading from a
   pipe — and that pump stalled when a grandchild (e.g. brian's
   lint-deps shelling out to clj-kondo with `:out :string`) emitted
   large captured output, deadlocking the whole step."
  [step-run-atom]
  (let [{:keys [step session isolation paths]} @step-run-atom
        ;; Second-phase substitution: any {{step.*}} placeholders that
        ;; were left literal at config-load time (because isolation
        ;; allocations hadn't happened yet) get filled in here.
        substituted (ctx/substitute
                     (step-substitution-context
                      {:session session :isolation isolation})
                     step)
        env (isolation/env-overrides {:session session
                                      :isolation isolation
                                      :step substituted})
        working-dir (or (:working-dir substituted)
                        (:work-dir isolation)
                        (:worktree-path session))
        command-log (paths/service-log-path paths "command")
        _ (fs/create-dirs (:logs-dir paths))
        log-file (jio/file command-log)
        child (p/process {:cmd (isolation/spawn-command-vec (:command substituted))
                          :dir working-dir
                          :extra-env env
                          :out log-file
                          :err log-file})
        pid (.pid ^java.lang.Process (:proc child))
        pgid (or (isolation/await-own-pgid pid) pid)]
    (swap! step-run-atom step-run/attach-process pid pgid)
    (manifest/sync-run! @step-run-atom)
    (:exit @child)))

(defn- sr-pump-exception! [step-run-atom e]
  (when-not (or (step-run/terminal? @step-run-atom)
                (step-run/tearing-down? @step-run-atom))
    (swap! step-run-atom step-run/fail (or (ex-message e) (str e)))
    (manifest/sync-run! @step-run-atom)))

(defn- watch-abort!
  "Background thread that, on abort-promise delivery, flips the step-run
   into :tearing_down and kills its command + service groups. Harmless
   if the step-run already finished."
  [step-run-atom abort-promise]
  (doto (Thread.
         ^Runnable
         (fn []
           (deref abort-promise)
           (let [[old new]
                 (swap-vals! step-run-atom
                             (fn [sr]
                               (if (or (step-run/terminal? sr)
                                       (step-run/tearing-down? sr))
                                 sr
                                 (step-run/aborted-by-run sr))))]
             (when-not (identical? old new)
               (try (manifest/sync-run! new) (catch Exception _ nil)))
             (kill-command! @step-run-atom)
             (services/kill-all! @step-run-atom))))
    (.setDaemon true)
    (.start)))

(defn execute-step-run!
  "Execute one StepRun end to end. Expects the parent Run to own signal
   handling; this function only cooperates via abort-promise. Returns
   the final step-run snapshot."
  [{:keys [run-id session config step-name step paths abort-promise]}]
  (let [resolved-step (->> (resolve-step-with-defaults step config)
                           (ctx/substitute (step-substitution-context
                                            {:session session})))
        initial (-> (step-run/new-step-run {:run-id run-id
                                            :step-name step-name
                                            :step resolved-step
                                            :session session
                                            :paths paths})
                    (assoc :step resolved-step))
        step-run-atom (atom initial)
        done-promise (promise)
        defaults (:defaults config)
        timeout-ms (:step-run-timeout-ms defaults)]
    (core/log-step (str "step-run " (name step-name) " starting"))
    (fs/create-dirs (:root paths))
    (manifest/sync-run! @step-run-atom)
    (watch-abort! step-run-atom abort-promise)
    (start-timeout-watchdog!
     timeout-ms done-promise
     (fn []
       (let [[old new]
             (swap-vals! step-run-atom
                         (fn [sr]
                           (if (or (step-run/terminal? sr)
                                   (step-run/tearing-down? sr))
                             sr
                             (step-run/timed-out sr))))]
         (when-not (identical? old new)
           (core/log-step (str "step-run " (name step-name)
                               " exceeded step_run_timeout"))
           (try (manifest/sync-run! new) (catch Exception _ nil)))
         (kill-command! @step-run-atom)
         (services/kill-all! @step-run-atom))))
    (try
      (let [iso (isolation/prepare-dirs! paths (:worktree-path session))
            iso (if (:isolated-pg? resolved-step)
                  (let [pg (isolated-pg/start!
                            {:project-name (:project-name session)
                             :work-dir     (:work-dir iso)
                             :from-port    (:pg-port session)})]
                    (assoc iso :pg-port (:pg-port pg) :isolated-pg pg))
                  iso)
            iso (if (:isolated-app? resolved-step)
                  (let [app (isolated-app/start!
                             {:work-dir (:work-dir iso)})]
                    (isolated-pg/rewrite-app-port!
                     (:work-dir iso) (:app-port session) (:app-port app))
                    (assoc iso :app-port (:app-port app) :isolated-app app))
                  iso)
            ;; Render local.edn from the project's session.edn template
            ;; with isolation-allocated ports. Crucial for main-mode runs
            ;; (the developer's main local.edn doesn't carry session
            ;; ports), redundant-but-safe for session-mode runs (the
            ;; cloned session local.edn already has the same shape; the
            ;; render just normalises it).
            _ (when (or (:isolated-pg? resolved-step) (:isolated-app? resolved-step))
                (try
                  (local-edn/render!
                   {:session-edn (engine/load-session-edn (:project-name session))
                    :work-dir    (:work-dir iso)
                    :isolation   iso})
                  (catch Exception _ nil)))
            ;; Step-supplied `:local-edn-overrides` apply last so they
            ;; can change project-agnostic flags (e.g. brian's
            ;; `:dev/auto-init? true` to make a CI-spawned JVM mount
            ;; on boot).
            _ (isolated-pg/merge-local-edn-overrides!
               (:work-dir iso) (:local-edn-overrides resolved-step))
            ;; Re-substitute now that isolation allocations exist —
            ;; ci.edn :services :start, :healthcheck :port-from-env,
            ;; etc. can reference {{step.app-port}} just like :env can.
            substituted-step (ctx/substitute
                              (step-substitution-context
                               {:session session :isolation iso})
                              resolved-step)]
        (swap! step-run-atom step-run/prepare iso)
        (swap! step-run-atom assoc :step substituted-step))
      (manifest/sync-run! @step-run-atom)
      (let [substituted-step (:step @step-run-atom)
            iso             (:isolation @step-run-atom)
            env (isolation/env-overrides
                 {:session session
                  :isolation iso
                  :step substituted-step})
            ;; Services share the step's clone work-dir (where the
            ;; rewritten local.edn lives) so a spawned project app
            ;; reads the per-step PG/app ports rather than the
            ;; session's. The earlier static `working-dir` (worktree
            ;; root) was only correct when no clone existed.
            service-cwd (or (:working-dir substituted-step)
                            (:work-dir iso)
                            (:worktree-path session))
            services-result
            (if (seq (:services substituted-step))
              (let [ready-promises
                    (services/start-all!
                     {:run-atom step-run-atom
                      :env env
                      :working-dir service-cwd
                      :abort-promise abort-promise
                      :defaults defaults})]
                (services/await-all-ready! ready-promises))
              :all-ready)]
        (case services-result
          :all-ready
          (when-not (step-run/tearing-down? @step-run-atom)
            (swap! step-run-atom step-run/begin-command)
            (manifest/sync-run! @step-run-atom)
            (let [exit-code (sr-run-command! step-run-atom)]
              (when-not (step-run/tearing-down? @step-run-atom)
                (swap! step-run-atom step-run/record-command-exit exit-code)
                (manifest/sync-run! @step-run-atom))))

          :aborted nil ; abort-promise handler already set state

          :failed
          (when-not (step-run/tearing-down? @step-run-atom)
            (swap! step-run-atom step-run/fail "service failed healthcheck")
            (manifest/sync-run! @step-run-atom))))
      (catch Throwable t
        (sr-pump-exception! step-run-atom t)))
    (services/kill-all! @step-run-atom)
    (swap! step-run-atom services/mark-all-killed)
    (when-let [iso-app (:isolated-app (:isolation @step-run-atom))]
      (try (isolated-app/stop! iso-app) (catch Exception _ nil)))
    (when-let [iso-pg (:isolated-pg (:isolation @step-run-atom))]
      (try (isolated-pg/stop! iso-pg) (catch Exception _ nil)))
    (try (isolation/cleanup-dirs! (:isolation @step-run-atom))
         (catch Exception _ nil))
    (swap! step-run-atom step-run/finish)
    (manifest/sync-run! @step-run-atom)
    (deliver done-promise :done)
    (core/log-step (str "step-run " (name step-name)
                        " done: outcome=" (:outcome @step-run-atom)))
    @step-run-atom))

;; ---------------------------------------------------------------------------
;; Aggregate Run orchestration
;; ---------------------------------------------------------------------------

(defn- validate-step-graph!
  "Reject unknown :after references and cycles between steps."
  [selected-steps]
  (let [names (set (map :name selected-steps))]
    (doseq [s selected-steps
            dep (:after s)]
      (when-not (contains? names dep)
        (throw (ex-info (str "Step " (name (:name s))
                             " has :after referencing unknown step "
                             (name dep))
                        {:step (:name s) :missing-after dep
                         :selected (vec names)}))))
    (let [adj (into {} (for [s selected-steps]
                         [(:name s) (or (:after s) [])]))]
      (letfn [(visit [node path visited stack]
                (cond
                  (contains? stack node)
                  (throw (ex-info (str "Cycle in step :after graph: "
                                       (mapv name (conj path node)))
                                  {:cycle (conj path node)}))
                  (contains? visited node) visited
                  :else
                  (let [visited' (reduce
                                  (fn [vs child]
                                    (visit child (conj path node)
                                           vs (conj stack node)))
                                  visited
                                  (get adj node))]
                    (conj visited' node))))]
        (reduce (fn [vs s] (visit (:name s) [] vs #{})) #{} selected-steps)))))

(defn- install-run-signal-handlers!
  "SIGINT/SIGTERM/shutdown-hook → mark Run :tearing_down + deliver
   abort-promise + kill any live step-run pgids we know about."
  [run-atom abort-promise]
  (let [teardown
        (fn []
          (let [[old new]
                (swap-vals! run-atom
                            (fn [r]
                              (if (or (run/terminal? r) (run/tearing-down? r))
                                r
                                (run/interrupt r))))]
            (when-not (identical? old new)
              (try (manifest/sync-run! new) (catch Exception _ nil)))
            (when-not (realized? abort-promise)
              (deliver abort-promise :aborted))))]
    (doseq [signal ["INT" "TERM"]]
      (try
        (sun.misc.Signal/handle
         (sun.misc.Signal. signal)
         (reify sun.misc.SignalHandler
           (handle [_ _] (teardown))))
        (catch Throwable _ nil)))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable teardown))))

(defn- step-run-snapshot-summary
  "Keys we persist back to the aggregate manifest (the full step-run
   state is available at steps/<name>/status.edn)."
  [sr]
  (select-keys sr [:step-run-id :step-name :state :outcome
                   :started-at :finished-at
                   :command-exit-code :error-message
                   :paths]))

(defn- fan-out-step-run!
  "Future that waits for :after deps, calls execute-step-run!, and
   records the resulting snapshot on the aggregate Run."
  [{:keys [step-name step session config run-atom run-paths
           abort-promise all-done-promises own-done-promise]}]
  (future
    (try
      (doseq [dep (:after step)]
        (when-let [p (get all-done-promises dep)]
          @p))
      (if (realized? abort-promise)
        (let [skipped (-> (step-run/new-step-run
                           {:run-id (:run-id @run-atom)
                            :step-name step-name
                            :step step
                            :session session
                            :paths (paths/step-run-paths run-paths step-name)})
                          (step-run/aborted-by-run)
                          (step-run/finish))]
          (swap! run-atom run/record-step-run step-name
                 (step-run-snapshot-summary skipped))
          (manifest/sync-run! @run-atom))
        (let [step-paths (paths/step-run-paths run-paths step-name)
              result (execute-step-run!
                      {:run-id       (:run-id @run-atom)
                       :session      session
                       :config       config
                       :step-name    step-name
                       :step         step
                       :paths        step-paths
                       :abort-promise abort-promise})]
          (swap! run-atom run/record-step-run step-name
                 (step-run-snapshot-summary result))
          (manifest/sync-run! @run-atom)))
      (catch Throwable t
        (core/log-step (str "step-run " (name step-name)
                            " crashed: " (ex-message t))))
      (finally
        (deliver own-done-promise :done)))))

(defn- profile-set
  "Resolve a `:profile` keyword (or default) into a set of step names.
   Returns nil when the project has no `:profiles` declared and the
   user didn't ask for one — that path means 'run every step',
   matching the pre-profile behavior. Throws on a profile name the
   project doesn't define."
  [config requested-profile]
  (let [profiles (:profiles config)
        target   (or requested-profile (when profiles :default))]
    (cond
      (nil? target)               nil
      (contains? profiles target) (set (map keyword (get profiles target)))
      :else
      (throw (ex-info (str "Unknown ci.edn :profile '" target "'")
                      {:profile target
                       :known-profiles (vec (keys profiles))})))))

(defn- resolve-step-selection
  "Apply selection filters to the full step set from ci.edn:

   * `:only` (keyword or seq) — explicit allow-list, wins over everything.
   * `:rerun-failed-of` — explicit set of failed steps to re-run.
   * `:profile` — named slice declared under `:profiles` in ci.edn.
     Defaults to `:default` when the project declares profiles.

   When none of the above apply, every step runs — preserves the
   no-profile behavior projects have today.

   Returns the ordered vector of StepDefinitions to execute. Throws if
   the resulting set is empty."
  [config {:keys [only rerun-failed-of profile]}]
  (let [all-steps (:steps config)
        prof-set  (profile-set config profile)
        named-set (cond
                    only
                    (cond
                      (sequential? only) (set (map keyword only))
                      :else              #{(keyword only)})

                    rerun-failed-of
                    (set rerun-failed-of)

                    prof-set
                    prof-set

                    :else nil)
        selected (if named-set
                   (for [[step-name step-def] all-steps
                         :when (contains? named-set step-name)]
                     (assoc step-def :name step-name))
                   (for [[step-name step-def] all-steps]
                     (assoc step-def :name step-name)))
        ;; When the selection is a strict subset, `:after` deps that
        ;; fell outside the selection are meaningless — drop them so the
        ;; graph validator doesn't reject a user-requested subset run.
        selected-names (set (map :name selected))
        selected-vec (vec
                      (for [s selected]
                        (update s :after
                                (fn [deps]
                                  (vec (filter selected-names deps))))))]
    (when (empty? selected-vec)
      (throw (ex-info "No steps selected for this Run"
                      {:only only :rerun-failed-of rerun-failed-of
                       :profile profile
                       :known-steps (vec (keys all-steps))})))
    (when only
      (doseq [n named-set]
        (when-not (contains? all-steps n)
          (throw (ex-info (str "Unknown step: " (name n))
                          {:step n :known-steps (vec (keys all-steps))})))))
    selected-vec))

(defn execute-run!
  "Execute a Run end to end. ci-context = {:session :config :selection}
   where :selection is {:only <kw-or-vec>? :rerun-failed-of <set-of-kws>?
   :rerun-of <run-id>?}. Returns the final aggregate run snapshot."
  [{:keys [session config selection]}]
  (let [run-id (paths/mint-run-id)
        run-paths (paths/run-paths
                   {:instance-state-dir (:instance-state-dir session)
                    :run-id run-id})
        selected-steps (resolve-step-selection config selection)
        _ (validate-step-graph! selected-steps)
        step-names (mapv :name selected-steps)
        step-by-name (into {} (for [s selected-steps] [(:name s) s]))
        initial-run (run/new-run
                     {:run-id    run-id
                      :session   session
                      :paths     run-paths
                      :rerun-of  (:rerun-of selection)
                      :step-names step-names})
        run-atom (atom initial-run)
        abort-promise (promise)
        run-done-promise (promise)
        done-promises (into {} (for [n step-names] [n (promise)]))]
    (core/log-step (str "ci run " run-id
                        " starting — steps=" step-names
                        (when (:rerun-of selection)
                          (str " rerun-of=" (:rerun-of selection)))))
    (fs/create-dirs (:root run-paths))
    (manifest/sync-run! @run-atom)
    (install-run-signal-handlers! run-atom abort-promise)
    ;; If any selected step opted into per-step isolated PG, make sure
    ;; the project's CI-template variant exists before any step starts
    ;; cloning from it. Idempotent — `ensure!` short-circuits when the
    ;; variant is already on disk, so the cost of opting an extra step
    ;; in is one fs/exists? check.
    (when (some :isolated-pg? selected-steps)
      (ci-template/ensure! session))
    ;; Drift between the source worktree's lockfile(s) and its install
    ;; tree (e.g. node_modules) silently propagates into every step's
    ;; clone, since clones happen wholesale from the worktree. Running
    ;; the project's sync commands here, once, before the fan-out, costs
    ;; one round of `npm ci` on the rare drift case and is free when
    ;; everything is already in sync.
    (lockfile/verify-and-sync! session (:lockfile-checks config))
    (swap! run-atom run/begin)
    (manifest/sync-run! @run-atom)
    (let [futures
          (mapv (fn [step-name]
                  (fan-out-step-run!
                   {:step-name         step-name
                    :step              (get step-by-name step-name)
                    :session           session
                    :config            config
                    :run-atom          run-atom
                    :run-paths         run-paths
                    :abort-promise     abort-promise
                    :all-done-promises done-promises
                    :own-done-promise  (get done-promises step-name)}))
                step-names)]
      ;; Wait for every step-run to reach its terminal state.
      (doseq [f futures] @f))
    (when-not (realized? abort-promise)
      (deliver abort-promise :aborted))
    (swap! run-atom run/enter-teardown)
    (manifest/sync-run! @run-atom)
    (swap! run-atom run/finish)
    (manifest/sync-run! @run-atom)
    (deliver run-done-promise :done)
    (core/log-step (str "ci run " run-id
                        " done: outcome=" (:outcome @run-atom)))
    @run-atom))

;; ---------------------------------------------------------------------------
;; Rerun: look up the most recent Run for this session, pick failing
;; step names, kick off a new Run scoped to just those.
;; ---------------------------------------------------------------------------

(defn- last-run-manifest
  "Most-recent run-id manifest under <instance-state>/runs/, or nil."
  [session]
  (let [runs-root (str (fs/path (:instance-state-dir session) "runs"))]
    (when (fs/exists? runs-root)
      (let [run-ids (->> (fs/list-dir runs-root)
                         (filter fs/directory?)
                         (map (comp str fs/file-name))
                         sort
                         reverse)
            first-with-manifest
            (some (fn [id]
                    (let [p (str (fs/path runs-root id "status.edn"))]
                      (when (fs/exists? p)
                        {:run-id id :manifest-path p})))
                  run-ids)]
        first-with-manifest))))

(defn- failed-step-names [manifest]
  (->> (:step-runs manifest)
       (filter (fn [[_ sr]]
                 (contains? #{:failed :errored :interrupted} (:outcome sr))))
       (mapv first)))

(defn resolve-rerun-selection!
  "Read the session's last Run manifest and return a selection the
   caller can hand to `execute-run!`. Throws if there's no prior Run or
   the prior Run had no failed steps."
  [session {:keys [from]}]
  (let [target (or (when from
                     (let [p (str (fs/path (:instance-state-dir session)
                                           "runs" from "status.edn"))]
                       (when (fs/exists? p) {:run-id from :manifest-path p})))
                   (last-run-manifest session))]
    (when-not target
      (throw (ex-info "No prior Run found for this session"
                      {:instance-state-dir (:instance-state-dir session)
                       :from from})))
    (let [manifest (edn/read-string (slurp (:manifest-path target)))
          failed (failed-step-names manifest)]
      (when (empty? failed)
        (throw (ex-info (str "Run " (:run-id target) " had no failed steps to rerun")
                        {:run-id (:run-id target)
                         :step-outcomes
                         (into {} (for [[n sr] (:step-runs manifest)]
                                    [n (:outcome sr)]))})))
      {:rerun-failed-of failed
       :rerun-of (:run-id target)})))
