(ns nido.coordinator.state-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.state :as cstate]))

(deftest paths
  (testing "coordinator-dir under ~/.nido/coordinator"
    (is (= (str (fs/path (fs/home) ".nido" "coordinator"))
           (cstate/coordinator-dir))))
  (testing "queue-dir is a child of coordinator-dir"
    (is (= (str (fs/path (cstate/coordinator-dir) "queue"))
           (cstate/queue-dir))))
  (testing "status-path is status.edn under coordinator-dir"
    (is (= (str (fs/path (cstate/coordinator-dir) "status.edn"))
           (cstate/status-path))))
  (testing "runs-dir under ~/.nido/runs"
    (is (= (str (fs/path (fs/home) ".nido" "runs"))
           (cstate/runs-dir))))
  (testing "run-dir is a child of runs-dir, named by run-id"
    (is (= (str (fs/path (cstate/runs-dir) "abc-123"))
           (cstate/run-dir "abc-123"))))
  (testing "run.edn is named correctly inside run-dir"
    (is (= (str (fs/path (cstate/run-dir "abc-123") "run.edn"))
           (cstate/run-edn-path "abc-123"))))
  (testing "triggers.edn path is per-project"
    (is (= (str (fs/path (fs/home) ".nido" "projects" "brian" "triggers.edn"))
           (cstate/triggers-path :brian)))))

(deftest workstream-paths-compose-under-project
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (is (str/ends-with? (cstate/workstreams-dir :brian) "projects/brian/workstreams"))
        (is (str/ends-with? (cstate/workstream-dir :brian "ws-1") "workstreams/ws-1"))
        (is (str/ends-with? (cstate/workstream-edn-path :brian "ws-1") "ws-1/workstream.edn"))
        (is (str/ends-with? (cstate/ws-entries-dir :brian "ws-1") "ws-1/entries"))
        (is (str/ends-with? (cstate/ws-sessions-dir :brian "ws-1") "ws-1/sessions"))
        (is (str/ends-with? (cstate/session-dir :brian "ws-1" "sx") "sessions/sx"))
        (is (str/ends-with? (cstate/session-edn-path :brian "ws-1" "sx") "sx/session.edn"))
        (is (str/ends-with? (cstate/pre-unification-dir :brian) "projects/brian/_pre-unification")))
      (finally (fs/delete-tree tmp)))))

(deftest ensure-dirs!-creates-coordinator-tree
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (is (fs/directory? (str (fs/path tmp "coordinator"))))
        (is (fs/directory? (str (fs/path tmp "coordinator" "queue"))))
        (is (fs/directory? (str (fs/path tmp "runs"))))
        ;; idempotency
        (cstate/ensure-dirs!)
        (is (fs/directory? (str (fs/path tmp "coordinator" "queue")))))
      (finally (fs/delete-tree tmp)))))
