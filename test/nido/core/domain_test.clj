(ns nido.core.domain-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [nido.core.domain :as d]
            [nido.test.sessions :as sessions]))

(def ready {:question "Go?" :explored "tried A and B" :options [{:option "A" :consequence "x"}] :recommendation "A"})
(def spec {:executor :fake :instructions "do the thing"})

(defn resolve-command
  "A generated command as it is sent against `state`: a conductor turn's :whole-inbox becomes the
   inbox's items."
  [state c]
  (cond-> c (= :whole-inbox (:hears c)) (assoc :hears (vec (keys (:inbox state))))))


(defn- run
  "Apply `commands` in order, asserting each is admitted; the resulting state and every event."
  [commands]
  (reduce (fn [[state events] c]
            (let [c (resolve-command state c)
              [s es refused] (d/step state c)]
              (is (nil? refused) (str "refused " (pr-str c) " -> " (pr-str refused)))
              [s (into events es)]))
          [d/empty-state []] (sessions/expand commands)))

(defn- steps
  "Apply `commands` to `state` in order, `sessions/expand`ed, asserting each is admitted; the state."
  [state commands]
  (reduce (fn [s c] (let [[s2 _ r] (d/step s c)] (is (nil? r) (pr-str c r)) s2))
          state (sessions/expand commands)))

(defn- refusal [state command] (:reason (nth (d/step state command) 2)))

(def two-units
  [{:command :open-unit :unit "brian" :project "brian" :goal "g1" :session-cwd "/w"}
   {:command :open-unit :unit "babel" :project "babel" :goal "g2" :session-cwd "/w"}])

(deftest an-ask-halts-the-unit
  (let [[s] (run (conj two-units
                       {:command :start-turn :turn "t1" :unit "brian" :spec spec}
                       {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c1" :question "Go?"}}))]
    (testing "an asked turn is active, so no further turn starts on its unit"
      (is (= :unit-has-active-turn (refusal s {:command :start-turn :turn "t2" :unit "brian" :spec spec}))))
    (testing "a sibling unit is unaffected"
      (is (nil? (refusal s {:command :start-turn :turn "t3" :unit "babel" :spec spec}))))
    (testing "an asked turn reports nothing further"
      (is (= :turn-not-running (refusal s {:command :record-outcome :turn "t1" :outcome :completed}))))))

(deftest the-buffer-disposes-of-a-unit-ask-in-exactly-one-way
  (let [[s] (run (conj two-units
                       {:command :start-turn :turn "t1" :unit "brian" :spec spec}
                       {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c1" :question "Go?"}}))
        [s2] (d/step s {:command :answer-call :turn "t1" :answer "yes"})]
    (is (= :resolved (get-in s2 [:turns "t1" :status])) "answering records the resolution")
    (is (= {:answered "yes"} (get-in s2 [:turns "t1" :resolution])))
    (is (= :turn-not-asked (refusal s2 {:command :answer-call :turn "t1" :answer "again"}))
        "a call is resolved once")
    (is (= :unit-has-active-turn (refusal s2 {:command :start-turn :turn "t2" :unit "brian" :spec spec}))
        "a resolved turn still holds its unit")
    (let [[s3 events] (d/step s2 {:command :resume-turn :turn "t1"})]
      (is (= [{:event :turn-resumed :turn "t1"}] events) "resuming is recorded before it is attempted")
      (is (= :running (get-in s3 [:turns "t1" :status]))))))

(deftest a-resolved-conductor-turn-waits-for-the-conductor
  (let [[s] (run [{:command :start-turn :turn "k1" :unit nil :spec spec}
                  {:command :record-outcome :turn "k1" :outcome :asked :call {:id "c" :question "Go?"}}
                  {:command :raise-operator-ask :ask "a1" :binds ["k1"] :content ready}
                  {:command :start-turn :turn "k2" :unit nil :spec spec}
                  {:command :answer-operator-ask :ask "a1" :answer "x"}])]
    (is (= :resolved (get-in s [:turns "k1" :status])) "the person's answer is never refused")
    (is (= :conductor-turn-running (refusal s {:command :resume-turn :turn "k1"})))
    (let [[s2] (d/step s {:command :record-outcome :turn "k2" :outcome :completed})]
      (is (nil? (refusal s2 {:command :resume-turn :turn "k1"}))))))

(deftest a-conductor-ask-goes-to-the-person
  (let [[s] (run [{:command :start-turn :turn "k1" :unit nil :spec spec}
                  {:command :record-outcome :turn "k1" :outcome :asked :call {:id "c" :question "Go?"}}])]
    (is (= :conductor-ask-goes-to-the-person (refusal s {:command :answer-call :turn "k1" :answer "x"})))
    (is (= :conductor-ask-goes-to-the-person (refusal s {:command :return-call :turn "k1" :instructions "x"})))
    (testing "an asked conductor turn does not count against the conductor's one running turn"
      (is (nil? (refusal s {:command :start-turn :turn "k2" :unit nil :spec spec}))))
    (let [[s2] (d/step s {:command :start-turn :turn "k2" :unit nil :spec spec})]
      (is (= :conductor-turn-running (refusal s2 {:command :start-turn :turn "k3" :unit nil :spec spec}))))))

(deftest operator-asks-live-with-their-calls
  (let [[s] (run (conj two-units
                       {:command :start-turn :turn "t1" :unit "brian" :spec spec}
                       {:command :start-turn :turn "t2" :unit "babel" :spec spec}
                       {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c1" :question "Go?"}}
                       {:command :record-outcome :turn "t2" :outcome :asked :call {:id "c2" :question "Go?"}}
                       {:command :raise-operator-ask :ask "a1" :binds ["t1" "t2"] :content ready}))]
    (testing "a bound call is the person's, not the buffer's"
      (is (= :call-bound-by-operator-ask (refusal s {:command :answer-call :turn "t1" :answer "x"}))))
    (testing "answering resolves every bound call in one commit"
      (let [[s2] (d/step s {:command :answer-operator-ask :ask "a1" :answer "go"})]
        (is (= :answered (get-in s2 [:operator-asks "a1" :status])))
        (is (= #{:resolved} (set (map #(get-in s2 [:turns % :status]) ["t1" "t2"]))))
        (is (empty? (d/violations s2)))))
    (testing "withdrawing resolves every bound call with a refusal and its reason"
      (let [[s2] (d/step s {:command :withdraw-operator-ask :ask "a1" :reason "premise moved"})]
        (is (= {:refused "premise moved"} (get-in s2 [:turns "t2" :resolution])))))
    (testing "an ask binds at least one call, and is decision-ready"
      (is (= :ask-binds-no-call (refusal s {:command :raise-operator-ask :ask "a2" :binds [] :content ready})))
      (let [[s3] (run (conj two-units
                            {:command :start-turn :turn "t9" :unit "brian" :spec spec}
                            {:command :record-outcome :turn "t9" :outcome :asked :call {:id "c" :question "Go?"}}))]
        (is (nil? (refusal s3 {:command :raise-operator-ask :ask "a3" :binds ["t9"]
                               :content (dissoc ready :recommendation)}))
            "a recommendation is optional")
        (is (= :ask-not-decision-ready
               (refusal s3 {:command :raise-operator-ask :ask "a3" :binds ["t9"]
                            :content (dissoc ready :explored)}))
            "what the answer depends on is not")
        (testing "several decisions are ready as questions of their own, each with a question"
          (let [several (-> ready (dissoc :options)
                            (assoc :questions [{:question "a?" :options [{:option "x"}]} {:question "b?"}]))]
            (is (nil? (refusal s3 {:command :raise-operator-ask :ask "a3" :binds ["t9"] :content several})))
            (is (= :ask-not-decision-ready
                   (refusal s3 {:command :raise-operator-ask :ask "a3" :binds ["t9"]
                                :content (assoc several :questions [{:question " "}])})))))))))

(deftest a-malformed-ask-never-reaches-the-person
  (let [long-q (str (apply str (repeat 240 "x")) "?")]
    (testing "ask-problems names each field whose question breaks the form, and none when all hold"
      (is (= [] (d/ask-problems ready)))
      (is (= [] (d/ask-problems (assoc ready :question (str (apply str (repeat 239 "x")) "?")))) "240 is the cap, inclusive")
      (is (re-find #"^`question` is 241 characters" (first (d/ask-problems (assoc ready :question long-q)))))
      (is (re-find #"^`question` is more than one line" (first (d/ask-problems (assoc ready :question "The loop stopped.\nGo?")))))
      (is (re-find #"^`question` does not end in '\?'" (first (d/ask-problems (assoc ready :question "Pick one")))))
      (is (re-find #"^`question` is missing" (first (d/ask-problems (dissoc ready :question)))))
      (is (= 1 (count (d/ask-problems {:question "Two?" :questions [{:question "A?"} {:question "b"}]}))))
      (is (re-find #"^`questions` entry 2" (first (d/ask-problems {:question "Two?" :questions [{:question "A?"} {:question "b"}]})))))
    (let [[s] (run (conj two-units {:command :start-turn :turn "t1" :unit "brian" :spec spec}))]
      (testing "a turn reporting an ask whose question breaks the form ended :failed, its problems in the detail"
        (let [[s2 es] (d/step s {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c" :question long-q}})]
          (is (= :failed (get-in s2 [:turns "t1" :status])))
          (is (= :ask-malformed (get-in (first es) [:detail :reason])))
          (is (re-find #"`question`" (first (get-in (first es) [:detail :problems]))))))
      (testing "a well-formed one with no recommendation asks"
        (let [[s2] (d/step s {:command :record-outcome :turn "t1" :outcome :asked
                              :call {:id "c" :question "Go?" :explored "x" :options [{:option "y"}]}})]
          (is (= :asked (get-in s2 [:turns "t1" :status]))))))
    (let [[s] (run (conj two-units
                         {:command :start-turn :turn "t1" :unit "brian" :spec spec}
                         {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c" :question "Go?"}}))
          raised (fn [content] (nth (d/step s {:command :raise-operator-ask :ask "a" :binds ["t1"] :content content}) 2))]
      (testing "decide refuses a malformed operator ask, naming each field under :problems"
        (let [r (raised (-> ready (assoc :question long-q) (dissoc :explored :options)))]
          (is (= :ask-not-decision-ready (:reason r)))
          (is (= 3 (count (:problems r))))
          (is (some #(str/starts-with? % "`question`") (:problems r)))
          (is (some #(str/starts-with? % "`explored`") (:problems r)))
          (is (some #(str/includes? % "`options`") (:problems r)))))
      (testing "and admits a well-formed one without a recommendation"
        (is (nil? (raised (dissoc ready :recommendation))))))
    (testing "decide refuses a malformed conductor stop the same way"
      (let [r (nth (d/step d/empty-state {:command :raise-conductor-stop :ask "s" :content (assoc ready :question "Wake it")}) 2)]
        (is (= :ask-not-decision-ready (:reason r)))
        (is (re-find #"^`question`" (first (:problems r))))))))

(deftest an-ask-about-a-unit-holds-it
  (let [[s] (run (conj two-units
                       {:command :start-turn :turn "k1" :unit nil :spec spec}
                       {:command :record-outcome :turn "k1" :outcome :asked :call {:id "c" :question "Go?"}}
                       {:command :raise-operator-ask :ask "a1" :binds ["k1"] :concerns "babel" :content ready}))]
    (is (= :unit-has-open-operator-ask (refusal s {:command :start-turn :turn "t1" :unit "babel" :spec spec})))
    (is (nil? (refusal s {:command :start-turn :turn "t1" :unit "brian" :spec spec})))))

(deftest a-hold-takes-the-unit-over-in-one-commit
  (let [[s] (run (conj two-units
                       {:command :start-turn :turn "t1" :unit "brian" :spec spec}
                       {:command :start-turn :turn "t2" :unit "babel" :spec spec}
                       {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c1" :question "Go?"}}
                       {:command :record-outcome :turn "t2" :outcome :asked :call {:id "c2" :question "Go?"}}
                       {:command :raise-operator-ask :ask "a1" :binds ["t1" "t2"] :content ready}))
        [s2 events] (d/step s {:command :hold-unit :unit "brian"})]
    (is (= [{:event :unit-held :unit "brian"}] events) "the event records the input alone")
    (is (= :cancelled (get-in s2 [:turns "t1" :status])))
    (is (= :takeover (get-in s2 [:turns "t1" :ended-by])))
    (is (= :withdrawn (get-in s2 [:operator-asks "a1" :status])))
    (is (= {:refused :taken-over} (get-in s2 [:turns "t2" :resolution]))
        "the sibling's call is resolved with a refusal, and the sibling runs on")
    (is (= :resolved (get-in s2 [:turns "t2" :status])))
    (is (= :unit-held (refusal s2 {:command :start-turn :turn "t3" :unit "brian" :spec spec})))
    (is (= :turn-not-running (refusal s2 {:command :record-outcome :turn "t1" :outcome :completed}))
        "a late report from the cancelled turn is discarded")
    (is (empty? (d/violations s2)))))

(deftest unknowns-stop-a-unit-until-settled
  (let [[s] (run (conj two-units {:command :record-unknown :unit "brian" :key "push-1"}))]
    (is (= :unit-has-unknowns (refusal s {:command :start-turn :turn "t1" :unit "brian" :spec spec})))
    (let [[s2] (d/step s {:command :settle-unknown :unit "brian" :key "push-1" :how :observed})]
      (is (nil? (refusal s2 {:command :start-turn :turn "t1" :unit "brian" :spec spec}))))))

(deftest landings-follow-the-order
  (let [[s] (run (conj two-units {:command :set-landing-order :pairs [["babel" "brian"]]}))]
    (is (= {:reason :lands-after-unmerged :unit "brian" :waits-on ["babel"]}
           (nth (d/step s {:command :begin-landing :unit "brian"}) 2))
        "the refusal names the unit it waits on")
    (is (= :units-not-landed (refusal s {:command :close-workstream})))
    (let [[s2] (run (into (vec two-units)
                          [{:command :set-landing-order :pairs [["babel" "brian"]]}
                           {:command :record-merge :unit "babel"}
                           {:command :begin-landing :unit "brian"}
                           {:command :record-merge :unit "brian"}
                           {:command :close-workstream}]))]
      (is (:closed? s2)))
    (is (= :order-has-a-cycle
           (refusal s {:command :set-landing-order :pairs [["babel" "brian"] ["brian" "babel"]]})))))

;; ── generated command sequences ──────────────────────────────────────────────

(def ^:private units ["u1" "u2"])
(def ^:private turn-ids (mapv #(str "t" %) (range 8)))
(def ^:private ask-ids ["a1" "a2" "a3"])

(def gen-command
  (let [unit  (gen/elements units)
        mturn (gen/elements turn-ids)
        ask   (gen/elements ask-ids)]
    (gen/one-of
     [(gen/fmap (fn [[t u]] (cond-> {:command :start-turn :turn t :unit u :spec spec}
                               (nil? u) (assoc :hears :whole-inbox)))
                (gen/tuple mturn (gen/one-of [unit (gen/return nil)])))
      (gen/fmap (fn [[t o r u?]] (cond-> {:command :record-outcome :turn t :outcome o :call {:id (str "c-" t)}}
                                   r  (assoc :run r)
                                   u? (assoc :actions-unknown? true)))
                (gen/tuple mturn (gen/elements [:asked :completed :failed :cancelled])
                           (gen/elements [nil 1 2]) gen/boolean))
      (gen/fmap (fn [t] {:command :answer-call :turn t :answer "a"}) mturn)
      (gen/fmap (fn [t] {:command :resume-turn :turn t}) mturn)
      (gen/fmap (fn [t] {:command :return-call :turn t :instructions "i"}) mturn)
      (gen/fmap (fn [[a ts c]] {:command :raise-operator-ask :ask a :binds (vec (distinct ts))
                                :concerns c :content ready})
                (gen/tuple ask (gen/vector mturn 1 2) (gen/one-of [unit (gen/return nil)])))
      (gen/fmap (fn [a] {:command :answer-operator-ask :ask a :answer "x"}) ask)
      (gen/fmap (fn [a] {:command :withdraw-operator-ask :ask a :reason "r"}) ask)
      (gen/fmap (fn [u] {:command :hold-unit :unit u}) unit)
      (gen/fmap (fn [u] {:command :release-unit :unit u}) unit)
      (gen/fmap (fn [u] {:command :record-unknown :unit u :key "k"}) unit)
      (gen/fmap (fn [u] {:command :settle-unknown :unit u :key "k" :how :observed}) unit)
      (gen/fmap (fn [p] {:command :set-landing-order :pairs [p]}) (gen/elements [["u1" "u2"] ["u2" "u1"]]))
      (gen/fmap (fn [u] {:command :begin-landing :unit u}) unit)
      (gen/fmap (fn [u] {:command :record-merge :unit u}) unit)
      (gen/return {:command :close-workstream})
      (gen/return {:command :reopen-workstream})
      (gen/fmap (fn [u] {:command :open-unit :unit u :project u :goal "g"}) unit)
      (gen/fmap (fn [[u w]] {:command :record-provision-attempt :unit u :attempt (str "p-" u) :want w})
                (gen/tuple unit (gen/elements [:up :stopped :destroyed])))
      (gen/fmap (fn [[u o]] (cond-> {:command :record-provision-outcome :unit u :attempt (str "p-" u) :outcome o}
                                   (= :done o) (assoc :cwd "/w")))
                (gen/tuple unit (gen/elements [:done :failed :refused])))
      (gen/fmap (fn [u] {:command :retry-provision :unit u}) unit)
      (gen/fmap (fn [g] {:command :give-grant :grant g :by "p"}) (gen/elements [:merge :intent]))
      (gen/fmap (fn [g] {:command :spend-grant :grant g}) (gen/elements [:merge :intent]))
      (gen/fmap (fn [m] {:command :post-chat :message m :text "hi"}) (gen/elements ["m1" "m2"]))
      (gen/fmap (fn [n] {:command :post-notice :notice n :text "census"}) (gen/elements ["n1" "n2" "m1"]))
      (gen/fmap (fn [[m t]] {:command :reply-chat :message m :text "yo" :turn t}) (gen/tuple (gen/elements ["r1" "r2"]) mturn))
      (gen/return {:command :hand-over :summary "s"})
      (gen/fmap (fn [a] {:command :answer-operator-ask :ask a :answer "x" :by :person}) ask)
      (gen/fmap (fn [a] {:command :raise-conductor-stop :ask a :content ready}) ask)])))

(def no-admitted-command-breaks-an-invariant
  (prop/for-all [commands (gen/vector gen-command 0 60)]
    (loop [state d/empty-state, events [], [c & more] commands]
      (if (nil? c)
        (= state (d/fold events))
        (let [c (resolve-command state c)
              [s es refused] (d/step state c)]
          (cond
            refused         (recur state events more)
            (seq (d/violations s)) false
            :else           (recur s (into events es) more)))))))

(deftest generated-sequences-stay-legal-and-equal-their-fold
  (let [result (tc/quick-check 400 no-admitted-command-breaks-an-invariant)]
    (is (:pass? result) (pr-str (select-keys result [:shrunk :fail])))))

(deftest an-outcome-names-its-run-and-an-unknown-run-stops-the-unit
  (let [[s] (run [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :start-turn :turn "t" :unit "u" :spec spec}
                  {:command :record-outcome :turn "t" :run 1 :outcome :asked :call {:id "c" :question "Go?"}}
                  {:command :answer-call :turn "t" :answer "yes"}
                  {:command :resume-turn :turn "t"}])]
    (is (= 2 (get-in s [:turns "t" :run])) "a resumption is the turn's next run")
    (is (= :stale-run (refusal s {:command :record-outcome :turn "t" :run 1 :outcome :completed}))
        "a report from the earlier run is refused")
    (let [[s2] (d/step s {:command :record-outcome :turn "t" :run 2 :outcome :failed
                          :detail {:reason :ended-without-outcome} :actions-unknown? true})]
      (is (= #{(d/run-unknown-key "t" 2)} (get-in s2 [:units "u" :unknowns])))
      (is (= :unit-has-unknowns (refusal s2 {:command :start-turn :turn "t2" :unit "u" :spec spec}))
          "nothing proceeds on the unit until what the run did is settled"))))

(deftest the-conductor-hears-each-outcome-once
  (let [[s] (run [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :start-turn :turn "t" :unit "u" :spec spec}
                  {:command :record-outcome :turn "t" :outcome :completed}])
        ;; Its session coming up with nothing to run put a :unit-ready before it.
        item (some (fn [[id i]] (when (= :turn-ended (:kind i)) id)) (:inbox s))
        all  (vec (keys (:inbox s)))]
    (is (= {:id item :kind :turn-ended :unit "u" :turn "t" :run 1 :outcome :completed} (get-in s [:inbox item])))
    (is (= :only-the-conductor-hears (refusal s {:command :start-turn :turn "x" :unit "u" :spec spec :hears [item]})))
    (let [[s2] (d/step s {:command :start-turn :turn "k1" :unit nil :spec spec :hears all})]
      (is (empty? (:inbox s2)) "hearing takes the item out")
      (is (= :not-in-inbox (refusal (assoc-in s2 [:turns "k1" :status] :completed)
                                    {:command :start-turn :turn "k2" :unit nil :spec spec :hears [item]}))
          "so no other conductor turn hears it again")
      (testing "a conductor turn that fails puts back what it heard"
        (let [[s3] (d/step s2 {:command :record-outcome :turn "k1" :outcome :failed})]
          (is (contains? (:inbox s3) item))
          (is (= 1 (:conductor-failures s3)))))
      (testing "one that completes has disposed of it"
        (let [[s3] (d/step s2 {:command :record-outcome :turn "k1" :outcome :completed})]
          (is (empty? (:inbox s3)))
          (is (zero? (:conductor-failures s3))))))
    (testing "a hold's cancellation, a release and a merge reach it too"
      (let [[s2] (run [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                       {:command :start-turn :turn "t" :unit "u" :spec spec}
                       {:command :hold-unit :unit "u"}
                       {:command :release-unit :unit "u"}
                       {:command :record-merge :unit "u"}])]
        (is (= [[:turn-ended :cancelled] [:unit-released nil] [:unit-merged nil]]
               (map (juxt :kind :outcome) (remove #(= :unit-ready (:kind %)) (sort-by :id (vals (:inbox s2)))))))))))

(deftest an-ask-halts-every-unit-it-concerns-in-its-own-commit
  (let [[s] (run [{:command :open-unit :unit "x" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :open-unit :unit "y" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :start-turn :turn "tx" :unit "x" :spec spec}
                  {:command :start-turn :turn "ty" :unit "y" :spec spec}
                  {:command :record-outcome :turn "ty" :outcome :asked :call {:id "c" :question "Go?"}}])]
    (testing "an ask naming x cancels x's running turn"
      (let [[s2] (d/step s {:command :raise-operator-ask :ask "a" :binds ["ty"] :concerns "x" :content ready})]
        (is (= {:status :cancelled :ended-by :concerned-ask} (select-keys (get-in s2 [:turns "tx"]) [:status :ended-by])))
        (is (some #(and (= "tx" (:turn %)) (= :cancelled (:outcome %))) (vals (:inbox s2))) "and the conductor hears it")
        (is (empty? (d/violations s2)))))
    (testing "an ask naming no unit concerns every unit"
      (let [[s2] (d/step s {:command :raise-operator-ask :ask "a" :binds ["ty"] :concerns nil :content ready})]
        (is (= :cancelled (get-in s2 [:turns "tx" :status])))
        (let [s3 (steps s2 [{:command :open-unit :unit "z" :project "p" :goal "g" :session-cwd "/w"}])]
          (is (= :unit-has-open-operator-ask (refusal s3 {:command :start-turn :turn "tz" :unit "z" :spec spec}))
              "a unit opened while it is open is held still too"))))))

(def ^:private id* {:project :nido :external-refs [] :created-at "2026-11-23T00:00:00Z"})
(defn- row* [kind] {:kind kind :at "t" :ext "edn"})

(deftest a-ledger-is-appended-after-the-seq-its-author-read
  (let [[s events] (run [{:command :create-workstream :identity id*}
                         {:command :append-entry :after 0 :row (row* :intent) :content "{:format :intent}"}
                         {:command :append-entry :after 1 :row (row* :note) :content "n"}])]
    (is (= [1 2] (map :seq (:ledger s))) "the domain numbers each entry past the one it was read after")
    (is (= "n" (:content (last events))) "the content is the event's, never the state's")
    (is (not-any? #(contains? % :content) (:ledger s)))
    (is (= {:reason :stale :latest 2}
           (nth (d/step s {:command :append-entry :after 1 :row (row* :note) :content "late"}) 2))
        "an append made against a ledger that has moved since is refused")
    (is (= s (d/fold events)))))

(deftest a-ledger-belongs-to-a-workstream-the-core-holds
  (is (= :no-such-workstream (refusal d/empty-state {:command :append-entry :after 0 :row (row* :note) :content "x"})))
  (is (= :no-such-workstream (refusal d/empty-state {:command :revise-identity :changes {:facets {:a 1}}})))
  (let [[s] (run [{:command :create-workstream :identity id*}])]
    (is (= :workstream-exists (refusal s {:command :create-workstream :identity id*})))
    (is (= :identity-not-revisable (refusal s {:command :revise-identity :changes {:project :brian}})))
    (let [[s2] (d/step s {:command :revise-identity :changes {:facets {:a 1} :title {:text "t" :basis "b"}}})
          [s3] (d/step s2 {:command :revise-identity :changes {:title nil}})]
      (is (= {:a 1} (get-in s3 [:identity :facets])))
      (is (not (contains? (:identity s3) :title)) "a nil change removes the key"))
    (testing "a discarded workstream is found by nothing and created again by nothing"
      (let [[s2] (d/step s {:command :discard-workstream})]
        (is (= :workstream-exists (refusal s2 {:command :create-workstream :identity id*})))
        (is (= :no-such-workstream (refusal s2 {:command :append-entry :after 0 :row (row* :note) :content "x"})))))
    (testing "an open unit holds it; a retracted one does not, and its session is then wanted destroyed"
      (let [[s2] (d/step s {:command :open-unit :unit "u" :project "nido" :goal "g"})
            _    (is (= :work-in-flight (refusal s2 {:command :discard-workstream})))
            [s3] (d/step s2 {:command :retract-unit-goal :unit "u"})
            [s4 _ r] (d/step s3 {:command :discard-workstream})]
        (is (nil? r))
        (is (= :destroyed (d/session-want s4 (get-in s4 [:units "u"]))))))
    (testing "nor is one with a turn running discarded"
      (let [s2 (steps s [{:command :open-unit :unit "u" :project "nido" :goal "g" :session-cwd "/w"}])
            [s3 _ r] (d/step s2 {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "i"}})]
        (is (nil? r) "the turn starts")
        (is (= :work-in-flight (refusal s3 {:command :discard-workstream})))))))

(deftest the-events-the-retired-import-committed-still-fold
  (let [events [{:event :workstream-created :identity id* :imported true}
                {:event :entry-appended :row {:kind :intent :seq 1 :at "a" :ext "edn" :session "s"} :content "{}"}
                {:event :entry-appended :row {:kind :note :seq 3 :at "b" :ext "md"} :content "gap kept"}]
        s (d/fold events)]
    (is (= id* (:identity s)) "the imported mark is not read")
    (is (= [1 3] (map :seq (:ledger s))) "the seqs they were written under, gaps and all")
    (is (= 3 (d/latest-seq s)))
    (is (= :unknown-command (refusal s {:command :import-ledger :identity id* :entries []}))
        "no command imports a ledger any more")))

(def ^:private digest-a (apply str (repeat 64 "a")))

(deftest a-chat-message-names-its-images-by-digest
  (let [img digest-a
        [s] (run [{:command :post-chat :message "m1" :text "see this" :images [img]}])]
    (is (= [img] (:images (first (:chat s)))))
    (is (= [img] (:images (first (vals (:inbox s))))) "the conductor hears the images with the words"))
  (testing "a message without images folds as it always did"
    (let [[s] (run [{:command :post-chat :message "m1" :text "hi"}])]
      (is (= {:id "m1" :from :person :text "hi"} (first (:chat s))))
      (is (not (contains? (first (vals (:inbox s))) :images)))))
  (testing "an image is named, never carried, and a message carries at most four"
    (is (= :image-malformed (refusal d/empty-state {:command :post-chat :message "m" :text "x"
                                                    :images [{:digest digest-a :mime "image/png" :data "AAAA"}]}))
        "not even its media type")
    (is (= :image-malformed (refusal d/empty-state {:command :post-chat :message "m" :text "x"
                                                    :images ["nope"]})))
    (is (= :too-many-images (refusal d/empty-state {:command :post-chat :message "m" :text "x"
                                                    :images (repeat 5 digest-a)})))))

(deftest the-recipe-holds-code-until-a-design-stands-cleared
  (let [append (fn [s kind] (first (d/step s {:command :append-entry :after (d/latest-seq s)
                                                :row (row* kind) :content "{}"})))
        ledger (fn [& kinds] (reduce append (first (run [{:command :create-workstream :identity id*}
                                                          {:command :open-unit :unit "u" :project "brian"
                                                           :goal "g" :session-cwd "/w"}]))
                                     kinds))
        started (fn [s] (get-in (first (d/step s {:command :start-turn :turn "t" :unit "u" :spec spec}))
                                [:turns "t" :spec]))]
    (testing "a workstream the recipe does not run on changes code freely"
      (is (nil? (d/code-held (ledger :ticket))))
      (is (nil? (:read-only? (started (ledger :ticket))))))
    (testing "running it, nothing may change code before a design stands cleared"
      (doseq [s [(ledger :activated) (ledger :activated :intent :baseline :design)
                 (ledger :activated :design :design-cleared :design)
                 (ledger :activated :design :design-cleared :retraction)]]
        (is (= :design-not-cleared (d/code-held s)) (pr-str (map :kind (:ledger s))))
        (let [sp (started s)]
          (is (:read-only? sp) "the turn starts read-only, whatever the command said")
          (is (re-find #"may not change code" (:instructions sp)))
          (is (re-find #"do the thing$" (:instructions sp)) "and keeps its own instructions"))))
    (testing "a cleared design frees it, and so does taking the recipe off"
      (is (nil? (d/code-held (ledger :activated :intent :baseline :design :design-cleared))))
      (is (nil? (d/code-held (ledger :activated :design :design-approved))))
      (is (nil? (d/code-held (ledger :activated :deactivated))))
      (is (nil? (:read-only? (started (ledger :activated :design :design-cleared))))))))

(deftest a-unit-the-core-brings-up-with-nothing-to-run-wakes-the-conductor
  (let [up (fn [u] [{:command :record-provision-attempt :unit u :attempt (str "p-" u) :want :up}
                    {:command :record-provision-outcome :unit u :attempt (str "p-" u) :outcome :done :cwd "/w"}])
        items (fn [s] (map (juxt :kind :unit) (vals (:inbox s))))]
    (testing "a person's promote opens a unit with no first turn: once its session is up, the conductor hears it"
      (let [[s] (run (into [{:command :open-unit :unit "u" :project "p" :goal "g"}] (up "u")))]
        (is (= [[:unit-ready "u"]] (items s)))))
    (testing "a trigger's unit starts on its own first turn, so the conductor is not woken for it"
      (let [[s] (run (into [{:command :open-unit :unit "u" :project "p" :goal "g"
                             :first-turn {:spec spec}}] (up "u")))]
        (is (empty? (:inbox s)))))
    (testing "a provisioning recorded before the rule folds as it did: no item, no shifted inbox ids"
      (is (empty? (:inbox (d/fold [{:event :unit-opened :unit "u" :project "p" :goal "g"}
                                   {:event :provision-attempted :unit "u" :attempt "a" :want :up}
                                   {:event :provision-recorded :unit "u" :attempt "a" :outcome :done :cwd "/w"}])))))
    (testing "a unit is never given a session of its own: the core provisions every one (FU-803)"
      (is (= :session-cwd-not-accepted
             (refusal d/empty-state {:command :open-unit :unit "u" :project "p" :goal "g"
                                     :session-cwd "/home/me/Code/nido"})))
      (is (= {:status :ready :cwd "/w"}
             (get-in (d/fold [{:event :unit-opened :unit "u" :project "p" :goal "g" :session-cwd "/w"}])
                     [:units "u" :session]))
          "a unit already folded from an adopted :session-cwd keeps it"))))

(deftest what-the-core-is-doing-is-named-between-turns-too
  (let [after   (fn [cmds] (reduce (fn [s c] (let [[s2 _ r] (d/step s (resolve-command s c))]
                                                (when r (throw (ex-info "refused" {:c c :r r})))
                                                s2))
                                   d/empty-state (sessions/expand cmds)))
        kind    #(:kind (d/activity (after %)))
        open    {:command :open-unit :unit "u" :project "p" :goal "g"}
        ready   {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
        unit-t  {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "i"}}
        cond-t  {:command :start-turn :turn "c" :unit nil :spec {:executor :fake :instructions "i"}}
        asked   {:command :record-outcome :turn "t" :run 1 :outcome :asked
                 :call {:id "k" :question "q?"}}
        ask     {:command :raise-operator-ask :ask "a" :binds #{"c"}
                 :content {:question "q?" :explored "x" :options ["y" "n"] :recommendation "y"}}]
    (is (nil? (d/activity d/empty-state)) "nothing opened: nothing doing")
    (is (= :starting (kind [open])) "a unit whose session is not up yet is starting")
    (is (= :deciding (kind [ready])) "a unit come up with nothing to run wakes the conductor")
    (is (nil? (kind [ready {:command :start-turn :turn "c0" :unit nil :hears :whole-inbox
                            :spec {:executor :fake :instructions "i"}}
                     {:command :record-outcome :turn "c0" :run 1 :outcome :completed}]))
        "once it has heard so, a ready unit with nothing to run is idle")
    (is (= {:kind :working :turn "t" :unit "u"} (d/activity (after [ready unit-t]))))
    (is (= :deciding (kind [ready unit-t asked])) "a unit's call waits on the conductor")
    (is (= :deciding (kind [ready cond-t])) "the conductor running")
    (is (= :deciding (kind [ready unit-t {:command :record-outcome :turn "t" :run 1 :outcome :completed}]))
        "a turn ended: the conductor is owed a wake to hear it")
    (is (= :asking (kind [ready cond-t {:command :record-outcome :turn "c" :run 1 :outcome :asked
                                        :call {:id "k2" :question "q?"}}
                          ask]))
        "an ask awaiting the person outranks everything else")))

(deftest a-run-cancelled-by-an-ask-reads-stuck-beside-the-ask
  (let [[s] (run [{:command :open-unit :unit "x" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :open-unit :unit "y" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :start-turn :turn "tx" :unit "x" :spec spec}
                  {:command :start-turn :turn "ty" :unit "y" :spec spec}
                  {:command :record-outcome :turn "ty" :outcome :asked :call {:id "c" :question "q?"}}
                  {:command :raise-operator-ask :ask "a" :binds ["ty"] :concerns "x" :content ready}
                  {:command :record-stuck :turn "tx" :run 1 :reason :concerned-ask}])]
    (is (= :cancelled (get-in s [:turns "tx" :status])))
    (is (= {:kind :asking :stuck {:turn "tx" :unit "x"}} (d/activity s))
        "the ask outranks it, and carries the run that will not end")
    (let [[s2] (d/step s {:command :record-outcome :turn "tx" :run 1 :outcome :cancelled})]
      (is (= {:kind :asking} (d/activity s2)) "until its exit is recorded"))))

(deftest a-run-that-will-not-end-reads-stuck-until-it-does
  (let [ready {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
        unit-t {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "i"}}
        stuck {:command :record-stuck :turn "t" :run 1 :reason :settled}
        [s] (run [ready unit-t stuck])]
    (is (= {:kind :stuck :turn "t" :unit "u"} (d/activity s)) "stuck, not working")
    (is (= {:run 1 :reason :settled} (get-in s [:turns "t" :stuck])))
    (is (= {:turn "t" :run 1 :reason :settled :unit "u"} (d/stuck-run s)))
    (is (= :already-stuck (refusal s stuck)) "reported once per run")
    (is (= :stale-run (refusal s (assoc stuck :run 2))))
    (testing "its outcome, once it ends, is recorded as any turn's"
      (let [[s] (run [ready unit-t stuck {:command :record-outcome :turn "t" :run 1 :outcome :completed}])]
        (is (= :completed (get-in s [:turns "t" :status])))
        (is (= :deciding (:kind (d/activity s))))
        (is (= :turn-not-running (refusal s stuck)))))
    (testing "a stuck run is still stuck once its workstream closes, until its exit clears it"
      (is (= {:kind :stuck :turn "t" :unit "u"} (d/activity (assoc s :closed? true))))
      (let [[s] (run [ready unit-t stuck {:command :record-outcome :turn "t" :run 1 :outcome :completed}])]
        (is (nil? (d/activity (assoc s :closed? true))))))
    (testing "every recorded outcome records its run's exit, so a turn taken over after it asked awaits nothing"
      (let [[s] (run [ready unit-t {:command :record-outcome :turn "t" :run 1 :outcome :asked
                                    :call {:id "k" :question "q?"}}
                      {:command :hold-unit :unit "u"}])]
        (is (= :cancelled (get-in s [:turns "t" :status])))
        (is (= 1 (get-in s [:turns "t" :exited])))
        (is (not (d/run-awaited? (get-in s [:turns "t"]))))
        (is (= :turn-not-running (refusal s stuck)) "nor can it be reported stuck")))
    (testing "a conductor turn that will not end is stuck too"
      (let [[s] (run [ready {:command :start-turn :turn "c" :unit nil :spec {:executor :fake :instructions "i"}}
                      {:command :record-stuck :turn "c" :run 1 :reason :deadline}])]
        (is (= {:kind :stuck :turn "c"} (d/activity s)))))
    (testing "a run the store cancelled that will not end is stuck, and its exit clears it without reviving the turn"
      (let [hold {:command :hold-unit :unit "u"}
            [s] (run [ready unit-t hold (assoc stuck :reason :takeover)])]
        (is (= {:run 1 :reason :takeover} (get-in s [:turns "t" :stuck])))
        (is (= :stuck (:kind (d/activity s))))
        (let [[s] (run [ready unit-t hold (assoc stuck :reason :takeover)
                        {:command :record-outcome :turn "t" :run 1 :outcome :cancelled}])]
          (is (= :cancelled (get-in s [:turns "t" :status])))
          (is (= 1 (get-in s [:turns "t" :exited])) "its exit is recorded")
          (is (nil? (d/stuck-run s)) "and the stuck reading derives from it: no run is stuck")
          (is (not= :stuck (:kind (d/activity s))))
          (is (= :turn-not-running (refusal s {:command :record-outcome :turn "t" :run 1 :outcome :completed}))))
        (testing "its exit recorded before the stuck report refuses the report, so no mark outlives the process"
          (let [[s] (run [ready unit-t hold {:command :record-outcome :turn "t" :run 1 :outcome :cancelled}])]
            (is (= :turn-not-running (refusal s (assoc stuck :reason :takeover))))
            (is (not= :stuck (:kind (d/activity s))))))))))

(deftest a-granted-landing-is-the-conductors-to-direct
  (let [[s] (run [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :give-grant :grant :merge :by "person"}])]
    (is (= [:landing-granted] (keep #(#{:landing-granted} (:kind %)) (vals (:inbox s))))
        "the person's Land wakes the conductor, which nothing else would"))
  (let [[s] (run [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :give-grant :grant :intent :by "person"}])]
    (is (not-any? #(= :landing-granted (:kind %)) (vals (:inbox s))) "a grant of another kind wakes nothing")))

(deftest a-completed-turn-leaves-its-workstream-open
  ;; Work that is over when its turn completes is a run (`:start-run`), never a unit: a unit's
  ;; turn completing finishes nothing, whatever the command opening it carried.
  (let [[s]  (run [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"
                    :background? true}
                   {:command :start-turn :turn "t" :unit "u" :spec spec}])
        [s2] (d/step s {:command :record-outcome :turn "t" :outcome :completed})]
    (is (not (:closed? s2)))
    (is (= :open (get-in s2 [:units "u" :status])))
    (is (not (contains? (get-in s2 [:units "u"]) :background?)) "the flag is not kept")))

(deftest a-stopped-conductor-asks-as-a-failure
  (let [stop  {:command :raise-conductor-stop :ask "stop" :content ready}
        [s]   (run [stop])]
    (is (= {:kind :asking :failed? true :failure-only? true} (d/activity s))
        "the only ask is the machine's own: the person owes a recovery, not an answer")
    (is (every? d/machine-ask? (d/awaiting-person s)))
    (testing "a question beside it is asked as well: the failure still stands, and is not the only ask"
      (let [[s2] (run [{:command :start-turn :turn "k1" :unit nil :spec spec}
                       {:command :record-outcome :turn "k1" :outcome :asked :call {:id "c" :question "Go?"}}
                       {:command :raise-operator-ask :ask "a1" :binds ["k1"] :content ready}
                       stop])]
        (is (= {:kind :asking :failed? true} (d/activity s2)))
        (is (= [false true] (map d/machine-ask? (sort-by :id (d/awaiting-person s2)))))))))

(deftest an-ask-carries-its-failure-and-a-stuck-run-independently
  ;; :failed? and :stuck are separate facts about an :asking reading; either may be set alone or
  ;; with the other, and a reader of :asking accepts both.
  (let [[s] (run [{:command :open-unit :unit "x" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :start-turn :turn "tx" :unit "x" :spec spec}
                  {:command :record-stuck :turn "tx" :run 1 :reason :settled}
                  {:command :raise-conductor-stop :ask "stop" :content ready}])]
    (is (= {:kind :asking :failed? true :failure-only? true :stuck {:turn "tx" :unit "x"}}
           (d/activity s)))
    (let [[s2] (d/step s {:command :record-outcome :turn "tx" :run 1 :outcome :completed})]
      (is (= {:kind :asking :failed? true :failure-only? true} (d/activity s2))
          "the run's exit clears :stuck and leaves the failure"))))

;; ── runs ─────────────────────────────────────────────────────────────────────

(def a-run
  {:command :start-run
   :run {:id "review-queue-ab12" :project "brian" :trigger "review-queue" :goal "groom"
         :profile :lite :payload {:id "nightly-2026-11-25"}}
   :spec (assoc spec :ask? false)})

(defn- run-up
  "A run whose session came up at /w, and whose first turn has started."
  []
  (first (run [a-run
               {:command :record-provision-attempt :unit "review-queue-ab12" :want :up :attempt "a"}
               {:command :record-provision-outcome :unit "review-queue-ab12" :attempt "a" :outcome :done :cwd "/w"}
               {:command :start-turn :turn "review-queue-ab12 first" :unit "review-queue-ab12"
                :spec (assoc spec :ask? false)}])))

(deftest a-run-is-one-unit-provisioned-with-its-profile
  (let [[s] (run [a-run])]
    (is (= :starting (d/run-status s)))
    (is (= [{:unit "review-queue-ab12" :want :up}] (d/sessions-owed s)))
    (let [[s2] (run [a-run {:command :record-provision-attempt :unit "review-queue-ab12" :want :up :attempt "a"}])]
      (is (= :lite (:profile (first (d/attempts-pending s2))))))
    (testing "its first turn starts once its session is up"
      (is (= :running (d/run-status (run-up)))))
    (testing "only an empty stream starts a run"
      (is (= :stream-not-empty (refusal (first (run two-units)) a-run))))))

(deftest a-run-admits-no-workstream-command
  (let [s (run-up)]
    (doseq [c [{:command :post-chat :message "m" :text "hi"}
               {:command :open-unit :unit "x" :project "brian" :goal "g"}
               {:command :append-entry :row {:seq 1 :kind :note :at "t" :ext "md"} :content "x"}
               {:command :close-workstream}
               a-run]]
      (is (= :not-a-run-command (refusal s c)) (pr-str (:command c))))
    (is (= :a-run-has-no-conductor (refusal s {:command :start-turn :turn "c" :unit nil :spec spec})))))

(deftest a-run-is-over-when-its-turn-ends
  (doseq [[outcome status] [[:completed :done] [:failed :failed] [:cancelled :cancelled]]]
    (let [[s] (d/step (run-up) {:command :record-outcome :turn "review-queue-ab12 first" :outcome outcome})]
      (is (= status (d/run-status s)))
      (is (empty? (:inbox s)) "nobody hears a run's outcome")
      (is (= [{:unit "review-queue-ab12" :want :destroyed}] (d/sessions-owed s)))
      (is (empty? (d/violations s)))
      (is (= {:project "brian" :status status :closed? true :asking? false}
             (select-keys (d/summary s) [:project :status :closed? :asking?]))))))

(deftest a-run-that-asks-failed
  (let [[s] (d/step (run-up) {:command :record-outcome :turn "review-queue-ab12 first" :outcome :asked
                              :call {:id "c" :question "Go?"}})]
    (is (= :failed (d/run-status s)))
    (is (= {:reason :asked-in-a-run} (get-in s [:turns "review-queue-ab12 first" :detail])))
    (is (empty? (d/awaiting-person s)))))

(deftest a-run-whose-session-never-came-up-failed
  (let [[s] (run [a-run
                  {:command :record-provision-attempt :unit "review-queue-ab12" :want :up :attempt "a"}
                  {:command :record-provision-outcome :unit "review-queue-ab12" :attempt "a" :outcome :failed
                   :detail "no disk"}])]
    (is (= :failed (d/run-status s)))
    (is (= "no disk" (:detail (d/summary s))))
    (is (empty? (:inbox s)))
    (is (empty? (d/first-turns-owed s)))))

(deftest a-run-folds-from-its-events
  (let [s (run-up)
        [_ events] (run [a-run
                         {:command :record-provision-attempt :unit "review-queue-ab12" :want :up :attempt "a"}
                         {:command :record-provision-outcome :unit "review-queue-ab12" :attempt "a" :outcome :done :cwd "/w"}
                         {:command :start-turn :turn "review-queue-ab12 first" :unit "review-queue-ab12"
                          :spec (assoc spec :ask? false)}])]
    (is (= s (d/fold events)))))

;; ── a session lost with its host ─────────────────────────────────────────────

(deftest a-lost-session-is-owed-again-on-its-worktree
  (let [[s] (run [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w/u"}])
        [s2] (d/step s {:command :record-session-lost :unit "u"})]
    (is (= {:status :none :cwd "/w/u"} (get-in s2 [:units "u" :session])))
    (is (= [{:unit "u" :want :up}] (d/sessions-owed s2)) "an open unit's session is owed up again")
    (is (empty? (d/violations s2)))
    (testing "only a session recorded ready is lost"
      (is (= :session-not-ready (refusal s2 {:command :record-session-lost :unit "u"}))))
    (testing "never under a turn still running in it"
      (let [[s3] (d/step s {:command :start-turn :turn "t" :unit "u" :spec spec})]
        (is (= :turn-running (refusal s3 {:command :record-session-lost :unit "u"})))))))

;; ── records: what a run did, and journals ────────────────────────────────────

(def ^:private a-journal {:command :open-journal :journal {:project "nido" :name "improvement"}})

(deftest a-journal-holds-records-of-any-kind-in-order
  (let [[s] (run [a-journal
                  {:command :append-record :record {:kind :decision :address "a" :verdict :accept} :after 0}
                  {:command :append-record :record {:kind :landing :commit "c1"} :after 1}])]
    (is (= [{:kind :decision :address "a" :verdict :accept :seq 1} {:kind :landing :commit "c1" :seq 2}]
           (:records s)))
    (is (empty? (d/violations s)))
    (is (= {:project "nido" :journal {:project "nido" :name "improvement"} :last-seq 2
            :closed? false :asking? false}
           (d/summary s)))
    (testing "an append names the seq its author read; the later of two racing is refused"
      (is (= :stale (refusal s {:command :append-record :record {:kind :decision} :after 1}))))
    (testing "a record is a map with a keyword :kind, and the core numbers it"
      (is (= :record-needs-a-kind (refusal s {:command :append-record :record {:kind "x"} :after 2})))
      (is (= :record-names-its-own-seq (refusal s {:command :append-record :record {:kind :x :seq 9} :after 2}))))
    (testing "a journal admits nothing but records"
      (doseq [c [{:command :open-unit :unit "x" :project "nido" :goal "g"} {:command :post-chat :message "m" :text "hi"}
                 a-journal a-run]]
        (is (= :not-a-journal-command (refusal s c)) (pr-str (:command c)))))
    (testing "only an empty stream becomes a journal"
      (is (= :stream-not-empty (refusal (first (run two-units)) a-journal))))))

(deftest a-workstream-holds-no-records
  (is (= :records-are-a-journals-or-a-runs
         (refusal (first (run two-units)) {:command :append-record :record {:kind :x} :after 0}))))

(deftest a-run-records-what-it-did-while-it-runs
  (let [[s] (d/step (run-up) {:command :append-record :record {:kind :proposal :obs 1} :after 0})]
    (is (= [{:kind :proposal :obs 1 :seq 1}] (:records s)))
    (is (= [{:kind :proposal :obs 1 :seq 1}] (:records (d/summary s))))
    (let [[over] (d/step s {:command :record-outcome :turn "review-queue-ab12 first" :outcome :completed})]
      (is (= :run-is-over (refusal over {:command :append-record :record {:kind :proposal} :after 1}))
          "once its unit finished, nothing is added to what the run did"))))

(deftest records-fold-from-their-events
  (let [cs [a-journal {:command :append-record :record {:kind :k} :after 0}]
        [s events] (reduce (fn [[st evs] c] (let [[st' e] (d/step st c)] [st' (into evs e)])) [d/empty-state []] cs)]
    (is (= s (d/fold events)))))

;; ── a refused destroy ───────────────────────────────────────────────────────

(defn- unit-ready-at
  "Commands opening unit `u` and recording its session up at `cwd`."
  [u cwd]
  [{:command :open-unit :unit u :project "p" :goal "g"}
   {:command :record-provision-attempt :unit u :attempt (str "up-" u) :want :up}
   {:command :record-provision-outcome :unit u :attempt (str "up-" u) :outcome :done :cwd cwd}])

(def ^:private refusal-detail
  "Refusing to destroy session p: /src/p is not its own to remove — it is the project directory /src/p")

(deftest a-refused-destroy-is-terminal-and-never-reads-destroyed
  (doseq [[how ends] [[:closed [{:command :record-merge :unit "u"} {:command :close-workstream}]]
                      [:discarded [{:command :retract-unit-goal :unit "u"} {:command :discard-workstream}]]]]
    (testing (str "on a " (name how) " workstream")
      (let [begin (cons {:command :create-workstream :identity id*} (unit-ready-at "u" "/src/p"))
            [s0] (run (into (vec begin) ends))
            _    (is (= [{:unit "u" :want :destroyed}] (d/sessions-owed s0)))
            [s]  (run (into (into (vec begin) ends)
                            [{:command :record-provision-attempt :unit "u" :attempt "d1" :want :destroyed}
                             {:command :record-provision-outcome :unit "u" :attempt "d1" :outcome :refused
                              :detail refusal-detail}]))
            sess (get-in s [:units "u" :session])]
        (is (= :refused (:status sess)) "never :destroyed, never :failed")
        (is (= "/src/p" (:cwd sess)) "the path kept stays on the session")
        (is (= refusal-detail (:detail sess)) "with the refusal's detail")
        (is (empty? (d/sessions-owed s)) "and nothing is owed for it again")
        (is (= :session-not-owed
               (refusal s {:command :record-provision-attempt :unit "u" :attempt "d2" :want :destroyed}))
            "so no second attempt is admitted")
        (is (= :session-not-failed (refusal s {:command :retry-provision :unit "u"}))
            "nor a retry")
        (is (not-any? #(= :session-failed (:kind %)) (vals (:inbox s))) "a refusal is no failure")))))

(deftest only-a-destroy-is-recorded-refused
  (doseq [want [:up :stopped]]
    (let [[s] (run (cond-> [{:command :open-unit :unit "u" :project "p" :goal "g"}]
                     (= :stopped want) (into [{:command :record-provision-attempt :unit "u" :attempt "a" :want :up}
                                              {:command :record-provision-outcome :unit "u" :attempt "a"
                                               :outcome :done :cwd "/w"}
                                              {:command :retract-unit-goal :unit "u"}])
                     true (conj {:command :record-provision-attempt :unit "u" :attempt "b" :want want})))]
      (is (= :refused-is-a-destroys
             (refusal s {:command :record-provision-outcome :unit "u" :attempt "b" :outcome :refused
                         :detail "x"}))
          (str "refused for a " want " attempt"))
      (is (nil? (refusal s {:command :record-provision-outcome :unit "u" :attempt "b" :outcome :failed
                            :detail "x"}))
          (str "a " want " attempt that met the refusal records it :failed")))))

(deftest only-an-up-that-came-up-sets-the-sessions-cwd
  (testing "a failed :up carrying a cwd is refused, and the unit keeps no cwd"
    (let [[s] (run [{:command :open-unit :unit "u" :project "p" :goal "g"}
                    {:command :record-provision-attempt :unit "u" :attempt "a" :want :up}])]
      (is (= :cwd-only-from-an-up
             (refusal s {:command :record-provision-outcome :unit "u" :attempt "a" :outcome :failed
                         :cwd "/src/p" :detail "x"})))))
  (testing "a refused destroy carrying a cwd is refused, so it cannot overwrite the kept path"
    (let [[s] (run (into (vec (cons {:command :create-workstream :identity id*} (unit-ready-at "u" "/w/u")))
                         [{:command :record-merge :unit "u"} {:command :close-workstream}
                          {:command :record-provision-attempt :unit "u" :attempt "d1" :want :destroyed}]))]
      (is (= :cwd-only-from-an-up
             (refusal s {:command :record-provision-outcome :unit "u" :attempt "d1" :outcome :refused
                         :cwd "/elsewhere" :detail "x"})))))
  (testing "a ledger whose failed :up carried a cwd folds without it"
    (let [s (d/fold [{:event :unit-opened :unit "u" :project "p" :goal "g"}
                     {:event :provision-attempted :unit "u" :attempt "a" :want :up}
                     {:event :provision-recorded :unit "u" :attempt "a" :outcome :failed :cwd "/src/p"
                      :detail "x"}])]
      (is (nil? (get-in s [:units "u" :session :cwd]))))))

;; ── notices and the landing mark ─────────────────────────────────────────────

(deftest a-notice-is-posted-once-and-is-no-persons-input
  (let [[s] (run [{:command :post-notice :notice "wake:census:3" :text "Census #3: 2 remaining."}])
        item (first (vals (:inbox s)))]
    (is (= #{"wake:census:3"} (:notices s)))
    (is (= {:kind :notice :notice "wake:census:3" :text "Census #3: 2 remaining."} (dissoc item :id)))
    (is (empty? (:chat s)) "a notice never reaches the chat")
    (is (not (contains? d/person-input-kinds (:kind item))))
    (is (= :notice-exists (refusal s {:command :post-notice :notice "wake:census:3" :text "again"})))
    (testing "once heard, its id is still held"
      (let [[s2] (run [{:command :post-notice :notice "wake:census:3" :text "t"}
                       {:command :start-turn :turn "c1" :unit nil :spec spec :hears :whole-inbox}])]
        (is (empty? (:inbox s2)))
        (is (= :notice-exists (refusal s2 {:command :post-notice :notice "wake:census:3" :text "t"})))))
    (is (= :workstream-closed
           (refusal (assoc s :closed? true) {:command :post-notice :notice "n" :text "t"})))))

(def ^:private landing-unit
  [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}
   {:command :start-turn :turn "t1" :unit "u" :spec spec}])

(deftest a-unit-turn-ending-under-the-merge-grant-is-marked
  (testing "ended failed while the grant is held: marked"
    (let [[s] (run (into landing-unit [{:command :give-grant :grant :merge :by "person"}
                                       {:command :record-outcome :turn "t1" :outcome :failed}]))]
      (is (true? (get-in s [:turns "t1" :ended-under-merge?])))))
  (testing "a turn already running when the grant is given is marked too"
    (let [[s] (run (into landing-unit [{:command :give-grant :grant :merge :by "person"}
                                       {:command :record-outcome :turn "t1" :outcome :cancelled}]))]
      (is (true? (get-in s [:turns "t1" :ended-under-merge?])))))
  (testing "completed, or ended with no grant held: not marked"
    (let [[s] (run (into landing-unit [{:command :give-grant :grant :merge :by "person"}
                                       {:command :record-outcome :turn "t1" :outcome :completed}]))]
      (is (nil? (get-in s [:turns "t1" :ended-under-merge?]))))
    (let [[s] (run (conj landing-unit {:command :record-outcome :turn "t1" :outcome :failed}))]
      (is (nil? (get-in s [:turns "t1" :ended-under-merge?])))))
  (testing "a hold cancelling the unit's turn under the grant marks it"
    (let [[s] (run (into landing-unit [{:command :give-grant :grant :merge :by "person"}
                                       {:command :hold-unit :unit "u"}]))]
      (is (true? (get-in s [:turns "t1" :ended-under-merge?])))))
  (testing "an ask concerning the unit, cancelling its running turn under the grant, marks it"
    (let [[s] (run (into landing-unit [{:command :open-unit :unit "v" :project "brian" :goal "g" :session-cwd "/w"}
                                       {:command :start-turn :turn "t2" :unit "v" :spec spec}
                                       {:command :record-outcome :turn "t2" :outcome :asked :call {:id "c" :question "Go?" :explored "x" :options [{:option "A" :consequence "x"}] :recommendation "A"}}
                                       {:command :give-grant :grant :merge :by "person"}
                                       {:command :raise-operator-ask :ask "a" :binds ["t2"] :concerns "u" :content ready}]))]
      (is (true? (get-in s [:turns "t1" :ended-under-merge?])))))
  (testing "once the unit merged, the grant is gone and nothing is marked"
    (let [[s] (run (into landing-unit [{:command :give-grant :grant :merge :by "person"}
                                       {:command :record-merge :unit "u"}]))]
      (is (nil? (get-in s [:turns "t1" :ended-under-merge?]))))))
