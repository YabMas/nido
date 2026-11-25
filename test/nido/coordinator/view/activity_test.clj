(ns nido.coordinator.view.activity-test
  "Every facet of a workstream's activity is read off one observation, so no two can disagree;
   what runs is the core's to say."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.view.activity :as act]))

(def ^:private quiet {:closed? false :owed-by :agent})

(def ^:private claim {:kind :diff-review :run-id "r" :report-path "/r.json" :progress {:round 2}})

(deftest what-the-core-does-is-live-and-underway
  (doseq [k [:working :deciding :starting]]
    (let [a (act/of (assoc quiet :core {:kind k :unit "u"}))]
      (is (= {:source :core :activity k :unit "u"} (:doing a)) (str k))
      (is (:live a))
      (is (= :active (:engagement a)))
      (is (not (:stalled a))))))

(deftest a-claim-says-what-a-working-turn-is-doing-and-nothing-more
  (testing "beside a :working turn it describes the turn, with its round's progress"
    (let [a (act/of (assoc quiet :core {:kind :working :turn "t"} :claim claim))]
      (is (= {:source :claim :kind :diff-review :run-id "r" :report-path "/r.json" :progress {:round 2}}
             (:doing a)))
      (is (:live a))))
  (testing "with nothing the core does, a claim is a process holding a lock: nothing runs"
    (let [a (act/of (assoc quiet :claim claim))]
      (is (nil? (:doing a)))
      (is (not (:live a)))))
  (testing "an ask waits on the person whatever a claim says"
    (is (nil? (:doing (act/of (assoc quiet :core {:kind :asking} :claim claim)))))))

(deftest a-stall-is-the-cores-open-unit-left-unmoved
  (let [a (act/of (assoc quiet :movable? true))]
    (is (:stalled a))
    (is (= :stalled (:engagement a))))
  (testing "nothing stalls"
    (is (not (:stalled (act/of quiet))) "with no open unit nobody holds: nothing is the core's to move")
    (is (not (:stalled (act/of (assoc quiet :movable? true :paused? true)))) "while a pause stands")
    (is (not (:stalled (act/of (assoc quiet :movable? true :core {:kind :deciding})))) "while the core is owed a move")
    (is (not (:stalled (act/of (assoc quiet :movable? true :core {:kind :asking})))) "while it asks the person")
    (is (not (:stalled (act/of (assoc quiet :movable? true :closed? true)))) "once settled")
    (is (not (:stalled (act/of (assoc quiet :movable? true :owed-by :person))))
        "while the next move is the person's: it waits on them")))

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

(deftest the-machine-owing-a-move-is-queued
  (is (= :queued (:engagement (act/of (assoc quiet :owed-by :machine))))))

(deftest settled-is-settled
  (let [a (act/of (assoc quiet :closed? true :core {:kind :asking} :claim claim))]
    (is (= :settled (:engagement a)))
    (is (nil? (:doing a)))
    (is (not (:live a)))
    (is (not (:needs-you a)))
    (is (not (:stalled a)))))
