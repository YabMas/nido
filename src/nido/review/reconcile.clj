;; src/nido/review/reconcile.clj
(ns nido.review.reconcile
  "Settle the review runs that died on this tree, and say whether a new one may
   start.

   THE CLAIM IS THE LOCK AND THE LOCK IS THE PROCESS, so a run whose process
   dies drops the claim and leaves nothing behind saying it ever ended. Its
   report is still `running`, and it stays that way for the life of the run dir:
   `nido.review.analysis/enqueue!` fires on a terminated run, so a loop that
   died is also a loop nobody ever reads. This is the counterpart of
   `nido.coordinator.daemon.reconcile` for the review loop — force what did not
   end to a terminal state from what its run dir shows — and the moment it runs
   at is the analogue of daemon startup: the coordinator reconciles when it
   knows nothing of its own is running, and a claimant reconciles when it holds
   the claim, which is when nothing else on this tree can be alive AND
   supervised.

   THE SUBPROCESSES ARE WHAT THE LOCK NEVER COVERED. A fixer is a claude process
   the loop spawned, and `nido.platform.process` deliberately reaps only what a
   live JVM registered — kill the loop hard and its fixers keep running, keep
   writing to the run dir, and keep rewriting the branch. That is what makes
   `fix` the one phase a later claimant cannot ignore: the workstream reads as
   free while the tree is still being rewritten, and the run that takes it reads
   a stack mid-edit. Measured: a run that took the freed claim thirty seconds
   after its predecessor's last write reported three layers over thirty-two
   files where the branch had seven over eighty.

   So a claimant that finds one REFUSES, and settling the orphan is what clears
   the refusal — the next invocation finds a terminal report and proceeds. The
   one exception is an orphan one of whose AGENTS is still writing into the run
   dir, which is positive evidence of a live writer: that one is left
   non-terminal on purpose, so the refusal repeats until the writing stops."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.record.state :as cstate]
   [nido.review.analysis :as analysis]
   [nido.review.frontend :as frontend]
   [nido.review.report :as report]
   [nido.session.lifecycle :as lifecycle])
  (:import
   [java.time Instant]))

(def ^:private writer-quiet-ms
  "How recently one of a dead run's AGENTS must have written for them to count
   as still alive.

   READ IN ONE DIRECTION ONLY, and that is what makes a minute enough. Only that
   run's own agents write the entries `last-write-ms` folds, so a write inside
   the window PROVES a writer; silence proves nothing, because a fixer is quiet
   for as long as whatever tool it called takes. The inference this makes is
   `still running`, never `finished` — an orphan that has gone quiet is still
   refused, it is merely refused once instead of until it stops.

   A minute rather than an hour because the cost of the window is friction on
   the common case: Ctrl-C during a fix phase reaps the fixers and leaves them
   quiet from that moment, and a person re-running inside the window gets a
   refusal they then have to wait out."
  60000)

(def ^:private engine-written
  "The entries in a run dir the LOOP writes rather than one of its agents.

   Both names are one write: `report/persist!` spits `report.json.tmp` and
   renames it over `report.json`. That rename also creates and removes a
   directory entry, which is why `last-write-ms` drops the run directory's own
   mtime along with these two."
  #{"report.json" "report.json.tmp"})

(defn- last-write-ms
  "When one of `run-dir`'s own AGENTS last wrote, in epoch millis — nil when
   none of them ever has.

   One level deep, which is where everything a live agent touches is: agent.log,
   the per-target logs and the answer files. `artifacts/` is the only
   subdirectory and the loop writes nothing into it.

   THE ENGINE'S OWN WRITES ARE NOT EVIDENCE OF AN AGENT, and excluding them is
   what makes this fold answer the question `writer-quiet-ms` asks of it.
   `frontend/emit-fn` persists report.json on every event it folds, and the last
   event of a stopped run is the `:run-interrupted` its shutdown hook emits —
   which for a run mid-repair leaves the report unchanged and persists it
   anyway. Folded in, that write dates the run dir to the moment of the STOP, so
   the wait-it-out window starts a minute after the reap rather than at it.

   nil rather than 0 for a run whose agents never wrote, so that each caller has
   to say what it makes of that: no agent write is no evidence of a live writer,
   and it is not a timestamp either."
  [run-dir]
  (let [ms (->> (fs/list-dir run-dir)
                (remove #(engine-written (fs/file-name %)))
                (map #(.toMillis (fs/last-modified-time %))))]
    (when (seq ms) (reduce max ms))))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  fixing?
  "Whether this orphan stopped in the phase that rewrites the tree.

   The whole of what a claimant decides on: every other phase reads the branch,
   so a run that died in one left the tree exactly as its reviewers found it and
   there is nothing for the next run to be told. `report/interrupted` refuses to
   close a run stopped here for that reason — this refusal is what it is leaving
   the report open for."
  [orphan]
  (= report/rewriting-phase (:phase (:in-flight orphan))))

(defn ^{:malli/schema [:=> [:cat :Path [:maybe :string]] [:sequential :map]]}
  orphans
  "Every review run that was reading `cwd` and never wrote a terminal status,
   oldest write first. `own-run-id` is the caller's own run, excluded.

   Sound only because the caller holds the claim: two live runs on one tree are
   exactly what the claim excludes, so a report still saying `running` under a
   held claim belongs to a process that is gone. A claimless review — one
   outside a nido session — must not call this, having excluded nothing.

   Diff reviews alone, told by their `:base`: a record round names none, judges
   a ledger entry rather than the tree, and is analysed by nothing.

   Every run dir under ~/.nido/runs is opened, because nothing indexes runs by
   the tree they read: a report names its cwd and no path runs the other way.
   Nothing prunes a review run dir either — `bb nido:runs:clean` plans off the
   coordinator's own Run records, which a review run does not write — so this
   grows with the machine's whole review history. Measured at ~0.1s over 500
   reports, against a review run measured in minutes, and it happens once per
   run rather than once per round."
  [cwd own-run-id]
  (let [d (cstate/runs-dir)]
    (if-not (fs/exists? d)
      []
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (keep (fn [dir]
                   (let [report-path (str (fs/path dir "report.json"))
                         r           (frontend/read-report report-path)]
                     (when (and r
                                (= "running" (:status r))
                                (= cwd (get-in r [:target :cwd]))
                                (some? (get-in r [:target :base]))
                                (not= own-run-id (:run-id r)))
                       {:run-id      (:run-id r)
                        :run-dir     (str dir)
                        :report-path report-path
                        :report      r
                        :in-flight   (report/in-flight r)
                        :last-write  (last-write-ms dir)}))))
           (sort-by :last-write)
           vec))))

(defn- reviewed-names
  "Who the dead runs on `cwd` were reviewing for, in names — never a path into
   the tree. See `nido.review.analysis`'s namespace docstring for why the
   analysis is told names and nothing else.

   Every orphan here shares the cwd by construction, so this is asked once for
   the scan rather than once per run."
  [cwd]
  (try
    (when-let [{:keys [project session]} (lifecycle/session-from-cwd cwd)]
      {:reviewed-project project
       :reviewed-session session
       :reviewed-ws-id   (csession/workstream-id-for (keyword project) session)})
    (catch Throwable _ nil)))

(defn- analysis-run
  "What an orphan can tell the analysis about itself.

   Only what the report states. The counts a finished run supplies — what it
   settled, what it was still holding, what it gave up on — are read off the
   terminal ctx, and an orphan has none; they arrive as the zeros
   `analysis/payload` defaults them to, which is the same shape a
   `:review-failed` run already reaches the analysis in. `orphaned` in the
   status and the title is what says the counts are not a verdict, and
   report.json carries the round and phase it stopped in.

   `:dry-run?` is not passed because the report does not record the flag. It
   costs nothing: a dry run stops at the fix stage without launching anybody, so
   the only orphaned dry run is one killed mid-review, and analysing it says
   what any killed run's analysis says.

   `:in-flight` answers two questions with one value. It tells `worth-analysing?`
   the one orphan it must never refuse — the one that died in `fix`, whose fixers
   outlived it and whose count of targets read says nothing about the state it
   left the branch in — and `payload` publishes its phase, so the analysis is told
   which kind of orphan it holds rather than left to infer it from a run dir that
   is normally gone by then."
  [{:keys [run-id report-path report in-flight]} reviewed]
  (let [cover (report/coverage report)]
    (merge {:run-id           run-id
            :report-path      report-path
            :status           (:status report)
            :base             (get-in report [:target :base])
            :rounds           (or (get-in report [:summary :rounds]) 0)
            :fix-attempts     (or (get-in report [:summary :fix-attempts]) 0)
            :in-flight        in-flight
            :targets-reviewed (:reviewed cover)
            :targets-skipped  (:skipped cover)}
           reviewed)))

(defn- observed-at
  "When this orphan was last seen alive, as the instant string `report/orphaned`
   stamps as its `:ended-at`.

   Its agents' newest write when there is one: that is the moment the run
   stopped producing anything, which is what an `:ended-at` reconstructed after
   the fact can honestly claim to be.

   The report's own mtime for a run none of whose agents ever wrote. There is no
   agent moment to name, and the loop persisting its last event is then the last
   thing anybody can say about the run — a nil left to reach `Instant` would date
   it to 1970."
  [{:keys [last-write report-path]}]
  (str (Instant/ofEpochMilli (or last-write
                                 (.toMillis (fs/last-modified-time report-path))))))

(defn- settle-one!
  "Force one orphan terminal and OFFER it to the analysis.

   Offer, not enqueue: `analysis/worth-analysing?` is what decides, on the same
   list a finished run is judged against, and `:analysis` is nil for an orphan
   it refuses. Deciding here as well was a second gate on a shorter list — it
   asked whether a round had been FOLDED, which `apply-event` satisfies the
   instant `:phase-started` opens one, before any reviewer is launched.

   Best-effort, and the failure direction is the safe one: a report that could
   not be rewritten stays non-terminal, so the next claimant finds it again and
   refuses again rather than walking past a tree nobody vouched for."
  [orphan reviewed]
  (try
    (let [r (report/orphaned (:report orphan) (observed-at orphan))
          o (assoc orphan :report r)]
      (report/persist! r (:report-path orphan))
      (assoc o :analysis (analysis/enqueue! (analysis-run o reviewed))))
    (catch Exception e
      (assoc orphan :error (ex-message e)))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  settle!
  "Reconcile the dead runs on this tree and say whether the caller may review
   it. Call it holding the claim and before anything reads the branch.

   Returns `{:settled [..] :writing [..] :proceed? bool}`. `:settled` are the
   runs forced terminal and queued for analysis; `:writing` are the ones left
   alone because one of their agents is still writing. `:proceed?` is false when
   any orphan of either kind stopped in its fix phase — the tree was left
   mid-repair, and that is the caller's to be told rather than to discover.

   The asymmetry between the two lists is the whole mechanism. A settled orphan
   is gone from the next scan, so its refusal fires once and the invocation
   after it reviews the branch as it now stands. One still being written to is
   not settled, so its refusal repeats for as long as its agents keep going.

   Settles nothing, and proceeds, for a tree that resolves to no workstream —
   the same condition `tasks.nido-review/claiming` takes no claim on. Such a run
   has excluded nobody, so every other report saying `running` may belong to a
   process that is very much alive.

   `now` is an injection seam for the clock."
  [{:keys [cwd run-id now] :or {now #(System/currentTimeMillis)}}]
  (let [reviewed (reviewed-names cwd)
        t        (now)
        writing? (fn [o] (boolean (when-let [ms (:last-write o)]
                                    (and (fixing? o)
                                         (< (- t (long ms)) writer-quiet-ms)))))
        found    (if (:reviewed-ws-id reviewed) (orphans cwd run-id) [])
        {holding true settling false} (group-by writing? found)
        settled  (mapv #(settle-one! % reviewed) settling)]
    {:settled  settled
     :writing  (vec holding)
     :proceed? (not (or (seq holding) (some fixing? settled)))}))
