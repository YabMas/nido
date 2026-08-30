(ns tasks.nido-coordinator
  "Bb task entry points for the coordinator daemon.

   Usage:
     bb nido:coordinator:run [:poll-ms <int>]
     bb nido:coordinator:status"
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [nido.boot.core :as core]
   [nido.coordinator.daemon.halt :as halt]
   [nido.coordinator.daemon.heartbeat :as heartbeat]
   [nido.coordinator.daemon.pid :as pid]
   [nido.coordinator.lane.ship :as ship]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.daemon.launchctl :as lc]
   [nido.coordinator.source.state :as sst]
   [nido.platform.io :as io]
   [nido.ui.server :as ui-server]
   [nido.platform.process :as proc]
   [clojure.string :as str]
   [nido.platform.task-args :as task-args]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  run [& args]
  (let [[_ opts]  (task-args/split-args args)
        ms        (some-> (:poll-ms opts) str parse-long)
        dport     (some-> (:dashboard-port opts) str parse-long)
        no-dash?  (= true (:no-dashboard opts))]
    (apply core/run! (cond-> [:dashboard {:start! ui-server/start!
                                          :stop!  ui-server/stop!}]
                       ms       (into [:poll-ms ms])
                       dport    (into [:dashboard-port dport])
                       no-dash? (into [:no-dashboard true])))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  status [& _args]
  (let [p          (cstate/status-path)
        h          (halt/read-halt-info)
        pid        (pid/read)
        proc-alive (pid/alive?)
        installed  (lc/installed?)
        loaded     (when installed (lc/loaded?))]
    (println "Launchd:     "
             (cond
               (and installed loaded)        "installed (loaded)"
               installed                     "installed (not loaded)"
               :else                         "not installed"))
    (println "Managed by:  " (if installed "launchd" "none"))
    (println "Process:     "
             (cond
               (and pid proc-alive) (str "alive (pid " pid ")")
               pid                  (str "stale PID file (pid " pid " is not alive)")
               :else                "not running (no PID file)"))
    (if (fs/exists? p)
      (let [s (io/read-edn p)]
        (println "Coordinator:" (some-> (:status s) name))
        (println "Heartbeat:  " (:heartbeat-at s))
        (println "Slots:      " (:slots-in-use s))
        (let [{:keys [driving queued blocked]} (ship/merge-lane-summary)]
          (println (format "Merge lane:  %d driving · %d queued · %d blocked" driving queued blocked)))
        (when-let [dport (:dashboard-port s)]
          (println (core/dashboard-status-line dport (proc/tcp-open? dport)))))
      (println "Coordinator: no status.edn (never started or already cleaned up)"))
    (when h
      (println "Halted:     " (name (:source h)) "—" (or (:note h) "(no note)"))
      (println "Halted at:  " (:halted-at h)))
    (let [hashes (sst/list-state-hashes)
          states (map (fn [h] [h (sst/read-state h)]) hashes)
          open   (filter (fn [[_ s]] (= :open (:breaker s))) states)]
      (when (seq hashes)
        (when (seq open)
          (println (format "⚠ %d source breaker(s) OPEN — auto-recovers on cooldown; force now: bb nido:coordinator:source:reset"
                           (count open))))
        (println "Sources:")
        (doseq [[h s] states]
          (println (format "  %s %s  (%s, %s)"
                           (name (or (:type s) :unknown))
                           h
                           (case (:breaker s)
                             :open  (str "breaker OPEN: "
                                         (name (or (-> s :last-poll-result :error) :unknown)))
                             "OK")
                           (or (:last-polled-at s) "never polled"))))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  halt
  "bb nido:halt [:note \"...\"] — pauses coordinator; existing Runs get SIGTERM."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (halt/halt! {:source :user :note (some-> (:note opts) str)})
    (println "Coordinator: halted (user). Resume with: bb nido:coordinator:resume")))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  resume
  "bb nido:coordinator:resume — clears halted.edn so the daemon picks back up."
  [& _args]
  (halt/resume!)
  (println "Coordinator: resumed (halted.edn removed)."))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  up
  "bb nido:coordinator:up [:poll-ms <int>] — start the daemon.
   If the LaunchAgent plist is installed (bb nido:coordinator:install),
   delegates to launchctl. Otherwise spawns a bare background daemon
   that writes a PID file at ~/.nido/coordinator/coordinator.pid."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (cond
      (and (lc/installed?) (lc/loaded?) (pid/alive?))
      (println "Coordinator: already managed by launchd (pid" (pid/read) ").")

      (lc/installed?)
      (let [{:keys [exit err]} (lc/bootstrap!)]
        (if (zero? exit)
          (do (Thread/sleep 500) ; give the daemon a moment to write its PID
              (println "Coordinator: started via launchd (pid" (or (pid/read) "pending") ")."))
          (do (println "Coordinator: launchctl bootstrap failed (exit" exit "). stderr:")
              (println err)
              (System/exit exit))))

      (pid/alive?)
      (do (println "Coordinator: already running (pid" (pid/read) "). Use `bb nido:coordinator:down` to stop.")
          (System/exit 1))

      :else
      ;; Stage 3 bare-spawn path (unchanged).
      (do
        (cstate/ensure-dirs!)
        (let [log-file  (java.io.File. ^String (cstate/log-path))
              cmd       (cond-> ["bb" "nido:coordinator:run"]
                          (:poll-ms opts)        (into [":poll-ms" (str (:poll-ms opts))])
                          (:dashboard-port opts) (into [":dashboard-port" (str (:dashboard-port opts))])
                          (= true (:no-dashboard opts)) (into [":no-dashboard" "true"]))
              proc      (p/process cmd {:in        ""
                                        :out       :append
                                        :out-file  log-file
                                        :err       :append
                                        :err-file  log-file
                                        :shutdown  nil})
              child-pid (.pid (:proc proc))]
          (println "Coordinator: starting in background (pid" child-pid ")")
          (println "Logs: " (cstate/log-path))
          (println "Stop: bb nido:coordinator:down"))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  down
  "bb nido:coordinator:down [:force true] — stop the background daemon.
   If the LaunchAgent plist is installed, runs `launchctl bootout` so
   the daemon stops AND does not respawn until reinstalled or `up`'d.
   Otherwise sends SIGTERM (or SIGKILL with :force true) to the bare daemon.
   Also accepts :force? (zsh users: quote it as ':force?')."
  [& args]
  (let [[_ opts]  (task-args/split-args args)
        force?    (or (= true (:force opts)) (= true (:force? opts)))
        pid       (pid/read)]
    (cond
      (lc/installed?)
      (let [{:keys [exit err]} (lc/bootout!)]
        (if (zero? exit)
          (println "Coordinator: stopped via launchctl bootout.")
          (do (println "Coordinator: launchctl bootout failed (exit" exit "). stderr:")
              (println err)
              (System/exit exit))))

      (nil? pid)
      (do (println "Coordinator: not running (no PID file).") (System/exit 0))

      (not (pid/alive?))
      (do (println "Coordinator: stale PID file (pid" pid "is not alive). Cleaning up.")
          (pid/delete!)
          (heartbeat/write! {:status :stopped :slots-in-use 0})
          (System/exit 0))

      :else
      ;; Stage 3 SIGTERM/SIGKILL path (unchanged).
      (let [proc-handle (.get ^java.util.Optional (java.lang.ProcessHandle/of (long pid)))
            signal-name (if force? "SIGKILL" "SIGTERM")
            grace-ms    (core/shutdown-grace-ms)]
        (println "Coordinator: sending" signal-name "to pid" pid)
        (if force?
          (.destroyForcibly ^java.lang.ProcessHandle proc-handle)
          (.destroy ^java.lang.ProcessHandle proc-handle))
        (let [deadline (+ (System/currentTimeMillis) grace-ms)]
          (loop []
            (cond
              (not (.isAlive ^java.lang.ProcessHandle proc-handle))
              (do (pid/delete!)
                  (heartbeat/write! {:status :stopped :slots-in-use 0})
                  (println "Coordinator: stopped."))

              (> (System/currentTimeMillis) deadline)
              (do (println "Coordinator: did not exit within" grace-ms "ms. Use :force true to SIGKILL.")
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

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  install
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
                     :path-env (str (java-bin-dir)
                                    ":" (System/getProperty "user.home") "/.local/bin"
                                    ":/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin")})]
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

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  uninstall
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

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  restart
  "bb nido:coordinator:restart — restart the daemon via launchctl.
   Errors when the plist is not installed (use down + up instead)."
  [& _args]
  (cond
    (not (lc/installed?))
    (do (println "Coordinator: not installed. Use `bb nido:coordinator:down` then `bb nido:coordinator:up`.")
        (System/exit 1))

    (not (lc/loaded?))
    (let [{:keys [exit err]} (lc/bootstrap!)]
      (if (zero? exit)
        (println "Coordinator: was not loaded; bootstrapped via launchctl.")
        (do (println "Coordinator: launchctl bootstrap failed (exit" exit "). stderr:")
            (println err)
            (System/exit exit))))

    :else
    (let [{:keys [exit err]} (lc/kickstart!)]
      (if (zero? exit)
        (println "Coordinator: restarted via launchctl kickstart -k.")
        (do (println "Coordinator: launchctl kickstart failed (exit" exit "). stderr:")
            (println err)
            (System/exit exit))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  logs
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
