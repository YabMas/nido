(ns nido.session.chat-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [nido.acp.client :as acp]
            [nido.platform.core :as core]
            [nido.session.chat :as chat]
            [nido.session.lifecycle :as lifecycle]))

(def ^:dynamic *home* nil)

(use-fixtures :each
  (fn [t]
    (let [home (str (fs/create-temp-dir))]
      (reset! @#'chat/chats {})
      (with-redefs [core/nido-home (constantly home)
                    lifecycle/runtime-of (constantly {:instance-id "x"})
                    lifecycle/session-coords (constantly {:wt-path "/wt"})]
        (binding [*home* home] (t)))
      (reset! @#'chat/chats {})
      (fs/delete-tree home))))

(defn- fake-acp
  "Stub the ACP client: each prompt streams `reply` to the handler and ends its turn. Returns an
   atom of what was called."
  [reply]
  (let [calls   (atom [])
        handler (atom nil)]
    {:calls calls
     :redefs {#'acp/connect!      (fn [{:keys [on-message dir]}]
                                    (swap! calls conj [:connect dir]) (reset! handler on-message) {:fake true})
              #'acp/new-session!  (fn [_ opts] (swap! calls conj [:new (:meta opts)]) "acp-1")
              #'acp/load-session! (fn [_ id _]
                                    (swap! calls conj [:load id])
                                    (@handler {:method "session/update"
                                               :params {:update {:sessionUpdate "user_message_chunk"
                                                                 :messageId "u0"
                                                                 :content {:type "text" :text "earlier"}}}}))
              #'acp/set-mode!     (fn [_ _ mode] (swap! calls conj [:mode mode]))
              #'acp/prompt!       (fn [_ _ text]
                                    (swap! calls conj [:prompt text])
                                    (future (@handler {:method "session/update"
                                                       :params {:update {:sessionUpdate "agent_message_chunk"
                                                                         :content {:type "text" :text reply}}}})
                                            {:stopReason "end_turn"}))
              #'acp/respond!      (fn [_ id result] (swap! calls conj [:respond id result]))
              #'acp/cancel!       (fn [_ _] (swap! calls conj [:cancel]))
              #'acp/close!        (fn [_] (swap! calls conj [:close]))}}))

(defn- await-idle [c]
  (loop [n 0]
    (when (and (< n 200) (or (empty? (:items (chat/transcript c))) (:busy? (chat/transcript c))))
      (Thread/sleep 10) (recur (inc n)))))

(def ^:private c {:project "nido" :session "catalog-design" :name "catalog" :instructions "be brief"})

(deftest a-first-send-starts-a-session-and-keeps-only-its-id
  (let [{:keys [calls redefs]} (fake-acp "done")
        turn-ends (atom 0)]
    (with-redefs-fn redefs
      (fn []
        (chat/send! (assoc c :on-turn-end (fn [_] (swap! turn-ends inc))) "hi" "block: row")
        (await-idle c)
        (Thread/sleep 50)
        (is (= [[:user "hi"] [:agent "done"]] (map (juxt :kind :text) (:items (chat/transcript c)))))
        (is (= 1 @turn-ends) "the turn's end is told once")
        (testing "the agent runs with permissions skipped and the landing commands denied"
          (is (some #(= [:mode "bypassPermissions"] %) @calls))
          (let [meta (some #(when (= :new (first %)) (second %)) @calls)]
            (is (some #{"Bash(jj git push:*)"} (get-in meta [:claudeCode :options :disallowedTools])))
            (is (= {:append "be brief"} (:systemPrompt meta)))))
        (is (some #(= [:prompt "hi\n\n<nido-context>\nblock: row\n</nido-context>"] %) @calls)
            "the context goes with the words")
        (is (chat/started? c))
        (is (= "{:acp-session-id \"acp-1\"}"
               (slurp (str (fs/path *home* "chats" "nido" "catalog-design" "catalog.edn"))))
            "only the session id is written down")))))

(deftest a-send-while-a-turn-runs-is-refused
  (let [{:keys [redefs]} (fake-acp "x")]
    (with-redefs-fn (assoc redefs #'acp/prompt! (fn [& _] (future (Thread/sleep 300) {:stopReason "end_turn"})))
      (fn []
        (is (true? (chat/send! c "one" nil)))
        (Thread/sleep 50)
        (is (nil? (chat/send! c "two" nil)))
        (is (= ["one"] (map :text (filter #(= :user (:kind %)) (:items (chat/transcript c))))))))))

(deftest a-conversation-from-an-earlier-process-is-loaded-not-restarted
  (let [{:keys [calls redefs]} (fake-acp "again")
        f (str (fs/path *home* "chats" "nido" "catalog-design" "catalog.edn"))]
    (fs/create-dirs (fs/parent f))
    (spit f (pr-str {:acp-session-id "acp-old"}))
    (with-redefs-fn redefs
      (fn []
        (is (chat/started? c))
        (chat/send! c "and now" nil)
        (await-idle c)
        (Thread/sleep 50)
        (is (some #(= [:load "acp-old"] %) @calls))
        (is (not (some #(= :new (first %)) @calls)))
        (is (= [[:user "earlier"] [:user "and now"] [:agent "again"]]
               (map (juxt :kind :text) (:items (chat/transcript c))))
            "the history replays first, then this send")))))

(deftest a-permission-request-is-answered-once
  (let [{:keys [calls redefs]} (fake-acp "x")]
    (with-redefs-fn redefs
      (fn []
        (chat/send! c "go" nil)
        (await-idle c)
        ;; The agent raises a request through the handler the chat gave it.
        (#'chat/on-message (#'chat/chat-key c)
                           {:method "session/request_permission" :id 5
                            :params {:toolCall {:title "Run it"}
                                     :options [{:optionId "allow" :name "Allow" :kind "allow_once"}]}})
        (let [item (peek (:items (chat/transcript c)))]
          (chat/answer-permission! c (:id item) "allow")
          (chat/answer-permission! c (:id item) "allow")
          (is (= 1 (count (filter #(= :respond (first %)) @calls))))
          (is (= "allow" (:answer (peek (:items (chat/transcript c)))))))))))

(deftest subscribers-hear-each-change-until-they-leave
  (let [{:keys [redefs]} (fake-acp "x")
        heard (atom 0)]
    (with-redefs-fn redefs
      (fn []
        (let [unsub (chat/subscribe! c (fn [_] (swap! heard inc)))]
          (chat/send! c "go" nil)
          (await-idle c)
          (Thread/sleep 50)
          (is (pos? @heard))
          (unsub)
          (let [n @heard]
            (chat/send! c "more" nil)
            (await-idle c)
            (Thread/sleep 50)
            (is (= n @heard))))))))

(deftest an-agent-that-exits-mid-turn-ends-it-with-an-error
  (let [{:keys [redefs]} (fake-acp "x")]
    (with-redefs-fn (assoc redefs #'acp/prompt! (fn [& _] (future (Thread/sleep 200) (throw (ex-info "the agent process exited" {})))))
      (fn []
        (chat/send! c "go" nil)
        (Thread/sleep 50)
        (#'chat/on-message (#'chat/chat-key c) {:method "_nido/exited"})
        (Thread/sleep 300)
        (let [t (chat/transcript c)]
          (is (false? (:busy? t)))
          (is (= :error (:kind (peek (:items t))))))))))

(deftest an-error-met-on-the-chats-behalf-reaches-its-subscribers
  (let [heard (atom nil)
        unsub (chat/subscribe! c #(reset! heard %))]
    (chat/note-error! c "the preview did not start")
    (unsub)
    (is (= [[:error "the preview did not start"]]
           (map (juxt :kind :text) (:items @heard))))
    (is (= @heard (chat/transcript c)))))
