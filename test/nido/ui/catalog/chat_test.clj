(ns nido.ui.catalog.chat-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core]
            [nido.session.change :as change]
            [nido.session.chat :as chat]
            [nido.session.preview :as preview]
            [nido.ui.catalog.chat :as catalog-chat]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]
            [org.httpkit.server :as http]))

(defn- changed-events [old new] (host/changed-events @#'catalog-chat/routes old new))

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

(defn- chrome [] {:needs-count 0 :daemon {:state :up} :scope "nido" :projects ["nido"]})

(deftest the-frame-is-the-scene-page-alone-and-the-chat-page-wraps-it
  (with-redefs [chat/started? (constantly false)
                change/pending (constantly nil)]
    (is (not (str/includes? (catalog-chat/page "embed=1" #(throw (ex-info "the frame renders no chrome" {}))) "id=\"catc-frame\"")))
    (let [page (catalog-chat/page nil chrome)]
      (is (str/includes? page "id=\"catc-frame\""))
      (is (str/includes? page "/_catalog?embed=1") "with no design session the frame is this dashboard's")
      (is (str/includes? page "class=\"topbar\"") "the page around the frame sits in the dashboard's chrome")
      (is (str/includes? page "class=\"content bleed\"") "and fills its content area"))))

(deftest the-chat-starts-closed-and-opens-from-the-url
  (with-redefs [chat/started? (constantly false)
                change/pending (constantly nil)]
    (let [closed (catalog-chat/page nil chrome)
          open   (catalog-chat/page "chat=1" chrome)]
      (is (str/includes? closed "catalogchatOpen: false"))
      (is (not (str/includes? closed "class=\"catc open\"")))
      (is (str/includes? closed "id=\"catalog-chat-toggle\"") "one toggle opens the chat")
      (is (str/includes? closed "aria-expanded=\"false\""))
      (is (not (str/includes? closed "catc-close")) "and nothing else closes it")
      (is (str/includes? open "aria-expanded=\"true\""))
      (is (str/includes? open "catalogchatOpen: true"))
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
    (is (str/includes? (:body posted) "chat-pick-l\">span<")
        "a chip names the element by tag alone; the path is the agent's")
    (is (str/includes? (:body posted) "title=\"position-chip · every position · span\"")
        "where the element sits is on hover")
    (is (str/includes? (:body posted) "/_catalog/chat/unpick/1"))
    (let [removed (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/unpick/0"
                                         :body (body picks)})]
      (is (= 200 (:status removed)))
      (is (not (str/includes? (:body removed) "position-chip · every position")))
      (is (str/includes? (:body removed) "chat-panel · empty")))))

(defn- with-open-panel [f]
  (let [sent (atom [])
        streams (:streams @#'catalog-chat/chat-host)
        ch (Object.)]
    (swap! streams assoc ch {})
    (try
      (with-redefs [http/send! (fn [_ e _] (swap! sent conj e))]
        (f sent))
      (finally (swap! streams dissoc ch)))))

(deftest a-finished-turn-reloads-every-open-frame-from-the-preview
  (with-open-panel
    (fn [sent]
      (with-redefs [preview/refresh! (constantly nil)
                    change/pending   (constantly nil)]
        (#'catalog-chat/refresh-frame! nil))
      (is (= 2 (count @sent)))
      (is (str/includes? (first @sent) "datastar-patch-signals"))
      (is (str/includes? (first @sent) "catcFrame"))
      (is (not (str/includes? (first @sent) "localhost")) "the frame reloads from the dashboard's own origin")
      (is (str/includes? (second @sent) "catalog-chat-change") "the change bar follows the turn"))))

(deftest a-frame-open-before-a-reload-is-still-refreshed-after-it
  ;; `require :reload` rebuilds the host; a page whose stream opened before it must still be
  ;; reached by the next finished turn, or it stops refreshing until it reconnects.
  (with-open-panel
    (fn [sent]
      (require 'nido.ui.catalog.chat :reload)
      (with-redefs [preview/refresh! (constantly nil)
                    change/pending   (constantly nil)]
        (#'catalog-chat/refresh-frame! nil))
      (is (some #(str/includes? % "catcFrame") @sent) "the frame opened before the reload is reloaded"))))

(deftest a-refused-sync-is-said-in-the-conversation-and-the-frame-stays
  (let [noted (atom nil)]
    (with-open-panel
      (fn [sent]
        (with-redefs [preview/refresh! (fn [& _] (throw (ex-info "the working copies conflict" {})))
                      change/pending   (constantly nil)
                      chat/note-error! (fn [c text] (reset! noted [(:session c) text]))]
          (#'catalog-chat/refresh-frame! nil))
        (is (not-any? #(str/includes? % "catcFrame") @sent) "no frame is reloaded")
        (is (= "catalog-design" (first @noted)) "the note goes to the catalog's conversation")
        (is (str/includes? (second @noted) "the working copies conflict"))))))

(deftest the-frames-requests-are-forwarded-once-the-conversation-exists
  (let [forwarded (atom [])]
    (with-redefs [preview/forward! (fn [_ _ req] (swap! forwarded conj (:uri req)) {:status 200 :body "preview"})]
      (testing "before the design conversation exists the dashboard answers itself"
        (with-redefs [chat/started? (constantly false)]
          (is (nil? (catalog-chat/handle! {:request-method :get :uri "/_catalog" :query-string "embed=1"})))))
      (with-redefs [chat/started? (constantly true)]
        (testing "the scene page and a scene's post go to the preview"
          (is (= "preview" (:body (catalog-chat/handle! {:request-method :get :uri "/_catalog"
                                                         :query-string "block=chat&embed=1"}))))
          (is (= "preview" (:body (catalog-chat/handle! {:request-method :post
                                                         :uri "/_catalog/nowhere/chat/send"})))))
        (testing "the catalog page itself is the dashboard's"
          (is (nil? (catalog-chat/handle! {:request-method :get :uri "/_catalog" :query-string "chat=1"}))))))
    (is (= ["/_catalog" "/_catalog/nowhere/chat/send"] @forwarded))))

(defn- settle-publish []
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (while (and (:running? @@#'catalog-chat/publishing) (< (System/currentTimeMillis) deadline))
      (Thread/sleep 10))))

(deftest publish-follows-the-verb-and-says-how-it-ended
  (let [notes (atom [])]
    (with-open-panel
      (fn [sent]
        (reset! @#'catalog-chat/publishing {})
        (with-redefs [chat/transcript  (constantly {:items [] :busy? false})
                      chat/note!       (fn [_ text] (swap! notes conj text))
                      change/pending   (constantly nil)
                      preview/refresh! (constantly nil)
                      change/publish!  (fn [_ _ on-line]
                                         (doseq [l ["land · fetching origin" "land · running bb nido:test at abc"
                                                    "Ran 12 tests" "land ok · 0123456789ab is on origin's main"]]
                                           (on-line l))
                                         0)]
          (is (= 204 (:status (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/publish"}))))
          (settle-publish))
        (is (= {:landed "0123456789ab"} @@#'catalog-chat/publishing))
        (is (some #(str/includes? % "running bb nido:test") @sent) "each step reaches the bar as it starts")
        (is (= ["Published to main as 0123456789ab."] @notes))))))

(deftest a-refused-publish-names-why
  (with-open-panel
    (fn [_]
      (reset! @#'catalog-chat/publishing {})
      (with-redefs [chat/transcript  (constantly {:items [] :busy? false})
                    chat/note!       (constantly nil)
                    change/pending   (constantly nil)
                    preview/refresh! (constantly nil)
                    change/publish!  (fn [_ _ on-line] (on-line "land REFUSED · bb nido:test is red at the tip") 1)]
        (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/publish"})
        (settle-publish))
      (is (= {:refused "bb nido:test is red at the tip"} @@#'catalog-chat/publishing)))))

(deftest nothing-is-published-or-thrown-away-while-a-turn-runs
  (let [touched (atom [])]
    (reset! @#'catalog-chat/publishing {})
    (with-redefs [chat/transcript (constantly {:items [] :busy? true})
                  change/publish! (fn [& _] (swap! touched conj :publish) 0)
                  change/revert!  (fn [& _] (swap! touched conj :revert))]
      (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/publish"})
      (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/revert"}))
    (is (empty? @touched))))

(deftest cancel-throws-the-change-away-and-notes-it
  (let [notes (atom []) reverted (atom 0)]
    (with-open-panel
      (fn [_]
        (reset! @#'catalog-chat/publishing {})
        (with-redefs [chat/transcript  (constantly {:items [] :busy? false})
                      chat/note!       (fn [_ text] (swap! notes conj text))
                      change/pending   (constantly nil)
                      preview/refresh! (constantly nil)
                      change/revert!   (fn [& _] (swap! reverted inc))]
          (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/revert"}))))
    (is (= 1 @reverted))
    (is (= {:reverted? true} @@#'catalog-chat/publishing))
    (is (= 1 (count @notes)))
    (reset! @#'catalog-chat/publishing {})))
