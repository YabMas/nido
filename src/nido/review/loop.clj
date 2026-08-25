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
  "review (fan out) -> warden (fan in) -> fix (serial).
   The warden is the round barrier: no fix runs until every finding has an
   owner, so a fixer never starts against a layer the warden is about to
   reassign work to."
  [stages/review-stage stages/warden-stage stages/fix-stage])

(defn default-finding-key
  "How the DIFF review tells one finding from another: the place in the code it
   is about, plus its title. Correct there because a fix moves code and the
   finding follows it, so a finding that survives a round is recognisable.

   It is wrong for a pass that judges a RECORD. Those findings carry no file and
   no line, and the text they do carry is the very text their fixer rewrites — so
   a record pipeline injects its own, keyed on something its amender cannot
   move. See `run-loop`'s :finding-key."
  [f]
  [(:file f) (:line-start f) (:title f)])

(defn- no-progress?
  "The same findings again, by whatever identity this pipeline keys on.

   This is the ONLY thing that ends an uncapped run that is getting nowhere, so
   the identity fn is load-bearing: one that never collides turns `:max-iters`
   from a cap into the sole terminator."
  [finding-key prev-findings curr-findings]
  (and (seq prev-findings)
       (= (set (map finding-key curr-findings))
          (set (map finding-key prev-findings)))))

(def ^:private unfixable-after 3)

(defn- unfixable
  "Findings raised in `unfixable-after` consecutive rounds and never resolved.

   Whole-set repetition is too coarse to end a run that is nearly done. A loop
   that fixes four findings and cannot fix the fifth produces a DIFFERENT set
   every round — so `no-progress?` never fires, while the one finding that
   matters is raised, amended, and raised again indefinitely.

   Watched: a survey reached two findings, resolved one, and re-raised the other
   under the same key three rounds running. That is not a loop making progress
   and it is not a loop going nowhere; it is a loop that has finished everything
   it can and is stuck on the rest, which is a different thing to report and the
   only one a human can act on.

   Counted per finding rather than per round, and by the pipeline's own identity
   — the same handle the stall check uses, so a finding that cannot be told apart
   from round to round cannot silently accumulate here either."
  [finding-key history curr-findings]
  (let [runs (map #(set (map finding-key (:findings %))) (take-last (dec unfixable-after) history))
        curr (map finding-key curr-findings)]
    (when (= (count runs) (dec unfixable-after))
      (seq (filter (fn [k] (every? #(contains? % k) runs)) curr)))))

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
   :pipeline / :emit / :clock / :finding-key are injection seams. :finding-key
   decides what \"the same finding again\" means and so what no-progress? can
   detect; it defaults to the diff review's default-finding-key."
  [{:keys [run-id max-iters pipeline emit clock finding-key] :as config
    :or   {emit (fn [_]) clock #(Instant/now)
           finding-key default-finding-key}}]
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
                    (:status ctx)
                    ctx

                    (no-progress? finding-key prev-findings (:findings ctx))
                    (assoc ctx :status :no-progress)

                    ;; Everything fixable is fixed and the rest will not move.
                    ;; Distinct from :no-progress, which says the round changed
                    ;; nothing at all — a human reading one needs to know whether
                    ;; to look at every finding or only at these.
                    (seq (unfixable finding-key (:history ctx) (:findings ctx)))
                    (assoc ctx :status :unfixable
                           :unfixable (vec (unfixable finding-key (:history ctx) (:findings ctx))))

                    (and max-iters (>= iter max-iters))
                    (assoc ctx :status :max-iters)

                    :else nil)]
        (if final
          (do (emit {:event :run-finalized :status (:status final)
                     :ctx final :at (str (clock))})
              final)
          (recur (inc iter) (:history ctx) (:findings ctx)))))))
