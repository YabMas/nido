(ns nido.review.reconcile-test
  (:require
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.source.queue :as queue]
   [nido.platform.core :as core]
   [nido.review.reconcile :as reconcile]
   [nido.review.report :as report]
   [nido.session.lifecycle :as lifecycle])
  (:import
   [java.time Instant]))

(def ^:private tree "/Users/x/Code/brian-worktrees/fix-thing")

(defn- report-in
  "A report folded to `phases` of round 1, never finalized — the shape a run
   leaves behind when its process disappears."
  [run-id cwd phases]
  (-> (report/init {:run-id run-id :cwd cwd :base "main" :started-at "t0"})
      (assoc :rounds [{:round 1 :status "running" :started-at "t0" :ended-at nil
                       :phases phases}])))

(def ^:private reviewing
  [{:phase "review" :status "running" :started-at "t0" :ended-at nil
    :layers [{:label "a1" :status "running"}]}])

(def ^:private read-one-then-died
  "A run that got one layer's answer back and was killed on the next."
  [{:phase "review" :status "running" :started-at "t0" :ended-at nil
    :layers [{:label "a1" :status "reviewed" :findings 0}
             {:label "stack" :stack? true :status "running"}]}])

(def ^:private fixing
  [{:phase "review" :status "ok" :started-at "t0" :ended-at "t1"
    :layers [{:label "a1" :status "reviewed" :findings 2}]}
   {:phase "warden" :status "ok" :started-at "t1" :ended-at "t2" :rulings []}
   {:phase "fix" :status "running" :started-at "t2" :ended-at nil}])

(defn- write-run!
  "One run dir holding `report`, plus an `agent-files` map of `{name age-ms}` —
   the entries a run's own agents write, which are the only ones the quiet
   window is measured over.

   `age-ms` ages report.json AND the directory itself, because that pair is what
   one `report/persist!` touches: it stages through `report.json.tmp` and renames
   it over, and the rename creates and removes a directory entry. Stamped last so
   writing the agent files does not bump it back to now."
  ([report age-ms] (write-run! report age-ms {}))
  ([report age-ms agent-files]
   (let [dir (cstate/run-dir (:run-id report))
         p   (str (fs/path dir "report.json"))
         t   (System/currentTimeMillis)]
     (fs/create-dirs dir)
     (spit p (json/generate-string report))
     (doseq [[nm age] agent-files]
       (let [f (str (fs/path dir nm))]
         (spit f "")
         (fs/set-last-modified-time f (- t age))))
     (fs/set-last-modified-time p (- t age-ms))
     (fs/set-last-modified-time dir (- t age-ms))
     p)))

(defn- read-back [run-id]
  (json/parse-string (slurp (str (fs/path (cstate/run-dir run-id) "report.json"))) true))

(defn- ended-ms-ago
  "How long before now `run-id`'s settled report says it ended."
  [run-id]
  (- (System/currentTimeMillis)
     (.toEpochMilli (Instant/parse (:ended-at (read-back run-id))))))

(defn- in-tmp-home
  "Run `f` with ~/.nido pointed at a scratch dir, and with the workstream
   resolution the claim depends on answering yes."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    lifecycle/session-from-cwd (constantly {:project :brian
                                                            :session "fix-thing"})
                    csession/workstream-id-for (constantly "ws-1")]
        (f))
      (finally (fs/delete-tree tmp)))))

;; ---- what counts as an orphan -------------------------------------------

(deftest a-run-that-never-ended-on-this-tree-is-an-orphan
  (in-tmp-home
   (fn []
     (write-run! (report-in "review-dead" tree reviewing) 0)
     (let [[o :as os] (reconcile/orphans tree "review-mine")]
       (is (= 1 (count os)))
       (is (= "review-dead" (:run-id o)))
       (is (= {:round 1 :phase "review"} (:in-flight o))
           "where it stopped is what a claimant decides on — every phase but
            fix only reads the tree")))))

(deftest a-finished-run-is-not-an-orphan
  (in-tmp-home
   (fn []
     (write-run! (assoc (report-in "review-done" tree reviewing) :status "converged") 0)
     (is (empty? (reconcile/orphans tree "review-mine"))
         "a terminal report is a verdict somebody reached; re-closing it would
          overwrite that verdict with `orphaned`"))))

(deftest a-run-on-another-tree-is-not-this-claimants-business
  (in-tmp-home
   (fn []
     (write-run! (report-in "review-elsewhere" "/Users/x/Code/other" reviewing) 0)
     (is (empty? (reconcile/orphans tree "review-mine"))
         "holding this workstream's claim says nothing about another worktree,
          where a run with the same shape may be alive"))))

(deftest the-callers-own-run-is-not-an-orphan
  (in-tmp-home
   (fn []
     (write-run! (report-in "review-mine" tree reviewing) 0)
     (is (empty? (reconcile/orphans tree "review-mine"))))))

(deftest a-run-that-recorded-being-stopped-is-not-an-orphan
  (in-tmp-home
   (fn []
     ;; The whole payoff of writing the report from the shutdown hook. The run
     ;; said how it ended, so there is nothing for a later claimant to settle:
     ;; no restamp, no refusal, and no analysis session to read a run that
     ;; already accounts for itself.
     (write-run! (report/interrupted (report-in "review-stopped" tree reviewing) "t3") 0)
     (is (empty? (reconcile/orphans tree "review-mine"))))))

(deftest a-run-stopped-mid-repair-is-still-an-orphan
  (in-tmp-home
   (fn []
     ;; `report/interrupted` refuses to close this one, and the refusal is for
     ;; this: its fixers had rewritten the tree and nothing else tells the next
     ;; claimant so. A person pressing Ctrl-C is still a branch left mid-repair.
     (let [r (report-in "review-stopped-fixing" tree fixing)]
       (is (nil? (report/interrupted r "t3")))
       (write-run! r 0)
       (is (= ["review-stopped-fixing"] (mapv :run-id (reconcile/orphans tree "review-mine"))))))))

(deftest a-record-round-is-not-a-review-run
  (in-tmp-home
   (fn []
     ;; A baseline round names no base: it judges a ledger entry, not the tree,
     ;; and `analysis/enqueue!` has never been fired for one.
     (write-run! (assoc-in (report-in "baseline-loop-1" tree reviewing)
                           [:target :base] nil)
                 0)
     (is (empty? (reconcile/orphans tree "review-mine"))))))

;; ---- settling ------------------------------------------------------------

(deftest a-dead-run-is-forced-terminal-all-the-way-down
  (in-tmp-home
   (fn []
     (with-redefs [queue/enqueue! (constantly "/q/1.edn")]
       (write-run! (report-in "review-dead" tree reviewing) 0)
       (let [out (reconcile/settle! {:cwd tree :run-id "review-mine"})
             r   (read-back "review-dead")]
         (is (= "orphaned" (:status r))
             "the report is the only record the run leaves, and `running` in it
              is a claim about a process that no longer exists")
         (is (some? (:ended-at r)))
         (is (= {:round 1 :phase "review"} (:orphaned (:reason r)))
             "what it stopped ON, where a finished run carries its judgement")
         (is (= "orphaned" (get-in r [:rounds 0 :status])))
         (is (= "orphaned" (get-in r [:rounds 0 :phases 0 :status]))
             "a phase left saying `running` makes the final frame animate a
              stage that stopped hours ago")
         (is (= "orphaned" (get-in r [:rounds 0 :phases 0 :layers 0 :status]))
             "and the target rows with it: the same spinner argument reaches
              them, and `coverage` reads them to say what the run covered")
         (is (:proceed? out)
             "it died reading the tree, so the branch is exactly what its
              reviewers found and there is nothing to warn about"))))))

(deftest an-orphan-whose-reviewers-never-answered-buys-no-analysis-session
  ;; The measured waste: two runs on this very tree lived 2.6s and 6.7s, each
  ;; killed inside its first review phase, and each provisioned a lite worktree
  ;; and an hour of Opus to report that nothing happened. Settling it is still
  ;; required — the refusal has to clear — but there is no loop behaviour in it.
  (in-tmp-home
   (fn []
     (let [queued (atom [])]
       (with-redefs [queue/enqueue! (fn [e] (swap! queued conj e) "/q/1.edn")]
         (write-run! (report-in "review-dead" tree reviewing) 0)
         (let [out (reconcile/settle! {:cwd tree :run-id "review-mine"})]
           (is (= 1 (count (:settled out))))
           (is (empty? @queued))
           (is (nil? (:analysis (first (:settled out))))
               "the gate is `analysis/worth-analysing?`, so refusing shows up
                here as no envelope rather than as a key nobody set")))))))

(deftest an-orphan-that-got-an-answer-out-of-a-reviewer-is-analysed
  ;; It produced loop behaviour, and how a run that was working came to stop is
  ;; exactly what an analysis reads.
  (in-tmp-home
   (fn []
     (let [queued (atom [])]
       (with-redefs [queue/enqueue! (fn [e] (swap! queued conj e) "/q/1.edn")]
         (write-run! (report-in "review-dead" tree read-one-then-died) 0)
         (reconcile/settle! {:cwd tree :run-id "review-mine"})
         (is (= 1 (count @queued)))
         (is (= {:project :nido :trigger :review-analysis}
                (:target (first @queued))))
         (is (= 1 (get-in (first @queued) [:payload :targets-reviewed]))
             "one target answered for, and the row the run died on is not a
              second — the count the analysis is briefed on is the true one"))))))

(deftest a-run-that-died-repairing-the-branch-refuses-the-next-one
  (in-tmp-home
   (fn []
     (with-redefs [queue/enqueue! (constantly "/q/1.edn")]
       (write-run! (report-in "review-dead" tree fixing) (* 5 60 1000))
       (let [out (reconcile/settle! {:cwd tree :run-id "review-mine"})]
         (is (not (:proceed? out))
             "its fixers outlived it and landed commits no round reviewed, so
              the branch is not what anybody signed off — that is the caller's
              to be told rather than to discover in a report reading 3 layers
              where the branch has 7")
         (is (= 1 (count (:settled out))))
         (is (reconcile/fixing? (first (:settled out)))))
       (testing "and the refusal clears itself"
         (let [again (reconcile/settle! {:cwd tree :run-id "review-mine"})]
           (is (:proceed? again)
               "settling is what makes the refusal fire once: the next
                invocation finds a terminal report and reviews the branch as it
                now stands")))))))

(deftest an-orphan-whose-agent-is-still-writing-is-left-alone
  (in-tmp-home
   (fn []
     (let [queued (atom [])]
       (with-redefs [queue/enqueue! (fn [e] (swap! queued conj e) "/q/1.edn")]
         (write-run! (report-in "review-dead" tree fixing) 0 {"agent.log" 0})
         (let [out (reconcile/settle! {:cwd tree :run-id "review-mine"})]
           (is (not (:proceed? out)))
           (is (= 1 (count (:writing out))))
           (is (empty? (:settled out)))
           (is (empty? @queued)
               "the run is not over — its agents are still writing, and an
                analysis queued now would read a report they have not finished
                producing the evidence for")
           (is (= "running" (:status (read-back "review-dead")))
               "left non-terminal ON PURPOSE, so the refusal repeats for as long
                as something is still rewriting the branch")))))))

(deftest the-loops-own-persist-does-not-hold-an-orphan
  (in-tmp-home
   (fn []
     (with-redefs [queue/enqueue! (constantly "/q/1.edn")]
       ;; Ctrl-C reaps the fixers and THEN runs the shutdown hook, whose
       ;; `:run-interrupted` folds to (or (interrupted report at) report) — for a
       ;; run mid-repair the report is unchanged and persisted anyway. So all
       ;; three things that persist touches carry the moment of the stop:
       ;; report.json, the tmp it renames over, and the directory entry that
       ;; rename creates and removes. Only agent.log says when a fixer last did
       ;; anything, and here that was five minutes ago.
       (let [p (write-run! (report-in "review-dead" tree fixing) 0
                           {"agent.log" (* 5 60 1000)})]
         (spit (str p ".tmp") "")
         (let [out (reconcile/settle! {:cwd tree :run-id "review-mine"})]
           (is (empty? (:writing out))
               "the window exists to wait out a live fixer; counting the loop's
                own dying write starts it a minute after the reap instead of at
                it, and the loop is the thing that died")
           (is (= 1 (count (:settled out))))
           (is (not (:proceed? out))
               "settled is not proceed — a branch left mid-repair still refuses
                this caller, but once rather than for a minute")))))))

(deftest an-orphan-is-dated-by-its-agents-last-write
  (in-tmp-home
   (fn []
     (with-redefs [queue/enqueue! (constantly "/q/1.edn")]
       (write-run! (report-in "review-dead" tree fixing) 0
                   {"agent.log" (* 5 60 1000)})
       (reconcile/settle! {:cwd tree :run-id "review-mine"})
       (is (< (* 4 60 1000) (ended-ms-ago "review-dead"))
           "`:ended-at` is a fact about the run, so it has to be when the run
            stopped PRODUCING — dating it to the engine's dying write hands the
            analysis a run that went on working after everything was reaped")))))

(deftest an-orphan-whose-agents-never-wrote-is-dated-by-its-report
  (in-tmp-home
   (fn []
     (with-redefs [queue/enqueue! (constantly "/q/1.edn")]
       ;; A run killed before it launched anybody. There is no agent moment to
       ;; name and the report's own mtime is the last thing anybody can say
       ;; about it; left to a missing one, `:ended-at` would read 1970.
       (write-run! (report-in "review-dead" tree reviewing) (* 5 60 1000))
       (reconcile/settle! {:cwd tree :run-id "review-mine"})
       (let [lag (ended-ms-ago "review-dead")]
         (is (< (* 4 60 1000) lag (* 6 60 1000))))))))

(deftest a-run-that-stopped-reading-is-settled-however-recently-it-wrote
  ;; The quiet window is only ever consulted about the fix phase. A dead
  ;; reviewer mutates nothing, so there is nothing to hold the next run back.
  (in-tmp-home
   (fn []
     (with-redefs [queue/enqueue! (constantly "/q/1.edn")]
       (let [out (do (write-run! (report-in "review-dead" tree reviewing) 0)
                     (reconcile/settle! {:cwd tree :run-id "review-mine"}))]
         (is (:proceed? out))
         (is (= 1 (count (:settled out)))))))))

(deftest a-run-that-folded-no-round-is-closed-without-an-analysis
  (in-tmp-home
   (fn []
     (let [queued (atom [])]
       (with-redefs [queue/enqueue! (fn [e] (swap! queued conj e) "/q/1.edn")]
         ;; No round at all: the fold opens one only when a phase starts, so
         ;; this is a process that vanished before its first reviewer. It is
         ;; refused by the same gate an orphan with an empty round is — nothing
         ;; here treats a folded round as evidence a reviewer ran.
         (write-run! (assoc (report-in "review-stillborn" tree []) :rounds []) 0)
         (let [out (reconcile/settle! {:cwd tree :run-id "review-mine"})]
           (is (= "orphaned" (:status (read-back "review-stillborn"))))
           (is (:proceed? out))
           (is (empty? @queued)
               "no reviewer read a line, so there is no loop behaviour to
                analyse — and an envelope here provisions a worktree and an hour
                of budget to say nothing happened")))))))

(deftest a-tree-with-no-workstream-settles-nothing
  ;; `claiming` takes no claim there, so this run has excluded nobody: another
  ;; process may be alive and holding exactly the report this would close.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    lifecycle/session-from-cwd (constantly nil)]
        (write-run! (report-in "review-dead" tree fixing) (* 5 60 1000))
        (let [out (reconcile/settle! {:cwd tree :run-id "review-mine"})]
          (is (:proceed? out))
          (is (empty? (:settled out)))
          (is (= "running" (:status (read-back "review-dead"))))))
      (finally (fs/delete-tree tmp)))))
