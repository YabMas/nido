(ns tasks.nido-ui
  "CLI entry point for the nido dashboard."
  (:require [clojure.edn :as edn]
            [nido.ui.server :as server]))

(defn start
  "Start the nido dashboard. Usage: bb nido:ui [:port 8800]"
  [& args]
  (let [opts (if (seq args)
               (apply hash-map (map #(try (edn/read-string %)
                                          (catch Exception _ %)) args))
               {})
        port (or (:port opts) 8800)]
    (server/start! {:port port})
    ;; Keep the process alive
    @(promise)))
