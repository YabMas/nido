(ns tasks.nido-coordinator
  "Bb task entry points for the coordinator daemon.

   Usage:
     bb nido:coordinator:run [:poll-ms <int>]
     bb nido:coordinator:status"
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.core :as core]
   [nido.coordinator.halt :as halt]
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
