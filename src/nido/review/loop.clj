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

(def engine-statuses
  "Every status this ENGINE can end a run on, whichever pipeline it drives.

   Declared as data because the ledger's ReviewReport enum has to admit each one
   and cannot read it: `nido.coordinator.report` is the shared-vocabulary band,
   it may depend on nothing above it, so the two lists live apart and a test in
   `tasks.nido-review-test` is what holds them together. Nothing held them
   together before, and `:unfixable` is what fell through — the closed enum
   refused the `:review` entry of every run that ended on it, and refusing a
   best-effort side record costs nothing a reader can see.

   The diff pipeline's stages end on their own statuses beside these; see
   `nido.review.stages/stage-statuses`. The record pipelines end on statuses of
   their own too, and those are NOT here: they reach a different ledger event,
   under no enum this one can drift from."
  #{:converged :unresolved :escalated :unfixable :no-progress :max-iters
    :review-failed :reviewer-unavailable :stack-unmovable})

(def ^:private terminal-reasons
  "The `:reason`s a stage throws with that END the run rather than crash it —
   each of which is also the status the run ends on.

   Split first by whether a review happened. `:review-failed` and
   `:reviewer-unavailable` are a review stage that produced none, split again by
   whether the REVIEWER could be run at all, which is the difference between a
   diff someone should open and a quota or a credential they must clear first;
   `nido.review.codex/unavailability` derives the second and carries the
   sentence that said so. `:stack-unmovable` is jj refusing a step the loop
   needed on the stack AFTER the reviewers had read it and the warden had ruled
   — see `nido.review.layers/refusal`. That round's review stands; filed under
   `:review-failed` it would read as one that never happened, and send its
   reader to check a quota first.

   Read at two moments for one throw — the phase event that records what stopped
   the round, and the run's own terminal status — so a reason admitted by one and
   not the other would emit an error the report keeps and then crash the loop out
   from under it."
  #{:review-failed :reviewer-unavailable :stack-unmovable})

(def default-pipeline
  "review (fan out) -> warden (fan in) -> reshape -> fix (serial).
   The warden is the round barrier: no fix runs until every finding has an
   owner, so a fixer never starts against a layer the warden is about to
   reassign work to. Reshape sits between the two because it rewrites the layers
   a fixer is about to be positioned on — the other order lands a fix on a layer
   that is about to move."
  [stages/review-stage stages/warden-stage stages/reshape-stage stages/fix-stage])

(defn ^{:malli/schema [:=> [:cat :Finding] :any]}
  default-finding-key
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
  "The same findings again, by whatever identity this pipeline keys on, on a
   round that also moved nothing.

   A repeated finding set is not on its own a stall. A defect CLASS narrows
   across rounds — a fixer closes two of its instances and the reviewers report
   what is left — and every instance is filed under the handle the class was
   first given, so the round that repaired the most looks identical to the round
   before it. Watched: a run ended here holding a ruling that named two untried
   remedies, on the round after one that had landed two repairs and moved every
   layer's patch hash.

   `changed?` is the pipeline's own evidence that something moved, and it VETOES
   the stall rather than establishing it: a pipeline that cannot tell says
   nothing, and the set equality stands alone as it always did.

   Ending an uncapped run that is getting nowhere rests on this AND on
   `unfixable`, which is what bounds a veto: a finding set that repeats is one
   whose every member is being raised again, which is what that counter reads.
   The identity fn is load-bearing to both — one that never collides turns
   `:max-iters` from a cap into the sole terminator."
  [finding-key prev-findings curr-findings changed?]
  (and (seq prev-findings)
       (not changed?)
       (= (set (map finding-key curr-findings))
          (set (map finding-key prev-findings)))))

(def ^:private unfixable-after
  "How many rounds a finding may be raised in before the run gives up on it.

   Four, so that THREE repairs are attempted and every one of them is judged.
   It was three, which bought two tested repairs — and twice in one day the
   third attempt was the one that worked: a baseline's reading corrected on the
   third try was reported as never resolved, and a re-run found it clean. A
   convergence loop must not stop while it is still making progress, and the
   evidence says the third attempt is often where progress is."
  4)

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  default-attempt-key
  "How the give-up counter tells one ATTEMPT at a defect from another.

   The finding's identity paired with the layer the last ruling aimed the repair
   at. `unfixable` counts how many times the loop has tried and failed, and a
   finding re-attributed to a different layer has not been tried there yet: the
   three prior rounds worked on the wrong code. Counting bare appearances gave up
   on exactly the round that first routed a finding correctly — the run ended one
   round before the fix it was set up to make.

   A pipeline whose findings have no owner (the record loops) gets the identity
   alone, which is the old behaviour and the right one where nothing is routed."
  [finding-key]
  (fn [f] [(finding-key f) (:owner-layer f)]))

(defn- unfixable
  "Findings raised in `unfixable-after` consecutive rounds and never resolved.

   Whole-set repetition is too coarse to end a run that is nearly done. A loop
   that fixes four findings and cannot fix the fifth produces a DIFFERENT set
   every round — so `no-progress?` never fires, while the one finding that
   matters is raised, amended, and raised again indefinitely.

   Watched: a baseline reached two findings, resolved one, and re-raised the other
   under the same key three rounds running. That is not a loop making progress
   and it is not a loop going nowhere; it is a loop that has finished everything
   it can and is stuck on the rest, which is a different thing to report and the
   only one a human can act on.

   Counted per finding rather than per round, and by the pipeline's own identity
   — the same handle the stall check uses, so a finding that cannot be told apart
   from round to round cannot silently accumulate here either.

   And counted on ATTEMPTS rather than appearances. A round that ruled a finding
   and aimed no repair at it has not tried and failed; it decided to try nothing,
   and whatever governs that decision is what should end the run over it.
   Watched: a defect repaired once and then parked in three consecutive rounds
   was read as three failed repairs, which stopped the run a round ahead of the
   rule that governs a standing park — and discarded, on the way out, a
   first-appearance P1 the same round had ordered fixed."
  [finding-key attempt-key attempted? prior curr-findings]
  ;; `prior` is the history NOT counting this round, and the caller says what
  ;; that is: after a judgement the round has appended nothing yet, after a
  ;; whole pipeline it has. Computing it here — with a `butlast` that is right
  ;; for one caller and wrong for the other — is how this came to make three
  ;; rounds out of two.
  ;;
  ;; Counted on the ATTEMPT key and reported on the finding key. The count is
  ;; about how many repairs were tried and failed, so a re-attribution restarts
  ;; it; what a reader is handed is the defect, which did not become a different
  ;; defect by being routed somewhere else.
  ;;
  ;; `attempted?` is the pipeline's own reading of whether a round aimed a repair
  ;; at a finding — the engine must not look inside one, which is what keeps it
  ;; shared with the record loops — and it is applied to the current round as
  ;; well as the prior ones: a round that attempts nothing must not be the round
  ;; the run gives up on either.
  (let [runs  (map #(into #{} (comp (filter attempted?) (map attempt-key))
                          (:findings %))
                   (take-last (dec unfixable-after) prior))]
    (when (= (count runs) (dec unfixable-after))
      (seq (distinct (keep (fn [f]
                             (when (and (attempted? f)
                                        (every? #(contains? % (attempt-key f)) runs))
                               (finding-key f)))
                           curr-findings))))))

(defn- terminal
  "The status this round ends on, or nil to keep going.

   `prior` is every round before this one. Split out of `run-loop` because it is
   now asked at two moments — after the stage that produces the judgement, and
   after the whole pipeline — and the two disagree about what history holds."
  [{:keys [finding-key attempt-key attempted? prev-findings iter max-iters
           changed?]} ctx prior]
  (cond
    ;; BEFORE no-progress?, because both are true of a run that ends holding the
    ;; same findings and only this one says which. :no-progress sends a reader
    ;; to look at everything; :unfixable names the two or three that did not
    ;; move, which on a converged baseline is the whole of what is left.
    (seq (unfixable finding-key attempt-key attempted? prior (:findings ctx)))
    (assoc ctx :status :unfixable
           :unfixable (vec (unfixable finding-key attempt-key attempted?
                                      prior (:findings ctx))))

    ;; Reached when the round changed nothing AND no single finding has yet
    ;; survived long enough to be called stuck — an amender that stopped working
    ;; rather than one that ran out of things it could fix.
    (no-progress? finding-key prev-findings (:findings ctx) (changed? ctx prior))
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
   spends no round repairing a finding it is about to report as immovable.

   A throw on one of `terminal-reasons` leaves carrying the round it was in, as
   `:ctx` on its ex-data: the ctx the stage put there itself, or else the one
   the stage was handed — which holds everything the stages before it did this
   round. Only the first reaches the phase event, because it is the stage's own
   account of itself and the second is not: folded as one, it would overwrite
   what the phase had already recorded with what the phase was given."
  [ctx pipeline emit clock judged-after end? open?]
  (reduce
   (fn [ctx stage]
     (emit {:event :phase-started :iter (:iter ctx) :phase (:name stage)
            :at (str (clock))})
     (let [ctx' (try
                  ((:run stage) ctx)
                  (catch clojure.lang.ExceptionInfo e
                    (let [data (ex-data e)]
                      (if (terminal-reasons (:reason data))
                        (do (emit (cond-> {:event :phase-errored :iter (:iter ctx)
                                           :phase (:name stage) :error (ex-message e)
                                           :at (str (clock))}
                                    (:ctx data) (assoc :ctx (:ctx data))))
                            (throw (ex-info (ex-message e) (update data :ctx #(or % ctx)) e)))
                        (throw e)))))]
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

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run-loop
  "Drive the pipeline until terminal. config:
   {:cwd :base :run-id :max-iters :pipeline :emit :clock :budget :dry-run?}.
   :max-iters is OPTIONAL and has no default — nil means run until the loop
   terminates on its own merits (converged / escalated / clean / no-progress /
   error). A round that changes nothing still ends the run via `no-progress?`,
   so unbounded does not mean non-terminating. Pass :max-iters only to cap it.
   :pipeline / :emit / :clock / :finding-key / :attempt-key / :attempted? /
   :open? are injection seams.
   :finding-key decides what \"the same finding again\" means and so what
   no-progress? can detect; it defaults to the diff review's
   default-finding-key. :attempt-key decides what \"we already tried this\"
   means, which is a different question — a finding re-routed to another layer
   is the same finding and a fresh attempt — and it defaults to :finding-key,
   the reading a pipeline that routes nothing wants. :attempted? decides
   whether a round aimed a repair at a finding at all, and so whether that
   round counts against the give-up counter — a ruling that dispatches nothing
   is a decision to try nothing rather than a repair that failed. It defaults
   to \"every appearance is an attempt\", which is what a pipeline with no way
   to rule on a finding wants. :changed? decides whether a round moved anything,
   and so whether a repeated finding set is a stall or a defect class the loop
   is still narrowing; it defaults to \"not known to have changed anything\",
   which leaves the set equality standing alone. :open? decides whether a
   finding is still owed, and so
   whether a pipeline saying stop has CONVERGED or merely stopped: a run that
   ends holding something reports :unresolved instead. It defaults to
   \"nothing is open\", which is the reading a pipeline with no notion of an
   unactioned finding wants.

   A round's ctx is rebuilt from scratch. `:carry` is the only channel a stage
   has to reach the next round, and it survives onto the terminal ctx too — see
   the comment on ctx0.

   A stage that throws on one of `terminal-reasons` ends the run on that status
   and on the round as far as it got: the ctx it puts on the ex-data as `:ctx`,
   if it has an account of its own partial work to give — see `run-pipeline`."
  [{:keys [run-id max-iters pipeline emit clock finding-key attempt-key
           attempted? judged-after open? changed?] :as config
    :or   {emit (fn [_]) clock #(Instant/now)
           finding-key default-finding-key
           attempted? (constantly true)
           open? (constantly false)
           changed? (constantly false)}}]
  (let [pipeline (or pipeline default-pipeline)
        ;; Defaults to the identity itself, which is what a pipeline with no
        ;; notion of routing wants: every appearance is an attempt.
        attempt-key (or attempt-key finding-key)
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
                  ;; still converged, because on a workstream with one baseline the
                  ;; latest entry IS the amended one, which is why nothing showed
                  ;; it. The fix belongs here rather than in either pipeline:
                  ;; there was no seam to put it through.
                  :carry carry}
            cfg  {:finding-key finding-key :attempt-key attempt-key
                  :attempted? attempted?
                  :prev-findings prev-findings :changed? changed?
                  :iter iter :max-iters max-iters}
            end? (fn [c prior] (terminal cfg c prior))
            ctx  (try
                   (run-pipeline ctx0 pipeline emit clock judged-after end? open?)
                   (catch clojure.lang.ExceptionInfo e
                     (let [{:keys [reason] :as data} (ex-data e)]
                       (if (terminal-reasons reason)
                         ;; On the round the throw came out of, not on ctx0.
                         ;; ctx0 is this round before any stage ran: finalized
                         ;; on it, a fix stage that throws after three fixers
                         ;; have reported success ends the run holding none of
                         ;; the round's findings, rulings, repairs or standing,
                         ;; and publishes `fix-attempts 0` over a branch they
                         ;; rewrote.
                         ;;
                         ;; `:unavailable` rides across opaque. The engine is
                         ;; told what stopped the run and carries the words
                         ;; without reading them, which is what keeps it shared
                         ;; with pipelines that have no reviewer at all.
                         (merge (assoc (or (:ctx data) ctx0)
                                       :status reason :error (ex-message e))
                                (select-keys data [:unavailable]))
                         (throw e)))))
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
