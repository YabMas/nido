(ns nido.session.state
  (:require
   [babashka.fs :as fs]
   [clojure.string]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.process :as proc]))

;; ---------------------------------------------------------------------------
;; Path helpers — all session state lives under ~/.nido/state/<instance-id>/
;;
;; instance-id identifies a runtime — one JVM, app and database, which every
;; session of a workstream shares. For the main checkout it equals the project
;; name (e.g. "brian"); otherwise it is `keyed-id` of the key the runtime was
;; started under (e.g. "brian--ws-20261019-412858"). Config (session.edn,
;; templates) stays shared per project.
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :string]}
  keyed-id
  "`<project>--<key>`, a `/` in `key` written `~` so the id is one path segment. The one spelling
   of a key-derived id: a runtime's instance id, and a session's own key. A `%` or `~` already in
   `key` is written `%25` or `%7E` first, so two keys never spell one id — `a/b` and `a~b` are
   two runtimes — while a key holding neither, as every session name does, is spelled as before."
  [project-name key]
  (str project-name "--"
       (-> (str key)
           (clojure.string/replace "%" "%25")
           (clojure.string/replace "~" "%7E")
           (clojure.string/replace "/" "~"))))

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  file-safe
  "`id` spelled in `[A-Za-z0-9.-]` alone — a lock file's or a jj workspace's name — with every
   other byte, `_` included, written `_XX` in hex. The one spelling of a name built from a runtime
   key or an id derived from one: `_` begins only an escape, so distinct ids never share a name,
   and a name a consumer folds to `[A-Za-z0-9._-]` is left as it is."
  [id]
  (apply str (map (fn [b]
                    (let [c (char (bit-and b 0xff))]
                      (if (re-matches #"[A-Za-z0-9.-]" (str c))
                        c
                        (format "_%02X" (bit-and b 0xff)))))
                  (.getBytes (str id) "UTF-8"))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :string]}
  session-key
  "The key of one session, whatever runtime it is on: what its links are kept under."
  [project-name session-name]
  (keyed-id project-name session-name))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :Path]}
  session-profile-path
  "Where the profile the session `session-name` was brought up with is kept:
   ~/.nido/profiles/<session-key>.edn. Keyed by the session, not the runtime it is on —
   sessions sharing a runtime each created their own working copy — and outside the state
   directory, which reclaim removes."
  [project-name session-name]
  (str (fs/path (core/nido-home) "profiles" (str (session-key project-name session-name) ".edn"))))

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

(defn ^{:malli/schema [:=> [:cat :InstanceId :any] :Path]}
  pid-file
  "Pid of a named process service: ~/.nido/state/<instance-id>/<name>.pid

   Written the moment the process spawns, which session.edn cannot be: that
   record is only written once EVERY service is up, and the process is detached
   long before then. A boot interrupted in between — the `bb` running it killed,
   not merely failing — would otherwise leave a running JVM that nothing on disk
   names."
  [instance-id service-name]
  (str (fs/path (instance-state-dir instance-id) (str (name service-name) ".pid"))))

(defn ^{:malli/schema [:=> [:cat :InstanceId] [:vector :Path]]}
  pid-files
  "Every pid file an instance holds, whatever service wrote it; [] when it has
   none or no state dir. One left after its session was stopped names a process
   the stop could not show was gone."
  [instance-id]
  (let [dir (instance-state-dir instance-id)]
    (if (fs/directory? dir)
      (mapv str (fs/glob dir "*.pid"))
      [])))

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

(defn- leaf-instance-id
  "The instance id a session's runtime had before runtimes were keyed by session: `<project>--`
   and the leaf of its worktree path, which is the last segment of its name — `feat/x` was
   `<project>--x`. The one spelling of that key, for every reader of state written under it."
  [project-name session-name]
  (str project-name "--" (fs/file-name (fs/path session-name))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] [:maybe :any]]}
  read-session-profile
  "The profile the session `session-name` was brought up with, or nil. A session brought up
   before profiles were kept by session has its profile in the state directory of the runtime it
   had to itself, keyed by its worktree's leaf (`leaf-instance-id`); the first read copies it to
   the session's own `session-profile-path`, so it outlives that runtime's directory, which
   reclaim removes once the session has moved off it."
  [project-name session-name]
  (let [own (session-profile-path project-name session-name)]
    (if (fs/exists? own)
      (io/read-edn own)
      (let [legacy (str (fs/path (instance-state-dir (leaf-instance-id project-name session-name))
                                 "profile.edn"))]
        (when (fs/exists? legacy)
          (let [profile (io/read-edn legacy)]
            (io/write-edn! own profile)
            profile))))))

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

(defn ^{:malli/schema [:=> [:cat :Path] :Path]}
  refresh-snapshot-dir
  "Where a template refresh keeps its pre-refresh snapshot of `data-dir`.
   It exists only while a refresh runs, or after one was killed before it
   could restore or discard it. Either way it is the last known-good template
   and `data-dir` is not."
  [data-dir]
  (str data-dir ".refresh-bak"))

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

(defn- worktree-record-file [instance-id]
  (str (fs/path (instance-state-dir instance-id) "worktree.edn")))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] [:map-of :string [:maybe :string]]]}
  members
  "The worktrees on runtime record `session`, each mapped to its session's name — the one reading
   of a runtime's membership, so a change to it starts from this and never from the stored field. A
   runtime recorded before it had members has one: the session that started it; no record has none."
  [session]
  (cond
    (nil? session)      {}
    (:members session)  (:members session)
    :else               {(str (:project-dir session)) (:name session)}))

(defn ^{:malli/schema [:=> [:cat :InstanceId :map] :any]}
  write-session!
  "Persist an instance's record, and the worktree it names in a record of its own that outlives
   it."
  [instance-id data]
  (when-let [wt (:project-dir data)]
    (io/write-edn! (worktree-record-file instance-id) {:worktree (str wt)}))
  (io/write-edn! (session-state-file instance-id) data))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :any]}
  delete-session!
  "Drop an instance's record; its worktree record stays, written from the record first when there
   was none — a down session's state dir still says whose it is."
  [instance-id]
  (let [wt-file (worktree-record-file instance-id)]
    (when-not (fs/exists? wt-file)
      (when-let [wt (try (:project-dir (io/read-edn (session-state-file instance-id)))
                         (catch Exception _ nil))]
        (io/write-edn! wt-file {:worktree (str wt)}))))
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

(defn ^{:malli/schema [:=> [:cat [:maybe :RegistryEntry]] [:maybe [:enum :serving :booting :lite]]]}
  entry-phase
  "Which kind of registry entry `entry` is — the one reading of that every reader makes:
   :serving when it recorded an app or nREPL port; :booting for a boot placeholder, which names
   the pid of a background boot still starting the session's services and no port; :lite for an
   entry naming an instance and no port, a session provisioned without services. nil for no entry,
   or one naming no instance."
  [entry]
  (cond
    (or (pos-int? (:app-port entry)) (pos-int? (:nrepl-port entry))) :serving
    (pos-int? (:boot-pid entry))                                       :booting
    (:instance-id entry)                                               :lite))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  session-live?
  "Is the session this registry entry records up RIGHT NOW? THE predicate for whether a session
   is up, which every reader of it asks.

   A session that recorded an app/nREPL port is up while one answers: probed rather than trusted,
   since the registry is only cleaned on a graceful `down!` (engine/stop-session! →
   state/remove-from-registry!), so a reboot, a JVM crash or a `kill` leaves an entry with its
   port numbers intact indefinitely. A `:lite` session (`:services []`) records no port and is in
   use without one, so it is up while its entry stands — nil, or a map naming no instance, is a
   session that is down. A boot placeholder is a session whose services a background boot is
   still starting: not up, whether or not that boot still runs. Which of these an entry is, is
   `entry-phase`'s.

   `:pg-port` is deliberately not a signal: most sessions point at the project's
   SHARED cluster, which answers whenever any one session is up. `:repl-pid` is
   not a signal either: PIDs are recycled, so a months-old entry whose PID was
   reused would read as live forever."
  [s]
  (case (entry-phase s)
    :serving (boolean (or (and (pos-int? (:app-port s))   (proc/tcp-open? (:app-port s)))
                          (and (pos-int? (:nrepl-port s)) (proc/tcp-open? (:nrepl-port s)))))
    :lite    true
    false))

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

(defn ^{:malli/schema [:=> [:cat] :Path]}
  casualties-dir
  "Where the record of sessions that died with the host is kept: ~/.nido/casualties/ — under the
   nido home, never under the per-instance state root, so reclaiming an instance cannot delete
   it."
  []
  (str (fs/path (core/nido-home) "casualties")))


(defn ^{:malli/schema [:=> [:cat :InstanceId] [:maybe :Path]]}
  read-instance-worktree
  "The worktree an instance's state dir records — its worktree record, or the :project-dir of its
   session record where it has none — or nil when it records neither. The worktree record is
   written with the session record and left when that is deleted, so a down session's state dir
   still says whose it is."
  [instance-id]
  (let [read (fn [f k] (try (let [v (get (io/read-edn f) k)] (when (string? v) v))
                            (catch Exception _ nil)))]
    (or (read (worktree-record-file instance-id) :worktree)
        (read (session-state-file instance-id) :project-dir))))
