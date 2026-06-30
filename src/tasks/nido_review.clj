(ns tasks.nido-review
  "bb-task entrypoint for the codex review loop. Drives the engine inside a live
   terminal frontend (spinner + per-round ledger); persists report.json under
   the run dir. See docs/superpowers/specs/2026-06-30-review-tui-frontend-design.md."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.state :as cstate]
   [nido.review.frontend :as frontend]
   [nido.review.loop :as rloop]
   [nido.review.report :as report]
   [nido.session.lifecycle :as lifecycle]
   [nido.task-args :as task-args])
  (:import
   [java.time Instant]))

(defn exit-code
  "CLI exit code for a terminal review status. review-failed is the only
   failure; escalated is a reported outcome, not an error."
  [status]
  (if (= :review-failed status) 1 0))

(defn loop-cmd* [{:keys [cwd base max-iters dry-run?]}]
  (let [cwd        (or cwd
                       (lifecycle/worktree-from-cwd)
                       (System/getProperty "user.dir"))
        base       (or base "main")
        run-id     (str "review-" (random-uuid))
        clock      #(Instant/now)
        report-path (str (fs/path (cstate/run-dir run-id) "report.json"))
        config     {:cwd cwd :base base
                    :max-iters (or max-iters 5)
                    :dry-run?  (boolean dry-run?)
                    :run-id    run-id
                    :clock     clock}
        report-atom (atom (report/init {:run-id run-id :cwd cwd :base base
                                        :started-at (str (clock))}))
        {:keys [status]}
        (frontend/with-live-display
          {:report-atom report-atom :report-path report-path :clock clock}
          (fn [emit] (rloop/run-loop (assoc config :emit emit))))]
    (println (str "review-loop: " (name status) " · report " report-path))
    status))

(defn loop-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (loop-cmd* opts)))
