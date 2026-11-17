(ns nido.ui.chat-host
  "One conversation served to the chat panels showing it: the stream that keeps each panel in step,
   and the requests a panel's controls make — send, stop, start a new conversation, answer a
   permission.

   HIDES how a panel is kept live — the whole log, status row and busy signal once, then one patch
   per transcript item that changed, appended or morphed by its id, and the whole log again when
   the conversation was reset — which open streams there are,
   and how a request's path and body are read.

   A host is a value naming its conversation (a `nido.session.chat` chat), its panel's routes (see
   `nido.ui.components.chat`), the path segments its routes live under, and `:refs`, a function of
   the posted body giving what a sent message carries besides its words. A caller may assoc three
   more: `:on-sent`, events to send after a message is accepted; `:holds?`, true while no message
   may be sent; `:opening`, events a new stream gets after the log, status and busy signal. What
   the page around the panel is, and any request beyond the five it answers, stay its caller's."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.session.chat :as chat]
            [nido.ui.components.chat :as panel]
            [nido.ui.datastar :as datastar]
            [org.httpkit.server :as http]))

(defonce ^:private stream-registries
  ;; prefix -> atom of httpkit channel -> {:unsubscribe fn :sent transcript}. Held here, outside
  ;; any host value, so a host rebuilt by reloading its caller's namespace still reaches the
  ;; streams pages opened before the reload.
  (atom {}))

(defn- streams-under [prefix]
  (get (swap! stream-registries update prefix #(or % (atom {}))) prefix))

(defn ^{:malli/schema [:=> [:cat :map :map [:sequential :string] :any] :map]}
  host
  "A host for `chat`, whose panel has `routes`, answering under the path segments `prefix`.
   `refs` is called with the posted body (string keys) when a message is sent, and returns its
   references, each `{:label :detail}`. Two hosts under one prefix share its open streams, which
   is what keeps a page's stream reached across a reload that rebuilds its host."
  [chat routes prefix refs]
  {:chat chat :routes routes :prefix (vec prefix) :refs refs :streams (streams-under (vec prefix))})

(defn ^{:malli/schema [:=> [:cat :any] :string]}
  html
  "Hiccup as markup text."
  [hiccup]
  (str (h/html hiccup)))

(defn ^{:malli/schema [:=> [:cat :map :string] :any]}
  broadcast!
  "Send `event` down every stream the host has open."
  [{:keys [streams]} event]
  (doseq [ch (keys @streams)] (http/send! ch event false)))

(defn ^{:malli/schema [:=> [:cat [:* :string]] :map]}
  sse
  "A finished SSE response carrying `events`."
  [& events]
  {:status 200
   :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
   :body (apply str events)})

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  body-json
  "The request's JSON body with string keys; empty when there is none or it does not parse."
  [req]
  (try (json/parse-string (slurp (:body req))) (catch Exception _ {})))

(defn- status-event [id t]
  (datastar/patch-elements-event (html (panel/turn-status id t))))

(defn- busy-event [id t]
  (datastar/patch-signals-event (json/generate-string {(:busy (panel/signals id)) (boolean (:busy? t))})))

(defn ^{:malli/schema [:=> [:cat :map :Conversation :Conversation] :any]}
  changed-events
  "The patches that take a page from transcript `old` to `new`: an item that is new is appended to
   the log, one that changed is morphed in place by its id, the status row is re-rendered when
   what it says changes, and the busy signal is set when a turn starts or ends. Items are only
   appended within a conversation, so position is identity; a `new` with fewer items than `old`
   is a conversation started over, and re-renders the whole log, the status row and the busy
   signal."
  [{:keys [id] :as routes} old new]
  (let [old-items (:items old)]
    (if (< (count (:items new)) (count old-items))
      [(datastar/patch-elements-event (html (panel/chat-log id (:items new) routes)))
       (status-event id new)
       (busy-event id new)]
      (concat
       (for [[i item] (map-indexed vector (:items new))
             :let [before (get old-items i)]
             :when (not= before item)]
         (let [markup (html (panel/chat-item item routes))]
           (if before
             (datastar/patch-elements-event markup)
             (datastar/patch-elements-event markup {:selector (str "#" (panel/log-id id)) :mode "append"}))))
       (when (not= (panel/turn-status id old) (panel/turn-status id new))
         [(status-event id new)])
       (when (not= (:busy? old) (:busy? new))
         [(busy-event id new)])))))

(defn- stream [{:keys [chat routes streams opening]} req]
  (let [id (:id routes)]
    (http/as-channel
     req
     {:on-open  (fn [ch]
                  (http/send! ch {:status 200 :headers {"Content-Type"  "text/event-stream"
                                                        "Cache-Control" "no-cache"}} false)
                  (let [t (chat/transcript chat)]
                    (http/send! ch (str (datastar/patch-elements-event
                                         (html (panel/chat-log id (:items t) routes)))
                                        (status-event id t)
                                        (busy-event id t)
                                        (apply str (when opening (opening))))
                                false)
                    (swap! streams assoc ch {:sent t})
                    (let [unsub (chat/subscribe!
                                 chat
                                 (fn [t']
                                   (let [{:keys [sent]} (get @streams ch)]
                                     (when sent
                                       (swap! streams assoc-in [ch :sent] t')
                                       (doseq [e (changed-events routes sent t')] (http/send! ch e false))))))]
                      (swap! streams assoc-in [ch :unsubscribe] unsub))))
      :on-close (fn [ch _]
                  (when-let [unsub (:unsubscribe (get @streams ch))] (unsub))
                  (swap! streams dissoc ch))})))

(defn- send-message
  "Send the posted draft with its references. Sent, the draft is cleared, plus whatever
   `:on-sent` adds (events); refused — a turn is running, `:holds?` says so, or there are no
   words — nothing changes, so the draft is kept."
  [{:keys [chat routes refs on-sent holds?]} req]
  (let [{:keys [text]} (panel/signals (:id routes))
        body  (body-json req)
        words (str/trim (str (get body text)))]
    (if (and (seq words) (not (and holds? (holds?))) (chat/send! chat words (vec (refs body)) []))
      (apply sse (datastar/patch-signals-event (json/generate-string {text ""}))
             (when on-sent (on-sent)))
      (sse))))

(defn ^{:malli/schema [:=> [:cat :map :map] [:maybe :map]]}
  handle!
  "The response to a request under the host's prefix — `stream` (GET), `send`, `cancel`, `reset`,
   `permission/<item>/<option>` (POST) — or nil for any other request, including another verb
   under the same prefix, which is the caller's to answer. A reset refused because a turn runs
   changes nothing and answers the same; every open panel hears an accepted one through its
   stream."
  [{:keys [chat prefix] :as host} {:keys [uri request-method] :as req}]
  (let [segs (vec (remove str/blank? (str/split (str uri) #"/")))
        n    (count prefix)]
    (when (= prefix (take n segs))
      (case [request-method (get segs n)]
        [:get "stream"]      (stream host req)
        [:post "send"]       (send-message host req)
        [:post "cancel"]     (do (chat/cancel! chat) {:status 204})
        [:post "reset"]      (do (chat/reset! chat) {:status 204})
        [:post "permission"] (do (when (= (+ n 3) (count segs))
                                   (chat/answer-permission! chat (get segs (inc n)) (get segs (+ n 2))))
                                 {:status 204})
        nil))))
