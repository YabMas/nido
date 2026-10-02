(ns nido.boot.attention-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.boot.attention :as attention]))

(def ^:private gate {:project "brian" :ws-id "ws-1" :stage :in-progress :label "BR-1 · Fix it"})

(deftest items-key-each-kind-of-attention
  (let [its (attention/items {:gates    [gate]
                              :halt     {:source :auto :note "auto-halt: fail-rate" :halted-at "t0"}
                              :breakers [{:project :brian :trigger :triage
                                          :info {:consecutive-failures 3}}]})]
    (is (= #{[:gate "brian" "ws-1"] [:halt "t0"] [:breaker "brian" "triage"]}
           (set (keys its))))
    (is (= "BR-1 · Fix it" (:message (its [:gate "brian" "ws-1"]))))
    (is (= "brian · in-progress" (:subtitle (its [:gate "brian" "ws-1"]))))
    (is (= "auto-halt: fail-rate" (:message (its [:halt "t0"]))))
    (is (= "triage tripped after 3 consecutive failures"
           (:message (its [:breaker "brian" "triage"]))))))

(deftest first-look-summarises-instead-of-replaying
  (testing "nothing waiting — nothing posted"
    (is (= [] (attention/arrivals nil {}))))
  (testing "something waiting — one summary"
    (let [current (attention/items {:gates [gate (assoc gate :ws-id "ws-2")]})]
      (is (= ["2 things need your attention"]
             (map :message (attention/arrivals nil current)))))))

(deftest only-new-keys-are-announced
  (let [current (attention/items {:gates [gate (assoc gate :ws-id "ws-2" :label "new one")]})]
    (is (= ["new one"]
           (map :message (attention/arrivals #{[:gate "brian" "ws-1"]} current))))
    (is (= [] (attention/arrivals (set (keys current)) current)))))

(deftest a-gate-that-leaves-and-returns-is-announced-again
  (let [current (attention/items {:gates [gate]})]
    (is (= 1 (count (attention/arrivals #{} current))))))

(deftest a-burst-is-coalesced
  (let [ns (mapv #(hash-map :message (str "g" %) :subtitle "brian") (range 10))
        out (attention/coalesce ns)]
    (is (= 4 (count out)))
    (is (= "and 7 more — brian" (:message (last out)))))
  (testing "up to limit+1 passes through untouched"
    (let [ns (mapv #(hash-map :message (str "g" %)) (range 4))]
      (is (= ns (attention/coalesce ns))))))
