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
   [nido.session.service :as service]
   ;; Load service implementations (must be loaded before state for the
   ;; defmethods to register; state itself has no transitive deps on them).
   nido.session.services.config-file
   nido.session.services.eval
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
      (some-> (:out result) str/trim fs/parent str))))

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
  (let [source-project-root (git-common-project-root project-dir)
        local-roots (local-root-paths project-dir)
        linked (atom [])
        missing (atom [])]
    (doseq [local-root local-roots
            :let [target-path (str (fs/normalize (fs/path project-dir local-root)))]
            :when (not (path-present? target-path))]
      (if source-project-root
        (let [source-path (str (fs/normalize (fs/path source-project-root local-root)))]
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
        source-root (git-common-project-root project-dir)]
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

(defn load-session-edn [project-name]
  (let [path (str (fs/path (core/nido-home) "projects" project-name "session.edn"))]
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
                (let [common-root (git-common-project-root project-dir)
                      reg-dir (str (fs/normalize (fs/path (:directory entry))))]
                  (when (and common-root
                             (= (str (fs/normalize (fs/path common-root)))
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

(defn- resolve-pg-mode
  "Normalize PG mode from opts + session-edn defaults into {:shared|:isolated}.
   Order of precedence: CLI flag > defaults > :shared.
   CLI spellings (any of):
     :pg-mode :shared | :isolated
     :isolated-pg? true     (sugar for :pg-mode :isolated)
     :shared-pg?   true     (sugar for :pg-mode :shared — legacy)"
  [session-edn opts]
  (cond
    (keyword? (:pg-mode opts)) (:pg-mode opts)
    (true? (:isolated-pg? opts)) :isolated
    (true? (:shared-pg? opts)) :shared
    (keyword? (get-in session-edn [:defaults :pg-mode])) (get-in session-edn [:defaults :pg-mode])
    :else :shared))

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

(defn- start-services! [project-dir project-name instance-id session-edn opts]
  (core/log-step (str "Starting session " instance-id " (" project-dir ")"))
  (let [pre-allocated (pre-allocate-ports (:services session-edn) project-dir)
        jvm-cfg (resolve-jvm-config (:defaults session-edn) opts)
        pg-mode (resolve-pg-mode session-edn opts)
        ;; Normalize pg-mode downstream: services dispatch on (:pg-mode opts).
        opts+ (assoc opts :pg-mode pg-mode)
        init-ctx (merge pre-allocated
                        {:session {:project-dir project-dir
                                   :project-name project-name
                                   :instance-id instance-id
                                   :jvm jvm-cfg
                                   :pg-mode pg-mode}})
        ;; Run setup steps
        _ (doseq [step (:setup session-edn)]
            (run-setup-step! step project-dir))
        ;; Start services in order. `started` accumulates
        ;; {:resolved-def ... :state ...} in start order so we can roll
        ;; back (stop in reverse) if any later service throws. Without
        ;; this, a mid-init failure would leave PG/JVM/etc. orphaned
        ;; because session state isn't persisted until after the reduce.
        services (:services session-edn)
        started (atom [])
        result (try
                 (reduce
                  (fn [{:keys [ctx service-states]} svc-def]
                    (let [svc-name (:name svc-def)
                          resolved-def (ctx/substitute ctx svc-def)
                          {:keys [state context]} (service/start-service! resolved-def ctx opts+)
                          new-ctx (ctx/merge-context ctx (keyword svc-name) context)]
                      (swap! started conj {:resolved-def resolved-def :state state})
                      {:ctx new-ctx
                       :service-states (assoc service-states svc-name state)}))
                  {:ctx init-ctx :service-states {}}
                  services)
                 (catch Exception e
                   (let [n (count @started)]
                     (core/log-step
                      (str "Session start failed: " (ex-message e)
                           " — rolling back " n " started service(s)")))
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
        session-data {:project-dir project-dir
                      :project-name project-name
                      :instance-id instance-id
                      :service-defs (:services session-edn)
                      :service-states service-states
                      :context final-ctx
                      :created-at (core/now-iso)
                      :pg-mode pg-mode}]
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
                           :pg-mode pg-mode
                           :created-at (core/now-iso)}
                          (when-let [p (get-in final-ctx [:pg :port])]
                            {:pg-port p}))]
      (state/upsert-registry! project-dir registry-entry))
    (try (agent-guidance/write! final-ctx)
         (catch Exception e
           (core/log-step (str "warning: failed to write agent CLAUDE.md: "
                               (ex-message e)))))
    (print-session-summary session-data final-ctx)
    session-data))

(defn- session-alive? [session]
  (let [svc-states (:service-states session)]
    (some (fn [[_ s]]
            (when-let [pid (:pid s)]
              (proc/process-alive? pid)))
          svc-states)))

(defn start-session!
  "Start a session for a project directory using its session.edn definition.
   opts: {:pg-mode :shared|:isolated (default :shared),
          :isolated-pg? bool  (sugar for :pg-mode :isolated),
          :jvm-heap-max string, :jvm-aliases [kw], :jvm-extra-opts [str],
          ...}"
  [project-dir opts]
  (let [project-name (resolve-project-name project-dir)
        instance-id  (resolve-instance-id project-dir)
        session-edn  (load-session-edn project-name)]
    ;; Check for existing running session
    (if-let [existing (state/read-session instance-id)]
      (if (session-alive? existing)
        (do (println "Session already running for" instance-id) nil)
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
