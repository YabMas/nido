(ns nido.core.buffer-test
  (:require [clojure.test :refer [deftest is testing]]
            [nido.core.buffer :as b]
            [nido.core.domain :as d]))

(def spec {:executor :fake :instructions "i"})
(def opts {:spec-fn (fn [_ws turn _state items] {:executor :fake :instructions (str turn " hears " (count items))})
           :max-failures 3})

(defn- state-after-from [s commands]
  (reduce (fn [s c]
            (let [c (cond-> c (= :inbox (:hears c)) (assoc :hears (vec (keys (:inbox s)))))
                  [s2 _ r] (d/step s c)]
              (assert (nil? r) (pr-str r c))
              s2))
          s commands))

(defn- state-after [commands] (state-after-from d/empty-state commands))

(deftest an-outcome-wakes-the-conductor-to-hear-the-whole-inbox
  (let [s (state-after [{:command :open-unit :unit "u" :project "p" :goal "g"}
                        {:command :start-turn :turn "t" :unit "u" :spec spec}
                        {:command :record-outcome :turn "t" :outcome :asked :call {:id "c"}}])
        [wake & more] (b/decisions opts "ws" s 7)]
    (is (nil? more))
    (is (= {:key "buffer/wake/conductor-7" :ws "ws"
            :command {:command :start-turn :turn "conductor-7" :unit nil :hears (vec (keys (:inbox s)))
                      :spec {:executor :fake :instructions "conductor-7 hears 1"}}}
           wake))
    (testing "nothing more while a conductor turn runs"
      (is (empty? (b/decisions opts "ws" (first (d/step s (:command wake))) 8))))
    (testing "the same state seen twice issues the same keyed command"
      (is (= [wake] (b/decisions opts "ws" s 7))))))

(deftest a-conductor-s-ask-goes-to-the-person-and-its-answer-resumes-it
  (let [s (state-after [{:command :start-turn :turn "k" :unit nil :spec spec}
                        {:command :record-outcome :turn "k" :outcome :asked
                         :call {:id "c" :question "open unit b?" :explored "x" :options ["yes" "no"] :recommendation "yes"}}])
        [raise] (b/decisions opts "ws" s 3)]
    (is (= {:command :raise-operator-ask :ask "ask k c" :binds ["k"] :concerns nil
            :content {:question "open unit b?" :explored "x" :options ["yes" "no"] :recommendation "yes"}}
           (:command raise)))
    (let [s2 (state-after-from s [(:command raise) {:command :answer-operator-ask :ask "ask k c" :answer "yes"}])]
      (is (= [{:command :resume-turn :turn "k"}] (map :command (b/decisions opts "ws" s2 5)))))))

(deftest failing-conductor-turns-stop-the-waking
  (let [s (state-after (concat [{:command :open-unit :unit "u" :project "p" :goal "g"}
                                {:command :start-turn :turn "t" :unit "u" :spec spec}
                                {:command :record-outcome :turn "t" :outcome :completed}]
                               (mapcat (fn [i] (let [k (str "k" i)]
                                                 [{:command :start-turn :turn k :unit nil :spec spec :hears :inbox}
                                                  {:command :record-outcome :turn k :outcome :failed}]))
                                       (range 3))))]
    (is (= 3 (:conductor-failures s)))
    (is (seq (:inbox s)) "what they heard is back in the inbox")
    (is (empty? (b/decisions opts "ws" s 9)) "but nothing wakes the conductor again")))
