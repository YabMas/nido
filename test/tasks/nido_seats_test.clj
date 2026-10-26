(ns tasks.nido-seats-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.conductor :as conductor]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.seats :as rseats]
   [tasks.nido-seats :as nido-seats]))

(defn- addressed
  "What each verb addresses, the current generation 1 with `current` seats."
  [current f]
  (let [sent (atom [])]
    (with-redefs [rseats/seats        (fn [_ _] current)
                  generation/current  (fn [_] 1)
                  seats/send!         (fn [_ _ msg] (swap! sent conj (:to msg)))
                  seats/record!       (fn [_ _ e] (swap! sent conj (:to e)))
                  conductor/decide!   (fn [_ _ {[_ is] :decision}] (swap! sent into (map :seat is)))]
      (f))
    @sent))

(deftest the-whole-seat-is-addressed-by-its-number-in-every-verb
  (is (= [1 1 "canvas.strata/seats" :conductor]
         (addressed [{:id 1 :whole true}]
                    #(do (nido-seats/send* {:project "brian" :ws-id "w" :to "1" :text "x"})
                         (nido-seats/record* {:project "brian" :ws-id "w" :kind "tension" :to "1" :about "a" :statement "s"})
                         (nido-seats/send* {:project "brian" :ws-id "w" :to "canvas.strata/seats" :text "x"})
                         (nido-seats/send* {:project "brian" :ws-id "w" :to "conductor" :text "x"}))))))

(deftest an-element-whose-id-reads-as-a-number-keeps-its-string-id
  (is (= ["42" "42" "42" "42" 1]
         (addressed [{:id "42"} {:id "canvas.strata/seats"}]
                    #(do (nido-seats/send* {:project "brian" :ws-id "w" :to "42" :text "x"})
                         (nido-seats/record* {:project "brian" :ws-id "w" :kind "tension" :to "42" :about "a" :statement "s"})
                         (nido-seats/decide* {:project "brian" :ws-id "w" :hand-back "h" :seat "42" :text "go"})
                         (nido-seats/decide* {:project "brian" :ws-id "w" :hand-back "h" :instruct "[{:seat \"42\" :text \"go\"}]"})
                         ;; the generation's own number, once its ids are fixed, is still its whole seat — the conductor's
                         (nido-seats/send* {:project "brian" :ws-id "w" :to "1" :text "x"}))))))
