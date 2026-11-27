(ns nido.session.shared-pg
  "Per-project shared Postgres cluster. One long-lived RUNNING cluster at
   ~/.nido/shared/<project>/pg-data, seeded once by cloning (copy-on-write) the (stopped)
   template. All :shared-mode sessions connect to it instead of cloning their
   own PGDATA.

   Lifecycle: ensure-up! / status / down! / reset! / destroy!

   It owns the cluster's connection budget too. The project declares it on its shared
   :postgresql service — :max-connections (the cluster's limit), :reserved-connections
   (slots for nido's owner psql and operators) and :session-connections (the most one
   runtime may open). A start of the cluster runs it with the declared limit; a running
   one is never restarted to apply a changed one. A shared-mode session start is admitted
   only when the runtimes' claims, its own share and the reserve fit the limit the
   cluster started with: validated-budget / admit! / release-claim!."
  (:refer-clojure :exclude [reset!])
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.process :as proc]
   [nido.session.services.postgresql :as pg]
   [nido.session.state :as state]))

(def ^:private shared-port-range
  "Shared clusters draw from the same range sessions use, so they never collide
   with a worktree's deterministic port (different seed → different value)."
  [5500 7500])

(defn ^{:malli/schema [:=> [:cat :ProjectName] :int]}
  resolve-shared-port
  "Deterministic port for a project's shared cluster, seeded by its shared dir
   so it is stable across runs and distinct from per-session ports."
  [project-name]
  (let [[low high] shared-port-range]
    (proc/deterministic-port (state/project-shared-dir project-name) low high)))

(defn ^{:malli/schema [:=> [:cat :Path :any] :any]}
  with-lock
  "Run (f) holding the exclusive lock on lock-path, blocking until it is free.
   Returns f's value.

   Callers are other processes (bb tasks) AND other threads of the daemon, which
   starts every Run's session in-process — two Runs firing on one poll reach
   here together. `io/with-file-lock` queues both; a bare FileChannel lock
   queues only the first case and throws OverlappingFileLockException at the
   second thread."
  [lock-path f]
  (io/with-file-lock (str lock-path) f))

;; ---------------------------------------------------------------------------
;; Lifecycle helpers
;; ---------------------------------------------------------------------------

(defn- template-data-dir [project-name]
  (state/template-pg-data-dir project-name))

(defn- assert-template-ready!
  "The shared cluster is seeded by cloning the template, which must exist and
   be stopped (clonefile needs no live postmaster.pid)."
  [project-name]
  (let [tdir (template-data-dir project-name)]
    (when-not (fs/exists? (str (fs/path tdir "PG_VERSION")))
      (throw (ex-info "Template not initialized — cannot seed shared cluster."
                      {:project-name project-name
                       :hint "Run `bb nido:template:pg:init :project <name>` first."})))
    (when-not (pg/template-stopped? tdir)
      (throw (ex-info "Template Postgres is running — stop it before seeding the shared cluster."
                      {:project-name project-name
                       :hint "Run `bb nido:template:pg:stop :project <name>`."})))))

(defn- pick-shared-port
  "Port to (re)start the shared cluster on. Prefers the project's STABLE
   deterministic port so a restart/reset never strands sessions pinned to it.

   After `down!` stops our own postmaster (pg-ctl -w waits for full shutdown),
   the deterministic port can linger in TIME_WAIT from the just-closed client
   connections — and the more sessions were attached, the longer it lingers. A
   bind-probe (`find-available-port`, which binds with SO_REUSEADDR off) reads
   that as busy and skips ahead, bumping the cluster to a new port and breaking
   every session pinned to the old one. But nothing is LISTENING there, and
   PostgreSQL (SO_REUSEADDR) rebinds a TIME_WAIT port fine — so we only avoid
   the preferred port when something is *actively listening* there (a genuine
   conflict), and otherwise keep it stable."
  [project-name]
  (let [pref (resolve-shared-port project-name)]
    (if (proc/tcp-open? pref)
      (proc/find-available-port pref 200)
      pref)))

;; ---------------------------------------------------------------------------
;; The connection budget
;; ---------------------------------------------------------------------------

(def ^:private default-max-connections
  "The limit a shared cluster starts with when its definition declares no :max-connections."
  200)

(def ^:private default-reserved-connections
  "Slots kept for nido's own owner psql and operators when none are declared."
  10)

(def ^:private stock-max-connections
  "PostgreSQL's own default, what a postmaster runs with when no file it reads sets one."
  100)

(def ^:private settings-file
  "The settings nido writes into the shared cluster's data dir, included last by its
   postgresql.conf. Written immediately before nido starts the postmaster and at no other
   time, so it always says what the running postmaster started with."
  "nido-shared.conf")

(defn- invalid-field!
  [project-name field v want]
  (throw (ex-info (str project-name "'s shared :postgresql service "
                       (if (nil? v)
                         (str "declares no " field)
                         (str "declares " field " " (pr-str v)))
                       " — it must be " want ". Declare it on the :postgresql service in "
                       project-name "'s session.edn.")
                  {:refused :connection-budget :reason :invalid-budget
                   :project-name project-name :field field :value v})))

(defn- limit-of
  "The :max-connections `service-def` declares, or the default. Throws naming the field
   when it declares one that is not a positive integer."
  [project-name service-def]
  (let [v (:max-connections service-def)]
    (cond (nil? v)     default-max-connections
          (pos-int? v) v
          :else        (invalid-field! project-name :max-connections v "a positive integer"))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :map]}
  validated-budget
  "The connection budget a shared-mode :postgresql definition declares, parsed:
   {:max-connections :reserved-connections :session-connections}. Touches no cluster.
   Throws, naming the field and the project, when :session-connections is not a positive
   integer — a shared-mode start never proceeds without one — or when :max-connections
   or :reserved-connections is declared as something other than a positive, respectively
   non-negative, integer."
  [project-name service-def]
  (let [{:keys [session-connections reserved-connections]} service-def]
    (when-not (pos-int? session-connections)
      (invalid-field! project-name :session-connections session-connections
                      "a positive integer: the most connections one runtime on the shared cluster may open, its pools plus its agent's tools"))
    (when-not (or (nil? reserved-connections) (nat-int? reserved-connections))
      (invalid-field! project-name :reserved-connections reserved-connections
                      "a non-negative integer"))
    {:max-connections      (limit-of project-name service-def)
     :reserved-connections (or reserved-connections default-reserved-connections)
     :session-connections  session-connections}))

(defn- conf-max-connections
  "The max_connections the last uncommented setting in the conf file at `path` sets, or nil.
   Reads the assignment as PostgreSQL does: the name in any case, `=` optional, the value
   quoted or not, a trailing comment."
  [path]
  (when (fs/exists? (str path))
    (some->> (str/split-lines (slurp (str path)))
             (keep #(second (re-matches #"(?i)\s*max_connections(?:\s*=\s*|\s+)'?(\d+)'?\s*(?:#.*)?" %)))
             last
             parse-long)))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :int]}
  started-with-limit
  "The max_connections the project's running shared cluster started with: what nido's
   settings file says, else — a postmaster started before nido wrote one — what
   PostgreSQL itself applied: an ALTER SYSTEM value in postgresql.auto.conf, which it
   reads after postgresql.conf, else what postgresql.conf sets, else its stock default.
   (A start nido makes drops any ALTER SYSTEM max_connections, so with the settings file
   present the file is the whole answer.)"
  [project-name]
  (let [dir (state/shared-pg-data-dir project-name)]
    (or (conf-max-connections (fs/path dir settings-file))
        (conf-max-connections (fs/path dir "postgresql.auto.conf"))
        (conf-max-connections (fs/path dir "postgresql.conf"))
        stock-max-connections)))

(defn- drop-lines!
  "Rewrite the conf file at `path` without the lines matching `re`, when it has any."
  [path re]
  (when (fs/exists? path)
    (let [text (slurp path)
          kept (remove #(re-matches re %) (str/split-lines text))]
      (when (< (count kept) (count (str/split-lines text)))
        (spit path (str (str/join "\n" kept) "\n"))))))

(defn- write-limit!
  "Write the settings file the postmaster about to start reads, and make the cluster's
   postgresql.conf include it as its last line, active, so no later setting there
   overrides it. postgresql.auto.conf is read after postgresql.conf, so a
   max_connections an ALTER SYSTEM left there is dropped. Only ever called immediately
   before a start of the postmaster."
  [data-dir max-connections]
  (let [conf    (str (fs/path data-dir "postgresql.conf"))
        include (str "include_if_exists = '" settings-file "'")]
    ;; Every earlier include of the file, active or commented out, goes; one is appended.
    (drop-lines! conf (re-pattern (str "\\s*#?\\s*include(?:_if_exists)?\\s*=?\\s*'"
                                       settings-file "'.*")))
    (spit conf (str (some-> (when (fs/exists? conf) (slurp conf)) str/trimr not-empty (str "\n\n"))
                    include "\n"))
    (drop-lines! (str (fs/path data-dir "postgresql.auto.conf"))
                 #"\s*max_connections\s*=.*")
    (spit (str (fs/path data-dir settings-file))
          (str "# Written by nido immediately before it starts this cluster; read by\n"
               "# admission as the limit the running postmaster started with.\n"
               "max_connections = " max-connections "\n"))))

(defn- start-existing!
  "Start (or adopt) the already-seeded shared cluster; return its live port. A start runs
   the postmaster with `max-connections`; a cluster already running is adopted as it is,
   never restarted to apply a changed limit."
  [project-name max-connections]
  (let [data-dir (state/shared-pg-data-dir project-name)
        log-path (state/shared-log-file project-name)
        bin-dir  (pg/find-pg-bin-dir)
        existing (pg/finish-stopping! data-dir (pg/detect-running-postmaster data-dir))]
    (cond
      (= :running (:status existing))
      (do (core/log-step (str "Shared cluster already running (pid " (:pid existing)
                              ", port " (:port existing) ")"))
          (:port existing))

      :else
      (let [_     (when (= :stale (:status existing))
                    (core/log-step "Removing stale shared postmaster.pid")
                    (fs/delete-if-exists (:pid-file existing)))
            port  (pick-shared-port project-name)]
        (write-limit! data-dir max-connections)
        (pg/pg-ctl-start! bin-dir data-dir port log-path pg/socket-base-dir)
        (pg/wait-for-tcp! port)
        (state/write-shared-meta! project-name
                                  (merge (or (try (state/read-shared-meta project-name) (catch Exception _ nil)) {})
                                         {:project-name project-name :port port}))
        (core/log-step (str "Shared cluster running (port " port ", max_connections "
                            max-connections ", data-dir=" data-dir ")"))
        port))))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:maybe :map]] :any]}
  ensure-up!
  "Ensure the project's shared cluster exists and is running. Seeds it by
   cloning the template (copy-on-write) on first call; a start runs it with the
   :max-connections `service-def` declares (nido's default when it declares none or is
   nil). Concurrency-safe. Opens no client connection. Returns {:port p}."
  [project-name service-def]
  (let [limit (limit-of project-name service-def)]
    (with-lock (state/shared-lock-file project-name)
      (fn []
        (let [data-dir (state/shared-pg-data-dir project-name)
              seeded?  (fs/exists? (str (fs/path data-dir "PG_VERSION")))]
          (when-not seeded?
            (assert-template-ready! project-name)
            (fs/create-dirs (state/project-shared-dir project-name))
            (core/log-step "Seeding shared cluster: cloning template…")
            (pg/clone-pgdata! (template-data-dir project-name) data-dir)
            (state/write-shared-meta! project-name
                                      {:project-name project-name
                                       :seeded-at (core/now-iso)}))
          {:port (start-existing! project-name limit)})))))

;; ---------------------------------------------------------------------------
;; Admission — claims on the budget
;; ---------------------------------------------------------------------------

(defn- read-claims [project-name]
  (or (io/read-edn (state/shared-claims-file project-name)) {}))

(defn- on-cluster-runtimes
  "instance-id of every runtime the registry records as up on the project's shared
   cluster at `port` — a runtime once however many sessions share it. None when no port is
   known: a cluster with no recorded port has no runtime up on it."
  [project-name port]
  (->> (when port (vals (state/read-registry)))
       (filter #(and (= (str project-name) (str (:project-name %)))
                     (= port (:pg-port %))
                     (:instance-id %)
                     (state/session-live? %)))
       (map :instance-id)
       distinct))

(defn- claimed
  "What runtimes on the cluster other than `instance-id` claim: every claim held, and every
   runtime registered as up there that holds none — one started before admission
   existed — at `share`."
  [project-name port instance-id share]
  (let [claims    (dissoc (read-claims project-name) instance-id)
        unclaimed (->> (on-cluster-runtimes project-name port)
                       (remove #(or (= % instance-id) (contains? claims %)))
                       vec)]
    {:claims    claims
     :unclaimed unclaimed
     :runtimes  (+ (count claims) (count unclaimed))
     :claimed   (+ (reduce + 0 (map :connections (vals claims)))
                   (* share (count unclaimed)))}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string :map] :map]}
  admit!
  "Admit runtime `instance-id` onto the project's shared cluster under `budget` (as
   `validated-budget` answers it). Brings the cluster up first, without opening a client
   connection. Admissions of one project are taken one at a time, so each counts the
   claims of the others. Answers as a value:
     {:admitted figures} — the runtime now holds a claim of its :session-connections;
     {:refused figures}  — it would take the cluster past its started-with limit.
   figures: :project-name :max-connections (started-with) :reserved-connections
   :runtimes :claimed :session-connections."
  [project-name instance-id {:keys [reserved-connections session-connections] :as budget}]
  (let [{:keys [port]} (ensure-up! project-name budget)]
    (with-lock (state/shared-lock-file project-name)
      (fn []
        (let [limit (started-with-limit project-name)
              {:keys [claims runtimes] :as c} (claimed project-name port instance-id
                                                       session-connections)
              figures {:project-name         project-name
                       :port                 port
                       :max-connections      limit
                       :reserved-connections reserved-connections
                       :runtimes             runtimes
                       :claimed              (:claimed c)
                       :session-connections  session-connections}]
          (if (<= (+ (:claimed c) session-connections reserved-connections) limit)
            (do (io/write-edn! (state/shared-claims-file project-name)
                               (assoc claims instance-id
                                      {:connections session-connections
                                       :claimed-at  (core/now-iso)}))
                {:admitted figures})
            {:refused figures}))))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  refusal
  "The error a budget refusal is thrown as. Its message carries the figures and the ways
   out, and nothing that names one particular session, so every budget refusal of one
   project is one failure cause."
  [{:keys [project-name max-connections reserved-connections runtimes claimed
           session-connections] :as figures}]
  (ex-info (str project-name "'s shared Postgres cluster has no connection budget left for this session: "
                "max_connections " max-connections ", reserve " reserved-connections
                ", runtimes on it " runtimes " claiming " claimed
                ", this session's :session-connections " session-connections
                " — " (+ claimed session-connections reserved-connections) " > " max-connections
                ". Stop a session, or raise :max-connections on " project-name
                "'s shared :postgresql service and restart the shared cluster "
                "(bb nido:shared:pg:down, then bb nido:shared:pg:up).")
           (assoc figures :refused :connection-budget :reason :over-budget)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] :any]}
  release-claim!
  "Release runtime `instance-id`'s claim on the project's shared cluster. No-op when it
   holds none. Never throws: a release that fails is a known gap the design accepts — the
   claim stays counted until removed by hand — so it is reported, naming the file and the
   runtime, and the stop (or the failed start's own error) goes on."
  [project-name instance-id]
  (let [f (state/shared-claims-file project-name)]
    (try
      (when (fs/exists? f)
        (with-lock (state/shared-lock-file project-name)
          (fn []
            (let [claims (read-claims project-name)]
              (when (contains? claims instance-id)
                (io/write-edn! f (dissoc claims instance-id))
                (core/log-step (str "Released " project-name " shared-cluster claim of "
                                    instance-id)))))))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "warning: could not release " instance-id "'s claim on " project-name
                        "'s shared cluster (" (ex-message e) "). It stays counted against "
                        "admission: delete its entry from " f " by hand "
                        "(bb nido:shared:pg:status :project " project-name " lists the claims).")))
        nil))))

(defn ^{:malli/schema [:=> [:cat :string] :any]}
  release-claims-of!
  "Release runtime `instance-id`'s claim on whichever project's shared cluster holds one —
   for a stop that knows the runtime but not its project."
  [instance-id]
  (let [root (state/shared-dir)]
    (when (fs/exists? root)
      (doseq [dir (fs/list-dir root)
              :when (fs/exists? (fs/path dir "claims.edn"))]
        (release-claim! (str (fs/file-name dir)) instance-id)))))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:maybe :map]] :any]}
  status
  "Print shared-cluster status for a project: the cluster, and its connection budget —
   the limit it started with, the reserve and share `service-def` declares, and each
   runtime's claim. The claims are printed whatever the cluster's state: they count against
   admission until their runtimes stop, a destroyed cluster's included."
  [project-name service-def]
  (let [data-dir (state/shared-pg-data-dir project-name)
        m        (try (state/read-shared-meta project-name) (catch Exception _ nil))
        seeded?  (fs/exists? (str (fs/path data-dir "PG_VERSION")))
        running? (and seeded?
                      (= :running (:status (pg/detect-running-postmaster data-dir))))]
    (println "nido shared cluster:")
    (println "  project:" project-name)
    (println "  data-dir:" data-dir)
    (println "  seeded?:" seeded?)
    (println "  running?:" running?)
    (when m
      (when-let [p (:port m)] (println "  port:" p))
      (when-let [t (:seeded-at m)] (println "  seeded-at:" t)))
    (let [share   (:session-connections service-def)
          reserve (or (:reserved-connections service-def) default-reserved-connections)
          {:keys [claims unclaimed claimed]}
          (claimed project-name (:port m) "" (if (pos-int? share) share 0))]
      (println "connection budget:")
      (println "  max_connections (started with):" (if running? (started-with-limit project-name) "—"))
      (println "  declared :max-connections:" (or (:max-connections service-def)
                                                 (str default-max-connections " (default)")))
      (println "  reserve:" reserve)
      (println "  per runtime (:session-connections):" (or share "— not declared"))
      (println "  claimed:" claimed)
      (doseq [[id {:keys [connections claimed-at]}] (sort-by key claims)]
        (println (str "    " id "  " connections "  since " claimed-at)))
      (doseq [id (sort unclaimed)]
        (println (str "    " id "  " (or share "?") "  up, no claim (counted at the declared share)"))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  down!
  "Stop the shared cluster (preserve data). Cleans a stale postmaster.pid if the
   cluster died uncleanly. No-op if not seeded or already stopped."
  [project-name]
  (let [data-dir (state/shared-pg-data-dir project-name)]
    (when (fs/exists? (str (fs/path data-dir "PG_VERSION")))
      (let [{:keys [status pid-file]} (pg/detect-running-postmaster data-dir)]
        (cond
          (#{:running :stopping} status)
          (do (pg/pg-ctl-stop! data-dir)
              (core/log-step (str "Stopped shared cluster for " project-name)))

          (= :stale status)
          (do (fs/delete-if-exists pid-file)
              (core/log-step (str "Removed stale shared postmaster.pid for " project-name))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName [:maybe :map]] :any]}
  reset!
  "Stop, drop PGDATA, re-clone from template, start with the :max-connections
   `service-def` declares. Recovery path."
  [project-name service-def]
  (with-lock (state/shared-lock-file project-name)
    (fn []
      (down! project-name)
      (let [data-dir (state/shared-pg-data-dir project-name)]
        (when (fs/exists? data-dir)
          (fs/delete-tree data-dir)
          (core/log-step (str "Dropped shared PGDATA at " data-dir))))))
  ;; ensure-up! re-takes the lock; keep reset! simple by composing.
  (ensure-up! project-name service-def))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  destroy!
  "Stop and remove the shared cluster PGDATA + metadata. The claims of runtimes on it are
   kept: a runtime's claim is held until nido stops that runtime, not the cluster, so a
   recreated cluster still counts every runtime admitted before it."
  [project-name]
  (let [dir   (state/project-shared-dir project-name)
        keep? #{(str (fs/absolutize (state/shared-claims-file project-name)))
                (str (fs/absolutize (state/shared-lock-file project-name)))}]
    (when (fs/exists? dir)
      (with-lock (state/shared-lock-file project-name)
        (fn []
          (down! project-name)
          (doseq [child (fs/list-dir dir)
                  :when (not (keep? (str (fs/absolutize child))))]
            (fs/delete-tree child))
          (core/log-step (str "Destroyed shared cluster for " project-name
                              (when (seq (read-claims project-name))
                                " (its runtimes' connection claims are kept)"))))))))

;; ---------------------------------------------------------------------------
;; Migration file helpers
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :int]]}
  migration-file->version
  "Numeric version of a Flyway versioned migration filename, or nil for
   non-versioned (e.g. repeatable R__) names."
  [filename]
  (when-let [[_ v] (re-matches #"V(\d+)__.*\.sql" filename)]
    (parse-long v)))

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  migration-file->description
  "Flyway description: the text between the version prefix and .sql, with
   underscores turned into spaces (matches Flyway's own recorded description)."
  [filename]
  (when-let [[_ _ desc] (re-matches #"V(\d+)__(.*)\.sql" filename)]
    (str/replace desc "_" " ")))

(defn ^{:malli/schema [:=> [:cat [:set :int] [:vector :string]] [:vector :string]]}
  pending-migrations
  "Migration filenames whose version the cluster has not applied, sorted
   ascending by version. Non-versioned names are ignored.

   Membership, not a high-water mark: a branch session's Flyway writes its own
   migrations into the shared history, so a main migration can arrive with a
   version BELOW one already applied. brian migrates out-of-order for the same
   reason. Versions at or below the lowest applied one are the baseline's
   pre-history — a seeded cluster records only the baseline row, not the
   migrations it folds in — and are never pending."
  [applied-versions filenames]
  (let [floor (if (seq applied-versions) (apply min applied-versions) 0)]
    (->> filenames
         (keep (fn [f] (when-let [v (migration-file->version f)] [v f])))
         (filter (fn [[v _]] (and (> v floor) (not (contains? applied-versions v)))))
         (sort-by first)
         (mapv second))))

;; ---------------------------------------------------------------------------
;; DDL-less application role
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  app-role-sql
  "Idempotent SQL that (re)establishes the DDL-less application role. Grants
   full DML on current and future objects but never CREATE on the schema, so a
   Flyway migration run as this role fails `permission denied` and rolls back."
  [{:keys [schema app-user owner-user] :or {owner-user "user"}}]
  (str
   "DO $$ BEGIN "
   "  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" app-user "') THEN "
   "    CREATE ROLE " app-user " LOGIN; "
   "  END IF; "
   "END $$;\n"
   "GRANT USAGE ON SCHEMA " schema " TO " app-user ";\n"
   "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " schema " TO " app-user ";\n"
   "GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA " schema " TO " app-user ";\n"
   ;; DEFAULT PRIVILEGES only affect objects created by the named role, so it
   ;; must target the actual owner (the role nido's advance step migrates as),
   ;; not a hardcoded literal — else future owner-created tables silently miss
   ;; the app role's grants.
   "ALTER DEFAULT PRIVILEGES FOR ROLE " owner-user " IN SCHEMA " schema
   " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " app-user ";\n"
   "ALTER DEFAULT PRIVILEGES FOR ROLE " owner-user " IN SCHEMA " schema
   " GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO " app-user ";\n"))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  run-owner-sql!
  "Run a SQL string against the shared cluster as the owner via psql. Throws on
   non-zero exit with the captured stderr."
  [{:keys [port db-name owner-user]} sql]
  (let [bin-dir (pg/find-pg-bin-dir)
        result  (shell {:continue true :out :string :err :string}
                       (pg/pg-cmd bin-dir "psql")
                       "-h" "127.0.0.1" "-p" (str port) "-U" owner-user "-d" db-name
                       "-v" "ON_ERROR_STOP=1" "-c" sql)]
    (when-not (zero? (:exit result))
      (throw (ex-info "shared-pg owner SQL failed"
                      {:error (:err result) :output (:out result)})))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  ensure-app-role!
  "Create/refresh the DDL-less application role on the shared cluster.
   No-op when :app-user is nil (feature off)."
  [{:keys [app-user schema owner-user] :as opts}]
  (when app-user
    (core/log-step (str "Ensuring DDL-less shared role " app-user))
    (run-owner-sql! opts (app-role-sql {:schema schema :app-user app-user
                                        :owner-user (or owner-user "user")}))))

;; ---------------------------------------------------------------------------
;; Advance-to-main apply loop
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  history-insert-sql
  "One INSERT into flyway_schema_history matching Flyway's row shape. Wrapped
   with the migration body in a single transaction by the caller."
  [{:keys [schema rank version description script checksum owner-user]}]
  (str "INSERT INTO " schema ".flyway_schema_history "
       "(installed_rank, version, description, type, script, checksum, "
       "installed_by, installed_on, execution_time, success) VALUES ("
       rank ", '" version "', '" description "', 'SQL', '" script "', "
       checksum ", '" owner-user "', now(), 0, true);"))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  shared-applied-history
  "The numeric versions the shared history records, and its max installed_rank.
   Returns {:versions #{} :rank 0} for an empty/baseline-free history."
  [{:keys [port db-name owner-user schema]}]
  (let [bin-dir (pg/find-pg-bin-dir)
        ;; max(installed_rank) must scan ALL rows — a version-regex filter here
        ;; would drop NULL/non-numeric versions (e.g. a repeatable R__ row) from
        ;; the rank max, undercounting the next installed_rank and colliding on
        ;; its PK. The regex belongs only on the versions.
        ;;
        ;; Versions are parsed as longs, not ints: brian's timestamp-versioned
        ;; migrations (e.g. V20260708212543__…) overflow int4 (max ~2.1e9).
        q (str "select "
               "coalesce(string_agg(version, ',') filter (where version ~ '^[0-9]+$'), ''), "
               "coalesce(max(installed_rank), 0) "
               "from " schema ".flyway_schema_history;")
        result (shell {:continue true :out :string :err :string}
                      (pg/pg-cmd bin-dir "psql")
                      "-h" "127.0.0.1" "-p" (str port) "-U" owner-user "-d" db-name
                      "-At" "-F" "|" "-c" q)
        [vs r] (some-> (:out result) str/trim (str/split #"\|" 2))]
    {:versions (into #{} (keep (comp parse-long str/trim)) (some-> vs (str/split #",")))
     :rank     (or (some-> r str/trim parse-long) 0)}))

(defn- reversioned-candidates
  "Applied history rows whose version main does not carry, keyed by
   [checksum description] → version. A branch session's Flyway writes its
   migration into the shared history under the branch's version; brian
   re-versions a migration when it merges, so main then carries the SAME
   body under a new version and the old row names a version nothing on main
   has. Rows main still carries are never candidates."
  [{:keys [port db-name owner-user schema]} main-versions]
  (let [bin-dir (pg/find-pg-bin-dir)
        q       (str "select version, checksum, description from " schema
                     ".flyway_schema_history where version ~ '^[0-9]+$' "
                     "and checksum is not null;")
        result  (shell {:continue true :out :string :err :string}
                       (pg/pg-cmd bin-dir "psql")
                       "-h" "127.0.0.1" "-p" (str port) "-U" owner-user "-d" db-name
                       "-At" "-F" "\u001f" "-c" q)]
    (when-not (zero? (:exit result))
      (throw (ex-info "reading shared flyway history failed"
                      {:error (:err result) :output (:out result)})))
    (into {}
          (keep (fn [line]
                  (let [[v c d] (str/split line #"\u001f" 3)
                        v       (some-> v str/trim parse-long)
                        c       (some-> c str/trim parse-long)]
                    (when (and v c d (not (contains? main-versions v)))
                      [[c d] v]))))
          (remove str/blank? (str/split-lines (:out result))))))

(defn- applied-as
  "The [checksum description] key of the `reversioned` row that already ran
   main's migration `file` (its body at `file-path`), or nil.

   A row matches on description and on checksum, the checksum taken either over
   main's body as it is or over it with every mention of its own script name
   rewritten to the row's. brian's migrations open with a comment naming their
   own file, so re-versioning one changes that line and nothing else — a match
   on the raw checksum alone never fires for them, and the DDL is re-run."
  [reversioned file-path file checksum desc]
  (or (when (contains? reversioned [checksum desc]) [checksum desc])
      (let [version (migration-file->version file)
            tail    (subs file (count (str "V" version)))
            body    (slurp file-path)]
        (some (fn [[[c d :as k] from]]
                (when (= d desc)
                  (let [renamed (fs/create-temp-file {:suffix ".sql"})]
                    (try
                      (spit (str renamed)
                            (str/replace body file (str "V" from tail))
                            :encoding "UTF-8")
                      (when (= c (pg/flyway-checksum (str renamed))) k)
                      (finally (fs/delete-if-exists renamed))))))
              reversioned))))

(defn- history-rekey-sql
  "Move an applied history row from its branch version to main's, so the
   migration it records is not run a second time. The row takes main's
   `checksum` too: a row can match on a body that differs from main's in how
   it names its own file (see `applied-as`), and Flyway validates the recorded
   checksum against main's file, refusing every later boot on a mismatch."
  [{:keys [schema from-version version script checksum]}]
  (str "UPDATE " schema ".flyway_schema_history SET version = '" version
       "', script = '" script "', checksum = " checksum
       " WHERE version = '" from-version "';"))

(def ^:private migrations-subdir "resources/db/migrations")

(def ^:private main-rev
  "The revision whose migrations are the shared schema's: what `advance-shared-to-main!` applies,
   and what `offending-migrations` measures a candidate against."
  "main@origin")

(defn ^{:malli/schema [:=> [:cat :Path] [:vector :string]]}
  list-main-migration-files
  "Basenames of main@origin's migration files, via jj — one cheap subprocess,
   no per-file `file show`. Fails loud on jj error. (The dir is flat: every
   file lives directly under resources/db/migrations.)"
  [source-repo]
  (let [res (shell {:continue true :out :string :err :string}
                   "jj" "--ignore-working-copy" "-R" source-repo
                   "file" "list" "-r" main-rev (str "root:" migrations-subdir))]
    (when-not (zero? (:exit res))
      (throw (ex-info "jj file list (main@origin migrations) failed"
                      {:error (:err res) :source-repo source-repo})))
    (->> (str/split-lines (:out res))
         (map str/trim) (remove str/blank?)
         (filter #(str/ends-with? % ".sql"))
         (mapv (comp str fs/file-name)))))

(defn ^{:malli/schema [:=> [:cat :Path :Path :string] :any]}
  materialize-one!
  "Write one main@origin migration file (by basename) into dest-dir via jj.
   Fails loud on jj error. UTF-8 pinned on the write so the checksum round-trip
   is charset-independent."
  [source-repo dest-dir filename]
  (let [show (shell {:continue true :out :string :err :string}
                    "jj" "--ignore-working-copy" "-R" source-repo
                    "file" "show" "-r" main-rev
                    (str "root:" migrations-subdir "/" filename))]
    (when-not (zero? (:exit show))
      (throw (ex-info "jj file show failed" {:file filename :error (:err show)})))
    (spit (str (fs/path dest-dir filename)) (:out show) :encoding "UTF-8")
    filename))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  advance-shared-to-main!
  "Advance the shared cluster to main@origin by applying pending migrations as
   the owner. No-op when already current — nothing is materialized or shelled
   out to jj/psql in that case. Returns the count applied, a re-versioned
   migration re-keyed rather than re-run included."
  [{:keys [port db-name owner-user schema source-repo] :as opts}]
  (let [{:keys [versions rank]} (shared-applied-history opts)
        basenames (list-main-migration-files source-repo)
        pending   (pending-migrations versions basenames)]
    (if (empty? pending)
      0
      (let [tmp (str (fs/create-temp-dir {:prefix "nido-shared-advance"}))]
        ;; Only reached (and only removed) when there IS pending work.
        (try
          (core/log-step (str "Advancing shared cluster to main@origin: applying "
                              (count pending) " migration(s): "
                              (str/join ", " (map #(str "V" (migration-file->version %)) pending))))
          (loop [[f & more] pending, rank (inc rank), applied 0
                 reversioned (reversioned-candidates
                              opts (into #{} (keep migration-file->version) basenames))]
            (if-not f
              applied
              (let [_         (materialize-one! source-repo tmp f)
                    file-path (str (fs/path tmp f))
                    checksum  (pg/flyway-checksum file-path)
                    desc      (migration-file->description f)
                    version   (migration-file->version f)
                    ;; A row main dropped whose body is this one: it already ran
                    ;; here under its branch version. Re-key the row rather than
                    ;; re-run DDL that would fail on its own objects. A body that
                    ;; changed on the way to main beyond naming its own file does
                    ;; not match, and is applied — and fails — as before.
                    matched   (applied-as reversioned file-path f checksum desc)
                    from      (some->> matched (get reversioned))
                    combined  (if from
                                (history-rekey-sql {:schema schema :from-version from
                                                    :version version :script f
                                                    :checksum checksum})
                                ;; A migration's last statement need not end in `;`
                                ;; (V4 on brian's main does not); the separator keeps it
                                ;; from running into the history insert.
                                (str "SET search_path TO " schema ", public;\n"
                                     (slurp file-path) "\n;\n"
                                     (history-insert-sql
                                      {:schema schema :rank rank :version version
                                       :description desc :script f :checksum checksum
                                       :owner-user owner-user})))
                    bin-dir   (pg/find-pg-bin-dir)
                    res       (shell {:continue true :out :string :err :string}
                                     (pg/pg-cmd bin-dir "psql")
                                     "-h" "127.0.0.1" "-p" (str port) "-U" owner-user "-d" db-name
                                     "--single-transaction" "-v" "ON_ERROR_STOP=1"
                                     "-c" combined)]
                (when-not (zero? (:exit res))
                  (throw (ex-info (str "Advancing shared cluster failed applying " f)
                                  {:file f :error (:err res) :output (:out res)})))
                (when from
                  (core/log-step (str "  V" version " already applied as V" from
                                      " (re-versioned on main) — history re-keyed")))
                (recur more (if from rank (inc rank)) (inc applied)
                       (dissoc reversioned matched)))))
          (finally
            (fs/delete-tree tmp)))))))

(defn- jj-lines
  "The lines jj prints for `args`, run in `repo` without touching its working copy. Throws,
   naming `what`, on a non-zero exit."
  [repo what & args]
  (let [res (apply shell {:dir (str repo) :continue true :out :string :err :string}
                   "jj" "--ignore-working-copy" args)]
    (when-not (zero? (:exit res))
      (throw (ex-info (str "jj " what " failed in " repo ": " (str/trim (:err res)))
                      {:error (:err res) :repo (str repo) :args (vec args)})))
    (->> (str/split-lines (:out res)) (map str/trim) (remove str/blank?))))

(defn ^{:malli/schema [:=> [:cat :Path] :string]}
  main-commit
  "The commit id main@origin names now in the repository `repo` (any working copy of it): the
   main a guard admits against, pinned (`offending-migrations`), so a fetch that moves main
   between an admission and the write it admits cannot refuse the write. Throws on a jj error."
  [repo]
  (first (jj-lines repo "log" "log" "-r" main-rev "--no-graph" "-T" "commit_id")))

(defn ^{:malli/schema [:function
                       [:=> [:cat :Path :string] [:vector :string]]
                       [:=> [:cat :Path :string [:maybe :string]] [:vector :string]]]}
  offending-migrations
  "The migration files revision `rev` of the repository `repo` (any working copy of it) would put
   in front of a shared cluster: every path under the migrations directory that `rev` holds and
   main either lacks or holds with other content — read from the same directory
   `advance-shared-to-main!` applies. Main is `main`, a commit `main-commit` pinned, else
   main@origin as it is now. A migration identical to main's is not one, nor is one `rev`
   deletes. Paths are relative to the repository root, sorted. Throws on a jj error, which
   admits nothing."
  ([repo rev] (offending-migrations repo rev nil))
  ([repo rev main]
  (let [main-rev (or main main-rev)
        dir     (str "root:" migrations-subdir)
        held    (set (jj-lines repo "file list" "file" "list" "-r" rev dir))
        on-main (set (jj-lines repo "file list" "file" "list" "-r" main-rev dir))
        changed (jj-lines repo "diff" "diff" "--from" main-rev "--to" rev "--name-only" dir)
        bytes   (fn [r path]
                  (let [res (shell {:dir (str repo) :continue true :out :bytes :err :string}
                                   "jj" "--ignore-working-copy" "file" "show" "-r" r
                                   (str "root:" path))]
                    (when-not (zero? (:exit res))
                      (throw (ex-info (str "jj file show failed in " repo ": " (str/trim (:err res)))
                                      {:error (:err res) :repo (str repo) :rev r :path path})))
                    (:out res)))]
    ;; A changed path `rev` does not hold is one it deleted; a rename lists only its new path.
    ;; A path the diff names may differ from main's in its executable bit alone, so a path both
    ;; hold offends only when its bytes differ.
    (vec (sort (filter #(and (held %)
                             (or (not (on-main %))
                                 (not (java.util.Arrays/equals ^bytes (bytes main-rev %)
                                                               ^bytes (bytes rev %)))))
                       changed))))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  migration-refusal
  "The error a shared runtime's checkout refresh is refused with when the members' working copies
   hold `paths` (`offending-migrations`): the runtime's `sessions`, `session` the one whose verb
   asked, of project `project`. Its data names no session, so every such refusal of one project
   is one failure cause."
  [{:keys [project session sessions paths]}]
  (let [session (or session (first sessions) "<session>")]
    (ex-info
     (str "Refused: " (str/join ", " (sort sessions)) " on " project
          "'s shared Postgres cluster would serve migrations main@origin does not hold — "
          (str/join ", " paths) ". The app's Flyway would apply them to the database every "
          project " session shares. The runtime keeps the checkout it had.\n"
          "To run them, isolate the session's database:\n"
          "  bb nido:session:isolate :project " project " " session "\n"
          "  (or, from inside the session: nido session:isolate)\n"
          "Isolating restarts the session on a private copy-on-write clone of " project
          "'s template database; the shared cluster and every other session are untouched. "
          "It is refused while another session is on the same runtime. "
          "`bb nido:session:share :project " project " " session "` (nido:session:share) returns "
          "the session to the shared cluster, dropping the private copy.\n"
          "Or take the migration out of the working copy, or land it on main first.")
     {:refused :migration-on-shared-db :reason :branch-migration
      :project project :paths (vec paths)})))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :any]}
  ensure-ready!
  "Bring the shared cluster up — a start runs it with opts' :max-connections, nido's
   default when absent — and, when opts opts-in (has :app-user or :source-repo), advance
   it to main@origin and ensure the DDL-less role. Returns {:port p}. opts nil/empty ⇒
   plain ensure-up!."
  [project-name {:keys [db-name owner-user schema app-user source-repo] :as opts}]
  (let [{:keys [port] :as up} (ensure-up! project-name opts)]
    (when (and opts (or app-user source-repo))
      ;; ensure-up! already released its lock (sequential in-process, so
      ;; re-acquiring here is not reentrant) — take a fresh one so a second
      ;; shared session:up racing right after a main merge can't compute the
      ;; same pending set concurrently and collide on the history PK.
      (with-lock (state/shared-lock-file project-name)
        (fn []
          (let [base {:port port :db-name db-name :owner-user (or owner-user "user")
                      :schema schema}]
            (when source-repo
              (advance-shared-to-main! (assoc base :source-repo source-repo)))
            (when app-user
              (ensure-app-role! (assoc base :app-user app-user)))))))
    up))
