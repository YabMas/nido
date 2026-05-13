(ns tasks.nido-coordinator
  "Bb task entry points for the coordinator daemon.

   Usage:
     bb nido:coordinator:run [:poll-ms <int>]
     bb nido:coordinator:status"
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.core :as core]
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
  (let [p (cstate/status-path)]
    (if (fs/exists? p)
      (let [s (io/read-edn p)]
        (println "Coordinator:" (some-> (:status s) name))
        (println "Heartbeat:  " (:heartbeat-at s))
        (println "Slots:      " (:slots-in-use s))
        (when (:halted-reason s)
          (println "Halted:     " (:halted-reason s))))
      (println "Coordinator: not running (no status.edn)"))))
