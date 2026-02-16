(ns nido.session.services.postgresql
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.service :as service]
   [nido.session.state :as state]))

(defn- find-pg-bin-dir []
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

(defn- pg-cmd [bin-dir cmd]
  (let [full (str (fs/path bin-dir cmd))]
    (if (fs/exists? full) full cmd)))

(defmethod service/start-service! :postgresql
  [service-def ctx _opts]
  (let [{:keys [db-name db-user db-password schema extensions port-range]
         :or {db-user "user" db-password "password"
              port-range [5500 7500]}} service-def
        project-name (get-in ctx [:session :project-name])
        project-dir (get-in ctx [:session :project-dir])
        [low high] port-range
        preferred-port (proc/deterministic-port project-dir low high)
        pg-port (proc/find-available-port preferred-port (- high low))
        bin-dir (find-pg-bin-dir)
        data-dir (state/pg-data-dir project-name)
        log-path (state/log-file project-name :pg)]

    (fs/create-dirs (state/log-dir project-name))

    ;; initdb
    (when-not (fs/exists? (str (fs/path data-dir "PG_VERSION")))
      (core/log-step "Initializing PostgreSQL data directory...")
      (let [result (shell {:continue true :out :string :err :string}
                          (pg-cmd bin-dir "initdb")
                          "-D" data-dir
                          "--auth" "trust"
                          "--username" db-user
                          "--encoding" "UTF8"
                          "--no-locale")]
        (when-not (zero? (:exit result))
          (throw (ex-info "initdb failed" {:error (:err result) :output (:out result)})))))

    ;; pg_ctl start
    (core/log-step (str "Starting PostgreSQL on port " pg-port "..."))
    (let [result (shell {:continue true :out :string :err :string}
                        (pg-cmd bin-dir "pg_ctl")
                        "start" "-D" data-dir
                        "-l" log-path
                        "-o" (str "-p " pg-port " -k " data-dir " -h 127.0.0.1")
                        "-w")]
      (when-not (zero? (:exit result))
        (throw (ex-info "pg_ctl start failed" {:error (:err result) :output (:out result)}))))

    ;; Wait for TCP
    (let [deadline (+ (System/currentTimeMillis) 15000)]
      (loop []
        (cond
          (proc/tcp-open? pg-port) true
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for PostgreSQL" {:port pg-port}))
          :else (do (Thread/sleep 250) (recur)))))

    ;; createdb
    (core/log-step (str "Creating database '" db-name "'..."))
    (let [result (shell {:continue true :out :string :err :string}
                        (pg-cmd bin-dir "createdb")
                        "-h" "127.0.0.1" "-p" (str pg-port) "-U" db-user db-name)]
      (when-not (zero? (:exit result))
        (throw (ex-info "createdb failed" {:error (:err result) :output (:out result)}))))

    ;; Schema and extensions via psql
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
          (core/log-step (str "WARNING: psql schema setup had issues: " (:err result))))))

    ;; Read PG pid
    (let [pid-file (str (fs/path data-dir "postmaster.pid"))
          pg-pid (some-> (slurp pid-file) str/split-lines first str/trim parse-long)]
      (core/log-step (str "PostgreSQL running (pid " pg-pid ", port " pg-port ")"))
      {:state {:pg-port pg-port :pg-pid pg-pid :pg-data-dir data-dir
               :project-name project-name}
       :context {:port pg-port :db-name db-name :db-user db-user :db-password db-password}})))

(defmethod service/stop-service! :postgresql
  [_service-def saved-state]
  (let [{:keys [pg-data-dir project-name]} saved-state
        data-dir (or pg-data-dir
                     (when project-name (state/pg-data-dir project-name)))]
    (when (and data-dir (fs/exists? data-dir))
      (core/log-step "Stopping PostgreSQL...")
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
        (core/log-step "Removing PostgreSQL data directory")
        (fs/delete-tree data-dir)))))

(defmethod service/service-status :postgresql
  [_service-def saved-state]
  (let [{:keys [pg-pid pg-port]} saved-state]
    {:alive? (and (pos-int? pg-pid) (proc/process-alive? pg-pid))
     :listening? (and (pos-int? pg-port) (proc/tcp-open? pg-port))
     :port pg-port
     :pid pg-pid}))
