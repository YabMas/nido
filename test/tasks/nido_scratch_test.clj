(ns tasks.nido-scratch-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-scratch :as task]))

(deftest backfill-births-one-loose-workstream-per-session
  (let [births (atom [])]
    (with-redefs [lifecycle/list-all-data (fn [_] {:sessions [{:name "a"} {:name "b"}]})
                  scratch/birth!          (fn [p n] (swap! births conj [p n]) "ws-x")]
      (task/backfill ":project" "brian")
      (is (= [[:brian "a"] [:brian "b"]] @births)
          "one idempotent birth! per existing session, keyword project"))))
