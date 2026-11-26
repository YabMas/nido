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
     :handler handler
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
              #'acp/accepts-images? (fn [_] true)
              #'acp/prompt!       (fn [_ _ blocks]
                                    (swap! calls conj [:prompt (:text (first blocks))])
                                    (swap! calls conj [:blocks blocks])
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

(defn- from-agent
  "Deliver `msg` as the process the chat now holds would."
  [msg]
  (let [k (#'chat/chat-key c)]
    (#'chat/on-message k (:owner (#'chat/state k)) msg)))

(deftest a-first-send-starts-a-session-and-keeps-only-its-id
  (let [{:keys [calls redefs]} (fake-acp "done")
        turn-ends (atom 0)]
    (with-redefs-fn redefs
      (fn []
        (chat/send! (assoc c :on-turn-end (fn [_] (swap! turn-ends inc))) "hi" [{:label "row" :detail "block: row"}] [])
        (await-idle c)
        (Thread/sleep 50)
        (is (= [[:user "hi"] [:agent "done"]] (map (juxt :kind :text) (:items (chat/transcript c)))))
        (is (= 1 @turn-ends) "the turn's end is told once")
        (testing "the agent runs with permissions skipped and the landing commands denied"
          (is (some #(= [:mode "bypassPermissions"] %) @calls))
          (let [meta (some #(when (= :new (first %)) (second %)) @calls)]
            (is (some #{"Bash(jj git push:*)"} (get-in meta [:claudeCode :options :disallowedTools])))
            (is (= {:append "be brief"} (:systemPrompt meta)))))
        (is (some #(= [:prompt "hi\n\n<nido-context>\n[1] row\nblock: row\n</nido-context>"] %) @calls)
            "the context goes with the words")
        (is (chat/started? c))
        (is (= "{:acp-session-id \"acp-1\"}"
               (slurp (str (fs/path *home* "chats" "nido" "catalog-design" "catalog.edn"))))
            "only the session id is written down")))))

(deftest a-send-while-a-turn-runs-is-refused
  (let [{:keys [redefs]} (fake-acp "x")]
    (with-redefs-fn (assoc redefs #'acp/prompt! (fn [& _] (future (Thread/sleep 300) {:stopReason "end_turn"})))
      (fn []
        (is (true? (chat/send! c "one" [] [])))
        (Thread/sleep 50)
        (is (nil? (chat/send! c "two" [] [])))
        (is (= ["one"] (map :text (filter #(= :user (:kind %)) (:items (chat/transcript c))))))))))

(deftest a-conversation-from-an-earlier-process-is-loaded-not-restarted
  (let [{:keys [calls redefs]} (fake-acp "again")
        f (str (fs/path *home* "chats" "nido" "catalog-design" "catalog.edn"))]
    (fs/create-dirs (fs/parent f))
    (spit f (pr-str {:acp-session-id "acp-old"}))
    (with-redefs-fn redefs
      (fn []
        (is (chat/started? c))
        (chat/send! c "and now" [] [])
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
        (chat/send! c "go" [] [])
        (await-idle c)
        ;; The agent raises a request through the handler the chat gave it.
        (from-agent
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
          (chat/send! c "go" [] [])
          (await-idle c)
          (Thread/sleep 50)
          (is (pos? @heard))
          (unsub)
          (let [n @heard]
            (chat/send! c "more" [] [])
            (await-idle c)
            (Thread/sleep 50)
            (is (= n @heard))))))))

(deftest an-agent-that-exits-mid-turn-ends-it-with-an-error
  (let [{:keys [redefs]} (fake-acp "x")]
    (with-redefs-fn (assoc redefs #'acp/prompt! (fn [& _] (future (Thread/sleep 200) (throw (ex-info "the agent process exited" {})))))
      (fn []
        (chat/send! c "go" [] [])
        (Thread/sleep 50)
        (from-agent {:method "_nido/exited"})
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

(defn- id-file [] (str (fs/path *home* "chats" "nido" "catalog-design" "catalog.edn")))

(deftest a-reset-forgets-the-conversation-and-the-next-send-starts-a-new-one
  (let [{:keys [calls redefs]} (fake-acp "done")
        heard (atom nil)]
    (with-redefs-fn redefs
      (fn []
        (chat/send! c "hi" [] [])
        (await-idle c)
        (Thread/sleep 50)
        (chat/note! c "published")
        (let [unsub (chat/subscribe! c #(reset! heard %))]
          (is (true? (chat/reset! c)))
          (unsub))
        (is (= [] (:items (chat/transcript c))) "notes go with the conversation")
        (is (= [] (:items @heard)) "subscribers hear the empty transcript")
        (is (not (fs/exists? (id-file))))
        (is (not (chat/started? c)))
        (is (some #(= [:close] %) @calls) "the agent is stopped")
        (let [news (count (filter #(= :new (first %)) @calls))]
          (Thread/sleep 50)
          (is (= news (count (filter #(= :new (first %)) @calls))) "nothing starts before the next send")
          (chat/send! c "again" [] [])
          (await-idle c)
          (Thread/sleep 50)
          (is (= (inc news) (count (filter #(= :new (first %)) @calls))))
          (is (not (some #(= :load (first %)) @calls)) "the old conversation is never loaded")
          (is (= [[:user "again"] [:agent "done"]] (map (juxt :kind :text) (:items (chat/transcript c))))))))))

(deftest a-reset-while-a-turn-runs-is-refused
  (let [{:keys [calls redefs]} (fake-acp "x")]
    (with-redefs-fn (assoc redefs #'acp/prompt! (fn [& _] (future (Thread/sleep 300) {:stopReason "end_turn"})))
      (fn []
        (chat/send! c "one" [] [])
        (Thread/sleep 50)
        (is (nil? (chat/reset! c)))
        (is (fs/exists? (id-file)))
        (is (= ["one"] (map :text (:items (chat/transcript c)))))
        (is (not (some #(= [:close] %) @calls)))))))

(defn- within
  "Whether `pred` holds within ten seconds, polled."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 5) (recur))))))

(deftest a-reset-while-a-send-is-loading-its-conversation-is-refused
  ;; The load is held until the reset is seen waiting for it, and the turn's prompt until the reset
  ;; has answered, so the reset meets the send mid-load however the threads are scheduled.
  (let [{:keys [redefs]} (fake-acp "x")
        loading (promise)
        loaded  (promise)
        reset   (promise)
        tester  (Thread/currentThread)]
    (fs/create-dirs (fs/parent (id-file)))
    (spit (id-file) (pr-str {:acp-session-id "acp-old"}))
    (with-redefs-fn (-> redefs
                        (update #'acp/load-session! (fn [f] (fn [& args]
                                                              (deliver loading true)
                                                              (deref loaded 10000 nil)
                                                              (apply f args))))
                        (update #'acp/prompt! (fn [f] (fn [& args] (deref reset 10000 nil) (apply f args)))))
      (fn []
        (chat/send! c "and now" [] [])
        (is (deref loading 10000 false) "the send begins loading its conversation")
        (is (:busy? (chat/transcript c)) "the accepted send is busy while its conversation loads")
        ;; A reset waits for a start to finish: the load ends once the reset is blocked on it.
        (future (within #(= "BLOCKED" (str (.getState tester)))) (deliver loaded true))
        (is (nil? (chat/reset! c)) "an accepted send is not lost under a reset")
        (deliver reset true)
        (await-idle c)
        (Thread/sleep 50)
        (is (fs/exists? (id-file)))))))

(deftest an-old-process-cannot-touch-the-conversation-after-it
  (let [{:keys [handler redefs]} (fake-acp "x")]
    (with-redefs-fn redefs
      (fn []
        (chat/send! c "one" [] [])
        (await-idle c)
        (Thread/sleep 50)
        (let [old @handler]
          (is (true? (chat/reset! c)))
          (chat/send! c "two" [] [])
          (await-idle c)
          (Thread/sleep 50)
          (old {:method "_nido/exited"})
          (is (:conn (#'chat/state (#'chat/chat-key c))) "the new process is still held")
          (is (= [[:user "two"] [:agent "x"]] (map (juxt :kind :text) (:items (chat/transcript c))))))))))

(deftest an-old-turn-that-fails-after-a-reset-cannot-end-the-new-one
  (let [{:keys [redefs]} (fake-acp "x")
        first? (atom true)]
    (with-redefs-fn (assoc redefs #'acp/prompt!
                           (fn [& _] (if (compare-and-set! first? true false)
                                       (future (Thread/sleep 200) (throw (ex-info "the old process exited" {})))
                                       (future (Thread/sleep 400) {:stopReason "end_turn"}))))
      (fn []
        (chat/send! c "one" [] [])
        (Thread/sleep 50)
        (from-agent {:method "_nido/exited"})
        (is (nil? (chat/reset! c)) "the old turn has not ended yet")
        (Thread/sleep 250)
        (is (true? (chat/reset! c)))
        (chat/send! c "two" [] [])
        (Thread/sleep 300)
        (let [t (chat/transcript c)]
          (is (:busy? t) "the new turn is still running")
          (is (= [[:user "two"]] (map (juxt :kind :text) (:items t)))))))))

(deftest the-reaper-cannot-take-a-connection-that-replaced-the-one-it-chose
  (let [{:keys [handler redefs]} (fake-acp "x")
        other {:project "nido" :session "catalog-design" :name "other"}
        k     (#'chat/chat-key c)
        long-ago (- (System/currentTimeMillis) (* 2 @#'chat/idle-ms))]
    (with-redefs-fn (assoc redefs
                           #'acp/connect! (fn [{:keys [on-message]}] (reset! handler on-message) {:conn (Object.)})
                           ;; Closing the first chat's idle process is the moment `c` is reset and
                           ;; given a new one — after the reaper chose `c`'s old connection.
                           #'acp/close! (fn [conn]
                                          (when (= :other (:which conn))
                                            (chat/reset! c)
                                            (chat/send! c "two" [] [])
                                            (await-idle c)
                                            (Thread/sleep 50))))
      (fn []
        (chat/send! c "one" [] [])
        (await-idle c)
        (Thread/sleep 50)
        (clojure.core/reset! @#'chat/chats
                             (array-map (#'chat/chat-key other) {:conn {:which :other} :last-used long-ago}
                                        k (assoc (#'chat/state k) :last-used long-ago)))
        (#'chat/reap-idle!)
        (is (:conn (#'chat/state k)) "the new process is still held")
        (is (:owner (#'chat/state k)))))))

(deftest a-process-that-exits-before-a-replayed-message-lands-ends-the-turn
  (let [{:keys [handler redefs]} (fake-acp "x")]
    (fs/create-dirs (fs/parent (id-file)))
    (spit (id-file) (pr-str {:acp-session-id "acp-old"}))
    (with-redefs-fn (assoc redefs #'acp/set-mode!
                           ;; The last thing the start does: the process exits before the message
                           ;; that waited for the replay goes in.
                           (fn [& _] (future (Thread/sleep 50) (@handler {:method "_nido/exited"}))))
      (fn []
        (with-redefs [chat/ensure-agent! (let [f @#'chat/ensure-agent!]
                                           (fn [& args] (let [r (apply f args)] (Thread/sleep 150) r)))]
          (chat/send! c "and now" [] [])
          (Thread/sleep 400)
          (is (not (:busy? (chat/transcript c))) "the chat is not left busy")
          (is (= :error (:kind (peek (:items (chat/transcript c))))))
          (is (true? (chat/reset! c)) "a reset is taken"))))))

(deftest an-old-turns-hook-cannot-write-into-the-conversation-after-it
  (let [{:keys [redefs]} (fake-acp "x")
        first? (atom true)
        hooked (promise)
        c' (assoc c :on-turn-end (fn [chat] @hooked (chat/note-error! chat "the old preview failed")))]
    (with-redefs-fn (assoc redefs #'acp/prompt!
                           (fn [& _] (if (compare-and-set! first? true false)
                                       (future (Thread/sleep 100) (throw (ex-info "the old process exited" {})))
                                       (future (Thread/sleep 400) {:stopReason "end_turn"}))))
      (fn []
        (chat/send! c' "one" [] [])
        (Thread/sleep 50)
        (from-agent {:method "_nido/exited"})
        (Thread/sleep 100)
        (is (true? (chat/reset! c')))
        (chat/send! c' "two" [] [])
        (Thread/sleep 100)
        (deliver hooked true)
        (Thread/sleep 100)
        (let [t (chat/transcript c')]
          (is (:busy? t) "the new turn is still running")
          (is (= [[:user "two"]] (map (juxt :kind :text) (:items t)))))))))

(deftest a-send-while-another-is-starting-its-agent-is-refused
  (let [{:keys [redefs]} (fake-acp "x")]
    (fs/create-dirs (fs/parent (id-file)))
    (spit (id-file) (pr-str {:acp-session-id "acp-old"}))
    (with-redefs-fn (update redefs #'acp/load-session! (fn [f] (fn [& args] (Thread/sleep 200) (apply f args))))
      (fn []
        (is (true? (chat/send! c "one" [] [])))
        (Thread/sleep 50)
        (is (:busy? (chat/transcript c)) "the accepted send is busy while its conversation loads")
        (is (nil? (chat/send! c "two" [] [])) "a second send cannot start while the first is starting")
        (await-idle c)
        (Thread/sleep 100)
        (is (= ["earlier" "one" "x"] (map :text (:items (chat/transcript c)))))))))

(deftest a-delivery-cannot-overtake-a-reset
  (let [{:keys [redefs]} (fake-acp "done")
        paused  (promise)
        go-on   (promise)
        heard   (atom nil)]
    (with-redefs-fn redefs
      (fn []
        (chat/send! c "hi" [] [])
        (await-idle c)
        (Thread/sleep 50)
        ;; The first subscriber is slow on one delivery: the note's, made before the reset.
        (let [slow  (chat/subscribe! c (fn [t] (when (= "published" (:text (peek (:items t))))
                                                 (deliver paused true) @go-on)))
              later (chat/subscribe! c #(reset! heard %))
              noted (future (chat/note! c "published"))]
          @paused
          (let [reset (future (chat/reset! c))]
            (Thread/sleep 100)
            (deliver go-on true)
            @noted
            (is (true? @reset)))
          (Thread/sleep 50)
          (slow) (later)
          (is (= [] (:items @heard)) "the last thing a subscriber hears is the empty log"))))))

(deftest a-send-that-fails-before-its-future-still-frees-the-chat
  (let [{:keys [redefs]} (fake-acp "x")]
    (with-redefs-fn (assoc redefs #'chat/read-acp-id (fn [_] (throw (ex-info "the id went away" {}))))
      (fn []
        (try (chat/send! c "one" [] []) (catch Throwable _ nil))
        (Thread/sleep 100)
        (is (not (pos? (get (#'chat/state (#'chat/chat-key c)) :starting 0))) "the count is released")
        (is (true? (chat/reset! c)) "a reset is taken")))))

(deftest a-start-that-replays-after-the-send-chose-not-to-keeps-the-message
  (let [{:keys [redefs]} (fake-acp "x")
        slow? (atom false)]
    (with-redefs-fn (assoc redefs #'acp/prompt!
                           (let [f (get redefs #'acp/prompt!)]
                             (fn [& args] (if @slow?
                                            (future (Thread/sleep 400) {:stopReason "end_turn"})
                                            (apply f args)))))
      (fn []
        (chat/send! c "one" [] [])
        (await-idle c)
        (Thread/sleep 50)
        (clojure.core/reset! slow? true)
        ;; The process the chat holds exits just as the next send starts its agent: the start
        ;; loads the conversation again, after the message went in.
        (with-redefs [chat/ensure-agent! (let [f @#'chat/ensure-agent!]
                                           (fn [& args] (from-agent {:method "_nido/exited"}) (apply f args)))]
          (chat/send! c "two" [] [])
          (Thread/sleep 200)
          (let [t (chat/transcript c)]
            (is (:busy? t) "the turn is running")
            (is (= "two" (:text (peek (:items t)))) "the message is after the history it replayed")
            (is (nil? (chat/reset! c)) "a reset waits for the turn"))
          (await-idle c))))))

(deftest an-error-noted-after-one-turn-cannot-end-the-next
  (let [{:keys [redefs]} (fake-acp "x")
        first? (atom true)
        hooked (promise)
        c' (assoc c :on-turn-end (fn [chat] (when (compare-and-set! first? true false)
                                              @hooked (chat/note-error! chat "the preview failed"))))]
    (with-redefs-fn (assoc redefs #'acp/prompt!
                           (let [f (get redefs #'acp/prompt!) n (atom 0)]
                             (fn [& args] (if (= 2 (swap! n inc))
                                            (future (Thread/sleep 400) {:stopReason "end_turn"})
                                            (apply f args)))))
      (fn []
        (chat/send! c' "one" [] [])
        (Thread/sleep 100)
        (is (true? (chat/send! c' "two" [] [])))
        (Thread/sleep 50)
        (deliver hooked true)
        (Thread/sleep 50)
        (is (:busy? (chat/transcript c')) "the second turn still runs")
        (is (nil? (chat/reset! c')) "a reset waits for it")
        (Thread/sleep 400)))))

(deftest a-start-failure-is-not-told-to-the-process-that-replaced-it
  (let [{:keys [redefs]} (fake-acp "x")
        loads (atom 0)]
    (fs/create-dirs (fs/parent (id-file)))
    (spit (id-file) (pr-str {:acp-session-id "acp-old"}))
    (with-redefs-fn (update redefs #'acp/load-session!
                            (fn [f] (fn [& args]
                                      (if (= 1 (swap! loads inc))
                                        ;; Someone opens the panel while the first load runs: its
                                        ;; load waits for the lock, and then starts a new process.
                                        (do (future (chat/subscribe! c (fn [_])))
                                            (Thread/sleep 50)
                                            (throw (ex-info "the first process failed to load" {})))
                                        (apply f args)))))
      (fn []
        ;; The failing send tells its failure late, after the panel's load has taken the lock.
        (with-redefs [chat/fold! (let [f @#'chat/fold!]
                                   (fn [k e] (when (= "the first process failed to load" (:text e))
                                               (Thread/sleep 200))
                                     (f k e)))]
          (chat/send! c "one" [] [])
          (Thread/sleep 600)
          (is (= ["earlier"] (map :text (:items (chat/transcript c))))
              "the new process's conversation does not carry the old one's failure"))))))

(def ^:private png {:mime "image/png" :data "iVBORw0KGgo=" :digest "d"})

(deftest a-message-s-images-go-as-image-blocks-after-its-words
  (let [{:keys [calls redefs]} (fake-acp "a cat")]
    (with-redefs-fn redefs
      (fn []
        (chat/send! c "what is this?" [] [png])
        (await-idle c)
        (Thread/sleep 50)
        (is (= [{:type "text" :text "what is this?"}
                {:type "image" :mimeType "image/png" :data "iVBORw0KGgo="}]
               (some #(when (= :blocks (first %)) (second %)) @calls)))
        (is (= [png] (:images (first (:items (chat/transcript c)))))
            "the person's message carries its image")))))

(deftest a-message-without-images-is-one-text-block
  (let [{:keys [calls redefs]} (fake-acp "ok")]
    (with-redefs-fn redefs
      (fn []
        (chat/send! c "plain" [] [])
        (await-idle c)
        (Thread/sleep 50)
        (is (= [{:type "text" :text "plain"}] (some #(when (= :blocks (first %)) (second %)) @calls)))
        (is (= [] (:images (first (:items (chat/transcript c))))))))))

(deftest images-to-an-agent-that-cannot-see-them-are-not-sent
  (let [{:keys [calls redefs]} (fake-acp "x")]
    (with-redefs-fn (assoc redefs #'acp/accepts-images? (fn [_] false))
      (fn []
        (chat/send! c "look" [] [png])
        (Thread/sleep 200)
        (let [t (chat/transcript c)]
          (is (false? (:busy? t)))
          (is (= :error (:kind (peek (:items t)))))
          (is (re-find #"does not accept images" (:text (peek (:items t))))))
        (is (not (some #(= :blocks (first %)) @calls)) "nothing reached the agent")))))

(deftest a-sent-image-is-answerable-before-its-message-is-folded
  (let [{:keys [redefs]} (fake-acp "x")
        release (promise)
        fold    @#'chat/fold!]
    (with-redefs-fn (assoc redefs #'chat/fold! (fn [k e] (when (= :user (:local e)) (deref release 2000 nil)) (fold k e)))
      (fn []
        (is (true? (chat/send! c "look" [] [png])))
        (is (= png (chat/image c (:digest png))) "held from the send's acceptance")
        (deliver release true)
        (await-idle c)
        (Thread/sleep 50)
        (is (= png (chat/image c (:digest png))) "then answered from the transcript")
        (is (empty? (:held (#'chat/state (#'chat/chat-key c)))) "the hold ends with the turn")))))

(deftest an-image-stays-answerable-while-its-conversation-replays
  (let [{:keys [redefs]} (fake-acp "x")
        f        (str (fs/path *home* "chats" "nido" "catalog-design" "catalog.edn"))
        entered  (promise)
        release  (promise)
        k        (#'chat/chat-key c)]
    (fs/create-dirs (fs/parent f))
    (spit f (pr-str {:acp-session-id "acp-old"}))
    (#'chat/fold! k {:local :user :text "earlier" :refs [] :images [png]})
    (with-redefs-fn (update redefs #'acp/load-session!
                            (fn [g] (fn [& args] (deliver entered true) (deref release 2000 nil) (apply g args))))
      (fn []
        (chat/send! c "and now" [] [])
        (deref entered 2000 nil)
        (is (empty? (mapcat :images (:items (chat/transcript c)))) "the transcript is replaced for the replay")
        (is (= png (chat/image c (:digest png))) "the image it showed is still answered")
        (deliver release true)
        (await-idle c)
        (Thread/sleep 50)
        (is (nil? (chat/image c (:digest png))) "the hold ends with the load; the replay never held it")))))

(deftest a-chat-that-names-its-directory-runs-there-and-brings-no-session-up
  (let [{:keys [calls redefs]} (fake-acp "ok")
        upped (atom 0)
        redefs (assoc redefs
                      #'lifecycle/runtime-of (fn [& _] (swap! upped inc) nil)
                      #'lifecycle/up!        (fn [& _] (swap! upped inc) nil))]
    (with-redefs-fn redefs
      (fn []
        (testing "a directory it names is where its agent runs"
          (chat/send! (assoc c :dir (fn [_] "/the/dir")) "hi" [] [])
          (await-idle c)
          (is (some #(= [:connect "/the/dir"] %) @calls)))
        (chat/reset! c)
        (testing "a source that gives none fails the start as an error, starting no agent"
          (reset! calls [])
          (try (chat/send! (assoc c :dir (fn [_] nil)) "hi" [] []) (catch Throwable _ nil))
          (await-idle c)
          (is (not-any? #(= :connect (first %)) @calls))
          (is (some #(= :error (:kind %)) (:items (chat/transcript c)))))
        (is (zero? @upped) "no session is looked up or brought up")))))
