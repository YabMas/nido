(ns tasks.nido-migrate
  (:require
   [nido.coordinator.migrate :as migrate]
   [nido.task-args :as task-args]))

(defn migrate-cmd
  "bb nido:migrate :project <p> — migrate legacy run.edn/ticket records into the
   workstream/session model, archiving the old trees under _pre-unification/."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        project  (:project opts)]
    (when-not project
      (println "usage: bb nido:migrate :project <project>")
      (System/exit 2))
    (let [{:keys [workstreams sessions]} (migrate/run-once! (keyword project))]
      (println (format "Migrated %d workstream(s), %d session(s). Old records archived under _pre-unification/."
                       workstreams sessions)))))
