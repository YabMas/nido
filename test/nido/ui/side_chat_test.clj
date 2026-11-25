(ns nido.ui.side-chat-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hiccup2.core :as h]
            [nido.coordinator.work :as work]
            [nido.coordinator.work.core :as wcore]
            [nido.platform.core :as core]
            [nido.session.change :as change]
            [nido.session.chat :as chat]
            [nido.session.lifecycle :as lifecycle]
            [nido.session.state :as state]
            [nido.test.fake-core :as fake-core]
            [nido.ui.server :as server]
            [nido.ui.side-chat :as side-chat]
            [nido.ui.views :as views]))

;; Every test gets a store of its own, workstreams the work plane knows, and conversations that
;; start nothing.
(def ^:private sent (atom []))

(use-fixtures :each
  (fn [t]
    (let [home (str (fs/create-temp-dir {:prefix "side-chat-test"}))]
      (reset! sent [])
      (try
        (with-redefs [core/nido-root    (constantly home)
                      chat/started?     (constantly false)
                      chat/transcript   (fn [c] {:items [] :busy? false :chat (:name c)})
                      chat/subscribe!   (fn [& _] (fn []))
                      chat/send!        (fn [c words refs images]
                                          (swap! sent conj {:chat (:name c) :words words :refs refs :images images})
                                          true)
                      work/workstream   (fn [p w _] {:project p :ws-id w :label (str "Workstream " w)
                                                     :stage :design})]
          (t))
        (finally (fs/delete-tree home))))))

(defn- at [path] {"referer" (str "http://localhost:8800" path)})

(defn- post!
  ([uri path] (post! uri path nil))
  ([uri path body]
   (side-chat/handle! {:request-method :post :uri uri :headers (at path)
                       :body (when body (java.io.ByteArrayInputStream. (.getBytes ^String body)))})))

(defn- page-panel
  "The panel the page at `path` draws, as markup."
  [path]
  (let [[uri q] (str/split path #"\?" 2)]
    (str (h/html (side-chat/panel {:uri uri :query-string q :headers {}} true)))))

(defn- chats-shown
  "The ids of the chats the page at `path` shows, in order."
  [path]
  (map second (re-seq #"data-chat=\"([^\"]+)\"" (page-panel path))))

(defn- new-chat!
  "Start a chat from the page at `path`; its id."
  [path]
  (let [before (set (chats-shown path))]
    (post! "/_chat/new" path)
    (first (remove before (chats-shown path)))))

(defn- say!
  "Send `words` from chat `id`, from the page at `path`."
  [id path words]
  (post! (str "/_chat/c/" id "/send") path
         (json/generate-string {"chat" {(str "chat_" (str/replace id "-" "_")) {"text" words}}})))

(defn- log-of [id] (#'side-chat/read-log id))

(deftest the-cookie-says-whether-the-next-page-opens-the-chat
  (is (side-chat/open? {:headers {"cookie" "nidoChat=1"}}))
  (is (side-chat/open? {:headers {"cookie" "a=b; nidoChat=1; c=d"}}))
  (is (not (side-chat/open? {:headers {"cookie" "nidoChat=0"}})))
  (is (not (side-chat/open? {:headers {"cookie" "xnidoChat=1"}})) "another cookie's name is not ours")
  (is (not (side-chat/open? {:headers {}})) "no cookie: closed"))

(deftest a-chat-stays-at-the-workstream-it-was-started-on
  (let [id (new-chat! "/workstreams/nido/ws-1")]
    (is id)
    (is (= [{:event :started :anchor {:project "nido" :ws-id "ws-1"}}]
           (map #(dissoc % :at) (log-of id))))
    (testing "it is shown wherever that workstream is open, whatever entry or page shows it"
      (is (= [id] (chats-shown "/workstreams/nido/ws-1")))
      (is (= [id] (chats-shown "/workstreams/nido/ws-1?entry=4")))
      (is (= [id] (chats-shown "/workstreams?sel=nido:ws-1")))
      (is (= [id] (chats-shown "/?sel=nido:ws-1"))))
    (testing "and nowhere else"
      (is (empty? (chats-shown "/workstreams/nido/ws-2")))
      (is (empty? (chats-shown "/workstreams")))
      (is (empty? (chats-shown "/operations"))))))

(deftest a-chat-on-a-page-with-no-workstream-is-pinned-to-its-path
  (let [id (new-chat! "/operations/recovery?scope=nido")]
    (is (= {:path "/operations/recovery"} (:anchor (first (log-of id)))))
    (is (= [id] (chats-shown "/operations/recovery")))
    (is (empty? (chats-shown "/operations")))))

(deftest an-unlinked-chat-follows-the-person-until-it-is-pinned-again
  (let [id (new-chat! "/workstreams/nido/ws-1")]
    (post! (str "/_chat/unlink/" id) "/workstreams/nido/ws-1")
    (testing "unlinked, it is shown on every page"
      (is (= [id] (chats-shown "/workstreams/nido/ws-2")))
      (is (= [id] (chats-shown "/operations"))))
    (testing "a message from it names the place it was sent from, and that it follows"
      (say! id "/operations" "what broke?")
      (let [{:keys [chat refs]} (last @sent)]
        (is (= id chat))
        (is (= ["page · Operations" "chat · follows the person"] (map :label refs)))))
    (testing "pinned again from another page, it is that page's chat and no other's"
      (post! (str "/_chat/pin/" id) "/workstreams?sel=nido:ws-2")
      (is (= [id] (chats-shown "/workstreams/nido/ws-2")))
      (is (empty? (chats-shown "/workstreams/nido/ws-1")))
      (is (empty? (chats-shown "/operations")))
      (say! id "/workstreams/nido/ws-2" "and here?")
      (is (= "chat · pinned to nido/ws-2" (:label (last (:refs (last @sent)))))))
    (testing "its story reads back in order, its start unchanged"
      (is (= [[:started {:project "nido" :ws-id "ws-1"}]
              [:unlinked {:project "nido" :ws-id "ws-1"}]
              [:pinned {:project "nido" :ws-id "ws-2"}]]
             (map (juxt :event :anchor) (log-of id)))))))

(deftest a-chats-story-outlives-the-dashboard
  ;; The log is on disk, apart from the conversation: what a fresh read sees is what was written.
  (let [id (new-chat! "/workstreams/nido/ws-1")]
    (post! (str "/_chat/unlink/" id) "/workstreams/nido/ws-1")
    (let [f (str (fs/path (core/nido-root) "side-chats" id "anchor.log"))]
      (is (= [:started :unlinked]
             (map (comp :event read-string) (str/split-lines (slurp f))))))))

(deftest several-chats-at-one-place-are-each-their-own
  (let [a (new-chat! "/workstreams/nido/ws-1")
        b (new-chat! "/workstreams/nido/ws-1")]
    (is (not= a b))
    (is (= #{a b} (set (chats-shown "/workstreams/nido/ws-1"))))
    (say! a "/workstreams/nido/ws-1" "one")
    (say! b "/workstreams/nido/ws-1" "two")
    (is (= [[a "one"] [b "two"]] (map (juxt :chat :words) @sent))
        "each message goes to its own chat's conversation")
    (testing "the open one is the one last opened, and opening another switches"
      (let [open-of (fn [] (let [[_ x y] (re-find #"class=\"chat-row open\" data-chat=\"([^\"]+)\"|data-chat=\"([^\"]+)\" class=\"chat-row open\""
                                                   (page-panel "/workstreams/nido/ws-1"))]
                             (or x y)))]
        (Thread/sleep 3)
        (post! (str "/_chat/open/" a) "/workstreams/nido/ws-1")
        (is (= a (open-of)))
        (Thread/sleep 3)
        (post! (str "/_chat/open/" b) "/workstreams/nido/ws-1")
        (is (= b (open-of)))
        (is (= 1 (count (re-seq #"chat-row open" (page-panel "/workstreams/nido/ws-1")))) "one open at a time")))))

(deftest a-closed-chat-is-shown-nowhere
  (let [id (new-chat! "/workstreams/nido/ws-1")]
    (post! (str "/_chat/unlink/" id) "/workstreams/nido/ws-1")
    (post! (str "/_chat/close/" id) "/operations")
    (is (empty? (chats-shown "/workstreams/nido/ws-1")))
    (is (empty? (chats-shown "/operations")))
    (is (= :closed (:event (last (log-of id)))))))

(deftest a-first-message-where-there-is-no-chat-starts-one-pinned-there
  (is (empty? (chats-shown "/workstreams/nido/ws-1")))
  (is (str/includes? (page-panel "/workstreams/nido/ws-1") "/_chat/new/send") "an empty panel still has a composer")
  (post! "/_chat/new/send" "/workstreams/nido/ws-1"
         (json/generate-string {"chat" {"chat_new" {"text" "  "}}}))
  (is (empty? (chats-shown "/workstreams/nido/ws-1")) "no words, no chat")
  (post! "/_chat/new/send" "/workstreams/nido/ws-1"
         (json/generate-string {"chat" {"chat_new" {"text" "is this ready to land?"}}}))
  (let [[id] (chats-shown "/workstreams/nido/ws-1")]
    (is id)
    (is (= [[id "is this ready to land?"]] (map (juxt :chat :words) @sent)))
    (is (= ["page · Workstreams" "workstream · nido/ws-1" "chat · pinned to nido/ws-1"]
           (map :label (:refs (first @sent)))))
    (is (str/includes? (page-panel "/workstreams/nido/ws-1") "is this ready to land?")
        "the chat is labelled by its first message")))

(deftest a-turn-that-ends-while-the-person-is-away-marks-its-workstream
  (let [id (new-chat! "/workstreams/nido/ws-1")
        c  (#'side-chat/operator id)]
    (is (empty? (side-chat/unseen)))
    (Thread/sleep 5)
    ((:on-turn-end c) c)
    (is (= #{{:project "nido" :ws-id "ws-1"}} (side-chat/unseen)))
    (testing "opening it there clears the mark"
      (Thread/sleep 5)
      (post! (str "/_chat/open/" id) "/workstreams/nido/ws-1")
      (is (empty? (side-chat/unseen))))
    (testing "a chat that follows the person marks no row"
      (post! (str "/_chat/unlink/" id) "/workstreams/nido/ws-1")
      (Thread/sleep 5)
      ((:on-turn-end c) c)
      (is (empty? (side-chat/unseen))))))

(deftest the-board-marks-a-row-with-news
  (let [row (fn [chat?] (str (h/html (nido.libraries.ui.block/render
                                     nido.ui.components.row/board-row "nido:ws-1"
                                     {:project "nido" :row {:ws-id "ws-1" :label "x"} :href "#" :chat? chat?}
                                     {} (fn [& _] "")))))]
    (is (str/includes? (row true) "chat-mark"))
    (is (not (str/includes? (row false) "chat-mark")))))

(deftest the-panel-follows-the-location
  (let [p (page-panel "/workstreams/nido/ws-1")]
    (is (str/includes? p "data-on:nido-location__window"))
    (is (str/includes? p "/_chat/here"))
    (is (str/includes? p "replaceState") "the location's writers are announced")
    (testing "asked again, the server reads the place from the request"
      (let [id (new-chat! "/workstreams/nido/ws-1")
            r  (post! "/_chat/here" "/workstreams/nido/ws-1?entry=3")]
        (is (str/includes? (:body r) id))
        (is (not (str/includes? (:body (post! "/_chat/here" "/workstreams/nido/ws-2")) id)))))))

(deftest every-page-carries-the-panel-as-the-person-left-it
  (let [page (fn [open?] (views/shell {:title "Workstreams"
                                       :side-chat (side-chat/panel {:uri "/workstreams" :headers {}} open?)}
                                      [:h1 "Workstreams"]))]
    (testing "closed"
      (let [p (page false)]
        (is (str/includes? p "id=\"chat:nido-chat:toggle\""))
        (is (str/includes? p "&quot;open&quot;:false"))))
    (testing "open"
      (is (str/includes? (page true) "class=\"chat-side open\"")))
    (testing "a page without a chat has none"
      (is (not (str/includes? (views/shell {:title "x"} [:p]) "<aside"))))))

(deftest an-open-chat-is-live-and-ends-its-stream-when-it-leaves-the-page
  (new-chat! "/workstreams/nido/ws-1")
  (let [p (page-panel "/workstreams/nido/ws-1")]
    (is (re-find #"/_chat/c/[a-z0-9-]+/stream" p))
    (is (str/includes? p "requestCancellation: &apos;cleanup&apos;"))))

(deftest the-chat-answers-only-its-own-paths
  (is (nil? (side-chat/handle! {:request-method :get :uri "/workstreams"})))
  (is (nil? (side-chat/handle! {:request-method :get :uri "/_chat/nowhere"})))
  (is (nil? (side-chat/handle! {:request-method :post :uri "/_chat/c/no-such-chat/cancel"})) "an unknown chat")
  (is (nil? (side-chat/handle! {:request-method :post :uri "/_chat/c/../../etc/send"})))
  (let [id (new-chat! "/operations")]
    (with-redefs [chat/cancel! (fn [_] nil)]
      (is (= 204 (:status (side-chat/handle! {:request-method :post :uri (str "/_chat/c/" id "/cancel")})))))))

(deftest the-catalog-carries-its-own-chat-and-not-the-dashboards
  ;; The catalog sits in the dashboard's chrome but has a chat of its own; the dashboard's would be
  ;; a second panel beside it. Development, a page of the same chrome, carries the dashboard's.
  (with-redefs [change/pending          (constantly nil)
                server/view-state       (fn [_] {:scope "nido" :surface :development})
                server/needs-screen     (constantly {:needs-count 0})]
    (let [body (fn [uri] (:body (server/handle-request {:request-method :get :uri uri :headers {}})))]
      (is (str/includes? (body "/_catalog") "id=\"chat:catalog-chat:toggle\""))
      (is (not (str/includes? (body "/_catalog") "chat:nido-chat:toggle")))
      (is (str/includes? (body "/development") "id=\"chat:nido-chat:toggle\"")))))

(deftest the-operator-is-told-how-to-reach-a-conductor-and-where-its-chat-belongs
  (let [i (:instructions (#'side-chat/operator "c-1"))]
    (is (str/includes? i "nido:workstream:tell :project <p> :ws-id <id>"))
    (is (str/includes? i ":images <digest>"))
    (is (str/includes? i ":grants merge"))
    (is (str/includes? i "chat · …"))))

(deftest the-operator-runs-in-its-session-home-not-its-worktree
  ;; On project nido the worktree is a copy of nido nothing keeps current: a verb run against its
  ;; bb.edn is that copy's, not the one that runs.
  (let [ups (atom [])
        c   (#'side-chat/operator "c-1")]
    (with-redefs [lifecycle/runtime-of  (fn [_ _] nil)
                  lifecycle/up!         (fn [s opts] (swap! ups conj [s opts]))]
      (is (= (state/session-home-dir "nido" "operator") ((:dir c) c)))
      (is (= [["operator" {:project "nido"}]] @ups) "a session that is down is brought up first")
      (is (= {:project "nido" :session "operator" :name "c-1"} (select-keys c [:project :session :name]))
          "each chat is its own conversation in the operator's session"))))

(def ^:private png-bytes
  (byte-array (concat [0x89 0x50 0x4e 0x47 0x0d 0x0a 0x1a 0x0a] (.getBytes "not much of a png"))))

(deftest an-operator-message-keeps-its-images-and-says-nothing-to-the-core
  (fake-core/with-fake-core
    (fn [c]
      (fake-core/seed! c :brian "ws-1" [{:command :post-chat :message "m0" :text "earlier"}])
      (let [kept (atom [])
            id   (new-chat! "/operations")]
        (with-redefs [wcore/keep-chat-image! (fn [img] (swap! kept conj img) (:digest img))]
          (is (= 204 (:status (side-chat/handle! {:request-method :post :uri (str "/_chat/c/" id "/attach")
                                                  :headers {} :body (java.io.ByteArrayInputStream. png-bytes)}))))
          (say! id "/operations" "what is in this picture?")
          (let [{:keys [words refs images]} (last @sent)
                digest (:digest (first images))]
            (testing "the message, its place, its anchor and its images go to the chat's conversation"
              (is (= "what is in this picture?" words))
              (is (= 1 (count images)))
              (is (= ["page · Operations" "chat · pinned to /operations"] (take 2 (map :label refs))))
              (is (= (str "image 1 · " (subs digest 0 12)) (:label (last refs))))
              (is (str/includes? (:detail (last refs)) digest) "the agent can name the image by digest"))
            (testing "the image is kept by the side chat, ready for a relay"
              (is (= [digest] (map :digest @kept))))
            (testing "nothing of it reaches the core"
              (is (= ["earlier"] (map :text (:chat (get @(:states c) "brian/ws-1")))))
              (is (empty? @(:sent c))))))))))

(deftest a-stored-log-is-trusted-only-when-it-parses
  (let [parse #'side-chat/parse-log
        e     (fn [event anchor] (pr-str {:event event :anchor anchor :at 1}))]
    (is (= [:started :pinned] (map :event (:log (parse (str (e :started nil) "\n" (e :pinned {:path "/x"}) "\n"))))))
    (testing "refused, saying why"
      (is (:refused (parse "")))
      (is (re-find #"line 2" (:refused (parse (str (e :started nil) "\n{:event :moved}\n")))))
      (is (re-find #"begin" (:refused (parse (e :pinned {:path "/x"})))))
      (is (re-find #"twice" (:refused (parse (str (e :started nil) "\n" (e :started nil))))))
      (is (re-find #"after its close" (:refused (parse (str (e :started nil) "\n" (e :closed nil) "\n" (e :pinned {:path "/x"})))))))
    (testing "a refused chat is shown nowhere and never written to"
      (let [id  "c-broken"
            dir (fs/path (core/nido-root) "side-chats" id)]
        (fs/create-dirs dir)
        (spit (str (fs/path dir "anchor.log")) "not edn at all\n")
        (is (empty? (chats-shown "/operations")))
        (is (nil? (post! (str "/_chat/pin/" id) "/operations")))
        (is (= "not edn at all\n" (slurp (str (fs/path dir "anchor.log")))))))
    (testing "a closed chat takes no further entries"
      (let [id (new-chat! "/operations")]
        (post! (str "/_chat/close/" id) "/operations")
        (is (nil? (post! (str "/_chat/pin/" id) "/operations")))
        (is (= [:started :closed] (map :event (log-of id))))))))

(deftest a-winding-down-row-carries-the-mark-too
  (let [row (fn [chat?] (str (h/html (nido.libraries.ui.block/render
                                     nido.ui.components.row/winddown-row "nido:ws-1"
                                     {:row {:project "nido" :ws-id "ws-1" :label "x" :sessions []} :chat? chat?}
                                     {} (fn [& _] "")))))]
    (is (str/includes? (row true) "chat-mark"))
    (is (not (str/includes? (row false) "chat-mark")))))

(deftest a-collapsed-panel-is-not-the-person-reading
  (let [id (new-chat! "/workstreams/nido/ws-1")
        c  (#'side-chat/operator id)]
    (testing "a stream with the panel collapsed: a turn ending is news"
      (post! (str "/_chat/hidden/" id) "/workstreams/nido/ws-1")
      (with-redefs [nido.ui.chat-host/host (let [h nido.ui.chat-host/host]
                                             (fn [& args] (assoc (apply h args) :streams (atom {:ch {}}))))]
        (Thread/sleep 5)
        ((:on-turn-end c) c))
      (is (= #{{:project "nido" :ws-id "ws-1"}} (side-chat/unseen))))
    (testing "expanded on it, a turn ending is read as it lands"
      (Thread/sleep 5)
      (post! (str "/_chat/shown/" id) "/workstreams/nido/ws-1")
      (is (empty? (side-chat/unseen)))
      (with-redefs [nido.ui.chat-host/host (let [h nido.ui.chat-host/host]
                                             (fn [& args] (assoc (apply h args) :streams (atom {:ch {}}))))]
        (Thread/sleep 5)
        ((:on-turn-end c) c))
      (is (empty? (side-chat/unseen))))))

(deftest a-pin-always-names-where
  (let [parse #'side-chat/parse-log]
    (is (re-find #"line 2" (:refused (parse (str (pr-str {:event :started :anchor nil :at 1}) "\n"
                                                 (pr-str {:event :pinned :anchor nil :at 2}))))))
    (testing "a pin from an unknown place changes nothing"
      (let [id (new-chat! "/operations")]
        (post! (str "/_chat/unlink/" id) "/operations")
        (side-chat/handle! {:request-method :post :uri (str "/_chat/pin/" id) :headers {}})
        (is (= [:started :unlinked] (map :event (log-of id))))))))
