(ns nido.ship-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.queue :as queue]
   [nido.ship :as sut]))

(deftest enqueue-ship-writes-a-ship-envelope
  (let [written (atom nil)]
    (with-redefs [queue/enqueue! (fn [env] (reset! written env) "/tmp/x.edn")]
      (sut/enqueue-ship! {:project :brian :session "impl-br-1" :ws-id "ws-1"})
      (is (= :ship       (:type @written)))
      (is (= :brian      (:project @written)))
      (is (= "impl-br-1" (:session @written)))
      (is (= "ws-1"      (:ws-id @written))))))
