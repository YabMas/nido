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
   :options [{:label "Web only" :summary "Through the teacher's Preview tab." :recommended? true}
             {:label "brian-mobile too" :summary "Adds mobile API and rendering work."
              :consequence "Cross-repo, a second ticket."}]})

(def ^:private option-actions
  [{:id :option-a :label "A" :kind :mutation}
   {:id :option-b :label "B" :kind :mutation}
   {:id :reply :label "Answer" :kind :resume}])

(deftest a-blocker-asks-its-needs
  (let [a (ask/ask :answer-blocker {:report blocker :intent intent :actions option-actions})]
    (is (m/validate ask/Ask a))
    (is (= "Where should students see interactive elements?" (:question a)))
    (is (= [(:summary blocker)] (:context a)))
    (is (= [:option-a :option-b] (mapv :action (:options a)))
        "each branch is tied to the button that gives it")
    (is (= ["A" "B"] (mapv :letter (:options a))))
    (is (:recommended? (first (:options a))))
    (is (= "Cross-repo, a second ticket." (:consequence (second (:options a)))))
    (is (= [:option-a :option-b :reply] (mapv :action (:answer-does a)))
        "one effect per action the gate offers")
    (is (= (:done-when intent) (get-in a [:goal :done-when])))))

(deftest a-branch-with-no-button-is-not-offered
  (let [a (ask/ask :answer-blocker {:report blocker :actions [{:id :option-a :label "A" :kind :mutation}]})]
    (is (= [:option-a] (mapv :action (:options a))))
    (is (nil? (:goal a)) "no intent, no goal")))

(deftest the-escalation-headline-is-never-context
  (let [a (ask/ask :answer-blocker
                   {:report {:format :blocker :summary "the conductor escalated a hand-back"
                             :needs "Should this ship for blocker cards first?"}
                    :actions [{:id :reply :label "Answer" :kind :resume}]})]
    (is (= "Should this ship for blocker cards first?" (:question a)))
    (is (= [] (:context a)))))

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
                                         :summary "Cookie is dropped." :directions []}
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

(deftest triage-directions-are-options
  (let [a (ask/ask :accept-triage
                   {:report nil
                    :entries [{:format :triage-report :title "Login loops" :determination :bug :summary "s"
                               :directions [{:label "Pin the cookie" :shape "Set SameSite" :effort :s :confidence :high}]}]
                    :actions [{:id :direction-a :label "A" :kind :mutation}
                              {:id :apply :label "Defer decision" :kind :mutation}]})]
    (is (= [:direction-a] (mapv :action (:options a))))
    (is (str/includes? (:question a) "which fix"))))

(deftest a-slack-proposal-is-asked-from-the-proposal
  (let [a (ask/ask :accept-triage
                   {:report nil
                    :entries [{:format :proposed-ticket :seq 2 :title "Dialogue loses its draft"
                               :ticket-type "bug" :source-url "https://slack/x"
                               :problem "The draft vanishes on reload." :root-cause "r" :fix "f"}]
                    :actions [{:id :apply :label "Apply" :kind :mutation :answers :proposal}]})]
    (is (= "File “Dialogue loses its draft” as a ticket?" (:question a)))
    (is (some #(str/includes? % "The draft vanishes on reload.") (:context a))
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
