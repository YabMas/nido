(ns nido.ui.server-pickup-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.pickup :as pickup]
   [nido.notion.client :as client]
   [nido.ui.server :as server]))

(defn- post [uri body]
  (server/handle-request {:request-method :post :uri uri
                          :body (java.io.ByteArrayInputStream.
                                 (.getBytes ^String body "UTF-8"))}))

(deftest pickup-post-resolves-and-patches-result
  (with-redefs [client/keychain-token (fn [] "tok")
                server/read-rail-daemon (fn [] {:state :up})
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
                server/read-rail-daemon (fn [] {:state :up})
                pickup/pickup! (fn [& _] (throw (ex-info "should not be called" {})))]
    (let [resp (post "/workstreams/pickup/brian" "{\"pickup\":\"   \"}")]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Paste a Notion URL")))))

(deftest pickup-post-daemon-down-warns
  (with-redefs [client/keychain-token (fn [] "tok")
                server/read-rail-daemon (fn [] {:state :down})
                pickup/pickup! (fn [& _]
                                 {:decision :driving :continuing? false :ws-id nil
                                  :ref {:id "BR-104" :title "t"} :queued "/q/x.edn"})]
    (let [resp (post "/workstreams/pickup/brian" "{\"pickup\":\"BR-104\"}")]
      (is (str/includes? (:body resp) "daemon is down")))))
