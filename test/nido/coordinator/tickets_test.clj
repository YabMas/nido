(ns nido.coordinator.tickets-test
  (:require
   [babashka.fs :as fs]
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

(deftest append-entry-writes-file-and-records-it
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-1" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (let [path (tickets/append-entry! :brian "BR-1"
                                        {:kind :triage :session "s1" :run-id "r1"}
                                        "# report body")
            m    (tickets/read-meta :brian "BR-1")]
        (is (fs/exists? path))
        (is (= "# report body" (slurp path)))
        (is (= 1 (count (:entries m))))
        (is (= "entries/0001-triage.md" (:file (first (:entries m)))))))))

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
      (tickets/append-entry! :brian "BR-2" {:kind :triage :session "s" :run-id "r"} "first")
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
                  {:skill :triage-bug :project :brian :event-payload {:id ""}} :failed))))))
