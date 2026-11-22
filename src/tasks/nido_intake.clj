(ns tasks.nido-intake
  "`bb nido:intake:run` — the intake host in the foreground, as its supervised service runs it."
  (:require [nido.boot.intake :as intake]
            [nido.platform.task-args :as task-args]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]} run
  "Run the intake host until the process ends. [:poll-ms <n>]"
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (intake/run! :poll-ms (or (some-> (:poll-ms opts) str parse-long) 1000))))
