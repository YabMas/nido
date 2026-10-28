(ns nido.slack.socket-test
  (:require
   [babashka.http-client.websocket :as ws]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [nido.slack.client :as client]
   [nido.slack.socket :as socket]))

(defn- frame [m] (json/generate-string m))

(def reaction-frame
  (frame {:envelope_id "e1" :type "events_api"
          :payload {:event {:type "reaction_added" :reaction "owl" :user "U1"
                            :item {:type "message" :channel "C1" :ts "1.2"}}}}))

(deftest normalise-envelope-keeps-message-reactions-only
  (is (= {:ack "e1" :control nil
          :event {:channel "C1" :ts "1.2" :user "U1" :reaction "owl"}}
         (socket/normalise-envelope reaction-frame)))
  (is (= {:ack "e2" :control nil :event nil}
         (socket/normalise-envelope
          (frame {:envelope_id "e2" :type "events_api"
                  :payload {:event {:type "reaction_added" :item {:type "file"}}}})))
      "a reaction on a file is acked and dropped")
  (is (= :disconnect (:control (socket/normalise-envelope (frame {:type "disconnect"})))))
  (is (= {:ack nil :control nil :event nil} (socket/normalise-envelope "not json"))))

(defn- wait-for [pred]
  (loop [n 0] (cond (pred) true (> n 200) false :else (do (Thread/sleep 10) (recur (inc n))))))

(deftest connect-acks-every-envelope-and-reconnects-after-a-drop
  (let [acks (atom []) events (atom []) statuses (atom []) opens (atom 0)]
    (with-redefs [client/http-request  (fn [_ _ _] {:status 200 :body (frame {:ok true :url "wss://x"})})
                  ws/send!             (fn [_ data] (swap! acks conj (json/parse-string data true)))
                  ws/close!            (fn [& _] nil)
                  socket/open-websocket
                  (fn [{:keys [on-message on-close]}]
                    (swap! opens inc)
                    (future
                      (on-message :sock (frame {:type "hello"}) true)
                      ;; A frame split across two messages is reassembled before it is read.
                      (on-message :sock (subs reaction-frame 0 20) false)
                      (on-message :sock (subs reaction-frame 20) true)
                      (on-close :sock 1000 "bye"))
                    :sock)]
      (let [close (socket/connect! "xapp" #(swap! events conj %) #(swap! statuses conj %))]
        (is (wait-for #(>= @opens 2)) "reopened after the connection closed")
        (close)
        (is (= {:envelope_id "e1"} (first @acks)))
        (is (= {:channel "C1" :ts "1.2" :user "U1" :reaction "owl"} (first @events)))
        (is (some #(= :open (:state %)) @statuses))
        (is (some #(= :closed (:state %)) @statuses))))))

(deftest connect-stops-on-an-auth-refusal
  (let [statuses (atom []) calls (atom 0)]
    (with-redefs [client/http-request (fn [_ _ _] (swap! calls inc)
                                        {:status 200 :body (frame {:ok false :error "invalid_auth"})})
                  socket/open-websocket (fn [_] (throw (ex-info "must not open" {})))]
      (socket/connect! "xapp" (fn [_]) #(swap! statuses conj %))
      (is (wait-for #(some (fn [s] (= :refused (:state s))) @statuses)))
      (Thread/sleep 50)
      (is (= 1 @calls) "no retry on a token Slack refused"))))
