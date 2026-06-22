;; test/nido/ui/health_test.clj
(ns nido.ui.health-test
  (:require [clojure.test :refer [deftest is]]
            [nido.ui.health :as health]))

(deftest daemon-health-state-priority
  ;; halted beats everything; breaker beats running; running+alive = up; else down.
  (is (= :halted  (:state (health/daemon-health {:halted? true :alive? true
                                                 :breaker-count 1 :status {:status :running}}))))
  (is (= :breaker (:state (health/daemon-health {:halted? false :alive? true
                                                 :breaker-count 2 :status {:status :running}}))))
  (is (= :up      (:state (health/daemon-health {:halted? false :alive? true
                                                 :breaker-count 0 :status {:status :running}}))))
  ;; nil breaker-count is treated as zero (the `(or breaker-count 0)` guard), not an error.
  (is (= :up      (:state (health/daemon-health {:halted? false :alive? true
                                                 :breaker-count nil :status {:status :running}}))))
  (is (= :down    (:state (health/daemon-health {:halted? false :alive? false
                                                 :breaker-count 0 :status nil}))))
  (is (= :down    (:state (health/daemon-health {:halted? false :alive? true
                                                 :breaker-count 0 :status {:status :stopped}})))))

(deftest daemon-health-passes-through-heartbeat
  (is (= "2026-06-22T00:00:00Z"
         (:heartbeat-at (health/daemon-health {:halted? false :alive? true :breaker-count 0
                                               :status {:status :running
                                                        :heartbeat-at "2026-06-22T00:00:00Z"}})))))
