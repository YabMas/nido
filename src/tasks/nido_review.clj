(ns tasks.nido-review
  "bb-task entrypoint for the codex review loop. Synchronous; streams a final
   summary. See docs/superpowers/specs/2026-06-26-codex-review-loop-design.md."
  (:require
   [nido.review.loop :as rloop]
   [nido.task-args :as task-args]))

(defn loop-cmd* [{:keys [cwd base max-iters dry-run?]}]
  (let [config {:cwd       (or cwd (System/getProperty "user.dir"))
                :base      (or base "main")
                :max-iters (or max-iters 5)
                :dry-run?  (boolean dry-run?)
                :run-id    (str "review-" (random-uuid))}
        {:keys [status history]} (rloop/run-loop config)]
    (println (format "review-loop: %s after %d iteration(s); run-id %s"
                     (name status) (count history) (:run-id config)))
    status))

(defn loop-cmd [& args]
  (let [[_ opts] (task-args/split-args args)]
    (loop-cmd* opts)))
