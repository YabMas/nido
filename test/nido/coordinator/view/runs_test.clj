(ns nido.coordinator.view.runs-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.daemon.breakers :as breakers]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.daemon.halt :as halt]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.view.runs :as rv]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(def base-run
  {:id "2026-05-13-brian-investigate-bug-a1b2c3"
   :project :brian
   :trigger :investigate-bug
   :source {:type :manual :fired-at "T" :fired-by "u"}
   :event-payload {:url "https://x"}
   :skill :investigate-bug
   :first-message "/investigate-bug https://x"
   :agent :claude
   :session-name "run-brian-investigate-bug-a1b2c3"
   :claude-session-id nil
   :limits {:budget "30m"}
   :priority 0
   :session-profile :full
   :uncapped? false
   :state :queued
   :state-history [{:at "2026-05-13T09:14:22Z" :state :queued}]
   :artifacts []
   :error nil})

(defn- with-tmp-runs-dir [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest read-all-runs-empty-when-no-runs
  (with-tmp-runs-dir
    (fn []
      (is (= [] (rv/read-all-runs))))))

(deftest read-all-runs-finds-and-validates-runs
  (with-tmp-runs-dir
    (fn []
      (fs/create-dirs (cstate/run-dir (:id base-run)))
      (runs/write-run! base-run)
      (let [loaded (rv/read-all-runs)]
        (is (= 1 (count loaded)))
        (is (= (:id base-run) (-> loaded first :id)))))))

(deftest classify-by-state
  (is (= :needs-attention (rv/classify (assoc base-run :state :awaiting-review))))
  (is (= :needs-attention (rv/classify (assoc base-run :state :failed))))
  (is (= :needs-attention (rv/classify (assoc base-run :state :halted))))
  (is (= :in-flight       (rv/classify (assoc base-run :state :queued))))
  (is (= :in-flight       (rv/classify (assoc base-run :state :running))))
  (is (= :recent          (rv/classify (assoc base-run :state :done))))
  (is (= :archive         (rv/classify (assoc base-run :state :dry-run-would-fire)))))

(deftest grouped-runs-buckets-correctly
  (let [r-queued    (assoc base-run :id "a" :state :queued)
        r-running   (assoc base-run :id "b" :state :running)
        r-awaiting  (assoc base-run :id "c" :state :awaiting-review)
        r-failed    (assoc base-run :id "d" :state :failed)
        r-done      (assoc base-run :id "e" :state :done)
        groups      (rv/grouped-runs [r-queued r-running r-awaiting r-failed r-done])]
    (is (= #{"c" "d"} (set (map :id (:needs-attention groups)))))
    (is (= #{"a" "b"} (set (map :id (:in-flight groups)))))
    (is (= #{"e"}     (set (map :id (:recent groups)))))))

(deftest grouped-runs-recent-capped-at-10
  (let [done-runs (for [i (range 15)]
                    (assoc base-run :id (format "r%02d" i) :state :done))
        groups    (rv/grouped-runs done-runs)]
    (is (= 10 (count (:recent groups))))))

(deftest format-row-shape
  (is (= "[awaiting       ] brian · investigate-bug · 2026-05-13-brian-investigate-bug-a1b2c3"
         (rv/format-row (assoc base-run :state :awaiting-review)))))

(deftest format-row-uses-payload-key-when-trigger-config-present
  (is (= "[done           ] brian · investigate-bug · 2026-05-13-brian-investigate-bug-a1b2c3"
         (rv/format-row (assoc base-run :state :done)))))

(deftest format-row-prefers-br-and-title-from-event-payload
  ;; long titles truncate at 40 chars + ellipsis so rows stay tidy
  (is (= "[done           ] brian · triage-teacher-bugs · BR-4674 · pop up of modal after clicking on review…"
         (rv/format-row (assoc base-run
                               :state :done
                               :trigger :triage-teacher-bugs
                               :event-payload {:id "BR-4674"
                                               :title "pop up of modal after clicking on review (vocab)"}))))
  ;; title only (no BR id) → title alone
  (is (= "[done           ] brian · investigate-bug · smoke target"
         (rv/format-row (assoc base-run :state :done
                               :event-payload {:title "smoke target"}))))
  ;; neither → fall back to run-id
  (is (= "[done           ] brian · investigate-bug · 2026-05-13-brian-investigate-bug-a1b2c3"
         (rv/format-row (assoc base-run :state :done :event-payload {})))))

(deftest format-age
  (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:00Z")]
    (is (= "just now" (rv/format-age "2026-05-13T09:59:55Z")))
    (is (= "30s ago"  (rv/format-age "2026-05-13T09:59:30Z")))
    (is (= "12m ago"  (rv/format-age "2026-05-13T09:48:00Z")))
    (is (= "3h ago"   (rv/format-age "2026-05-13T07:00:00Z")))
    (is (= "2d ago"   (rv/format-age "2026-05-11T10:00:00Z")))))

(deftest read-coordinator-status-when-absent
  (with-tmp-runs-dir
    (fn []
      (let [s (rv/read-coordinator-status)]
        (is (= :unreachable (:status s)))
        (is (false? (:reachable? s)))))))

(deftest read-coordinator-status-fresh-heartbeat
  (with-tmp-runs-dir
    (fn []
      (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:00Z")]
        (cstate/ensure-dirs!)
        (io/write-edn! (cstate/status-path)
                       {:status :running :slots-in-use 1 :heartbeat-at "2026-05-13T09:59:59Z"})
        (let [s (rv/read-coordinator-status)]
          (is (= :running (:status s)))
          (is (= 1 (:slots-in-use s)))
          (is (true? (:reachable? s))))))))

(deftest read-coordinator-status-stale-heartbeat
  (with-tmp-runs-dir
    (fn []
      (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:00Z")]
        (cstate/ensure-dirs!)
        (io/write-edn! (cstate/status-path)
                       {:status :running :slots-in-use 0 :heartbeat-at "2026-05-13T09:59:30Z"})
        (let [s (rv/read-coordinator-status)]
          (is (false? (:reachable? s))
              "30s-old heartbeat should be considered unreachable (>5s threshold)"))))))

(deftest read-coordinator-status-includes-alerts
  (with-tmp-runs-dir
    (fn []
      (let [s (rv/read-coordinator-status)]
        (is (contains? s :alerts))
        (is (false? (-> s :alerts :halted?)))
        (is (= 0 (-> s :alerts :breakers)))))))

(deftest read-coordinator-status-includes-executor-snapshot
  (with-tmp-runs-dir
    (fn []
      (with-redefs [executor/snapshot
                    (constantly {:cap 5 :in-flight 2 :queued 3 :queue ["a" "b" "c"]})]
        (let [s (rv/read-coordinator-status)]
          (is (= 5 (-> s :executor :cap)))
          (is (= 2 (-> s :executor :in-flight)))
          (is (= 3 (-> s :executor :queued))))))))

(deftest breaker-reason-text
  (is (= "paused by you — new-reports paused"
         (rv/breaker-reason {:disabled-by-user? true :note "new-reports paused"})))
  (is (= "paused by you"
         (rv/breaker-reason {:disabled-by-user? true :note nil})))
  (with-redefs [clock/now-iso (constantly "2026-06-05T10:00:00Z")]
    (is (= "3 consecutive failures, last 5m ago"
           (rv/breaker-reason {:disabled-by-user? false :consecutive-failures 3
                               :last-failure-at "2026-06-05T09:55:00Z"})))
    (is (= "1 consecutive failure, last 5m ago"
           (rv/breaker-reason {:consecutive-failures 1 :last-failure-at "2026-06-05T09:55:00Z"})))))

(deftest read-alerts-includes-breaker-reasons
  (with-redefs [halt/read-halt-info (constantly nil)
                breakers/tripped-triggers
                (constantly [{:project :brian :trigger :triage-new
                              :info {:disabled-by-user? true :note "paused"}}
                             {:project :brian :trigger :triage-teacher-bugs
                              :info {:disabled-by-user? false :consecutive-failures 3
                                     :last-failure-at nil}}])]
    (let [a (rv/read-alerts)]
      (is (= 2 (:breakers a)))
      (is (= 1 (:breakers-paused a)) "user-disabled count")
      (is (= 1 (:breakers-failing a)) "failure-tripped count")
      (is (= {:project :brian :trigger :triage-new :disabled? true
              :reason "paused by you — paused"}
             (first (:breaker-triggers a)))))))
