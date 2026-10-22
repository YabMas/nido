(ns nido.coordinator.record.trend-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.trend :as trend]))

(defn- run [kind day status rounds minutes]
  {:kind kind :day day :status status :outcome (trend/outcome status)
   :rounds rounds :minutes minutes})

(deftest a-run-is-converged-handed-or-failed
  (is (= :converged (trend/outcome "proceed")))
  (is (= :handed (trend/outcome "asked")))
  (is (= :handed (trend/outcome "stalled")))
  (is (= :failed (trend/outcome "interrupted"))))

(deftest a-report-names-its-loop-by-its-run-id
  (is (= {:run-id "design-loop-1" :kind :design :day "2026-10-21" :status "asked"
          :outcome :handed :rounds 2 :minutes 30.0}
         (trend/figures {"run-id" "design-loop-1" "status" "asked"
                         "started-at" "2026-10-21T20:00:00Z" "ended-at" "2026-10-21T20:30:00Z"
                         "rounds" [{} {}]})))
  (is (nil? (trend/figures {"run-id" "design-loop-1" "status" "running" "started-at" "2026-10-21T20:00:00Z"}))
      "a run still going has no outcome to count")
  (is (nil? (trend/figures {"run-id" "nido-session-recovery-1" "status" "done" "started-at" "2026-10-21T20:00:00Z"}))))

(deftest failed-runs-are-left-out-of-the-rates
  (let [s (trend/summary [(run :diff "2026-10-01" "clean" 2 10)
                          (run :diff "2026-10-01" "asked" 4 20)
                          (run :diff "2026-10-01" "interrupted" 1 nil)])]
    (is (= 3 (:runs s)))
    (is (= 2 (:decided s)))
    (is (= 0.5 (:handed s)))
    (is (= 3.0 (:median-rounds s)))))

(defn- fortnight [kind start-day status rounds minutes]
  (let [d (java.time.LocalDate/parse start-day)]
    (for [i (range 12)] (run kind (str (.plusDays d i)) status rounds minutes))))

(deftest a-handed-share-up-ten-points-is-a-regression
  (let [runs (concat (fortnight :design "2026-09-25" "proceed" 2 6)
                     (fortnight :design "2026-10-09" "asked" 2 6))]
    (is (= [{:kind :design :measure :handed :before 0.0 :now 1.0}]
           (trend/regression runs "2026-10-22")))))

(deftest rounds-and-minutes-up-by-half-are-regressions
  (let [runs (concat (fortnight :diff "2026-09-25" "clean" 2 10)
                     (fortnight :diff "2026-10-09" "clean" 3 30))]
    (is (= #{:median-rounds :median-minutes}
           (set (map :measure (trend/regression runs "2026-10-22")))))))

(deftest too-few-runs-are-not-judged
  (let [runs (concat (take 5 (fortnight :diff "2026-09-25" "clean" 2 10))
                     (fortnight :diff "2026-10-09" "asked" 9 90))]
    (is (empty? (trend/regression runs "2026-10-22")))))

(deftest a-release-is-dated-and-lapses
  (let [root (str (fs/create-temp-dir))]
    (with-redefs [cstate/nido-root (constantly root)]
      (is (false? (trend/released? :nido "2026-10-22")))
      (trend/release! :nido "2026-10-25" "measuring the stall fix")
      (is (true? (trend/released? :nido "2026-10-25")))
      (is (false? (trend/released? :nido "2026-10-26"))))))
