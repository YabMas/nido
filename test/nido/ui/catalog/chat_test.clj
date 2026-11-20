(ns nido.ui.catalog.chat-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core]
            [nido.coordinator.control :as control]
            [nido.session.change :as change]
            [nido.session.chat :as chat]
            [nido.session.preview :as preview]
            [nido.ui.catalog.chat :as catalog-chat]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]
            [org.httpkit.server :as http]))

(defn- changed-events [old new] (host/changed-events @#'catalog-chat/view old new))

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
        (is (str/includes? e "data: selector #chat\\:catalog-chat\\:log"))
        (is (str/includes? e "chat:catalog-chat:item-i1"))
        (is (str/includes? status "chat:catalog-chat:status"))
        (is (str/includes? status "Writing"))))
    (testing "a changed item is morphed in place by its id, and an unchanged status is not resent"
      (let [[e & more] (changed-events t1 t2)]
        (is (nil? more))
        (is (not (str/includes? e "mode append")))
        (is (str/includes? e "working"))))
    (testing "a turn ending empties the status row and clears the busy signal"
      (let [events (changed-events t2 t3)]
        (is (= 3 (count events)))
        (is (some #(str/includes? % "chat:catalog-chat:status") events))
        (is (some #(and (str/includes? % "datastar-patch-signals")
                        (str/includes? % "{\"chat\":{\"catalog_chat\":{\"busy\":false}}}"))
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
      (is (str/includes? closed "&quot;open&quot;:false"))
      (is (not (str/includes? closed "class=\"catc open\"")))
      (is (str/includes? closed "id=\"chat:catalog-chat:toggle\"") "one toggle opens the chat")
      (is (str/includes? closed "aria-expanded=\"false\""))
      (is (not (str/includes? closed "catc-close")) "and nothing else closes it")
      (is (str/includes? open "aria-expanded=\"true\""))
      (is (str/includes? open "&quot;open&quot;:true"))
      (is (str/includes? open "class=\"catc open\"")))))

(deftest picks-become-chips-and-a-chip-removes-its-pick
  (let [picks [{"block" "position-chip" "scene" "every position" "path" "div.scene-rail:0 > span:3"
                "text" "yours" "html" "<span>yours</span>"}
               {"block" "chat-panel" "scene" "empty" "path" "section.chat:0" "text" "" "html" "<section>"}]
        body  (fn [ps] (java.io.ByteArrayInputStream.
                        (.getBytes (cheshire.core/generate-string
                                    {"chat" {"catalog_chat" {"picks" (cheshire.core/generate-string ps)}}})
                                   "UTF-8")))
        posted (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/picks"
                                      :body (body picks)})]
    (is (= 200 (:status posted)))
    (is (str/includes? (:body posted) "chat:catalog-chat:picks"))
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
      (is (str/includes? (second @sent) "chat-change:catalog-chat:root") "the change bar follows the turn"))))

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

(defn- publish-req [rev]
  {:request-method :post :uri "/_catalog/chat/publish" :query-string (str "rev=" rev)})

(defmacro ^:private with-catalog
  "A quiet catalog: no turn running, the worktree already on main, notes collected in `notes`."
  [[notes] & body]
  `(let [~notes (atom [])]
     (reset! @#'catalog-chat/publishing {})
     (reset! @#'catalog-chat/noted-conflicts nil)
     (with-redefs [chat/transcript   (constantly {:items [] :busy? false})
                   chat/note!        (fn [_# text#] (swap! ~notes conj text#))
                   change/pending    (constantly nil)
                   change/landing    (constantly nil)
                   change/onto-main! (constantly [])
                   preview/refresh!  (constantly nil)]
       ~@body)))

(deftest publish-lands-the-revision-it-was-shown-and-says-how-it-ended
  (with-open-panel
    (fn [sent]
      (with-catalog [notes]
        (let [landed-rev (atom nil)]
          (with-redefs [change/seal!    (fn [_ _ rev] (when (= "abc1" rev) "s1"))
                        change/publish! (fn [_ _ rev on-line]
                                          (reset! landed-rev rev)
                                          (doseq [l ["land · fetching origin" "land · running bb nido:test at abc"
                                                     "Ran 12 tests" "land pushed · 0123456789ab"
                                                     "land ok · 0123456789ab is on origin's main"]]
                                            (on-line l))
                                          0)]
            (is (= 204 (:status (catalog-chat/handle! (publish-req "abc1")))))
            (settle-publish))
          (is (= "s1" @landed-rev) "the verb is told the sealed revision")
          (is (= {:landed "0123456789ab"} @@#'catalog-chat/publishing) "back on main, the seal is forgotten")
          (is (some #(str/includes? % "running bb nido:test") @sent) "each step reaches the bar as it starts")
          (is (= ["Published to main as 0123456789ab."] @notes)))))))

(deftest a-change-that-moved-since-the-bar-showed-it-is-not-sealed
  (with-open-panel
    (fn [_]
      (with-catalog [_]
        (let [published (atom 0)]
          (with-redefs [change/seal!    (constantly nil)
                        change/publish! (fn [& _] (swap! published inc) 0)]
            (catalog-chat/handle! (publish-req "dead"))
            (settle-publish))
          (is (zero? @published))
          (is (not (:running? @@#'catalog-chat/publishing)))
          (testing "a press that names no revision publishes nothing"
            (with-redefs [change/seal! (fn [& _] (swap! published inc) "s")]
              (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/publish"})
              (settle-publish))
            (is (zero? @published))))))))

(deftest a-message-is-accepted-while-the-change-lands
  (with-open-panel
    (fn [_]
      (with-catalog [_]
        (let [lock     @#'catalog-chat/worktree-lock
              accepted (atom [])
              guard    (:guard @#'catalog-chat/chat-host)]
          (with-redefs [change/seal!    (constantly "s1")
                        change/publish! (fn [_ _ _ on-line]
                                          (swap! accepted conj [:before-load (.tryAcquire lock)])
                                          (on-line "land · fetching origin")
                                          (swap! accepted conj [:after-load (guard (constantly :sent))])
                                          (on-line "land pushed · abc")
                                          0)]
            (catalog-chat/handle! (publish-req "abc1"))
            (settle-publish))
          (is (= [[:before-load false] [:after-load :sent]] @accepted)
              "a send waits only while the verb loads its code from the sealed tree"))))))

(deftest a-refused-publish-names-why-and-keeps-every-edit
  (with-open-panel
    (fn [_]
      (with-catalog [notes]
        (with-redefs [change/seal!    (constantly "s1")
                      change/publish! (fn [_ _ _ on-line]
                                        (on-line "land · fetching origin")
                                        (on-line "land REFUSED · bb nido:test is red at the tip") 1)]
          (catalog-chat/handle! (publish-req "abc1"))
          (settle-publish))
        (is (= {:refused "bb nido:test is red at the tip"} @@#'catalog-chat/publishing))
        (is (str/includes? (first @notes) "Nothing was pushed"))
        (is (str/includes? (first @notes) "the ones made since Publish included"))))))

(deftest a-push-origin-took-is-a-landing-whatever-failed-after-it
  (with-open-panel
    (fn [_]
      (with-catalog [notes]
        (with-redefs [change/seal!    (constantly "s1")
                      change/publish! (fn [_ _ _ on-line]
                                        (on-line "land pushed · 0123456789ab")
                                        (on-line "land:record REFUSED · the design no longer stands") 1)]
          (catalog-chat/handle! (publish-req "abc1"))
          (settle-publish))
        (is (= "0123456789ab" (:landed @@#'catalog-chat/publishing)))
        (is (str/includes? (first @notes) "Published to main as 0123456789ab."))
        (is (str/includes? (first @notes) "the design no longer stands"))))))

(deftest the-bar-shows-the-sealed-arc-until-the-worktree-rejoins
  (with-catalog [_]
    (with-redefs [change/landing          (fn [_ _ sealed] {:description (str "landing " sealed) :files []})
                  control/latest-landing  (constantly nil)]
      (doseq [p [{:running? true :step "pushing main" :sealed "s1"} {:landed "0123456789ab" :sealed "s1"}]]
        (reset! @#'catalog-chat/publishing p)
        (is (= {:description "landing s1" :files []} (:landing (#'catalog-chat/bar-value))) (pr-str p)))
      (reset! @#'catalog-chat/publishing {:landed "0123456789ab"})
      (is (nil? (:landing (#'catalog-chat/bar-value))) "back on main, nothing is shown as landing"))))

(deftest a-rejoin-waits-for-the-turn-and-says-its-conflicts-once
  (with-open-panel
    (fn [_]
      (with-catalog [notes]
        (let [rebased (atom 0)]
          (with-redefs [change/onto-main! (fn [& _] (swap! rebased inc) ["a.clj"])]
            (testing "not while a turn runs"
              (with-redefs [chat/transcript (constantly {:items [] :busy? true})]
                (#'catalog-chat/rejoin!))
              (is (zero? @rebased)))
            (testing "not while a publish runs"
              (swap! @#'catalog-chat/publishing assoc :running? true)
              (#'catalog-chat/rejoin!)
              (swap! @#'catalog-chat/publishing dissoc :running?)
              (is (zero? @rebased)))
            (swap! @#'catalog-chat/publishing assoc :sealed "s1")
            (#'catalog-chat/refresh-frame! nil)
            (#'catalog-chat/refresh-frame! nil)
            (is (= 2 @rebased) "every turn's end asks")
            (is (nil? (:sealed @@#'catalog-chat/publishing)))
            (is (= 1 (count @notes)) "the same conflicts are said once")
            (is (str/includes? (first @notes) "a.clj"))))))))

(deftest a-rejoin-that-finds-the-worktree-held-is-made-when-it-is-released
  (with-open-panel
    (fn [_]
      (with-catalog [_]
        (let [rebased (atom 0)]
          (with-redefs [change/onto-main! (fn [& _] (swap! rebased inc) [])]
            ;; A send holds the worktree while a turn ends, then starts no turn of its own.
            (#'catalog-chat/with-worktree-lock
             (fn []
               (#'catalog-chat/refresh-frame! nil)
               (is (zero? @rebased) "not while the send holds the worktree")))
            (is (= 1 @rebased) "the turn's rejoin is made once the send releases it")))))))

(deftest nothing-is-published-or-thrown-away-while-a-turn-runs
  (let [touched (atom [])]
    (reset! @#'catalog-chat/publishing {})
    (with-redefs [chat/transcript (constantly {:items [] :busy? true})
                  change/seal!    (fn [& _] (swap! touched conj :seal) "s")
                  change/publish! (fn [& _] (swap! touched conj :publish) 0)
                  change/revert!  (fn [& _] (swap! touched conj :revert))]
      (catalog-chat/handle! (publish-req "abc1"))
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

(deftest a-conversation-started-over-redraws-the-whole-log
  (let [t0 {:items [{:id "i0" :kind :user :text "hi"} {:id "i1" :kind :agent :text "done"}] :busy? false}
        es (changed-events t0 {:items [] :busy? false})]
    (is (= 3 (count es)) "the log, the status row and the busy signal")
    (is (str/includes? (first es) "id=\"chat:catalog-chat:log\""))
    (is (not (str/includes? (first es) "chat:catalog-chat:item-i0")) "no earlier item is left on the page")
    (is (not (str/includes? (first es) "mode append")))))

(deftest a-reset-is-the-hosts-to-answer
  (let [reset (atom 0)]
    (with-redefs [chat/reset! (fn [_] (swap! reset inc) true)]
      (is (= 204 (:status (catalog-chat/handle! {:request-method :post :uri "/_catalog/chat/reset"}))))
      (is (= 1 @reset)))))
