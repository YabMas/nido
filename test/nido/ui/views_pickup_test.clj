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
              {:decision :driving :continuing? true :ws-id "ws-42"
               :ref {:id "BR-104" :title "Fix login redirect"}}
              {:project "brian" :blocked-by nil :trigger :plan-bug})]
    (is (str/includes? html "id=\"pickup-result\""))
    (is (str/includes? html "Continuing"))
    (is (str/includes? html "BR-104"))
    (is (str/includes? html "Fix login redirect"))
    (is (str/includes? html "/workstreams/brian/ws-42"))
    (is (not (str/includes? html "daemon is down")))))

(deftest pickup-result-starting-fresh-has-no-link
  (let [html (views/pickup-result-fragment
              {:decision :driving :continuing? false :ws-id nil
               :ref {:id "BR-104" :title "Fix login redirect"}}
              {:project "brian" :blocked-by nil :trigger :plan-bug})]
    (is (str/includes? html "Starting"))
    (is (str/includes? html "new workstream"))
    (is (not (str/includes? html "/workstreams/brian/")))))

(defn- queued-with [blocked-by]
  (views/pickup-result-fragment
   {:decision :driving :continuing? false :ws-id nil
    :ref {:id "BR-104" :title "t"}}
   {:project "brian" :blocked-by blocked-by :trigger :plan-bug}))

(deftest pickup-result-warns-with-the-reason-it-wont-run
  ;; Each blocker needs its own fix, so each gets its own copy. The bug was
  ;; every non-:up state rendering as "daemon is down".
  (is (str/includes? (queued-with :daemon-down) "daemon is down"))
  (let [halted (queued-with :halted)]
    (is (str/includes? halted "halted"))
    (is (str/includes? halted "coordinator:resume"))
    (is (not (str/includes? halted "daemon is down"))))
  (let [breaker (queued-with :breaker)]
    (is (str/includes? breaker "plan-bug"))
    (is (str/includes? breaker "breaker"))
    (is (str/includes? breaker "trigger:enable :project brian plan-bug"))
    (is (not (str/includes? breaker "daemon is down")))))

(deftest pickup-result-unblocked-warns-about-nothing
  (let [html (queued-with nil)]
    (is (str/includes? html "Starting"))
    (is (not (str/includes? html "⚠")))))

(deftest pickup-result-errors-render-friendly-text
  (let [render (fn [err] (views/pickup-result-fragment
                          {:decision :unresolved :error err}
                          {:project "brian" :blocked-by nil :trigger :plan-bug}))]
    (is (str/includes? (render :no-token) "keychain"))
    (is (str/includes? (render :not-found) "Couldn't find"))
    (is (str/includes? (render :not-a-ticket) "Couldn't find"))
    (is (str/includes? (render :unrecognized-input) "Paste a Notion URL"))
    (is (str/includes? (render :notion-error) "Notion lookup failed"))
    (is (str/includes? (render :auth) "Notion lookup failed"))))
