(ns nido.ui.components.chat-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [nido.ui.components.chat :as chat]))

(def ^:private routes
  {:id "p" :send-url "/s" :cancel-url "/c"
   :answer-url (fn [i o] (str "/a/" i "/" o)) :unpick-url (fn [i] (str "/u/" i))})

(defn- html [x] (str (h/html x)))

(defn- status [& items]
  (html (chat/turn-status "p" {:busy? true :started-at 1000
                               :items (into [{:id "i0" :kind :user :text "hi" :refs []}] items)})))

(deftest the-status-row-names-the-phase-of-a-running-turn
  (is (str/includes? (status) "Waiting for the agent") "sent, nothing back yet")
  (is (str/includes? (status {:kind :thought :text "…" :streaming? true}) "Thinking"))
  (is (str/includes? (status {:kind :tool :title "bb nido:test" :status :in-progress})
                     "Running bb nido:test"))
  (is (str/includes? (status {:kind :tool :title "bb nido:test" :status :in-progress}
                             {:kind :agent :text "while it runs" :streaming? true})
                     "Running bb nido:test")
      "a tool still running anywhere in the turn outranks the newest item")
  (is (str/includes? (status {:kind :agent :text "wor" :streaming? true}) "Writing"))
  (is (str/includes? (status {:kind :permission :title "Run" :options [] :answer nil}
                             {:kind :agent :text "…" :streaming? true})
                     "Waiting for your answer")
      "an unanswered request anywhere in the turn outranks the newest item")
  (is (str/includes? (status {:kind :agent :text "done"}) "Working"))
  (testing "the elapsed time is counted from when the turn started"
    (is (str/includes? (status) "data-since=\"1000\"")))
  (testing "a polite live region"
    (is (str/includes? (status) "aria-live=\"polite\""))))

(deftest no-turn-running-is-no-status
  (let [row (html (chat/turn-status "p" {:busy? false :items [{:id "i0" :kind :agent :text "done"}]}))]
    (is (not (str/includes? row "chat-status-l")))
    (is (not (str/includes? row "chat-spin")))))

(deftest what-opens-and-what-folds
  (let [item #(html (chat/chat-item (assoc % :id "i1") routes))]
    (is (str/includes? (item {:kind :thought :text "…" :streaming? true}) "open"))
    (is (not (str/includes? (item {:kind :thought :text "…"}) "open")))
    (is (not (str/includes? (item {:kind :tool :title "t" :status :completed :body "ok" :diffs []}) "open")))
    (is (str/includes? (item {:kind :tool :title "t" :status :failed :body "no" :diffs []}) "open")
        "a failed tool call opens itself")))

(deftest picks-are-numbered-and-each-removable
  (let [chips (html (chat/picks "p" [{:label "a"} {:label "b"}] routes))]
    (is (str/includes? chips "id=\"p-picks\""))
    (is (str/includes? chips "/u/0"))
    (is (str/includes? chips "/u/1"))
    (is (re-find #"chat-pick-n\">1<.*chat-pick-l\">a<.*chat-pick-n\">2<.*chat-pick-l\">b<" chips)))
  (testing "a sent message shows what it carried, without the remove control"
    (let [msg (html (chat/chat-item {:id "i0" :kind :user :text "hi" :refs [{:label "a"}]} routes))]
      (is (str/includes? msg "chat-pick-l\">a<"))
      (is (not (str/includes? msg "/u/0"))))))

(def ^:private change-routes {:publish-url "/pub" :revert-url "/rev"})
(def ^:private a-change {:description "Name the thing\n\nbody" :files [{:path "a.clj" :status :modified}]})

(deftest the-change-bar-renders-what-is-unlanded-and-how-a-publish-went
  (testing "no change and nothing to say is an empty element"
    (is (= "<section class=\"chat-change\" data-signals=\"{pConfirm: &apos;&apos;}\" id=\"p-change\"></section>"
           (html (chat/change-bar "p" nil {} change-routes)))))
  (testing "a change names its files and what it would land as, with Publish and Cancel confirmed in place"
    (let [out (html (chat/change-bar "p" a-change {} change-routes))]
      (is (str/includes? out "1 file changed"))
      (is (str/includes? out "Name the thing"))
      (is (not (str/includes? out "body")) "only the subject line")
      (is (str/includes? out "@post(&apos;/pub&apos;)"))
      (is (str/includes? out "@post(&apos;/rev&apos;)"))
      (is (str/includes? out "data-attr:disabled=\"$pBusy\"") "withheld while a turn runs")))
  (testing "an undescribed change cannot be published"
    (is (str/includes? (html (chat/change-bar "p" (assoc a-change :description " ") {} change-routes))
                       "data-attr:disabled=\"true\"")))
  (testing "a running publish shows its step and offers nothing"
    (let [out (html (chat/change-bar "p" a-change {:running? true :step "pushing main"} change-routes))]
      (is (str/includes? out "Publishing · pushing main"))
      (is (not (str/includes? out "/pub")))))
  (testing "how the last publish ended"
    (is (str/includes? (html (chat/change-bar "p" nil {:landed "0123456789abcdef"} change-routes)) "0123456789ab<"))
    (is (str/includes? (html (chat/change-bar "p" a-change {:refused "red"} change-routes)) "Not published: red"))))

(deftest a-note-is-a-quiet-line
  (is (str/includes? (html (chat/chat-item {:id "i0" :kind :note :text "Edits thrown away."} routes))
                     "class=\"chat-note\"")))
