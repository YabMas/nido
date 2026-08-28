(ns nido.session.engine
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.config :as config]
   [nido.core :as core]
   [nido.io :as io]
   [nido.process :as proc]
   [nido.session.agent-guidance :as agent-guidance]
   [nido.session.context :as ctx]
   [nido.session.launcher :as launcher]
   [nido.session.service :as service]
   ;; Load service implementations (must be loaded before state for the
   ;; defmethods to register; state itself has no transitive deps on them).
   ;; `eval` is additionally aliased: the idempotent up path calls its app
   ;; toggle directly (see `reconcile-app!`).
   nido.session.services.config-file
   [nido.session.services.eval :as eval-svc]
   nido.session.services.postgresql
   nido.session.services.process
   [nido.session.state :as state]))

;; ---------------------------------------------------------------------------
;; Setup step dispatch
;; ---------------------------------------------------------------------------

(defn- git-common-project-root [project-dir]
  (let [result (shell {:continue true :out :string :err :string}
                      "git" "-C" project-dir "rev-parse" "--git-common-dir")]
    (when (zero? (:exit result))
      ;; --git-common-dir returns a relative path when discovery walks up
      ;; the tree (e.g. when a jj workspace lives below a colocated repo);
      ;; canonicalize against project-dir so callers always get an absolute
      ;; source root.
      (some-> (:out result)
              str/trim
              (->> (fs/path project-dir))
              fs/canonicalize
              fs/parent
              str))))

(defn- jj-source-root
  "When `project-dir` is a jj workspace (no `.git`, just `.jj/`), its
   `.jj/repo` is a small text file containing a relative path to the
   source's `.jj/repo/` directory. Resolve that pointer and return the
   source dir (parent of the source's `.jj/`)."
  [project-dir]
  (let [pointer (fs/path project-dir ".jj" "repo")]
    (when (fs/regular-file? pointer)
      (let [rel (str/trim (slurp (str pointer)))
            target (fs/canonicalize (fs/path (fs/parent pointer) rel))]
        (some-> target fs/parent fs/parent str)))))

(defn- source-project-root
  "Resolve the source project root for a worktree at `project-dir`. jj
   workspaces (no `.git`, just a `.jj/repo` pointer file) take precedence
   so we don't accidentally walk up past the workspace via git's discovery
   behavior. Falls back to `git rev-parse --git-common-dir` for plain git
   worktrees."
  [project-dir]
  (or (jj-source-root project-dir)
      (git-common-project-root project-dir)))

(defn- local-root-paths [project-dir]
  (let [deps-path (str (fs/path project-dir "deps.edn"))]
    (if-not (fs/exists? deps-path)
      []
      (let [deps-edn (edn/read-string (slurp deps-path))]
        (->> (:deps deps-edn)
             vals
             (keep :local/root)
             distinct)))))

(defn- path-present?
  "True if the path exists (file or directory) OR if it's a symlink — even
   one whose target no longer resolves. fs/exists? alone follows links and
   reports false for dangling symlinks, which then trips `ln -s`."
  [path]
  (or (fs/exists? path)
      (fs/sym-link? path)))

(defn- link-path! [source-path target-path]
  (when-let [parent (fs/parent target-path)]
    (fs/create-dirs parent))
  (let [result (shell {:continue true :out :string :err :string}
                      "ln" "-s" source-path target-path)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Failed to create symlink"
                      {:source source-path
                       :target target-path
                       :error (:err result)})))))

(defn- ensure-local-root-deps! [project-dir]
  (let [src-root (source-project-root project-dir)
        local-roots (local-root-paths project-dir)
        linked (atom [])
        missing (atom [])]
    (doseq [local-root local-roots
            :let [target-path (str (fs/normalize (fs/path project-dir local-root)))]
            :when (not (path-present? target-path))]
      (if src-root
        (let [source-path (str (fs/normalize (fs/path src-root local-root)))]
          (if (fs/exists? source-path)
            (do
              (link-path! source-path target-path)
              (swap! linked conj {:source source-path :target target-path}))
            (swap! missing conj {:local-root local-root
                                 :source source-path
                                 :target target-path})))
        (swap! missing conj {:local-root local-root :source nil :target target-path})))
    (when (seq @linked)
      (core/log-step "Linked local deps for this worktree:")
      (doseq [{:keys [source target]} @linked]
        (println "  " target "->" source)))
    (when (seq @missing)
      (throw (ex-info "Missing local/root dependencies for this worktree"
                      {:missing @missing
                       :hint "Ensure sibling repos exist near your git common project root."})))))

(defn- ensure-shared-link! [project-dir source-project-root rel-path]
  (when source-project-root
    (let [source-path (str (fs/path source-project-root rel-path))
          target-path (str (fs/path project-dir rel-path))]
      (when (and (fs/exists? source-path)
                 (not (path-present? target-path)))
        (link-path! source-path target-path)
        (core/log-step (str "Linked " rel-path " -> " source-path))))))

(defmulti run-setup-step!
  "Run a one-shot setup step. Dispatches on (:type step)."
  (fn [step _project-dir] (:type step)))

(defmethod run-setup-step! :worktree-links
  [step project-dir]
  (let [{:keys [shared-paths local-deps]} step
        source-root (source-project-root project-dir)]
    (when (= local-deps :deps-edn)
      (core/log-step "Ensuring local/root dependencies...")
      (ensure-local-root-deps! project-dir))
    (doseq [p shared-paths]
      (ensure-shared-link! project-dir source-root p))))

(defmethod run-setup-step! :shell
  [step project-dir]
  (let [{:keys [command skip-if-exists]} step]
    (if (and skip-if-exists (fs/exists? (str (fs/path project-dir skip-if-exists))))
      (core/log-step (str "Skipping shell step: " skip-if-exists " already exists"))
      (do
        (core/log-step (str "Running: " command))
        (let [result (shell {:continue true :out :string :err :string :dir project-dir}
                            "bash" "-lc" command)]
          (when-not (zero? (:exit result))
            (core/log-step (str "WARNING: shell step failed: " (:err result)))))))))

(defmethod run-setup-step! :default
  [step _project-dir]
  (core/log-step (str "WARNING: Unknown setup step type: " (:type step))))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn session-edn-path [project-name]
  (str (fs/path (core/nido-home) "projects" project-name "session.edn")))

(defn read-session-edn
  "Soft read: the project's session.edn, or nil when it has no session.edn.
   For read-only callers that can still answer from defaults — a project can be
   registered in projects.edn but never configured, and enumerating it must not
   throw. Use `load-session-edn` wherever the config is genuinely required
   (anything that boots or mutates a session)."
  [project-name]
  (let [path (session-edn-path project-name)]
    (when (fs/exists? path)
      (io/read-edn path))))

(defn load-session-edn [project-name]
  (let [path (session-edn-path project-name)]
    (when-not (fs/exists? path)
      (throw (ex-info (str "No session.edn found for project '" project-name "'")
                      {:path path
                       :hint "Create a session.edn in ~/.nido/projects/<name>/session.edn"})))
    (io/read-edn path)))

(defn resolve-project-name
  "Resolve a project-dir to its registered project name, falling back to the
   leaf path component."
  [project-dir]
  (let [projects (config/read-projects)]
    (or (some (fn [[name entry]]
                (when (= (str (fs/normalize (fs/path (:directory entry))))
                         (str (fs/normalize (fs/path project-dir))))
                  name))
              projects)
        (some (fn [[name entry]]
                (let [src-root (source-project-root project-dir)
                      reg-dir (str (fs/normalize (fs/path (:directory entry))))]
                  (when (and src-root
                             (= (str (fs/normalize (fs/path src-root)))
                                reg-dir))
                    name)))
              projects)
        (str (fs/file-name (fs/path project-dir))))))

(defn- main-checkout?
  "True when project-dir is the registered project's canonical directory
   (i.e. not a worktree)."
  [project-dir project-name]
  (let [projects (config/read-projects)
        reg-dir (some-> (get projects project-name) :directory)]
    (and reg-dir
         (= (str (fs/normalize (fs/path project-dir)))
            (str (fs/normalize (fs/path reg-dir)))))))

(defn resolve-instance-id
  "Resolve a project-dir to a unique instance identifier. For the main
   checkout this equals the project-name; for a worktree it is
   `<project-name>--<leaf-of-project-dir>` so per-worktree state is isolated."
  [project-dir]
  (let [project-name (resolve-project-name project-dir)]
    (if (main-checkout? project-dir project-name)
      project-name
      (str project-name "--" (fs/file-name (fs/path project-dir))))))

(defn- print-session-summary [_session-data ctx]
  (println "nido session:")
  (println "  project:" (get-in ctx [:session :project-dir]))
  (println "  project-name:" (get-in ctx [:session :project-name]))
  (println "  instance-id:" (get-in ctx [:session :instance-id]))
  (when-let [url (get-in ctx [:app :url])]
    (println "  url:" url))
  (when-let [p (get-in ctx [:app :port])]
    (println "  app port:" p))
  (when-let [p (get-in ctx [:repl :port])]
    (println "  nrepl port:" p))
  (when-let [p (get-in ctx [:pg :port])]
    (println "  pg port:" p))
  (println "  state file:" (state/session-state-file
                            (get-in ctx [:session :instance-id]))))

(defn- resolve-jvm-config
  "Merge nido-controlled JVM knobs from session-edn :defaults :jvm with
   per-invocation opts. Flat CLI-friendly keys (:jvm-heap-max,
   :jvm-aliases, :jvm-extra-opts) are supported alongside a nested :jvm
   map in opts. Returns a map enriched with :aliases-joined and
   :extra-opts-joined so service-def templates can reference them
   directly."
  [defaults opts]
  (let [base (or (:jvm defaults) {})
        flat (cond-> {}
               (:jvm-heap-max opts)   (assoc :heap-max (:jvm-heap-max opts))
               (:jvm-aliases opts)    (assoc :aliases (:jvm-aliases opts))
               (:jvm-extra-opts opts) (assoc :extra-opts (:jvm-extra-opts opts)))]
    (ctx/prepare-jvm (merge base flat (:jvm opts)))))

(defn- pre-allocate-ports
  "Pre-allocate ports for services that need their port known by other
   services BEFORE they run (e.g. the :eval service's app port is
   referenced from the :config-file template, which runs first). Seeds
   `(keyword svc-name)` → {:port N} into the context."
  [services project-dir]
  (reduce (fn [acc svc-def]
            (if (and (= :eval (:type svc-def)) (:name svc-def))
              (let [[low high] (or (:port-range svc-def) [3100 5100])
                    pref (proc/deterministic-port project-dir low high)
                    port (proc/find-available-port pref (- high low))]
                (assoc acc (keyword (:name svc-def)) {:port port}))
              acc))
          {} services))

(defn filter-services
  "Filter a session.edn :services list by an allowlist. Allowlist is
   either :all (return everything) or a vector of allowed :type values."
  [services allowlist]
  (cond
    (= :all allowlist) (vec services)
    (vector? allowlist) (filterv #(contains? (set allowlist) (:type %)) services)
    :else (throw (ex-info "Invalid services allowlist" {:allowlist allowlist}))))

;; ---------------------------------------------------------------------------
;; Profile persistence — written at session-up so cleanup can read the
;; resolved profile without re-resolving from config (robust against
;; registry edits between up and destroy).
;; ---------------------------------------------------------------------------

(defn- profile-path [wt-path]
  (str (fs/path (state/instance-state-dir (resolve-instance-id wt-path)) "profile.edn")))

(defn write-profile-for-session!
  "Persist the resolved profile to the session's state dir. Called at the
   end of start-session! so cleanup paths can read the profile back
   without re-resolving (robust against registry edits between up and
   destroy)."
  [wt-path profile]
  (io/write-edn! (profile-path wt-path) profile))

(defn read-profile-for-session
  "Return the resolved profile persisted at session-up time. nil if absent
   (e.g. legacy sessions predating this feature)."
  [wt-path]
  (let [path (profile-path wt-path)]
    (when (fs/exists? path)
      (io/read-edn path))))

(defn- apply-pg-mode-override
  "If this session has a persisted PG mode override, apply it to the
   :postgresql service-def's :mode. Other services pass through untouched."
  [services instance-id]
  (if-let [ov (:mode (state/read-pg-mode-override instance-id))]
    (mapv (fn [svc] (if (= :postgresql (:type svc)) (assoc svc :mode ov) svc))
          services)
    services))

(defn- start-failure-report
  "The message logged when a service fails to start and the session rolls back.
   A :process service attaches the tail of its own log to the exception, and
   that is where the real cause lives — a classpath error, a port already
   bound. Append it instead of printing the bare ex-message and leaving the
   operator to go find ~/.nido/state/<instance>/logs/<service>.log by hand."
  [e started-count]
  (let [{:keys [log-tail log-path]} (ex-data e)
        headline (str "Session start failed: " (ex-message e)
                      " — rolling back " started-count " started service(s)")]
    (if (str/blank? log-tail)
      headline
      (str headline
           "\n  last output from the failed service:\n"
           (->> (str/split-lines (str/trim log-tail))
                (map (fn [line] (str "    " line)))
                (str/join "\n"))
           (when log-path (str "\n  full log: " log-path))))))

(defn- start-services! [project-dir project-name instance-id session-edn opts]
  (core/log-step (str "Starting session " instance-id " (" project-dir ")"))
  (let [profile  (:profile opts)
        allow    (or (:services profile) :all)
        services (apply-pg-mode-override
                  (filter-services (:services session-edn) allow)
                  instance-id)
        pre-allocated (pre-allocate-ports services project-dir)
        jvm-cfg (resolve-jvm-config (:defaults session-edn) opts)
        session-name (:session-name opts)
        init-ctx (merge pre-allocated
                        {:session (cond-> {:project-dir project-dir
                                           :project-name project-name
                                           :instance-id instance-id
                                           :jvm jvm-cfg}
                                    session-name (assoc :name session-name))})
        ;; Run setup steps
        _ (doseq [step (:setup session-edn)]
            (run-setup-step! step project-dir))
        ;; Start services in order. `started` accumulates
        ;; {:resolved-def ... :state ...} in start order so we can roll
        ;; back (stop in reverse) if any later service throws. Without
        ;; this, a mid-init failure would leave PG/JVM/etc. orphaned
        ;; because session state isn't persisted until after the reduce.
        started (atom [])
        result (try
                 (reduce
                  (fn [{:keys [ctx service-states]} svc-def]
                    (let [svc-name (:name svc-def)
                          resolved-def (ctx/substitute ctx svc-def)
                          {:keys [state context]} (service/start-service! resolved-def ctx opts)
                          new-ctx (ctx/merge-context ctx (keyword svc-name) context)]
                      (swap! started conj {:resolved-def resolved-def :state state})
                      {:ctx new-ctx
                       :service-states (assoc service-states svc-name state)}))
                  {:ctx init-ctx :service-states {}}
                  services)
                 (catch Exception e
                   (core/log-step (start-failure-report e (count @started)))
                   (doseq [{:keys [resolved-def state]} (reverse @started)]
                     (let [svc-name (:name resolved-def)]
                       (try
                         (core/log-step (str "Rolling back " (name svc-name) "..."))
                         (service/stop-service! resolved-def state)
                         (catch Exception e2
                           (println (str "warning: rollback of " (name svc-name)
                                         " threw: " (ex-message e2)))))))
                   ;; No session state was persisted; re-throw so the
                   ;; caller sees the original failure.
                   (throw e)))
        final-ctx (:ctx result)
        service-states (:service-states result)
        session-data (cond-> {:project-dir project-dir
                              :project-name project-name
                              :instance-id instance-id
                              :service-defs (:services session-edn)
                              :service-states service-states
                              :context final-ctx
                              :created-at (core/now-iso)}
                       session-name (assoc :name session-name))]
    ;; Write session state
    (state/write-session! instance-id session-data)
    ;; Upsert registry for backward compat
    (let [registry-entry (merge
                          {:project-dir project-dir
                           :project-name project-name
                           :instance-id instance-id
                           :url (get-in final-ctx [:app :url])
                           :app-port (get-in final-ctx [:app :port])
                           :nrepl-port (get-in final-ctx [:repl :port])
                           :repl-pid (get-in final-ctx [:repl :pid])
                           :created-at (core/now-iso)}
                          (when-let [p (get-in final-ctx [:pg :port])]
                            {:pg-port p}))]
      (state/upsert-registry! project-dir registry-entry))
    ;; The session briefing is now injected at launch (--append-system-prompt),
    ;; so the worktree stays pure project code: remove any prior nido-managed
    ;; CLAUDE.md instead of writing one. (Session-home dissolution, step 2.)
    (try (agent-guidance/remove! project-dir)
         (catch Exception e
           (core/log-step (str "warning: failed to remove agent CLAUDE.md: "
                               (ex-message e)))))
    ;; Persist the resolved profile so destroy! / reset! can read it back
    ;; without re-resolving (robust against registry edits between up and
    ;; destroy). Must precede launcher/write-artifacts!, which reads
    ;; profile.edn from disk to decide between the full/lite briefing —
    ;; reversed order misclassifies every fresh session as lite.
    (try (write-profile-for-session! project-dir (:profile opts))
         (catch Exception e
           (core/log-step (str "warning: failed to write profile snapshot: "
                               (ex-message e)))))
    ;; Thread :owned-by-run from opts into the in-memory session-edn the
    ;; launcher sees so it can decorate Run-owned sessions (resume shim +
    ;; run-link). Project session.edn on disk is shared and stays untouched.
    (try (launcher/write-artifacts! final-ctx
                                    (cond-> session-edn
                                      (:owned-by-run opts)
                                      (assoc :owned-by-run (:owned-by-run opts))))
         (catch Exception e
           (core/log-step (str "warning: failed to write launcher artifacts: "
                               (ex-message e)))))
    (print-session-summary session-data final-ctx)
    session-data))

(defn- session-alive?
  "Is any of this session's PROCESSES still running? Deliberately narrow: it
   decides only whether re-provisioning would duplicate a live process. The
   :eval service owns no process at all (it is mount state inside the repl
   JVM), so it is invisible here by construction — `reconcile-app!` covers it."
  [session]
  (let [svc-states (:service-states session)]
    (some (fn [[_ s]]
            (when-let [pid (:pid s)]
              (proc/process-alive? pid)))
          svc-states)))

(defn reconcile-app!
  "Re-start any :eval service of an already-running session whose app is no
   longer listening.

   Without this, a session whose app died inside a living JVM — a failed
   auto-reload, a stray `development/stop` — was unrecoverable: `session-alive?`
   saw the repl pid and short-circuited, so the start-form was never evaluated
   by `up` nor by the dashboard's retry, which then blamed a timeout that had
   not happened.

   `start-app!` is itself idempotent (an open port returns immediately), so the
   healthy case is a no-op. Exceptions propagate, matching `start-service! :eval`
   on a fresh boot, so the caller reports the real cause. A service with no saved
   state was never started for this session (a profile that excludes the app), so
   it is skipped rather than provisioned here."
  [existing]
  (doseq [svc-def (:service-defs existing)
          :when   (= :eval (:type svc-def))
          :let    [saved-state (get (:service-states existing) (:name svc-def))]
          :when   saved-state]
    (eval-svc/start-app! svc-def saved-state (:context existing))))

(defn start-session!
  "Start a session for a project directory using its session.edn definition.
   opts: {:jvm-heap-max string, :jvm-aliases [kw], :jvm-extra-opts [str],
          :profile <resolved-profile-map>, ...}
   :profile defaults to {:services :all :worktree {:strategy :git-worktree}}
   so callers that don't pass it preserve prior behavior unchanged."
  [project-dir {:keys [profile] :as opts}]
  (let [opts        (assoc opts :profile (or profile {:services :all
                                                      :worktree {:strategy :git-worktree}}))
        project-name (resolve-project-name project-dir)
        instance-id  (resolve-instance-id project-dir)
        session-edn  (load-session-edn project-name)]
    ;; Check for existing running session
    (if-let [existing (state/read-session instance-id)]
      (if (session-alive? existing)
        (do (println "Session already running for" instance-id)
            ;; Refresh launcher artifacts on the idempotent path. Covers two
            ;; cases: (a) sessions that pre-date the launcher feature have
            ;; no artifacts yet; (b) artifacts could have been deleted out
            ;; of band. Same content as a fresh start writes, derived from
            ;; the persisted session context.
            (try (launcher/write-artifacts! (:context existing)
                                            (cond-> session-edn
                                              (:owned-by-run opts)
                                              (assoc :owned-by-run (:owned-by-run opts))))
                 (catch Exception e
                   (core/log-step (str "warning: refresh launcher artifacts: "
                                       (ex-message e)))))
            ;; ...then converge the one piece of the session that "already
            ;; running" says nothing about: the app inside the live JVM.
            (reconcile-app! existing)
            nil)
        (start-services! project-dir project-name instance-id session-edn opts))
      (start-services! project-dir project-name instance-id session-edn opts))))

;; ---------------------------------------------------------------------------
;; Legacy session detection
;; ---------------------------------------------------------------------------

(defn- read-legacy-session [project-dir]
  (let [legacy-path (str (fs/path project-dir ".codex" "session.edn"))]
    (io/read-edn legacy-path)))

(defn stop-session!
  "Stop a session for a project directory."
  [project-dir]
  (let [instance-id (resolve-instance-id project-dir)
        session (or (state/read-session instance-id)
                    ;; Legacy fallback
                    (when-let [legacy (read-legacy-session project-dir)]
                      (core/log-step "Found legacy session format, converting...")
                      legacy))]
    (if-not session
      (println "No session to stop for" project-dir)
      (let [service-defs (or (:service-defs session) [])
            service-states (or (:service-states session) {})]
        (core/log-step (str "Stopping session " instance-id))
        ;; Stop services in reverse order
        (doseq [svc-def (reverse service-defs)]
          (let [svc-name (:name svc-def)
                saved-state (get service-states svc-name)]
            (when saved-state
              (try
                (core/log-step (str "Stopping " (name svc-name) "..."))
                (service/stop-service! svc-def saved-state)
                (catch Exception e
                  (println (str "warning: error stopping " (name svc-name) ": "
                                (ex-message e))))))))
        ;; Clean up state
        (state/delete-session! instance-id)
        (state/remove-from-registry! project-dir)
        ;; Clean up legacy session file if it exists
        (let [legacy-file (str (fs/path project-dir ".codex" "session.edn"))]
          (fs/delete-if-exists legacy-file))
        ;; Clean up .nrepl-port
        (fs/delete-if-exists (str (fs/path project-dir ".nrepl-port")))
        ;; Remove nido-managed CLAUDE.md
        (try (agent-guidance/remove! project-dir)
             (catch Exception e
               (core/log-step (str "warning: failed to remove agent CLAUDE.md: "
                                   (ex-message e)))))
        (try (launcher/remove-artifacts! (:project-name session) (:name session))
             (catch Exception e
               (core/log-step (str "warning: failed to remove launcher artifacts: "
                                   (ex-message e)))))
        (println "Stopped session" instance-id)))))

(defn session-status
  "Show status for a project session."
  [project-dir]
  (let [project-name (resolve-project-name project-dir)
        instance-id (resolve-instance-id project-dir)
        session (or (state/read-session instance-id)
                    (read-legacy-session project-dir))]
    (if-not session
      (println "No session found for" project-dir)
      (let [service-defs (or (:service-defs session) [])
            service-states (or (:service-states session) {})]
        (println "nido session:")
        (println "  project:" project-dir)
        (println "  project-name:" project-name)
        (println "  instance-id:" instance-id)
        (doseq [svc-def service-defs]
          (let [svc-name (:name svc-def)
                saved-state (get service-states svc-name)]
            (when saved-state
              (let [status (try
                             (service/service-status svc-def saved-state)
                             (catch Exception e
                               {:alive? false :error (ex-message e)}))]
                (println (str "  " (name svc-name) ":"))
                (doseq [[k v] (sort-by key status)]
                  (println (str "    " (name k) ": " v)))))))
        (println "  state file:" (state/session-state-file instance-id))))))

(defn list-sessions
  "List all sessions tracked by the nido registry."
  []
  (let [registry (state/read-registry)]
    (if (seq registry)
      (doseq [[project-dir entry] (sort-by key registry)]
        (let [repl-pid (:repl-pid entry)
              app-port (:app-port entry)
              pg-port (:pg-port entry)]
          (println "-")
          (println "  project:" project-dir)
          (when-let [name (:project-name entry)]
            (println "  name:" name))
          (println "  url:" (:url entry))
          (println "  app port:" app-port)
          (println "  nrepl port:" (:nrepl-port entry))
          (println "  repl pid:" repl-pid)
          (println "  repl alive:" (and (pos-int? repl-pid) (proc/process-alive? repl-pid)))
          (println "  app listening:" (and (pos-int? app-port) (proc/tcp-open? app-port)))
          (when pg-port
            (println "  pg port:" pg-port)
            (println "  pg listening:" (proc/tcp-open? pg-port)))))
      (println "No sessions tracked."))))
