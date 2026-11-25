(ns nido.coordinator.view.activity-test
  "Every facet of a workstream's activity is read off one observation, so no two can disagree."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.view.activity :as act]))

(def ^:private quiet {:closed? false :owed-by :agent :runs [] :sessions []})

(deftest what-runs-is-live-and-active-whichever-source-says-so
  (doseq [[why obs] [["a claim"           {:claim {:kind :diff-review :run-id "r"}}]
                     ["a core turn"       {:core {:kind :working :turn "t" :unit "u"}}]
                     ["a run's lock"      {:runs [{:id "r" :state :running :live? true :session-name "s"}]}]
                     ["a person's session" {:sessions [{:name "s" :up? true}]}]]]
    (testing why
      (let [a (act/of (merge quiet obs))]
        (is (:live a))
        (is (= :active (:engagement a)))
        (is (not (:stalled a)))))))

(deftest doing-is-always-live
  (doseq [obs [{:claim {:kind :design-round :run-id "r"}}
               {:core {:kind :deciding}}
               {:runs [{:id "r" :state :running :live? true}]}
               {:sessions [{:name "s" :up? true :claude {:queued "go"}}]}
               {:sessions [{:name "s" :mid-turn? true :claude {}}]}]]
    (let [a (act/of (merge quiet obs))]
      (is (some? (:doing a)) (pr-str obs))
      (is (:live a) (pr-str obs)))))

(deftest a-person-session-up-is-live-without-being-underway
  (let [a (act/of (assoc quiet :sessions [{:name "s" :up? true}]))]
    (is (nil? (:doing a)))
    (is (:live a))
    (is (= :active (:engagement a))))
  (testing "a session nido drives being up is not evidence: its services stay up between turns"
    (is (not (:live (act/of (assoc quiet :sessions [{:name "s" :up? true :claude {:session-id "x"}}])))))))

(deftest nothing-running-with-an-agent-owing-is-a-stall
  (let [a (act/of (assoc quiet :stall-why {:turn-end :none}))]
    (is (:stalled a))
    (is (= :stalled (:engagement a)))
    (is (= {:turn-end :none} (:why a))))
  (testing "unless something excuses it"
    (is (not (:stalled (act/of (assoc quiet :paused? true)))) "a person paused it")
    (is (not (:stalled (act/of (assoc quiet :resting? true)))) "the conversation is the person's own")
    (is (not (:stalled (act/of (assoc quiet :owed-by :machine)))) "the machine owes it")))

(deftest only-a-core-ask-needs-the-person
  (let [a (act/of (assoc quiet :core {:kind :asking}))]
    (is (:needs-you a))
    (is (= :person (:owed-by a)))
    (is (= :parked-at-gate (:engagement a)))
    (is (nil? (:doing a)) "an ask waits on the person: nothing is underway")
    (is (not (:stalled a))))
  (let [a (act/of (assoc quiet :owed-by :person))]
    (is (not (:needs-you a)) "a ledger that says the person owes a move asks nothing")
    (is (not= :parked-at-gate (:engagement a)))))

(deftest a-merge-waiting-its-turn-is-underway-but-nothing-runs
  (let [a (act/of (assoc quiet :band :shipping :owed-by :machine))]
    (is (= {:source :merge :phase :queued} (:doing a)))
    (is (not (:live a)))
    (is (= :queued (:engagement a)))))

(deftest settled-is-settled-but-what-still-runs-is-seen
  (let [a (act/of (assoc quiet :closed? true :runs [{:id "r" :live? true}] :core {:kind :asking}))]
    (is (= :settled (:engagement a)))
    (is (nil? (:doing a)))
    (is (not (:needs-you a)))
    (is (not (:stalled a)))
    (is (:live a) "a process still running under it is not hidden")))
