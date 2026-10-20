(ns nido.session.test-pg
  "The databases a session's test runs work against: one disposable Postgres
   cluster per project, a template database in it kept at main@origin, and a
   database per session cloned from that template.

   The cluster runs without fsync, full-page writes or synchronous commit, so a
   crash may cost every database in it; nothing here is worth more than the time
   the project's test fixture takes to rebuild it. That is why it is not the
   shared cluster: durability is cluster-wide, and the shared cluster holds a dev
   database worth keeping.

   Only nido connects to the template. A database cannot be cloned while anyone
   else is connected to it, and a session writing to it would leak into every
   later clone."
  (:refer-clojure :exclude [reset!])
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.process :as proc]
   [nido.session.service :as service]
   [nido.session.services.postgresql :as pg]
   [nido.session.shared-pg :as shared])
  (:import
   [java.util.zip CRC32]))

(def ^:private template-db
  "Never a session database: those all start with the declared stem and end in
   a hash, see `session-db-name`."
  "nido_test_template")

(def ^:private default-max-connections 200)

(defn- cluster-dir [project-name]
  (str (fs/path (core/nido-home) "test-pg" project-name)))

(defn- data-dir [project-name] (str (fs/path (cluster-dir project-name) "pg-data")))
(defn- log-file [project-name] (str (fs/path (cluster-dir project-name) "pg.log")))
(defn- lock-file [project-name] (str (fs/path (cluster-dir project-name) "test-pg.lock")))

(defn- initialized? [project-name]
  (fs/exists? (str (fs/path (data-dir project-name) "PG_VERSION"))))

(defn- with-lock
  "Run (f) holding the project's test-cluster lock. Not reentrant: the public
   functions take it once and call only lock-free helpers inside it."
  [project-name f]
  (fs/create-dirs (cluster-dir project-name))
  (io/with-file-lock (lock-file project-name) f))

;; ---------------------------------------------------------------------------
;; The cluster
;; ---------------------------------------------------------------------------

(def ^:private settings-file
  "Rewritten before every start, so a changed :max-connections or :settings takes
   effect the next time the cluster starts; a running cluster keeps the values it
   started with."
  "nido-test.conf")

(defn- write-settings!
  "settings: the project's own postgresql.conf parameters, name → value, written
   after nido's and so overriding them — what the project's CI database runs
   with (wal_level, timezone, …). A value is written verbatim: quote a string
   the way postgresql.conf wants it."
  [project-name max-connections settings]
  (spit (str (fs/path (data-dir project-name) settings-file))
        (str "fsync = off\n"
             "full_page_writes = off\n"
             "synchronous_commit = off\n"
             "max_connections = " max-connections "\n"
             (apply str (for [[k v] settings] (str (name k) " = " v "\n"))))))

(defn- initdb!
  "Initialize the cluster. A data dir with no PG_VERSION and no live postmaster
   is a half-made one from an interrupted init, and is replaced. The locale is
   fixed here for the cluster's life: a changed :locale takes a `reset!`."
  [bin-dir project-name db-user locale]
  (let [dir (data-dir project-name)]
    (when (and (fs/exists? dir) (not (pg/detect-running-postmaster dir)))
      (fs/delete-tree dir))
    (pg/initdb! bin-dir dir db-user locale)
    (spit (str (fs/path dir "postgresql.conf"))
          (str "\ninclude_if_exists = '" settings-file "'\n")
          :append true)))

(defn- start!
  "Start the cluster, or adopt the one already running over its data dir.
   Never touches the data of a running cluster. Returns its port."
  [bin-dir project-name max-connections settings]
  (let [dir      (data-dir project-name)
        existing (pg/detect-running-postmaster dir)]
    (if (= :running (:status existing))
      (:port existing)
      (let [preferred (proc/deterministic-port (cluster-dir project-name) 5500 7500)
            port      (if (proc/tcp-open? preferred)
                        (proc/find-available-port preferred 200)
                        preferred)]
        (when (= :stale (:status existing))
          (fs/delete-if-exists (:pid-file existing)))
        (write-settings! project-name max-connections settings)
        (pg/pg-ctl-start! bin-dir dir port (log-file project-name) pg/socket-base-dir)
        (pg/wait-for-tcp! port)
        (core/log-step (str "Test cluster for " project-name " running (port " port ")"))
        port))))

(defn- up!
  "The cluster running, initialized first when there is none. Returns its port."
  [project-name {:keys [db-user max-connections settings locale]
                 :or {db-user "user" max-connections default-max-connections}}]
  (let [bin-dir (pg/find-pg-bin-dir)]
    (when-not (initialized? project-name)
      (initdb! bin-dir project-name db-user locale))
    (start! bin-dir project-name max-connections settings)))

(defn- psql! [port db-user db-name sql]
  (let [result (shell {:continue true :out :string :err :string}
                      (pg/pg-cmd (pg/find-pg-bin-dir) "psql")
                      "-h" "127.0.0.1" "-p" (str port) "-U" db-user "-d" db-name
                      "-v" "ON_ERROR_STOP=1" "-At" "-c" sql)]
    (when-not (zero? (:exit result))
      (throw (ex-info "test cluster SQL failed" {:sql sql :error (:err result)})))
    (:out result)))

(defn- database-exists? [port db-user db-name]
  (= "1" (str/trim (psql! port db-user "postgres"
                          (str "select 1 from pg_database where datname = '" db-name "'")))))

;; ---------------------------------------------------------------------------
;; The template
;; ---------------------------------------------------------------------------

(defn- ensure-template!
  "Create the template from the project's baseline when it is missing, then
   advance it to main@origin's migrations. Run under the lock: a clone and an
   advance must never overlap, since a clone refuses a template anyone is
   connected to."
  [port {:keys [db-user schema baseline project-dir source-repo]
         :or {db-user "user"}}]
  (when-not (database-exists? port db-user template-db)
    (core/log-step "Building the test template database from the baseline")
    (pg/setup-fresh-database! {:bin-dir (pg/find-pg-bin-dir) :pg-port port :db-user db-user
                               :db-name template-db :schema schema :baseline baseline
                               :project-dir project-dir}))
  (when source-repo
    (shared/advance-shared-to-main! {:port port :db-name template-db :owner-user db-user
                                     :schema schema :source-repo source-repo})))

;; ---------------------------------------------------------------------------
;; Public
;; ---------------------------------------------------------------------------

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :map]}
  ensure-ready!
  "Bring the project's test cluster up and its template database to
   main@origin, under the project's test lock. Returns {:port p}.

   opts: :db-user, :max-connections, :settings (see `write-settings!`),
   :locale (the cluster's default collation; nil is C), and what the template
   is built from —
   :schema, :baseline and :project-dir as `setup-fresh-database!` takes them,
   and :source-repo, whose main@origin the template follows (nil: no advance)."
  [project-name opts]
  (with-lock project-name
    (fn []
      (let [port (up! project-name opts)]
        (ensure-template! port opts)
        {:port port}))))

(defn ^{:malli/schema [:=> [:cat :string :string] :string]}
  session-db-name
  "The test database name for one session: the stem, a readable slug of the
   instance id, and a hash of the instance id, within Postgres's 63-byte
   identifier limit. The hash is what keeps two sessions apart — instance ids
   that slug alike (`a-b`, `a_b`) or are truncated alike still differ in it."
  [stem instance-id]
  (let [crc  (doto (CRC32.) (.update (.getBytes ^String instance-id "UTF-8")))
        hash (format "%08x" (.getValue crc))
        slug (-> (str/lower-case instance-id)
                 (str/replace #"[^a-z0-9]+" "_")
                 (str/replace #"^_+|_+$" ""))
        room (- 63 (count stem) (count hash) 2)
        slug (subs slug 0 (max 0 (min room (count slug))))]
    (str/join "_" (remove str/blank? [stem slug hash]))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :map]}
  ensure-session-db!
  "Create a session's test database from the template when it does not exist.
   Idempotent: an existing database is left as the session last left it.
   opts: those of `ensure-ready!`, plus :db-stem and :instance-id.
   Returns {:port p :db-name n}."
  [project-name {:keys [db-user schema db-stem instance-id] :or {db-user "user"} :as opts}]
  (let [db-name (session-db-name db-stem instance-id)]
    (with-lock project-name
      (fn []
        (let [port (up! project-name opts)]
          (ensure-template! port opts)
          (when-not (database-exists? port db-user db-name)
            (core/log-step (str "Cloning test database " db-name " from the template"))
            (psql! port db-user "postgres"
                   (str "CREATE DATABASE " db-name " TEMPLATE " template-db))
            ;; A clone does not inherit database-level settings.
            (when schema
              (psql! port db-user "postgres"
                     (str "ALTER DATABASE " db-name " SET search_path TO " schema ", public"))))
          {:port port :db-name db-name})))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :map] :any]}
  drop-session-db!
  "Drop a session's test database if it exists, bringing the test cluster up
   to do so. A project with no test cluster has nothing to drop.
   opts: :db-user, :max-connections, :settings, :locale, :db-stem, :instance-id."
  [project-name {:keys [db-user db-stem instance-id] :or {db-user "user"} :as opts}]
  (when (initialized? project-name)
    (let [db-name (session-db-name db-stem instance-id)]
      (with-lock project-name
        (fn []
          (let [port (up! project-name opts)]
            (core/log-step (str "Dropping test database " db-name " (if exists)"))
            (psql! port db-user "postgres"
                   (str "DROP DATABASE IF EXISTS " db-name " WITH (FORCE)"))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  status
  "Print what state the project's test cluster is in, and which databases it holds."
  [project-name]
  (let [dir      (data-dir project-name)
        running  (when (initialized? project-name) (pg/detect-running-postmaster dir))
        port     (when (= :running (:status running)) (:port running))]
    (println "nido test cluster:")
    (println "  project:" project-name)
    (println "  data-dir:" dir)
    (println "  initialized?:" (initialized? project-name))
    (println "  running?:" (boolean port))
    (when port
      (println "  port:" port)
      (println "  databases:")
      (doseq [db (->> (str/split-lines
                       (psql! port "user" "postgres"
                              "select datname from pg_database where not datistemplate and datname <> 'postgres' order by 1"))
                      (remove str/blank?))]
        (println "   " db (if (= db template-db) "(template)" ""))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  reset!
  "Stop the test cluster and delete it, with every session's test database in
   it. The next session start, or a session's next test run, rebuilds what it
   needs."
  [project-name]
  (with-lock project-name
    (fn []
      (let [dir (data-dir project-name)]
        (when (fs/exists? dir)
          (when (= :running (:status (pg/detect-running-postmaster dir)))
            (pg/pg-ctl-stop! dir))
          (fs/delete-tree dir)
          (core/log-step (str "Deleted the test cluster for " project-name)))))))

;; ---------------------------------------------------------------------------
;; The session service
;; ---------------------------------------------------------------------------

(defn- service-opts [service-def ctx]
  (let [project-dir (get-in ctx [:session :project-dir])]
    (merge (select-keys service-def [:db-user :schema :baseline :max-connections :settings :locale :db-stem])
           {:project-dir project-dir
            :instance-id (or (get-in ctx [:session :instance-id])
                             (get-in ctx [:session :project-name]))
            :source-repo ((requiring-resolve 'nido.session.engine/source-project-root)
                          project-dir)})))

(defn- start-db! [{:keys [db-user db-password] :or {db-user "user" db-password "password"} :as service-def}
                  ctx]
  (let [project-name (get-in ctx [:session :project-name])
        {:keys [port db-name]} (ensure-session-db! project-name (service-opts service-def ctx))]
    {:state {:project-name project-name :port port :db-name db-name}
     :context {:port port :db-name db-name :db-user db-user :db-password db-password}}))

(defmethod service/start-service! :test-pg
  [service-def ctx _opts]
  (start-db! service-def ctx))

(defn ^{:malli/schema [:=> [:cat :map :map :string] :map]}
  member-context
  "The context `service-def` hands a session that shares a runtime it does not run out of: a test
   database of its own, keyed by `session-key` rather than by the runtime, created from the
   template when missing. `ctx` is the runtime's. Sessions sharing a runtime share its dev
   database; the runtime's test database is the one its own REPL reads."
  [service-def ctx session-key]
  (:context (start-db! service-def (assoc-in ctx [:session :instance-id] session-key))))

(defmethod service/stop-service! :test-pg
  [_service-def _saved-state]
  (core/log-step "Leaving the test cluster running; the session keeps its test database."))

(defmethod service/service-status :test-pg
  [_service-def {:keys [project-name port]}]
  (let [running (when project-name (pg/detect-running-postmaster (data-dir project-name)))]
    {:alive? (= :running (:status running))
     :listening? (boolean (and port (proc/tcp-open? port)))
     :port port}))
