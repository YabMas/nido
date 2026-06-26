;; src/nido/review/loop.clj
(ns nido.review.loop
  "Review-loop engine: run a stage pipeline over an immutable iteration context
   until a terminal :control / status. Pure control logic; stages and sink are
   injectable for tests."
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [nido.coordinator.state :as cstate]
   [nido.review.stages :as stages]))

(def default-pipeline [stages/review-stage stages/judge-stage stages/fix-stage])

(defn- finding-key [f] [(:file f) (:line-start f) (:title f)])

(defn- no-progress?
  "True when this round's findings did not shrink vs the previous round."
  [prev-findings curr-findings]
  (and (seq prev-findings)
       (>= (count (set (map finding-key curr-findings)))
           (count (set (map finding-key prev-findings))))
       (= (set (map finding-key curr-findings))
          (set (map finding-key prev-findings)))))

(defn- file-sink
  "Default sink: append the ctx snapshot to <run-dir>/iterations.jsonl."
  [run-id]
  (let [dir (cstate/run-dir run-id)]
    (fs/create-dirs dir)
    (fn [ctx]
      (spit (str (fs/path dir "iterations.jsonl"))
            (str (json/generate-string
                  (select-keys ctx [:iter :findings :judge :control :status :history]))
                 "\n")
            :append true))))

(defn- run-pipeline
  "Run stages in order over ctx, short-circuiting (reduced) the moment a stage
   sets a terminal :status or a terminal :control. Stage-agnostic: the engine
   never names a stage, so the pipeline vector can be extended or reordered.
   A trailing :continue means 'reached the end, loop again'."
  [ctx pipeline sink]
  (reduce
   (fn [ctx stage]
     (let [ctx' ((:run stage) ctx)]
       (sink ctx')
       (cond
         (:status ctx')                (reduced ctx')                       ; e.g. :clean / :fix-noop / :judge-indeterminate
         (= :stop (:control ctx'))     (reduced (assoc ctx' :status :converged))
         (= :escalate (:control ctx')) (reduced (assoc ctx' :status :escalated))
         :else                         ctx')))                              ; :control still :continue → next stage
   ctx
   pipeline))

(defn run-loop
  "Drive the pipeline until terminal. config:
   {:cwd :base :run-id :max-iters :pipeline? :sink? :budget? :dry-run?}.
   :pipeline and :sink are injection seams (defaults: default-pipeline, file sink)."
  [{:keys [run-id max-iters pipeline sink] :as config
    :or   {max-iters 5}}]
  (let [pipeline (or pipeline default-pipeline)
        sink     (or sink (file-sink run-id))]
    (loop [iter 1, history [], prev-findings nil]
      (let [ctx0 {:config (assoc config :max-iters max-iters)
                  :iter iter :history history :control :continue}
            ctx  (run-pipeline ctx0 pipeline sink)]
        (cond
          ;; a stage set a terminal status or terminal control
          (:status ctx) ctx
          ;; reached end of pipeline with :control :continue → apply backstops
          (no-progress? prev-findings (:findings ctx)) (assoc ctx :status :no-progress)
          (>= iter max-iters)                          (assoc ctx :status :max-iters)
          :else (recur (inc iter) (:history ctx) (:findings ctx)))))))
