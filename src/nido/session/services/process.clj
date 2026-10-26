(ns nido.session.services.process
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.process :as proc]
   [nido.session.service :as service]
   [nido.session.state :as state]))

(defn- wait-for-port-file!
  "Poll `port-file` until it holds a positive port. Returns `{:port n}` on
   success, `{:outcome :process-died}` when `pid` vanishes first, or
   `{:outcome :timeout}` once `timeout-ms` elapses. The two failures are kept
   apart deliberately: a process that died wants its log read, a slow one
   wants a longer :port-timeout-ms, and collapsing both into nil reported
   every startup crash as a timeout."
  [port-file pid timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [next-log-at (System/currentTimeMillis)]
      (cond
        (not (proc/process-alive? pid)) {:outcome :process-died}
        (> (System/currentTimeMillis) deadline) {:outcome :timeout}
        :else
        (let [now (System/currentTimeMillis)
              _ (when (>= now next-log-at)
                  (core/log-step "Waiting for port file...")
                  nil)
              port (try
                     (some-> (io/read-edn port-file)
                             str
                             str/trim
                             parse-long)
                     (catch Exception _ nil))]
          (if (pos-int? port)
            {:port port}
            (do
              (Thread/sleep 250)
              (recur (if (>= now next-log-at)
                       (+ now 5000)
                       next-log-at)))))))))

(defn- resolve-command
  "Return the shell command string this process service should exec.
   Prefers :command-template (vector of pre-substituted tokens;
   blank/nil tokens dropped) over :command (literal string). Tokens may
   still contain whitespace — they are joined with single spaces; quoting
   is the author's responsibility."
  [service-def]
  (if-let [tmpl (:command-template service-def)]
    (str/join " " (remove (fn [t] (or (nil? t) (and (string? t) (str/blank? t)))) tmpl))
    (:command service-def)))

(defn- recorded-pid
  "The pid `pid-path` holds, or nil when there is no file or it holds no pid."
  [pid-path]
  (when (fs/exists? pid-path)
    (try (some-> (slurp pid-path) str/trim parse-long)
         (catch Exception _ nil))))

(defn- recorded-owner
  "Whose process the pid in `pid-path` names now: :gone when nothing runs under
   it, :ours when the running process started no later than the file was
   written — the spawn always precedes the write — :other when it started after
   (the pid was recycled), and :unknown when the OS will not say when it started.
   :unknown is not :other: it is the answer that must not license anything."
  [pid pid-path]
  (let [ph (.orElse (java.lang.ProcessHandle/of pid) nil)]
    (if (or (nil? ph) (not (.isAlive ^java.lang.ProcessHandle ph)))
      :gone
      (if-let [start (.orElse (.startInstant (.info ^java.lang.ProcessHandle ph)) nil)]
        (if (<= (.toEpochMilli ^java.time.Instant start)
                (.toMillis (fs/last-modified-time pid-path)))
          :ours
          :other)
        :unknown))))

(defn- retire-recorded!
  "Stop the process `pid-path` names when it is this instance's, then remove the
   file. The file is the only record of a process a start spawned and never saw
   through to session.edn, so it outlives anything that fails here: a process
   that survives its stop, or one whose ownership cannot be told, throws with the
   file left in place. A recycled pid is left running and only the file goes."
  [pid-path]
  (when-let [pid (recorded-pid pid-path)]
    (case (recorded-owner pid pid-path)
      (:gone :other) nil
      :ours (do (core/log-step (str "Stopping process left by an earlier start (pid " pid ")"))
                (proc/stop-process! pid)
                (when (proc/process-alive? pid)
                  (throw (ex-info (str "Could not stop pid " pid ", which " pid-path " names")
                                  {:pid pid :pid-file pid-path}))))
      :unknown (throw (ex-info (str "Cannot tell whether pid " pid ", which " pid-path
                                    " names, is this instance's process — stop it or"
                                    " remove the file")
                               {:pid pid :pid-file pid-path}))))
  (fs/delete-if-exists pid-path))

(defmethod service/start-service! :process
  [service-def ctx _opts]
  (let [{:keys [port-file port-timeout-ms]
         svc-name :name
         :or {port-timeout-ms 120000}} service-def
        command (resolve-command service-def)
        project-name (get-in ctx [:session :project-name])
        project-dir (get-in ctx [:session :project-dir])
        instance-id (or (get-in ctx [:session :instance-id]) project-name)
        git-dir (get-in ctx [:session :git-dir])
        log-path (state/log-file instance-id svc-name)]

    (when (str/blank? command)
      (throw (ex-info ":process service missing :command or :command-template"
                      {:service-name svc-name})))

    (fs/create-dirs (state/log-dir instance-id))

    ;; One process per instance and service: whatever an earlier start left
    ;; running is stopped before this one spawns, since the spawn overwrites the
    ;; only record of it.
    (retire-recorded! (state/pid-file instance-id svc-name))

    ;; Delete existing port file
    (when port-file
      (fs/delete-if-exists (str (fs/path project-dir port-file))))

    ;; Start background process. :shutdown nil disables the destroy-tree
    ;; JVM shutdown hook babashka.process/shell registers by default — that
    ;; hook hangs the bb process at exit when the spawned bash leaves a
    ;; long-running detached descendant (the JVM here is intentionally
    ;; supposed to outlive bb).
    ;;
    ;; The shell records $! itself, in the same command that detaches it, so no
    ;; check made here afterwards — exit code, stdout a login profile may have
    ;; written to — can stand between the detach and the record. Until the
    ;; engine writes session.edn, after every other service is up, this file is
    ;; the only thing on disk that names the process.
    (let [pid-path (state/pid-file instance-id svc-name)
          cmd (str "nohup " command " > "
                   (proc/quoted log-path)
                   " 2>&1 & printf %s $! > " (proc/quoted pid-path))
          ;; A runtime run out of its own checkout is told where git is (`checkout/git-dir`).
          result (shell (cond-> {:continue true :out :string :err :string
                                 :dir project-dir :shutdown nil}
                          git-dir (assoc :extra-env {"GIT_DIR" git-dir}))
                        "bash" "-lc" cmd)
          pid (recorded-pid pid-path)]
      (when-not (zero? (:exit result))
        (throw (ex-info (str "Failed to start " (name svc-name))
                        {:error (:err result) :pid-file pid-path})))
      (when-not (pos-int? pid)
        (throw (ex-info (str "Failed to read the pid of " (name svc-name) " from " pid-path)
                        {:output (:out result) :pid-file pid-path})))
      (core/log-step (str "Started " (name svc-name) " process (pid " pid ")"))

      ;; Optionally wait for port file
      (let [port (when port-file
                   (let [pf (str (fs/path project-dir port-file))
                         {p :port outcome :outcome} (wait-for-port-file! pf pid port-timeout-ms)]
                     (when-not p
                       ;; A process that will not stop keeps its record; the
                       ;; cause reported is still the boot's, not the stop's.
                       (try (retire-recorded! pid-path) (catch Exception _ nil))
                       (throw (ex-info (if (= :process-died outcome)
                                         (str (name svc-name) " process exited before writing "
                                              port-file)
                                         (str "Timed out waiting for " port-file))
                                       {:timeout-ms port-timeout-ms
                                        :outcome outcome
                                        :pid pid
                                        :log-path log-path
                                        :log-tail (proc/log-tail log-path 20)})))
                     (core/log-step (str (name svc-name) " available on port " p))
                     p))]
        {:state {:pid pid :port port :log-path log-path :pid-file pid-path}
         :context (cond-> {:pid pid}
                    port (assoc :port port))}))))

(defmethod service/stop-service! :process
  ;; The saved :pid and the pid file can name different processes: a start that
  ;; never reached session.edn overwrites the file while session.edn still holds
  ;; an earlier start's pid. Both are stopped; the file only through the
  ;; ownership check, since nothing but the file vouches for what it names.
  [_service-def saved-state]
  (let [{:keys [pid pid-file]} saved-state]
    (when (pos-int? pid)
      (when (proc/process-alive? pid)
        (core/log-step (str "Stopping process (pid " pid ")"))
        (proc/stop-process! pid))
      ;; A survivor keeps its record for the next start or stop to find.
      (when (proc/process-alive? pid)
        (throw (ex-info (str "Process " pid " survived its stop")
                        {:pid pid :pid-file pid-file}))))
    ;; State written before :pid-file existed has none.
    (when pid-file (retire-recorded! pid-file))))

(defmethod service/stop-recorded! :process
  [service-def instance-id]
  (retire-recorded! (state/pid-file instance-id (:name service-def))))

(defmethod service/service-status :process
  [_service-def saved-state]
  (let [{:keys [pid port]} saved-state]
    {:alive? (and (pos-int? pid) (proc/process-alive? pid))
     :listening? (and (pos-int? port) (proc/tcp-open? port))
     :port port
     :pid pid}))
