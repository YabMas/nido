(ns nido.coordinator.session-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.session :as sess]
   [nido.coordinator.state :as cstate]))

(def human-session
  {:name "explore-firefox"
   :workstream-id "ws-1"
   :project :brian
   :weight :light
   :substrate :live
   :substrate-history [{:at "2026-06-05T09:00:00Z" :substrate :live}]
   :autonomy nil
   :created-at "2026-06-05T09:00:00Z"})

(def autonomous-session
  (assoc human-session
         :name "run-triage-x"
         :weight :light
         :autonomy {:skill :triage-bug
                    :first-message "/triage-bug BR-1"
                    :agent :claude
                    :claude-session-id nil
                    :trigger :triage-bug
                    :limits {:budget "30m" :max-failures 3}
                    :priority 0
                    :uncapped? false
                    :on-promote nil
                    :phase :running
                    :phase-history [{:at "2026-06-05T09:00:00Z" :phase :queued}
                                    {:at "2026-06-05T09:01:00Z" :phase :running}]
                    :error nil}))

(deftest schema-accepts-human-and-autonomous
  (is (m/validate sess/Session human-session))
  (is (m/validate sess/Session autonomous-session)))

(deftest schema-rejects-bad-substrate
  (is (not (m/validate sess/Session (assoc human-session :substrate :nonsense)))))

(deftest schema-rejects-bad-weight
  (is (not (m/validate sess/Session (assoc human-session :weight :medium)))))

(deftest round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (sess/write! autonomous-session)
        (is (= autonomous-session
               (sess/read-session :brian "ws-1" "run-triage-x"))))
      (finally (fs/delete-tree tmp)))))

(deftest read-session-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (nil? (sess/read-session :brian "ws-1" "no-such"))))
      (finally (fs/delete-tree tmp)))))

(deftest create-seeds-substrate-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [s (sess/create! :brian "ws-1"
                              {:name "sx" :weight :heavy :autonomy nil})]
          (is (= :live (:substrate s)))
          (is (= [{:at "2026-06-05T09:00:00Z" :substrate :live}] (:substrate-history s)))
          (is (= s (sess/read-session :brian "ws-1" "sx")))))
      (finally (fs/delete-tree tmp)))))

(deftest list-sessions-returns-records-for-a-workstream
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (sess/create! :brian "ws-1" {:name "a" :weight :light :autonomy nil})
        (sess/create! :brian "ws-1" {:name "b" :weight :heavy :autonomy nil})
        (is (= #{"a" "b"} (set (map :name (sess/list-sessions :brian "ws-1"))))))
      (finally (fs/delete-tree tmp)))))
