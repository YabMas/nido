(ns tasks.nido-ticket-promote-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.promote :as promote]
   [tasks.nido-ticket :as nido-ticket]))

(deftest promote-cmd-passes-project-and-br-and-prints
  (let [seen (atom nil)
        out  (with-out-str
               (with-redefs [promote/promote! (fn [p br] (reset! seen [p br])
                                                {:decision :promote :queued "/q/abc.edn"})]
                 (nido-ticket/promote-cmd ":project" "brian" ":br" "BR-7")))]
    (is (= [:brian "BR-7"] @seen))
    (is (re-find #"promoted BR-7" out))))

(deftest promote-cmd-accepts-positional-br
  (let [seen (atom nil)]
    (with-redefs [promote/promote! (fn [p br] (reset! seen [p br])
                                     {:decision :promote :queued "/q/x"})]
      (with-out-str (nido-ticket/promote-cmd ":project" "brian" "BR-9")))
    (is (= [:brian "BR-9"] @seen))))
