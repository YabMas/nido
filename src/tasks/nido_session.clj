(ns tasks.nido-session
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def codex-home
  (or (System/getenv "CODEX_HOME")
      (str (System/getProperty "user.home") "/.codex")))

(def registry-file (str (fs/path codex-home "nido" "sessions.edn")))
(def legacy-registry-file (str (fs/path codex-home "agent-cockpit" "sessions.edn")))

(def default-repl-command "clojure -M:dev:rad-dev:test:cider/nrepl")
(def default-tailwind-input "src/css/tailwind.css")
(def default-tailwind-output "resources/public/dist/tailwind.css")

(defn- log-step [message]
  (println (str "[nido] " message)))

(defn- now-iso []
  (str (java.time.Instant/now)))

(defn- parse-opts
  "Parse EDN key/value CLI args, e.g. :project-dir \"/path\" :app-port 3901."
  [args]
  (if (empty? args)
    {}
    (try
      (let [parse-arg
            (fn [arg]
              (try
                (edn/read-string arg)
                (catch Exception _
                  arg)))
            values (map parse-arg args)]
        (when (odd? (count values))
          (throw (ex-info "Options must be key/value pairs" {:args args})))
        (apply hash-map values))
      (catch Exception e
        (throw (ex-info "Failed to parse task options"
                        {:args args
                         :error (ex-message e)}))))))

(defn- read-edn-file [path]
  (when (fs/exists? path)
    (edn/read-string (slurp path))))

(defn- write-edn-file! [path data]
  (when-let [parent (fs/parent path)]
    (fs/create-dirs parent))
  (spit path (str (pr-str data) "\n")))

(defn- quoted [s]
  (str "'" (str/replace s "'" "'\"'\"'") "'"))

(defn- normalize-project-dir [raw]
  (-> raw fs/path fs/absolutize fs/normalize str))

(defn- resolve-project-dir [opts]
  (let [raw (or (:project-dir opts) (:dir opts))]
    (when-not raw
      (throw (ex-info "Missing :project-dir"
                      {:hint "Pass :project-dir \"/abs/or/relative/path\"."})))
    (let [project-dir (normalize-project-dir raw)]
      (when-not (fs/exists? project-dir)
        (throw (ex-info "Project directory does not exist" {:project-dir project-dir})))
      project-dir)))

(defn- project-path [project-dir relative]
  (str (fs/path project-dir relative)))

(defn- session-dir [project-dir]
  (project-path project-dir ".codex"))

(defn- session-file [project-dir]
  (project-path project-dir ".codex/session.edn"))

(defn- log-dir [project-dir]
  (project-path project-dir ".codex/logs"))

(defn- repl-log-file [project-dir]
  (project-path project-dir ".codex/logs/nido-session-repl.log"))

(defn- pg-data-dir [project-dir]
  (project-path project-dir ".codex/pg-data"))

(defn- pg-log-file [project-dir]
  (project-path project-dir ".codex/logs/nido-session-pg.log"))

(defn- local-edn-file [project-dir]
  (project-path project-dir "local.edn"))

(defn- nrepl-port-file [project-dir]
  (project-path project-dir ".nrepl-port"))

(defn- read-registry []
  (merge (or (read-edn-file legacy-registry-file) {})
         (or (read-edn-file registry-file) {})))

(defn- write-registry! [registry]
  (write-edn-file! registry-file registry))

(defn- upsert-registry! [project-dir session]
  (write-registry! (assoc (read-registry) project-dir (assoc session :project-dir project-dir))))

(defn- remove-from-registry! [project-dir]
  (write-registry! (dissoc (read-registry) project-dir)))

(defn- git-common-project-root [project-dir]
  (let [result (shell {:continue true :out :string :err :string}
                      "git" "-C" project-dir "rev-parse" "--git-common-dir")]
    (when (zero? (:exit result))
      (some-> (:out result) str/trim fs/parent str))))

(defn- local-root-paths [project-dir]
  (let [deps-path (project-path project-dir "deps.edn")]
    (if-not (fs/exists? deps-path)
      []
      (let [deps-edn (edn/read-string (slurp deps-path))]
        (->> (:deps deps-edn)
             vals
             (keep :local/root)
             distinct)))))

(defn- link-path! [source-path target-path]
  (when-let [parent (fs/parent target-path)]
    (fs/create-dirs parent))
  (let [result (shell {:continue true :out :string :err :string}
                      "ln" "-s" source-path target-path)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Failed to create symlink"
                      {:source source-path
                       :target target-path
                       :error (:err result)})))))

(defn- ensure-local-root-deps! [project-dir]
  (let [source-project-root (git-common-project-root project-dir)
        local-roots (local-root-paths project-dir)
        linked (atom [])
        missing (atom [])]
    (doseq [local-root local-roots
            :let [target-path (str (fs/normalize (fs/path project-dir local-root)))]
            :when (not (fs/exists? target-path))]
      (if source-project-root
        (let [source-path (str (fs/normalize (fs/path source-project-root local-root)))]
          (if (fs/exists? source-path)
            (do
              (link-path! source-path target-path)
              (swap! linked conj {:source source-path :target target-path}))
            (swap! missing conj {:local-root local-root
                                 :source source-path
                                 :target target-path})))
        (swap! missing conj {:local-root local-root :source nil :target target-path})))
    (when (seq @linked)
      (log-step "Linked local deps for this worktree:")
      (doseq [{:keys [source target]} @linked]
        (println "  " target "->" source)))
    (when (seq @missing)
      (throw (ex-info "Missing local/root dependencies for this worktree"
                      {:missing @missing
                       :hint "Ensure sibling repos exist near your git common project root or adjust deps.edn/local overrides."})))))

(defn- ensure-shared-link! [project-dir source-project-root rel-path]
  (when source-project-root
    (let [source-path (str (fs/path source-project-root rel-path))
          target-path (project-path project-dir rel-path)]
      (when (and (fs/exists? source-path)
                 (not (fs/exists? target-path)))
        (link-path! source-path target-path)
        (log-step (str "Linked " rel-path " -> " source-path))))))

(defn- ensure-local-repos-link! [project-dir]
  (ensure-shared-link! project-dir (git-common-project-root project-dir) ".local-repos"))

(defn- ensure-node-modules-link! [project-dir]
  (ensure-shared-link! project-dir (git-common-project-root project-dir) "node_modules"))

(defn- process-alive? [pid]
  (zero? (:exit (shell {:continue true :out :string :err :string}
                       "kill" "-0" (str pid)))))

(defn- stop-process! [pid]
  (when (process-alive? pid)
    (shell {:continue true} "kill" (str pid))
    (loop [attempt 0]
      (cond
        (not (process-alive? pid)) :stopped
        (>= attempt 20) (do (shell {:continue true} "kill" "-9" (str pid)) :killed)
        :else (do (Thread/sleep 100) (recur (inc attempt)))))))

(defn- port-free? [port]
  (try
    (with-open [socket (java.net.ServerSocket.)]
      (.setReuseAddress socket false)
      (.bind socket (java.net.InetSocketAddress. "127.0.0.1" port))
      true)
    (catch Exception _
      false)))

(defn- tcp-open? [port]
  (try
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "127.0.0.1" port) 500)
      true)
    (catch Exception _
      false)))

(defn- deterministic-port [project-dir]
  (let [h (-> project-dir hash long (bit-and 0x7fffffff))]
    (+ 3100 (mod h 2000))))

(defn- find-available-port [preferred-port]
  (loop [port preferred-port
         attempts 0]
    (cond
      (port-free? port) port
      (>= attempts 2000) (throw (ex-info "Could not find free app port" {:preferred-port preferred-port}))
      :else (recur (inc port) (inc attempts)))))

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

(defn- deterministic-pg-port [project-dir]
  (let [h (-> project-dir hash long (bit-and 0x7fffffff))]
    (+ 5500 (mod h 2000))))

(defn- start-pg-instance! [project-dir pg-port]
  (let [bin-dir (find-pg-bin-dir)
        data-dir (pg-data-dir project-dir)
        log-file (pg-log-file project-dir)]
    (fs/create-dirs (log-dir project-dir))

    ;; initdb (skip if already initialized)
    (when-not (fs/exists? (str (fs/path data-dir "PG_VERSION")))
      (log-step "Initializing PostgreSQL data directory...")
      (let [result (shell {:continue true :out :string :err :string}
                          (pg-cmd bin-dir "initdb")
                          "-D" data-dir
                          "--auth" "trust"
                          "--username" "user"
                          "--encoding" "UTF8"
                          "--no-locale")]
        (when-not (zero? (:exit result))
          (throw (ex-info "initdb failed" {:error (:err result) :output (:out result)})))))

    ;; pg_ctl start
    (log-step (str "Starting PostgreSQL on port " pg-port "..."))
    (let [result (shell {:continue true :out :string :err :string}
                        (pg-cmd bin-dir "pg_ctl")
                        "start" "-D" data-dir
                        "-l" log-file
                        "-o" (str "-p " pg-port " -k " data-dir " -h 127.0.0.1")
                        "-w")]
      (when-not (zero? (:exit result))
        (throw (ex-info "pg_ctl start failed" {:error (:err result) :output (:out result)}))))

    ;; Wait for TCP
    (let [deadline (+ (System/currentTimeMillis) 15000)]
      (loop []
        (cond
          (tcp-open? pg-port) true
          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for PostgreSQL to accept connections" {:port pg-port}))
          :else (do (Thread/sleep 250) (recur)))))

    ;; createdb
    (log-step "Creating database 'brian'...")
    (let [result (shell {:continue true :out :string :err :string}
                        (pg-cmd bin-dir "createdb")
                        "-h" "127.0.0.1" "-p" (str pg-port) "-U" "user" "brian")]
      (when-not (zero? (:exit result))
        (throw (ex-info "createdb failed" {:error (:err result) :output (:out result)}))))

    ;; Setup schema and extensions via psql
    (log-step "Setting up brian schema and extensions...")
    (let [psql-cmd (str "CREATE SCHEMA IF NOT EXISTS brian; "
                        "ALTER DATABASE brian SET search_path TO brian, public; "
                        "DO $$ BEGIN CREATE EXTENSION IF NOT EXISTS vector; "
                        "EXCEPTION WHEN OTHERS THEN RAISE NOTICE 'pgvector not available: %', SQLERRM; END $$;")
          result (shell {:continue true :out :string :err :string}
                        (pg-cmd bin-dir "psql")
                        "-h" "127.0.0.1" "-p" (str pg-port) "-U" "user" "-d" "brian"
                        "-c" psql-cmd)]
      (when-not (zero? (:exit result))
        (log-step (str "WARNING: psql schema setup had issues: " (:err result)))))

    ;; Read PG pid from postmaster.pid
    (let [pid-file (str (fs/path data-dir "postmaster.pid"))
          pg-pid (some-> (slurp pid-file) str/split-lines first str/trim parse-long)]
      (log-step (str "PostgreSQL running (pid " pg-pid ", port " pg-port ")"))
      {:pg-port pg-port :pg-pid pg-pid :pg-data-dir data-dir})))

(def ^:private nido-local-edn-marker ";; AUTO-GENERATED by nido — do not edit\n")

(defn- write-local-edn! [project-dir pg-port]
  (let [local-edn (local-edn-file project-dir)
        jdbc-url (str "jdbc:postgresql://localhost:" pg-port "/brian")]
    ;; Backup existing user-managed local.edn
    (when (fs/exists? local-edn)
      (let [content (slurp local-edn)]
        (when-not (str/starts-with? content nido-local-edn-marker)
          (let [backup (str local-edn ".nido-backup")]
            (log-step (str "Backing up existing local.edn to " backup))
            (fs/copy local-edn backup {:replace-existing true})))))
    (log-step (str "Writing local.edn with PG port " pg-port))
    (spit local-edn
          (str nido-local-edn-marker
               (pr-str
                {:postgres/config
                 {:jdbcUrl jdbc-url
                  :username "user"
                  :password "password"}
                 :pg2/config
                 {:host "localhost"
                  :port pg-port
                  :database "brian"
                  :user "user"
                  :password "password"}
                 :com.fulcrologic.rad.database-adapters.sql/databases
                 {:main
                  {:flyway/migrate? true
                   :hikaricp/config
                   {"jdbcUrl" jdbc-url
                    "dataSource.user" "user"
                    "dataSource.password" "password"
                    "driverClassName" "org.postgresql.Driver"}}}})
               "\n"))))

(defn- cleanup-local-edn! [project-dir]
  (let [local-edn (local-edn-file project-dir)
        backup (str local-edn ".nido-backup")]
    (if (fs/exists? backup)
      (do
        (log-step "Restoring original local.edn from backup")
        (fs/move backup local-edn {:replace-existing true}))
      (when (fs/exists? local-edn)
        (let [content (slurp local-edn)]
          (when (str/starts-with? content nido-local-edn-marker)
            (log-step "Removing nido-generated local.edn")
            (fs/delete local-edn)))))))

(defn- stop-pg-instance! [project-dir]
  (let [data-dir (pg-data-dir project-dir)]
    (when (fs/exists? data-dir)
      (log-step "Stopping PostgreSQL...")
      (let [bin-dir (try (find-pg-bin-dir) (catch Exception _ nil))
            stopped? (when bin-dir
                       (zero? (:exit (shell {:continue true :out :string :err :string}
                                            (pg-cmd bin-dir "pg_ctl")
                                            "stop" "-D" data-dir "-m" "fast" "-w"))))]
        (when-not stopped?
          (let [pid-file (str (fs/path data-dir "postmaster.pid"))]
            (when (fs/exists? pid-file)
              (when-let [pid (some-> (slurp pid-file) str/split-lines first str/trim parse-long)]
                (log-step (str "Falling back to kill for PG pid " pid))
                (stop-process! pid)))))
        (log-step "Removing PostgreSQL data directory")
        (fs/delete-tree data-dir)))))

(defn- wait-for-nrepl-port! [project-dir pid timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)
        port-file (nrepl-port-file project-dir)]
    (loop [next-log-at (System/currentTimeMillis)]
      (cond
        (not (process-alive? pid)) nil
        (> (System/currentTimeMillis) deadline) nil
        :else
        (let [now (System/currentTimeMillis)
              _ (when (>= now next-log-at)
                  (log-step "Waiting for nREPL port file...")
                  nil)
              port (try
                     (some-> (read-edn-file port-file)
                             str
                             str/trim
                             parse-long)
                     (catch Exception _ nil))]
          (if (pos-int? port)
            port
            (do
              (Thread/sleep 250)
              (recur (if (>= now next-log-at)
                       (+ now 5000)
                       next-log-at)))))))))

(defn- start-repl-process! [project-dir repl-command]
  (fs/create-dirs (log-dir project-dir))
  (fs/delete-if-exists (nrepl-port-file project-dir))
  (let [cmd (str "nohup " repl-command " > "
                 (quoted (repl-log-file project-dir))
                 " 2>&1 & echo $!")
        result (shell {:continue true :out :string :err :string :dir project-dir} "bash" "-lc" cmd)
        pid (some-> (:out result) str/trim parse-long)]
    (when-not (zero? (:exit result))
      (throw (ex-info "Failed to start REPL process" {:error (:err result)})))
    (when-not (pos-int? pid)
      (throw (ex-info "Failed to parse REPL pid" {:output (:out result)})))
    pid))

(defn- ensure-tailwind-css! [project-dir opts]
  (let [tailwind-input (project-path project-dir (or (:tailwind-input opts) default-tailwind-input))
        tailwind-output (project-path project-dir (or (:tailwind-output opts) default-tailwind-output))]
    (cond
      (:skip-tailwind? opts)
      (log-step "Skipping Tailwind check (:skip-tailwind? true)")

      (not (fs/exists? tailwind-input))
      (log-step "No Tailwind input detected, skipping CSS compile")

      (fs/exists? tailwind-output)
      (log-step (str "Tailwind CSS already present at " tailwind-output))

      :else
      (do
        (log-step "Tailwind CSS not found, compiling once...")
        (let [result (shell {:continue true :out :string :err :string :dir project-dir}
                            "npx" "tailwindcss"
                            "-i" (or (:tailwind-input opts) default-tailwind-input)
                            "-o" (or (:tailwind-output opts) default-tailwind-output))]
          (when-not (zero? (:exit result))
            (log-step (str "WARNING: Tailwind CSS compile failed; continuing startup. "
                           "Run npm install and a CSS build manually if styles are missing.")))
          (when-not (str/blank? (:err result))
            (println (:err result)))
          (when (and (zero? (:exit result))
                     (fs/exists? tailwind-output))
            (log-step (str "Tailwind CSS compiled at " tailwind-output))))))))

(defn- eval-on-repl! [nrepl-port timeout-ms form]
  (let [result (shell {:continue true :out :string :err :string}
                      "clj-nrepl-eval"
                      "-p" (str nrepl-port)
                      "--timeout" (str timeout-ms)
                      form)]
    (when-not (zero? (:exit result))
      (throw (ex-info "nREPL evaluation failed"
                      {:port nrepl-port
                       :form form
                       :error (:err result)
                       :output (:out result)})))
    (:out result)))

(defn- wait-for-app-port! [app-port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [next-log-at (System/currentTimeMillis)]
      (cond
        (tcp-open? app-port) true
        (> (System/currentTimeMillis) deadline) false
        :else
        (let [now (System/currentTimeMillis)]
          (when (>= now next-log-at)
            (log-step (str "Waiting for app on http://localhost:" app-port "...")))
          (Thread/sleep 250)
          (recur (if (>= now next-log-at)
                   (+ now 5000)
                   next-log-at)))))))

(defn- log-tail [path lines]
  (if-not (fs/exists? path)
    ""
    (->> (slurp path)
         str/split-lines
         (take-last lines)
         (str/join "\n"))))

(defn- read-session [project-dir]
  (read-edn-file (session-file project-dir)))

(defn- print-session [session]
  (println "nido session:")
  (println "  project:" (or (:project-dir session) (:cwd session)))
  (println "  worktree:" (:worktree-id session))
  (println "  url:" (:url session))
  (println "  app port:" (:app-port session))
  (println "  nrepl port:" (:nrepl-port session))
  (println "  repl pid:" (:repl-pid session))
  (when (:pg-port session)
    (println "  pg port:" (:pg-port session))
    (println "  pg pid:" (:pg-pid session))
    (println "  pg data dir:" (:pg-data-dir session))))

(defn- default-start-form [app-port]
  (str "(do "
       "(require 'development :reload) "
       "(try "
       "(development/start {:datastar-port " app-port "}) "
       "(catch clojure.lang.ArityException _ "
       "(do "
       "(require '[mount.core :as mount] "
       "         '[brian.fixtures :as fixtures] "
       "         '[brian.server-components.http-server :as legacy-http]) "
       "(mount/with-args {:firebase-optional? true :datastar/port " app-port "} "
       "  (mount/start-without #'legacy-http/http-server)) "
       "(fixtures/upsert-baseline-db-fixtures) "
       "(when-let [f (ns-resolve 'development 'start-watcher!)] (f)) "
       "(when-let [f (ns-resolve 'development 'start-po-watcher!)] (f)))) "
       ":started)"))

(def default-stop-form
  "(do (require 'development :reload) (when-let [f (ns-resolve 'development 'stop)] (f)) :stopped)")

(defn- status-summary [session]
  (let [pid (:repl-pid session)
        app-port (:app-port session)
        pg-pid (:pg-pid session)
        pg-port (:pg-port session)
        repl-alive? (and (pos-int? pid) (process-alive? pid))
        app-listening? (and (pos-int? app-port) (tcp-open? app-port))
        pg-alive? (and (pos-int? pg-pid) (process-alive? pg-pid))
        pg-listening? (and (pos-int? pg-port) (tcp-open? pg-port))]
    (assoc session
           :repl-alive? repl-alive?
           :app-listening? app-listening?
           :pg-alive? pg-alive?
           :pg-listening? pg-listening?)))

(defn- status-for-project [project-dir]
  (if-let [session (read-session project-dir)]
    (let [session (assoc session :project-dir (or (:project-dir session) project-dir))
          summary (status-summary session)
          tailwind-output (project-path project-dir default-tailwind-output)]
      (print-session summary)
      (println "  repl alive:" (:repl-alive? summary))
      (println "  app listening:" (:app-listening? summary))
      (when (:pg-port summary)
        (println "  pg alive:" (:pg-alive? summary))
        (println "  pg listening:" (:pg-listening? summary)))
      (println "  tailwind css present:" (fs/exists? tailwind-output))
      (println "  session file:" (session-file project-dir)))
    (println "No nido session file found at" (session-file project-dir))))

(defn start
  "Start an isolated app + nREPL session for a project worktree.

   Required args:
     :project-dir \"/path/to/worktree\"

   Optional args:
     :app-port <int>
     :pg-port <int>
     :shared-pg? true
     :repl-command \"clojure -M:dev:rad-dev:test:cider/nrepl\"
     :start-form \"(do ... )\"
     :tailwind-input \"src/css/tailwind.css\"
     :tailwind-output \"resources/public/dist/tailwind.css\"
     :skip-tailwind? true"
  [& args]
  (let [opts (parse-opts args)
        project-dir (resolve-project-dir opts)
        project-path (fs/path project-dir)
        hash-suffix (format "%08x" (-> project-dir hash long (bit-and 0xffffffff)))
        worktree-id (format "%s-%s" (fs/file-name project-path) hash-suffix)
        preferred-port (or (:app-port opts) (deterministic-port project-dir))
        app-port (find-available-port preferred-port)
        shared-pg? (:shared-pg? opts)
        existing (read-session project-dir)]
    (log-step (str "Starting worktree session for " project-dir))
    (log-step (str "Selected app port " app-port))

    (if (and existing (process-alive? (:repl-pid existing)))
      (let [existing (assoc existing :project-dir (or (:project-dir existing) project-dir))]
        (upsert-registry! project-dir existing)
        (println "Nido session already running in this project")
        (print-session existing))
      (do
        (log-step "Ensuring local/root dependencies are available in this worktree")
        (ensure-local-root-deps! project-dir)
        (log-step "Ensuring .local-repos link is available")
        (ensure-local-repos-link! project-dir)
        (log-step "Ensuring node_modules link is available")
        (ensure-node-modules-link! project-dir)
        (ensure-tailwind-css! project-dir opts)

        (fs/create-dirs (session-dir project-dir))

        ;; Start per-session PostgreSQL (unless shared-pg?)
        (let [pg-info (when-not shared-pg?
                        (let [preferred-pg-port (or (:pg-port opts) (deterministic-pg-port project-dir))
                              pg-port (find-available-port preferred-pg-port)]
                          (log-step (str "Selected PG port " pg-port))
                          (try
                            (let [info (start-pg-instance! project-dir pg-port)]
                              (write-local-edn! project-dir pg-port)
                              info)
                            (catch Exception e
                              (log-step (str "ERROR: Failed to start PostgreSQL: " (ex-message e)))
                              (try (stop-pg-instance! project-dir) (catch Exception _))
                              (try (cleanup-local-edn! project-dir) (catch Exception _))
                              (throw e)))))]
          (try
            (let [repl-command (or (:repl-command opts) default-repl-command)
                  repl-pid (start-repl-process! project-dir repl-command)
                  _ (log-step (str "Started background REPL process with pid " repl-pid))
                  nrepl-port (or (wait-for-nrepl-port! project-dir repl-pid 120000)
                                 (do
                                   (stop-process! repl-pid)
                                   (throw (ex-info "Timed out waiting for .nrepl-port"
                                                   {:timeout-ms 120000
                                                    :repl-pid repl-pid
                                                    :repl-log (repl-log-file project-dir)
                                                    :repl-log-tail (log-tail (repl-log-file project-dir) 20)}))))
                  _ (log-step (str "nREPL available on port " nrepl-port))
                  start-form (or (:start-form opts) (default-start-form app-port))
                  _ (log-step "Starting development server via nREPL eval")
                  _ (eval-on-repl! nrepl-port 180000 start-form)
                  _ (log-step "Development start call returned, waiting for HTTP port")
                  app-ready? (wait-for-app-port! app-port 45000)
                  session (merge {:project-dir project-dir
                                  :worktree-id worktree-id
                                  :cwd project-dir
                                  :url (str "http://localhost:" app-port)
                                  :app-port app-port
                                  :nrepl-port nrepl-port
                                  :repl-pid repl-pid
                                  :shared-pg? (boolean shared-pg?)
                                  :created-at (now-iso)
                                  :repl-log (repl-log-file project-dir)}
                                 (when pg-info
                                   {:pg-port (:pg-port pg-info)
                                    :pg-pid (:pg-pid pg-info)
                                    :pg-data-dir (:pg-data-dir pg-info)
                                    :pg-log (pg-log-file project-dir)}))]
              (when-not app-ready?
                (println "warning: app port did not open before timeout, session may still be starting"))
              (when app-ready?
                (log-step (str "App is reachable at http://localhost:" app-port)))
              (write-edn-file! (session-file project-dir) session)
              (upsert-registry! project-dir session)
              (print-session session)
              (println "session file:" (session-file project-dir)))
            (catch Exception e
              (when-not shared-pg?
                (log-step "REPL startup failed, cleaning up PostgreSQL...")
                (try (stop-pg-instance! project-dir) (catch Exception _))
                (try (cleanup-local-edn! project-dir) (catch Exception _)))
              (throw e))))))))

(defn status
  "Show status for a project session.

   Usage:
     bb agent:session:status :project-dir \"/path/to/worktree\""
  [& args]
  (let [opts (parse-opts args)
        project-dir (resolve-project-dir opts)]
    (status-for-project project-dir)))

(defn list-sessions
  "List all sessions tracked by the nido registry."
  [& _]
  (let [registry (read-registry)]
    (if (seq registry)
      (doseq [[project-dir session] (sort-by key registry)]
        (let [summary (status-summary session)]
          (println "-")
          (println "  project:" project-dir)
          (println "  url:" (:url summary))
          (println "  app port:" (:app-port summary))
          (println "  nrepl port:" (:nrepl-port summary))
          (println "  repl pid:" (:repl-pid summary))
          (println "  repl alive:" (:repl-alive? summary))
          (println "  app listening:" (:app-listening? summary))
          (when (:pg-port summary)
            (println "  pg port:" (:pg-port summary))
            (println "  pg alive:" (:pg-alive? summary))
            (println "  pg listening:" (:pg-listening? summary)))))
      (println "No sessions tracked in" registry-file))))

(defn stop
  "Stop a project session.

   Usage:
     bb agent:session:stop :project-dir \"/path/to/worktree\""
  [& args]
  (let [opts (parse-opts args)
        project-dir (resolve-project-dir opts)]
    (if-let [session (read-session project-dir)]
      (let [nrepl-port (:nrepl-port session)
            repl-pid (:repl-pid session)
            repl-alive? (and (pos-int? repl-pid) (process-alive? repl-pid))
            stop-form (or (:stop-form opts) default-stop-form)]
        (when (and repl-alive? (pos-int? nrepl-port))
          (try
            (eval-on-repl! nrepl-port 30000 stop-form)
            (catch Exception e
              (println "warning: could not stop app via nREPL:" (ex-message e)))))
        (when repl-alive?
          (stop-process! repl-pid))
        ;; Stop per-session PostgreSQL and clean up local.edn
        (when-not (:shared-pg? session)
          (try (stop-pg-instance! project-dir) (catch Exception e
                 (println "warning: PG cleanup failed:" (ex-message e))))
          (try (cleanup-local-edn! project-dir) (catch Exception e
                 (println "warning: local.edn cleanup failed:" (ex-message e)))))
        (fs/delete-if-exists (session-file project-dir))
        (fs/delete-if-exists (nrepl-port-file project-dir))
        (remove-from-registry! project-dir)
        (println "Stopped nido session for" project-dir))
      (println "No nido session to stop for" project-dir))))
