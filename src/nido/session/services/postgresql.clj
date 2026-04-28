(ns nido.session.services.postgresql
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.core :as core]
   [nido.process :as proc]
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

(defn- flyway-checksum
  "Computes a Flyway-compatible CRC32 checksum over a SQL file.
   Reads line-by-line, converts each line to UTF-8 bytes, updates CRC32."
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

(defn- pid-file-pid
  "Read the pid recorded in postmaster.pid (line 1), or nil if the file is
   missing or unparseable."
  [data-dir]
  (let [pid-file (str (fs/path data-dir "postmaster.pid"))]
    (when (fs/exists? pid-file)
      (some-> (slurp pid-file) str/split-lines first str/trim parse-long))))

(defn pg-running?
  "True iff postmaster.pid is present AND the recorded postmaster pid is
   alive. Distinguishes a real running cluster from a leftover pid file
   from an ungraceful exit (kill -9, OOM, host reboot)."
  [data-dir]
  (boolean
   (when-let [pid (pid-file-pid data-dir)]
     (proc/process-alive? pid))))

(defn clear-stale-pid-file!
  "If postmaster.pid exists but the recorded pid is dead, delete the pid
   file. No-op when the file is absent or the recorded pid is alive — we
   never remove a pid file for a live postmaster."
  [data-dir]
  (when-let [pid (pid-file-pid data-dir)]
    (when-not (proc/process-alive? pid)
      (let [pid-file (str (fs/path data-dir "postmaster.pid"))]
        (core/log-step
         (str "Removing stale postmaster.pid (pid " pid " not running) at "
              pid-file))
        (fs/delete pid-file)))))

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
   (let [result (shell {:continue true :out :string :err :string :shutdown nil}
                       (pg-cmd bin-dir "pg_ctl")
                       "start" "-D" data-dir
                       "-l" log-path
                       "-o" (str "-p " pg-port " -k " socket-dir " -h 127.0.0.1")
                       "-w")]
     (when-not (zero? (:exit result))
       (throw (ex-info "pg_ctl start failed"
                       {:error (:err result) :output (:out result)}))))))

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
;; Workspace (shared) cluster lifecycle
;; ---------------------------------------------------------------------------

(defn ensure-workspace-pg-running!
  "Idempotent: ensure the project's workspace PG cluster exists and is
   running on its configured port. Bootstraps on first call by APFS-cloning
   the template PGDATA (stopped) to the workspace path. Returns
   {:pg-port :pg-pid :pg-data-dir}.

   Precondition for bootstrap: the template PG has been initialized via
   `bb nido:template:pg:init` and is currently stopped. If the template
   is missing, throws with an actionable hint."
  [project-name pg-port]
  (let [bin-dir (find-pg-bin-dir)
        data-dir (state/workspace-pg-data-dir project-name)
        log-path (state/workspace-pg-log-file project-name)
        initialized? (fs/exists? (str (fs/path data-dir "PG_VERSION")))
        running? (pg-running? data-dir)]
    (fs/create-dirs (state/project-workspace-dir project-name))
    (cond
      running?
      (core/log-step
       (str "Workspace PG already running for " project-name
            " on port " pg-port))

      initialized?
      (do (clear-stale-pid-file! data-dir)
          (core/log-step
           (str "Starting existing workspace PG for " project-name))
          (pg-ctl-start! bin-dir data-dir pg-port log-path)
          (wait-for-tcp! pg-port))

      :else
      (do
        (core/log-step
         (str "Bootstrapping workspace PG for " project-name
              " from template clone"))
        (clone-pgdata! (state/template-pg-data-dir project-name) data-dir)
        (pg-ctl-start! bin-dir data-dir pg-port log-path)
        (wait-for-tcp! pg-port)))
    {:pg-port pg-port
     :pg-pid (read-pg-pid data-dir)
     :pg-data-dir data-dir}))

(defn stop-workspace-pg!
  "Stop the project's workspace PG cluster if running. Leaves PGDATA
   on disk so the next start is cheap. If a stale postmaster.pid is
   present (recorded pid is dead), clears it so the next start can
   proceed cleanly."
  [project-name]
  (let [data-dir (state/workspace-pg-data-dir project-name)]
    (when (fs/exists? data-dir)
      (if (pg-running? data-dir)
        (pg-ctl-stop! data-dir)
        (clear-stale-pid-file! data-dir)))))

;; ---------------------------------------------------------------------------
;; Service implementation — dispatches on effective mode (:shared | :isolated)
;; ---------------------------------------------------------------------------

(defn- resolve-pg-mode
  "Mode order: CLI opts :pg-mode > service-def :mode > :shared."
  [service-def opts]
  (or (:pg-mode opts) (:mode service-def) :shared))

(defn- start-shared!
  "Attach a session to the project's workspace PG. Side effects are
   limited to ensuring the workspace cluster is up; no per-session
   PGDATA, no Flyway, no createdb."
  [service-def ctx]
  (let [{:keys [shared-config]} service-def
        {:keys [port db-name db-user db-password flyway-migrate?]
         :or {db-user "user" db-password "password" flyway-migrate? false}}
        shared-config
        project-name (get-in ctx [:session :project-name])]
    (when-not (and port db-name)
      (throw (ex-info ":shared-config must set :port and :db-name"
                      {:shared-config shared-config})))
    (ensure-workspace-pg-running! project-name port)
    (core/log-step
     (str "Session attached to workspace PG on port " port
          " (flyway-migrate? " flyway-migrate? ")"))
    {:state {:mode :shared :pg-port port :project-name project-name}
     :context {:port port :db-name db-name :db-user db-user
               :db-password db-password
               :flyway-migrate? flyway-migrate?}}))

(defn- start-isolated!
  "Spawn a per-session PG cluster. This is the pre-B3a path, unchanged
   in its externally observable behavior; config fields now live under
   :isolated-config on the service def."
  [service-def ctx]
  (let [{:keys [isolated-config]
         :or {isolated-config {}}} service-def
        ;; Legacy: accept isolated fields at top-level of service-def too.
        cfg (merge (select-keys service-def
                                [:db-name :db-user :db-password :schema
                                 :extensions :port-range :clone-from-template
                                 :baseline :flyway-migrate?])
                   isolated-config)
        {:keys [db-name db-user db-password schema extensions port-range
                clone-from-template flyway-migrate?]
         :or {db-user "user" db-password "password"
              port-range [5500 7500]
              flyway-migrate? true}} cfg
        project-name (get-in ctx [:session :project-name])
        project-dir (get-in ctx [:session :project-dir])
        instance-id (or (get-in ctx [:session :instance-id]) project-name)
        [low high] port-range
        preferred-port (proc/deterministic-port project-dir low high)
        pg-port (proc/find-available-port preferred-port (- high low))
        bin-dir (find-pg-bin-dir)
        data-dir (state/pg-data-dir instance-id)
        log-path (state/log-file instance-id :pg)
        already-initialized? (fs/exists? (str (fs/path data-dir "PG_VERSION")))
        cloned? (and clone-from-template (not already-initialized?))]
    (fs/create-dirs (state/log-dir instance-id))
    (if cloned?
      (clone-pgdata! (state/template-pg-data-dir project-name) data-dir)
      (initdb! bin-dir data-dir db-user))
    (pg-ctl-start! bin-dir data-dir pg-port log-path)
    (wait-for-tcp! pg-port)
    (when-not (or cloned? already-initialized?)
      (setup-fresh-database! {:bin-dir bin-dir :pg-port pg-port :db-user db-user
                              :db-name db-name :schema schema :extensions extensions
                              :baseline (:baseline cfg)
                              :project-dir project-dir}))
    (let [pg-pid (read-pg-pid data-dir)]
      (core/log-step (str "PostgreSQL (isolated) running (pid " pg-pid
                          ", port " pg-port
                          (when cloned? ", cloned from template") ")"))
      {:state {:mode :isolated :pg-port pg-port :pg-pid pg-pid
               :pg-data-dir data-dir :instance-id instance-id :cloned? cloned?}
       :context {:port pg-port :db-name db-name :db-user db-user
                 :db-password db-password
                 :flyway-migrate? flyway-migrate?}})))

(defmethod service/start-service! :postgresql
  [service-def ctx opts]
  (case (resolve-pg-mode service-def opts)
    :shared   (start-shared! service-def ctx)
    :isolated (start-isolated! service-def ctx)))

(defmethod service/stop-service! :postgresql
  [_service-def saved-state]
  (case (:mode saved-state :isolated)
    ;; Shared mode: the workspace cluster is owned by the project, not
    ;; by any one session. Session detach is just removing the registry
    ;; entry (done by the engine); nothing to do here.
    :shared nil

    :isolated
    (let [{:keys [pg-data-dir instance-id project-name]} saved-state
          id (or instance-id project-name)
          data-dir (or pg-data-dir
                       (when id (state/pg-data-dir id)))]
      (when (and data-dir (fs/exists? data-dir))
        (pg-ctl-stop! data-dir)
        (core/log-step "Removing PostgreSQL data directory")
        (fs/delete-tree data-dir)))))

(defmethod service/service-status :postgresql
  [_service-def saved-state]
  (let [{:keys [pg-pid pg-port mode]
         :or {mode :isolated}} saved-state]
    (case mode
      :shared
      {:alive? (and (pos-int? pg-port) (proc/tcp-open? pg-port))
       :listening? (and (pos-int? pg-port) (proc/tcp-open? pg-port))
       :port pg-port
       :mode :shared}

      :isolated
      {:alive? (and (pos-int? pg-pid) (proc/process-alive? pg-pid))
       :listening? (and (pos-int? pg-port) (proc/tcp-open? pg-port))
       :port pg-port
       :pid pg-pid
       :mode :isolated})))
