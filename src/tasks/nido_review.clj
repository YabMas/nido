(ns tasks.nido-review
  "bb-task entrypoint for the codex review loop. Drives the engine inside a live
   terminal frontend (spinner + per-round ledger); persists report.json under
   the run dir. See docs/superpowers/specs/2026-06-30-review-tui-frontend-design.md."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.session :as csession]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
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

(defn review-event
  "Pure: build a :review ledger payload from the loop's terminal value `final`
   ({:status :findings}) and the folded review `report` ({:summary :target})."
  [final report report-path]
  {:format             :review-report
   :status             (:status final)
   :base               (get-in report [:target :base])
   :base-rev           (get-in report [:target :base-rev])
   :rounds             (or (get-in report [:summary :rounds]) 0)
   :findings-fixed     (or (get-in report [:summary :findings-fixed]) 0)
   :findings-remaining (count (:findings final))
   :report-path        report-path})

(defn append-review-entry!
  "Resolve cwd → session → workstream (the nido.ship path) and append one :review
   entry. Best-effort: a ledger-write failure must never turn a completed review
   into a failure exit — visibility is a side record, not part of the review. No-op
   returning nil when cwd maps to no workstream or the append fails. Returns ws-id."
  [cwd final report report-path]
  (try
    (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
      (when-let [ws-id (csession/workstream-id-for (keyword project) session)]
        (ws/append-entry! (keyword project) ws-id {:kind :review}
                          (pr-str (review-event final report report-path)))
        ws-id))
    (catch Exception e
      (binding [*out* *err*]
        (println (str "review-loop: could not append :review ledger event — "
                      (ex-message e))))
      nil)))

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
        final  (frontend/with-live-display
                 {:report-atom report-atom :report-path report-path :clock clock}
                 (fn [emit] (rloop/run-loop (assoc config :emit emit))))
        status (:status final)]
    (append-review-entry! cwd final @report-atom report-path)
    (println (str "review-loop: " (name status) " · report " report-path))
    status))

(defn loop-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (loop-cmd* opts)))
