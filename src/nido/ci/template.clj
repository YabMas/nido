(ns nido.ci.template
  "CI-specific template variant: ~/.nido/templates/<project>--ci/pg-data.

   The dev template (`nido.template`) is meant to mirror production for
   editor sessions — it can be 20+ GB of real-shaped data so REPL work
   feels realistic. That same data turns ordinary cascade DELETEs in
   integration test fixtures into multi-minute scans (brian: a single
   `DELETE FROM course WHERE id = $1` against 9k cascading rows held a
   step at 75% for 8 minutes before we killed it).

   The CI variant fixes that without touching the dev template. It is:
     - APFS-cloned from the dev template (same schema, same migrations
       already applied)
     - TRUNCATEd of every project-schema table (preserving
       `flyway_schema_history` so the test JVM doesn't think it has
       work to do at boot)
     - Topped up with any unapplied migrations (when newer migrations
       exist in the source tree than in `flyway_schema_history`)
     - Configured with project-supplied `postgresql.auto.conf`
       overrides (e.g. brian needs `wal_level = 'logical'` for its
       logical-decoding integration tests)

   Build is triggered automatically by `nido.ci.lifecycle/execute-run!`
   when a Run includes any `:isolated-pg?` step and the CI variant is
   missing. `nido.template/refresh!` invalidates the variant so the next
   CI run picks up new dev-template data."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.core :as core]
   [nido.session.engine :as engine]
   [nido.session.services.postgresql :as pg]
   [nido.session.state :as state]))

(def ^:private socket-base-dir
  "Short Unix-domain socket dir — same as nido.ci.isolated-pg uses, kept
   in lockstep so future macOS sun_path changes have one place to
   update."
  "/tmp/nido-pg-sock")

(defn ci-data-dir
  "Path to the CI variant template's PGDATA. Lives at
   `~/.nido/templates/<project>--ci/pg-data/`."
  [project-name]
  (state/template-pg-data-dir (str project-name "--ci")))

(defn- ci-template-log-path
  [project-name]
  (str (fs/path (state/project-template-dir (str project-name "--ci"))
                "pg.log")))

(defn exists?
  [project-name]
  (fs/exists? (str (fs/path (ci-data-dir project-name) "PG_VERSION"))))

(defn- ensure-stopped!
  [data-dir]
  (when (and (fs/exists? data-dir)
             (not (pg/template-stopped? data-dir)))
    (pg/pg-ctl-stop! data-dir)))

(defn- write-auto-conf-overrides!
  "Append nido-controlled parameter overrides to PGDATA/postgresql.auto.conf.
   Existing ALTER SYSTEM entries are preserved."
  [data-dir overrides]
  (when (seq overrides)
    (let [file (str (fs/path data-dir "postgresql.auto.conf"))
          existing (if (fs/exists? file) (slurp file) "")
          marker "# nido CI template overrides"
          ;; If a previous run already appended overrides, we don't double-write.
          already? (str/includes? existing marker)]
      (when-not already?
        (let [body (str/join "\n"
                             (for [[k v] overrides]
                               (str (name k) " = '" v "'")))]
          (spit file (str existing "\n" marker "\n" body "\n")))))))

(defn- pending-migrations
  "Ordered seq of (V<n>__*.sql files in `migrations-dir` whose numeric
   version is greater than every entry currently in
   `<schema>.flyway_schema_history`. Returns [] when the schema has no
   flyway history (rare — but treat it as 'nothing pending' so we don't
   re-apply the baseline by accident)."
  [{:keys [bin-dir port db-user db-name schema migrations-dir]}]
  (let [{:keys [exit out]}
        (shell {:continue true :out :string :err :string}
               (pg/pg-cmd bin-dir "psql")
               "-h" "127.0.0.1" "-p" (str port) "-U" db-user "-d" db-name "-tA"
               "-c" (str "select coalesce(max(version::int), 0) "
                         "from " schema ".flyway_schema_history "
                         "where version ~ '^[0-9]+$'"))
        max-applied (if (zero? exit)
                      (or (parse-long (str/trim out)) 0)
                      0)]
    (when-not (fs/exists? migrations-dir)
      (throw (ex-info (str "Migrations dir not found: " migrations-dir)
                      {:hint "Set :ci-template :migrations-path in session.edn or ensure project-dir is correct."})))
    (->> (fs/list-dir migrations-dir)
         (map #(str (fs/file-name %)))
         (keep (fn [fname]
                 (when-let [[_ v desc] (re-matches #"^V(\d+)__(.+)\.sql$" fname)]
                   {:version (parse-long v)
                    :description (str/replace desc "_" " ")
                    :file fname
                    :path (str (fs/path migrations-dir fname))})))
         (filter #(> (:version %) max-applied))
         (sort-by :version)
         vec)))

(defn- apply-migration!
  [{:keys [bin-dir port db-user db-name schema next-rank]} {:keys [path version description file]}]
  (core/log-step (str "Applying CI-template migration V" version " (" file ")"))
  (let [{:keys [exit err]} (shell {:continue true :out :string :err :string}
                                  (pg/pg-cmd bin-dir "psql")
                                  "-h" "127.0.0.1" "-p" (str port)
                                  "-U" db-user "-d" db-name
                                  "-v" "ON_ERROR_STOP=1"
                                  "-f" path)]
    (when-not (zero? exit)
      (throw (ex-info (str "Migration " file " failed against CI template")
                      {:file file :err err}))))
  (let [insert-sql (str "INSERT INTO " schema ".flyway_schema_history "
                        "(installed_rank, version, description, type, script, "
                        "checksum, installed_by, installed_on, execution_time, success) "
                        "VALUES (" next-rank ", '" version "', '" description "', "
                        "'SQL', '" file "', 0, '" db-user "', now(), 0, true) "
                        "ON CONFLICT DO NOTHING")
        {:keys [exit err]} (shell {:continue true :out :string :err :string}
                                  (pg/pg-cmd bin-dir "psql")
                                  "-h" "127.0.0.1" "-p" (str port)
                                  "-U" db-user "-d" db-name "-c" insert-sql)]
    (when-not (zero? exit)
      (throw (ex-info "Failed to record CI-template migration in flyway_schema_history"
                      {:file file :err err})))))

(defn- apply-pending-migrations!
  "Bring the CI template's flyway_schema_history up to date with whatever
   the project source has on disk. Idempotent — if nothing is pending
   this is a no-op."
  [{:keys [schema] :as ctx}]
  (let [pending (pending-migrations ctx)]
    (if (empty? pending)
      (core/log-step "CI template: no pending migrations to apply")
      (do
        (core/log-step (str "CI template: " (count pending) " pending migration(s) to apply"))
        (let [next-rank (->> (shell {:continue true :out :string :err :string}
                                    (pg/pg-cmd (:bin-dir ctx) "psql")
                                    "-h" "127.0.0.1" "-p" (str (:port ctx))
                                    "-U" (:db-user ctx) "-d" (:db-name ctx) "-tA"
                                    "-c" (str "select coalesce(max(installed_rank), 0) from "
                                              schema ".flyway_schema_history"))
                             :out
                             str/trim
                             parse-long
                             (or 0))]
          (loop [[m & rest] pending r (inc next-rank)]
            (when m
              (apply-migration! (assoc ctx :next-rank r) m)
              (recur rest (inc r)))))))))

(defn- truncate-schema!
  "TRUNCATE every table in the given schema except flyway_schema_history.
   `RESTART IDENTITY CASCADE` so sequences reset and FKs are honored."
  [{:keys [bin-dir port db-user db-name schema]}]
  (let [list-sql (str "select string_agg(format('%I.%I', schemaname, tablename), ',') "
                      "from pg_tables "
                      "where schemaname = '" schema "' "
                      "and tablename <> 'flyway_schema_history'")
        {:keys [exit out err]} (shell {:continue true :out :string :err :string}
                                      (pg/pg-cmd bin-dir "psql")
                                      "-h" "127.0.0.1" "-p" (str port)
                                      "-U" db-user "-d" db-name "-tA"
                                      "-c" list-sql)
        tables (when (zero? exit) (str/trim out))]
    (when-not (zero? exit)
      (throw (ex-info "Failed to enumerate tables for CI template TRUNCATE"
                      {:err err :schema schema})))
    (if (str/blank? tables)
      (core/log-step (str "CI template: no tables in schema '" schema "' to truncate"))
      (let [n (count (str/split tables #","))
            sql (str "TRUNCATE TABLE " tables " RESTART IDENTITY CASCADE")
            {:keys [exit err]} (shell {:continue true :out :string :err :string}
                                      (pg/pg-cmd bin-dir "psql")
                                      "-h" "127.0.0.1" "-p" (str port)
                                      "-U" db-user "-d" db-name
                                      "-c" sql)]
        (when-not (zero? exit)
          (throw (ex-info "TRUNCATE for CI template failed"
                          {:err err :schema schema})))
        (core/log-step (str "CI template: truncated " n " table(s) in schema '" schema "'"))))))

(defn build!
  "Build the project's CI template variant from scratch. The dev template
   must already be initialized and stopped.

   `migrations-source-dir` is the directory that holds the project's
   `V<n>__*.sql` files. Most callers pass the session's worktree path —
   sessions are usually checked out to a more recent commit than the
   project's registered project directory, and we want migrations
   pending against the *session's* code, not the project root's.

   `migrations-path` (project-relative or absolute) is appended to
   `migrations-source-dir` to find the actual files. Defaults to
   `resources/db/migrations`."
  [{:keys [project-name migrations-source-dir]}]
  (let [session-edn (engine/load-session-edn project-name)
        dev-cfg (or (get-in session-edn [:templates :pg])
                    (throw (ex-info "No :templates :pg config in session.edn"
                                    {:project project-name})))
        ci-cfg (or (:ci-template session-edn) {})
        {:keys [db-name db-user schema]
         :or {db-user "user" db-name "postgres"}} dev-cfg
        {:keys [postgres-config migrations-path]
         :or {migrations-path "resources/db/migrations"}} ci-cfg
        bin-dir (pg/find-pg-bin-dir)
        dev-data-dir (state/template-pg-data-dir project-name)
        ci-data-dir' (ci-data-dir project-name)
        log-path (ci-template-log-path project-name)
        migrations-dir (str (fs/path migrations-source-dir migrations-path))
        port (+ 5500 (rand-int 1500))]
    (when-not (fs/exists? (str (fs/path dev-data-dir "PG_VERSION")))
      (throw (ex-info (str "Dev template not initialized for '" project-name
                           "'. Run `bb nido:template:pg:init :project " project-name
                           "` first.")
                      {:project project-name :dev-data-dir dev-data-dir})))
    (ensure-stopped! dev-data-dir)
    ;; Rebuild from scratch — keeps the operation simple and predictable.
    (when (fs/exists? ci-data-dir')
      (ensure-stopped! ci-data-dir')
      (fs/delete-tree ci-data-dir'))
    (fs/create-dirs (state/project-template-dir (str project-name "--ci")))
    (core/log-step (str "Building CI template variant for " project-name))
    (pg/clone-pgdata! dev-data-dir ci-data-dir')
    (write-auto-conf-overrides! ci-data-dir' postgres-config)
    (fs/create-dirs socket-base-dir)
    (pg/pg-ctl-start! bin-dir ci-data-dir' port log-path socket-base-dir)
    (pg/wait-for-tcp! port)
    (try
      (apply-pending-migrations!
       {:bin-dir bin-dir :port port :db-name db-name
        :db-user db-user :schema schema :migrations-dir migrations-dir})
      (truncate-schema!
       {:bin-dir bin-dir :port port :db-name db-name
        :db-user db-user :schema schema})
      (finally
        (pg/pg-ctl-stop! ci-data-dir')))
    (core/log-step (str "CI template ready at " ci-data-dir'))
    ci-data-dir'))

(defn ensure!
  "Build the CI template variant if it doesn't already exist. Cheap when
   the variant is already on disk (single fs/exists? check). `session`
   supplies the worktree-path for resolving the project's migrations
   directory at the session's checked-out commit (newer migrations
   landed there will be applied to the variant before it's TRUNCATEd)."
  [session]
  (let [project-name (:project-name session)]
    (when-not (exists? project-name)
      (build! {:project-name project-name
               :migrations-source-dir (:worktree-path session)}))))

(defn invalidate!
  "Remove the CI template variant so the next call to `ensure!` rebuilds
   from the (newly refreshed) dev template. Intended to be called from
   `nido.template/refresh!`."
  [project-name]
  (let [data-dir (ci-data-dir project-name)]
    (when (fs/exists? data-dir)
      (ensure-stopped! data-dir)
      (fs/delete-tree data-dir)
      (core/log-step (str "Invalidated CI template variant at " data-dir)))))
