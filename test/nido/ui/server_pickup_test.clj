(ns nido.ui.server-pickup-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.pickup :as pickup]
   [nido.notion.client :as client]
   [nido.ui.server :as server]))

(defn- post [uri body]
  (server/handle-request {:request-method :post :uri uri
                          :body (java.io.ByteArrayInputStream.
                                 (.getBytes ^String body "UTF-8"))}))

(deftest pickup-post-resolves-and-patches-result
  (with-redefs [client/keychain-token (fn [] "tok")
                server/read-pickup-blocker (fn [_] nil)
                pickup/pickup! (fn [project input token]
                                 (is (= :brian project))
                                 (is (= "BR-104" input))
                                 (is (= "tok" token))
                                 {:decision :driving :continuing? true :ws-id "ws-9"
                                  :ref {:id "BR-104" :title "t"} :queued "/q/x.edn"})]
    (let [resp (post "/workstreams/pickup/brian" "{\"pickup\":\"BR-104\"}")]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "datastar-patch-elements"))
      (is (str/includes? (:body resp) "Continuing"))
      (is (str/includes? (:body resp) "/workstreams/brian/ws-9")))))

(deftest pickup-post-blank-input-short-circuits
  (with-redefs [client/keychain-token (fn [] "tok")
                server/read-pickup-blocker (fn [_] nil)
                pickup/pickup! (fn [& _] (throw (ex-info "should not be called" {})))]
    (let [resp (post "/workstreams/pickup/brian" "{\"pickup\":\"   \"}")]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Paste a Notion URL")))))

(defn- post-pickup-blocked-by [blocked-by]
  (with-redefs [client/keychain-token (fn [] "tok")
                server/read-pickup-blocker (fn [project]
                                             (is (= "brian" project))
                                             blocked-by)
                pickup/pickup! (fn [& _]
                                 {:decision :driving :continuing? false :ws-id nil
                                  :ref {:id "BR-104" :title "t"} :queued "/q/x.edn"})]
    (:body (post "/workstreams/pickup/brian" "{\"pickup\":\"BR-104\"}"))))

(deftest pickup-post-warns-with-the-reason-it-wont-run
  (is (str/includes? (post-pickup-blocked-by :daemon-down) "daemon is down"))
  (is (str/includes? (post-pickup-blocked-by :halted) "halted"))
  (is (str/includes? (post-pickup-blocked-by :breaker) "breaker")))

(deftest pickup-post-healthy-daemon-never-claims-it-is-down
  ;; The regression: an unrelated tripped trigger made the rail dot :breaker,
  ;; which the handler read as not-:up and reported as a dead daemon. The
  ;; handler must now consult the pickup leg's own blocker instead.
  (let [body (post-pickup-blocked-by nil)]
    (is (str/includes? body "Starting"))
    (is (not (str/includes? body "daemon is down")))
    (is (not (str/includes? body "\u26a0")))))

(deftest pickup-post-asks-about-the-leg-it-fires
  (let [asked (atom nil)]
    (with-redefs [client/keychain-token (fn [] "tok")
                  server/read-pickup-blocker (fn [p] (reset! asked p) nil)
                  pickup/pickup! (fn [& _]
                                   {:decision :driving :continuing? false :ws-id nil
                                    :ref {:id "BR-104" :title "t"} :queued "/q/x.edn"})]
      (post "/workstreams/pickup/brian" "{\"pickup\":\"BR-104\"}")
      (is (= "brian" @asked)))))
