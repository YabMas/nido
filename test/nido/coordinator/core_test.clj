(ns nido.coordinator.core-test
  "Integration smoke test: envelope → executor → run-blocking! → terminal state.
   Also covers the triage pre-spawn gate (Task 4)."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is use-fixtures]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.core :as core]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.anomaly :as anomaly]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.status-file :as status-file]))

(defn- reset-executor! [f]
  (executor/configure! {:global-cap 1})
  (executor/clear!)
  (f))

(use-fixtures :each reset-executor!)

(deftest envelope-drives-run-to-terminal-via-executor
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    agent/launch!    (fn [_]
                                       {:exit-code         0
                                        :claude-session-id "sess-x"
                                        :timed-out?        false})
                    runs/spawn-session-for-run! (fn [_] nil)]
        (cstate/ensure-dirs!)
        (let [trigger  {:name    :t
                        :source  {:type :test}
                        :skill   :noop
                        :payload "x"}
              run      (runs/create-run!
                         {:project :p :trigger trigger :payload {} :priority 0}
                         {})]
          ;; submit directly to the executor (bypassing process-envelope!)
          (executor/submit! (:id run) 0)
          ;; first tick: promotes the Run into a future that calls run-blocking!
          (executor/tick! #'nido.coordinator.core/run-blocking! {})
          ;; wait for the future to finish (agent stub is instant)
          (Thread/sleep 200)
          ;; second tick: reaps the finished future
          (executor/tick! #'nido.coordinator.core/run-blocking! {})
          (is (contains? #{:done :failed :awaiting-review}
                         (:state (runs/read-run (:id run)))))))
      (finally (fs/delete-tree tmp)))))

(deftest defaults-include-executor-shutdown-grace
  (is (= 5000 (core/shutdown-grace-ms))))

;; ---------------------------------------------------------------------------
;; Triage pre-spawn gate tests (Task 4)
;; ---------------------------------------------------------------------------
;;
;; The broadcast envelope shape (from events_test.clj and sources/notion.clj):
;;   {:broadcast {:type :notion-view
;;                :source-config {:database "x"}   ; must match trigger :source
;;                :payload {:page-id "pg" :id "BR-5236" :title "T"}}}
;;
;; The :project comes from the triggers-by-project map (NOT from the broadcast
;; itself). The task-description's test literal had :project in the broadcast,
;; which was incorrect — corrected here.
;;
;; The triage-trigger's :source must match the broadcast's :source-config via
;; events/route-broadcast's source-config-match? (compares sans :type).

(defn- gate-with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private triage-trigger
  {:name    :triage-new
   :skill   :triage-bug
   :payload "Triage {{event/title}}"
   :source  {:type :notion-view :database "triage-db"}})

(def ^:private triage-envelope
  {:broadcast {:type          :notion-view
               :source-config {:database "triage-db"}
               :payload       {:source   :notion-view
                               :page-id  "pg"
                               :id       "BR-5236"
                               :title    "T"}}})

(deftest gate-skips-completed-triage-ticket
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-5236"
                     {:notion-page-id "pg" :url "u" :title "T"
                      :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/complete! :brian "BR-5236" :triaged :applied)
      (let [created (atom 0)]
        (with-redefs [runs/create-run! (fn [& _] (swap! created inc) {:id "x"})]
          (#'core/process-envelope!
            triage-envelope
            {:brian [triage-trigger]})
          (is (zero? @created) "completed ticket must not create a Run"))))))

(deftest gate-skips-active-triage-ticket
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-5236"
                     {:notion-page-id "pg" :url "u" :title "T"
                      :opened-by :triage-new :notion-last-edited-at "t0"})
      ;; status is :investigating (the default after open!)
      (let [created (atom 0)]
        (with-redefs [runs/create-run! (fn [& _] (swap! created inc) {:id "x"})]
          (#'core/process-envelope!
            triage-envelope
            {:brian [triage-trigger]})
          (is (zero? @created) ":investigating ticket must not spawn a duplicate"))))))

(deftest gate-allows-untriaged-ticket
  (gate-with-tmp
    (fn [_]
      ;; No ticket record exists — gate-decision returns :spawn.
      ;; anomaly/record-spawn is no-op'd so the shared !detector atom is not
      ;; polluted across test namespaces (which would cause the e2e breaker
      ;; test to trip the spawn-burst anomaly threshold early).
      (let [created (atom 0)]
        (with-redefs [runs/create-run!        (fn [& _]
                                                (swap! created inc)
                                                {:id "run-x" :priority 0 :uncapped? false})
                      executor/submit!        (fn [& _] nil)
                      anomaly/record-spawn    (fn [det _] det)]
          (#'core/process-envelope!
            triage-envelope
            {:brian [triage-trigger]})
          (is (= 1 @created) "untriaged ticket creates a Run"))))))

(deftest gate-does-not-affect-non-triage-triggers
  (gate-with-tmp
    (fn [_]
      ;; A non-triage trigger (different :skill) fires unconditionally.
      ;; anomaly/record-spawn is no-op'd for the same reason as above.
      (let [non-triage-trigger {:name    :investigate-new
                                :skill   :investigate-bug
                                :payload "Investigate {{event/title}}"
                                :source  {:type :notion-view :database "triage-db"}}
            created            (atom 0)]
        (with-redefs [runs/create-run!      (fn [& _]
                                              (swap! created inc)
                                              {:id "run-y" :priority 0 :uncapped? false})
                      executor/submit!      (fn [& _] nil)
                      anomaly/record-spawn  (fn [det _] det)]
          (#'core/process-envelope!
            triage-envelope
            {:brian [non-triage-trigger]})
          (is (= 1 @created) "non-triage trigger must not be blocked by the gate"))))))

;; ---------------------------------------------------------------------------
;; Run-termination hook tests (Task 5)
;; ---------------------------------------------------------------------------

(deftest run-blocking-clears-ticket-on-abnormal-exit
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-7" {:notion-page-id "pg" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      ;; Stand up a minimal :queued triage Run on disk.
      ;; run-blocking! first transitions :queued → :running, so the run
      ;; must start in :queued state (not :running).
      (let [run {:id "r7" :project :brian :trigger :triage-new
                 :source {:type :notion-view} :event-payload {:id "BR-7"}
                 :skill :triage-bug :first-message "/triage-bug x" :agent :claude
                 :session-name "run-x" :claude-session-id nil
                 :limits {:budget "15m" :max-failures 3} :priority 10
                 :session-profile :lite :uncapped? true :state :queued
                 :state-history [{:at "t" :state :queued}] :artifacts [] :error nil}]
        (runs/write-run! run)
        (with-redefs [;; force an abnormal (:failed) outcome without launching claude:
                      ;; exit-code 1 (non-zero) → :else :failed branch in run-blocking!
                      ;; status-file/read-status is NOT called on the :else path
                      runs/spawn-session-for-run! (fn [_] nil)
                      agent/launch!               (fn [_] {:exit-code 1 :timed-out? false
                                                           :claude-session-id nil})
                      cstate/run-session-home-link (constantly "/tmp/nonexistent")
                      ;; no-op anomaly/breaker side-effects: the !detector atom and
                      ;; breakers file are global across the test suite; recording a
                      ;; failure here would inflate counts and cause the e2e breaker
                      ;; test to trip the anomaly auto-halt during tick! — same
                      ;; isolation pattern used in gate-allows-untriaged-ticket.
                      anomaly/record-failure      (fn [det _] det)
                      breakers/record-failure!    (fn [& _] nil)]
          (#'core/run-blocking! "r7")
          (is (nil? (tickets/status :brian "BR-7"))
              "abnormal triage exit clears the stale :investigating status"))))))

(deftest run-blocking-fails-cleanly-when-session-spawn-throws
  ;; If spawn-session-for-run! (or launch) throws, run-blocking! must mark the
  ;; run :failed — NOT leave it stuck :running (a zombie that leaks an in-flight
  ;; slot). The ticket is then cleared → re-triable.
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-Z" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (runs/write-run! {:id "rz" :project :brian :trigger :triage-teacher-bugs
                        :source {:type :notion-view} :event-payload {:id "BR-Z"}
                        :skill :triage-bug :first-message "x" :agent :claude
                        :session-name "run-rz" :claude-session-id nil :limits {}
                        :priority 0 :session-profile :lite :uncapped? false
                        :state :queued :state-history [{:at "t" :state :queued}]
                        :artifacts [] :error nil})
      (with-redefs [runs/spawn-session-for-run! (fn [_] (throw (ex-info "boom: worktree create failed" {})))
                    nido.coordinator.agent/launch! (fn [_] {:exit-code 0 :timed-out? false}) ; unreached
                    cstate/run-session-home-link (constantly "/tmp/nope")
                    anomaly/record-failure      (fn [det _] det)
                    breakers/record-failure!    (fn [& _] nil)]
        (#'core/run-blocking! "rz")
        (is (= :failed (:state (runs/read-run "rz")))
            "spawn failure ⇒ run :failed, not stuck :running (no zombie)")
        (is (= :spawn-failed (-> (runs/read-run "rz") :error :reason)))
        (is (nil? (tickets/status :brian "BR-Z"))
            "ticket cleared on spawn-failure terminal ⇒ re-triable")))))

(deftest run-blocking-parks-triage-run-from-ticket-status
  (gate-with-tmp
    (fn [_]
      (tickets/open! :brian "BR-11" {:notion-page-id "p" :url "u" :title "T"
                                     :opened-by :triage-teacher-bugs :notion-last-edited-at "t"})
      (tickets/set-status! :brian "BR-11" :awaiting-input)   ; skill parked
      (runs/write-run! {:id "rp" :project :brian :trigger :triage-teacher-bugs
                        :source {:type :notion-view} :event-payload {:id "BR-11"}
                        :skill :triage-bug :first-message "x" :agent :claude
                        :session-name "run-rp" :claude-session-id nil :limits {}
                        :priority 0 :session-profile :lite :uncapped? false
                        :state :queued :state-history [{:at "t" :state :queued}]
                        :artifacts [] :error nil})
      (with-redefs [runs/spawn-session-for-run! (fn [_] nil)
                    nido.coordinator.agent/launch! (fn [_] {:exit-code 0 :timed-out? false})
                    cstate/run-session-home-link (constantly "/tmp/nope")
                    status-file/read-status (fn [_] nil)
                    anomaly/record-failure      (fn [det _] det)
                    breakers/record-failure!    (fn [& _] nil)]
        (#'core/run-blocking! "rp")
        (is (= :awaiting-review (:state (runs/read-run "rp")))
            "clean exit + ticket :awaiting-input ⇒ run parks at :awaiting-review, not :done")))))
