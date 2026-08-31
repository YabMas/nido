(ns nido.session.state
  (:require
   [babashka.fs :as fs]
   [nido.platform.core :as core]
   [nido.platform.io :as io]))

;; ---------------------------------------------------------------------------
;; Path helpers — all session state lives under ~/.nido/state/<instance-id>/
;;
;; instance-id identifies a single running session. For the main checkout it
;; equals the project name (e.g. "brian"); for a worktree-based session it is
;; "<project-name>--<wt-name>" (e.g. "brian--feat-auth"). This keeps state
;; isolated per running session while keeping config (session.edn, templates)
;; shared per project.
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat] :Path]}
  state-dir
  "Root state directory: ~/.nido/state/"
  []
  (str (fs/path (core/nido-home) "state")))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :Path]}
  instance-state-dir
  "Per-instance state directory: ~/.nido/state/<instance-id>/"
  [instance-id]
  (str (fs/path (state-dir) instance-id)))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :Path]}
  session-mcp-path
  "Path to a session's rendered postgres MCP config (a launch input passed via
   claude's --mcp-config). Lives under the durable instance-state dir, not the
   ephemeral session home."
  [instance-id]
  (str (fs/path (instance-state-dir instance-id) "mcp.json")))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :Path]}
  session-state-file
  "Session state file: ~/.nido/state/<instance-id>/session.edn"
  [instance-id]
  (str (fs/path (instance-state-dir instance-id) "session.edn")))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :Path]}
  log-dir
  "Log directory: ~/.nido/state/<instance-id>/logs/"
  [instance-id]
  (str (fs/path (instance-state-dir instance-id) "logs")))

(defn ^{:malli/schema [:=> [:cat :InstanceId :any] :Path]}
  log-file
  "Log file for a named service: ~/.nido/state/<instance-id>/logs/<name>.log"
  [instance-id service-name]
  (str (fs/path (log-dir instance-id) (str (name service-name) ".log"))))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :Path]}
  pg-data-dir
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

(defn ^{:malli/schema [:=> [:cat] :Path]}
  sessions-root
  "~/.nido/sessions/"
  []
  (str (fs/path (core/nido-home) "sessions")))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :Path]}
  session-home-dir
  "~/.nido/sessions/<project>/<session>/"
  [project-name session-name]
  (str (fs/path (sessions-root) project-name session-name)))

;; ---------------------------------------------------------------------------
;; Template paths — long-lived, per-project, source for APFS clones
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat] :Path]}
  templates-dir
  "Root templates directory: ~/.nido/templates/"
  []
  (str (fs/path (core/nido-home) "templates")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  project-template-dir
  "Per-project template directory: ~/.nido/templates/<project-name>/"
  [project-name]
  (str (fs/path (templates-dir) project-name)))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  template-pg-data-dir
  "Template PostgreSQL data directory: ~/.nido/templates/<project-name>/pg-data/"
  [project-name]
  (str (fs/path (project-template-dir project-name) "pg-data")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  template-meta-file
  "Template metadata file: ~/.nido/templates/<project-name>/template.edn"
  [project-name]
  (str (fs/path (project-template-dir project-name) "template.edn")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  template-log-file
  "Template log file: ~/.nido/templates/<project-name>/pg.log"
  [project-name]
  (str (fs/path (project-template-dir project-name) "pg.log")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:maybe :map]]}
  read-template-meta [project-name]
  (io/read-edn (template-meta-file project-name)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :any]}
  write-template-meta! [project-name data]
  (io/write-edn! (template-meta-file project-name) data))

;; ---------------------------------------------------------------------------
;; Shared cluster — one long-lived running Postgres per project, shared by all
;; sessions in :shared mode. Lives under ~/.nido/shared/<project-name>/.
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat] :Path]}
  shared-dir
  "Root shared-cluster directory: ~/.nido/shared/"
  []
  (str (fs/path (core/nido-home) "shared")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  project-shared-dir
  "Per-project shared dir: ~/.nido/shared/<project-name>/"
  [project-name]
  (str (fs/path (shared-dir) project-name)))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  shared-pg-data-dir
  "Shared cluster PGDATA: ~/.nido/shared/<project-name>/pg-data/"
  [project-name]
  (str (fs/path (project-shared-dir project-name) "pg-data")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  shared-log-file
  "Shared cluster log: ~/.nido/shared/<project-name>/pg.log"
  [project-name]
  (str (fs/path (project-shared-dir project-name) "pg.log")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  shared-meta-file
  "Shared cluster metadata: ~/.nido/shared/<project-name>/shared.edn"
  [project-name]
  (str (fs/path (project-shared-dir project-name) "shared.edn")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :Path]}
  shared-lock-file
  "Lock file guarding shared-cluster creation: ~/.nido/shared/<project-name>/shared.lock"
  [project-name]
  (str (fs/path (project-shared-dir project-name) "shared.lock")))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:maybe :map]]}
  read-shared-meta [project-name]
  (io/read-edn (shared-meta-file project-name)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :any]}
  write-shared-meta! [project-name data]
  (io/write-edn! (shared-meta-file project-name) data))

;; ---------------------------------------------------------------------------
;; Per-session PG mode override — lets a single session opt into :isolated (a
;; private PGDATA clone) while the project default stays :shared. Lives at
;; ~/.nido/state/<instance-id>/pg-mode-override.edn and survives down/up.
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :InstanceId] :Path]}
  pg-mode-override-file
  "Per-session PG mode override file: ~/.nido/state/<instance-id>/pg-mode-override.edn"
  [instance-id]
  (str (fs/path (instance-state-dir instance-id) "pg-mode-override.edn")))

(defn ^{:malli/schema [:=> [:cat :InstanceId] [:maybe :map]]}
  read-pg-mode-override
  "Return the override map {:mode <kw>} for a session, or nil if none set."
  [instance-id]
  (let [f (pg-mode-override-file instance-id)]
    (when (fs/exists? f) (io/read-edn f))))

(defn ^{:malli/schema [:=> [:cat :InstanceId :keyword] :any]}
  write-pg-mode-override!
  "Persist {:mode <kw>} as this session's PG mode override."
  [instance-id mode]
  (io/write-edn! (pg-mode-override-file instance-id) {:mode mode}))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :any]}
  clear-pg-mode-override!
  "Remove a session's PG mode override (if any)."
  [instance-id]
  (fs/delete-if-exists (pg-mode-override-file instance-id)))

;; ---------------------------------------------------------------------------
;; Session read/write
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :InstanceId] [:maybe :map]]}
  read-session [instance-id]
  (io/read-edn (session-state-file instance-id)))

(defn ^{:malli/schema [:=> [:cat :InstanceId :map] :any]}
  write-session! [instance-id data]
  (io/write-edn! (session-state-file instance-id) data))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :any]}
  delete-session! [instance-id]
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

(defn- read-legacy-registry
  "The legacy registries merged in path order. Read separately from the
   canonical one because every mutation has to re-merge them UNDER the
   canonical value it was handed — see read-registry."
  []
  (reduce (fn [acc path] (merge acc (or (io/read-edn path) {})))
          {}
          (legacy-registry-paths)))

(defn ^{:malli/schema [:=> [:cat] :map]}
  read-registry []
  (merge (read-legacy-registry)
         (or (io/read-edn @registry-file-path) {})))

(defn- update-registry!
  "Apply `f` to the merged registry and write the result to the canonical file,
   as one locked operation.

   Every session verb in nido mutates this one file — up, down, destroy,
   reclaim, the daemon adopting an orphan — so it is the single most contended
   piece of state here, and the read has to happen inside the lock rather than
   in the caller. remove-many-from-registry! already documents this race and
   could only shrink the window from N writes to 1; the lock is what closes it."
  [f]
  (io/update-edn! @registry-file-path
                  (fn [canonical]
                    (f (merge (read-legacy-registry) (or canonical {}))))))

(defn ^{:malli/schema [:=> [:cat :Path :map] :any]}
  upsert-registry! [project-dir entry]
  (update-registry! #(assoc % project-dir entry)))

(defn- prune-legacy-registry!
  "Drop `k` from any legacy registry that still carries it. read-registry merges
   those files UNDER the canonical one but every mutation only rewrites the
   canonical one — so without this, removing a legacy-only key is a no-op: the
   next read merges it back in and the entry is immortal."
  [k]
  (doseq [path (legacy-registry-paths)]
    (when-let [m (io/read-edn path)]
      (when (contains? m k)
        (io/write-edn! path (dissoc m k))))))

(defn ^{:malli/schema [:=> [:cat :Path] :any]}
  remove-from-registry! [project-dir]
  (prune-legacy-registry! project-dir)
  (update-registry! #(dissoc % project-dir)))

(defn ^{:malli/schema [:=> [:cat [:vector :any]] :any]}
  remove-many-from-registry!
  "Remove several keys in ONE canonical write, still pruning each from the
   legacy registries.

   The lost update this was written against — a concurrent upsert-registry!
   landing between one key's read and its write, losing a just-registered
   session, which then reads as dead and becomes reclaimable — is now excluded
   by the lock rather than merely narrowed by it. N locked updates would be
   correct too; one write is kept because removing a set of sessions is one
   decision, and a reader never catches it half-done."
  [ks]
  (when (seq ks)
    (doseq [k ks] (prune-legacy-registry! k))
    (update-registry! #(apply dissoc % ks))))
