(ns nido.coordinator.status-file-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.status-file :as sf]
   [nido.io :as io]))

(deftest read-returns-nil-when-absent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (is (nil? (sf/read-status "r1"))))
      (finally (fs/delete-tree tmp)))))

(deftest read-returns-map-when-present
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (io/write-edn! (cstate/run-status-path "r1")
                       {:phase :awaiting-input :note "look"})
        (is (= {:phase :awaiting-input :note "look"}
               (sf/read-status "r1"))))
      (finally (fs/delete-tree tmp)))))

(deftest phase->state
  (is (= :awaiting-review (sf/phase->state :awaiting-input)))
  (is (= :done            (sf/phase->state :complete)))
  (is (= :failed          (sf/phase->state :error)))
  (is (nil?               (sf/phase->state :investigating)))
  (is (nil?               (sf/phase->state :working))))

(deftest derived-state-after-clean-exit
  (testing "status says awaiting-input"
    (is (= :awaiting-review
           (sf/derive-state-after-exit {:phase :awaiting-input}))))
  (testing "status says complete"
    (is (= :done
           (sf/derive-state-after-exit {:phase :complete}))))
  (testing "status absent — treat as done"
    (is (= :done (sf/derive-state-after-exit nil)))))
