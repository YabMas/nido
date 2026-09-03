(ns tasks.nido-review-claim-test
  "What a review invocation does when the workstream is already claimed. The
   branch is the whole layer: asking for the round that is already running means
   you want to watch it; asking for a different one means you want work the
   holder is not doing."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [nido.coordinator.record.activity :as activity]
   [nido.platform.core :as core]
   [nido.review.frontend :as frontend]
   [nido.review.stages :as stages]
   [tasks.nido-review :as review]))

(use-fixtures :each
  (fn [f]
    (let [tmp (str (fs/create-temp-dir {:prefix "nido-review-claim"}))]
      (try (with-redefs [core/nido-root (constantly tmp)] (f))
           (finally (fs/delete-tree tmp))))))

(def their {:kind :diff-review :run-id "review-7" :pid 4242
            :started-at "2026-09-03T10:00:00Z"
            :report-path "/runs/review-7/report.json"})

(deftest a-refusal-says-what-is-running-not-that-something-is
  (let [msg (review/refusal-lines {:kind :design-round} their nil)]
    (testing "names the activity in words rather than printing a keyword"
      (is (str/includes? msg "a branch review"))
      (is (str/includes? msg "a design round"))
      (is (not (str/includes? msg ":diff-review"))))
    (testing "carries what the reader needs next: the report, and the pid they
              would have to end — ending a run is theirs to decide"
      (is (str/includes? msg "/runs/review-7/report.json"))
      (is (str/includes? msg "4242"))
      (is (str/includes? msg "kill 4242")))
    (testing "says WHY, so the refusal is not just a locked door"
      (is (str/includes? msg "same tree")))))

(deftest an-unknown-kind-still-produces-a-usable-refusal
  (testing "a claim written by a newer nido must not make an older one print
            nothing useful"
    (let [msg (review/refusal-lines {:kind :diff-review}
                                    (assoc their :kind :some-new-round) nil)]
      (is (str/includes? msg "some-new-round")))))

(deftest a-refusal-does-not-tell-anyone-to-kill-the-coordinator
  (testing "a mechanical round runs INSIDE the daemon, which hosts every other
            workstream it drives — `kill` there ends all of them, so the pid
            stays display and the instruction goes"
    (let [msg (review/refusal-lines {:kind :diff-review}
                                    (assoc their :kind :baseline-round) 4242)]
      (is (not (str/includes? msg "kill")))
      (is (str/includes? msg "coordinator daemon"))
      (is (str/includes? msg "wait for this one to finish"))
      (is (str/includes? msg "4242") "the pid is still shown; it is what it names that changed"))
    (testing "and the same round run by hand is an ordinary process to end"
      (is (str/includes? (review/refusal-lines {:kind :diff-review}
                                               (assoc their :kind :baseline-round) 9999)
                         "kill 4242")))))

(deftest the-same-round-of-a-different-record-is-refused-not-joined
  (testing "a baseline round names WHICH baseline. Joining one that is verifying
            another entry watches it finish and reports a verification of the
            entry nobody verified"
    (let [msg (review/refusal-lines {:kind :baseline-round :target {:seq 7 :code-cwd nil}}
                                    (assoc their :kind :baseline-round
                                           :target {:seq 4 :code-cwd nil})
                                    nil)]
      (is (str/includes? msg "seq 4"))
      (is (str/includes? msg "seq 7"))
      (is (str/includes? msg "one tree cannot answer both")))))

(deftest claiming-runs-the-body-when-nothing-holds-it
  (let [ran (atom false)]
    (is (= :ok (review/claiming {:cwd "/nowhere/at/all" :kind :diff-review
                                 :run-id "r" :report-path "/p"}
                                (fn [] (reset! ran true) :ok))))
    (is (true? @ran))))

(deftest a-review-outside-a-session-takes-no-claim-and-still-runs
  (testing "there is no workstream to be the singleton of, so there is nothing to
            exclude and nothing for a second caller to join — the run proceeds
            exactly as it does today"
    (let [ran (atom 0)]
      (dotimes [_ 3]
        (review/claiming {:cwd "/definitely/not/a/session" :kind :diff-review
                          :run-id "r" :report-path "/p"}
                         (fn [] (swap! ran inc))))
      (is (= 3 @ran) "every invocation runs; none is refused"))))

(deftest the-claim-vocabulary-covers-every-record-loop
  (testing "a loop whose claim nobody enumerated must fail loudly rather than
            write a kind no surface can render"
    (is (every? activity/kinds (vals @#'review/record-loop-kinds)))
    (is (= #{"baseline" "design"} (set (keys @#'review/record-loop-kinds))))))

(deftest every-claim-kind-has-a-label
  (testing "a kind with no label would print as a raw keyword at the one moment
            a person is being told why their command did not run"
    (is (= activity/kinds (set (keys @#'review/kind-labels))))))

(deftest a-join-paints-the-frame-the-holder-is-painting
  (testing "a record round is painted by the record frame, and a join happens
            only when the two kinds agree — so the frame the caller would have
            drawn for itself is the holder's own. Through the diff review's
            frame a baseline round is headed `base nil` and says nothing about
            its verdict."
    (let [captured (atom nil)
          joined   (atom nil)
          mine     (fn [_report _now] "the record frame")]
      (with-redefs [frontend/follow! (fn [opts] (reset! captured opts) :detached)]
        (with-out-str
          (reset! joined (review/join-or-refuse! {:kind :baseline-round :render-fn mine}
                                                 (assoc their :kind :baseline-round)
                                                 :nido "ws-1")))
        (is (= :detached @joined))
        (is (= "the record frame" ((:render-fn @captured) {} nil))))
      (testing "and a caller with no frame of its own leaves follow! its default"
        (with-redefs [frontend/follow! (fn [opts] (reset! captured opts) :detached)]
          (with-out-str (review/join-or-refuse! {:kind :diff-review} their :nido "ws-1")))
        (is (not (contains? @captured :render-fn)))))))

(deftest following-ends-when-THAT-holder-ends-not-when-any-does
  (testing "the follower joined a named run, so the question is whether IT still
            holds the claim. Asking whether anybody does would keep painting a
            finished run's frozen report for as long as its replacement ran."
    (let [captured (atom nil)]
      (with-redefs [frontend/follow! (fn [opts] (reset! captured opts) :detached)]
        (with-out-str (review/join-or-refuse! {:kind :diff-review} their :nido "ws-1")))
      (let [stop? (:stop? @captured)]
        (with-redefs [activity/read-live (constantly their)]
          (is (false? (stop?)) "the run it attached to still holds the claim"))
        (with-redefs [activity/read-live (constantly (assoc their :run-id "review-8"))]
          (is (true? (stop?)) "a replacement holds it — what we attached to is over"))
        (with-redefs [activity/read-live (constantly nil)]
          (is (true? (stop?))))))))

(deftest a-refusal-whose-holder-vanished-is-retried-and-then-said-out-loud
  (testing "the lock attempt can lose and the read still come back empty, when
            the holder exits in between. Detecting the refusal from what it
            carries reads that as the body having run — a review that reviewed
            nothing, joined nobody and said nothing."
    (let [holding (promise)
          release (promise)
          holder  (future (activity/with-claim
                            :nido "ws-9"
                            {:kind :diff-review :run-id "held-here" :report-path nil}
                            (fn [] (deliver holding true) @release)))]
      (try
        @holding
        (let [ran (atom false)
              err (java.io.StringWriter.)
              res (binding [*err* err]
                    (with-redefs [stages/project+ws-from-cwd (constantly [:nido "ws-9"])
                                  activity/read-live (constantly nil)]
                      (review/claiming {:cwd "/in/a/session" :kind :diff-review
                                        :run-id "r" :report-path "/p"}
                                       (fn [] (reset! ran true) :ok))))]
          (is (false? @ran) "nothing ran — the claim was never taken")
          (is (= :refused res) "and the sentinel is never handed back as a result")
          (is (str/includes? (str err) "refused")
              "a reader is told, rather than left with a command that exited 0"))
        (finally (deliver release :done) @holder)))))

(deftest a-join-returns-the-holders-own-outcome
  (testing "the record loops have an in-process caller — lane.drive/run-stage! —
            that reads what they return as a work outcome, and classifies
            anything it does not recognise as :escalate. A follower reporting
            that it followed turns a holder's :sufficient into a parked blocker."
    (let [rp (str (fs/path (fs/create-temp-dir) "report.json"))]
      (spit rp "{\"run-id\":\"review-7\",\"status\":\"sufficient\",\"rounds\":[]}")
      (with-redefs [frontend/follow! (constantly :detached)]
        (let [holder (assoc their :report-path rp)
              joined (atom nil)]
          (with-out-str (reset! joined (review/join-or-refuse! {:kind :diff-review}
                                                               holder :nido "ws-1")))
          (is (= :sufficient @joined)))))
    (testing "and :detached only when the report cannot say — a fact about the
              machinery, which is how the driver reads it"
      (with-redefs [frontend/follow! (constantly :detached)]
        (let [joined (atom nil)]
          (with-out-str (reset! joined (review/join-or-refuse!
                                        {:kind :diff-review}
                                        (assoc their :report-path "/no/such/report.json")
                                        :nido "ws-1")))
          (is (= :detached @joined)))))))

(deftest a-round-of-a-different-record-is-refused-rather-than-followed
  (testing "kind alone is not the same work: a baseline round verifying entry 4
            is not the round somebody asked for on entry 7"
    (let [followed (atom false)
          err      (java.io.StringWriter.)
          res      (with-redefs [frontend/follow! (fn [_] (reset! followed true) :detached)]
                     (binding [*err* err]
                       (review/join-or-refuse!
                        {:kind :baseline-round :target {:seq 7 :code-cwd nil}}
                        (assoc their :kind :baseline-round :target {:seq 4 :code-cwd nil})
                        :nido "ws-1")))]
      (is (= :refused res))
      (is (false? @followed) "nothing was joined")
      (is (str/includes? (str err) "seq 4")))
    (testing "and the same target IS joined"
      (with-redefs [frontend/follow! (constantly :detached)]
        (let [joined (atom nil)]
          (with-out-str
            (reset! joined (review/join-or-refuse!
                            {:kind :baseline-round :target {:seq 7 :code-cwd nil}}
                            (assoc their :kind :baseline-round :target {:seq 7 :code-cwd nil})
                            :nido "ws-1")))
          (is (= :detached @joined) "followed; its report says nothing else"))))))

(deftest a-payload-is-confirmed-before-anything-is-decided-on-it
  (testing "read-live may hand back the PREVIOUS holder's payload while a
            replacement holds the lock, and every decision made from one is
            identity-sensitive: a join adopts that run's outcome, a refusal
            names its pid as the process to kill"
    (let [rp (str (fs/path (fs/create-temp-dir) "report.json"))]
      (testing "a replacement that has published its own claim: the runs differ"
        (with-redefs [activity/read-live (constantly (assoc their :run-id "review-8"))]
          (is (nil? (@#'review/confirmed-holder their :nido "ws-1")))))
      (testing "a FINISHED REPORT IS NOT A DEAD HOLDER. A run finalizes its
                report and then keeps the claim through its post-processing —
                the diff loop's design verdict launches an agent and can hold it
                for minutes — so rejecting it here told the second caller to
                kill a run doing exactly what it should. The window this used to
                catch, where a replacement holds the lock and has not published,
                is self-correcting: a follower stops on the same identity test
                the moment the replacement publishes."
        (spit rp "{\"status\":\"converged\",\"rounds\":[]}")
        (let [holder (assoc their :report-path rp)]
          (with-redefs [activity/read-live (constantly holder)]
            (is (= holder (@#'review/confirmed-holder holder :nido "ws-1"))))))
      (testing "and a live holder whose run is still going is confirmed"
        (spit rp "{\"status\":\"running\",\"rounds\":[]}")
        (let [holder (assoc their :report-path rp)]
          (with-redefs [activity/read-live (constantly holder)]
            (is (= holder (@#'review/confirmed-holder holder :nido "ws-1"))))))
      (testing "as is one that has not written a report at all yet"
        (let [holder (assoc their :report-path "/no/such/report.json")]
          (with-redefs [activity/read-live (constantly holder)]
            (is (= holder (@#'review/confirmed-holder holder :nido "ws-1")))))))))

(deftest a-finished-runs-status-never-becomes-this-invocations-answer
  (testing "a payload can be read while the claim changes hands, so the run we
            attached to may not be the one that held it. WHY following stopped is
            what says which: a replacement holding the claim means the payload
            was the previous holder's, and its verdict was reached before this
            request existed."
    (let [rp    (str (fs/path (fs/create-temp-dir) "report.json"))
          _     (spit rp "{\"status\":\"converged\",\"rounds\":[]}")
          stale {:kind :diff-review :target {:base "main"} :run-id "review-old"
                 :pid 4242 :started-at "2026-09-03T09:00:00Z" :report-path rp}
          out   (java.io.StringWriter.)
          res   (binding [*out* out]
                  (with-redefs [frontend/follow! (constantly :detached)
                                ;; a DIFFERENT run holds the claim once we stop
                                activity/read-live (constantly (assoc stale :run-id "review-new"))]
                    (review/join-or-refuse! {:kind :diff-review :target {:base "main"}}
                                            stale :nido "ws-8")))]
      (is (not= :converged res)
          "the previous holder's verdict is not this run's to report")
      (is (= :detached res))
      (is (str/includes? (str out) "was replaced while this watched it"))))

  (testing "and a holder that finished and LET GO is the one this asked about,
            so its verdict is the answer — the in-process driver reads this
            return as a work outcome, and :detached there parks a blocker"
    (let [rp   (str (fs/path (fs/create-temp-dir) "report.json"))
          _    (spit rp "{\"status\":\"converged\",\"rounds\":[]}")
          mine {:kind :diff-review :target {:base "main"} :run-id "review-old"
                :pid 4242 :started-at "t" :report-path rp}
          res  (atom nil)]
      (with-redefs [frontend/follow! (constantly :detached)
                    activity/read-live (constantly nil)]   ; claim free again
        (with-out-str
          (reset! res (review/join-or-refuse! {:kind :diff-review :target {:base "main"}}
                                              mine :nido "ws-8"))))
      (is (= :converged @res)))))

(deftest a-record-round-publishes-the-tree-it-actually-judges
  (testing "the pipeline judges with (or code-cwd cwd), so a round given no
            :code-cwd and one given its own worktree do identical work. Publishing
            the raw value made them look like different targets, and the second
            was refused as other work rather than joined."
    (let [captured (atom [])]
      (with-redefs [review/claiming (fn [opts _f] (swap! captured conj (:target opts)) :stopped)]
        (#'review/record-loop-cmd*
         {:kind "baseline" :pipeline [] :finding-key identity}
         {:cwd "/wt" :code-cwd nil})
        (#'review/record-loop-cmd*
         {:kind "baseline" :pipeline [] :finding-key identity}
         {:cwd "/wt" :code-cwd "/wt"}))
      (is (= 2 (count @captured)))
      (is (apply = (map :code-cwd @captured))
          "identical work must publish an identical target"))))
