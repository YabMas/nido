(ns nido.coordinator.record.phase-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.phase :as phase]))

(def ^:private plan
  {:format :design
   :phases [{:claim "both writers maintain the new column" :exit {:kind :soak :criterion "a week"}}
            {:claim "reads move to the new column"          :exit {:kind :soak :criterion "a cycle"}}
            {:claim "the old column is dropped"             :exit {:kind :completion :criterion "done"}}]})

(defn- rows [& kinds]
  (vec (map-indexed (fn [i k] (if (map? k) (assoc k :seq (inc i)) {:kind k :seq (inc i)})) kinds)))

(defn- gate [claim] {:kind :phase-gate :opens claim})

(deftest an-unphased-design-has-no-progress
  (is (nil? (phase/progress {:format :design} (rows :design :merged))))
  (is (nil? (phase/progress nil [])))
  (is (= :done (phase/landing-outcome {:format :design} (rows :design))))
  (is (= :done (phase/landing-outcome nil []))))

(deftest a-plan-starts-on-its-first-phase-unlanded
  (is (= {:current 1 :of 3 :landed? false :next (get-in plan [:phases 1])}
         (phase/progress plan (rows :intent :baseline :design)))))

(deftest a-merge-lands-the-current-phase-without-moving-it
  (let [p (phase/progress plan (rows :design :pr-opened :merged))]
    (is (= 1 (:current p)))
    (is (:landed? p))))

(deftest merges-never-decide-which-phase-is-current
  (testing "a findings round merging again on the same workstream"
    (is (= 1 (:current (phase/progress plan (rows :design :merged :findings :pr-opened :merged)))))))

(deftest a-gate-opens-the-phase-it-names-and-resets-the-landing
  (let [p (phase/progress plan (rows :design :merged (gate "reads move to the new column")))]
    (is (= 2 (:current p)))
    (is (not (:landed? p)) "the :merged before the gate was the previous phase's landing")
    (is (= "the old column is dropped" (:claim (:next p)))))
  (is (:landed? (phase/progress plan (rows :design :merged (gate "reads move to the new column") :merged)))))

(deftest a-gate-for-a-phase-this-plan-does-not-have-is-not-counted
  (is (= 1 (:current (phase/progress plan (rows :design :merged (gate "a claim a superseded design made")))))))

(deftest a-repeated-claim-is-opened-one-phase-per-gate
  (let [twice {:format :design
               :phases [{:claim "A" :exit {:kind :soak :criterion "a week"}}
                        {:claim "B" :exit {:kind :soak :criterion "a week"}}
                        {:claim "B" :exit {:kind :completion :criterion "done"}}]}
        p     (phase/progress twice (rows :design :merged (gate "B")))]
    (is (= 2 (:current p)) "the gate opened phase 2, not the claim's last position")
    (is (= (get-in twice [:phases 2]) (:next p)))
    (is (= :between-phases (phase/landing-outcome twice (rows :design :merged (gate "B")))))
    (is (= 3 (:current (phase/progress twice (rows :design :merged (gate "B") :merged (gate "B"))))))))

(deftest the-last-phase-has-no-next
  (let [p (phase/progress plan (rows :design :merged (gate "reads move to the new column")
                                     :merged (gate "the old column is dropped")))]
    (is (= 3 (:current p)))
    (is (nil? (:next p)))))

(deftest a-landing-closes-between-phases-until-the-last
  (is (= :between-phases (phase/landing-outcome plan (rows :design))))
  (is (= :between-phases (phase/landing-outcome plan (rows :design :merged (gate "reads move to the new column")))))
  (is (= :done (phase/landing-outcome plan (rows :design :merged (gate "reads move to the new column")
                                                  :merged (gate "the old column is dropped"))))))
