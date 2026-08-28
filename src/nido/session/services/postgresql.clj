(ns nido.session.services.postgresql
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.process :as proc]
   [nido.session.service :as service]
   [nido.session.state :as state])
  (:import
   [java.util.zip CRC32]))

(defn find-pg-bin-dir []
  (let [result (shell {:continue true :out :string :err :string} "which" "initdb")]
    (if (zero? (:exit result))
      (str (fs/parent (str/trim (:out result))))
      (let [candidates (concat
                        (when (fs/exists? "/opt/homebrew/opt")
                          (->> (fs/list-dir "/opt/homebrew/opt")
                               (filter #(str/starts-with? (str (fs/file-name %)) "postgresql"))
                               (map #(str (fs/path % "bin")))))
                        (when (fs/exists? "/usr/local/opt")
                          (->> (fs/list-dir "/usr/local/opt")
                               (filter #(str/starts-with? (str (fs/file-name %)) "postgresql"))
                               (map #(str (fs/path % "bin"))))))
            found (first (filter #(fs/exists? (str (fs/path % "initdb"))) candidates))]
        (when-not found
          (throw (ex-info "PostgreSQL not found. Install with: brew install postgresql"
                          {:hint "Ensure initdb is on PATH or install PostgreSQL via Homebrew."})))
        found))))

(defn pg-cmd [bin-dir cmd]
  (let [full (str (fs/path bin-dir cmd))]
    (if (fs/exists? full) full cmd)))

(def socket-base-dir
  "Short, shared base for Unix-domain sockets. macOS caps `sun_path` at
   103 bytes, so PGDATA paths under `~/.nido/state/<long-instance-id>/
   pg-data/` overflow as soon as you append `.s.PGSQL.<port>`. Pointing
   PG's `-k` at a short directory keeps the socket file under the limit;
   different ports → different socket file names, so concurrent sessions
   and CI clusters coexist here without collision."
  "/tmp/nido-pg-sock")

(defn flyway-checksum
  "Computes a Flyway-compatible CRC32 checksum over a SQL file.
   Reads line-by-line, converts each line to UTF-8 bytes, updates CRC32.
   Public: nido.session.shared-pg reuses it to record history rows when advancing the
   shared cluster, and it must match Flyway's own checksum exactly."
  [file-path]
  (let [crc (CRC32.)
        content (slurp file-path)]
    (doseq [line (str/split-lines content)]
      (let [bytes (.getBytes ^String line "UTF-8")]
        (.update crc bytes 0 (alength bytes))))
    (unchecked-int (.getValue crc))))

(defn- load-baseline!
  "Loads a baseline SQL dump via psql and inserts a Flyway history record."
  [{:keys [bin-dir pg-port db-user db-name schema baseline project-dir]}]
  (let [{:keys [file version description]
         :or {version "1" description "baseline"}} baseline
        sql-path (str (fs/path project-dir file))
        checksum (flyway-checksum sql-path)]
    (core/log-step (str "Loading baseline: " file))
    (let [result (shell {:continue true :out :string :err :string}
                        (pg-cmd bin-dir "psql")
                        "-h" "127.0.0.1" "-p" (str pg-port) "-U" db-user "-d" db-name
                        "-f" sql-path)]
      (when-not (zero? (:exit result))
        (throw (ex-info "Baseline SQL load failed"
                        {:error (:err result) :output (:out result)}))))
    (let [flyway-table (if schema
                         (str schema ".flyway_schema_history")
                         "flyway_schema_history")
          search-path-sql (when schema
                            (str "ALTER DATABASE " db-name
                                 " SET search_path TO " schema ", public; "))
          insert-sql (str "INSERT INTO " flyway-table
                          " (installed_rank, version, description, type, script,"
                          " checksum, installed_by, execution_time, success)"
                          " VALUES (1, '" version "', '" description "', 'SQL',"
                          " 'V" version "__" description ".sql', "
                          checksum ", '" db-user "', 0, true);")
          sql (str search-path-sql insert-sql)
          result (shell {:continue true :out :string :err :string}
                        (pg-cmd bin-dir "psql")
                        "-h" "127.0.0.1" "-p" (str pg-port) "-U" db-user "-d" db-name
                        "-c" sql)]
      (when-not (zero? (:exit result))
        (throw (ex-info "Flyway baseline record insert failed"
                        {:error (:err result) :output (:out result)}))))
    (core/log-step "Baseline loaded and Flyway history record inserted")))

;; ---------------------------------------------------------------------------
;; Cluster lifecycle helpers (reusable: session pg + template pg)
;; ---------------------------------------------------------------------------

(defn initdb!
  "Run initdb on data-dir if not already initialized. Idempotent."
  [bin-dir data-dir db-user]
  (when-not (fs/exists? (str (fs/path data-dir "PG_VERSION")))
    (core/log-step (str "Initializing PostgreSQL data directory at " data-dir))
    (let [result (shell {:continue true :out :string :err :string}
                        (pg-cmd bin-dir "initdb")
                        "-D" data-dir
                        "--auth" "trust"
                        "--username" db-user
                        "--encoding" "UTF8"
                        "--no-locale")]
      (when-not (zero? (:exit result))
        (throw (ex-info "initdb failed" {:error (:err result) :output (:out result)}))))))

(defn template-stopped?
  "A PGDATA dir is considered stopped iff it has no postmaster.pid file.
   pg_ctl removes it on clean shutdown."
  [data-dir]
  (not (fs/exists? (str (fs/path data-dir "postmaster.pid")))))

(defn clone-pgdata!
  "APFS-clone source-data-dir to target-data-dir using `cp -cR`. Source must be
   stopped (no postmaster.pid). Target must not yet exist. The clone is
   essentially free on APFS — blocks are shared until either side mutates."
  [source-data-dir target-data-dir]
  (when-not (fs/exists? (str (fs/path source-data-dir "PG_VERSION")))
    (throw (ex-info "Template PGDATA does not exist or is not initialized"
                    {:source source-data-dir
                     :hint "Run `bb nido:template:pg:init :project <name>` first."})))
  (when-not (template-stopped? source-data-dir)
    (throw (ex-info "Template Postgres is not stopped (postmaster.pid present)"
                    {:source source-data-dir
                     :hint "Stop the template cluster before cloning a worktree from it."})))
  (when (fs/exists? target-data-dir)
    (throw (ex-info "Target PGDATA already exists; refusing to overwrite"
                    {:target target-data-dir})))
  (when-let [parent (fs/parent target-data-dir)]
    (fs/create-dirs parent))
  (core/log-step "Cloning template PGDATA via cp -cR (APFS)")
  (let [result (shell {:continue true :out :string :err :string}
                      "cp" "-cR" source-data-dir target-data-dir)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Failed to clone PGDATA. "
                           "If the template and target are on different APFS volumes "
                           "(or non-APFS), use a single volume under ~/.nido/.")
                      {:source source-data-dir
                       :target target-data-dir
                       :error (:err result)})))))

(defn pg-ctl-start!
  "Start a Postgres cluster on a given port. Overrides port and socket dir via
   `-o` so the cloned cluster's stored postgresql.conf doesn't need editing.

   The optional `socket-dir` (4-arg arity → defaults to `data-dir`)
   controls where PG creates its Unix-domain socket. macOS's `sun_path`
   limit (103 bytes) makes the default impractical for deeply nested
   PGDATAs (e.g. CI step work-dirs under `~/.nido/state/<…>/runs/<…>/
   steps/<…>/work/pg-data/`); pass `/tmp` (or any short writable dir)
   for those callers.

   :shutdown nil disables the destroy-tree JVM shutdown hook
   babashka.process/shell registers by default — pg_ctl exits as soon as
   the postmaster is ready, but that postmaster is meant to outlive bb,
   and the hook hangs the bb process trying to manage it at exit."
  ([bin-dir data-dir pg-port log-path]
   (pg-ctl-start! bin-dir data-dir pg-port log-path data-dir))
  ([bin-dir data-dir pg-port log-path socket-dir]
   (core/log-step (str "Starting PostgreSQL on port " pg-port " (data-dir=" data-dir ")"))
   ;; The `-k` socket dir is a precondition of the flag, not something PG
   ;; creates. macOS reaps idle `/tmp` entries, so a short-lived socket-base-dir
   ;; under /tmp can vanish while a cluster is down — the next start then dies
   ;; with "could not create lock file …: No such file or directory". Ensure it
   ;; here so every caller (session, shared cluster, template) is protected.
   (fs/create-dirs socket-dir)
   ;; LC_ALL / LANG must be set BEFORE pg_ctl forks the postmaster — on macOS
   ;; PG's startup invokes locale-sensitive code that becomes multithreaded
   ;; when the locale is empty, which fails the single-threaded-fork invariant
   ;; ("postmaster became multithreaded during startup"). Coordinator-driven
   ;; spawns from launchd have a minimal env (Stage 4 plist) and trigger this;
   ;; setting locale explicitly here protects every caller regardless of
   ;; ambient env. en_US.UTF-8 is a safe, ubiquitous macOS default.
   (let [result (shell {:continue true :out :string :err :string :shutdown nil
                        :extra-env {"LC_ALL" "en_US.UTF-8"
                                    "LANG"   "en_US.UTF-8"}}
                       (pg-cmd bin-dir "pg_ctl")
                       "start" "-D" data-dir
                       "-l" log-path
                       "-o" (str "-p " pg-port " -k " socket-dir " -h 127.0.0.1")
                       "-w")]
     (when-not (zero? (:exit result))
       ;; pg_ctl writes "could not start server" to stderr but the actual
       ;; FATAL line is in log-path — slurp it so callers see the real reason.
       (let [pg-log (when (fs/exists? log-path)
                      (try (slurp log-path) (catch Exception _ nil)))]
         (throw (ex-info "pg_ctl start failed"
                         {:error    (:err result)
                          :output   (:out result)
                          :log-path log-path
                          :pg-log   pg-log})))))))

(defn wait-for-tcp!
  "Block until the given port accepts TCP connections, or throw on timeout."
  ([pg-port] (wait-for-tcp! pg-port 15000))
  ([pg-port timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (proc/tcp-open? pg-port) true
         (> (System/currentTimeMillis) deadline)
         (throw (ex-info "Timed out waiting for PostgreSQL" {:port pg-port}))
         :else (do (Thread/sleep 250) (recur)))))))

(defn pg-ctl-stop!
  "Stop a Postgres cluster. Returns true on clean shutdown."
  [data-dir]
  (when (and data-dir (fs/exists? data-dir))
    (core/log-step (str "Stopping PostgreSQL at " data-dir))
    (let [bin-dir (try (find-pg-bin-dir) (catch Exception _ nil))
          stopped? (when bin-dir
                     (zero? (:exit (shell {:continue true :out :string :err :string}
                                          (pg-cmd bin-dir "pg_ctl")
                                          "stop" "-D" data-dir "-m" "fast" "-w"))))]
      (when-not stopped?
        (let [pid-file (str (fs/path data-dir "postmaster.pid"))]
          (when (fs/exists? pid-file)
            (when-let [pid (some-> (slurp pid-file) str/split-lines first str/trim parse-long)]
              (core/log-step (str "Falling back to kill for PG pid " pid))
              (proc/stop-process! pid)))))
      (boolean stopped?))))

(defn read-pg-pid
  "Read the postmaster pid from a running cluster's postmaster.pid."
  [data-dir]
  (let [pid-file (str (fs/path data-dir "postmaster.pid"))]
    (when (fs/exists? pid-file)
      (some-> (slurp pid-file) str/split-lines first str/trim parse-long))))

(defn- read-postmaster-pid-file
  "Parse PGDATA/postmaster.pid into {:pid :port}. PG writes a fixed
   line layout: line 1 = pid, line 4 = port. Returns nil if absent
   or unparseable."
  [data-dir]
  (let [pid-file (str (fs/path data-dir "postmaster.pid"))]
    (when (fs/exists? pid-file)
      (try
        (let [lines (str/split-lines (slurp pid-file))
              pid   (some-> (nth lines 0 nil) str/trim parse-long)
              port  (some-> (nth lines 3 nil) str/trim parse-long)]
          (when (and (pos-int? pid) (pos-int? port))
            {:pid pid :port port}))
        (catch Exception _ nil)))))

(defn detect-running-postmaster
  "Inspect PGDATA for a live postmaster. Returns:
     {:status :running :pid :port}    — live postmaster, adopt it
     {:status :stale   :pid-file …}   — pid file present but process dead
     nil                              — no pid file, free to start fresh"
  [data-dir]
  (when-let [{:keys [pid port]} (read-postmaster-pid-file data-dir)]
    (if (proc/process-alive? pid)
      {:status :running :pid pid :port port}
      {:status :stale :pid pid :pid-file (str (fs/path data-dir "postmaster.pid"))})))

(defn dropdb!
  "Drop the named database if it exists. Wraps `dropdb --if-exists`, which
   connects to the maintenance `postgres` DB. Caller must ensure no other
   client is connected to db-name."
  [bin-dir pg-port db-user db-name]
  (core/log-step (str "Dropping database '" db-name "' (if exists)..."))
  (let [result (shell {:continue true :out :string :err :string}
                      (pg-cmd bin-dir "dropdb")
                      "--if-exists"
                      "-h" "127.0.0.1" "-p" (str pg-port) "-U" db-user db-name)]
    (when-not (zero? (:exit result))
      (throw (ex-info "dropdb failed" {:error (:err result) :output (:out result)})))))

(defn setup-fresh-database!
  "After initdb + start, create the application database and apply baseline or
   raw schema/extensions. Skipped when starting from a cloned template."
  [{:keys [bin-dir pg-port db-user db-name schema extensions baseline project-dir]}]
  (core/log-step (str "Creating database '" db-name "'..."))
  (let [result (shell {:continue true :out :string :err :string}
                      (pg-cmd bin-dir "createdb")
                      "-h" "127.0.0.1" "-p" (str pg-port) "-U" db-user db-name)]
    (when-not (zero? (:exit result))
      (throw (ex-info "createdb failed" {:error (:err result) :output (:out result)}))))
  (if baseline
    (load-baseline! {:bin-dir bin-dir :pg-port pg-port :db-user db-user
                     :db-name db-name :schema schema :baseline baseline
                     :project-dir project-dir})
    (when (or schema (seq extensions))
      (core/log-step (str "Setting up schema/extensions for " db-name "..."))
      (let [schema-sql (when schema
                         (str "CREATE SCHEMA IF NOT EXISTS " schema "; "
                              "ALTER DATABASE " db-name " SET search_path TO " schema ", public; "))
            ext-sql (str/join " "
                              (for [ext extensions]
                                (str "DO $$ BEGIN CREATE EXTENSION IF NOT EXISTS " ext "; "
                                     "EXCEPTION WHEN OTHERS THEN RAISE NOTICE '"
                                     ext " not available: %', SQLERRM; END $$;")))
            psql-cmd (str schema-sql ext-sql)
            result (shell {:continue true :out :string :err :string}
                          (pg-cmd bin-dir "psql")
                          "-h" "127.0.0.1" "-p" (str pg-port) "-U" db-user "-d" db-name
                          "-c" psql-cmd)]
        (when-not (zero? (:exit result))
          (core/log-step (str "WARNING: psql schema setup had issues: " (:err result))))))))

;; ---------------------------------------------------------------------------
;; Service implementation — every session gets its own PGDATA, APFS-cloned
;; from the project's template cluster.
;; ---------------------------------------------------------------------------

(defn resolve-pg-mode
  "Resolve the effective PG provisioning mode from a service-def.
     :shared             — connect to the project's shared cluster (no clone).
     :isolated / :clone  — private per-session PGDATA (APFS clone of template).
   Back-compat: :clone-from-template true (no :mode) resolves to :clone."
  [service-def]
  (let [{:keys [mode]} service-def]
    (if (#{:shared :isolated :clone} mode)
      mode
      :clone)))

(defmethod service/start-service! :postgresql
  [service-def ctx _opts]
  (if (= :shared (resolve-pg-mode service-def))
    ;; --- shared mode: no per-session PGDATA; connect to the shared cluster ---
    (let [{:keys [db-name db-user db-password schema app-db-user app-db-password
                  flyway-migrate?]
           :or {db-user "user" db-password "password" flyway-migrate? true}} service-def
          project-name (get-in ctx [:session :project-name])
          project-dir  (get-in ctx [:session :project-dir])
          source-repo  ((requiring-resolve 'nido.session.engine/source-project-root)
                        project-dir)
          {:keys [port]} ((requiring-resolve 'nido.session.shared-pg/ensure-ready!)
                          project-name
                          {:db-name db-name :owner-user db-user :schema schema
                           :app-user app-db-user :source-repo source-repo})
          ;; Sessions connect with the DDL-less app role when configured, so a
          ;; migration attempt fails permission-denied instead of poisoning the
          ;; shared history. Falls back to the owner creds when not configured.
          conn-user (or app-db-user db-user)
          conn-pass (or app-db-password db-password)]
      (core/log-step (str "Using shared cluster for " project-name " (port " port ")"
                          (when app-db-user (str " as " conn-user))))
      {:state {:mode :shared :project-name project-name :pg-port port}
       :context {:port port :db-name db-name :db-user conn-user
                 :db-password conn-pass :flyway-migrate? flyway-migrate?}})
    ;; --- private mode (:clone / :isolated): EXISTING body, unchanged ---
    (let [{:keys [db-name db-user db-password schema extensions port-range
                  clone-from-template flyway-migrate? baseline]
           :or {db-user "user" db-password "password"
                port-range [5500 7500]
                flyway-migrate? true}} service-def
          project-name (get-in ctx [:session :project-name])
          project-dir (get-in ctx [:session :project-dir])
          instance-id (or (get-in ctx [:session :instance-id]) project-name)
          [low high] port-range
          bin-dir (find-pg-bin-dir)
          data-dir (state/pg-data-dir instance-id)
          log-path (state/log-file instance-id :pg)
          already-initialized? (fs/exists? (str (fs/path data-dir "PG_VERSION")))
          ;; A live postmaster from a previous (partially-failed) lifecycle
          ;; still owns the data-dir; pg_ctl will refuse a second start on the
          ;; postmaster.pid lock regardless of port. Adopt it instead of
          ;; bailing out. A stale pid-file (process gone) is removed so the
          ;; fresh start can proceed.
          existing (when already-initialized?
                     (detect-running-postmaster data-dir))
          adopted? (= :running (:status existing))
          pg-port (cond
                    adopted? (:port existing)
                    :else (let [preferred (proc/deterministic-port project-dir low high)]
                            (proc/find-available-port preferred (- high low))))
          cloned? (and clone-from-template (not already-initialized?))]
      (fs/create-dirs (state/log-dir instance-id))
      (fs/create-dirs socket-base-dir)
      (when (= :stale (:status existing))
        (core/log-step (str "Removing stale postmaster.pid (pid "
                            (:pid existing) " not alive)"))
        (fs/delete-if-exists (:pid-file existing)))
      (cond
        adopted?
        (core/log-step (str "Adopting running PostgreSQL (pid " (:pid existing)
                            ", port " pg-port ", data-dir=" data-dir ")"))
        cloned? (clone-pgdata! (state/template-pg-data-dir project-name) data-dir)
        :else (initdb! bin-dir data-dir db-user))
      (when-not adopted?
        (pg-ctl-start! bin-dir data-dir pg-port log-path socket-base-dir))
      (wait-for-tcp! pg-port)
      (when-not (or adopted? cloned? already-initialized?)
        (setup-fresh-database! {:bin-dir bin-dir :pg-port pg-port :db-user db-user
                                :db-name db-name :schema schema :extensions extensions
                                :baseline baseline
                                :project-dir project-dir}))
      (let [pg-pid (read-pg-pid data-dir)]
        (core/log-step (str "PostgreSQL running (pid " pg-pid
                            ", port " pg-port
                            (cond adopted? ", adopted"
                                  cloned?  ", cloned from template"
                                  :else    "")
                            ")"))
        {:state {:pg-port pg-port :pg-pid pg-pid
                 :pg-data-dir data-dir :instance-id instance-id :cloned? cloned?
                 :adopted? adopted?}
         :context {:port pg-port :db-name db-name :db-user db-user
                   :db-password db-password
                   :flyway-migrate? flyway-migrate?}}))))

(defmethod service/stop-service! :postgresql
  [_service-def saved-state]
  (if (= :shared (:mode saved-state))
    (core/log-step "Shared-mode session: leaving shared cluster running.")
    ;; --- private mode: EXISTING stop body, unchanged ---
    ;; Stop the cluster but leave PGDATA on disk. Sessions can be cycled
    ;; (down → up, JVM crash) without losing intra-session
    ;; DB state — re-cloning is reset!'s job, not down!'s. Without this
    ;; restraint, `bb nido:session:down` silently destroys ad-hoc data the
    ;; user pulled into the session (e.g. a one-off staging restore that
    ;; wasn't promoted to the template).
    (let [{:keys [pg-data-dir instance-id project-name]} saved-state
          id (or instance-id project-name)
          data-dir (or pg-data-dir
                       (when id (state/pg-data-dir id)))]
      (when (and data-dir (fs/exists? data-dir))
        (pg-ctl-stop! data-dir)))))

(defmethod service/service-status :postgresql
  [_service-def saved-state]
  (let [{:keys [pg-pid pg-port mode project-name]} saved-state]
    (if (= :shared mode)
      (let [data-dir (state/shared-pg-data-dir project-name)
            running? (and (fs/exists? data-dir)
                          (= :running (:status (detect-running-postmaster data-dir))))]
        {:alive? running? :listening? (and pg-port (proc/tcp-open? pg-port))
         :port pg-port :mode :shared})
      ;; --- private mode: EXISTING status body, unchanged ---
      {:alive? (and (pos-int? pg-pid) (proc/process-alive? pg-pid))
       :listening? (and (pos-int? pg-port) (proc/tcp-open? pg-port))
       :port pg-port
       :pid pg-pid})))
