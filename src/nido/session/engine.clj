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
   [nido.session.context :as ctx]
   [nido.session.service :as service]
   [nido.session.state :as state]
   ;; Load service implementations
   nido.session.services.postgresql
   nido.session.services.process
   nido.session.services.eval
   nido.session.services.config-file))

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
            :when (not (fs/exists? target-path))]
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
                 (not (fs/exists? target-path)))
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
;; Project name resolution
;; ---------------------------------------------------------------------------

(defn- resolve-project-name [project-dir]
  (let [projects (config/read-projects)]
    (or (some (fn [[name entry]]
                (when (= (str (fs/normalize (fs/path (:directory entry))))
                         (str (fs/normalize (fs/path project-dir))))
                  name))
              projects)
        ;; For worktrees, check if project-dir is under a registered project's directory
        (some (fn [[name entry]]
                (let [common-root (git-common-project-root project-dir)
                      reg-dir (str (fs/normalize (fs/path (:directory entry))))]
                  (when (and common-root
                             (= (str (fs/normalize (fs/path common-root)))
                                reg-dir))
                    name)))
              projects)
        ;; Fallback: use the last path component
        (str (fs/file-name (fs/path project-dir))))))

;; ---------------------------------------------------------------------------
;; Session lifecycle
;; ---------------------------------------------------------------------------

(defn- load-session-edn [project-name]
  (let [path (str (fs/path (core/nido-home) "projects" project-name "session.edn"))]
    (when-not (fs/exists? path)
      (throw (ex-info (str "No session.edn found for project '" project-name "'")
                      {:path path
                       :hint "Create a session.edn in ~/.nido/projects/<name>/session.edn"})))
    (io/read-edn path)))

(defn- print-session-summary [session-data ctx]
  (println "nido session:")
  (println "  project:" (get-in ctx [:session :project-dir]))
  (println "  project-name:" (get-in ctx [:session :project-name]))
  (when-let [url (get-in ctx [:app :url])]
    (println "  url:" url))
  (when-let [p (get-in ctx [:app :port])]
    (println "  app port:" p))
  (when-let [p (get-in ctx [:repl :port])]
    (println "  nrepl port:" p))
  (when-let [p (get-in ctx [:pg :port])]
    (println "  pg port:" p))
  (println "  state file:" (state/session-state-file
                            (get-in ctx [:session :project-name]))))

(defn- start-services! [project-dir project-name session-edn opts]
  (core/log-step (str "Starting session for " project-name " (" project-dir ")"))
  (let [init-ctx {:session {:project-dir project-dir
                            :project-name project-name}}
        ;; Run setup steps
        _ (doseq [step (:setup session-edn)]
            (run-setup-step! step project-dir))
        ;; Start services in order
        services (:services session-edn)
        result (reduce
                (fn [{:keys [ctx service-states]} svc-def]
                  (let [svc-name (:name svc-def)
                        resolved-def (ctx/substitute ctx svc-def)
                        skip? (and (= (:type svc-def) :postgresql)
                                   (:shared-pg? opts))]
                    (if skip?
                      (do
                        (core/log-step (str "Skipping " (name svc-name) " (shared-pg? true)"))
                        {:ctx ctx :service-states service-states})
                      (let [{:keys [state context]} (service/start-service! resolved-def ctx opts)
                            new-ctx (ctx/merge-context ctx (keyword svc-name) context)]
                        {:ctx new-ctx
                         :service-states (assoc service-states svc-name state)}))))
                {:ctx init-ctx :service-states {}}
                services)
        final-ctx (:ctx result)
        service-states (:service-states result)
        session-data {:project-dir project-dir
                      :project-name project-name
                      :service-defs (:services session-edn)
                      :service-states service-states
                      :context final-ctx
                      :created-at (core/now-iso)
                      :shared-pg? (boolean (:shared-pg? opts))}]
    ;; Write session state
    (state/write-session! project-name session-data)
    ;; Upsert registry for backward compat
    (let [registry-entry (merge
                          {:project-dir project-dir
                           :project-name project-name
                           :url (get-in final-ctx [:app :url])
                           :app-port (get-in final-ctx [:app :port])
                           :nrepl-port (get-in final-ctx [:repl :port])
                           :repl-pid (get-in final-ctx [:repl :pid])
                           :created-at (core/now-iso)}
                          (when-let [p (get-in final-ctx [:pg :port])]
                            {:pg-port p}))]
      (state/upsert-registry! project-dir registry-entry))
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
   opts: {:shared-pg? bool, ...}"
  [project-dir opts]
  (let [project-name (resolve-project-name project-dir)
        session-edn (load-session-edn project-name)]
    ;; Check for existing running session
    (if-let [existing (state/read-session project-name)]
      (if (session-alive? existing)
        (do (println "Session already running for" project-name) nil)
        (start-services! project-dir project-name session-edn opts))
      (start-services! project-dir project-name session-edn opts))))

;; ---------------------------------------------------------------------------
;; Legacy session detection
;; ---------------------------------------------------------------------------

(defn- read-legacy-session [project-dir]
  (let [legacy-path (str (fs/path project-dir ".codex" "session.edn"))]
    (io/read-edn legacy-path)))

(defn stop-session!
  "Stop a session for a project directory."
  [project-dir]
  (let [project-name (resolve-project-name project-dir)
        session (or (state/read-session project-name)
                    ;; Legacy fallback
                    (when-let [legacy (read-legacy-session project-dir)]
                      (core/log-step "Found legacy session format, converting...")
                      legacy))]
    (if-not session
      (println "No session to stop for" project-dir)
      (let [service-defs (or (:service-defs session) [])
            service-states (or (:service-states session) {})]
        (core/log-step (str "Stopping session for " project-dir))
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
        (state/delete-session! project-name)
        (state/remove-from-registry! project-dir)
        ;; Clean up legacy session file if it exists
        (let [legacy-file (str (fs/path project-dir ".codex" "session.edn"))]
          (fs/delete-if-exists legacy-file))
        ;; Clean up .nrepl-port
        (fs/delete-if-exists (str (fs/path project-dir ".nrepl-port")))
        (println "Stopped session for" project-dir)))))

(defn session-status
  "Show status for a project session."
  [project-dir]
  (let [project-name (resolve-project-name project-dir)
        session (or (state/read-session project-name)
                    (read-legacy-session project-dir))]
    (if-not session
      (println "No session found for" project-dir)
      (let [service-defs (or (:service-defs session) [])
            service-states (or (:service-states session) {})]
        (println "nido session:")
        (println "  project:" project-dir)
        (println "  project-name:" project-name)
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
        (println "  state file:" (state/session-state-file project-name))))))

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
