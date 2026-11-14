(ns nido.ui.catalog.chat-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core]
            [nido.session.chat :as chat]
            [nido.session.preview :as preview]
            [nido.ui.catalog.chat :as catalog-chat]
            [nido.ui.components.chat :as panel]
            [org.httpkit.server :as http]))

(def ^:private changed-events #'catalog-chat/changed-events)

(deftest a-stream-patches-only-what-changed
  (let [t0 {:items [{:id "i0" :kind :user :text "hi"}] :busy? true :started-at 1000}
        t1 (update t0 :items conj {:id "i1" :kind :agent :text "wor" :streaming? true})
        t2 (assoc-in t1 [:items 1 :text] "working")
        t3 (-> t2 (assoc :busy? false) (dissoc :started-at)
               (assoc-in [:items 1] {:id "i1" :kind :agent :text "working"}))]
    (testing "a new item is appended to the log, and the status row says what the agent now does"
      (let [[e status & more] (changed-events t0 t1)]
        (is (nil? more))
        (is (str/includes? e "data: mode append"))
        (is (str/includes? e "data: selector #catalog-chat-log"))
        (is (str/includes? e "catalog-chat-i1"))
        (is (str/includes? status "catalog-chat-status"))
        (is (str/includes? status "Writing"))))
    (testing "a changed item is morphed in place by its id, and an unchanged status is not resent"
      (let [[e & more] (changed-events t1 t2)]
        (is (nil? more))
        (is (not (str/includes? e "mode append")))
        (is (str/includes? e "working"))))
    (testing "a turn ending empties the status row and clears the busy signal"
      (let [events (changed-events t2 t3)]
        (is (= 3 (count events)))
        (is (some #(str/includes? % "catalog-chat-status") events))
        (is (some #(and (str/includes? % "datastar-patch-signals")
                        (str/includes? % (:busy (panel/signals "catalog-chat")))
                        (str/includes? % "false"))
                  events))))
    (is (empty? (changed-events t3 t3)))))

(deftest the-frame-is-the-scene-page-alone-and-the-chat-page-wraps-it
  (with-redefs [chat/started? (constantly false)]
    (is (not (str/includes? (catalog-chat/page "embed=1") "id=\"catc-frame\"")))
    (let [page (catalog-chat/page nil)]
      (is (str/includes? page "id=\"catc-frame\""))
      (is (str/includes? page "/_catalog?embed=1") "with no design session the frame is this dashboard's"))))

(deftest the-chat-starts-closed-and-opens-from-the-url
  (with-redefs [chat/started? (constantly false)]
    (let [closed (catalog-chat/page nil)
          open   (catalog-chat/page "chat=1")]
      (is (str/includes? closed "catcOpen: false"))
      (is (not (str/includes? closed "class=\"catc open\"")))
      (is (str/includes? closed "id=\"catc-launch\"") "the launcher is the one chat control while closed")
      (is (str/includes? open "catcOpen: true"))
      (is (str/includes? open "class=\"catc open\"")))))

(deftest picks-become-chips-and-a-chip-removes-its-pick
  (let [picks [{"block" "position-chip" "scene" "every position" "path" "div.cat-rail:0 > span:3"
                "text" "yours" "html" "<span>yours</span>"}
               {"block" "chat-panel" "scene" "empty" "path" "section.chat:0" "text" "" "html" "<section>"}]
        body  (fn [ps] (java.io.ByteArrayInputStream.
                        (.getBytes (cheshire.core/generate-string {(:picks (panel/signals "catalog-chat"))
                                                         (cheshire.core/generate-string ps)})
                                   "UTF-8")))
        posted (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/picks"
                                      :body (body picks)})]
    (is (= 200 (:status posted)))
    (is (str/includes? (:body posted) "catalog-chat-picks"))
    (is (str/includes? (:body posted) "position-chip · every position · span<")
        "a chip names the element by tag; the path is the agent's")
    (is (str/includes? (:body posted) "/_catalog/chat/unpick/1"))
    (let [removed (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/unpick/0"
                                         :body (body picks)})]
      (is (= 200 (:status removed)))
      (is (not (str/includes? (:body removed) "position-chip · every position")))
      (is (str/includes? (:body removed) "chat-panel · empty")))))

(defn- with-open-panel [f]
  (let [sent (atom [])
        streams @#'catalog-chat/streams
        ch (Object.)]
    (swap! streams assoc ch {})
    (try
      (with-redefs [http/send! (fn [_ e _] (swap! sent conj e))]
        (f sent))
      (finally (swap! streams dissoc ch)))))

(deftest a-finished-turn-reloads-every-open-frame-from-the-preview
  (with-open-panel
    (fn [sent]
      (with-redefs [preview/refresh! (constantly "http://localhost:4444")]
        (#'catalog-chat/refresh-frame! nil))
      (is (= 1 (count @sent)))
      (is (str/includes? (first @sent) "datastar-patch-signals"))
      (is (str/includes? (first @sent) "http://localhost:4444#")))))

(deftest a-refused-sync-is-said-in-the-conversation-and-the-frame-stays
  (let [noted (atom nil)]
    (with-open-panel
      (fn [sent]
        (with-redefs [preview/refresh! (fn [& _] (throw (ex-info "the working copies conflict" {})))
                      chat/note-error! (fn [c text] (reset! noted [(:session c) text]))]
          (#'catalog-chat/refresh-frame! nil))
        (is (empty? @sent) "no frame is reloaded")
        (is (= "catalog-design" (first @noted)) "the note goes to the catalog's conversation")
        (is (str/includes? (second @noted) "the working copies conflict"))))))
