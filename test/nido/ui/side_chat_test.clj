(ns nido.ui.side-chat-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.coordinator.work :as work]
            [nido.session.change :as change]
            [nido.session.chat :as chat]
            [nido.ui.server :as server]
            [nido.ui.side-chat :as side-chat]
            [nido.ui.views :as views]))

(deftest the-cookie-says-whether-the-next-page-opens-the-chat
  (is (side-chat/open? {:headers {"cookie" "nidoChat=1"}}))
  (is (side-chat/open? {:headers {"cookie" "a=b; nidoChat=1; c=d"}}))
  (is (not (side-chat/open? {:headers {"cookie" "nidoChat=0"}})))
  (is (not (side-chat/open? {:headers {"cookie" "xnidoChat=1"}})) "another cookie's name is not ours")
  (is (not (side-chat/open? {:headers {}})) "no cookie: closed"))

(deftest a-message-names-the-page-it-was-sent-from
  (let [page-ref @#'side-chat/page-ref]
    (is (= [{:label "page · Workstreams"
             :detail "Sent from nido's dashboard, on Workstreams — nido — /workstreams/nido/ws-1"}]
           (page-ref {"nidoPage" "Workstreams — nido — /workstreams/nido/ws-1"})))
    (is (nil? (page-ref {})) "a page that did not say carries nothing")))

(deftest every-page-carries-the-panel-as-the-person-left-it
  (with-redefs [chat/transcript (constantly {:items [] :busy? false})]
    (let [page (fn [open?] (views/shell {:title "Workstreams" :side-chat (side-chat/panel open? nil)}
                                        [:h1 "Workstreams"]))]
      (testing "closed"
        (let [p (page false)]
          (is (str/includes? p "id=\"nido-chat-toggle\""))
          (is (str/includes? p "nidochatOpen: false"))
          (is (str/includes? p "/_chat/stream") "the panel is live on every page")))
      (testing "open"
        (is (str/includes? (page true) "class=\"chat-side open\"")))
      (testing "a page without a chat has none"
        (is (not (str/includes? (views/shell {:title "x"} [:p]) "<aside")))))))

(deftest the-place-a-page-shows-chooses-the-conversation
  (let [asked (atom [])]
    (with-redefs [chat/transcript (fn [c] (swap! asked conj (select-keys c [:project :session :name]))
                                    {:items [] :busy? false})
                  work/chat-place (fn [_ ws-id] (when (= "ws-open" ws-id) {:session "ws-open--chat"}))]
      (testing "no place: nido's operator"
        (let [p (str (views/shell {:title "Needs" :side-chat (side-chat/panel true nil)} [:p]))]
          (is (re-find #"[^a-z]/_chat/stream" p))
          (is (= {:project "nido" :session "operator" :name "operator"} (last @asked)))))
      (testing "an open workstream: its own chat, under a path naming it"
        (let [p (str (views/shell {:title "Workstreams"
                                   :side-chat (side-chat/panel true {:project "brian" :ws-id "ws-open"})}
                                  [:p]))]
          (is (str/includes? p "/_chat/ws/brian/ws-open/stream"))
          (is (not (re-find #"[^a-z0-9]/_chat/stream" p)))
          (is (= {:project "brian" :session "ws-open--chat" :name "workstream"} (last @asked)))))
      (testing "a workstream with no chat to offer — closed, or a row no workstream stands behind: the operator"
        (side-chat/panel true {:project "brian" :ws-id "ws-closed"})
        (is (= "operator" (:session (last @asked))))))))

(deftest a-workstreams-chat-is-briefed-on-its-workstream-and-brought-up-through-the-work-plane
  (let [sent (atom nil) opened (atom [])]
    (with-redefs [work/chat-place (fn [_ _] {:session "ws-1--chat"})
                  work/open-chat! (fn [p id] (swap! opened conj [p id]) {:session "ws-1--chat"})
                  chat/send!      (fn [c text _] (reset! sent [c text]) true)]
      (side-chat/handle! {:request-method :post :uri "/_chat/ws/brian/ws-1/send"
                          :body (java.io.ByteArrayInputStream. (.getBytes "{\"nidochatText\": \"where does it stand?\"}"))})
      (let [[c text] @sent]
        (is (= "where does it stand?" text))
        (is (= {:project "brian" :session "ws-1--chat" :name "workstream"} (select-keys c [:project :session :name])))
        (is (str/includes? (:instructions c) "workstream ws-1 of project brian") "it is told which workstream it is in")
        (is (str/includes? (:instructions c) "nido:workstream:tell") "and how its word reaches the conductor")
        ((:ready! c) c)
        (is (= [["brian" "ws-1"]] @opened) "its session is the work plane's to bring up")))))

(deftest a-workstream-path-with-no-chat-is-not-the-operators
  (with-redefs [work/chat-place (constantly nil)
                chat/cancel!    (fn [_] (throw (ex-info "the operator's turn was cancelled" {})))]
    (is (nil? (side-chat/handle! {:request-method :post :uri "/_chat/ws/brian/ws-gone/cancel"})))))

(deftest the-chat-answers-only-its-own-paths
  (is (nil? (side-chat/handle! {:request-method :get :uri "/workstreams"})))
  (is (nil? (side-chat/handle! {:request-method :get :uri "/_chat/nowhere"})))
  (with-redefs [chat/cancel! (fn [_] nil)]
    (is (= 204 (:status (side-chat/handle! {:request-method :post :uri "/_chat/cancel"}))))))

(deftest the-catalog-carries-its-own-chat-and-not-the-dashboards
  ;; The catalog sits in the dashboard's chrome but has a chat of its own; the dashboard's would be
  ;; a second panel beside it. Development, a page of the same chrome, carries the dashboard's.
  (with-redefs [chat/transcript         (constantly {:items [] :busy? false})
                chat/started?           (constantly false)
                change/pending          (constantly nil)
                server/view-state       (fn [_] {:scope "nido" :surface :development})
                server/needs-screen     (constantly {:needs-count 0})]
    (let [body (fn [uri] (:body (server/handle-request {:request-method :get :uri uri :headers {}})))]
      (is (str/includes? (body "/_catalog") "id=\"catalog-chat-toggle\""))
      (is (not (str/includes? (body "/_catalog") "nido-chat-toggle")))
      (is (str/includes? (body "/development") "id=\"nido-chat-toggle\"")))))
