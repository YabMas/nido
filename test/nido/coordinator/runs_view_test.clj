(ns nido.coordinator.runs-view-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.runs-view :as rv]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

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
   :state :queued
   :state-history [{:at "2026-05-13T09:14:22Z" :state :queued}]
   :artifacts []
   :error nil})

(defn- with-tmp-runs-dir [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
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
