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
(def ^:private a-change {:description "Name the thing\n\nbody" :files [{:path "a.clj" :status :modified}]
                         :rev "r1" :conflicts []})

(deftest the-change-bar-renders-what-is-unlanded-and-how-a-publish-went
  (testing "no change and nothing to say is an empty element"
    (is (= "<section class=\"chat-change\" data-signals=\"{pConfirm: &apos;&apos;}\" id=\"p-change\"></section>"
           (html (chat/change-bar "p" nil {} change-routes)))))
  (testing "a change names its files and what it would land as, with Publish and Cancel confirmed in place"
    (let [out (html (chat/change-bar "p" a-change {} change-routes))]
      (is (str/includes? out "1 file changed"))
      (is (str/includes? out "Name the thing"))
      (is (not (str/includes? out "body")) "only the subject line")
      (is (str/includes? out "@post(&apos;/pub?rev=r1&apos;)") "Publish carries the revision it showed")
      (is (str/includes? out "@post(&apos;/rev&apos;)"))
      (is (str/includes? out "data-attr:disabled=\"$pBusy\"") "withheld while a turn runs")))
  (testing "an undescribed change cannot be published"
    (is (str/includes? (html (chat/change-bar "p" (assoc a-change :description " ") {} change-routes))
                       "data-attr:disabled=\"true\"")))
  (testing "a running publish shows its step and what it lands, apart from what was made since"
    (let [out (html (chat/change-bar "p" (assoc a-change :description "Newer")
                                     {:running? true :step "pushing main"
                                      :landing {:description "Landing it" :files [{:path "b.clj" :status :added}]}}
                                     change-routes))]
      (is (str/includes? out "Publishing · pushing main"))
      (is (str/includes? out "1 file landing"))
      (is (str/includes? out "Landing it"))
      (is (str/includes? out "1 file changed since Publish"))
      (is (str/includes? out "Newer"))
      (is (not (str/includes? out "data-attr:disabled=\"$pBusy\"")) "nothing can be published or thrown away meanwhile")))
  (testing "a landed publish keeps what it landed apart from what was made since, until the worktree rejoins"
    (let [out (html (chat/change-bar "p" (assoc a-change :description "Newer")
                                     {:landed "0123456789abcdef"
                                      :landing {:description "Landing it" :files [{:path "b.clj" :status :added}]}}
                                     change-routes))]
      (is (str/includes? out "Published <code>0123456789ab"))
      (is (str/includes? out "1 file landed"))
      (is (str/includes? out "Landing it"))
      (is (str/includes? out "1 file changed since Publish"))
      (is (str/includes? out "Newer"))))
  (testing "a conflicted change names its paths and cannot be published"
    (let [out (html (chat/change-bar "p" (assoc a-change :conflicts ["a.clj"]) {} change-routes))]
      (is (str/includes? out "Conflicts with main in <code>a.clj</code>"))
      (is (not (re-find #"btn-primary\" data-attr:disabled=\"\$pBusy" out)))))
  (testing "how the last publish ended"
    (is (str/includes? (html (chat/change-bar "p" nil {:landed "0123456789abcdef"} change-routes)) "0123456789ab<"))
    (is (str/includes? (html (chat/change-bar "p" a-change {:refused "red"} change-routes)) "Not published: red"))))

(deftest the-change-bar-carries-the-latest-landing-whatever-else-it-shows
  (let [owed    {:landing {:state :owed :commit "0123456789abcdef" :owed-at "t" :by-pid 1}
                 :waits ["1 run(s) to finish: r1"]}
        running {:landing {:state :running :commit "0123456789abcdef" :owed-at "t" :since "s1" :pid 2}}]
    (testing "owed: the commit and what it waits for, beside a publish result"
      (let [out (html (chat/change-bar "p" nil {:landed "0123456789abcdef" :restart owed} change-routes))]
        (is (str/includes? out "restarts onto"))
        (is (str/includes? out "1 run(s) to finish: r1"))))
    (testing "running: shown with no change and no publish result"
      (let [out (html (chat/change-bar "p" nil {:restart running} change-routes))]
        (is (str/includes? out "The dashboard runs"))
        (is (str/includes? out "since s1"))))
    (testing "unreadable is said, not dropped"
      (is (str/includes? (html (chat/change-bar "p" nil {:restart {:unreadable "bad"}} change-routes))
                         "cannot be read: bad")))
    (testing "no landing and nothing else: still the empty element"
      (is (= (html (chat/change-bar "p" nil {} change-routes))
             (html (chat/change-bar "p" nil {:restart {:none true}} change-routes)))))))

(deftest a-note-is-a-quiet-line
  (is (str/includes? (html (chat/chat-item {:id "i0" :kind :note :text "Edits thrown away."} routes))
                     "class=\"chat-note\"")))

(deftest a-side-panel-offers-a-new-conversation-only-when-it-can-reset
  (let [empty-t {:items [] :busy? false}
        with    (html (chat/side empty-t (assoc routes :reset-url "/r") {:open? true}))
        without (html (chat/side empty-t routes {:open? true}))]
    (testing "routes with a reset URL put the control in the bar, and only the ask posts"
      (is (str/includes? with "New conversation"))
      (is (str/includes? with "pReset: false"))
      (is (str/includes? with "data-on:click=\"$pReset = true\"") "choosing it asks first")
      (is (str/includes? with "@post(&apos;/r&apos;)") "the ask's own button posts")
      (is (str/includes? with "can&apos;t be brought back")))
    (testing "both are hidden while a turn runs"
      (is (str/includes? with "!$pReset &amp;&amp; !$pBusy"))
      (is (str/includes? with "data-show=\"$pReset &amp;&amp; !$pBusy\"")))
    (testing "without a reset URL there is no control"
      (is (not (str/includes? without "New conversation")))
      (is (not (str/includes? without "Reset"))))))
