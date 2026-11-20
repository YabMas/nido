(ns nido.ui.chat-host-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [nido.session.chat :as chat]
            [nido.ui.chat-host :as host]))

(def ^:private view {:id "t-chat" :router (host/router "/_t")})

(deftest refs-read-the-send-request-and-what-it-posted
  ;; A send's references are worked out from the request, not the body alone: what the request
  ;; itself states — the page it came from, as its Referer — is the caller's to read.
  (let [seen (atom nil) sent (atom nil)
        h    (host/host {:project "p" :session "s" :name "n"} view ["_t"]
                        (fn [req] (reset! seen req) [{:label "from" :detail (get-in req [:headers "referer"])}]))]
    (with-redefs [chat/send! (fn [_ words refs & _] (reset! sent [words refs]) true)]
      (host/handle! h {:request-method :post :uri "/_t/send"
                       :headers {"referer" "http://localhost:8800/workstreams?sel=nido:ws-1"}
                       :body (java.io.ByteArrayInputStream.
                              (.getBytes (json/generate-string {"chat" {"t_chat" {"text" "hello" "extra" 1}}})))}))
    (is (= {:text "hello"} (:fields @seen)) "the decoded fields ride along, and only the declared ones")
    (is (= ["hello" [{:label "from" :detail "http://localhost:8800/workstreams?sel=nido:ws-1"}]] @sent))))
