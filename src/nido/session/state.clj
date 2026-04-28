(ns nido.session.state
  (:require
   [babashka.fs :as fs]
   [nido.core :as core]
   [nido.io :as io]))

;; ---------------------------------------------------------------------------
;; Path helpers — all session state lives under ~/.nido/state/<instance-id>/
;;
;; instance-id identifies a single running session. For the main checkout it
;; equals the project name (e.g. "brian"); for a worktree-based session it is
;; "<project-name>--<wt-name>" (e.g. "brian--feat-auth"). This keeps state
;; isolated per running session while keeping config (session.edn, templates)
;; shared per project.
;; ---------------------------------------------------------------------------

(defn state-dir
  "Root state directory: ~/.nido/state/"
  []
  (str (fs/path (core/nido-home) "state")))

(defn instance-state-dir
  "Per-instance state directory: ~/.nido/state/<instance-id>/"
  [instance-id]
  (str (fs/path (state-dir) instance-id)))

(defn session-state-file
  "Session state file: ~/.nido/state/<instance-id>/session.edn"
  [instance-id]
  (str (fs/path (instance-state-dir instance-id) "session.edn")))

(defn log-dir
  "Log directory: ~/.nido/state/<instance-id>/logs/"
  [instance-id]
  (str (fs/path (instance-state-dir instance-id) "logs")))

(defn log-file
  "Log file for a named service: ~/.nido/state/<instance-id>/logs/<name>.log"
  [instance-id service-name]
  (str (fs/path (log-dir instance-id) (str (name service-name) ".log"))))

(defn pg-data-dir
  "PostgreSQL data directory: ~/.nido/state/<instance-id>/pg-data/"
  [instance-id]
  (str (fs/path (instance-state-dir instance-id) "pg-data")))

;; ---------------------------------------------------------------------------
;; Template paths — long-lived, per-project, source for APFS clones
;; ---------------------------------------------------------------------------

(defn templates-dir
  "Root templates directory: ~/.nido/templates/"
  []
  (str (fs/path (core/nido-home) "templates")))

(defn project-template-dir
  "Per-project template directory: ~/.nido/templates/<project-name>/"
  [project-name]
  (str (fs/path (templates-dir) project-name)))

(defn template-pg-data-dir
  "Template PostgreSQL data directory: ~/.nido/templates/<project-name>/pg-data/"
  [project-name]
  (str (fs/path (project-template-dir project-name) "pg-data")))

(defn template-meta-file
  "Template metadata file: ~/.nido/templates/<project-name>/template.edn"
  [project-name]
  (str (fs/path (project-template-dir project-name) "template.edn")))

(defn template-log-file
  "Template log file: ~/.nido/templates/<project-name>/pg.log"
  [project-name]
  (str (fs/path (project-template-dir project-name) "pg.log")))

;; ---------------------------------------------------------------------------
;; Workspace (shared-PG) paths — one long-lived cluster per project, shared
;; across all sessions started with :pg-mode :shared (the default).
;; ---------------------------------------------------------------------------

(defn workspace-dir
  "Root workspaces directory: ~/.nido/workspaces/"
  []
  (str (fs/path (core/nido-home) "workspaces")))

(defn project-workspace-dir
  "Per-project workspace directory: ~/.nido/workspaces/<project-name>/"
  [project-name]
  (str (fs/path (workspace-dir) project-name)))

(defn workspace-pg-data-dir
  "Workspace PostgreSQL data directory:
   ~/.nido/workspaces/<project-name>/pg-data/"
  [project-name]
  (str (fs/path (project-workspace-dir project-name) "pg-data")))

(defn workspace-pg-log-file
  "Workspace PostgreSQL log file:
   ~/.nido/workspaces/<project-name>/pg.log"
  [project-name]
  (str (fs/path (project-workspace-dir project-name) "pg.log")))

(defn read-template-meta [project-name]
  (io/read-edn (template-meta-file project-name)))

(defn write-template-meta! [project-name data]
  (io/write-edn! (template-meta-file project-name) data))

;; ---------------------------------------------------------------------------
;; Session read/write
;; ---------------------------------------------------------------------------

(defn read-session [instance-id]
  (io/read-edn (session-state-file instance-id)))

(defn write-session! [instance-id data]
  (io/write-edn! (session-state-file instance-id) data))

(defn delete-session! [instance-id]
  (fs/delete-if-exists (session-state-file instance-id)))

;; ---------------------------------------------------------------------------
;; Registry — global session index at ~/.nido/state/sessions.edn
;; ---------------------------------------------------------------------------

(def ^:private registry-file-path
  (delay (str (fs/path (state-dir) "sessions.edn"))))

(defn- legacy-registry-paths []
  (let [codex-home (or (System/getenv "CODEX_HOME")
                       (str (System/getProperty "user.home") "/.codex"))]
    [(str (fs/path codex-home "nido" "sessions.edn"))
     (str (fs/path codex-home "agent-cockpit" "sessions.edn"))]))

(defn read-registry []
  (let [legacy (reduce (fn [acc path]
                         (merge acc (or (io/read-edn path) {})))
                       {}
                       (legacy-registry-paths))]
    (merge legacy (or (io/read-edn @registry-file-path) {}))))

(defn write-registry! [registry]
  (io/write-edn! @registry-file-path registry))

(defn upsert-registry! [project-dir entry]
  (write-registry! (assoc (read-registry) project-dir entry)))

(defn remove-from-registry! [project-dir]
  (write-registry! (dissoc (read-registry) project-dir)))

;; ---------------------------------------------------------------------------
;; Workspace PG attach counting — registry entries carry :pg-mode, set by the
;; :postgresql service. :shared entries are "attached" to the workspace PG;
;; workspace:pg:stop refuses while any exist.
;; ---------------------------------------------------------------------------

(defn attached-sessions
  "Return the sequence of registry entries currently attached to the
   workspace PG for a given project. An entry is attached iff its
   :pg-mode is :shared and its :project-name matches."
  [project-name]
  (->> (read-registry)
       vals
       (filter (fn [entry]
                 (and (= :shared (:pg-mode entry))
                      (= project-name (:project-name entry)))))))
