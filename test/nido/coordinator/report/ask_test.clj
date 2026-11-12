(ns nido.coordinator.report.ask-test
  "Every kind of question is asked in one shape. These pin what each kind's entry reads, and what
   the generic flow guarantees whatever the kind: a question, options tied to offered actions, one
   effect per action, and the goal beside it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [nido.coordinator.report.ask :as ask]))

(def ^:private intent
  {:format :intent :goal "Let a teacher embed interactive elements."
   :done-when ["A teacher can attach one." "A student sees it inline."]})

(def ^:private blocker
  {:format :blocker
   :summary "Students run AI dialogues in brian-mobile, which never receives interactive elements."
   :needs "Where should students see interactive elements?"
   :recommendation "Web only — it ships without the mobile work."
   :after "The ticket is built for the surface you choose."
   :options [{:label "Web only" :summary "Through the teacher's Preview tab." :recommended? true}
             {:label "brian-mobile too" :summary "Adds mobile API and rendering work."
              :consequence "Cross-repo, a second ticket."}]})

(def ^:private option-actions
  [{:id :option-a :label "A" :kind :mutation}
   {:id :option-b :label "B" :kind :mutation}
   {:id :reply :label "Answer" :kind :resume}])

(defn- q1 [a] (first (:questions a)))

(deftest a-blocker-asks-its-needs-in-its-parts
  (let [a (ask/ask :answer-blocker {:report blocker :intent intent :actions option-actions})]
    (is (m/validate ask/Ask a))
    (is (= "Where should students see interactive elements?" (:question a) (:question (q1 a)))
        "the headline and the one question are the blocker's needs")
    (is (= 1 (count (:questions a))))
    (is (= [(:summary blocker)] (:context (q1 a))))
    (is (= (:recommendation blocker) (:recommendation (q1 a))))
    (is (= (:after blocker) (:after (q1 a))))
    (is (= [:option-a :option-b] (mapv :action (:options (q1 a))))
        "each branch is tied to the button that gives it")
    (is (nil? (:submit a)) "every option answers by itself")
    (is (= ["A" "B"] (mapv :letter (:options (q1 a)))))
    (is (:recommended? (first (:options (q1 a)))))
    (is (= "Cross-repo, a second ticket." (:consequence (second (:options (q1 a))))))
    (is (= [:option-a :option-b :reply] (mapv :action (:answer-does a)))
        "one effect per action the gate offers")
    (is (= (:done-when intent) (get-in a [:goal :done-when])))))

(deftest a-branch-with-no-button-is-not-offered
  (let [a (ask/ask :answer-blocker {:report blocker :actions [{:id :option-a :label "A" :kind :mutation}]})]
    (is (= [:option-a] (mapv :action (:options (q1 a)))))
    (is (nil? (:goal a)) "no intent, no goal")))

(deftest a-legacy-blocker-reads-its-needs-as-body-text
  (let [a (ask/ask :answer-blocker
                   {:report {:format :blocker :summary "The modal offers a branch nothing implements."
                             :needs "A product decision on the Keep Old branch, given the migration."}
                    :actions [{:id :reply :label "Answer" :kind :resume}]})]
    (is (= "This work is waiting on your answer." (:question a))
        "a paragraph written before the parts is never set as the question")
    (is (= ["A product decision on the Keep Old branch, given the migration."
            "The modal offers a branch nothing implements."]
           (:context (q1 a))))))

(deftest the-escalation-headline-is-never-context
  (let [a (ask/ask :answer-blocker
                   {:report {:format :blocker :summary "the conductor escalated a hand-back"
                             :needs "Should this ship for blocker cards first?"
                             :after "The conductor carries on."}
                    :actions [{:id :reply :label "Answer" :kind :resume}]})]
    (is (= "Should this ship for blocker cards first?" (:question a)))
    (is (= [] (:context (q1 a))))))

(deftest the-owed-record-is-the-newest-of-its-kind
  (let [a (ask/ask :approve-design
                   {:report {:format :baseline-review :reason "a later review"}
                    :entries [{:format :design-decision :asks "Old question?" :seq 3}
                              {:format :baseline-review :reason "x" :seq 4}
                              {:format :design-decision :asks "Worth an L now?" :seq 5}]
                    :actions [{:id :approve :label "Approve" :kind :mutation}]})]
    (is (= "Worth an L now?" (:question a))
        "the decision owed is read even when a later record is the gate's report")))

(deftest every-kind-yields-a-question
  (doseq [[kind record] {:approve-design {:format :design-decision :recommend :proceed
                                          :reason "All checks held." :asks "Worth an L now?"}
                         :acknowledge-invalidation {:format :design-verdict :verdict :invalidated
                                                    :reason "The claim does not hold."}
                         :assert-gate {:criterion "p95 under 200ms for a week" :claim "the cache serves reads"}
                         :accept-triage {:format :triage-report :title "Login loops" :determination :bug
                                         :summary "Cookie is dropped." :questions []}
                         :land {:format :pr-opened :title "Fix login loop" :url "https://x/1"}
                         :something-new {:title "An unregistered kind"}}]
    (testing (name kind)
      (let [a (ask/ask kind {:report record :entries [record]
                             :actions [{:id :reply :label "Reply" :kind :resume}
                                       {:id :assert-gate :label "Assert gate" :kind :evidence
                                        :criterion (:criterion record)}]})]
        (is (m/validate ask/Ask a))
        (is (not (str/blank? (:question a))))))))

(deftest an-unregistered-kind-answers-generically
  (is (= :generic (:kind (ask/ask :something-new {:report {} :actions []}))))
  (is (= :generic (:kind (ask/ask :approve-design {:report {:title "t"} :entries [] :actions []})))
      "a kind whose record is missing still asks, generically, rather than costing the card")
  (is (= "This workstream is waiting on you." (:question (ask/ask :something-new {:report {} :actions []})))))

(def ^:private accept-actions
  [{:id :apply :label "Accept" :kind :mutation :answers :questions}
   {:id :defer :label "Defer decision" :kind :mutation}])

(deftest a-triage-asks-each-of-its-questions
  (let [a (ask/ask :accept-triage
                   {:report nil
                    :entries [{:format :triage-report :title "Login loops" :determination :bug
                               :summary "Two defects."
                               :questions [{:question "How should the cookie survive the redirect?"
                                            :context "It is dropped cross-site." :recommendation "Pin it."
                                            :after "The fix is built." 
                                            :directions [{:label "Pin the cookie" :shape "Set SameSite" :effort :S}
                                                         {:label "Move to a header" :shape "Token" :effort :L}]}
                                           {:question "Should the loop be capped?" :context "c" :after "a"
                                            :directions [{:label "Cap it" :shape "Three tries" :effort :XS}]}]}]
                    :actions accept-actions})]
    (is (m/validate ask/Ask a))
    (is (= "Accept the triage of “Login loops”?" (:question a)) "the headline asks to accept it")
    (is (= ["How should the cookie survive the redirect?" "Should the loop be capped?"]
           (mapv :question (:questions a)))
        "one question per question of the report, in order")
    (is (= ["Determination: bug." "Two defects." "It is dropped cross-site."] (:context (q1 a)))
        "the verdict leads the first question's context")
    (is (= "Pin it." (:recommendation (q1 a))))
    (is (= [["A" "Pin the cookie" "Effort: S"] ["B" "Move to a header" "Effort: L"]]
           (mapv (juxt :letter :label :consequence) (:options (q1 a)))))
    (is (not-any? :action (mapcat :options (:questions a)))
        "a direction is answered by Accept, never by a button of its own")
    (is (= :apply (:submit a)))))

(deftest a-legacy-triage-is-one-question
  (let [a (ask/ask :accept-triage
                   {:report nil
                    :entries [{:format :triage-report :title "t" :determination :bug :summary "s"
                               :directions [{:label "x" :shape "y" :effort :S}]}]
                    :actions accept-actions})]
    (is (= ["Which fix should be built?"] (mapv :question (:questions a))))
    (is (= :apply (:submit a)))))

(deftest a-triage-that-asks-nothing-asks-its-acceptance
  (let [a (ask/ask :accept-triage
                   {:report nil
                    :entries [{:format :triage-report :title "t" :determination :bug :summary "s" :questions []}]
                    :actions [{:id :apply :label "Apply" :kind :mutation}]})]
    (is (= ["Accept the triage of “t”?"] (mapv :question (:questions a))))
    (is (nil? (:submit a)))))

(deftest a-triage-whose-accept-is-not-offered-asks-generically
  (is (= :generic (:kind (ask/ask :accept-triage
                                  {:report nil
                                   :entries [{:format :triage-report :title "t" :determination :bug :summary "s"
                                              :directions [{:label "x" :shape "y"}]}]
                                   :actions [{:id :dismiss :label "Dismiss" :kind :mutation}]})))
      "choices nothing can send are not on offer"))

(deftest a-slack-proposal-is-asked-from-the-proposal
  (let [a (ask/ask :accept-triage
                   {:report nil
                    :entries [{:format :proposed-ticket :seq 2 :title "Dialogue loses its draft"
                               :ticket-type "bug" :source-url "https://slack/x"
                               :problem "The draft vanishes on reload." :root-cause "r" :fix "f"}]
                    :actions [{:id :apply :label "Apply" :kind :mutation :answers :proposal}]})]
    (is (= "File “Dialogue loses its draft” as a ticket?" (:question a)))
    (is (some #(str/includes? % "The draft vanishes on reload.") (:context (q1 a)))
        "the person can tell what they are accepting")
    (is (str/includes? (:does (first (:answer-does a))) "files it as a ticket"))))

(deftest an-answer-nobody-hears-promises-no-resume
  (let [does (fn [a] (:does (first (:answer-does (ask/ask :answer-blocker {:report blocker :actions [a]})))))]
    (is (str/includes? (does {:id :reply :label "Answer" :kind :resume :answers :ledger}) "Nobody is resumed"))
    (is (str/includes? (does {:id :reply :label "Reply" :kind :resume :answers :conductor}) "conductor"))
    (is (str/includes? (does {:id :reply :label "Reply" :kind :resume}) "parked session"))))

(deftest a-note-rides-on-the-ask
  (is (= "The conductor adds: approve it." (:note (ask/ask :approve-design {:report nil :entries [{:format :design-decision :asks "Go?"}] :actions []
                                                                         :note "The conductor adds: approve it."})))))

(deftest guidance-is-generic-plus-the-kinds-addendum
  (let [g (ask/guidance :answer-blocker)]
    (is (str/starts-with? g "# Asking the person a question"))
    (is (str/includes? g "When you halt on a blocker")))
  (is (str/includes? (ask/guidance :escalation) "When you escalate"))
  (is (= (ask/guidance :land) (str/trim (ask/guidance :generic)))
      "a kind with no addendum is briefed with the generic guidance alone"))

(deftest no-kind-says-what-waits-on-its-answer
  (doseq [[kind record] {:answer-blocker blocker
                         :approve-design {:format :design-decision :recommend :proceed :asks "Worth an L now?"}
                         :acknowledge-invalidation {:format :design-verdict :verdict :invalidated :reason "r"}
                         :assert-gate {:criterion "p95 under 200ms for a week"}
                         :accept-triage {:format :triage-report :title "Login loops" :determination :bug
                                         :summary "s" :questions []}
                         :land {:format :pr-opened :title "Fix login loop"}}]
    (testing (name kind)
      (let [a (ask/ask kind {:report record :entries [record]
                             :actions [{:id :reply :label "Reply" :kind :resume}
                                       {:id :assert-gate :label "Assert gate" :kind :evidence
                                        :criterion (:criterion record)}]})]
        (is (= kind (:kind a)))
        (is (not (contains? a :holding))
            "no kind says what waits on its answer: being asked at all already says it"))))
  (is (not (contains? (ask/ask :something-new {:report {} :actions []}) :holding)))
  (is (not (re-find #":holding" (pr-str ask/Ask))) "and the Ask has no slot for it"))

(deftest options-on-one-gate-do-the-same-thing-in-the-same-words
  (let [a    (ask/ask :answer-blocker {:report blocker :actions option-actions})
        opts (filter #(#{:option-a :option-b} (:action %)) (:answer-does a))]
    (is (= 1 (count (distinct (map :does opts))))
        "the letter is not in the sentence, so the card can say it once")
    (is (not-any? #(re-find #"(Records|direction) [AB]\b" (:does %)) opts)))
  (is (str/includes? (:does (first (:answer-does (ask/ask :accept-triage
                                                         {:report nil
                                                          :entries [{:format :triage-report :title "t"
                                                                     :determination :bug :summary "s"
                                                                     :directions [{:label "x" :shape "y"}]}]
                                                          :actions accept-actions}))))
                     "fixes you chose")
      "Accept says it carries the choices"))
