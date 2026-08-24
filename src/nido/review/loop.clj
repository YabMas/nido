;; src/nido/review/loop.clj
(ns nido.review.loop
  "Review-loop engine: run a stage pipeline over an immutable iteration context
   until terminal, emitting typed lifecycle events to an injected `emit` fn.
   Pure control logic; stages and emit are injectable for tests. The engine
   never prints and never builds the report — it only emits."
  (:require
   [nido.review.stages :as stages])
  (:import
   [java.time Instant]))

(def default-pipeline
  "review (fan out) -> warden (per layer) -> arbiter (fan in) -> fix (serial).
   The arbiter is the round barrier: no fix runs until every finding has an
   owner, so a fixer never starts against a layer the arbiter is about to
   reassign work to."
  [stages/review-stage stages/warden-stage stages/arbiter-stage stages/fix-stage])

(defn- finding-key [f] [(:file f) (:line-start f) (:title f)])

(defn- no-progress?
  [prev-findings curr-findings]
  (and (seq prev-findings)
       (>= (count (set (map finding-key curr-findings)))
           (count (set (map finding-key prev-findings))))
       (= (set (map finding-key curr-findings))
          (set (map finding-key prev-findings)))))

(defn- run-pipeline
  "Run stages in order over ctx, emitting phase-started before each stage and
   phase-finished (or phase-errored) after. Short-circuits (reduced) on a
   terminal :status or terminal :control. Stage-agnostic — never names a stage."
  [ctx pipeline emit clock]
  (reduce
   (fn [ctx stage]
     (emit {:event :phase-started :iter (:iter ctx) :phase (:name stage)
            :at (str (clock))})
     (let [ctx' (try
                  ((:run stage) ctx)
                  (catch clojure.lang.ExceptionInfo e
                    (when (= :review-failed (:reason (ex-data e)))
                      (emit {:event :phase-errored :iter (:iter ctx)
                             :phase (:name stage) :error (ex-message e)
                             :at (str (clock))}))
                    (throw e)))]
       (emit {:event :phase-finished :iter (:iter ctx') :phase (:name stage)
              :ctx ctx' :at (str (clock))})
       (cond
         (:status ctx')                (reduced ctx')
         (= :stop (:control ctx'))     (reduced (assoc ctx' :status :converged))
         (= :escalate (:control ctx')) (reduced (assoc ctx' :status :escalated))
         :else                         ctx')))
   ctx
   pipeline))

(defn run-loop
  "Drive the pipeline until terminal. config:
   {:cwd :base :run-id :max-iters :pipeline :emit :clock :budget :dry-run?}.
   :max-iters is OPTIONAL and has no default — nil means run until the loop
   terminates on its own merits (converged / escalated / clean / no-progress /
   error). A round that changes nothing still ends the run via `no-progress?`,
   so unbounded does not mean non-terminating. Pass :max-iters only to cap it.
   :pipeline / :emit / :clock are injection seams."
  [{:keys [run-id max-iters pipeline emit clock] :as config
    :or   {emit (fn [_]) clock #(Instant/now)}}]
  (let [pipeline (or pipeline default-pipeline)
        impl-session-id (str (random-uuid))]
    (emit {:event :run-started :run-id run-id
           :cwd (:cwd config) :base (:base config) :at (str (clock))})
    (loop [iter 1, history [], prev-findings nil]
      (let [ctx0 {:config (assoc config :impl-session-id impl-session-id)
                  :iter iter :history history :control :continue}
            ctx  (try
                   (run-pipeline ctx0 pipeline emit clock)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :review-failed (:reason (ex-data e)))
                       (assoc ctx0 :status :review-failed :error (ex-message e))
                       (throw e))))
            final (cond
                    (:status ctx)                                ctx
                    (no-progress? prev-findings (:findings ctx)) (assoc ctx :status :no-progress)
                    (and max-iters (>= iter max-iters))          (assoc ctx :status :max-iters)
                    :else                                        nil)]
        (if final
          (do (emit {:event :run-finalized :status (:status final)
                     :ctx final :at (str (clock))})
              final)
          (recur (inc iter) (:history ctx) (:findings ctx)))))))
