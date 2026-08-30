(ns tasks.nido-scratch
  "One-time migration: give every pre-existing manual session a loose (scratch)
   workstream, so the universal-workstream model holds for sessions that predate
   it. Idempotent — `scratch/birth!` no-ops on sessions already owned by a
   workstream (Notion, GitHub, or an earlier backfill), beyond reconciling a
   stale `:weight` against what the session actually has provisioned. That makes
   a re-run the repair for records stamped before weight was derived."
  (:require
   [clojure.string :as str]
   [nido.coordinator.lane.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.task-args :as task-args]))

(defn- coordinator-run?
  "A coordinator-run worktree, named `run-<project>-<trigger>-<suffix>` (see
   runs.clj). These are coordinator-owned — spawn-records! mints their workstream
   — so backfill must not adopt them as scratch one-offs. An orphaned (pre-model)
   run carries no workstream, and without this guard a re-run would leak it into
   the Scratch view."
  [session-name]
  (str/starts-with? (str session-name) "run-"))

(defn backfill
  "Birth a loose workstream for every existing *manual* session of `:project`
   that does not yet belong to one. Coordinator-run worktrees (run-*) are skipped.
   Safe to re-run."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        project  (or (some-> (:project opts) name)
                     (throw (ex-info "Missing :project <name>"
                                     {:hint "Pass :project <project-name>."})))
        {:keys [sessions]} (lifecycle/list-all-data {:project project})
        {runs true manual false} (group-by (comp coordinator-run? :name) sessions)]
    (doseq [{:keys [name]} manual]
      (let [ws-id (scratch/birth! (keyword project) name
                                  (lifecycle/session-weight name {:project project}))]
        (println (format "  %-40s → %s" name ws-id))))
    (when (seq runs)
      (println (format "Skipped %d coordinator run(s)" (count runs))))
    (println (format "Backfilled %d session(s) for %s" (count manual) project))))
