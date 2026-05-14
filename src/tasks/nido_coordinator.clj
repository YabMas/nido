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
   [nido.coordinator.pid :as pid]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]
   [nido.task-args :as task-args]))

(defn run [& args]
  (let [[_ opts] (task-args/split-args args)
        ms       (some-> (:poll-ms opts) str parse-long)]
    (if ms
      (core/run! :poll-ms ms)
      (core/run!))))

(defn status [& _args]
  (let [p (cstate/status-path)
        h (halt/read-halt-info)]
    (if (fs/exists? p)
      (let [s (io/read-edn p)]
        (println "Coordinator:" (some-> (:status s) name))
        (println "Heartbeat:  " (:heartbeat-at s))
        (println "Slots:      " (:slots-in-use s)))
      (println "Coordinator: not running (no status.edn)"))
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
  "bb nido:coordinator:down [:force? true] — stop the background daemon.
   Default SIGTERM, waits up to 30s for graceful shutdown.
   With :force? true, sends SIGKILL immediately (orphans any in-flight agent)."
  [& args]
  (let [[_ opts]  (task-args/split-args args)
        force?    (= true (:force? opts))
        pid       (pid/read)]
    (cond
      (nil? pid)
      (do (println "Coordinator: not running (no PID file).") (System/exit 0))

      (not (pid/alive?))
      (do (println "Coordinator: stale PID file (pid" pid "is not alive). Cleaning up.")
          (pid/delete!)
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
                  (println "Coordinator: stopped."))

              (> (System/currentTimeMillis) deadline)
              (do (println "Coordinator: did not exit within 30s. Use :force? true to SIGKILL.")
                  (System/exit 2))

              :else
              (do (Thread/sleep 200) (recur)))))))))
