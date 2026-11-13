(ns nido.ui.catalog.chat
  "The catalog's chat: which conversation the catalog talks to, the page that hosts it, and the
   requests its panel makes.

   HIDES which conversation that is — one design session of nido's, `catalog-design`, made on the
   first send and resumed after — how a picked element is worded for the agent, where the frame
   points, and that a finished turn refreshes the preview and reloads the frame.

   The frame shows this dashboard's own scene page until the design conversation exists, and the
   design session's preview after: the code the agent is editing, synced and restarted at the end
   of every turn. The daemon's dashboard never loads that code itself.

   The panel's markup is the chat block's and the page's is the catalog's; the conversation and the
   preview are the Session band's. What this holds is the one live thing on the dashboard: a panel's
   stream, which patches each transcript item as it changes and unsubscribes when its browser goes."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.session.chat :as chat]
            [nido.session.preview :as preview]
            [nido.ui.catalog :as catalog]
            [nido.ui.components.chat :as panel]
            [nido.ui.datastar :as datastar]
            [org.httpkit.server :as http]))

(def ^:private project "nido")
(def ^:private design-session "catalog-design")
(def ^:private panel-id "catalog-chat")

(def ^:private instructions
  "What the design session's agent is told, appended to its system prompt."
  "You are the agent behind the chat panel in nido's component catalog (/_catalog). The person
reviews the dashboard's blocks there and asks you for changes. When they picked an element, their
message ends with a <nido-context> block naming the block (a function in nido.ui.components.*), the
scene (in nido.ui.catalog.scenes), the element's path from the scene's frame — one
tag.first-class:sibling-index step per level — and the markup it rendered as.

Edit the code in this worktree. Follow docs/reference/ui-principles.md, and put every class rule in
src/nido/ui/stylesheet.clj. When your turn ends, nido syncs this worktree into its runtime and
restarts the catalog preview the person is looking at, so a change shows as soon as you finish.
Keep each turn to the change asked for, and say in a sentence what you changed.

Never push, fast-forward or move main, and never record a landing: landing is the person's own
deliberate act in this session's terminal, never a consequence of a chat message.")

(defonce ^:private streams
  ;; httpkit channel -> {:unsubscribe fn :sent transcript}
  (atom {}))

(def ^:private routes
  {:id         panel-id
   :send-url   "/_catalog/chat/send"
   :cancel-url "/_catalog/chat/cancel"
   :stream-url "/_catalog/chat/stream"
   :answer-url (fn [item option] (str "/_catalog/chat/permission/" item "/" option))})

(defn- html [hiccup] (str (h/html hiccup)))

(defn- broadcast! [event]
  (doseq [ch (keys @streams)] (http/send! ch event false)))

(defn- frame-src
  "Where the frame loads the scene page from: the design session's preview once the conversation
   has been started, this dashboard otherwise."
  [design-chat]
  (if (chat/started? design-chat)
    (try (str (preview/preview-url project design-session) "/_catalog?embed=1")
         (catch Exception _ "/_catalog?embed=1"))
    "/_catalog?embed=1"))

(declare design-chat)

(defn- refresh-frame!
  "After a turn: bring the preview up to date with what the agent changed, and reload every open
   page's frame from it. When the sync refuses or the preview will not start, the frame is left on
   what it showed and the conversation says why, under the turn: the edit is in the worktree but
   not on screen, and a person must not read the old frame as the new code."
  [_]
  (try
    (let [base (preview/refresh! project design-session)]
      (broadcast! (datastar/patch-signals-event
                   (json/generate-string {:catcFrame (str base "#" (System/currentTimeMillis))}))))
    (catch Exception e
      (chat/note-error! design-chat (str "The preview was not refreshed, so the frame still shows "
                                         "the code before this turn: " (ex-message e))))))

(def ^:private design-chat
  {:project project :session design-session :name "catalog"
   :instructions instructions :on-turn-end refresh-frame!})

(defn- context-text
  "The picked element as the agent reads it. The first line is the label the panel shows above
   the person's words."
  [{:keys [block scene path text html]}]
  (str block " · " scene " · " path "\n"
       "Selected in nido's component catalog.\n"
       "block: " block " (a function in nido.ui.components.*)\n"
       "scene: " scene " (in nido.ui.catalog.scenes)\n"
       "element path from the scene's frame: " path "\n"
       (when (seq text) (str "its text: " text "\n"))
       "it rendered as: " html))

(defn- changed-events
  "The patches that take a page from transcript `old` to `new`: an item that is new is appended to
   the log, one that changed is morphed in place by its id, and the composer is re-rendered when a
   turn starts or ends. Items are only ever appended, so position is identity."
  [old new]
  (let [old-items (:items old)]
    (concat
     (for [[i item] (map-indexed vector (:items new))
           :let [before (get old-items i)]
           :when (not= before item)]
       (let [markup (html (panel/chat-item item routes))]
         (if before
           (datastar/patch-elements-event markup)
           (datastar/patch-elements-event markup {:selector (str "#" panel-id "-log") :mode "append"}))))
     (when (not= (:busy? old) (:busy? new))
       [(datastar/patch-elements-event (html (panel/composer panel-id (:busy? new) routes)))]))))

(defn- stream
  "A panel's stream: the whole log once, then each change as it happens, until the browser goes."
  [req]
  (http/as-channel
   req
   {:on-open  (fn [ch]
                (http/send! ch {:status 200 :headers {"Content-Type"  "text/event-stream"
                                                      "Cache-Control" "no-cache"}} false)
                (let [t (chat/transcript design-chat)]
                  (http/send! ch (str (datastar/patch-elements-event
                                       (html (into [:div.chat-log {:id (str panel-id "-log")}]
                                                   (map #(panel/chat-item % routes) (:items t)))))
                                      (datastar/patch-elements-event
                                       (html (panel/composer panel-id (:busy? t) routes))))
                              false)
                  (swap! streams assoc ch {:sent t})
                  (let [unsub (chat/subscribe!
                               design-chat
                               (fn [t']
                                 (let [{:keys [sent]} (get @streams ch)]
                                   (when sent
                                     (swap! streams assoc-in [ch :sent] t')
                                     (doseq [e (changed-events sent t')] (http/send! ch e false))))))]
                    (swap! streams assoc-in [ch :unsubscribe] unsub))))
    :on-close (fn [ch _]
                (when-let [unsub (:unsubscribe (get @streams ch))] (unsub))
                (swap! streams dissoc ch))}))

(defn- body-json [req]
  (try (json/parse-string (slurp (:body req))) (catch Exception _ {})))

(defn- send-message [req]
  (let [{:keys [text ref]} (panel/signals panel-id)
        body  (body-json req)
        words (str/trim (str (get body text)))
        picked (try (some-> (get body ref) not-empty json/parse-string) (catch Exception _ nil))]
    (when (seq words)
      (chat/send! design-chat words
                  (when picked (context-text {:block (get picked "block") :scene (get picked "scene")
                                              :path (get picked "path") :text (get picked "text")
                                              :html (get picked "html")}))))
    {:status 200
     :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
     :body (datastar/patch-signals-event
            (json/generate-string (zipmap (vals (panel/signals panel-id)) (repeat ""))))}))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] :string]}
  page
  "/_catalog: the scene page alone when it is the frame (`embed=1`), else the catalog with its
   chat."
  [query-string]
  (if (str/includes? (str query-string) "embed=1")
    (catalog/catalog-page query-string)
    (catalog/chat-page (chat/transcript design-chat) (frame-src design-chat) routes)))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  handle!
  "One request under /_catalog/chat — the stream, a send, a cancel or a permission answer — or nil
   for any other path."
  [{:keys [uri request-method] :as req}]
  (let [segs (vec (remove str/blank? (str/split (str uri) #"/")))]
    (when (= ["_catalog" "chat"] (take 2 segs))
      (case [request-method (get segs 2)]
        [:get "stream"]      (stream req)
        [:post "send"]       (send-message req)
        [:post "cancel"]     (do (chat/cancel! design-chat) {:status 204})
        [:post "permission"] (do (when (= 5 (count segs))
                                   (chat/answer-permission! design-chat (get segs 3) (get segs 4)))
                                 {:status 204})
        {:status 404 :body ""}))))
