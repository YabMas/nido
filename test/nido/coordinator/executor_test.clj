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
    (ex/tick! (fn [rid] (swap! spawned conj rid)) {})
    (Thread/sleep 50)
    (is (= ["run-1"] @spawned))))

(deftest higher-priority-pops-first
  (let [spawned (atom [])
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 100))]
    (ex/configure! {:global-cap 1})
    (ex/submit! "low"  1)
    (ex/submit! "high" 10)
    (ex/tick! slow {})
    (Thread/sleep 30)
    (ex/tick! slow {})                     ; slot full
    (Thread/sleep 200)                    ; high finishes
    (ex/tick! slow {})                     ; reap, promote low
    (Thread/sleep 200)
    (is (= ["high" "low"] @spawned))))

(deftest fifo-tie-breaks-priority-ties
  (let [spawned (atom [])
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 100))]
    (ex/configure! {:global-cap 1})
    (ex/submit! "first"  5)
    (Thread/sleep 5)                      ; distinct received-at
    (ex/submit! "second" 5)
    (ex/tick! slow {}) (Thread/sleep 30)
    (ex/tick! slow {}) (Thread/sleep 200)
    (ex/tick! slow {}) (Thread/sleep 200)
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
    (dotimes [_ 10] (ex/tick! slow {}) (Thread/sleep 50))
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
    (ex/tick! (fn [rid] (swap! spawned conj rid)) {})
    (Thread/sleep 100)
    (ex/tick! (fn [_] nil) {})
    (is (= 0 (:in-flight (ex/snapshot))))))

(deftest tick-swallows-future-exceptions-and-frees-slot
  (let [thrown (atom 0)]
    (ex/configure! {:global-cap 1})
    (ex/submit! "boom" 0)
    (ex/tick! (fn [_] (swap! thrown inc) (throw (ex-info "boom" {}))) {})
    (Thread/sleep 100)
    (ex/tick! (fn [_] nil) {})
    (is (= 1 @thrown))
    (is (= 0 (:in-flight (ex/snapshot))))
    (is (= 0 (:queued    (ex/snapshot))))))

(deftest submit-defaults-uncapped-to-false-for-backcompat
  (let [snap (do (ex/configure! {:global-cap 5})
                 (ex/submit! "rid-1" 0)        ;; 2-arity (old signature)
                 (ex/snapshot))]
    (is (= 1 (:queued snap)))))

(deftest uncapped-runs-bypass-the-cap
  (let [spawned (atom [])
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 200))]
    (ex/configure! {:global-cap 1})
    ;; Fill the cap with a capped Run
    (ex/submit! "capped-1" 0 false)
    (ex/tick! slow {})
    (Thread/sleep 30)
    (is (= ["capped-1"] @spawned))
    ;; Now submit two more capped (should NOT promote — cap full)
    (ex/submit! "capped-2" 0 false)
    (ex/submit! "capped-3" 0 false)
    (ex/tick! slow {})
    (Thread/sleep 30)
    (is (= ["capped-1"] @spawned) "capped Runs still queued; cap is full")
    ;; Submit an uncapped Run; it should jump straight through
    (ex/submit! "uncapped-1" 0 true)
    (ex/tick! slow {})
    (Thread/sleep 30)
    (is (contains? (set @spawned) "uncapped-1")
        "uncapped Run promoted even though cap is full")
    ;; Wait for all spawns to drain
    (Thread/sleep 500)))

(deftest uncapped-runs-do-not-consume-cap-slots
  (let [spawned (atom [])
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 300))]
    (ex/configure! {:global-cap 1})
    ;; Submit an uncapped Run first — it spawns
    (ex/submit! "uncapped-1" 0 true)
    (ex/tick! slow {})
    (Thread/sleep 30)
    (is (= ["uncapped-1"] @spawned))
    ;; Now submit a capped Run — should ALSO spawn, since uncapped doesn't fill the cap
    (ex/submit! "capped-1" 0 false)
    (ex/tick! slow {})
    (Thread/sleep 30)
    (is (= ["uncapped-1" "capped-1"] @spawned)
        "capped Run spawns alongside uncapped — uncapped didn't fill the slot")
    (Thread/sleep 500)))

(deftest max-in-flight-gates-promotion
  (ex/clear!)
  (ex/configure! {:global-cap 10})           ; global cap not the limiter here
  (ex/submit! "r1" 0 false :tt 2)
  (ex/submit! "r2" 0 false :tt 2)
  (ex/submit! "r3" 0 false :tt 2)
  (let [spawned (atom [])]
    ;; trigger :tt already has 1 in-progress run on disk; cap 2 ⇒ only 1 more promotes
    (ex/tick! (fn [rid] (swap! spawned conj rid)) {:tt 1})
    (Thread/sleep 50)
    (is (= 1 (count @spawned)) "cap 2 minus 1 in-flight ⇒ promote exactly 1")))

(deftest no-max-in-flight-promotes-under-global-cap-only
  (ex/clear!)
  (ex/configure! {:global-cap 2})
  (ex/submit! "a" 0 false :tt nil)           ; nil cap = uncapped per-trigger
  (ex/submit! "b" 0 false :tt nil)
  (ex/submit! "c" 0 false :tt nil)
  (let [spawned (atom [])]
    (ex/tick! (fn [rid] (swap! spawned conj rid)) {})
    (Thread/sleep 50)
    (is (= 2 (count @spawned)) "global cap 2 limits; per-trigger uncapped")))
