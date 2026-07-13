(ns nido.coordinator.tickets-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
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
        (is (nil? (tickets/clear-status! :brian br)))
        (is (nil? (tickets/append-entry! :brian br {:kind :note} "body"))))
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

(deftest append-entry-writes-file-and-records-it
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-1" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (let [path (tickets/append-entry! :brian "BR-1"
                                        {:kind :note :session "s1" :run-id "r1"}
                                        "# report body")
            m    (tickets/read-meta :brian "BR-1")]
        (is (fs/exists? path))
        (is (= "# report body" (slurp path)))
        (is (= 1 (count (:entries m))))
        (is (= "entries/0001-note.md" (:file (first (:entries m)))))))))

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

(deftest append-entry-numbers-sequentially
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-2" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/append-entry! :brian "BR-2" {:kind :note :session "s" :run-id "r"} "first")
      (tickets/append-entry! :brian "BR-2" {:kind :note :session "s" :run-id "r"} "second")
      (let [m (tickets/read-meta :brian "BR-2")]
        (is (= 2 (count (:entries m))))
        (is (= 2 (:seq (second (:entries m)))))
        (is (= "entries/0002-note.md" (:file (second (:entries m)))))))))

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
;; Task 2: triage EDN validation at append + latest-triage-report
;; ---------------------------------------------------------------------------

(defn- with-ticket-tmp
  "Like with-tmp but calls f with no argument (brief-style)."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(def ^:private edn-report
  (pr-str {:format :triage-report :ticket-key "BR-7" :determination :bug
           :title "t" :summary "s" :confidence {:level :high :reason "r"}
           :directions [{:label "A" :shape "x" :effort :M
                         :confidence {:level :medium :reason "r"}}]
           :notion-writes nil
           :trail [{:ref "f:1" :note "n"}]}))

(deftest triage-append-stores-validated-edn
  (with-ticket-tmp
    (fn []
      (tickets/open! :brian "BR-7" {:title "t"})
      (let [path (tickets/append-entry! :brian "BR-7"
                                        {:kind :triage :session "s" :run-id "r"}
                                        edn-report)]
        (is (str/ends-with? path ".edn") "triage entries are .edn")
        (is (= :bug (:determination (tickets/latest-triage-report :brian "BR-7"))))))))

(deftest triage-append-rejects-invalid-report
  (with-ticket-tmp
    (fn []
      (tickets/open! :brian "BR-7" {:title "t"})
      (is (thrown? clojure.lang.ExceptionInfo
                   (tickets/append-entry! :brian "BR-7"
                                          {:kind :triage :session "s" :run-id "r"}
                                          (pr-str {:format :triage-report :bogus 1})))))))

(deftest non-triage-append-stays-markdown
  (with-ticket-tmp
    (fn []
      (tickets/open! :brian "BR-7" {:title "t"})
      (let [path (tickets/append-entry! :brian "BR-7"
                                        {:kind :note :session "s" :run-id "r"}
                                        "# free text")]
        (is (str/ends-with? path ".md"))
        (is (nil? (tickets/latest-triage-report :brian "BR-7"))
            "a non-edn entry is not a triage report")))))

;; ---------------------------------------------------------------------------
;; Task 1: has-triage-report?
;; ---------------------------------------------------------------------------

(deftest has-triage-report?-detects-triage-entry
  (with-tmp
    (fn [_]
      (tickets/write-meta! :brian "BR-1"
        {:br-id "BR-1" :status :dismissed
         :entries [{:kind :note :seq 1} {:kind :triage :seq 2}]})
      (is (true? (tickets/has-triage-report? :brian "BR-1")) "has a :triage entry")
      (tickets/write-meta! :brian "BR-2"
        {:br-id "BR-2" :status :investigating :entries [{:kind :note :seq 1}]})
      (is (false? (tickets/has-triage-report? :brian "BR-2")) "no :triage entry")
      (is (false? (tickets/has-triage-report? :brian "BR-nope")) "no ticket record → false"))))
