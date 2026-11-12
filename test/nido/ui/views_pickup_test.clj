(ns nido.ui.views-pickup-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [hiccup2.core :as h]
   [nido.ui.views :as views]))

(deftest pickup-bar-renders-input-and-post-for-project
  (let [html (str (h/html (views/pickup-bar "brian")))]
    (is (str/includes? html "data-bind=\"pickup\""))
    (is (str/includes? html "/workstreams/pickup/brian"))
    (is (str/includes? html "id=\"pickup-result\""))))

(deftest pickup-result-continuing-renders-link
  (let [html (views/pickup-result-fragment
              {:decision :driving :continuing? true :ws-id "ws-42" :session "impl-br-104"
               :ref {:id "BR-104" :title "Fix login redirect"}}
              {:project "brian"})]
    (is (str/includes? html "id=\"pickup-result\""))
    (is (str/includes? html "Continuing"))
    (is (str/includes? html "BR-104"))
    (is (str/includes? html "Fix login redirect"))
    (is (str/includes? html "/workstreams/brian/ws-42"))))

(deftest pickup-result-a-fresh-start-links-its-new-workstream
  ;; The start happened at the click, so even a fresh ticket has a workstream to link — and
  ;; nothing waits on the daemon, so no warning about it can apply.
  (let [html (views/pickup-result-fragment
              {:decision :driving :continuing? false :ws-id "ws-43" :session "impl-br-104"
               :ref {:id "BR-104" :title "Fix login redirect"}}
              {:project "brian"})]
    (is (str/includes? html "Started"))
    (is (str/includes? html "/workstreams/brian/ws-43"))
    (is (not (str/includes? html "⚠")))))

(deftest pickup-result-errors-render-friendly-text
  (let [render (fn [err] (views/pickup-result-fragment {:decision :unresolved :error err}
                                                       {:project "brian"}))]
    (is (str/includes? (render :no-token) "keychain"))
    (is (str/includes? (render :not-found) "Couldn't find"))
    (is (str/includes? (render :not-a-ticket) "Couldn't find"))
    (is (str/includes? (render :unrecognized-input) "Paste a Notion URL"))
    (is (str/includes? (render :notion-error) "Notion lookup failed"))
    (is (str/includes? (render :auth) "Notion lookup failed"))
    (is (str/includes? (render :ticket-unreadable) "read the ticket"))
    (is (str/includes? (render :name-held) "another workstream"))
    (is (str/includes? (render :closed) "closed"))))
