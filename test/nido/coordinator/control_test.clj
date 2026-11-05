;; test/nido/coordinator/control_test.clj
(ns nido.coordinator.control-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [nido.coordinator.control :as control]
            [nido.coordinator.daemon.breakers :as breakers]
            [nido.platform.core :as core]))

(deftest daemon-health-state-priority
  ;; halted beats everything; breaker beats running; running+alive = up; else down.
  (is (= :halted  (:state (control/daemon-health {:halted? true :alive? true
                                                 :breaker-count 1 :status {:status :running}}))))
  (is (= :breaker (:state (control/daemon-health {:halted? false :alive? true
                                                 :breaker-count 2 :status {:status :running}}))))
  (is (= :up      (:state (control/daemon-health {:halted? false :alive? true
                                                 :breaker-count 0 :status {:status :running}}))))
  ;; nil breaker-count is treated as zero (the `(or breaker-count 0)` guard), not an error.
  (is (= :up      (:state (control/daemon-health {:halted? false :alive? true
                                                 :breaker-count nil :status {:status :running}}))))
  (is (= :down    (:state (control/daemon-health {:halted? false :alive? false
                                                 :breaker-count 0 :status nil}))))
  (is (= :down    (:state (control/daemon-health {:halted? false :alive? true
                                                 :breaker-count 0 :status {:status :stopped}})))))

(deftest daemon-health-passes-through-heartbeat
  (is (= "2026-06-22T00:00:00Z"
         (:heartbeat-at (control/daemon-health {:halted? false :alive? true :breaker-count 0
                                               :status {:status :running
                                                        :heartbeat-at "2026-06-22T00:00:00Z"}})))))

(deftest queue-blocker-answers-will-this-envelope-run
  ;; nil = it runs. A breaker on ANOTHER trigger is invisible here by design:
  ;; the caller passes only its own trigger's state, so the dot's global
  ;; breaker-count can never leak in and read as "down".
  (is (nil? (control/queue-blocker {:alive? true :halted? false
                                   :status {:status :running} :trigger-tripped? false})))
  ;; This is the regression the rail dot caused: healthy daemon, unrelated
  ;; trigger tripped -> pickup must still promise the work runs.
  (is (nil? (control/queue-blocker {:alive? true :halted? false
                                   :status {:status :running} :trigger-tripped? false
                                   :breaker-count 3})))
  (is (= :breaker (control/queue-blocker {:alive? true :halted? false
                                         :status {:status :running} :trigger-tripped? true})))
  (is (= :halted (control/queue-blocker {:alive? true :halted? true
                                        :status {:status :halted} :trigger-tripped? false})))
  (is (= :daemon-down (control/queue-blocker {:alive? false :halted? false
                                             :status nil :trigger-tripped? false})))
  ;; Alive but not draining (booting/wedged) is down, not "running".
  (is (= :daemon-down (control/queue-blocker {:alive? true :halted? false
                                             :status {:status :stopped} :trigger-tripped? false}))))

(deftest queue-blocker-reports-the-actionable-reason-first
  ;; A dead process with a stale halted.edn is :daemon-down — resuming a daemon
  ;; that isn't running would not help, so aliveness outranks the halt file.
  (is (= :daemon-down (control/queue-blocker {:alive? false :halted? true
                                             :status {:status :halted} :trigger-tripped? true})))
  ;; A LIVE halted daemon writes :status :halted, which also fails the :running
  ;; check — halt must be reported before that, or the copy says "down" for a
  ;; daemon that is up and merely paused.
  (is (= :halted (control/queue-blocker {:alive? true :halted? true
                                        :status {:status :halted} :trigger-tripped? true}))))

(deftest clearing-a-breaker-leaves-a-persons-pause
  ;; 2026-10-27: clearing from the ops panel lifted four pauses and left their notes behind, so
  ;; breakers.edn read as paused while the triggers fired.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (breakers/disable-by-user! :brian :triage-new "stopped for now")
        (dotimes [_ 3] (breakers/record-failure! :brian :triage-new 3))
        (control/clear-breaker! :brian :triage-new)
        (let [e (get-in (breakers/read-all) [:brian :triage-new])]
          (is (false? (:tripped? e)) "the trip is cleared")
          (is (zero? (:consecutive-failures e)))
          (is (true? (:disabled-by-user? e)) "the pause stays")
          (is (= "stopped for now" (:note e)) "and so does its note")
          (is (breakers/tripped? :brian :triage-new) "so the daemon still skips it"))
        (control/resume-trigger! :brian :triage-new)
        (is (not (breakers/tripped? :brian :triage-new)) "only resuming lifts it"))
      (finally (fs/delete-tree tmp)))))
