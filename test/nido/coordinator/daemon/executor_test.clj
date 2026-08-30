(ns nido.coordinator.daemon.executor-test
  (:require
   [clojure.test :refer [deftest is use-fixtures]]
   [nido.coordinator.daemon.executor :as ex]))

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

(deftest uncapped-merge-lane-serializes-by-trigger
  (let [spawned (atom [])
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 100))]
    (ex/configure! {:global-cap 1})
    ;; two uncapped :merge runs, lane max-in-flight 1
    (ex/submit! "merge-a" 0 true :merge 1)
    (ex/submit! "merge-b" 0 true :merge 1)
    (ex/tick! slow {})                 ; only merge-a promotes (lane full at 1)
    (Thread/sleep 30)
    (is (= ["merge-a"] @spawned))
    (Thread/sleep 200)                 ; merge-a finishes, future reaped
    (ex/tick! slow {})                 ; now merge-b promotes
    (Thread/sleep 50)
    (is (= ["merge-a" "merge-b"] @spawned))))

(deftest uncapped-merge-does-not-consume-a-global-slot
  (let [spawned (atom #{})
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 150))]
    (ex/configure! {:global-cap 1})
    (ex/submit! "capped-1" 0)          ; consumes the one global slot
    (ex/submit! "merge-a"  0 true :merge 1)
    (ex/tick! slow {})                 ; capped-1 (global) AND merge-a (uncapped) both run
    (Thread/sleep 50)
    (is (= #{"capped-1" "merge-a"} @spawned))))

(deftest uncapped-with-nil-max-in-flight-still-promotes
  (let [spawned (atom [])]
    (ex/configure! {:global-cap 1})
    (ex/submit! "u1" 0 true nil nil)
    (ex/submit! "u2" 0 true nil nil)
    (ex/tick! (fn [rid] (swap! spawned conj rid)) {})
    (Thread/sleep 50)
    (is (= #{"u1" "u2"} (set @spawned)))))

(deftest merge-lane-respects-in-flight-by-trigger-arg
  (let [spawned (atom [])]
    (ex/configure! {:global-cap 5})
    (ex/submit! "merge-a" 0 true :merge 1)
    ;; one :merge already in flight (reported via the arg) → don't promote
    (ex/tick! (fn [rid] (swap! spawned conj rid)) {:merge 1})
    (Thread/sleep 50)
    (is (= [] @spawned))))

;; ── Turns against an already-spawned Run ────────────────────────────────────

(deftest a-turn-runs-its-own-body-instead-of-on-spawn
  (ex/clear!)
  (ex/configure! {:global-cap 4})
  (let [spawned (atom []) ran (atom [])]
    (ex/submit-turn! {:run-id "r1" :turn "a"
                            :body #(swap! ran conj :a)})
    (ex/tick! (fn [rid] (swap! spawned conj rid)) {})
    (Thread/sleep 200)
    (is (= [:a] @ran) "the turn's own body ran")
    (is (= [] @spawned) "and on-spawn was not called for it")))

(deftest two-turns-against-one-run-are-two-units-of-work
  ;; submit!'s idempotence exists to stop one Run being SPAWNED twice. Keyed on
  ;; the run-id alone it would also swallow a human's second reply, which is the
  ;; opposite of what anybody wants.
  (ex/clear!)
  (ex/configure! {:global-cap 4})
  (let [ran (atom [])]
    (ex/submit-turn! {:run-id "r1" :turn "a" :body #(swap! ran conj :a)})
    (ex/submit-turn! {:run-id "r1" :turn "b" :body #(swap! ran conj :b)})
    (ex/tick! (fn [_]) {})
    (Thread/sleep 200)
    (is (= #{:a :b} (set @ran)))))

(deftest a-turn-waits-for-a-slot-like-everything-else
  ;; The point of the layer: a resumed turn used to launch on a bare future
  ;; beside the executor, so the cap counted sessions started rather than agents
  ;; running.
  (ex/clear!)
  (ex/configure! {:global-cap 1})
  (let [ran   (atom [])
        gate  (promise)]
    (ex/submit-turn! {:run-id "r1" :turn "a"
                            :body (fn [] @gate (swap! ran conj :a))})
    (ex/submit-turn! {:run-id "r2" :turn "b" :body #(swap! ran conj :b)})
    (ex/tick! (fn [_]) {})
    (Thread/sleep 150)
    (is (= 1 (:in-flight (ex/snapshot))) "one slot, one turn running")
    (is (= 1 (:queued (ex/snapshot))) "the other is waiting, not launched")
    (deliver gate true)
    (Thread/sleep 150)
    (ex/tick! (fn [_]) {})
    (Thread/sleep 200)
    (is (= #{:a :b} (set @ran)) "and it runs once the slot frees")))
