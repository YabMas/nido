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

(defn- refusal* [s c] (:reason (nth (d/step s c) 2)))

(deftest an-outcome-wakes-the-conductor-to-hear-the-whole-inbox
  (let [s (state-after [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
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
  (let [s (state-after (concat [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                                {:command :start-turn :turn "t" :unit "u" :spec spec}
                                {:command :record-outcome :turn "t" :outcome :completed}]
                               (mapcat (fn [i] (let [k (str "k" i)]
                                                 [{:command :start-turn :turn k :unit nil :spec spec :hears :inbox}
                                                  {:command :record-outcome :turn k :outcome :failed}]))
                                       (range 3))))]
    (is (= 3 (:conductor-failures s)))
    (is (seq (:inbox s)) "what they heard is back in the inbox")
    (is (= [:raise-conductor-stop] (map (comp :command :command) (b/decisions opts "ws" s 9)))
        "nothing wakes the conductor again: the person is asked instead")))

(deftest a-unit-owes-its-session-before-its-first-turn
  (let [s (state-after [{:command :open-unit :unit "u" :project "p" :goal "g"}])
        [attempt] (b/decisions opts "ws" s 2)]
    (is (= {:command :record-provision-attempt :unit "u" :want :up :attempt "u up 2"} (:command attempt)))
    (is (= :session-not-ready (refusal* s {:command :start-turn :turn "t" :unit "u" :spec spec})))
    (let [s2 (state-after-from s [(:command attempt)
                                  {:command :record-provision-outcome :unit "u" :attempt "u up 2" :outcome :done :cwd "/wt"}])
          [s3 [e]] (d/step s2 {:command :start-turn :turn "t" :unit "u" :spec (assoc spec :cwd "/elsewhere")})]
      (is (empty? (b/decisions opts "ws" s2 3)) "nothing more is owed")
      (is (= "/wt" (get-in e [:spec :cwd])) "a unit turn runs in its session, whatever it was told")
      (testing "a merge owes a stop, and a close a destroy"
        (let [s4 (state-after-from s3 [{:command :record-outcome :turn "t" :outcome :completed}
                                       {:command :record-merge :unit "u"}])]
          (is (= [{:unit "u" :want :stopped}] (d/sessions-owed s4)))
          (is (= [{:unit "u" :want :destroyed}]
                 (d/sessions-owed (state-after-from s4 [{:command :close-workstream}])))))))))

(deftest a-stopped-conductor-asks-the-person-and-their-answer-wakes-it
  (let [s (state-after (concat [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                                {:command :start-turn :turn "t" :unit "u" :spec spec}
                                {:command :record-outcome :turn "t" :outcome :completed}]
                               (mapcat (fn [i] (let [k (str "k" i)]
                                                 [{:command :start-turn :turn k :unit nil :spec spec :hears :inbox}
                                                  {:command :record-outcome :turn k :outcome :failed}]))
                                       (range 3))))
        [stop] (b/decisions opts "ws" s 9)
        s2 (state-after-from s [(:command stop) {:command :post-chat :message "m" :text "hello?"}])]
    (is (= :raise-conductor-stop (get-in stop [:command :command])))
    (is (= 1 (count (d/awaiting-person s2))))
    (is (empty? (b/decisions opts "ws" s2 10)) "a person's input does not pass the stop")
    (let [s3 (state-after-from s2 [{:command :answer-operator-ask :ask (get-in stop [:command :ask]) :answer "wake" :by :person}])]
      (is (= [:start-turn] (map (comp :command :command) (b/decisions opts "ws" s3 11)))))))

(deftest background-wakes-are-capped-and-a-person-s-input-is-not
  (let [handed (state-after [{:command :hand-over :summary "x"}])
        chatted (state-after [{:command :post-chat :message "m" :text "hi"}])
        states {"a" {:state handed :pos 1} "b" {:state handed :pos 2} "c" {:state handed :pos 3}
                "p" {:state chatted :pos 4}}
        ws (map :ws (b/wakes (assoc opts :wake-cap 2) states ["a" "b" "c" "p"]))]
    (is (= ["p" "a" "b"] ws))
    (testing "running background turns count against the cap"
      (let [running (state-after-from handed [{:command :start-turn :turn "k" :unit nil :spec spec :hears :inbox}
                                              {:command :hand-over :summary "y"}])]
        (is (= ["p"] (map :ws (b/wakes (assoc opts :wake-cap 1) (assoc states "a" {:state running :pos 5}) ["a" "b" "c" "p"]))))))))
