(ns nido.shared-pg
  "Per-project shared Postgres cluster. One long-lived RUNNING cluster at
   ~/.nido/shared/<project>/pg-data, seeded once by APFS-cloning the (stopped)
   template. All :shared-mode sessions connect to it instead of cloning their
   own PGDATA.

   Lifecycle (later tasks):
     ensure-up! / status / down! / reset! / destroy!"
  (:refer-clojure :exclude [reset!])
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.services.postgresql :as pg]
   [nido.session.state :as state]))

(def ^:private shared-port-range
  "Shared clusters draw from the same range sessions use, so they never collide
   with a worktree's deterministic port (different seed → different value)."
  [5500 7500])

(defn resolve-shared-port
  "Deterministic port for a project's shared cluster, seeded by its shared dir
   so it is stable across runs and distinct from per-session ports."
  [project-name]
  (let [[low high] shared-port-range]
    (proc/deterministic-port (state/project-shared-dir project-name) low high)))

(defn with-lock
  "Run (f) while holding an exclusive OS file lock on lock-path. Creates the
   parent dir and lock file as needed. Blocks until the lock is acquired.

   Uses RandomAccessFile + FileChannel.lock() — the JVM releases the OS lock
   when the channel is closed, so no explicit FileLock.release() call is needed
   (and avoids the FileLock class not being in Babashka's class allowlist)."
  [lock-path f]
  (fs/create-dirs (fs/parent lock-path))
  (let [raf (java.io.RandomAccessFile. lock-path "rw")
        ch  (.getChannel raf)]
    (try
      (.lock ch)  ;; blocks until exclusive lock is acquired
      (try (f)
           (finally (.close ch)))  ;; closing channel releases the OS lock
      (finally (.close raf)))))

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

(defn- start-existing!
  "Start (or adopt) the already-seeded shared cluster; return its live port."
  [project-name]
  (let [data-dir (state/shared-pg-data-dir project-name)
        log-path (state/shared-log-file project-name)
        bin-dir  (pg/find-pg-bin-dir)
        existing (pg/detect-running-postmaster data-dir)]
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
        (pg/pg-ctl-start! bin-dir data-dir port log-path pg/socket-base-dir)
        (pg/wait-for-tcp! port)
        (state/write-shared-meta! project-name
                                  (merge (or (try (state/read-shared-meta project-name) (catch Exception _ nil)) {})
                                         {:project-name project-name :port port}))
        (core/log-step (str "Shared cluster running (port " port ", data-dir=" data-dir ")"))
        port))))

(defn ensure-up!
  "Ensure the project's shared cluster exists and is running. Seeds it by
   APFS-cloning the template on first call. Concurrency-safe. Returns {:port p}."
  [project-name]
  (with-lock (state/shared-lock-file project-name)
    (fn []
      (let [data-dir (state/shared-pg-data-dir project-name)
            seeded?  (fs/exists? (str (fs/path data-dir "PG_VERSION")))]
        (when-not seeded?
          (assert-template-ready! project-name)
          (fs/create-dirs (state/project-shared-dir project-name))
          (core/log-step "Seeding shared cluster: APFS-cloning template…")
          (pg/clone-pgdata! (template-data-dir project-name) data-dir)
          (state/write-shared-meta! project-name
                                    {:project-name project-name
                                     :seeded-at (core/now-iso)}))
        {:port (start-existing! project-name)}))))

(defn status
  "Print shared-cluster status for a project."
  [project-name]
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
      (when-let [t (:seeded-at m)] (println "  seeded-at:" t)))))

(defn down!
  "Stop the shared cluster (preserve data). Cleans a stale postmaster.pid if the
   cluster died uncleanly. No-op if not seeded or already stopped."
  [project-name]
  (let [data-dir (state/shared-pg-data-dir project-name)]
    (when (fs/exists? (str (fs/path data-dir "PG_VERSION")))
      (let [{:keys [status pid-file]} (pg/detect-running-postmaster data-dir)]
        (cond
          (= :running status)
          (do (pg/pg-ctl-stop! data-dir)
              (core/log-step (str "Stopped shared cluster for " project-name)))

          (= :stale status)
          (do (fs/delete-if-exists pid-file)
              (core/log-step (str "Removed stale shared postmaster.pid for " project-name))))))))

(defn reset!
  "Stop, drop PGDATA, re-clone from template, start. Recovery path."
  [project-name]
  (with-lock (state/shared-lock-file project-name)
    (fn []
      (down! project-name)
      (let [data-dir (state/shared-pg-data-dir project-name)]
        (when (fs/exists? data-dir)
          (fs/delete-tree data-dir)
          (core/log-step (str "Dropped shared PGDATA at " data-dir))))))
  ;; ensure-up! re-takes the lock; keep reset! simple by composing.
  (ensure-up! project-name))

(defn destroy!
  "Stop and remove the shared cluster PGDATA + metadata."
  [project-name]
  (down! project-name)
  (let [dir (state/project-shared-dir project-name)]
    (when (fs/exists? dir)
      (fs/delete-tree dir)
      (core/log-step (str "Destroyed shared cluster for " project-name)))))

;; ---------------------------------------------------------------------------
;; Migration file helpers
;; ---------------------------------------------------------------------------

(defn migration-file->version
  "Numeric version of a Flyway versioned migration filename, or nil for
   non-versioned (e.g. repeatable R__) names."
  [filename]
  (when-let [[_ v] (re-matches #"V(\d+)__.*\.sql" filename)]
    (parse-long v)))

(defn migration-file->description
  "Flyway description: the text between the version prefix and .sql, with
   underscores turned into spaces (matches Flyway's own recorded description)."
  [filename]
  (when-let [[_ _ desc] (re-matches #"V(\d+)__(.*)\.sql" filename)]
    (str/replace desc "_" " ")))

(defn pending-migrations
  "Migration filenames whose version is greater than applied-max, sorted
   ascending by version. Non-versioned names are ignored."
  [applied-max filenames]
  (->> filenames
       (keep (fn [f] (when-let [v (migration-file->version f)] [v f])))
       (filter (fn [[v _]] (> v applied-max)))
       (sort-by first)
       (mapv second)))

;; ---------------------------------------------------------------------------
;; DDL-less application role
;; ---------------------------------------------------------------------------

(defn app-role-sql
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

(defn run-owner-sql!
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

(defn ensure-app-role!
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

(defn history-insert-sql
  "One INSERT into flyway_schema_history matching Flyway's row shape. Wrapped
   with the migration body in a single transaction by the caller."
  [{:keys [schema rank version description script checksum owner-user]}]
  (str "INSERT INTO " schema ".flyway_schema_history "
       "(installed_rank, version, description, type, script, checksum, "
       "installed_by, installed_on, execution_time, success) VALUES ("
       rank ", '" version "', '" description "', 'SQL', '" script "', "
       checksum ", '" owner-user "', now(), 0, true);"))

(defn shared-applied-max
  "Max applied version and max installed_rank in the shared history. Returns
   {:version 0 :rank 0} for an empty/baseline-free history."
  [{:keys [port db-name owner-user schema]}]
  (let [bin-dir (pg/find-pg-bin-dir)
        ;; max(installed_rank) must scan ALL rows — a version-regex filter here
        ;; would drop NULL/non-numeric versions (e.g. a repeatable R__ row) from
        ;; the rank max, undercounting the next installed_rank and colliding on
        ;; its PK. The regex belongs only on the version::bigint cast.
        ;;
        ;; bigint, not int: brian's timestamp-versioned migrations (e.g.
        ;; V20260708212543__…) overflow int4 (max ~2.1e9). An ::int cast here
        ;; ERRORs mid-query; the error is swallowed (:continue true) so the fn
        ;; falls back to {:version 0}, and the advance loop then re-applies
        ;; every migration from V1__baseline.sql onto a live schema → "schema
        ;; already exists". bigint holds a 14-digit timestamp comfortably.
        q (str "select "
               "coalesce((select max(version::bigint) from " schema ".flyway_schema_history"
               " where version ~ '^[0-9]+$'), 0), "
               "coalesce((select max(installed_rank) from " schema ".flyway_schema_history), 0);")
        result (shell {:continue true :out :string :err :string}
                      (pg/pg-cmd bin-dir "psql")
                      "-h" "127.0.0.1" "-p" (str port) "-U" owner-user "-d" db-name
                      "-At" "-F" "|" "-c" q)
        [v r] (some-> (:out result) str/trim (str/split #"\|"))]
    {:version (or (some-> v str/trim parse-long) 0)
     :rank    (or (some-> r str/trim parse-long) 0)}))

(def ^:private migrations-subdir "resources/db/migrations")

(defn list-main-migration-files
  "Basenames of main@origin's migration files, via jj — one cheap subprocess,
   no per-file `file show`. Fails loud on jj error. (The dir is flat: every
   file lives directly under resources/db/migrations.)"
  [source-repo]
  (let [res (shell {:continue true :out :string :err :string}
                   "jj" "--ignore-working-copy" "-R" source-repo
                   "file" "list" "-r" "main@origin" (str "root:" migrations-subdir))]
    (when-not (zero? (:exit res))
      (throw (ex-info "jj file list (main@origin migrations) failed"
                      {:error (:err res) :source-repo source-repo})))
    (->> (str/split-lines (:out res))
         (map str/trim) (remove str/blank?)
         (filter #(str/ends-with? % ".sql"))
         (mapv (comp str fs/file-name)))))

(defn materialize-one!
  "Write one main@origin migration file (by basename) into dest-dir via jj.
   Fails loud on jj error. UTF-8 pinned on the write so the checksum round-trip
   is charset-independent."
  [source-repo dest-dir filename]
  (let [show (shell {:continue true :out :string :err :string}
                    "jj" "--ignore-working-copy" "-R" source-repo
                    "file" "show" "-r" "main@origin"
                    (str "root:" migrations-subdir "/" filename))]
    (when-not (zero? (:exit show))
      (throw (ex-info "jj file show failed" {:file filename :error (:err show)})))
    (spit (str (fs/path dest-dir filename)) (:out show) :encoding "UTF-8")
    filename))

(defn advance-shared-to-main!
  "Advance the shared cluster to main@origin by applying pending migrations as
   the owner. No-op when already current — nothing is materialized or shelled
   out to jj/psql in that case. Returns the count applied."
  [{:keys [port db-name owner-user schema source-repo] :as opts}]
  (let [{:keys [version rank]} (shared-applied-max opts)
        basenames (list-main-migration-files source-repo)
        pending   (pending-migrations version basenames)]
    (if (empty? pending)
      0
      (let [tmp (str (fs/create-temp-dir {:prefix "nido-shared-advance"}))]
        ;; Only reached (and only removed) when there IS pending work.
        (try
          (core/log-step (str "Advancing shared cluster to main@origin: applying "
                              (count pending) " migration(s) from V" version))
          (loop [[f & more] pending, rank (inc rank), applied 0]
            (if-not f
              applied
              (let [_         (materialize-one! source-repo tmp f)
                    file-path (str (fs/path tmp f))
                    checksum  (pg/flyway-checksum file-path)
                    body      (slurp file-path)
                    combined  (str "SET search_path TO " schema ", public;\n"
                                   body "\n"
                                   (history-insert-sql
                                    {:schema schema :rank rank
                                     :version (migration-file->version f)
                                     :description (migration-file->description f)
                                     :script f :checksum checksum :owner-user owner-user}))
                    bin-dir   (pg/find-pg-bin-dir)
                    res       (shell {:continue true :out :string :err :string}
                                     (pg/pg-cmd bin-dir "psql")
                                     "-h" "127.0.0.1" "-p" (str port) "-U" owner-user "-d" db-name
                                     "--single-transaction" "-v" "ON_ERROR_STOP=1"
                                     "-c" combined)]
                (when-not (zero? (:exit res))
                  (throw (ex-info (str "Advancing shared cluster failed applying " f)
                                  {:file f :error (:err res) :output (:out res)})))
                (recur more (inc rank) (inc applied)))))
          (finally
            (fs/delete-tree tmp)))))))

(defn ensure-ready!
  "Bring the shared cluster up and, when opts opts-in (has :app-user or
   :source-repo), advance it to main@origin and ensure the DDL-less role.
   Returns {:port p}. opts nil/empty ⇒ plain ensure-up!."
  [project-name {:keys [db-name owner-user schema app-user source-repo] :as opts}]
  (let [{:keys [port] :as up} (ensure-up! project-name)]
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
