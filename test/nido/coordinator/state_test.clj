(ns nido.coordinator.state-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.state :as cstate]))

(deftest paths
  (testing "coordinator-dir under ~/.nido/coordinator"
    (is (= (str (fs/path (fs/home) ".nido" "coordinator"))
           (str (cstate/coordinator-dir)))))
  (testing "queue-dir is a child of coordinator-dir"
    (is (= (str (fs/path (cstate/coordinator-dir) "queue"))
           (str (cstate/queue-dir)))))
  (testing "status-path is status.edn under coordinator-dir"
    (is (= (str (fs/path (cstate/coordinator-dir) "status.edn"))
           (str (cstate/status-path)))))
  (testing "runs-dir under ~/.nido/runs"
    (is (= (str (fs/path (fs/home) ".nido" "runs"))
           (str (cstate/runs-dir)))))
  (testing "run-dir is a child of runs-dir, named by run-id"
    (is (= (str (fs/path (cstate/runs-dir) "abc-123"))
           (str (cstate/run-dir "abc-123")))))
  (testing "run.edn is named correctly inside run-dir"
    (is (= (str (fs/path (cstate/run-dir "abc-123") "run.edn"))
           (str (cstate/run-edn-path "abc-123")))))
  (testing "triggers.edn path is per-project"
    (is (= (str (fs/path (fs/home) ".nido" "projects" "brian" "triggers.edn"))
           (str (cstate/triggers-path :brian))))))

(deftest ensure-dirs!-creates-coordinator-tree
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/coordinator-root (constantly tmp)]
        (cstate/ensure-dirs!)
        (is (fs/directory? (fs/path tmp "queue")))
        (is (fs/directory? (fs/path tmp ".."))))   ; sanity
      (finally (fs/delete-tree tmp)))))
