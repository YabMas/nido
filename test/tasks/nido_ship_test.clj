(ns tasks.nido-ship-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.work :as work]
   [tasks.nido-ship :as sut]))

(deftest enqueue-ship-writes-a-ship-envelope
  (let [written (atom nil)]
    (with-redefs [queue/enqueue! (fn [env] (reset! written env) "/tmp/x.edn")]
      (sut/enqueue-ship! {:project :brian :session "impl-br-1" :ws-id "ws-1"})
      (is (= :ship       (:type @written)))
      (is (= :brian      (:project @written)))
      (is (= "impl-br-1" (:session @written)))
      (is (= "ws-1"      (:ws-id @written))))))

(def ^:private records
  {"impl"         {:name "impl" :weight :heavy :substrate :live}
   "ws-1--chat"   {:name "ws-1--chat" :weight :heavy :substrate :live :chat true}
   "ws-1--conductor" {:name "ws-1--conductor" :weight :heavy :substrate :live :conductor true}})

(deftest ship-refuses-non-work-session
  (with-redefs [session/read-session (fn [_ _ n] (get records n))
                work/environment     (fn [_ _] (get records "impl"))]
    (let [chat (sut/non-work-refusal :fukan "ws-1" "ws-1--chat")]
      (is (seq chat) "a chat session is refused")
      (is (some #(re-find #"bb nido:ship :project fukan impl" %) chat)
          "the refusal names the environment session to ship instead"))
    (is (seq (sut/non-work-refusal :fukan "ws-1" "ws-1--conductor")) "a conductor session is refused")
    (is (nil? (sut/non-work-refusal :fukan "ws-1" "impl")) "a work session ships as before")
    (is (nil? (sut/non-work-refusal :fukan "ws-1" "unrecorded")) "a session with no record is not refused here"))
  (with-redefs [session/read-session (fn [_ _ n] (get records n))
                work/environment     (fn [_ _] nil)]
    (is (some #(re-find #"no work session" %) (sut/non-work-refusal :fukan "ws-1" "ws-1--chat"))
        "with no environment the refusal says there is none")))
