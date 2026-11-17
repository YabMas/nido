(ns nido.ui.chat-host-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [nido.session.chat :as chat]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]))

(def ^:private routes
  {:id "t-chat" :send-url "/_t/send" :cancel-url "/_t/cancel" :stream-url "/_t/stream"
   :answer-url (fn [i o] (str "/_t/permission/" i "/" o)) :unpick-url (fn [i] (str "/_t/unpick/" i))})

(deftest refs-read-the-send-request-and-what-it-posted
  ;; A send's references are worked out from the request, not the body alone: what the request
  ;; itself states — the page it came from, as its Referer — is the caller's to read.
  (let [seen (atom nil) sent (atom nil)
        h    (host/host {:project "p" :session "s" :name "n"} routes ["_t"]
                        (fn [req] (reset! seen req) [{:label "from" :detail (get-in req [:headers "referer"])}]))
        text (:text (panel/signals "t-chat"))]
    (with-redefs [chat/send! (fn [_ words refs & _] (reset! sent [words refs]) true)]
      (host/handle! h {:request-method :post :uri "/_t/send"
                       :headers {"referer" "http://localhost:8800/workstreams?sel=nido:ws-1"}
                       :body (java.io.ByteArrayInputStream.
                              (.getBytes (json/generate-string {text "hello" "extra" 1})))}))
    (is (= {text "hello" "extra" 1} (:json @seen)) "the posted body rides along as :json")
    (is (= ["hello" [{:label "from" :detail "http://localhost:8800/workstreams?sel=nido:ws-1"}]] @sent))))
