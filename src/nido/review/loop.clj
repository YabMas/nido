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
  "review (fan out) -> warden (fan in) -> reshape -> fix (serial).
   The warden is the round barrier: no fix runs until every finding has an
   owner, so a fixer never starts against a layer the warden is about to
   reassign work to. Reshape sits between the two because it rewrites the layers
   a fixer is about to be positioned on — the other order lands a fix on a layer
   that is about to move."
  [stages/review-stage stages/warden-stage stages/reshape-stage stages/fix-stage])

(defn default-finding-key
  "How the DIFF review tells one finding from another: the handle the warden
   filed it under.

   Not the place in the code plus the title, which is what a reviewer reports
   and therefore what a fresh reviewer rewrites. A fix moves the code, so the
   file and line move with it; the title is prose, and the same defect described
   again next round is described in different words. Identity derived from any
   of the three is stable only while nothing is happening — and a defect the loop
   cannot move is exactly the one that gets restated, so the check that exists to
   notice it was blind in the one case it was for.

   The handle is assigned once per round, by the only reader that can tell two
   findings are the same defect, and carried forward. The triple survives as the
   fallback for a finding that never reached that reader — an unrecognised repeat
   costs a round, which is the cheaper failure.

   Still wrong for a pass that judges a RECORD: those findings carry no file, no
   line and no handle, and the text they do carry is the very text their fixer
   rewrites — so a record pipeline injects its own, keyed on something its
   amender cannot move. See `run-loop`'s :finding-key."
  [f]
  (or (:handle f) [(:file f) (:line-start f) (:title f)]))

(defn- no-progress?
  "The same findings again, by whatever identity this pipeline keys on.

   This is the ONLY thing that ends an uncapped run that is getting nowhere, so
   the identity fn is load-bearing: one that never collides turns `:max-iters`
   from a cap into the sole terminator."
  [finding-key prev-findings curr-findings]
  (and (seq prev-findings)
       (= (set (map finding-key curr-findings))
          (set (map finding-key prev-findings)))))

(def ^:private unfixable-after
  "How many rounds a finding may be raised in before the run gives up on it.

   Four, so that THREE repairs are attempted and every one of them is judged.
   It was three, which bought two tested repairs — and twice in one day the
   third attempt was the one that worked: a survey's reading corrected on the
   third try was reported as never resolved, and a re-run found it clean. A
   convergence loop must not stop while it is still making progress, and the
   evidence says the third attempt is often where progress is."
  4)

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
  [finding-key prior curr-findings]
  ;; `prior` is the history NOT counting this round, and the caller says what
  ;; that is: after a judgement the round has appended nothing yet, after a
  ;; whole pipeline it has. Computing it here — with a `butlast` that is right
  ;; for one caller and wrong for the other — is how this came to make three
  ;; rounds out of two.
  (let [runs  (map #(set (map finding-key (:findings %)))
                   (take-last (dec unfixable-after) prior))
        curr  (map finding-key curr-findings)]
    (when (= (count runs) (dec unfixable-after))
      (seq (distinct (filter (fn [k] (every? #(contains? % k) runs)) curr))))))

(defn- terminal
  "The status this round ends on, or nil to keep going.

   `prior` is every round before this one. Split out of `run-loop` because it is
   now asked at two moments — after the stage that produces the judgement, and
   after the whole pipeline — and the two disagree about what history holds."
  [{:keys [finding-key prev-findings iter max-iters]} ctx prior]
  (cond
    ;; BEFORE no-progress?, because both are true of a run that ends holding the
    ;; same findings and only this one says which. :no-progress sends a reader
    ;; to look at everything; :unfixable names the two or three that did not
    ;; move, which on a converged survey is the whole of what is left.
    (seq (unfixable finding-key prior (:findings ctx)))
    (assoc ctx :status :unfixable
           :unfixable (vec (unfixable finding-key prior (:findings ctx))))

    ;; Reached when the round changed nothing AND no single finding has yet
    ;; survived long enough to be called stuck — an amender that stopped working
    ;; rather than one that ran out of things it could fix.
    (no-progress? finding-key prev-findings (:findings ctx))
    ;; Naming what is still open, like :unfixable does. A run that stops holding
    ;; findings should say which; the two statuses differ in how long they
    ;; persisted, not in whether a reader is told what they were.
    (assoc ctx :status :no-progress
           :unfixable (vec (distinct (map finding-key (:findings ctx)))))

    (and max-iters (>= iter max-iters))
    (assoc ctx :status :max-iters)

    :else nil))

(defn- run-pipeline
  "Run stages in order over ctx, emitting phase-started before each stage and
   phase-finished (or phase-errored) after. Short-circuits (reduced) on a
   terminal :status or terminal :control.

   Stage-agnostic still: it never names a stage, it is TOLD one. `judged-after`
   is the pipeline saying which of its stages produces the judgement a run may
   end on, and a run that ends there ends on a judgement rather than on a
   repair — so every repair it reports as failed was actually tested, and it
   spends no round repairing a finding it is about to report as immovable."
  [ctx pipeline emit clock judged-after end? open?]
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
         ;; A stop is a convergence only if the round is not still holding
         ;; something. `open?` is the pipeline's own reading of that — the engine
         ;; must not look inside a finding, which is what keeps it shared with
         ;; the record loops — and it defaults to "nothing is open", so a
         ;; pipeline that does not answer the question keeps the old behaviour.
         (= :stop (:control ctx'))
         (reduced (assoc ctx' :status (if (some open? (:findings ctx'))
                                        :unresolved
                                        :converged)))
         (= :escalate (:control ctx')) (reduced (assoc ctx' :status :escalated))

         ;; The history here does not yet count this round — the stage that
         ;; appends it has not run — so it is already the `prior` the check
         ;; wants.
         (and judged-after (= judged-after (:name stage)))
         (if-let [final (end? ctx' (:history ctx'))] (reduced final) ctx')

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
   :pipeline / :emit / :clock / :finding-key / :open? are injection seams.
   :finding-key decides what \"the same finding again\" means and so what
   no-progress? can detect; it defaults to the diff review's
   default-finding-key. :open? decides whether a finding is still owed, and so
   whether a pipeline saying stop has CONVERGED or merely stopped: a run that
   ends holding something reports :unresolved instead. It defaults to
   \"nothing is open\", which is the reading a pipeline with no notion of an
   unactioned finding wants.

   A round's ctx is rebuilt from scratch. `:carry` is the only channel a stage
   has to reach the next round, and it survives onto the terminal ctx too — see
   the comment on ctx0."
  [{:keys [run-id max-iters pipeline emit clock finding-key judged-after open?] :as config
    :or   {emit (fn [_]) clock #(Instant/now)
           finding-key default-finding-key
           open? (constantly false)}}]
  (let [pipeline (or pipeline default-pipeline)
        impl-session-id (str (random-uuid))]
    (emit {:event :run-started :run-id run-id
           :cwd (:cwd config) :base (:base config) :at (str (clock))})
    (loop [iter 1, history [], prev-findings nil, carry nil]
      (let [ctx0 {:config (assoc config :impl-session-id impl-session-id)
                  :iter iter :history history :control :continue
                  ;; The one thing a round may hand to the next one. Everything
                  ;; else a stage puts on the ctx is scratch for that round: the
                  ;; ctx is rebuilt here from :config, :iter, :history and
                  ;; nothing more, so a stage that stores a value for later and
                  ;; does not put it here is storing it nowhere.
                  ;;
                  ;; Watched: the record pipelines kept "the record this run is
                  ;; repairing" on the bare ctx. It was dropped every round, so
                  ;; each judge fell through to its "or the latest entry" default
                  ;; — the exact re-read that key exists to prevent. The loop
                  ;; still converged, because on a workstream with one survey the
                  ;; latest entry IS the amended one, which is why nothing showed
                  ;; it. The fix belongs here rather than in either pipeline:
                  ;; there was no seam to put it through.
                  :carry carry}
            cfg  {:finding-key finding-key :prev-findings prev-findings
                  :iter iter :max-iters max-iters}
            end? (fn [c prior] (terminal cfg c prior))
            ctx  (try
                   (run-pipeline ctx0 pipeline emit clock judged-after end? open?)
                   (catch clojure.lang.ExceptionInfo e
                     (if (= :review-failed (:reason (ex-data e)))
                       (assoc ctx0 :status :review-failed :error (ex-message e))
                       (throw e))))
            final (or (when (:status ctx) ctx)
                      ;; The whole pipeline ran without ending. `butlast`
                      ;; because a stage after the judgement has since appended
                      ;; this round to the history. A pipeline that named a
                      ;; judged-after stage has already asked and been told no,
                      ;; on the same findings and the same prior — so this
                      ;; cannot contradict it.
                      (terminal cfg ctx (butlast (:history ctx))))]
        (if final
          (do (emit {:event :run-finalized :status (:status final)
                     :ctx final :at (str (clock))})
              final)
          (recur (inc iter) (:history ctx) (:findings ctx) (:carry ctx)))))))
