(ns tasks.nido-ticket-promote-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.workstream :as workstream]
   [nido.coordinator.work :as work]
   [tasks.nido-ticket :as nido-ticket]))

(deftest promote-cmd-opens-the-tickets-unit-in-the-core
  (let [seen (atom nil)
        out  (with-out-str
               (with-redefs [workstream/find-by-ref-id (fn [_ br] (when (= "BR-7" br) {:id "ws-1"}))
                             work/set-stage! (fn [p ws-id stage] (reset! seen [p ws-id stage])
                                               {:decision :promoted})]
                 (nido-ticket/promote-cmd ":project" "brian" ":br" "BR-7")))]
    (is (= [:brian "ws-1" :in-progress] @seen))
    (is (re-find #"started BR-7 on ws-1" out))))

(deftest promote-cmd-accepts-positional-br
  (let [seen (atom nil)]
    (with-redefs [workstream/find-by-ref-id (fn [_ br] (reset! seen br) {:id "ws-1"})
                  work/set-stage! (fn [& _] {:decision :promoted})]
      (with-out-str (nido-ticket/promote-cmd ":project" "brian" "BR-9")))
    (is (= "BR-9" @seen))))
