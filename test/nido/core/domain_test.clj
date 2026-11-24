(ns nido.core.domain-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [nido.core.domain :as d]))

(def ready {:explored "tried A and B" :options [{:option "A" :consequence "x"}] :recommendation "A"})
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
          [d/empty-state []] commands))

(defn- refusal [state command] (:reason (nth (d/step state command) 2)))

(def two-units
  [{:command :open-unit :unit "brian" :project "brian" :goal "g1" :session-cwd "/w"}
   {:command :open-unit :unit "babel" :project "babel" :goal "g2" :session-cwd "/w"}])

(deftest an-ask-halts-the-unit
  (let [[s] (run (conj two-units
                       {:command :start-turn :turn "t1" :unit "brian" :spec spec}
                       {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c1"}}))]
    (testing "an asked turn is active, so no further turn starts on its unit"
      (is (= :unit-has-active-turn (refusal s {:command :start-turn :turn "t2" :unit "brian" :spec spec}))))
    (testing "a sibling unit is unaffected"
      (is (nil? (refusal s {:command :start-turn :turn "t3" :unit "babel" :spec spec}))))
    (testing "an asked turn reports nothing further"
      (is (= :turn-not-running (refusal s {:command :record-outcome :turn "t1" :outcome :completed}))))))

(deftest the-buffer-disposes-of-a-unit-ask-in-exactly-one-way
  (let [[s] (run (conj two-units
                       {:command :start-turn :turn "t1" :unit "brian" :spec spec}
                       {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c1"}}))
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
                  {:command :record-outcome :turn "k1" :outcome :asked :call {:id "c"}}
                  {:command :raise-operator-ask :ask "a1" :binds ["k1"] :content ready}
                  {:command :start-turn :turn "k2" :unit nil :spec spec}
                  {:command :answer-operator-ask :ask "a1" :answer "x"}])]
    (is (= :resolved (get-in s [:turns "k1" :status])) "the person's answer is never refused")
    (is (= :conductor-turn-running (refusal s {:command :resume-turn :turn "k1"})))
    (let [[s2] (d/step s {:command :record-outcome :turn "k2" :outcome :completed})]
      (is (nil? (refusal s2 {:command :resume-turn :turn "k1"}))))))

(deftest a-conductor-ask-goes-to-the-person
  (let [[s] (run [{:command :start-turn :turn "k1" :unit nil :spec spec}
                  {:command :record-outcome :turn "k1" :outcome :asked :call {:id "c"}}])]
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
                       {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c1"}}
                       {:command :record-outcome :turn "t2" :outcome :asked :call {:id "c2"}}
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
                            {:command :record-outcome :turn "t9" :outcome :asked :call {:id "c"}}))]
        (is (= :ask-not-decision-ready
               (refusal s3 {:command :raise-operator-ask :ask "a3" :binds ["t9"]
                            :content (dissoc ready :recommendation)})))))))

(deftest an-ask-about-a-unit-holds-it
  (let [[s] (run (conj two-units
                       {:command :start-turn :turn "k1" :unit nil :spec spec}
                       {:command :record-outcome :turn "k1" :outcome :asked :call {:id "c"}}
                       {:command :raise-operator-ask :ask "a1" :binds ["k1"] :concerns "babel" :content ready}))]
    (is (= :unit-has-open-operator-ask (refusal s {:command :start-turn :turn "t1" :unit "babel" :spec spec})))
    (is (nil? (refusal s {:command :start-turn :turn "t1" :unit "brian" :spec spec})))))

(deftest a-hold-takes-the-unit-over-in-one-commit
  (let [[s] (run (conj two-units
                       {:command :start-turn :turn "t1" :unit "brian" :spec spec}
                       {:command :start-turn :turn "t2" :unit "babel" :spec spec}
                       {:command :record-outcome :turn "t1" :outcome :asked :call {:id "c1"}}
                       {:command :record-outcome :turn "t2" :outcome :asked :call {:id "c2"}}
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
     [(gen/fmap (fn [u] {:command :open-unit :unit u :project u :goal "g" :session-cwd "/w"}) unit)
      (gen/fmap (fn [[t u]] (cond-> {:command :start-turn :turn t :unit u :spec spec}
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
      (gen/fmap (fn [[u o]] {:command :record-provision-outcome :unit u :attempt (str "p-" u) :outcome o :cwd "/w"})
                (gen/tuple unit (gen/elements [:done :failed])))
      (gen/fmap (fn [u] {:command :retry-provision :unit u}) unit)
      (gen/fmap (fn [g] {:command :give-grant :grant g :by "p"}) (gen/elements [:merge :intent]))
      (gen/fmap (fn [g] {:command :spend-grant :grant g}) (gen/elements [:merge :intent]))
      (gen/fmap (fn [m] {:command :post-chat :message m :text "hi"}) (gen/elements ["m1" "m2"]))
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
                  {:command :record-outcome :turn "t" :run 1 :outcome :asked :call {:id "c"}}
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
        [item] (keys (:inbox s))]
    (is (= {:id item :kind :turn-ended :unit "u" :turn "t" :run 1 :outcome :completed} (get-in s [:inbox item])))
    (is (= :only-the-conductor-hears (refusal s {:command :start-turn :turn "x" :unit "u" :spec spec :hears [item]})))
    (let [[s2] (d/step s {:command :start-turn :turn "k1" :unit nil :spec spec :hears [item]})]
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
               (map (juxt :kind :outcome) (sort-by :id (vals (:inbox s2))))))))))

(deftest an-ask-halts-every-unit-it-concerns-in-its-own-commit
  (let [[s] (run [{:command :open-unit :unit "x" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :open-unit :unit "y" :project "p" :goal "g" :session-cwd "/w"}
                  {:command :start-turn :turn "tx" :unit "x" :spec spec}
                  {:command :start-turn :turn "ty" :unit "y" :spec spec}
                  {:command :record-outcome :turn "ty" :outcome :asked :call {:id "c"}}])]
    (testing "an ask naming x cancels x's running turn"
      (let [[s2] (d/step s {:command :raise-operator-ask :ask "a" :binds ["ty"] :concerns "x" :content ready})]
        (is (= {:status :cancelled :ended-by :concerned-ask} (select-keys (get-in s2 [:turns "tx"]) [:status :ended-by])))
        (is (some #(and (= "tx" (:turn %)) (= :cancelled (:outcome %))) (vals (:inbox s2))) "and the conductor hears it")
        (is (empty? (d/violations s2)))))
    (testing "an ask naming no unit concerns every unit"
      (let [[s2] (d/step s {:command :raise-operator-ask :ask "a" :binds ["ty"] :concerns nil :content ready})]
        (is (= :cancelled (get-in s2 [:turns "tx" :status])))
        (let [[s3] (d/step s2 {:command :open-unit :unit "z" :project "p" :goal "g" :session-cwd "/w"})]
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
    (is (= :workstream-exists (refusal s {:command :import-ledger :identity id* :entries []})))
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
      (let [[s2] (d/step s {:command :open-unit :unit "u" :project "nido" :goal "g" :session-cwd "/w"})
            [s3 _ r] (d/step s2 {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "i"}})]
        (is (nil? r) "the turn starts")
        (is (= :work-in-flight (refusal s3 {:command :discard-workstream})))))))

(deftest a-legacy-ledger-is-imported-whole-with-its-seqs
  (let [entries [{:row {:kind :intent :seq 1 :at "a" :ext "edn" :session "s"} :content "{}"}
                 {:row {:kind :note :seq 3 :at "b" :ext "md"} :content "gap kept"}]
        [s events] (run [{:command :open-unit :unit "u" :project "nido" :goal "g" :session-cwd "/w"}
                         {:command :import-ledger :identity id* :entries entries}])]
    (is (= [1 3] (map :seq (:ledger s))) "the seqs it was written under, gaps and all")
    (is (= "s" (:session (first (:ledger s)))))
    (is (= :open (get-in s [:units "u" :status])) "coordination already held is kept")
    (is (= 3 (d/latest-seq s)))
    (is (= s (d/fold events)))
    (is (= :seqs-not-increasing
           (refusal d/empty-state {:command :import-ledger :identity id*
                                   :entries (reverse entries)})))))

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
