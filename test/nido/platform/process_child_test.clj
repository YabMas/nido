;; test/nido/platform/process_child_test.clj
(ns nido.platform.process-child-test
  "Children this process started, and stopping them when it stops.

   nido disables babashka.process's destroy-tree hook globally to cure a macOS
   shutdown hang, and the cost is orphans: a killed review loop left its judge
   running, billing, and writing an answer nothing would read."
  (:require
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.process :as nprocess]))

(defn- sleeper []
  (:proc (p/process {:out :inherit} "sleep" "120")))

(deftest a-registered-child-is-stopped
  (let [proc (sleeper)]
    (future (nprocess/with-child-registered proc #(Thread/sleep 10000)))
    (Thread/sleep 300)
    (is (.isAlive proc) "still running while the work is in flight")
    (is (pos? (nprocess/stop-live-children!)))
    (Thread/sleep 300)
    (is (not (.isAlive proc)) "and stopped when this process would stop")))

(deftest a-finished-child-is-deregistered
  ;; The registry must not grow across a long run, and a reaper must not sit
  ;; waiting on processes that ended rounds ago.
  (let [proc (:proc (p/process {:out :inherit} "true"))]
    (nprocess/with-child-registered proc #(Thread/sleep 200))
    (is (zero? (nprocess/stop-live-children!)))))

(deftest a-child-is-deregistered-even-when-the-work-throws
  (let [proc (sleeper)]
    (is (thrown? Exception
                 (nprocess/with-child-registered proc #(throw (ex-info "boom" {})))))
    (is (zero? (nprocess/stop-live-children!))
        "a round that blew up must not leave its agent in the registry")
    (.destroy proc)))

(deftest the-helper-returns-what-the-work-returned
  (let [proc (:proc (p/process {:out :inherit} "true"))]
    (is (= :answer (nprocess/with-child-registered proc (constantly :answer))))))

;; ── What this process puts on the record before it stops them ───────────────

(deftest a-finished-note-is-deregistered
  (let [ran (atom 0)]
    (is (= :answer (nprocess/with-exit-note #(swap! ran inc) (constantly :answer))))
    (is (zero? (nprocess/run-exit-notes!)))
    (is (zero? @ran)
        "a note left registered past its own work would fire on behalf of a run
         that has already said how it ended")))

(deftest an-exit-note-runs-while-its-work-is-in-flight
  (let [ran   (atom 0)
        going (promise)
        done  (promise)]
    (future (nprocess/with-exit-note #(swap! ran inc)
              (fn [] (deliver going true) @done)))
    @going
    (is (= 1 (nprocess/run-exit-notes!)))
    (is (= 1 @ran))
    (deliver done true)))

(deftest a-note-that-throws-does-not-take-the-hook-down-with-it
  ;; Shutdown is the one moment where an exception costs more than what it was
  ;; reporting: the hook thread dies with the children still running.
  (let [ran (atom 0)
        ok? (promise)]
    (future (nprocess/with-exit-note #(throw (ex-info "boom" {}))
              (fn [] @ok?)))
    (Thread/sleep 100)
    (future (nprocess/with-exit-note #(swap! ran inc) (fn [] @ok?)))
    (Thread/sleep 100)
    (is (= 1 (nprocess/run-exit-notes!)) "only the note that returned is counted")
    (is (= 1 @ran) "and the one after it still ran")
    (deliver ok? true)))

(deftest the-notes-are-written-before-the-children-are-reaped
  ;; The ordering is the claim. Destroying a child unblocks the thread reading
  ;; it, which then spends the reap's five-second grace unwinding — everything
  ;; that thread does would land on top of a note written afterwards.
  (let [proc  (sleeper)
        alive (atom nil)
        go    (promise)]
    (future (nprocess/with-child-registered proc #(deref go)))
    (future (nprocess/with-exit-note #(reset! alive (.isAlive proc)) #(deref go)))
    (Thread/sleep 300)
    (nprocess/at-exit!)
    (is (true? @alive)
        "the note ran with the child still alive — i.e. before the reap")
    (deliver go true)
    (.destroy proc)))
