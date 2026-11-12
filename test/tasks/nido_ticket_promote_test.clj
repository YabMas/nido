(ns tasks.nido-ticket-promote-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.promote :as promote]
   [tasks.nido-ticket :as nido-ticket]))

(deftest promote-cmd-passes-project-and-br-and-prints
  (let [seen (atom nil)
        out  (with-out-str
               (with-redefs [promote/promote! (fn [p br] (reset! seen [p br])
                                                {:decision :promote :ws-id "ws-1" :session "impl-br-7"})]
                 (nido-ticket/promote-cmd ":project" "brian" ":br" "BR-7")))]
    (is (= [:brian "BR-7"] @seen))
    (is (re-find #"started BR-7 → session impl-br-7 on ws-1" out))))

(deftest promote-cmd-accepts-positional-br
  (let [seen (atom nil)]
    (with-redefs [promote/promote! (fn [p br] (reset! seen [p br])
                                     {:decision :promote :ws-id "ws-1" :session "impl-br-9"})]
      (with-out-str (nido-ticket/promote-cmd ":project" "brian" "BR-9")))
    (is (= [:brian "BR-9"] @seen))))
