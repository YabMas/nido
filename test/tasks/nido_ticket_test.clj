(ns tasks.nido-ticket-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.facets :as facets]
   [nido.coordinator.tickets :as tickets]
   [tasks.nido-ticket :as t]))

(deftest complete-refreshes-facets-after-completing
  (let [calls (atom [])]
    (with-redefs [tickets/complete!         (fn [p br st disp] (swap! calls conj [:complete p br st disp]))
                  facets/refresh-for-ticket! (fn [p br] (swap! calls conj [:refresh p br]))]
      (t/complete-cmd ":project" "brian" ":br" "BR-1" ":status" "triaged" ":disposition" "real-bug")
      (is (= [[:complete :brian "BR-1" :triaged :real-bug]
              [:refresh :brian "BR-1"]]
             @calls)
          "complete! first, then a single facet refresh"))))
