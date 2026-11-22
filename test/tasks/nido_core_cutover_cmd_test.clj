(ns tasks.nido-core-cutover-cmd-test
  "The hand-over plan: each unclosed workstream handed over, one with a live session opening its
   unit over it, an unknown where that session was stopped mid-turn; a closed one left alone."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]
   [nido.platform.project :as project]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-core-cutover-cmd :as cutover]))

(deftest each-unclosed-workstream-is-handed-over
  (let [tmp (fs/create-temp-dir) wt (str (fs/canonicalize (fs/create-dirs (fs/path tmp "wt-s1"))))]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    project/list-projects (fn [] {"brian" {:directory (str tmp)}})
                    lifecycle/session-coords (fn [n _] {:wt-path (if (= "s1" n) wt (str tmp "/gone"))})]
        (cstate/ensure-dirs!)
        (let [a (ws/create! :brian {:external-refs []})
              b (ws/create! :brian {:external-refs []})
              c (ws/create! :brian {:external-refs []})]
          (session/create! :brian (:id a) {:name "s1" :weight :heavy})
          (session/create! :brian (:id b) {:name "s2" :weight :heavy})
          (ws/close! :brian (:id c) :done nil)
          (let [plan (into {} (map (juxt :ws :commands)) (cutover/plan #{wt}))]
            (is (= #{(str "brian/" (:id a)) (str "brian/" (:id b))} (set (keys plan))))
            (is (= [:open-unit :record-unknown :hand-over] (map :command (plan (str "brian/" (:id a))))))
            (is (= wt (:session-cwd (first (plan (str "brian/" (:id a)))))))
            (is (= [:hand-over] (map :command (plan (str "brian/" (:id b)))))
                "a session whose worktree is gone opens no unit"))))
      (finally (fs/delete-tree tmp)))))
