(ns tasks.nido-facets-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.facets :as facets]
   [tasks.nido-facets :as t]))

(deftest refresh-cmd-refreshes-whole-project-by-default
  (let [calls (atom [])]
    (with-redefs [facets/refresh-project! (fn [p] (swap! calls conj [:project p]) 3)]
      (t/refresh-cmd ":project" "brian")
      (is (= [[:project :brian]] @calls)))))

(deftest refresh-cmd-refreshes-one-ws-when-given
  (let [calls (atom [])]
    (with-redefs [facets/refresh-ws! (fn [p id] (swap! calls conj [:ws p id]))]
      (t/refresh-cmd ":project" "brian" ":ws" "ws-1")
      (is (= [[:ws :brian "ws-1"]] @calls)))))
