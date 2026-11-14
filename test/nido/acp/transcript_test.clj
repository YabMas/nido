(ns nido.acp.transcript-test
  (:require [clojure.test :refer [deftest is testing]]
            [nido.acp.transcript :as t]))

(defn- upd [u] {:method "session/update" :params {:sessionId "s" :update u}})

(defn- run [& events] (reduce t/fold (t/empty-transcript) events))

(deftest a-turn-reads-as-items
  (let [tr (run {:local :user :text "make it blue" :refs [{:label "row · ordinary" :detail "html"}] :at 1000}
                (upd {:sessionUpdate "agent_thought_chunk" :content {:type "text" :text "think"}})
                (upd {:sessionUpdate "agent_message_chunk" :content {:type "text" :text "On "}})
                (upd {:sessionUpdate "agent_message_chunk" :content {:type "text" :text "it."}})
                (upd {:sessionUpdate "tool_call" :toolCallId "tc1" :title "Edit row.clj" :kind "edit"
                      :status "pending"})
                (upd {:sessionUpdate "tool_call_update" :toolCallId "tc1" :status "completed"
                      :content [{:type "diff" :path "row.clj" :oldText "a" :newText "b"}]}))]
    (is (= [:user :thought :agent :tool] (map :kind (:items tr))))
    (is (= ["i0" "i1" "i2" "i3"] (map :id (:items tr))))
    (is (:busy? tr) "a turn is running until it ends")
    (testing "chunks of one kind grow one item, and a new kind settles the one before"
      (is (= "On it." (:text (nth (:items tr) 2))))
      (is (not (:streaming? (nth (:items tr) 2)))))
    (testing "a tool-call update merges into its call"
      (is (= {:status :completed :tool-kind :edit :diffs [{:path "row.clj" :old "a" :new "b"}]}
             (select-keys (nth (:items tr) 3) [:status :tool-kind :diffs]))))
    (testing "the person's message keeps the labels of what it came with, and the turn when it started"
      (is (= [{:label "row · ordinary"}] (:refs (first (:items tr)))))
      (is (= 1000 (:started-at tr))))))

(deftest a-streaming-reply-settles-when-the-turn-ends
  (let [tr (run {:local :user :text "hi"}
                (upd {:sessionUpdate "agent_message_chunk" :content {:type "text" :text "Hel"}}))]
    (is (:streaming? (peek (:items tr))))
    (let [done (t/fold tr {:local :turn-ended})]
      (is (not (:streaming? (peek (:items done)))))
      (is (false? (:busy? done)))
      (is (not (contains? done :started-at)) "a finished turn has no start time"))))

(deftest a-permission-request-is-an-item-until-answered
  (let [tr (run {:method "session/request_permission" :id 9
                 :params {:toolCall {:title "Run bb nido:test"}
                          :options [{:optionId "allow" :name "Allow" :kind "allow_once"}
                                    {:optionId "deny" :name "Deny" :kind "reject_once"}]}})
        item (peek (:items tr))]
    (is (= {:kind :permission :title "Run bb nido:test" :request-id 9 :answer nil}
           (select-keys item [:kind :title :request-id :answer])))
    (is (= ["allow" "deny"] (map :id (:options item))))
    (is (= "deny" (:answer (peek (:items (t/fold tr {:local :answered :item-id (:id item)
                                                     :option-id "deny"}))))))))

(deftest replayed-history-groups-by-message
  (let [tr (run (upd {:sessionUpdate "user_message_chunk" :messageId "u1" :content {:type "text" :text "a"}})
                (upd {:sessionUpdate "user_message_chunk" :messageId "u1" :content {:type "text" :text "b"}})
                (upd {:sessionUpdate "agent_message_chunk" :messageId "m1" :content {:type "text" :text "x"}})
                (upd {:sessionUpdate "agent_message_chunk" :messageId "m2" :content {:type "text" :text "y"}}))]
    (is (= [[:user "ab"] [:agent "x"] [:agent "y"]] (map (juxt :kind :text) (:items tr))))))

(deftest what-nido-does-not-read-changes-nothing
  (let [tr (run {:local :user :text "hi"})]
    (is (= tr (t/fold tr (upd {:sessionUpdate "usage_update" :used 1 :size 2}))))
    (is (= tr (t/fold tr {:method "_auth/status_update"})))
    (is (= tr (t/fold tr (upd {:sessionUpdate "tool_call_update" :toolCallId "nope" :status "failed"}))))))

(deftest an-error-ends-the-turn
  (let [tr (run {:local :user :text "hi"} {:local :error :text "agent exited"})]
    (is (= :error (:kind (peek (:items tr)))))
    (is (false? (:busy? tr)))))

(deftest a-replayed-prompt-reads-back-as-words-and-references
  (let [sent (t/prompt-text "make it blue" [{:label "row · ordinary · div:0" :detail "[2] is not a label here"}
                                            {:label "ask · open"}])
        tr   (run (upd {:sessionUpdate "user_message_chunk" :messageId "u1"
                        :content {:type "text" :text (subs sent 0 5)}})
                  (upd {:sessionUpdate "user_message_chunk" :messageId "u1"
                        :content {:type "text" :text (subs sent 5)}}))]
    (is (= ["make it blue" [{:label "row · ordinary · div:0"} {:label "ask · open"}]]
           ((juxt :text :refs) (first (:items tr)))))
    (is (= "plain" (t/prompt-text "plain" [])))
    (is (= [] (:refs (first (:items (run (upd {:sessionUpdate "user_message_chunk" :messageId "u1"
                                                :content {:type "text" :text "plain"}})))))))))
