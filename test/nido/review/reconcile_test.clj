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
   [nido.session.lifecycle :as lifecycle]))

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

(def ^:private fixing
  [{:phase "review" :status "ok" :started-at "t0" :ended-at "t1"
    :layers [{:label "a1" :status "reviewed" :findings 2}]}
   {:phase "warden" :status "ok" :started-at "t1" :ended-at "t2" :rulings []}
   {:phase "fix" :status "running" :started-at "t2" :ended-at nil}])

(defn- write-run!
  "One run dir holding `report`, its files stamped `age-ms` old."
  [report age-ms]
  (let [dir (cstate/run-dir (:run-id report))
        p   (str (fs/path dir "report.json"))]
    (fs/create-dirs dir)
    (spit p (json/generate-string report))
    (fs/set-last-modified-time p (- (System/currentTimeMillis) age-ms))
    (fs/set-last-modified-time dir (- (System/currentTimeMillis) age-ms))
    p))

(defn- read-back [run-id]
  (json/parse-string (slurp (str (fs/path (cstate/run-dir run-id) "report.json"))) true))

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

(deftest a-dead-run-is-forced-terminal-and-queued-for-analysis
  (in-tmp-home
   (fn []
     (let [queued (atom [])]
       (with-redefs [queue/enqueue! (fn [e] (swap! queued conj e) "/q/1.edn")]
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
           (is (= 1 (count @queued))
               "a run forced terminal is worth-analysing? by construction, and
                the loops that die are the ones most worth reading")
           (is (= {:project :nido :trigger :review-analysis}
                  (:target (first @queued))))
           (is (:proceed? out)
               "it died reading the tree, so the branch is exactly what its
                reviewers found and there is nothing to warn about")))))))

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

(deftest an-orphan-still-being-written-to-is-left-alone
  (in-tmp-home
   (fn []
     (let [queued (atom [])]
       (with-redefs [queue/enqueue! (fn [e] (swap! queued conj e) "/q/1.edn")]
         (write-run! (report-in "review-dead" tree fixing) 0)
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
         ;; this is a process that vanished before its first reviewer.
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
