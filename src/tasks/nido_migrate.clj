(ns tasks.nido-migrate
  (:require
   [nido.coordinator.migrate :as migrate]
   [nido.platform.task-args :as task-args]))

(defn migrate-cmd
  "bb nido:migrate :project <p> — migrate legacy run.edn/ticket records into the
   workstream/session model, archiving the old trees under _pre-unification/.

   FROZEN — pre-cutover. The project adopted the strangler dual-write instead
   of a hard cut-over, so running this NOW would archive the tickets/ tree the
   live spine still reads on every render — degrading everything to :triage.
   This command refuses unless you pass the explicit override flag:
     :i-understand-this-degrades-the-spine true"
  [& args]
  (let [[_ opts] (task-args/split-args args)
        project  (:project opts)]
    (when-not project
      (println "usage: bb nido:migrate :project <project>")
      (System/exit 2))
    (when-not (= "true" (str (:i-understand-this-degrades-the-spine opts)))
      (throw (ex-info
              (str "REFUSED: nido:migrate archives the per-project tickets/ tree "
                   "the live model still reads every render — running it will "
                   "degrade the spine (everything re-projects to :triage). This "
                   "task is frozen pre-cutover. Pass "
                   ":i-understand-this-degrades-the-spine true only if you truly "
                   "intend a one-way pre-cutover migration.")
              {:project project})))
    (let [{:keys [workstreams sessions]} (migrate/run-once! (keyword project))]
      (println (format "Migrated %d workstream(s), %d session(s). Old records archived under _pre-unification/."
                       workstreams sessions)))))

(defn ledger-cmd
  "bb nido:migrate:ledger :project <p> — one-shot, best-effort copy of each
   ticket's legacy ledger entries (tickets/<br>/entries/) into its workstream's
   ledger (found by ref). Non-destructive: ticket entry files are left in place
   (copied, not moved). Orphan tickets (no workstream, or a legacy entry body
   that no longer validates) are skipped and counted, not migrated — lossy is
   acceptable per the ledger-unification design. Safe to re-run: a workstream
   that already carries entries is skipped."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        project  (:project opts)]
    (when-not project
      (println "usage: bb nido:migrate:ledger :project <project>")
      (System/exit 2))
    (let [{:keys [migrated orphans failed-entries]} (migrate/ledger->workstreams! (keyword project))]
      (println (format "Migrated %d ticket ledger(s) into workstreams. %d orphan(s) skipped (no workstream). %d entries failed re-validation and were skipped (within an otherwise-migrated ticket)."
                       migrated orphans failed-entries)))))
