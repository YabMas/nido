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

   The counts are duplicated out of the report on purpose. They are what the
   payload's title is built from, and they are the whole story of the run if the
   run dir has been reclaimed by the time the analysis gets there — which is the
   normal end state of a run dir, not an edge case."
  [{:keys [run-id report-path status rounds findings-fixed findings-remaining
           base reviewed-project reviewed-session reviewed-ws-id]}]
  (cond-> {:adapter            :review-run
           :id                 (str run-id)
           :title              (str "review-loop " (name (or status :unknown))
                                    (when reviewed-session (str " · " reviewed-session))
                                    " · " (or rounds 0)
                                    " round" (when (not= 1 rounds) "s"))
           :run-id             (str run-id)
           :run-dir            (cstate/run-dir (str run-id))
           :report-path        report-path
           :status             (name (or status :unknown))
           :rounds             (or rounds 0)
           :findings-fixed     (or findings-fixed 0)
           :findings-remaining (or findings-remaining 0)}
    base             (assoc :base base)
    reviewed-project (assoc :reviewed-project (name reviewed-project))
    reviewed-session (assoc :reviewed-session reviewed-session)
    reviewed-ws-id   (assoc :reviewed-ws-id reviewed-ws-id)))

(defn ^{:malli/schema [:=> [:cat :any :boolean :boolean] :boolean]}
  worth-analysing?
  "Pure. Every terminal outcome is worth a look EXCEPT a dry run, a run that
   reviewed nothing, and a run that left no report to read.

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
   frontend persists one as the events arrive, so the failure is in it."
  [status dry-run? report?]
  (boolean (and status
                (not (#{:nothing-to-review :stack-conflicted} (keyword status)))
                (not dry-run?)
                report?)))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :any]]}
  enqueue!
  "Queue the analysis for one terminated run. Returns the envelope path, or nil
   when the run was not worth analysing or the write failed.

   Best-effort by contract: see the namespace docstring. The coordinator need
   not be running — an envelope sitting in the queue dir is picked up on the
   next drain, so a review run with the daemon down is analysed when it comes
   back up rather than lost."
  [{:keys [status dry-run? report-path] :as run}]
  (when (worth-analysing? status dry-run?
                          (boolean (and report-path (fs/exists? (str report-path)))))
    (try
      (cstate/ensure-dirs!)
      (control/fire! (:project target) (:trigger target) (payload run))
      (catch Exception e
        (binding [*out* *err*]
          (println (str "review-loop: could not queue the run for analysis — "
                        (ex-message e))))
        nil))))
