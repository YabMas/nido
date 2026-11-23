(ns nido.ui.components.ask-test
  "The gate card: answering in words, and its agent-written prose rendered through the one
   Markdown renderer."
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

;; The fukan blocker that prompted this (ws-20261117-2786b5, entry 75), trimmed: its body is a
;; three-finding bullet list, and its recommended option enumerates (1) … (2) … inline.
(def ^:private summary
  (str "The review loop is holding 3 findings it has no move for:\n\n"
       "- **Apply scalar substitutions** — re-derives names from the `where` as written.\n"
       "- **Judge nested negations** — under invariant `an-engine-refusal-is-a-sentence`.\n"
       "- **Traverse measure bodies** — Round 2 wrote `clause-refs`.\n\n"
       "It names the repair."))

(def ^:private repair
  (str "(1) Before landing, attribute against the `where*` that `q` compiles (see pressure). "
       "(2) Amend the design record. (3) Decide whether a law should read like a query refusal."))

(def ^:private the-ask
  {:kind :blocker
   :question "Is that repair owed here?"
   :questions [{:question "Is that repair owed here?"
                :context [summary]
                :recommendation "Take the **repair** the verdict names"
                :after "Your answer is recorded; the `parked` findings stay open."
                :options [{:letter "a" :label "The design stands"
                           :summary "The findings are answered by the design as written."
                           :consequence "They are declined on the record."}
                          {:letter "c" :label "Take the repair the verdict names"
                           :summary repair
                           :consequence "The design record is *untouched*."
                           :recommended? true}]}]
   :answer-does []})

(defn- card [ask]
  (str (h/html (ask/question-card "fukan" "ws-1" ask []
                                     {:route (fn [p w a] (str "/x/" p "/" w "/" (name a)))}))))

(deftest the-gate-body-renders-its-markdown
  (let [out (card the-ask)]
    (testing "the findings are a list with bold titles and code spans, not one paragraph of marks"
      (is (str/includes? out "<li><strong>Apply scalar substitutions</strong> — re-derives names from the <code>where</code> as written.</li>"))
      (is (= 3 (count (re-seq #"<li><strong>" out))))
      (is (not (str/includes? out "**")) "no literal bold marks")
      (is (not (str/includes? out "`")) "no literal backticks"))
    (testing "the option's (1) … (2) … items are separate list items"
      (is (str/includes? out "<ol><li>Before landing, attribute against the <code>where*</code> that <code>q</code> compiles (see pressure).</li><li>Amend the design record.</li><li>Decide whether a law should read like a query refusal.</li></ol>")))
    (testing "Recommended, Once answered and an option's consequence render too"
      (is (str/includes? out "<strong>repair</strong>"))
      (is (str/includes? out "<code>parked</code>"))
      (is (str/includes? out "<em>untouched</em>")))))

(deftest the-gate-card-escapes-agent-html
  (let [x   "<script>alert(1)</script>"
        out (card (-> the-ask
                      (assoc :question x)
                      (update-in [:questions 0] assoc :question x :context [x] :recommendation x :after x)
                      (update-in [:questions 0 :options 0] assoc :summary x :consequence x)))]
    (is (not (str/includes? out "<script>")) "no field lets agent HTML through as markup")
    (is (<= 6 (count (re-seq #"&lt;script&gt;" out))) "each field shows it as text")))

(deftest an-option-renders-its-links-as-text-inside-its-button
  ;; An <a> inside the option's <button> is invalid HTML; the prose around the card keeps its links.
  (let [link "see [the record](https://example.com/r)"
        out  (card (-> the-ask
                       (update-in [:questions 0] assoc :context [link])
                       (update-in [:questions 0 :options 0] assoc :summary link :consequence link)))
        opts (subs out (str/index-of out "qc-opts"))]
    (is (str/includes? out "<a href=\"https://example.com/r\"") "the question's context links")
    (is (not (str/includes? opts "<a ")) "no option summary or consequence carries an <a>")
    (is (str/includes? opts "see <span>the record</span>"))))
