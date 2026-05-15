(ns tasks.nido-coordinator
  "Bb task entry points for the coordinator daemon.

   Usage:
     bb nido:coordinator:run [:poll-ms <int>]
     bb nido:coordinator:status"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [nido.coordinator.core :as core]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.heartbeat :as heartbeat]
   [nido.coordinator.pid :as pid]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.launchctl :as lc]
   [nido.io :as io]
   [clojure.string :as str]
   [nido.task-args :as task-args]))

(defn run [& args]
  (let [[_ opts] (task-args/split-args args)
        ms       (some-> (:poll-ms opts) str parse-long)]
    (if ms
      (core/run! :poll-ms ms)
      (core/run!))))

(defn status [& _args]
  (let [p          (cstate/status-path)
        h          (halt/read-halt-info)
        pid        (pid/read)
        proc-alive (pid/alive?)]
    (println "Process:     "
             (cond
               (and pid proc-alive) (str "alive (pid " pid ")")
               pid                  (str "stale PID file (pid " pid " is not alive)")
               :else                "not running (no PID file)"))
    (if (fs/exists? p)
      (let [s (io/read-edn p)]
        (println "Coordinator:" (some-> (:status s) name))
        (println "Heartbeat:  " (:heartbeat-at s))
        (println "Slots:      " (:slots-in-use s)))
      (println "Coordinator: no status.edn (never started or already cleaned up)"))
    (when h
      (println "Halted:     " (name (:source h)) "—" (or (:note h) "(no note)"))
      (println "Halted at:  " (:halted-at h)))))

(defn halt
  "bb nido:halt [:note \"...\"] — pauses coordinator; existing Runs get SIGTERM."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (halt/halt! {:source :user :note (some-> (:note opts) str)})
    (println "Coordinator: halted (user). Resume with: bb nido:coordinator:resume")))

(defn resume
  "bb nido:coordinator:resume — clears halted.edn so the daemon picks back up."
  [& _args]
  (halt/resume!)
  (println "Coordinator: resumed (halted.edn removed)."))

(defn up
  "bb nido:coordinator:up [:poll-ms <int>] — spawn the daemon in background.
   Refuses if coordinator.pid points at a live process.

   Output goes to ~/.nido/coordinator/coordinator.log (append).
   The daemon writes its own PID; this task exits as soon as the spawn is set up."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (if (pid/alive?)
      (do (println "Coordinator: already running (pid" (pid/read) "). Use `bb nido:coordinator:down` to stop.")
          (System/exit 1))
      (do
        (cstate/ensure-dirs!)
        (let [log-file  (java.io.File. ^String (cstate/log-path))
              cmd       (cond-> ["bb" "nido:coordinator:run"]
                          (:poll-ms opts) (into [":poll-ms" (str (:poll-ms opts))]))
              ;; Use :append + :out-file so the child's stdout/stderr are
              ;; redirected at the OS level (ProcessBuilder.Redirect.appendTo).
              ;; A plain OutputStream would be pumped by a thread inside this
              ;; bb — which dies the moment we exit, taking the log with it.
              proc      (p/process cmd {:in        ""         ; close stdin
                                        :out       :append
                                        :out-file  log-file
                                        :err       :append
                                        :err-file  log-file
                                        :shutdown  nil})      ; survive bb exit
              child-pid (.pid (:proc proc))]
          ;; Don't wait for the child — leave it detached.
          (println "Coordinator: starting in background (pid" child-pid ")")
          (println "Logs: " (cstate/log-path))
          (println "Stop: bb nido:coordinator:down"))))))

(defn down
  "bb nido:coordinator:down [:force true] — stop the background daemon.
   Default SIGTERM, waits up to 30s for graceful shutdown.
   With :force true, sends SIGKILL immediately (orphans any in-flight agent).
   Also accepts :force? (zsh users: quote it as ':force?')."
  [& args]
  (let [[_ opts]  (task-args/split-args args)
        force?    (or (= true (:force opts)) (= true (:force? opts)))
        pid       (pid/read)]
    (cond
      (nil? pid)
      (do (println "Coordinator: not running (no PID file).") (System/exit 0))

      (not (pid/alive?))
      (do (println "Coordinator: stale PID file (pid" pid "is not alive). Cleaning up.")
          (pid/delete!)
          (heartbeat/write! {:status :stopped :slots-in-use 0})
          (System/exit 0))

      :else
      (let [proc-handle (.get ^java.util.Optional (java.lang.ProcessHandle/of (long pid)))
            signal-name (if force? "SIGKILL" "SIGTERM")]
        (println "Coordinator: sending" signal-name "to pid" pid)
        (if force?
          (.destroyForcibly ^java.lang.ProcessHandle proc-handle)
          (.destroy ^java.lang.ProcessHandle proc-handle))
        ;; Wait up to 30s for the process to exit. Poll every 200ms.
        (let [deadline (+ (System/currentTimeMillis) 30000)]
          (loop []
            (cond
              (not (.isAlive ^java.lang.ProcessHandle proc-handle))
              (do (pid/delete!)   ; defensive — shutdown hook usually does this
                  ;; Idempotent for SIGTERM (the daemon's shutdown hook already
                  ;; wrote :stopped). Load-bearing for SIGKILL, where the hook
                  ;; never ran and status.edn would otherwise still say :running.
                  (heartbeat/write! {:status :stopped :slots-in-use 0})
                  (println "Coordinator: stopped."))

              (> (System/currentTimeMillis) deadline)
              (do (println "Coordinator: did not exit within 30s. Use :force true to SIGKILL.")
                  (System/exit 2))

              :else
              (do (Thread/sleep 200) (recur)))))))))

(defn- which-bb []
  (let [{:keys [exit out]} (p/sh ["which" "bb"])]
    (when (zero? exit) (str/trim out))))

(defn- git-toplevel []
  (let [{:keys [exit out]} (p/sh ["git" "rev-parse" "--show-toplevel"])]
    (when (zero? exit) (str/trim out))))

(defn- java-bin-dir
  "Resolve the directory containing `java` for the plist PATH.
   Prefers $JAVA_HOME/bin (if set + non-empty); otherwise the parent of `which java`.
   Returns nil if neither is available."
  []
  (let [jh (System/getenv "JAVA_HOME")]
    (cond
      (and jh (seq jh))
      (str jh "/bin")

      :else
      (let [{:keys [exit out]} (p/sh ["which" "java"])]
        (when (zero? exit)
          (str (fs/parent (str/trim out))))))))

(defn install
  "bb nido:coordinator:install — write the LaunchAgent plist and start
   the daemon. Auto-starts at every subsequent login."
  [& _args]
  (cond
    (and (pid/alive?) (not (lc/installed?)))
    (do (println "Coordinator: bare daemon already running (pid" (pid/read) "). Run `bb nido:coordinator:down`, then re-install.")
        (System/exit 1))

    (nil? (git-toplevel))
    (do (println "Coordinator: install must be run from inside the nido git checkout.")
        (System/exit 1))

    (nil? (which-bb))
    (do (println "Coordinator: `which bb` failed. Install babashka first.")
        (System/exit 1))

    (nil? (java-bin-dir))
    (do (println "Coordinator: cannot find java. Set $JAVA_HOME or install java on your PATH.")
        (System/exit 1))

    :else
    (let [bb-path  (which-bb)
          nido-dir (git-toplevel)
          log-path (cstate/log-path)
          plist    (lc/render-plist
                    {:bb-path  bb-path
                     :nido-dir nido-dir
                     :log-path log-path
                     :path-env (str (java-bin-dir) ":/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin")})]
      ;; If already loaded (e.g., re-install), bootout first so bootstrap
      ;; picks up the new plist contents.
      (when (lc/loaded?)
        (lc/bootout!))
      (lc/write-plist! plist)
      (let [{:keys [exit err]} (lc/bootstrap!)]
        (if (zero? exit)
          (println "Coordinator: installed. Plist at" (lc/plist-path)
                   "— daemon will auto-start at login.")
          (do (println "Coordinator: launchctl bootstrap failed (exit" exit "). stderr:")
              (println err)
              (System/exit exit)))))))

(defn uninstall
  "bb nido:coordinator:uninstall — bootout and remove the plist. Idempotent."
  [& _args]
  (cond
    (not (lc/installed?))
    (do (println "Coordinator: not installed. Nothing to do.")
        (System/exit 0))

    :else
    (do
      (when (lc/loaded?)
        (lc/bootout!))
      (lc/remove-plist!)
      (println "Coordinator: uninstalled. Run `bb nido:coordinator:up` to start manually."))))

(defn logs
  "bb nido:coordinator:logs [:follow true] [:lines <n>]
   Default: print last 50 lines of coordinator.log and exit.
   :follow true → tail -f (blocks until Ctrl-C).
   Also accepts :follow? (zsh users: quote it as ':follow?')."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        follow?  (or (= true (:follow opts)) (= true (:follow? opts)))
        lines    (or (some-> (:lines opts) str parse-long) 50)
        log      (cstate/log-path)]
    (if-not (fs/exists? log)
      (println "Coordinator log not found:" log)
      (if follow?
        ;; tail -f, blocks until Ctrl-C
        (apply p/exec ["tail" "-f" "-n" (str lines) log])
        ;; one-shot tail -n N
        (apply p/exec ["tail" "-n" (str lines) log])))))
