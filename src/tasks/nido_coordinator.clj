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
   [nido.coordinator.work :as work]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.daemon.supervisor :as sup]
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

;; The poll's own state says only whether the source is healthy, and a held sweep is
;; healthy: it polls on time and emits nothing, which reads "OK" exactly like a
;; discharged day. A hold whose session died is released by nothing but a close,
;; so on the one surface a person reads, a hold says what holds it and whether
;; anyone is still working on it.
(defn- source-health [s]
  (cond
    (= :open (:breaker s)) (str "breaker OPEN: "
                                (name (or (-> s :last-poll-result :error) :unknown)))
    (:held-by s)           (str "held by " (:held-by s) " at last poll")
    (and (:connection s) (not= :open (-> s :connection :state)))
    (let [{:keys [state since last-connected-at]} (:connection s)]
      (str "not connected (" (name state) ") since " since
           "; last connected " (or last-connected-at "never")))
    :else                  "OK"))

(defn ^{:malli/schema [:=> [:cat [:maybe [:vector :map]]] [:vector :string]]}
  sweep-hold-lines
  "The status lines for what holds each improvement sweep, from
   `work/improvement-holds`; empty when nothing does. A `:stuck` hold names the
   close that releases it, because no session will ever write that close. nil
   (the holds could not be read) is said, not read as no hold."
  [holds]
  (cond
    (nil? holds) ["Improvement sweep: holds could not be read"]
    :else
    (vec (for [{:keys [project ws-id address state started-at ended-at]} holds
               line (case state
                      :stuck
                      [(format "⚠ Improvement sweep (%s) STUCK: %s holds it (claim %s, since %s) and no session is working on it%s"
                               project ws-id address started-at
                               (if ended-at (str " — its last session ended " ended-at) ""))
                       (format "    nothing will release it but a close: bb nido:workstream:close :project %s :ws-id %s :outcome vetoed"
                               project ws-id)]
                      :waiting-on-you
                      [(format "Improvement sweep (%s): held by %s (claim %s) — its session is parked at a gate for you"
                               project ws-id address)]
                      [(format "Improvement sweep (%s): held by %s (claim %s) — a session is working on it"
                               project ws-id address)])]
           line))))

(defn- held-lines
  "The status lines for `held` — the sessions the daemon last saw waiting idle at their prompt,
   owed something it does not give them, past its bound — each with how long it has waited at
   `now-ms`. Nothing when there are none."
  [held now-ms]
  (when (seq held)
    (cons (format "⚠ %d session(s) owed work, idle at the prompt and not given it:" (count held))
          (for [{:keys [project ws-id session since]} held
                :let [at   (try (.toEpochMilli (java.time.Instant/parse (str since))) (catch Exception _ nil))
                      mins (when at (quot (- now-ms at) 60000))]]
            (str "  Held:      " (name project) "/" ws-id " " session
                 (if mins (str " — waiting " mins "m, since " since) " — since an unknown time"))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  status [& _args]
  (let [p          (cstate/status-path)
        h          (halt/read-halt-info)
        pid        (pid/read)
        proc-alive (pid/alive?)
        {:keys [manager installed? running?]} (sup/status)]
    (println "Supervisor:  "
             (str (name manager) ": "
                  (cond
                    (and installed? running?) "installed (running)"
                    installed?                "installed (not running)"
                    :else                     "not installed")))
    (println "Managed by:  " (if installed? (name manager) "none"))
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
        (when-let [until (:usage-held-until s)]
          (println "Usage hold: " "no Run starts until" until "(account usage limit)"))
        (run! println (held-lines (:held s) (System/currentTimeMillis)))
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
                           (source-health s)
                           (or (:last-polled-at s) "never polled"))))))
    (run! println (sweep-hold-lines (try (work/improvement-holds)
                                         (catch Exception _ nil))))))

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

(defn- fail!
  "Report a supervisor verb that failed, in the manager's words, and exit with its code."
  [did exit err]
  (println "Coordinator:" did "failed (exit" exit "). stderr:")
  (println err)
  (System/exit exit))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  up
  "bb nido:coordinator:up [:poll-ms <int>] — start the daemon.
   If its service is installed (bb nido:coordinator:install), the supervisor starts it.
   Otherwise spawns a bare background daemon that writes a PID file at
   ~/.nido/coordinator/coordinator.pid."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        {:keys [manager installed? running?]} (sup/status)]
    (cond
      (and installed? running? (pid/alive?))
      (println "Coordinator: already managed by" (name manager) "(pid" (pid/read) ").")

      installed?
      (let [{:keys [ok? exit err did]} (sup/start!)]
        (if ok?
          (do (Thread/sleep 500) ; give the daemon a moment to write its PID
              (println "Coordinator: started via" (name manager) "(pid" (or (pid/read) "pending") ")."))
          (fail! did exit err)))

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
   If its service is installed, the supervisor stops it, so it does not
   respawn until `up`'d (or the next boot).
   Otherwise sends SIGTERM (or SIGKILL with :force true) to the bare daemon.
   Also accepts :force? (zsh users: quote it as ':force?')."
  [& args]
  (let [[_ opts]  (task-args/split-args args)
        force?    (or (= true (:force opts)) (= true (:force? opts)))
        pid       (pid/read)]
    (cond
      (:installed? (sup/status))
      (let [{:keys [ok? exit err did]} (sup/stop!)]
        (if ok?
          (println "Coordinator: stopped via" (str did "."))
          (fail! did exit err)))

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
  "Resolve the directory containing `java` for the daemon service's PATH.
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
  "bb nido:coordinator:install — install the daemon's service with the host's supervisor
   (a LaunchAgent on macOS, a systemd --user unit on Linux) and start it. From then on it is
   started again when it exits and at every login (macOS) or boot (Linux, with lingering)."
  [& _args]
  (cond
    (and (pid/alive?) (not (:installed? (sup/status))))
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
    (let [_ (cstate/ensure-dirs!) ; the manager opens the log before the daemon runs
          {:keys [ok? exit err did manager]}
          (sup/install! (cond-> {:bb-path  (which-bb)
                                 :nido-dir (git-toplevel)
                                 :log-path (cstate/log-path)
                                 :path-env (str (java-bin-dir)
                                                ":" (System/getProperty "user.home") "/.local/bin"
                                                ":/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin")}
                          ;; A daemon installed from a shell with NIDO_HOME set runs against that home.
                          (System/getenv "NIDO_HOME") (assoc :nido-home (System/getenv "NIDO_HOME"))))]
      (if ok?
        (println "Coordinator: installed under" (name manager)
                 "— the daemon is started again when it exits, and at login or boot.")
        (fail! did exit err)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  uninstall
  "bb nido:coordinator:uninstall — stop the daemon's service and remove it. Idempotent."
  [& _args]
  (cond
    (not (:installed? (sup/status)))
    (do (println "Coordinator: not installed. Nothing to do.")
        (System/exit 0))

    :else
    (let [{:keys [ok? exit err did]} (sup/uninstall!)]
      (if ok?
        (println "Coordinator: uninstalled. Run `bb nido:coordinator:up` to start manually.")
        (fail! did exit err)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  restart
  "bb nido:coordinator:restart — restart the daemon through its supervisor.
   Errors when its service is not installed (use down + up instead)."
  [& _args]
  (if-not (:installed? (sup/status))
    (do (println "Coordinator: not installed. Use `bb nido:coordinator:down` then `bb nido:coordinator:up`.")
        (System/exit 1))
    (let [{:keys [ok? exit err did]} (sup/restart!)]
      (if ok?
        (println "Coordinator: restarted via" (str did "."))
        (fail! did exit err)))))

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
