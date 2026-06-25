(ns tasks.nido-review
  "bb-task entrypoint for the codex review loop. Synchronous; streams a final
   summary. See docs/superpowers/specs/2026-06-26-codex-review-loop-design.md."
  (:require
   [nido.review.loop :as rloop]
   [nido.session.lifecycle :as lifecycle]
   [nido.task-args :as task-args]))

(defn loop-cmd* [{:keys [cwd base max-iters dry-run?]}]
  (let [;; Explicit :cwd wins (standalone / arbitrary workspace). Otherwise
        ;; resolve the session's worktree from cwd so the verb works from the
        ;; session-home OR the worktree — hiding that split — falling back to
        ;; the raw cwd when cwd belongs to no known session.
        cwd        (or cwd
                       (lifecycle/worktree-from-cwd)
                       (System/getProperty "user.dir"))
        base       (or base "main")
        config     {:cwd       cwd
                    :base      base
                    :max-iters (or max-iters 5)
                    :dry-run?  (boolean dry-run?)
                    :run-id    (str "review-" (random-uuid))}
        _          (println (format "review-loop: reviewing %s (base %s)" cwd base))
        {:keys [status history]} (rloop/run-loop config)]
    (println (format "review-loop: %s after %d iteration(s); run-id %s"
                     (name status) (count history) (:run-id config)))
    status))

(defn loop-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (loop-cmd* opts)))
