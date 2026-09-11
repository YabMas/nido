;; src/nido/review/analysis.clj
(ns nido.review.analysis
  "Hand a finished review loop to nido for analysis.

   The review loop is machinery that reviews other people's code; nothing was
   watching whether IT works. This enqueues one coordinator envelope per
   terminated run, targeting nido's own `:review-analysis` trigger, so a session
   on nido's side reads the run afterwards and says what the loop did well and
   what it got wrong.

   Two boundaries make this safe to fire from inside a review:

   It never touches the reviewed branch. The envelope is a file under
   ~/.nido/coordinator/queue/, the run it points at lives under ~/.nido/runs/,
   and the session the coordinator spawns for it belongs to project `nido`. The
   worktree the loop just reviewed is named here — as `:reviewed-session`, never
   as a path — so the analysis can say WHICH branch it is talking about without
   being handed a way to go and edit it.

   And it never fails the review. A review that finished is finished; an
   analysis that could not be queued is a missing side record, and turning that
   into a non-zero exit would mean the loop reports failure for work that
   succeeded. Every failure here is swallowed to stderr, exactly as
   `tasks.nido-review/append-review-entry!` does for the ledger."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.control :as control]
   [nido.coordinator.record.state :as cstate]))

(def target
  "Where the envelope is aimed. `:nido` is the project whose triggers.edn
   declares `:review-analysis`; the reviewed project is irrelevant to routing —
   every review loop, whatever it reviewed, is analysed nido-side."
  {:project :nido :trigger :review-analysis})

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  payload
  "Pure: the envelope payload for one terminated run.

   `:adapter`/`:id` form the external ref the coordinator dedups workstreams on.
   The adapter is named explicitly because `spawn/external-ref` defaults it to
   `:notion`, and a review run is not a Notion page — left to the default, every
   analysis would mint a workstream claiming a Notion identity it does not have.
   Keyed on the run id, a re-fire of the same run lands back in the workstream
   that already holds its analysis instead of starting a second one.

   The counts are duplicated out of the report on purpose. They are the whole
   story of the run if the run dir has been reclaimed by the time the analysis
   gets there — which is the normal end state of a run dir, not an edge case.

   `:fix-attempts` and `:defects-settled` are two different questions: how much
   repair work the run sent out, and how many defects a later reviewer's silence
   confirms came off the branch. A handle handed out in three rounds is three of
   the first and at most one of the second, so one number for both overstates
   what the loop achieved by roughly its own persistence.

   `:targets-reviewed` and `:targets-skipped` say what the status is a status
   OF. A skipped target was converged in an earlier run and not re-opened, so a
   `clean` over three of eight targets is mostly a memory — and an analysis
   grading the loop on a report it may not be able to open cannot recover that
   from anything else here.

   `:remaining-handed` and `:remaining-parked` are the only counts carried just
   when non-zero, so a converged run says nothing rather than 0. They split the
   remainder into the three things it can be: a repair already in the branch that
   no round checked, a question put to a human that only a human can answer, and
   — what is left over — findings no fixer was ever launched for.

   `:findings-kept` is beside them rather than inside them, and unlike them it is
   carried at zero. A declined defect and a deviated claim were decided, so they
   are not owed and not part of the remainder; an analysis still needs to know
   the run left some, because a run that declines its way to `converged` and one
   that fixes its way there are the same status and different behaviour. Zero is
   the sentence that says which — and a trigger template renders a missing value
   as the empty string, so an omitted key reaches the analysis as ` · kept`
   rather than as silence.

   It counts the design judge's own remainder too, and this is the one number in
   the payload that spans the loop AND the pass that judges it: a verdict
   needing no decision can still name a located defect no round raised, and that
   is a defect the branch ships on somebody's say-so like any other kept one.
   `verdict/kept-by-the-verdict` is the reading; the `:review` ledger entry
   cannot make it, because it is written before the pass runs.

   The title carries three of these out of all of it, and each one changes what
   the rest of them MEAN. It is what a human reads off the board without opening
   anything. A park is the one count that asks them for something. `died in fix`
   says the branch may have been left mid-rewrite, which nothing else in an
   orphan's title tells apart from a run that died reading. And a design verdict
   other than `sound` says the status is not the last word on the run —
   `converged · 0 still open · design strained` is a run whose own headline the
   judge went on to contradict. A `sound` verdict is left off because it agrees
   with the status, and this line is read by someone scanning for the ones that
   do not.

   `:unfixable` and `:parked` are what the run stopped ON, and they are here for
   the same reason the counts are: a status names the KIND of ending, and an
   analysis asked to say whether the loop stopped for the right reason needs the
   findings themselves. Both omitted when the run had nothing to hand over.

   `:drift` is the third of them, and the analysis is the reader it costs most:
   establishing why one drifted run stopped meant reading the fix stage's source
   against the reshape phase, because the two revisions the refusal is about
   were computed and dropped.

   `:standing` is the fourth and the one no count above touches at all: what the
   last warden knew was open and raised as nothing, so a run reports `0 still
   open` over a list of it — and, beside it, the inherited rows the last round
   could place on no layer, which are counted and were handed to nobody. An
   analysis asked whether the loop stopped for the right reason is the reader
   that most needs it, and the reader least able to go and look.

   `:design-verdict` and `:verdict-implementation` are the design judge's answer,
   and they are here on the counts' own argument carried further. The pass judges
   the whole run, so it answers AFTER the status is fixed and nothing the loop
   published knows what it said: one run reached the analysis as `converged · 0
   still open` over a verdict of `strained` with three contradicted invariants and
   two findings classified as implementation defects — repair the loop dispatched
   nobody for and named nowhere. The count is of `:implementation` alone because
   those are the ones that are work on the BRANCH. Both or neither, the count at
   zero, because a `sound` verdict over no implementation findings is the
   sentence that says the run is genuinely done.

   `:died-in` is the phase an ORPHAN stopped in, and it is the whole of what
   separates a harmless one from a dangerous one: a run killed while its fixers
   were rewriting the branch left a tree nobody vouched for, and one killed while
   a reviewer was reading left the tree exactly as it found it. `reconcile/settle!`
   has always computed it — it refuses the next claimant on it — and until it
   reached here both were filed under the same title."
  [{:keys [run-id report-path status rounds fix-attempts defects-settled
           findings-remaining findings-kept remaining-handed remaining-parked
           targets-reviewed targets-skipped unfixable parked standing
           drift base in-flight design-verdict verdict-implementation
           reviewed-project reviewed-session reviewed-ws-id]}]
  ;; `:in-flight` is the reconciler's reading of an orphan's report and is the
  ;; same value `worth-analysing?` gates on; the phase is the half of it that
  ;; means something to a reader, so it is published and the round is not.
  (let [died-in (:phase in-flight)
        verdict (some-> design-verdict name)]
    (cond-> {:adapter            :review-run
             :id                 (str run-id)
             :title              (str "review-loop " (name (or status :unknown))
                                      (when reviewed-session (str " · " reviewed-session))
                                      " · " (or rounds 0)
                                      " round" (when (not= 1 rounds) "s")
                                      (when died-in (str " · died in " died-in))
                                      (when (and verdict (not= "sound" verdict))
                                        (str " · design " verdict))
                                      (when (pos? (or remaining-parked 0))
                                        (str " · " remaining-parked " parked")))
             :run-id             (str run-id)
             :run-dir            (cstate/run-dir (str run-id))
             :report-path        report-path
             :status             (name (or status :unknown))
             :rounds             (or rounds 0)
             :fix-attempts       (or fix-attempts 0)
             :defects-settled    (or defects-settled 0)
             :findings-remaining (or findings-remaining 0)
             :findings-kept      (or findings-kept 0)
             :targets-reviewed   (or targets-reviewed 0)
             :targets-skipped    (or targets-skipped 0)}
      (pos? (or remaining-handed 0)) (assoc :remaining-handed remaining-handed)
      (pos? (or remaining-parked 0)) (assoc :remaining-parked remaining-parked)
      (seq unfixable)  (assoc :unfixable (mapv str unfixable))
      (seq parked)     (assoc :parked (vec parked))
      (seq standing)   (assoc :standing (vec standing))
      drift            (assoc :drift drift)
      base             (assoc :base base)
      died-in          (assoc :died-in died-in)
      verdict          (assoc :design-verdict verdict
                              :verdict-implementation (or verdict-implementation 0))
      reviewed-project (assoc :reviewed-project (name reviewed-project))
      reviewed-session (assoc :reviewed-session reviewed-session)
      reviewed-ws-id   (assoc :reviewed-ws-id reviewed-ws-id))))

(defn- orphan-worth-reading?
  "Whether a run whose process vanished left anything an analysis could read.

   Two answers, and they are different questions. It read something: at least
   one target reached a terminal read state, so there is reviewer behaviour in
   the run to grade. Or it died in `fix`: that one left agents rewriting a
   branch nobody was supervising, and what an analysis has to say about it is
   not about coverage at all — it is the most important run in the record to
   read, at any count.

   `:targets-reviewed` is `report/coverage`'s `:reviewed`, which counts a target
   only once a reviewer answered for it. Read off the count of ROUNDS instead —
   which is what `reconcile/settle-one!` gated on — this says yes to every run
   that got as far as opening a round, and a round opens on `:phase-started`,
   before a reviewer is launched."
  [{:keys [targets-reviewed in-flight]}]
  (or (= "fix" (:phase in-flight))
      (pos? (long (or targets-reviewed 0)))))

(defn ^{:malli/schema [:=> [:cat :map :boolean] :boolean]}
  worth-analysing?
  "Pure. Every terminal outcome is worth a look EXCEPT a dry run, a run that
   reviewed nothing, an orphan that stopped before it read anything, and a run
   that left no report to read.

   `:nothing-to-review` is the cheapest of all to exclude and the most obviously
   right: no reviewer read anything, so there is no loop behaviour in the run to
   analyse. Left in, every empty-diff review — a re-run on an unchanged branch,
   a stack whose layers were all folded away — provisions a worktree and an hour
   of budget to report that the loop did nothing, correctly.

   `:stack-conflicted` is excluded on the same ground and for a sharper reason:
   the run exists to stop in a second instead of spending six agents on a branch
   it cannot read, and queueing an analysis session would spend an hour of
   budget to say so. What that run found is a fact about the BRANCH, and it
   reaches a human already — through the :review ledger entry that names the
   change ids, and through the lane escalating on the status.

   An ORPHAN is excluded on exactly that ground and judged by `:targets-reviewed`
   — see `orphan-worth-reading?`. `orphaned` is not a status the loop reaches; it
   is stamped from outside, by whoever next takes the workstream's claim, and a
   process killed seconds into its first review phase reaches this having read
   nothing at all. Two such runs on one tree, alive 2.6s and 6.7s, each bought a
   worktree and an hour of Opus to report that nothing happened.

   A dry run drove the stages without letting a fixer touch anything, so what it
   produced says how the loop behaves under a flag rather than how it behaves;
   analysing it would fill the record with runs that were never trying.

   `report?` is the load-bearing one. Queueing is cheap, but what it queues is
   an agent session with a worktree and an hour of budget, and the first thing
   that session does is open the report. Enqueueing a run whose report does not
   exist provisions all of that to read a file that is not there. This gate was
   added after exactly that happened: a test suite drove the real command
   against a fake cwd, and the daemon spawned a session per test.

   Notably still included: `:review-failed`. A loop that could not review at all
   is the outcome most worth reading, and it still writes a report — the
   frontend persists one as the events arrive, so the failure is in it.

   Takes the run map the enqueue site already holds rather than the status
   alone, because two of the four exclusions are now facts about the run. That
   is also what keeps this the ONLY gate: an orphan reaches the analysis through
   `reconcile/settle-one!` and a finished one through `tasks.nido-review`, and a
   second gate at either call site is a second place for the list above to be
   incomplete."
  [{:keys [status dry-run?] :as run} report?]
  (boolean (and status
                (not (#{:nothing-to-review :stack-conflicted} (keyword status)))
                (not dry-run?)
                report?
                (or (not= :orphaned (keyword status))
                    (orphan-worth-reading? run)))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :any]]}
  enqueue!
  "Queue the analysis for one terminated run. Returns the envelope path, or nil
   when the run was not worth analysing or the write failed.

   Best-effort by contract: see the namespace docstring. The coordinator need
   not be running — an envelope sitting in the queue dir is picked up on the
   next drain, so a review run with the daemon down is analysed when it comes
   back up rather than lost."
  [{:keys [report-path] :as run}]
  (when (worth-analysing? run (boolean (and report-path (fs/exists? (str report-path)))))
    (try
      (cstate/ensure-dirs!)
      (control/fire! (:project target) (:trigger target) (payload run))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "review-loop: could not queue the run for analysis — "
                        (ex-message e))))
        nil))))
