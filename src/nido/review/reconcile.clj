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
   non-terminal on purpose, so the refusal repeats until the writing stops.

   SETTLING IS WHERE A DEAD RUN'S INHERITANCE IS KEPT. The run dir is the only
   copy of what it ruled, repaired and parked, and nothing reads a run dir after
   the fact, so settling writes a `:review-settled` entry out of it — the next
   run inherits from that rather than from the run before. And settling does not
   wait for a claimant: the coordinator sweeps for runs nobody holds the claim
   of (`settle-abandoned!`), because a branch nobody reviews again would
   otherwise keep its orphan, and its orphan's rulings, for ever."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.review.analysis :as analysis]
   [nido.review.frontend :as frontend]
   [nido.review.provenance :as provenance]
   [nido.review.report :as report]
   [nido.review.stages :as stages]
   [nido.review.verdict :as verdict]
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

(defn- unsettled?
  "Whether a report belongs to a run nobody has settled: still `running`, or
   `interrupted` by its own shutdown hook — which seals the report and writes
   nothing else, so what the run was holding is still only in its run dir."
  [r]
  (or (= "running" (:status r))
      (and (= report/interrupted-status (:status r)) (nil? (:settled r)))))

(defn- unsettled-reviews
  "Every diff-review run dir whose report is `unsettled?`, whatever tree it
   read, oldest write first — `own-run-id`, the caller's own run, excluded.

   Whether a `running` one is dead is not this fn's to say: it is a live run
   until something that holds its claim says otherwise. See `orphans` and
   `settle-abandoned!`."
  [own-run-id]
  (let [d (cstate/runs-dir)]
    (if-not (fs/exists? d)
      []
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (keep (fn [dir]
                   (let [report-path (str (fs/path dir "report.json"))
                         r           (frontend/read-report report-path)]
                     (when (and r
                                (unsettled? r)
                                (some? (get-in r [:target :base]))
                                (not= own-run-id (:run-id r)))
                       {:run-id      (:run-id r)
                        :run-dir     (str dir)
                        :report-path report-path
                        :report      r
                        :in-flight   (report/in-flight r)
                        :last-write  (last-write-ms dir)}))))
           (sort-by #(or (:last-write %) 0))
           vec))))

(defn ^{:malli/schema [:=> [:cat :Path [:maybe :string]] [:sequential :map]]}
  orphans
  "Every review run that was reading `cwd` and never wrote a terminal status —
   or was stopped by a person and never settled — oldest write first.
   `own-run-id` is the caller's own run, excluded.

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
  (filterv #(= cwd (get-in % [:report :target :cwd])) (unsettled-reviews own-run-id)))

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
  "What a settled run can tell the analysis about itself: what its report
   states, and the counts its `:review-settled` entry derived from it.

   The counts are the entry's rather than zeros. Defaulted, a run killed mid-fix
   holding a P1 was published as `0 still open`, which is the opposite of what a
   reader needed to hear; the entry derives them from the rounds the report
   folded, as a finished run's own entry does. A count it could not derive is
   passed as nil and published as unknown — see `analysis/payload`.

   `:dry-run?` is not passed because the report does not record the flag. It
   costs nothing: a dry run stops at the fix stage without launching anybody, so
   the only orphaned dry run is one killed mid-review, and analysing it says
   what any killed run's analysis says.

   `:in-flight` answers two questions with one value. It tells `worth-analysing?`
   the one orphan it must never refuse — the one that died in `fix`, which left
   the branch mid-repair whatever its count of targets read — and `payload`
   publishes its phase, so the analysis is told which kind of orphan it holds
   rather than left to infer it from a run dir that is normally gone by then."
  [{:keys [run-id report-path report in-flight entry recorded]} reviewed]
  (let [cover (report/coverage report)]
    (merge {:run-id             run-id
            :report-path        report-path
            :status             (:status report)
            :base               (get-in report [:target :base])
            :rounds             (or (get-in report [:summary :rounds]) 0)
            :fix-attempts       (:fix-attempts entry)
            :defects-settled    (:defects-settled entry)
            :findings-remaining (:findings-remaining entry)
            :findings-kept      (or (:findings-kept entry) 0)
            :remaining-handed   (:remaining-handed entry)
            :remaining-parked   (:remaining-parked entry)
            :standing           (:standing entry)
            :review-entry       recorded
            :in-flight          in-flight
            :targets-reviewed   (:reviewed cover)
            :targets-skipped    (:skipped cover)
            :machinery          (:machinery report)}
           reviewed)))

(defn- ruled-ids
  "Every id and handle the run ruled on, in any round — what answers a row the
   last run left, as `stages/unanswered-of` reads it."
  [final]
  (conj (mapv :findings (:history final)) (vec (:findings final))))

(defn- stopped
  "Where the run stopped, as the entry's `:stopped`: the round and phase, when a
   person stopped it, the fixer it had launched and not settled, and the repairs
   that round landed that no reviewer read. Off the report as it was left, or
   off the `:reason` the shutdown hook sealed one with. nil for a run that
   stopped between rounds with nothing in flight."
  [pre final]
  (when-let [{:keys [round phase fixing]} (or (report/in-flight pre)
                                              (get-in pre [:reason :interrupted])
                                              (get-in pre [:reason :orphaned]))]
    (let [stopped-at (or (:interrupted-at pre)
                         (when (= report/interrupted-status (:status pre)) (:ended-at pre)))
          landed (mapv (fn [{:keys [layer commit handed]}]
                         (cond-> {:handed (mapv str handed)}
                           layer  (assoc :layer (str layer))
                           commit (assoc :commit (str commit))))
                       (when (= report/rewriting-phase phase) (:fixes final)))]
      (cond-> {:round round}
        phase                    (assoc :phase phase)
        stopped-at               (assoc :at stopped-at)
        fixing                   (assoc :fixing
                                        (cond-> {:handed (mapv str (:handed fixing))}
                                          (:layer fixing) (assoc :layer (str (:layer fixing)))
                                          (:op fixing)    (assoc :op (str (:op fixing)))))
        (seq landed)             (assoc :landed landed)))))

(defn ^{:malli/schema [:=> [:cat :map :map [:sequential :map] [:maybe :string]] :map]}
  settled-entry
  "Pure: the `:review-settled` entry for a run that never finished, out of its
   report as it was left (`pre`) and as settling closed it (`post`). `prior` is
   what the last entry on the workstream left owed — `stages/prior-open` — which
   the run was handed and may not have finished asking about.

   Every count is the derivation a finished run's `:review` makes, over the
   rounds the report folded (`report/as-final`), so the two kinds of entry
   cannot disagree about what a remainder is. The one that can be unknown is
   `:fix-attempts`, for a fix phase that kept no account — see
   `report/account-lost?`.

   The last run's rows are carried AS THEY WERE, less those this run ruled on —
   not marked inherited, as a finished run marks them. Inheriting spends a
   row's one hop of attention, and a run that stopped did not finish paying it:
   a row it never put in front of a warden would otherwise drop at the next run
   having been read by nobody."
  [pre post prior report-path]
  (let [final   (report/as-final pre)
        own     (verdict/owed-rows final)
        seen    (into #{} (comp (mapcat (juxt :id :handle)) (remove nil?))
                      (concat own (verdict/open-across-run final)))
        carried (into [] (comp (remove #(contains? seen (:id %)))
                               (map #(select-keys % verdict/ledger-row-keys)))
                      (stages/unanswered-of prior (ruled-ids final)))
        open    (into own carried)
        kept    (verdict/ledger-findings #{} (verdict/kept-across-run final))
        handed  (count (filter :handed open))
        parked  (count (filter #(= :park (:disposition %)) open))
        cover   (report/coverage post)
        standing (some->> (:rounds pre)
                          (keep #(let [w (last (filter (fn [ph] (= "warden" (:phase ph)))
                                                       (:phases %)))]
                                   (when (= "ok" (:status w)) w)))
                          last :standing
                          (mapv #(select-keys % [:what :why-no-finding])))
        design  (some->> (:rounds pre) (mapcat :phases)
                         (filter #(= "review" (:phase %))) (keep :design-seq) last)
        where   (stopped pre final)]
    (cond-> {:format             :review-report
             :status             (keyword (:status post))
             :base               (str (get-in pre [:target :base]))
             :base-rev           (get-in pre [:target :base-rev])
             :rounds             (count (:rounds pre))
             :fix-attempts       (when-not (report/account-lost? pre)
                                   (get-in post [:summary :fix-attempts]))
             :defects-settled    (count (verdict/settled-by-fixing final))
             :findings-remaining (count open)
             :report-path        report-path}
      (seq open)       (assoc :open open)
      (seq kept)       (assoc :kept kept :findings-kept (count kept))
      (pos? handed)    (assoc :remaining-handed handed)
      (pos? parked)    (assoc :remaining-parked parked)
      (pos? (+ (:reviewed cover) (:skipped cover)))
      (assoc :targets-reviewed (:reviewed cover) :targets-skipped (:skipped cover))
      (seq standing)   (assoc :standing standing)
      where            (assoc :stopped where)
      design           (assoc :design {:seq design}))))

(defn- newer-review?
  "Whether `ws-id` already holds a review record written after this run started.

   Then this run is not the last word on the workstream and must not become it:
   `stages/prior-open` reads the newest entry, so appending a run that died
   before its successor finished would hand the next run the dead one's rows in
   place of the live one's. What the successor did not inherit is lost either
   way; the newer record is the truer of the two."
  [project ws-id started-at]
  (boolean
   (when-let [last-at (:at (stages/last-review project ws-id))]
     (try (.isAfter (java.time.Instant/parse last-at) (java.time.Instant/parse started-at))
          (catch Exception _ true)))))

(defn- append-settled!
  "Offer the settled run's entry to the workstream it reviewed, and say what
   happened, in `report/with-review-entry`'s shape. Nothing here throws.

   `:superseded` is the one answer peculiar to a settled run — see
   `newer-review?`."
  [entry {:keys [reviewed-project reviewed-ws-id]} started-at]
  (let [project (some-> reviewed-project keyword)]
    (if-not (and project reviewed-ws-id)
      {:ledger :no-workstream}
      (try
        (cond
          (newer-review? project reviewed-ws-id started-at)
          {:ledger :superseded :ws-id reviewed-ws-id
           :because "the workstream holds a review record written after this run started"}

          (and (nil? (:design entry)) (ws/holds-design? (ws/read-ws project reviewed-ws-id)))
          {:ledger :no-design :ws-id reviewed-ws-id
           :because (str "the run's report names no design, and the workstream holds one,"
                         " so there is no design its rulings were made under")}

          :else
          (do (ws/append-entry! project reviewed-ws-id {:kind :review-settled} (pr-str entry))
              {:ledger :appended :ws-id reviewed-ws-id}))
        (catch Exception e
          {:ledger :refused :ws-id reviewed-ws-id :because (ex-message e)})))))

(defn- observed-ms
  "`observed-at`, in epoch millis."
  [{:keys [last-write report-path]}]
  (or last-write (.toMillis (fs/last-modified-time report-path))))

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
  [orphan]
  (str (Instant/ofEpochMilli (observed-ms orphan))))

(def ^:private write-tools-re
  "A tool call in a fixer's stream-json transcript that writes a file."
  #"\"name\"\s*:\s*\"(?:Edit|MultiEdit|Write|NotebookEdit)\"")

(defn- fixer-wrote?
  "Whether the fixer a run stopped under had called a tool that writes — read off
   its own transcript, the only record of what it did before the stop. nil when
   there is no transcript to read, which says nothing either way."
  [run-id {:keys [layer]} round]
  (let [log (stages/fixer-log run-id layer round ".log")]
    (when (fs/exists? log)
      (boolean (re-find write-tools-re (slurp log))))))

(defn- settle-one!
  "Force one orphan terminal, keep what it was holding on the workstream it
   reviewed, and OFFER it to the analysis.

   `:interrupted` when the run's own shutdown hook stamped the stop, `:orphaned`
   otherwise — see `report/settled`. A report the hook already sealed keeps the
   status it has, and is not offered to the analysis: settling it adds only the
   entry, which is all a person's stop outside `fix` ever lacked. The report is stamped with who settled it
   and when, under `:settled`, because it is rewritten here by code that is not
   the code that ran it, and an analysis reading it weeks later has to be able
   to say which.

   The `:review-settled` entry is written before the analysis is offered, so the
   analysis finds it; what became of it is on the report under `:review-entry`,
   as it is for a finished run.

   Offer, not enqueue: `analysis/worth-analysing?` is what decides, on the same
   list a finished run is judged against, and `:analysis` is nil for an orphan
   it refuses. `analyse?` false skips the offer outright — see
   `settle-abandoned!`.

   Best-effort, and the failure direction is the safe one: a report that could
   not be rewritten stays non-terminal, so the next claimant finds it again and
   refuses again rather than walking past a tree nobody vouched for."
  ([orphan reviewed] (settle-one! orphan reviewed true))
  ([{:keys [run-id report report-path in-flight] :as orphan} reviewed analyse?]
   (try
     (let [open?    (= "running" (:status report))
           post     (-> (cond-> report open? (report/settled (observed-at orphan)))
                        (assoc :settled {:at        (str (Instant/now))
                                         :machinery (provenance/loaded-from)}))
           prior    (or (some-> (get-in report [:target :cwd]) stages/prior-open) [])
           entry    (settled-entry report post prior report-path)
           recorded (append-settled! entry reviewed (:started-at report))
           post     (report/with-review-entry post recorded)
           o        (assoc orphan :report post :entry entry :recorded recorded
                           :wrote? (when-let [f (:fixing in-flight)]
                                     (fixer-wrote? run-id f (:round in-flight))))]
       (report/persist! post report-path)
       (cond-> o
         (and analyse? open?) (assoc :analysis (analysis/enqueue! (analysis-run o reviewed)))))
     (catch Exception e
       (assoc orphan :error (ex-message e))))))

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

   `now` is an injection seam for the clock; `analyse?` is `settle-one!`'s, and
   `settle?` leaves alone, unsettled and unreported, any orphan it refuses."
  [{:keys [cwd run-id now analyse? settle?]
    :or   {now #(System/currentTimeMillis) analyse? (constantly true) settle? (constantly true)}}]
  (let [reviewed (reviewed-names cwd)
        t        (now)
        writing? (fn [o] (boolean (when-let [ms (:last-write o)]
                                    (and (fixing? o)
                                         (< (- t (long ms)) writer-quiet-ms)))))
        found    (if (:reviewed-ws-id reviewed) (filterv settle? (orphans cwd run-id)) [])
        {holding true settling false} (group-by writing? found)
        settled  (mapv #(settle-one! % reviewed (boolean (analyse? %))) settling)]
    {:settled  settled
     :writing  (vec holding)
     :proceed? (not (or (seq holding) (some fixing? settled)))}))

(def ^:private analyse-within-ms
  "How recently a run the sweep settles must have died for it to be offered to
   the analysis.

   The analysis grades the machinery the run ran on, and a run dead for weeks
   ran on machinery main has long since moved past — its findings would be
   re-filed defects already fixed. The sweep's first pass also meets every run
   that ever died without a claimant, dozens of them, and an analysis session
   each is an hour of budget apiece to learn nothing current. Still settled,
   and still kept on the ledger: only the analysis is skipped."
  (* 3 24 60 60 1000))

(def ^:private fix-grace-ms
  "How long the sweep leaves a run stopped mid-repair for a claimant to find.

   A claimant settling one is REFUSED, and the refusal is the one place the
   person who stopped it is told what the branch is now: which repairs landed
   unread, which fixer was cut off and the `jj op restore` that undoes it. The
   sweep settling it first would take that notice away from somebody coming
   back to the branch within the day, and waiting costs nothing — a claimant
   settles on its way in whatever the sweep did."
  (* 12 60 60 1000))

(defn ^{:malli/schema [:=> [:cat :map] [:sequential :map]]}
  settle-abandoned!
  "Settle every review run whose report still says `running` and whose claim
   nobody holds — the coordinator's half of `settle!`, so a dead run's
   inheritance reaches its workstream when the run dies rather than when
   somebody next reviews that branch, which may be never.

   Per tree, under that workstream's claim, taken exactly as a claimant takes it:
   holding it is what proves every `running` report on the tree dead. A tree
   whose claim is held is left to its holder, which settles on its way in. A tree
   that resolves to no workstream is left alone — no claim can prove its runs
   dead, and a review outside a session is not the coordinator's to close.

   An orphan whose agents are still writing is left for the next sweep, as
   `settle!` leaves it for the next claimant, and so is one stopped mid-repair
   for `fix-grace-ms`. The claim held here is released the moment settling
   ends; no review runs under it.

   Returns one `settle!` result per tree it settled, each carrying `:cwd`.
   `now` is an injection seam for the clock."
  [{:keys [now] :or {now #(System/currentTimeMillis)}}]
  (let [t (now)]
    (into []
          (keep (fn [cwd]
                  (when-let [[project ws-id] (stages/project+ws-from-cwd cwd)]
                    (let [res (activity/with-claim
                               project ws-id
                               {:kind :diff-review :target {:settling true}
                                :run-id (str "settle-" (random-uuid)) :report-path nil}
                               #(settle! {:cwd cwd :run-id nil :now (constantly t)
                                          :settle?  (fn [o] (or (not (fixing? o))
                                                                (<= fix-grace-ms (- t (long (observed-ms o))))))
                                          :analyse? (fn [o] (< (- t (long (observed-ms o))) analyse-within-ms))}))]
                      (when-not (activity/refused? res)
                        (when (or (seq (:settled res)) (seq (:writing res)))
                          (assoc res :cwd cwd)))))))
          (distinct (keep #(get-in % [:report :target :cwd]) (unsettled-reviews nil))))))
