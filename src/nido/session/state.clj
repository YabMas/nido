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

(defn session-mcp-path
  "Path to a session's rendered postgres MCP config (a launch input passed via
   claude's --mcp-config). Lives under the durable instance-state dir, not the
   ephemeral session home."
  [instance-id]
  (str (fs/path (instance-state-dir instance-id) "mcp.json")))

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
;; Session-home — human-readable per-session dir users cd into.
;; ~/.nido/sessions/<project>/<session>/ holds .mcp.json, CLAUDE.md, AGENTS.md,
;; and a `worktree` symlink. Distinct from instance-state-dir (opaque hash,
;; owned by nido). Same session is reachable via both paths until the migration
;; off instance-state-dir is complete.
;; ---------------------------------------------------------------------------

(defn sessions-root
  "~/.nido/sessions/"
  []
  (str (fs/path (core/nido-home) "sessions")))

(defn session-home-dir
  "~/.nido/sessions/<project>/<session>/"
  [project-name session-name]
  (str (fs/path (sessions-root) project-name session-name)))

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

(defn read-template-meta [project-name]
  (io/read-edn (template-meta-file project-name)))

(defn write-template-meta! [project-name data]
  (io/write-edn! (template-meta-file project-name) data))

;; ---------------------------------------------------------------------------
;; Shared cluster — one long-lived running Postgres per project, shared by all
;; sessions in :shared mode. Lives under ~/.nido/shared/<project-name>/.
;; ---------------------------------------------------------------------------

(defn shared-dir
  "Root shared-cluster directory: ~/.nido/shared/"
  []
  (str (fs/path (core/nido-home) "shared")))

(defn project-shared-dir
  "Per-project shared dir: ~/.nido/shared/<project-name>/"
  [project-name]
  (str (fs/path (shared-dir) project-name)))

(defn shared-pg-data-dir
  "Shared cluster PGDATA: ~/.nido/shared/<project-name>/pg-data/"
  [project-name]
  (str (fs/path (project-shared-dir project-name) "pg-data")))

(defn shared-log-file
  "Shared cluster log: ~/.nido/shared/<project-name>/pg.log"
  [project-name]
  (str (fs/path (project-shared-dir project-name) "pg.log")))

(defn shared-meta-file
  "Shared cluster metadata: ~/.nido/shared/<project-name>/shared.edn"
  [project-name]
  (str (fs/path (project-shared-dir project-name) "shared.edn")))

(defn shared-lock-file
  "Lock file guarding shared-cluster creation: ~/.nido/shared/<project-name>/shared.lock"
  [project-name]
  (str (fs/path (project-shared-dir project-name) "shared.lock")))

(defn read-shared-meta [project-name]
  (io/read-edn (shared-meta-file project-name)))

(defn write-shared-meta! [project-name data]
  (io/write-edn! (shared-meta-file project-name) data))

;; ---------------------------------------------------------------------------
;; Per-session PG mode override — lets a single session opt into :isolated (a
;; private PGDATA clone) while the project default stays :shared. Lives at
;; ~/.nido/state/<instance-id>/pg-mode-override.edn and survives down/up.
;; ---------------------------------------------------------------------------

(defn pg-mode-override-file
  "Per-session PG mode override file: ~/.nido/state/<instance-id>/pg-mode-override.edn"
  [instance-id]
  (str (fs/path (instance-state-dir instance-id) "pg-mode-override.edn")))

(defn read-pg-mode-override
  "Return the override map {:mode <kw>} for a session, or nil if none set."
  [instance-id]
  (let [f (pg-mode-override-file instance-id)]
    (when (fs/exists? f) (io/read-edn f))))

(defn write-pg-mode-override!
  "Persist {:mode <kw>} as this session's PG mode override."
  [instance-id mode]
  (io/write-edn! (pg-mode-override-file instance-id) {:mode mode}))

(defn clear-pg-mode-override!
  "Remove a session's PG mode override (if any)."
  [instance-id]
  (fs/delete-if-exists (pg-mode-override-file instance-id)))

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
