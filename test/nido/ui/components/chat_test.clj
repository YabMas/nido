(ns nido.ui.components.chat-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hiccup2.core :as h]
            [nido.libraries.ui.block :as block]
            [nido.ui.components.chat :as chat]))

(defn- router [action params]
  (str "/" (name action) (apply str (for [[_ v] (sort-by key params)] (str "/" v)))))

(defn- html [x] (str (h/html x)))

(defn- part
  "One part of the panel `p`, as a stream patches it."
  [k props]
  (block/render chat/panel "p" (merge {:transcript {:items []} :part k} props) {} router))

(defn- item [it] (part :item {:item it}))

(defn- status [& items]
  (html (part :status {:transcript {:busy? true :started-at 1000
                                    :items (into [{:id "i0" :kind :user :text "hi" :refs []}] items)}})))

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
  (testing "a polite live region, under the name the whole panel gives it"
    (is (str/includes? (status) "aria-live=\"polite\""))
    (is (str/includes? (status) "id=\"chat:p:status\""))))

(deftest no-turn-running-is-no-status
  (let [row (html (part :status {:transcript {:busy? false :items [{:id "i0" :kind :agent :text "done"}]}}))]
    (is (not (str/includes? row "chat-status-l")))
    (is (not (str/includes? row "chat-spin")))))

(deftest what-opens-and-what-folds
  (let [item #(html (item (assoc % :id "i1")))]
    (is (str/includes? (item {:kind :thought :text "…" :streaming? true}) "open"))
    (is (not (str/includes? (item {:kind :thought :text "…"}) "open")))
    (is (not (str/includes? (item {:kind :tool :title "t" :status :completed :body "ok" :diffs []}) "open")))
    (is (str/includes? (item {:kind :tool :title "t" :status :failed :body "no" :diffs []}) "open")
        "a failed tool call opens itself")
    (is (str/includes? (item {:kind :note :text "x"}) "id=\"chat:p:item-i1\"")
        "an item's id is derived from the panel's instance and its transcript id")))

(deftest a-permission-answer-is-routed-by-item-and-option
  (let [out (html (item {:id "i2" :kind :permission :title "Run" :answer nil
                                   :options [{:id "allow" :name "Allow" :kind :allow_once}]}))]
    (is (str/includes? out "@post(&apos;/permission/i2/allow&apos;"))))

(deftest picks-are-numbered-and-each-removable
  (let [chips (html (part :picks {:picks [{:label "a"} {:label "b"}]}))]
    (is (str/includes? chips "id=\"chat:p:picks\""))
    (is (str/includes? chips "/unpick/0"))
    (is (str/includes? chips "/unpick/1"))
    (is (re-find #"chat-pick-n\">1<.*chat-pick-l\">a<.*chat-pick-n\">2<.*chat-pick-l\">b<" chips)))
  (testing "a sent message shows what it carried, without the remove control"
    (let [msg (html (item {:id "i0" :kind :user :text "hi" :refs [{:label "a"}]}))]
      (is (str/includes? msg "chat-pick-l\">a<"))
      (is (not (str/includes? msg "/unpick/0"))))))

(defn- decode [body id] (block/decode-action chat/panel :send body id))

(deftest a-send-is-read-back-through-the-panels-decoder
  (is (= {:ok {:text "hi"}} (decode "{\"chat\":{\"p\":{\"text\":\"hi\"}}}" "p")))
  (is (= {:ok {:text "hi" :picks "[]"}}
         (decode "{\"chat\":{\"p\":{\"text\":\"hi\",\"picks\":\"[]\",\"busy\":false}}}" "p"))
      "only the declared fields are read")
  (is (= :missing (:failure (decode "{\"chat\":{\"q\":{\"text\":\"hi\"}}}" "p")))
      "another panel's draft is not this one's"))

(def ^:private a-change {:description "Name the thing\n\nbody" :files [{:path "a.clj" :status :modified}]
                         :rev "r1" :conflicts []})

(defn- bar [change publishing]
  (html (block/render chat/change-bar "p" {:change change :publishing publishing} {} router)))

(deftest the-change-bar-renders-what-is-unlanded-and-how-a-publish-went
  (testing "no change and nothing to say is an empty element"
    (is (re-find #"<section [^>]*id=\"chat-change:p:root\"[^>]*></section>$" (bar nil {}))))
  (testing "a change names its files and what it would land as, with Publish and Cancel confirmed in place"
    (let [out (bar a-change {})]
      (is (str/includes? out "1 file changed"))
      (is (str/includes? out "Name the thing"))
      (is (not (str/includes? out "body")) "only the subject line")
      (is (str/includes? out "@post(&apos;/publish?rev=r1&apos;") "Publish carries the revision it showed")
      (is (str/includes? out "@post(&apos;/revert&apos;"))
      (is (str/includes? out "data-attr:disabled=\"$chat.p.busy\"")
          "withheld while the panel's turn runs")))
  (testing "an undescribed change cannot be published"
    (is (str/includes? (bar (assoc a-change :description " ") {}) "data-attr:disabled=\"true\"")))
  (testing "a running publish shows its step and what it lands, apart from what was made since"
    (let [out (bar (assoc a-change :description "Newer")
                   {:running? true :step "pushing main"
                    :landing {:description "Landing it" :files [{:path "b.clj" :status :added}]}})]
      (is (str/includes? out "Publishing · pushing main"))
      (is (str/includes? out "1 file landing"))
      (is (str/includes? out "Landing it"))
      (is (str/includes? out "1 file changed since Publish"))
      (is (str/includes? out "Newer"))
      (is (not (str/includes? out "data-attr:disabled=\"$chat.p.busy\"")) "nothing can be published or thrown away meanwhile")))
  (testing "a landed publish keeps what it landed apart from what was made since, until the worktree rejoins"
    (let [out (bar (assoc a-change :description "Newer")
                   {:landed "0123456789abcdef"
                    :landing {:description "Landing it" :files [{:path "b.clj" :status :added}]}})]
      (is (str/includes? out "Published <code>0123456789ab"))
      (is (str/includes? out "1 file landed"))
      (is (str/includes? out "Landing it"))
      (is (str/includes? out "1 file changed since Publish"))
      (is (str/includes? out "Newer"))))
  (testing "a conflicted change names its paths and cannot be published"
    (let [out (bar (assoc a-change :conflicts ["a.clj"]) {})]
      (is (str/includes? out "Conflicts with main in <code>a.clj</code>"))
      (is (not (re-find #"btn-primary\" data-attr:disabled=\"\$chat\.p\.busy" out)))))
  (testing "how the last publish ended"
    (is (str/includes? (bar nil {:landed "0123456789abcdef"}) "0123456789ab<"))
    (is (str/includes? (bar a-change {:refused "red"}) "Not published: red"))))

(deftest the-change-bar-carries-the-latest-landing-whatever-else-it-shows
  (let [owed    {:landing {:state :owed :commit "0123456789abcdef" :owed-at "t" :by-pid 1}
                 :waits ["1 run(s) to finish: r1"]}
        running {:landing {:state :running :commit "0123456789abcdef" :owed-at "t" :since "s1" :pid 2}}]
    (testing "owed: the commit and what it waits for, beside a publish result"
      (let [out (bar nil {:landed "0123456789abcdef" :restart owed})]
        (is (str/includes? out "restarts onto"))
        (is (str/includes? out "1 run(s) to finish: r1"))))
    (testing "running: shown with no change and no publish result"
      (let [out (bar nil {:restart running})]
        (is (str/includes? out "The dashboard runs"))
        (is (str/includes? out "since s1"))))
    (testing "unreadable is said, not dropped"
      (is (str/includes? (bar nil {:restart {:unreadable "bad"}}) "cannot be read: bad")))
    (testing "no landing and nothing else: still the empty element"
      (is (= (bar nil {}) (bar nil {:restart {:none true}}))))))

(deftest a-note-is-a-quiet-line
  (is (str/includes? (html (item {:id "i0" :kind :note :text "Edits thrown away."}))
                     "class=\"chat-note\"")))

(defn- side [props]
  (html (block/render chat/panel "p" (merge {:transcript {:items [] :busy? false} :open? true} props)
                      {} router)))

(deftest a-side-panel-offers-a-new-conversation-only-when-it-can-reset
  (let [with    (side {:reset? true})
        without (side {})]
    (testing "a panel that can reset puts the control in the bar, and only the ask posts"
      (is (str/includes? with "New conversation"))
      (is (str/includes? with "data-on:click=\"$chat.p.reset = true\"") "choosing it asks first")
      (is (str/includes? with "@post(&apos;/reset&apos;") "the ask's own button posts")
      (is (str/includes? with "can&apos;t be brought back")))
    (testing "both are hidden while a turn runs"
      (is (str/includes? with "!$chat.p.reset &amp;&amp; !$chat.p.busy"))
      (is (str/includes? with "data-show=\"$chat.p.reset &amp;&amp; !$chat.p.busy\"")))
    (testing "without it there is no control"
      (is (not (str/includes? without "New conversation")))
      (is (not (str/includes? without "/reset"))))))

(deftest the-panels-page-state-is-seeded-only-where-it-is-missing
  ;; client-state-is-declared: the open panel, the draft and the busy flag are the client's, so a
  ;; patch of the panel never resets them.
  (let [out (side {:transcript {:items [] :busy? true}})]
    (is (str/includes? out "data-signals__ifmissing"))
    (is (re-find #"&quot;busy&quot;:true" out))
    (is (re-find #"&quot;open&quot;:true" out))
    (is (not (re-find #"data-signals=" out)) "the panel writes no data-signals of its own")))

(deftest only-a-live-panel-opens-a-stream
  (is (str/includes? (side {:live? true}) "data-init=\"@get(&apos;/stream&apos;"))
  (is (not (str/includes? (side {}) "/stream"))))

(deftest no-component-writes-a-datastar-action-literal
  ;; actions-are-data: a block declares its actions and the library spells them, so no
  ;; component namespace may write the request itself.
  (doseq [f (->> (file-seq (io/file "src/nido/ui/components"))
                 (filter #(.isFile %)) (map str) (filter #(str/ends-with? % ".clj")))]
    (testing f
      (is (not (re-find #"@(post|get)\(" (slurp f)))))))
