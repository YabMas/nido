(ns tasks.nido-scratch
  "One-time migration: give every pre-existing manual session a loose (scratch)
   workstream, so the universal-workstream model holds for sessions that predate
   it. Idempotent — `scratch/birth!` no-ops on sessions already owned by a
   workstream (Notion, GitHub, or an earlier backfill)."
  (:require
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [nido.task-args :as task-args]))

(defn backfill
  "Birth a loose workstream for every existing session of `:project` that does
   not yet belong to one. Safe to re-run."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        project  (or (some-> (:project opts) name)
                     (throw (ex-info "Missing :project <name>"
                                     {:hint "Pass :project <project-name>."})))
        {:keys [sessions]} (lifecycle/list-all-data {:project project})]
    (doseq [{:keys [name]} sessions]
      (let [ws-id (scratch/birth! (keyword project) name)]
        (println (format "  %-40s → %s" name ws-id))))
    (println (format "Backfilled %d session(s) for %s" (count sessions) project))))
