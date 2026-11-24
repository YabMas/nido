(ns nido.coordinator.work-core-test
  "What a surface shows as awaiting the person is exactly the open operator asks the core holds,
   and what a person does at a gate is their keyed command to the core."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.workstream :as workstream]
   [nido.coordinator.work :as work]
   [nido.platform.core :as core]
   [nido.platform.project :as project]
   [nido.test.fake-core :as fake-core]))

(defn- with-ws
  "Call `(f core ws-id)` with a fresh nido home holding one brian workstream, and a fake core."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    project/list-projects (fn [] {:brian {:directory (str tmp)}})]
        (cstate/ensure-dirs!)
        (let [w (workstream/create! :brian {:external-refs []})]
          (workstream/append-entry! :brian (:id w) {:kind :scratch} (pr-str {:format :scratch}))
          (fake-core/with-fake-core #(f % (:id w)))))
      (finally (fs/delete-tree tmp)))))

(defn- core-state [c ws-id] (get @(:states c) (str "brian/" ws-id)))

(deftest an-open-ask-is-the-gate-and-nothing-else-is
  (with-ws
    (fn [c ws-id]
      (testing "a blocker on the ledger awaits no one: only the core decides what is asked"
        (workstream/append-entry! :brian ws-id {:kind :blocker}
                                  (pr-str {:format :blocker :summary "stuck" :needs "what now?" :after "the round resumes"}))
        (is (empty? (work/gates :brian)))
        (is (not (:needs-you (first (work/list-workstreams :brian #{}))))))
      (fake-core/seed! c :brian ws-id fake-core/asking)
      (let [[g & more] (work/gates :brian)]
        (is (nil? more))
        (is (= ws-id (:ws-id g)))
        (is (= "a1" (:core-ask g)))
        (is (= "Ship it?" (get-in g [:ask :question])))
        (is (= [["A" "Yes" :option-a] ["B" "No" :option-b]]
               (map (juxt :letter :label :action) (get-in g [:ask :questions 0 :options]))))
        (is (= [:option-a :option-b :reply :withdraw] (map :id (:actions g)))))
      (is (:needs-you (first (work/list-workstreams :brian #{})))))))

(deftest a-letter-answers-the-ask-with-its-option
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id fake-core/asking)
      (is (= {:decision :answered} (work/resolve-gate! :brian ws-id :option-a nil)))
      (is (= {:answered "Yes"} (get-in (core-state c ws-id) [:turns "c1" :resolution])))
      (is (every? :input? @(:sent c)) "a person's act is input, taken ahead of background commands")
      (is (empty? (work/gates :brian)) "answered, it awaits no one")
      (is (= :core-refused (:decision (work/resolve-gate! :brian ws-id :option-b nil)))
          "a second answer finds nothing asked"))))

(deftest a-reply-answers-in-words-and-a-withdrawal-takes-the-ask-back
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id fake-core/asking)
      (is (= {:decision :withdrawn} (work/resolve-gate! :brian ws-id :withdraw nil)))
      (is (= :withdrawn (get-in (core-state c ws-id) [:operator-asks "a1" :status])))))
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id fake-core/asking)
      (is (= {:decision :answered} (work/resolve-gate! :brian ws-id :reply "  Only after the demo ")))
      (is (= {:answered "Only after the demo"} (get-in (core-state c ws-id) [:turns "c1" :resolution]))))))

(deftest land-grants-promote-opens-done-closes-pause-holds
  (with-ws
    (fn [c ws-id]
      (testing "promote is the person's start: the workstream's unit opened"
        (is (= {:decision :promoted} (work/set-stage! :brian ws-id :in-progress)))
        (is (= :open (get-in (core-state c ws-id) [:units ws-id :status]))))
      (testing "land grants the landing"
        (is (= {:decision :granted} (work/resolve-gate! :brian ws-id :land nil)))
        (is (contains? (:grants (core-state c ws-id)) :merge)))
      (testing "pause holds each open unit, and resume releases it"
        (work/pause! :brian ws-id "later")
        (is (get-in (core-state c ws-id) [:units ws-id :held?]))
        (is (get-in (first (work/list-workstreams :brian #{})) [:status :paused]))
        (work/resume! :brian ws-id)
        (is (not (get-in (core-state c ws-id) [:units ws-id :held?]))))
      (testing "done retracts the open unit and closes the workstream, in the core only"
        (is (= {:decision :done} (work/set-stage! :brian ws-id :done)))
        (is (:closed? (core-state c ws-id)))
        (is (nil? (workstream/closure (workstream/read-ws :brian ws-id))))
        (is (= :settled (:engagement (first (work/list-workstreams :brian #{})))))))))

(deftest a-persons-word-answers-what-is-asked-or-reaches-the-conductor
  (with-ws
    (fn [c ws-id]
      (is (= {:answered nil :posted true :granted [] :refused []}
             (work/person-says! :brian ws-id "how is it going?" [])))
      (is (= ["how is it going?"] (map :text (:chat (core-state c ws-id)))))
      (fake-core/seed! c :brian ws-id (rest fake-core/asking))
      (is (= {:answered "a1" :posted false :granted [:merge] :refused []}
             (work/person-says! :brian ws-id "yes" [:merge]))))))

(deftest activation-hands-a-session-to-the-core-as-its-unit
  (with-ws
    (fn [c ws-id]
      (let [r (work/activate! :brian ws-id "s1" {:cwd "/wt/s1"})]
        (is (false? (:already? r)))
        (is (= {:status :ready :cwd "/wt/s1"}
               (select-keys (get-in (core-state c ws-id) [:units ws-id :session]) [:status :cwd]))))
      (is (:already? (work/activate! :brian ws-id "s1" {:cwd "/wt/s1"})))
      (is (:was? (work/deactivate! :brian ws-id nil)))
      (is (get-in (core-state c ws-id) [:units ws-id :held?])))))

(deftest a-workstream-the-core-works-unrecorded-reads-as-its-core-state
  (with-ws
    (fn [c ws-id]
      (let [row #(first (work/list-workstreams :brian #{}))]
        (is (= :intake (get-in (row) [:position :at])) "nothing done anywhere: not started")
        (fake-core/seed! c :brian ws-id fake-core/asking)
        (is (= {:at :core-asking :next {:stage :answer-ask :mode :human}} (:position (row)))
            "the ledger records none of it, and the core asks the person")
        (work/resolve-gate! :brian ws-id :option-a nil)
        (fake-core/seed! c :brian ws-id
                         [{:command :start-turn :turn "u1" :unit "u" :spec {:executor :fake :instructions "i"}}])
        (is (= :core-under-way (get-in (row) [:position :at])) "a unit's turn ran: under way")))))

(deftest a-workstream-the-core-moves-is-never-read-as-stalled
  ;; Status reads liveness from claims, Runs and a person's sessions, and a core turn is none of
  ;; them; while the core has the move, the row must not carry status's stall.
  (with-ws
    (fn [c ws-id]
      (let [status pipeline/status
            row    #(first (work/list-workstreams :brian #{}))]
        (with-redefs [pipeline/status (fn [& args]
                                        (assoc (apply status args)
                                               :owed-by :agent :live false :stalled true
                                               :why "nothing runs"))]
          (is (get-in (row) [:status :stalled]) "the core holds nothing: status's stall stands")
          (fake-core/seed! c :brian ws-id
                           [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}
                            {:command :start-turn :turn "u1" :unit "u" :spec {:executor :fake :instructions "i"}}])
          (testing "a turn the core runs is live, not stalled"
            (is (= {:owed-by :agent :live true :stalled false}
                   (select-keys (:status (row)) [:owed-by :live :stalled :why]))))
          (fake-core/seed! c :brian ws-id
                           [{:command :record-outcome :turn "u1" :run 1 :outcome :completed}
                            {:command :start-turn :turn "c1" :unit nil :spec {:executor :fake :instructions "i"}}
                            {:command :record-outcome :turn "c1" :run 1 :outcome :asked
                             :call {:id "call-1" :question "Ship it?" :explored "x" :options ["Yes" "No"]
                                    :recommendation "Yes"}}
                            {:command :raise-operator-ask :ask "a1" :binds #{"c1"}
                             :content {:question "Ship it?" :explored "x" :options ["Yes" "No"]
                                       :recommendation "Yes"}}])
          (testing "an ask the core holds owes the person, and nothing is stalled"
            (is (= {:owed-by :person :live false :stalled false}
                   (select-keys (:status (row)) [:owed-by :live :stalled :why])))))))))
