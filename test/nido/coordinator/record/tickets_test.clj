(ns nido.coordinator.record.tickets-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.tickets :as tickets]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest read-meta-is-nil-safe-for-nil-or-blank-br-id
  ;; A nil/blank br-id (e.g. a triage run whose event-payload predates :id)
  ;; must not NPE in fs/path — it has no ticket record, so reads return nil.
  (with-tmp
    (fn [_]
      (is (nil? (tickets/read-meta :brian nil)))
      (is (nil? (tickets/read-meta :brian "")))
      (is (nil? (tickets/status :brian nil)))
      (is (= :spawn (tickets/gate-decision :brian nil))))))

(deftest write-paths-are-nil-safe-for-nil-or-blank-br-id
  ;; A run with no resolvable br-id has no ticket — every write path must no-op
  ;; rather than NPE in fs/path (the daemon crash-loop behind the '36 sessions'
  ;; incident traced to a ticket op on a nil br-id).
  (with-tmp
    (fn [_]
      (doseq [br [nil ""]]
        (is (nil? (tickets/write-meta! :brian br {:status :investigating})))
        (is (nil? (tickets/open! :brian br {:notion-page-id "p" :url "u" :title "T"
                                            :opened-by :triage-new :notion-last-edited-at "t"})))
        (is (nil? (tickets/set-status! :brian br :awaiting-input)))
        (is (nil? (tickets/complete! :brian br :triaged :applied)))
        (is (nil? (tickets/clear-status! :brian br))))
      ;; on-run-terminal! over a record-less run is also a no-op
      (is (nil? (tickets/on-run-terminal!
                  {:skill :triage-bug :project :brian :event-payload {}} :failed))))))

(deftest open-creates-record-with-investigating-status
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-5236"
                     {:notion-page-id "pg1" :url "u" :title "T" :opened-by :triage-new
                      :notion-last-edited-at "t0"})
      (let [m (tickets/read-meta :brian "BR-5236")]
        (is (= :investigating (:status m)))
        (is (= "BR-5236" (:br-id m)))
        (is (= "pg1" (:notion-page-id m)))
        (is (= :triage-new (:opened-by m)))
        (is (nil? (:triaged-at m)))))))

(deftest set-status-updates-without-clobbering
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-1" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/set-status! :brian "BR-1" :awaiting-input)
      (is (= :awaiting-input (tickets/status :brian "BR-1")))
      (is (= "p" (:notion-page-id (tickets/read-meta :brian "BR-1")))))))

(deftest complete-sets-terminal-fields
  (with-tmp
    (fn [_]
      (with-redefs [clock/now-iso (constantly "2026-06-04T10:00:00Z")]
        (tickets/open! :brian "BR-1" {:notion-page-id "p" :url "u" :title "T"
                                      :opened-by :triage-new :notion-last-edited-at "t0"})
        (tickets/complete! :brian "BR-1" :triaged :applied)
        (let [m (tickets/read-meta :brian "BR-1")]
          (is (= :triaged (:status m)))
          (is (= :applied (:disposition m)))
          (is (= "2026-06-04T10:00:00Z" (:triaged-at m))))))))

(deftest clear-status-makes-retriable
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-1" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/clear-status! :brian "BR-1")
      (is (nil? (tickets/status :brian "BR-1")))
      (is (= :spawn (tickets/gate-decision :brian "BR-1"))))))

(deftest dismiss-sets-dismissed-and-creates-record-when-absent
  (with-tmp
    (fn [_]
      (tickets/dismiss! :brian "BR-9")
      (is (= :dismissed (tickets/status :brian "BR-9")))
      (is (= :skip-completed (tickets/gate-decision :brian "BR-9"))))))

(deftest gate-decision-skips-in-progress-and-dismissed
  (with-tmp
    (fn [_]
      (tickets/set-status! :brian "BR-1" :implementing)
      (is (= :skip-active (tickets/gate-decision :brian "BR-1")))
      (tickets/set-status! :brian "BR-2" :planning)
      (is (= :skip-active (tickets/gate-decision :brian "BR-2")))
      (tickets/set-status! :brian "BR-3" :dismissed)
      (is (= :skip-completed (tickets/gate-decision :brian "BR-3"))))))

(deftest promote-decision-skips-dismissed
  (with-tmp
    (fn [_]
      (tickets/dismiss! :brian "BR-4")
      (is (= :skip-completed (tickets/promote-decision :brian "BR-4"))))))

(deftest on-run-terminal-leaves-dismissed-intact
  (with-tmp
    (fn [_]
      (tickets/dismiss! :brian "BR-7")
      (tickets/on-run-terminal!
        {:skill :triage-bug :project :brian :event-payload {:id "BR-7"}} :failed)
      (is (= :dismissed (tickets/status :brian "BR-7"))))))

(deftest gate-decision-three-way
  (with-tmp
    (fn [_]
      (is (= :spawn (tickets/gate-decision :brian "BR-none")))            ; no record
      (tickets/open! :brian "BR-a" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (is (= :skip-active (tickets/gate-decision :brian "BR-a")))         ; :investigating
      (tickets/set-status! :brian "BR-a" :awaiting-input)
      (is (= :skip-active (tickets/gate-decision :brian "BR-a")))
      (tickets/complete! :brian "BR-a" :triaged :applied)
      (is (= :skip-completed (tickets/gate-decision :brian "BR-a"))))))

(deftest on-run-terminal-clears-stale-investigating-but-keeps-disposition
  (with-tmp
    (fn [_]
      ;; A run that died while :investigating → status cleared (re-triable).
      (tickets/open! :brian "BR-x" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/on-run-terminal!
        {:project :brian :skill :triage-bug :event-payload {:id "BR-x"}} :failed)
      (is (nil? (tickets/status :brian "BR-x")) "stale :investigating cleared")

      ;; A completed triage → leave terminal disposition untouched.
      (tickets/open! :brian "BR-y" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/complete! :brian "BR-y" :triaged :applied)
      (tickets/on-run-terminal!
        {:project :brian :skill :triage-bug :event-payload {:id "BR-y"}} :done)
      (is (= :triaged (tickets/status :brian "BR-y")) "completed status preserved")

      ;; A run parked at awaiting-input (→ :awaiting-review) → leave it parked.
      (tickets/open! :brian "BR-z" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/set-status! :brian "BR-z" :awaiting-input)
      (tickets/on-run-terminal!
        {:project :brian :skill :triage-bug :event-payload {:id "BR-z"}} :awaiting-review)
      (is (= :awaiting-input (tickets/status :brian "BR-z")) "parked draft preserved")

      ;; Non-triage runs are ignored.
      (is (nil? (tickets/on-run-terminal!
                  {:project :brian :skill :investigate-bug :event-payload {:id "BR-q"}} :failed))))))

(deftest on-run-terminal-tolerates-missing-br-id
  (with-tmp
    (fn [_]
      (is (nil? (tickets/on-run-terminal!
                  {:skill :triage-bug :project :brian :event-payload nil} :failed)))
      (is (nil? (tickets/on-run-terminal!
                  {:skill :triage-bug :project :brian :event-payload {:id ""}} :failed)))
      ;; the shared nil/blank-id guard must hold for :plan-bug too
      (is (nil? (tickets/on-run-terminal!
                  {:skill :plan-bug :project :brian :event-payload nil} :failed)))
      (is (nil? (tickets/on-run-terminal!
                  {:skill :plan-bug :project :brian :event-payload {:id ""}} :failed))))))

(deftest promote-decision-allows-only-triaged
  (with-tmp
    (fn [_]
      (is (= :skip-no-record (tickets/promote-decision :brian "BR-X")))   ; no record
      (tickets/open! :brian "BR-1" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (is (= :skip-untriaged (tickets/promote-decision :brian "BR-1")))   ; :investigating
      (tickets/complete! :brian "BR-1" :triaged :applied)
      (is (= :promote (tickets/promote-decision :brian "BR-1")))          ; the one yes
      (tickets/set-status! :brian "BR-1" :planning)
      (is (= :skip-active (tickets/promote-decision :brian "BR-1")))      ; already planning
      (tickets/dismiss! :brian "BR-1")
      (is (= :skip-completed (tickets/promote-decision :brian "BR-1"))))))

(deftest on-run-terminal-plan-bug-abnormal-exit-reverts-to-triaged
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-2" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/complete! :brian "BR-2" :triaged :applied)
      (tickets/set-status! :brian "BR-2" :planning)
      ;; a :failed plan run with a stale :planning ⇒ re-promotable (:triaged)
      (tickets/on-run-terminal! {:skill :plan-bug :project :brian
                                 :event-payload {:id "BR-2"}} :failed)
      (is (= :triaged (tickets/status :brian "BR-2"))))))

(deftest on-run-terminal-plan-bug-parked-is-left-alone
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-3" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/set-status! :brian "BR-3" :awaiting-input)   ; plan parked
      (tickets/on-run-terminal! {:skill :plan-bug :project :brian
                                 :event-payload {:id "BR-3"}} :awaiting-review)
      (is (= :awaiting-input (tickets/status :brian "BR-3"))))))

(deftest on-run-terminal-plan-bug-leaves-advanced-implementing-ticket-alone
  ;; Regression: a promote burst can drive the ticket all the way to
  ;; :implementing before the plan Run exits. The plan Run then terminates
  ;; non-parked (:done via run-state-from-ticket, or :failed on a restart
  ;; orphan), but ownership has already handed off to implementation — the
  ;; plan Run must NOT yank the ticket back to :triaged.
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-4" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/complete! :brian "BR-4" :triaged :applied)
      (tickets/set-status! :brian "BR-4" :implementing)     ; burst advanced past planning
      (tickets/on-run-terminal! {:skill :plan-bug :project :brian
                                 :event-payload {:id "BR-4"}} :done)
      (is (= :implementing (tickets/status :brian "BR-4"))
          "plan Run terminating :done must not revert an :implementing ticket")
      ;; same must hold for a restart-orphaned plan Run forced to :failed
      (tickets/on-run-terminal! {:skill :plan-bug :project :brian
                                 :event-payload {:id "BR-4"}} :failed)
      (is (= :implementing (tickets/status :brian "BR-4"))
          "a restart-orphaned plan Run must not revert an :implementing ticket"))))

;; ---------------------------------------------------------------------------
;; Ledger unification Task 2: entry writes moved to the workstream ledger
;; ---------------------------------------------------------------------------

(deftest tickets-no-longer-writes-entries
  (is (nil? (resolve 'nido.coordinator.record.tickets/append-entry!))
      "entry writes moved to the workstream ledger"))
