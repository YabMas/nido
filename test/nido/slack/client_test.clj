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

(deftest post-message-posts-threaded
  (let [captured (atom nil)]
    (with-redefs [slack/http-request
                  (fn [_ url opts] (reset! captured {:url url :body (:body opts)})
                    {:status 200 :body (json/generate-string {:ok true :ts "123.45"})})]
      (is (= {:ok true :ts "123.45"} (slack/post-message "C1" "tok" {:text "hi" :thread-ts "100.0"})))
      (let [b (json/parse-string (:body @captured) true)]
        (is (= "C1" (:channel b))) (is (= "hi" (:text b))) (is (= "100.0" (:thread_ts b)))))))

(deftest post-message-maps-auth-error
  (with-redefs [slack/http-request (fn [_ _ _] {:status 200 :body (json/generate-string
                                                                     {:ok false :error "invalid_auth"})})]
    (is (= :auth (:error (slack/post-message "C1" "tok" {:text "hi"}))))))

(deftest add-reaction-posts-reactions-add
  (let [captured (atom nil)]
    (with-redefs [slack/http-request
                  (fn [_ url opts] (reset! captured {:url url :body (:body opts)})
                    {:status 200 :body (json/generate-string {:ok true})})]
      (is (= {:ok true} (slack/add-reaction "C1" "100.0" "tok" "eyes")))
      (let [b (json/parse-string (:body @captured) true)]
        (is (= "eyes" (:name b))) (is (= "100.0" (:timestamp b)))))))

(deftest parse-id-reads-thread-and-ticket-ids-and-nothing-else
  (is (= {:channel "C07N0U273AR" :ts "1718000000.000123"
          :thread-id "slack-C07N0U273AR-1718000000.000123"}
         (slack/parse-id "slack-C07N0U273AR-1718000000.000123")))
  (is (= "login-bug"
         (:key (slack/parse-id "slack-C07N0U273AR-1718000000.000123~login-bug"))))
  (is (= "slack-C07N0U273AR-1718000000.000123"
         (:thread-id (slack/parse-id "slack-C07N0U273AR-1718000000.000123~login-bug"))))
  (is (nil? (slack/parse-id "slack-C123-1718000000.000123-2"))
      "a trailing segment is malformed, not a channel with a dash in it")
  (is (nil? (slack/parse-id "BR-1234")))
  (is (nil? (slack/parse-id nil))))

(deftest ticket-id-round-trips-and-refuses-what-would-not-parse
  (let [thread (slack/message-id "C123" "1718000000.000123")
        id     (slack/ticket-id thread "a-1")]
    (is (= {:channel "C123" :ts "1718000000.000123" :thread-id thread :key "a-1"}
           (slack/parse-id id))))
  (is (thrown? clojure.lang.ExceptionInfo (slack/ticket-id "slack-C123-1.2" "Bad Key")))
  (is (thrown? clojure.lang.ExceptionInfo (slack/ticket-id "slack-C123-1.2~a" "b"))))

(deftest conversations-replies-pages-the-thread
  (let [calls (atom [])]
    (with-redefs [slack/http-request
                  (fn [_ _ opts]
                    (swap! calls conj (:query-params opts))
                    (if (get-in opts [:query-params "cursor"])
                      (ok-resp {:messages [{:ts "1.3"}] :has_more false})
                      (ok-resp {:messages [{:ts "1.1"} {:ts "1.2"}] :has_more true
                                :response_metadata {:next_cursor "c2"}})))]
      (is (= ["1.1" "1.2" "1.3"]
             (map :ts (:messages (slack/conversations-replies "C1" "1.1" "tok")))))
      (is (= "1.1" (get (first @calls) "ts"))))))

(deftest conversations-replies-maps-errors
  (with-redefs [slack/http-request (fn [_ _ _] (err-resp "invalid_auth"))]
    (is (= :auth (:error (slack/conversations-replies "C1" "1.1" "tok"))))))

(deftest users-info-prefers-display-name-then-real-name
  (with-redefs [slack/http-request (fn [_ _ _] (ok-resp {:user {:profile {:display_name "jaap"
                                                                          :real_name "Jaap M"}}}))]
    (is (= "jaap" (slack/users-info "U1" "tok"))))
  (with-redefs [slack/http-request (fn [_ _ _] (ok-resp {:user {:profile {:display_name ""
                                                                          :real_name "Jaap M"}}}))]
    (is (= "Jaap M" (slack/users-info "U1" "tok"))))
  (with-redefs [slack/http-request (fn [_ _ _] (err-resp "user_not_found"))]
    (is (nil? (slack/users-info "U1" "tok")))))

(deftest normalise-thread-keys-by-the-root-and-transcribes-every-reply
  (let [p (slack/normalise-thread "C1"
                                  [{:ts "1.1" :user "U1" :user-name "ann" :text "the export hangs"}
                                   {:ts "1.2" :user "U2" :text "same for me"}]
                                  "1.2" "https://perma")]
    (is (= "slack-C1-1.1" (:id p)))
    (is (= "1.2" (:reacted-ts p)))
    (is (= "the export hangs" (:title p)))
    (is (= "ann: the export hangs\n\nU2: same for me" (:text p)))
    (is (= :slack-message (:adapter p)))))
