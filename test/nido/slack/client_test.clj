(ns nido.slack.client-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [nido.slack.client :as slack]))

(defn- ok-resp [m] {:status 200 :body (json/generate-string (assoc m :ok true))})
(defn- err-resp [error] {:status 200 :body (json/generate-string {:ok false :error error})})

(deftest message-id-is-stable-and-fs-safe
  (is (= "slack-C123-1718000000.000123"
         (slack/message-id "C123" "1718000000.000123"))))

(deftest normalise-message-builds-the-event-payload
  (let [msg {:type "message" :ts "1718000000.000123" :user "U1"
             :text "login button is broken"}
        p   (slack/normalise-message "C123" msg "https://x.slack.com/archives/C123/p1718000000000123")]
    (is (= :slack-message (:adapter p)))
    (is (= "slack-C123-1718000000.000123" (:id p)))
    (is (= "1718000000.000123" (:ts p)))
    (is (= "C123" (:channel p)))
    (is (= "U1" (:user p)))
    (is (= "login button is broken" (:text p)))
    (is (= "login button is broken" (:title p)))
    (is (= "https://x.slack.com/archives/C123/p1718000000000123" (:url p)))))

(deftest normalise-message-truncates-the-title
  (let [long-text (apply str (repeat 200 "x"))
        p (slack/normalise-message "C123" {:ts "1.1" :text long-text} "u")]
    (is (<= (count (:title p)) 80))
    (is (= long-text (:text p)))))

(deftest normalise-message-handles-nil-text
  (let [p (slack/normalise-message "C123" {:ts "1.0"} nil)]
    (is (= "" (:text p)))
    (is (= "" (:title p)))))

(deftest conversations-history-success
  (with-redefs [slack/http-request (fn [_ _ _] (ok-resp {:messages [{:ts "2.0"} {:ts "1.0"}]
                                                         :has_more false}))]
    (let [r (slack/conversations-history "C123" "tok" {})]
      (is (= 2 (count (:messages r))))
      (is (false? (:has_more r)))
      (is (nil? (:error r))))))

(deftest conversations-history-maps-invalid-auth-to-auth-error
  (with-redefs [slack/http-request (fn [_ _ _] (err-resp "invalid_auth"))]
    (is (= :auth (:error (slack/conversations-history "C123" "tok" {}))))))

(deftest conversations-history-maps-429-to-rate-limit
  (with-redefs [slack/http-request (fn [_ _ _] {:status 429 :body ""})]
    (is (= :rate-limit (:error (slack/conversations-history "C123" "tok" {}))))))

(deftest conversations-history-maps-5xx-to-server
  (with-redefs [slack/http-request (fn [_ _ _] {:status 503 :body ""})]
    (is (= :server (:error (slack/conversations-history "C123" "tok" {}))))))

(deftest conversations-history-maps-network-to-network
  (with-redefs [slack/http-request (fn [_ _ _] {:status 0})]
    (is (= :network (:error (slack/conversations-history "C123" "tok" {}))))))

(deftest conversations-history-other-ok-false-is-api-error
  (with-redefs [slack/http-request (fn [_ _ _] (err-resp "not_in_channel"))]
    (let [r (slack/conversations-history "C123" "tok" {})]
      (is (= :api (:error r)))
      (is (= "not_in_channel" (:detail r))))))

(deftest chat-permalink-returns-url
  (with-redefs [slack/http-request (fn [_ _ _] (ok-resp {:permalink "https://x.slack.com/p"}))]
    (is (= "https://x.slack.com/p" (slack/chat-permalink "C123" "1.0" "tok")))))
