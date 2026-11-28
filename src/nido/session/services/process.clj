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
   `{:outcome :timeout}` once `timeout-ms` of awake time elapses (`proc/awake-ms` —
   time the host spent asleep is not the process's). The two failures are kept
   apart deliberately: a process that died wants its log read, a slow one
   wants a longer :port-timeout-ms, and collapsing both into nil reported
   every startup crash as a timeout."
  [port-file pid timeout-ms]
  (let [deadline (+ (proc/awake-ms) timeout-ms)]
    (loop [next-log-at (proc/awake-ms)]
      (cond
        (not (proc/process-alive? pid)) {:outcome :process-died}
        (> (proc/awake-ms) deadline) {:outcome :timeout}
        :else
        (let [now (proc/awake-ms)
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

(defn- classpath-race?
  "Whether the process logging to `log-path` died building its classpath. tools.deps expands
   dependencies in parallel, and Maven's model validator shares a HashMap across those threads:
   now and then the map corrupts and the JVM exits with `Error building classpath.` before any
   project code runs. The same classpath builds on the next attempt — five brian starts failed
   this way between 2026-10-17 and 2026-11-24, each restored by simply starting again. Any
   classpath error is matched, not only the race's ClassCastException: a dependency that truly
   does not resolve fails the second attempt too, so the retry costs one classpath build."
  [log-path]
  (boolean
   ;; The line opens the log, above a stack trace a tail would keep instead.
   (try (str/includes? (slurp log-path) "Error building classpath.")
        (catch Exception _ false))))

(defn- spawn!
  "Detach `command` in `project-dir`, its output to `log-path`, and return its pid.

   :shutdown nil disables the destroy-tree JVM shutdown hook babashka.process/shell registers
   by default — that hook hangs the bb process at exit when the spawned bash leaves a
   long-running detached descendant (the JVM here is intentionally supposed to outlive bb).

   The shell records $! itself, in the same command that detaches it, so no check made here
   afterwards — exit code, stdout a login profile may have written to — can stand between the
   detach and the record. Until the engine writes session.edn, after every other service is
   up, the pid file is the only thing on disk that names the process."
  [{:keys [command project-dir git-dir log-path pid-path svc-name]}]
  (let [cmd (str "nohup " command " > "
                 (proc/quoted log-path)
                 " 2>&1 & printf %s $! > " (proc/quoted pid-path))
        ;; A runtime run out of its own checkout is told where git is (`checkout/git-dir`).
        result (shell {:continue true :out :string :err :string
                       :dir project-dir :shutdown nil
                       :env (proc/project-shell-env (when git-dir {"GIT_DIR" git-dir}))}
                      "bash" "-lc" cmd)
        pid (recorded-pid pid-path)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Failed to start " (name svc-name))
                      {:error (:err result) :pid-file pid-path})))
    (when-not (pos-int? pid)
      (throw (ex-info (str "Failed to read the pid of " (name svc-name) " from " pid-path)
                      {:output (:out result) :pid-file pid-path})))
    (core/log-step (str "Started " (name svc-name) " process (pid " pid ")"))
    pid))

(defn- start-eager!
  "Start the service's process now, waiting for its port file when it declares one."
  [service-def ctx]
  (let [{:keys [port-file port-timeout-ms]
         svc-name :name
         :or {port-timeout-ms 120000}} service-def
        command (resolve-command service-def)
        project-name (get-in ctx [:session :project-name])
        project-dir (get-in ctx [:session :project-dir])
        instance-id (or (get-in ctx [:session :instance-id]) project-name)
        git-dir (get-in ctx [:session :git-dir])
        log-path (state/log-file instance-id svc-name)
        pid-path (state/pid-file instance-id svc-name)
        pf (when port-file (str (fs/path project-dir port-file)))
        spawn #(spawn! {:command command :project-dir project-dir :git-dir git-dir
                        :log-path log-path :pid-path pid-path :svc-name svc-name})]

    (when (str/blank? command)
      (throw (ex-info ":process service missing :command or :command-template"
                      {:service-name svc-name})))

    (fs/create-dirs (state/log-dir instance-id))

    ;; One process per instance and service: whatever an earlier start left
    ;; running is stopped before this one spawns, since the spawn overwrites the
    ;; only record of it.
    (retire-recorded! pid-path)

    (when pf (fs/delete-if-exists pf))

    (if-not pf
      (let [pid (spawn)]
        {:state {:pid pid :port nil :log-path log-path :pid-file pid-path}
         :context {:pid pid}})
      (let [{:keys [pid port outcome]}
            (loop [attempt 1]
              (let [pid (spawn)
                    r   (assoc (wait-for-port-file! pf pid port-timeout-ms) :pid pid)]
                (if (and (= 1 attempt) (= :process-died (:outcome r)) (classpath-race? log-path))
                  (do (core/log-step (str (name svc-name) " died building its classpath;"
                                          " starting it once more"))
                      (recur 2))
                  r)))]
        (when-not port
          ;; A process that will not stop keeps its record; the
          ;; cause reported is still the boot's, not the stop's.
          (try (retire-recorded! pid-path) (catch Exception _ nil))
          (throw (ex-info (if (= :process-died outcome)
                            (str (name svc-name) " process exited before writing " port-file)
                            (str "Timed out waiting for " port-file))
                          {:timeout-ms port-timeout-ms
                           :outcome outcome
                           :pid pid
                           :log-path log-path
                           :log-tail (proc/log-tail log-path 20)})))
        (core/log-step (str (name svc-name) " available on port " port))
        {:state {:pid pid :port port :log-path log-path :pid-file pid-path}
         :context {:pid pid :port port}}))))

(defn- stop-saved!
  ;; The saved :pid and the pid file can name different processes: a start that
  ;; never reached session.edn overwrites the file while session.edn still holds
  ;; an earlier start's pid. Both are stopped; the file only through the
  ;; ownership check, since nothing but the file vouches for what it names.
  [saved-state]
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

;; ---------------------------------------------------------------------------
;; On demand: the front holds the session's ports and the JVM starts on a first byte
;; ---------------------------------------------------------------------------

(defn- front-pid-file [instance-id svc-name]
  (state/pid-file instance-id (str (name svc-name) "-front")))

(def ^:private front-command-name
  "The name the front runs under. Not `bb`: `clj-nrepl-eval --discover-ports` sends an nREPL op to
   every TCP listener whose command matches `java|clojure|babashka|bb|nrepl`, and every front it
   reached would start its JVM. The session's own front is found through `.nrepl-port`."
  "nido-front")

(defn- front-executable
  "A link named `front-command-name` to the babashka binary running nido, under the nido home, so
   the front's process is named by it; plain `bb` when the binary cannot be told."
  []
  (if-let [bb (some-> (java.lang.ProcessHandle/current) .info .command (.orElse nil))]
    (let [dir  (fs/path (core/nido-home) "bin")
          link (fs/path dir front-command-name)]
      (fs/create-dirs dir)
      ;; Shared by every runtime's start, which no one runtime's lock orders: made under a name
      ;; of its own and renamed into place, so concurrent starts each land a whole link — the same
      ;; one — and none sees another's half-made step.
      (when-not (and (fs/sym-link? link) (= (str (fs/read-link link)) bb))
        (let [tmp (fs/path dir (str front-command-name "." (random-uuid) ".tmp"))]
          (fs/create-sym-link tmp bb)
          (try (fs/move tmp link {:replace-existing true :atomic-move true})
               (finally (fs/delete-if-exists tmp)))))
      (str link))
    "bb"))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  front-config
  "What the front of on-demand :process service `service-def` is told, read from `ctx` at the
   runtime's start: the runtime, the ports it holds and the app ports behind them
   (`engine/pre-allocate-ports`), the JVM's command, socket, log and pid file, and the declared
   idle period."
  [service-def ctx]
  (let [svc-name    (:name service-def)
        instance-id (or (get-in ctx [:session :instance-id]) (get-in ctx [:session :project-name]))
        own         (get ctx (keyword svc-name))]
    {:instance-id     instance-id
     :service         svc-name
     :project-name    (get-in ctx [:session :project-name])
     :project-dir     (str (get-in ctx [:session :project-dir]))
     :git-dir         (get-in ctx [:session :git-dir])
     :nrepl-port      (:port own)
     :apps            (vec (:front-apps own))
     :command         (resolve-command service-def)
     :socket          (state/repl-socket-path instance-id svc-name)
     :socket-args     (or (get-in service-def [:on-demand :socket-args]) ["--socket"])
     :log-path        (state/log-file instance-id svc-name)
     :pid-file        (state/pid-file instance-id svc-name)
     :port-file       (:port-file service-def)
     :port-timeout-ms (or (:port-timeout-ms service-def) 120000)
     :idle-ms         (or (get-in service-def [:on-demand :idle-ms]) (* 30 60 1000))}))

(defn- wait-for-front!
  "Wait until the front answers on every port it holds; throws when it exits first or 60 s pass."
  [pid ports log-path]
  (let [deadline (+ (proc/awake-ms) 60000)]
    (loop []
      (cond
        (every? proc/tcp-open? ports) :up
        (not (proc/process-alive? pid))
        (throw (ex-info "The on-demand front exited before it held the session's ports"
                        {:pid pid :ports ports :log-path log-path
                         :log-tail (proc/log-tail log-path 20)}))
        (> (proc/awake-ms) deadline)
        (throw (ex-info "Timed out waiting for the on-demand front to hold the session's ports"
                        {:pid pid :ports ports :log-path log-path
                         :log-tail (proc/log-tail log-path 20)}))
        :else (do (Thread/sleep 100) (recur))))))

(defn- start-front!
  "Start the front of on-demand :process service `service-def` and no JVM: the front holds the
   session's nREPL and app ports and starts the JVM when a client first sends it a byte."
  [service-def ctx]
  (let [{svc-name :name} service-def
        cfg       (front-config service-def ctx)
        {:keys [instance-id nrepl-port apps]} cfg
        cfg-path  (state/front-config-file instance-id svc-name)
        pid-path  (front-pid-file instance-id svc-name)
        log-path  (state/log-file instance-id (str (name svc-name) "-front"))
        nido-dir  (str (core/nido-source-dir))]
    (when (str/blank? (:command cfg))
      (throw (ex-info ":process service missing :command or :command-template"
                      {:service-name svc-name})))
    (when-not (pos-int? nrepl-port)
      (throw (ex-info "No nREPL port was allocated for the on-demand front" {:service-name svc-name})))
    (fs/create-dirs (state/log-dir instance-id))
    ;; Whatever an earlier start left — a JVM or a front — is stopped first.
    (retire-recorded! (:pid-file cfg))
    (retire-recorded! pid-path)
    (io/write-edn! cfg-path cfg)
    (let [pid (spawn! {:command (str/join " " (map proc/quoted
                                                   [(front-executable) "--config" (str nido-dir "/bb.edn")
                                                    "nido:session:front" instance-id (name svc-name)]))
                       :project-dir nido-dir :log-path log-path :pid-path pid-path
                       :svc-name (keyword (str (name svc-name) "-front"))})]
      (try (wait-for-front! pid (cons nrepl-port (map :port apps)) log-path)
           (catch Exception e
             (try (retire-recorded! pid-path) (catch Exception _ nil))
             (fs/delete-if-exists cfg-path)
             (throw e)))
      (core/log-step (str (name svc-name) " front on port " nrepl-port
                          " — the JVM starts on its first call"))
      {:state   {:pid pid :port nrepl-port :log-path log-path :pid-file pid-path
                 :on-demand? true :front-config cfg-path :socket (:socket cfg)
                 :jvm-pid-file (:pid-file cfg)}
       :context {:pid nil :port nrepl-port :socket (:socket cfg) :on-demand true}})))

(defn- wait-for-socket!
  "Poll until the nREPL answers on filesystem socket `path`: `{:ready true}`, or the same
   `{:outcome :process-died}` / `{:outcome :timeout}` as `wait-for-port-file!`."
  [path pid timeout-ms]
  (let [deadline (+ (proc/awake-ms) timeout-ms)
        answers? #(try (with-open [_ (java.nio.channels.SocketChannel/open
                                      (java.net.UnixDomainSocketAddress/of (str path)))]
                         true)
                       (catch Exception _ false))]
    (loop []
      (cond
        (not (proc/process-alive? pid)) {:outcome :process-died}
        (> (proc/awake-ms) deadline)    {:outcome :timeout}
        (and (fs/exists? path) (answers?)) {:ready true}
        :else (do (Thread/sleep 100) (recur))))))

(defn ^{:malli/schema [:=> [:cat :map] :int]}
  start-jvm!
  "Start an on-demand JVM as front config `cfg` describes, its nREPL on the config's filesystem
   socket, and return its pid once that socket answers; `(:on-spawn cfg)`, when given, is called
   with each pid as soon as it is spawned. Throws, the JVM stopped, when it exits
   first or the declared :port-timeout-ms passes; a JVM that died building its classpath is started
   once more, as an eager start does."
  [{:keys [command socket socket-args project-dir git-dir log-path pid-file port-timeout-ms service
           on-spawn]}]
  (retire-recorded! pid-file)
  (fs/delete-if-exists socket)
  (let [command (str command " " (str/join " " (map proc/quoted (conj (vec socket-args) socket))))
        ;; `on-spawn` hears each pid the moment its process exists — before it answers, while it
        ;; already holds memory — so what records it is never behind the process.
        spawn   #(let [pid (spawn! {:command command :project-dir project-dir :git-dir git-dir
                                    :log-path log-path :pid-path pid-file :svc-name service})]
                   (when on-spawn (on-spawn pid))
                   pid)
        {:keys [pid ready outcome]}
        (loop [attempt 1]
          (let [pid (spawn)
                r   (assoc (wait-for-socket! socket pid port-timeout-ms) :pid pid)]
            (if (and (= 1 attempt) (= :process-died (:outcome r)) (classpath-race? log-path))
              (do (core/log-step (str (name service) " died building its classpath; starting it once more"))
                  (recur 2))
              r)))]
    (when-not ready
      (try (retire-recorded! pid-file) (catch Exception _ nil))
      (throw (ex-info (if (= :process-died outcome)
                        (str (name service) " process exited before its nREPL answered")
                        (str "Timed out waiting for " (name service) "'s nREPL"))
                      {:timeout-ms port-timeout-ms :outcome outcome :pid pid
                       :log-path log-path :log-tail (proc/log-tail log-path 20)})))
    (core/log-step (str (name service) " JVM answers on " socket " (pid " pid ")"))
    pid))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  stop-jvm!
  "Stop the on-demand JVM front config `cfg` names, if one runs, and remove its socket."
  [{:keys [pid-file socket]}]
  (when-let [pid (recorded-pid pid-file)]
    (when (= :ours (recorded-owner pid pid-file))
      (core/log-step (str "Stopping the on-demand JVM (pid " pid ")"))
      (proc/stop-process! pid)))
  (retire-recorded! pid-file)
  (fs/delete-if-exists socket))

(defn- forget-port-file!
  "Remove the `.nrepl-port` the front wrote, when it still names `port`."
  [project-dir port-file port]
  (when (and project-dir port-file port)
    (let [f (fs/path project-dir port-file)]
      (when (= (str port) (try (str/trim (slurp (str f))) (catch Exception _ nil)))
        (fs/delete-if-exists f)))))

(defn- stop-front!
  "Stop an on-demand runtime's front and its JVM, if one runs: the front first, so nothing starts
   a JVM after this has stopped it."
  [service-def saved-state]
  (let [cfg (some-> (:front-config saved-state) io/read-edn)]
    (some-> (:front-config saved-state) fs/delete-if-exists)
    (stop-saved! (select-keys saved-state [:pid :pid-file]))
    (stop-jvm! {:pid-file (or (:jvm-pid-file saved-state) (:pid-file cfg))
                :socket   (or (:socket saved-state) (:socket cfg) "")})
    (forget-port-file! (:project-dir cfg) (or (:port-file service-def) ".nrepl-port") (:port saved-state))))

(defmethod service/start-service! :process
  [service-def ctx _opts]
  (if (:on-demand service-def)
    (start-front! service-def ctx)
    (start-eager! service-def ctx)))

(defmethod service/stop-service! :process
  [service-def saved-state]
  (if (:on-demand? saved-state)
    (stop-front! service-def saved-state)
    (stop-saved! saved-state)))

(defmethod service/stop-recorded! :process
  [service-def instance-id]
  (when (:on-demand service-def)
    (fs/delete-if-exists (state/front-config-file instance-id (:name service-def)))
    (retire-recorded! (front-pid-file instance-id (:name service-def)))
    (fs/delete-if-exists (state/repl-socket-path instance-id (:name service-def))))
  (retire-recorded! (state/pid-file instance-id (:name service-def))))

(defmethod service/service-status :process
  [_service-def saved-state]
  (let [{:keys [pid port]} saved-state]
    {:alive? (and (pos-int? pid) (proc/process-alive? pid))
     :listening? (and (pos-int? port) (proc/tcp-open? port))
     :port port
     :pid pid}))
