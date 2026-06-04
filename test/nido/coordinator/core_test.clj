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
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]))

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
          (executor/tick! #'nido.coordinator.core/run-blocking!)
          ;; wait for the future to finish (agent stub is instant)
          (Thread/sleep 200)
          ;; second tick: reaps the finished future
          (executor/tick! #'nido.coordinator.core/run-blocking!)
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
