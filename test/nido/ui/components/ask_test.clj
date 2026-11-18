(ns nido.ui.components.ask-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [nido.ui.components.ask :as ask]))

(defn- html [x] (str (h/html x)))

(def ^:private route (fn [_ _ id] (str "/a/" (name id))))

(def ^:private two-questions
  {:kind      :question
   :question  "Should the card ask the general question?"
   :submit    :answer-questions
   :questions [{:question "Should the card ask the general question?"
                :options  [{:letter "A" :label "General" :summary "Ask it."}
                           {:letter "B" :label "As written" :summary "Show it."}]}
               {:question "May alternatives show as their own questions?" :options []}]})

(def ^:private submit-action {:id :answer-questions :kind :mutation :label "Send answers" :words? true})

(deftest a-submitting-card-offers-every-question-an-answer
  (let [out (html (ask/question-card "nido" "ws-1" two-questions [submit-action] {:route route}))]
    (testing "an option-less question gets a words field under its own index"
      (is (str/includes? out "data-bind=\"qcard.ws_1.words.q1\""))
      (is (not (str/includes? out "qcard.ws_1.words.q0\"")) "a question with options is answered by them"))
    (testing "the card's state holds a letter and words per question, so a submit posts every index"
      (is (str/includes? out "answers: {q0: &apos;&apos;, q1: &apos;&apos;}"))
      (is (str/includes? out "words: {q0: &apos;&apos;, q1: &apos;&apos;}")))
    (testing "a question answered in words counts as answered in the stepper"
      (is (str/includes? out "$qcard.ws_1.words.q1")))))

(deftest a-card-that-does-not-submit-has-no-words-field
  (let [out (html (ask/question-card "nido" "ws-1" (dissoc two-questions :submit) [] {:route route}))]
    (is (not (str/includes? out "qc-words")))
    (is (not (str/includes? out "words: {")))))

(deftest a-submit-that-records-no-words-offers-no-words-field
  ;; A triage Accept reads only letters back, so words typed there would be dropped unseen.
  (let [out (html (ask/question-card "nido" "ws-1" (assoc two-questions :submit :apply)
                                     [{:id :apply :kind :mutation :label "Accept"}] {:route route}))]
    (is (not (str/includes? out "qc-words")))
    (is (not (str/includes? out "words: {")))))

(deftest a-pending-card-offers-no-words-field
  (is (not (str/includes? (html (ask/question-card "nido" "ws-1" two-questions [submit-action]
                                                    {:route route :pending? true}))
                          "qc-words"))))
