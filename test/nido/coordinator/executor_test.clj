(ns nido.coordinator.executor-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [nido.coordinator.executor :as ex]))

(defn- reset-executor! [f]
  (ex/configure! {:global-cap 2})
  (ex/clear!)
  (f))

(use-fixtures :each reset-executor!)

(deftest submit-then-tick-spawns-future
  (let [spawned (atom [])]
    (ex/submit! "run-1" 0)
    (ex/tick! (fn [rid] (swap! spawned conj rid)))
    (Thread/sleep 50)
    (is (= ["run-1"] @spawned))))

(deftest higher-priority-pops-first
  (let [spawned (atom [])
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 100))]
    (ex/configure! {:global-cap 1})
    (ex/submit! "low"  1)
    (ex/submit! "high" 10)
    (ex/tick! slow)
    (Thread/sleep 30)
    (ex/tick! slow)                      ; slot full
    (Thread/sleep 200)                    ; high finishes
    (ex/tick! slow)                      ; reap, promote low
    (Thread/sleep 200)
    (is (= ["high" "low"] @spawned))))

(deftest fifo-tie-breaks-priority-ties
  (let [spawned (atom [])
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 100))]
    (ex/configure! {:global-cap 1})
    (ex/submit! "first"  5)
    (Thread/sleep 5)                      ; distinct received-at
    (ex/submit! "second" 5)
    (ex/tick! slow) (Thread/sleep 30)
    (ex/tick! slow) (Thread/sleep 200)
    (ex/tick! slow) (Thread/sleep 200)
    (is (= ["first" "second"] @spawned))))

(deftest cap-limits-concurrent-futures
  (let [running (atom 0)
        max-r   (atom 0)
        slow    (fn [_] (swap! running inc)
                         (swap! max-r max @running)
                         (Thread/sleep 100)
                         (swap! running dec))]
    (ex/configure! {:global-cap 2})
    (dotimes [i 5] (ex/submit! (str "r" i) 0))
    (dotimes [_ 10] (ex/tick! slow) (Thread/sleep 50))
    (is (<= @max-r 2))))

(deftest snapshot-reports-counts
  (ex/configure! {:global-cap 1})
  (ex/submit! "r1" 0)
  (ex/submit! "r2" 0)
  (let [s (ex/snapshot)]
    (is (= 1 (:cap s)))
    (is (= 0 (:in-flight s)))
    (is (= 2 (:queued s)))))

(deftest tick-reaps-completed-futures
  (let [spawned (atom #{})]
    (ex/configure! {:global-cap 1})
    (ex/submit! "r1" 0)
    (ex/tick! (fn [rid] (swap! spawned conj rid)))
    (Thread/sleep 100)
    (ex/tick! (fn [_] nil))
    (is (= 0 (:in-flight (ex/snapshot))))))

(deftest tick-swallows-future-exceptions-and-frees-slot
  (let [thrown (atom 0)]
    (ex/configure! {:global-cap 1})
    (ex/submit! "boom" 0)
    (ex/tick! (fn [_] (swap! thrown inc) (throw (ex-info "boom" {}))))
    (Thread/sleep 100)
    (ex/tick! (fn [_] nil))
    (is (= 1 @thrown))
    (is (= 0 (:in-flight (ex/snapshot))))
    (is (= 0 (:queued    (ex/snapshot))))))
