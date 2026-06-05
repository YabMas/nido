(ns nido.coordinator.workstream-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]))

(def example-ws
  {:id            "ws-20260605-a1b2c3"
   :project       :brian
   :external-refs [{:adapter :notion :id "BR-4659"
                    :page-id "p" :url "u" :title "Firefox loading"}]
   :stage         :investigation
   :stage-history [{:at "2026-06-05T09:00:00Z" :stage :investigation}]
   :closed        nil
   :created-at    "2026-06-05T09:00:00Z"
   :entries       []})

(deftest schema-accepts-valid-workstream
  (is (m/validate ws/Workstream example-ws)))

(deftest schema-rejects-missing-id
  (is (not (m/validate ws/Workstream (dissoc example-ws :id)))))

(deftest stage-is-a-free-keyword
  (is (m/validate ws/Workstream (assoc example-ws :stage :some-project-specific-stage))))

(deftest mint-id-has-ws-prefix-and-is-unique
  (with-redefs [clock/now-iso (constantly "2026-06-05T09:00:00Z")]
    (let [a (ws/mint-id) b (ws/mint-id)]
      (is (str/starts-with? a "ws-20260605-"))
      (is (not= a b)))))

(deftest round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (ws/write! example-ws)
        (is (= example-ws (ws/read-ws :brian (:id example-ws)))))
      (finally (fs/delete-tree tmp)))))

(deftest read-ws-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (nil? (ws/read-ws :brian "nope"))))
      (finally (fs/delete-tree tmp)))))

(deftest create-mints-id-and-seeds-stage-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [w (ws/create! :brian {:stage :investigation
                                    :external-refs [{:adapter :notion :id "BR-1"}]})]
          (is (str/starts-with? (:id w) "ws-"))
          (is (= :investigation (:stage w)))
          (is (= [{:at "2026-06-05T09:00:00Z" :stage :investigation}] (:stage-history w)))
          (is (nil? (:closed w)))
          (is (= [] (:entries w)))
          (is (= w (ws/read-ws :brian (:id w))))))
      (finally (fs/delete-tree tmp)))))

(deftest advance-stage-appends-history-and-updates-stage
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T10:00:00Z")]
        (ws/write! example-ws)
        (let [updated (ws/advance-stage! :brian (:id example-ws) :triaged)]
          (is (= :triaged (:stage updated)))
          (is (= 2 (count (:stage-history updated))))
          (is (= {:at "2026-06-05T10:00:00Z" :stage :triaged}
                 (last (:stage-history updated))))
          (is (= updated (ws/read-ws :brian (:id example-ws))))))
      (finally (fs/delete-tree tmp)))))

(deftest advance-stage-is-noop-when-stage-unchanged
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (ws/write! example-ws)
        (let [updated (ws/advance-stage! :brian (:id example-ws) :investigation)]
          (is (= 1 (count (:stage-history updated))))))
      (finally (fs/delete-tree tmp)))))

(deftest close-sets-closed-with-outcome
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T11:00:00Z")]
        (ws/write! example-ws)
        (let [closed (ws/close! :brian (:id example-ws) :dropped)]
          (is (= {:at "2026-06-05T11:00:00Z" :outcome :dropped} (:closed closed)))))
      (finally (fs/delete-tree tmp)))))
