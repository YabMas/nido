(ns nido.ui.catalog.chat-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.session.chat :as chat]
            [nido.session.preview :as preview]
            [nido.ui.catalog.chat :as catalog-chat]
            [org.httpkit.server :as http]))

(def ^:private changed-events #'catalog-chat/changed-events)

(deftest a-stream-patches-only-what-changed
  (let [t0 {:items [{:id "i0" :kind :user :text "hi"}] :busy? true}
        t1 (update t0 :items conj {:id "i1" :kind :agent :text "wor" :streaming? true})
        t2 (assoc-in t1 [:items 1] {:id "i1" :kind :agent :text "working"})
        t3 (assoc t2 :busy? false)]
    (testing "a new item is appended to the log"
      (let [[e & more] (changed-events t0 t1)]
        (is (nil? more))
        (is (str/includes? e "data: mode append"))
        (is (str/includes? e "data: selector #catalog-chat-log"))
        (is (str/includes? e "catalog-chat-i1"))))
    (testing "a changed item is morphed in place by its id"
      (let [[e & more] (changed-events t1 t2)]
        (is (nil? more))
        (is (not (str/includes? e "mode append")))
        (is (str/includes? e "working"))))
    (testing "a turn ending re-renders the composer, and nothing else"
      (let [[e & more] (changed-events t2 t3)]
        (is (nil? more))
        (is (str/includes? e "catalog-chat-compose"))
        (is (str/includes? e ">Send<"))))
    (is (empty? (changed-events t3 t3)))))

(deftest the-frame-is-the-scene-page-alone-and-the-chat-page-wraps-it
  (with-redefs [chat/started? (constantly false)]
    (is (not (str/includes? (catalog-chat/page "embed=1") "id=\"catc-frame\"")))
    (let [page (catalog-chat/page nil)]
      (is (str/includes? page "id=\"catc-frame\""))
      (is (str/includes? page "/_catalog?embed=1") "with no design session the frame is this dashboard's"))))

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
