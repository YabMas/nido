(ns nido.ui.side-chat-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [clojure.test :refer [deftest is testing]]
            [nido.coordinator.work.core :as wcore]
            [nido.core.images]
            [nido.core.release]
            [nido.session.change :as change]
            [nido.ui.chat-host]
            [nido.coordinator.work :as work]
            [nido.session.chat :as chat]
            [nido.test.fake-core :as fake-core]
            [nido.ui.server :as server]
            [nido.ui.side-chat :as side-chat]
            [nido.ui.views :as views]))

(deftest the-cookie-says-whether-the-next-page-opens-the-chat
  (is (side-chat/open? {:headers {"cookie" "nidoChat=1"}}))
  (is (side-chat/open? {:headers {"cookie" "a=b; nidoChat=1; c=d"}}))
  (is (not (side-chat/open? {:headers {"cookie" "nidoChat=0"}})))
  (is (not (side-chat/open? {:headers {"cookie" "xnidoChat=1"}})) "another cookie's name is not ours")
  (is (not (side-chat/open? {:headers {}})) "no cookie: closed"))

(defn- send! [headers]
  (let [sent (atom nil)]
    (with-redefs [chat/send!      (fn [_ words refs & _] (reset! sent [words refs]) true)
                  work/workstream (fn [p w _] {:project p :ws-id w
                                               :label "Side chat knows the page" :stage :design
                                               :gate {:ask {:question "Approve this design?"}
                                                      :actions [{:label "Approve"}]}})]
      (side-chat/handle! {:request-method :post :uri "/_chat/send" :headers headers
                          :body (java.io.ByteArrayInputStream.
                                 (.getBytes (json/generate-string {"chat" {"nido_chat" {"text" "approve the open one"}}})))}))
    @sent))

(deftest a-message-carries-the-place-it-was-sent-from
  (let [[words refs] (send! {"referer" "http://localhost:8800/workstreams?sel=nido:ws-1"})]
    (is (= "approve the open one" words))
    (is (= ["page · Workstreams" "workstream · nido/ws-1" "gate · open"] (map :label refs)))
    (is (some #(re-find #"Approve this design\?" (:detail %)) refs))))

(deftest a-message-whose-page-cannot-be-read-still-says-so
  (let [[_ refs] (send! {})]
    (is (= ["place · unknown"] (map :label refs)) "never a message with no place")))

(deftest the-panel-holds-nothing-about-the-page
  ;; The page used to be a signal the panel set in the browser, and Datastar applied its
  ;; data-signals after its data-init, so it always posted empty. The place is now read from the
  ;; send on the server: the panel must carry no page value for anything to depend on.
  (with-redefs [chat/transcript (constantly {:items [] :busy? false})]
    (let [p (str (h/html (side-chat/panel true nil)))]
      (is (not (str/includes? p "nidoPage")))
      (is (not (re-find #"document\.title|location\.(pathname|search|href)" p))
          "nothing read from the page in the browser"))))

(deftest every-page-carries-the-panel-as-the-person-left-it
  (with-redefs [chat/transcript (constantly {:items [] :busy? false})]
    (let [page (fn [open?] (views/shell {:title "Workstreams" :side-chat (side-chat/panel open? nil)}
                                        [:h1 "Workstreams"]))]
      (testing "closed"
        (let [p (page false)]
          (is (str/includes? p "id=\"chat:nido-chat:toggle\""))
          (is (str/includes? p "&quot;open&quot;:false"))
          (is (str/includes? p "/_chat/stream") "the panel is live on every page")))
      (testing "open"
        (is (str/includes? (page true) "class=\"chat-side open\"")))
      (testing "a page without a chat has none"
        (is (not (str/includes? (views/shell {:title "x"} [:p]) "<aside")))))))

(deftest a-workstream-path-with-no-chat-is-not-the-operators
  (with-redefs [chat/cancel!    (fn [_] (throw (ex-info "the operator's turn was cancelled" {})))]
    (is (nil? (side-chat/handle! {:request-method :post :uri "/_chat/ws/brian/ws-gone/cancel"})))))

(deftest the-chat-answers-only-its-own-paths
  (is (nil? (side-chat/handle! {:request-method :get :uri "/workstreams"})))
  (is (nil? (side-chat/handle! {:request-method :get :uri "/_chat/nowhere"})))
  (with-redefs [chat/cancel! (fn [_] nil)]
    (is (= 204 (:status (side-chat/handle! {:request-method :post :uri "/_chat/cancel"})))))
  (let [reset (atom 0)]
    (with-redefs [chat/reset! (fn [_] (swap! reset inc) nil)]
      (is (= 204 (:status (side-chat/handle! {:request-method :post :uri "/_chat/reset"}))))
      (is (= 1 @reset) "the host answers a reset for the dashboard's chat"))))

(deftest the-catalog-carries-its-own-chat-and-not-the-dashboards
  ;; The catalog sits in the dashboard's chrome but has a chat of its own; the dashboard's would be
  ;; a second panel beside it. Development, a page of the same chrome, carries the dashboard's.
  (with-redefs [chat/transcript         (constantly {:items [] :busy? false})
                chat/started?           (constantly false)
                change/pending          (constantly nil)
                server/view-state       (fn [_] {:scope "nido" :surface :development})
                server/needs-screen     (constantly {:needs-count 0})]
    (let [body (fn [uri] (:body (server/handle-request {:request-method :get :uri uri :headers {}})))]
      (is (str/includes? (body "/_catalog") "id=\"chat:catalog-chat:toggle\""))
      (is (not (str/includes? (body "/_catalog") "chat:nido-chat:toggle")))
      (is (str/includes? (body "/development") "id=\"chat:nido-chat:toggle\"")))))


(deftest the-place-a-page-shows-chooses-the-conversation
  (fake-core/with-fake-core
    (fn [c]
      (fake-core/seed! c :brian "ws-open" [{:command :post-chat :message "m1" :text "hello"}])
      (let [asked (atom [])]
        (with-redefs [chat/transcript (fn [c] (swap! asked conj (select-keys c [:project :session :name]))
                                        {:items [] :busy? false})]
          (testing "no place: nido's operator"
            (let [p (str (views/shell {:title "Needs" :side-chat (side-chat/panel true nil)} [:p]))]
              (is (re-find #"[^a-z]/_chat/stream" p))
              (is (= {:project "nido" :session "operator" :name "operator"} (last @asked)))))
          (testing "a workstream the core holds: its own chat, under a path naming it, read from the core"
            (let [p (str (views/shell {:title "Workstreams"
                                       :side-chat (side-chat/panel true {:project "brian" :ws-id "ws-open"})}
                                      [:p]))]
              (is (str/includes? p "/_chat/ws/brian/ws-open/stream"))
              (is (str/includes? p "hello") "the message the core holds is drawn")
              (is (not (re-find #"[^a-z0-9]/_chat/stream" p)))))
          (testing "a workstream the core does not hold: the operator"
            (side-chat/panel true {:project "brian" :ws-id "ws-closed"})
            (is (= "operator" (:session (last @asked))))))))))

(def ^:private png-bytes
  (byte-array (concat [0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a] (.getBytes "not much of a png"))))

(defn- post! [uri ^bytes body]
  (side-chat/handle! {:request-method :post :uri uri :headers {}
                      :body (java.io.ByteArrayInputStream. body)}))

(deftest a-workstreams-chat-is-its-own-agent-and-says-nothing-to-the-core
  (fake-core/with-fake-core
    (fn [c]
      (fake-core/seed! c :brian "ws-1" [{:command :post-chat :message "m0" :text "earlier"}])
      (let [sent (atom nil) kept (atom [])]
        (with-redefs [chat/send!              (fn [chat words refs images] (reset! sent [chat words refs images]) true)
                      wcore/keep-chat-image!  (fn [img] (swap! kept conj img) (:digest img))]
          (is (= 204 (:status (post! "/_chat/ws/brian/ws-1/attach" png-bytes))))
          (post! "/_chat/ws/brian/ws-1/send" (.getBytes "{\"chat\":{\"nido_chat\":{\"text\":\"what is in this picture?\"}}}"))
          (let [[chat words refs images] @sent
                digest (:digest (first images))]
            (testing "the message, its place and its images go to the workstream's own ACP chat"
              (is (= {:project "brian" :session "ws-1" :name "workstream"} (select-keys chat [:project :session :name])))
              (is (= "what is in this picture?" words))
              (is (= 1 (count images)))
              (is (str/includes? (:instructions chat) "nido:workstream:tell :project brian :ws-id ws-1"))
              (is (= "place · unknown" (:label (first refs))) "the place the send was read from, first")
              (is (= (str "image 1 · " (subs digest 0 12)) (:label (last refs))))
              (is (str/includes? (:detail (last refs)) digest) "the agent can name the image by digest"))
            (testing "the image is kept by the side chat, ready for a relay"
              (is (= [digest] (map :digest @kept))))
            (testing "nothing of it reaches the core"
              (is (= ["earlier"] (map :text (:chat (get @(:states c) "brian/ws-1")))))
              (is (empty? @(:sent c))))))))))

(deftest what-reached-the-conductor-sits-where-it-was-first-seen
  (let [items [{:kind :user :text "a"} {:kind :agent :text "b"} {:kind :user :text "c"}]
        chat  [{:id "m1" :from :person :text "relayed" :images ["d"]}
               {:id "r1" :from :conductor :text "noted"}
               {:id "m2" :from :person :text "unplaced"}]
        out   (#'side-chat/merge-items items chat {"m1" 2 "r1" 2})]
    (is (= ["a" "b" "relayed" "noted" "c" "unplaced"] (map :text out)))
    (is (= [:user :agent :relay :conductor :user :relay] (map :kind out)))
    (is (= ["i0" "i1" "i2" "i3" "i4" "i5"] (map :id out)))
    (is (= [{:digest "d"}] (:images (nth out 2))))))

(deftest a-relayed-image-renders-from-the-core
  (fake-core/with-fake-core
    (fn [c]
      (let [root (str (java.nio.file.Files/createTempDirectory "imgs" (make-array java.nio.file.attribute.FileAttribute 0)))
            img  (nido.ui.chat-host/parse-image {:bytes png-bytes})]
        (nido.core.images/put! root img)
        (fake-core/seed! c :brian "ws-1" [{:command :post-chat :message "m1" :text "look at this"
                                           :images [(:digest img)]}
                                          {:command :start-turn :turn "c1" :unit nil :hears ["#1 chat"]
                                           :spec {:executor :fake :instructions "i"}}
                                          {:command :reply-chat :message "r1" :text "seen it" :turn "c1"}])
        (with-redefs [chat/transcript (constantly {:items [{:id "i0" :kind :agent :text "hello"}] :busy? false})
                      chat/started?   (constantly false)
                      chat/image      (constantly nil)
                      nido.core.release/paths (fn [& _] {:images root})]
          (let [p (str (views/shell {:title "W" :side-chat (side-chat/panel true {:project "brian" :ws-id "ws-1"})} [:p]))]
            (is (str/includes? p "Passed on to the conductor"))
            (is (str/includes? p (str "/_chat/ws/brian/ws-1/image/" (:digest img))))
            (is (str/includes? p "seen it") "the conductor's reply"))
          (is (= 200 (:status (side-chat/handle! {:request-method :get
                                                  :uri (str "/_chat/ws/brian/ws-1/image/" (:digest img))}))))
          (is (= 404 (:status (side-chat/handle! {:request-method :get
                                                  :uri (str "/_chat/ws/brian/ws-1/image/" (apply str (repeat 64 "a")))})))
              "a digest the workstream's chat does not name is not served"))))))
