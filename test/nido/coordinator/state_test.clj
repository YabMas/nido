(ns nido.coordinator.state-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
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

(deftest ensure-dirs!-creates-coordinator-tree
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (is (fs/directory? (str (fs/path tmp "coordinator"))))
        (is (fs/directory? (str (fs/path tmp "coordinator" "queue"))))
        (is (fs/directory? (str (fs/path tmp "runs"))))
        ;; idempotency
        (cstate/ensure-dirs!)
        (is (fs/directory? (str (fs/path tmp "coordinator" "queue")))))
      (finally (fs/delete-tree tmp)))))
