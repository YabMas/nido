(ns nido.acp.client-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [nido.acp.client :as acp])
  (:import (java.io PipedInputStream PipedOutputStream)))

(defn- fake-agent
  "Pipes standing in for an agent process. `respond` maps each request the client sends to the
   lines the agent writes back; it runs on the agent's thread."
  [respond]
  (let [to-agent   (PipedOutputStream.)
        agent-in   (PipedInputStream. to-agent)
        from-agent (PipedOutputStream.)
        client-in  (PipedInputStream. from-agent)
        w          (io/writer from-agent)
        out!       (fn [m] (locking w (.write w (str (json/generate-string m) "\n")) (.flush w)))
        seen       (atom [])]
    (future
      (doseq [line (line-seq (io/reader agent-in))]
        (let [m (json/parse-string line true)]
          (swap! seen conj m)
          (doseq [r (respond m)] (out! r)))))
    {:streams {:in to-agent :out client-in :destroy #(.close from-agent)}
     :seen seen :out! out!}))

(defn- agent-responder [m]
  (case (:method m)
    "initialize"     [{:jsonrpc "2.0" :id (:id m) :result {:protocolVersion 1}}]
    "session/new"    [{:jsonrpc "2.0" :id (:id m) :result {:sessionId "s-1"}}]
    "session/load"   [{:jsonrpc "2.0" :method "session/update"
                       :params {:sessionId "s-1" :update {:sessionUpdate "user_message_chunk"
                                                          :content {:type "text" :text "hi"}}}}
                      {:jsonrpc "2.0" :id (:id m) :result {}}]
    "session/prompt" [{:jsonrpc "2.0" :method "session/update"
                       :params {:sessionId "s-1" :update {:sessionUpdate "agent_message_chunk"
                                                          :content {:type "text" :text "ok"}}}}
                      "not json"
                      {:jsonrpc "2.0" :id (:id m) :result {:stopReason "end_turn"}}]
    "session/set_mode" [{:jsonrpc "2.0" :id (:id m) :error {:code -32602 :message "no such mode"}}]
    []))

(deftest a-conversation-round-trips
  (let [{:keys [streams seen]} (fake-agent #(remove string? (agent-responder %)))
        got (atom [])]
    (with-redefs [acp/open-process (fn [_] streams)]
      (let [conn (acp/connect! {:command ["x"] :on-message #(swap! got conj %)})]
        (testing "a session is created and its id returned"
          (is (= "s-1" (acp/new-session! conn {:cwd "/w" :meta {:claudeCode {:options {}}}})))
          (is (= {:claudeCode {:options {}}}
                 (:_meta (:params (first (filter #(= "session/new" (:method %)) @seen)))))))
        (testing "a prompt's updates reach the handler before its result"
          (is (= "end_turn" (:stopReason @(acp/prompt! conn "s-1" "go"))))
          (is (some #(= "agent_message_chunk" (get-in % [:params :update :sessionUpdate])) @got)))
        (testing "a load replays history to the handler"
          (acp/load-session! conn "s-1" {:cwd "/w"})
          (is (some #(= "user_message_chunk" (get-in % [:params :update :sessionUpdate])) @got)))
        (testing "an error answer throws with the agent's error"
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no such mode"
                                (acp/set-mode! conn "s-1" "nope"))))
        (acp/close! conn)))))

(deftest an-agent-request-is-answered-by-id
  (let [{:keys [streams seen out!]} (fake-agent #(remove string? (agent-responder %)))
        got (promise)]
    (with-redefs [acp/open-process (fn [_] streams)]
      (let [conn (acp/connect! {:command ["x"]
                                :on-message #(when (= "session/request_permission" (:method %))
                                               (deliver got %))})]
        (out! {:jsonrpc "2.0" :id 77 :method "session/request_permission"
               :params {:sessionId "s-1" :options [{:optionId "allow" :name "Allow" :kind "allow_once"}]}})
        (is (= 77 (:id (deref got 2000 nil))))
        (acp/respond! conn 77 {:outcome {:outcome "selected" :optionId "allow"}})
        (Thread/sleep 100)
        (is (some #(and (= 77 (:id %)) (= "allow" (get-in % [:result :outcome :optionId]))) @seen))
        (acp/close! conn)))))

(deftest an-exit-fails-what-is-waiting
  (let [{:keys [streams]} (fake-agent (fn [m] (when (= "initialize" (:method m))
                                                [{:jsonrpc "2.0" :id (:id m) :result {}}])))
        exited (promise)]
    (with-redefs [acp/open-process (fn [_] streams)]
      (let [conn (acp/connect! {:command ["x"]
                                :on-message #(when (= "_nido/exited" (:method %)) (deliver exited true))})
            turn (acp/prompt! conn "s-1" "go")]
        (Thread/sleep 100)
        ((:destroy streams))
        (is (true? (deref exited 2000 nil)))
        (is (thrown? Exception @turn))))))
