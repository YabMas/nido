(ns nido.coordinator.record.session-doing-test
  "The activity projection. Its whole job is that exactly ONE source ever
   answers, so most of these are precedence tests: a workstream that could be
   described two ways must be described one way."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.session :as session]))

(def claim {:kind :diff-review :run-id "review-1" :report-path "/runs/review-1/report.json"
            :started-at "2026-09-03T10:00:00Z" :pid 4242})

(defn- auto [phase] {:name "impl-1" :substrate :live :autonomy {:phase phase}})
(defn- human []     {:name "human-1" :substrate :live :autonomy nil})

(deftest nothing-underway-reads-as-nil
  (is (nil? (session/doing {:sessions []})))
  (testing "a live HUMAN session is engagement, not activity — somebody is in the
            session and has started nothing nido can name"
    (is (nil? (session/doing {:sessions [(human)]})))))

(deftest a-closed-workstream-is-doing-nothing
  (testing "closure outranks every source, including a claim some process still
            holds — it is over, whatever any record still says"
    (is (nil? (session/doing {:closed {:at "t" :outcome :done}
                              :sessions [(auto :running)]
                              :stage :shipping
                              :claim claim})))))

(deftest a-held-claim-answers-and-carries-its-payload
  (let [d (session/doing {:sessions [] :claim claim})]
    (is (= :claim (:source d)))
    (is (= :diff-review (:kind d)))
    (is (= "review-1" (:run-id d)))
    (is (= "/runs/review-1/report.json" (:report-path d))
        "the report path is what makes a live run joinable, so it must survive")))

(deftest the-claim-outranks-merge-and-session
  (testing "a claim is evidence of a process alive at the instant of the read;
            a stage and a phase are flags left behind by a crash"
    (let [d (session/doing {:sessions [(auto :running)] :stage :shipping :claim claim})]
      (is (= :claim (:source d))))))

(deftest merge-outranks-a-bare-session
  (let [d (session/doing {:sessions [(auto :running)] :stage :shipping})]
    (is (= :merge (:source d)))
    (is (= :driving (:phase d))))
  (testing "and a :shipping workstream with nothing of the merge running reads
            :queued rather than nil — the lane takes one branch at a time, so
            waiting for it is a state the board must keep saying, and a row that
            went blank when its session ended would lose the only thing it had
            left to report"
    (is (= {:source :merge :phase :queued}
           (session/doing {:sessions [] :stage :shipping})))
    (testing "and :queued is the RESTING state, not a default that swallows what
              the lane does report — a session it can read still answers"
      (is (= {:source :merge :phase :awaiting-merge}
             (session/doing {:sessions [(auto :done)] :stage :shipping}))))))

(deftest a-live-autonomous-session-answers-last
  (doseq [phase [:running :preprocessing]]
    (let [d (session/doing {:sessions [(auto phase)]})]
      (is (= :session (:source d)))
      (is (= phase (:phase d)))
      (is (= "impl-1" (:session d))))))

(deftest a-parked-session-is-a-gate-not-an-activity
  (testing "engagement already reports :parked-at-gate; calling it activity would
            say a workstream waiting on a human is busy"
    (is (nil? (session/doing {:sessions [(auto :parked)]})))
    (is (= :parked-at-gate (session/engagement-state nil [(auto :parked)]))
        "and engagement is where that fact does belong")))

(deftest queued-and-terminal-sessions-are-not-activity
  (doseq [phase [:queued :done :failed :halted]]
    (is (nil? (session/doing {:sessions [(auto phase)]}))
        (str phase " is not work underway"))))

(deftest exactly-one-source-ever-answers
  (testing "every combination yields at most one :source — the totality the
            projection exists to provide"
    (doseq [closed   [nil {:at "t" :outcome :done}]
            stage    [nil :shipping :in-progress]
            sessions [[] [(human)] [(auto :running)] [(auto :parked)]]
            c        [nil claim]]
      (let [d (session/doing {:closed closed :stage stage :sessions sessions :claim c})]
        (is (or (nil? d) (contains? #{:claim :merge :session} (:source d)))
            (pr-str [closed stage sessions (boolean c)]))))))

(deftest engagement-is-unchanged-by-activity
  (testing "the two facets are independent: adding a claim must not move
            engagement, or every existing reader's meaning shifts underneath it"
    (is (= :idle (session/engagement-state nil [])))
    (is (= :active (session/engagement-state nil [(auto :running)])))
    (is (= :settled (session/engagement-state {:at "t" :outcome :done} [(auto :running)])))))

(deftest an-effectively-done-workstream-is-doing-nothing
  (testing "a Notion-driven workstream ends by its ticket going terminal, and
            nido never writes :closed for it — so a row carrying an effective
            stage of :done and a live session must not report both at once"
    (is (nil? (session/doing {:closed nil :stage :done :sessions [(auto :running)]})))
    (is (nil? (session/doing {:closed nil :stage :done :sessions [] :claim claim}))
        "not even a held claim outranks the work being over"))
  (testing "and every other stage still answers"
    (is (some? (session/doing {:closed nil :stage :in-progress :sessions [(auto :running)]})))))
