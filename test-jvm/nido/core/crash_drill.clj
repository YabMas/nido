(ns nido.core.crash-drill
  "The child of the kill -9 drill (crash_test): commits from several threads until it is killed,
   printing `ack <key>` once each command's outcome has been answered."
  (:require [nido.core.store :as store]
            [nido.core.writer :as writer]))

(defn -main [db threads per-thread]
  (let [s (store/open db)
        w (writer/start s {:max-batch 16})
        out-lock (Object.)
        workers (doall
                 (for [t (range (parse-long threads))]
                   (future
                     (doseq [i (range (parse-long per-thread))]
                       (let [k (str "t" t "-" i)
                             o (deref (writer/submit! w {:key k :ws (str "ws" (mod i 4))
                                                         :command {:command :open-unit :unit k :project "p" :goal "g" :session-cwd "/tmp"}})
                                      10000 nil)]
                         (when (:events o)
                           (locking out-lock (println "ack" k) (flush))))))))]
    (run! deref workers)
    (locking out-lock (println "done") (flush))))
