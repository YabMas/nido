(ns nido.ui.chat-host
  "One conversation served to the chat panels showing it: the stream that keeps each panel in step,
   and the requests a panel's controls make — send, stop, start a new conversation, answer a
   permission.

   HIDES how a panel is kept live — the whole log, status row and busy signal once, then one patch
   per transcript item that changed, appended or morphed by its id, and the whole log again when
   the conversation was reset — which open streams there are,
   how a request's path and body are read, which images the conversation has staged for its next
   message, and how an uploaded file becomes an image (`parse-image`, the one place that happens).

   A panel whose view offers attaching (`:attach?`) is answered three more requests: attach (POST, the
   image's raw bytes as the body), unattach/<digest> (POST) and image/<digest> (GET, a staged or
   sent image's bytes). Staged images are the conversation's, not a page's: held per path prefix
   beside the streams, shown on every panel through them, taken by the next accepted send, and
   dropped when the conversation is started over.

   A host is a value naming its conversation (a `nido.session.chat` chat), its panel's view (see
   `nido.ui.components.chat`), the path segments its panel's actions are routed under — `router`
   makes the router that routes them there — and `:refs`, a function of the send request giving
   what a sent message carries besides its words. Every post a panel's action makes is read
   through the panel's own decoder, and nothing here spells a field it posts. A caller may assoc three
   more: `:on-sent`, events to send after a message is accepted; `:guard`, a function of one
   thunk that runs the send's acceptance inside whatever exclusion the caller needs and returns
   what it returned — a guard that waits delays a send, it never refuses one; `:opening`, events a
   new stream gets after the log, status and busy signal. What
   the page around the panel is, and any request beyond the five it answers, stay its caller's."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.session.chat :as chat]
            [nido.libraries.ui.block :as block]
            [nido.libraries.ui.names :as names]
            [nido.libraries.ui.wire :as wire]
            [nido.ui.components.chat :as panel]
            [org.httpkit.server :as http])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.security MessageDigest)
           (java.util Arrays Base64)))

(defonce ^:private stream-registries
  ;; prefix -> atom of httpkit channel -> {:unsubscribe fn :sent transcript}. Held here, outside
  ;; any host value, so a host rebuilt by reloading its caller's namespace still reaches the
  ;; streams pages opened before the reload.
  (atom {}))

;; A conversation nido does not hold over ACP — a workstream's chat, held by the core — supplies
;; its own functions under :backend, keyed as `nido.session.chat` names them; any it leaves out
;; are `nido.session.chat`'s.
(defn ^{:malli/schema [:=> [:cat :map :keyword fn?] fn?]} via
  "The function `k` of conversation `chat`: its backend's, else `f`."
  [chat k f] (or (get-in chat [:backend k]) f))

(defn- streams-under [prefix]
  (get (swap! stream-registries update prefix #(or % (atom {}))) prefix))

(defonce ^:private staged-registries
  ;; prefix -> atom of the staged images, in the order they were staged. Held outside any host
  ;; value for the same reason the streams are; in memory only, so a restart drops them.
  (atom {}))

(defn- staged-under [prefix]
  (get (swap! staged-registries update prefix #(or % (atom []))) prefix))

(def ^:private max-image-bytes
  "The largest image accepted, decoded: the Messages API's own limit for one image."
  (* 5 1024 1024))

(def ^:private max-staged
  "How many images one message may carry."
  4)

(defn ^{:malli/schema [:=> [:cat :string] :any]}
  router
  "The router for a panel whose actions are served under the path `base`: each action at
   `<base>/<action>`, its params' values following as path segments in the order of their keys —
   `permission/<item>/<option>`, `unpick/<index>`, `unattach/<digest>`, `image/<digest>`."
  [base]
  (fn [action params]
    (str base "/" (name action)
         (apply str (for [[_ v] (sort-by key params)] (str "/" v))))))

(defn ^{:malli/schema [:=> [:cat :map :keyword :map] :any]}
  part
  "One part of the panel `view` — `:log`, `:item`, `:status`, `:picks` or `:attachments` — over
   `props` (the transcript, the item, the picks or the staged images it renders), as the panel
   renders it: what a stream patches in place."
  [{:keys [id router attach?]} k props]
  (block/render panel/panel id (merge {:transcript {:items []} :attach? (boolean attach?) :part k} props)
                {} router))

(defn ^{:malli/schema [:=> [:cat :string :map] :string]}
  signals-patch
  "The JSON a patch-signals event carries to set the panel `id`'s signals in `m`, e.g.
   `{:busy true}`."
  [id m]
  (json/generate-string (assoc-in {} (names/signal-root (:id panel/panel) id) m)))

(defn ^{:malli/schema [:=> [:cat :keyword :string :map]
                       [:or [:map {:closed true} [:ok :map]] [:map {:closed true} [:failure [:= :unparseable]]] [:map {:closed true} [:failure [:= :missing]] [:key :keyword]] [:map {:closed true} [:failure [:= :invalid]] [:key :keyword] [:why :some]]]]}
  decode
  "The request `req`'s body read against the panel's action `k` for the panel `id` — what
   `nido.libraries.ui.block/decode-action` answers."
  [k id req]
  (block/decode-action panel/panel k (some-> (:body req) slurp) id))

(defn ^{:malli/schema [:=> [:cat :map :map [:sequential :string] :any] :map]}
  host
  "A host for `chat`, whose panel has `view`, answering under the path segments `prefix`.
   `refs` is called with the send request when a message is sent — the fields its decoder read,
   attached as `:fields` — and returns its references, each `{:label :detail}`. What the request
   itself states, its headers among them, is the caller's to read as well as what the client
   posted. Two hosts under one prefix share its open streams, which is what keeps a page's stream
   reached across a reload that rebuilds its host."
  [chat view prefix refs]
  {:chat chat :view view :prefix (vec prefix) :refs refs :streams (streams-under (vec prefix))
   :staged (staged-under (vec prefix))})

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

(defn- starts-with? [^bytes bs offset ^String sig]
  (let [sig (.getBytes sig "ISO-8859-1")]
    (and (>= (alength bs) (+ offset (alength sig)))
         (Arrays/equals (Arrays/copyOfRange bs (int offset) (int (+ offset (alength sig)))) sig))))

(defn- sniff
  "The media type `bs` are, read from their signature, or nil for none the agent accepts."
  [^bytes bs]
  (cond
    (starts-with? bs 0 "\u0089PNG\r\n\u001a\n")                        "image/png"
    (starts-with? bs 0 "\u00ff\u00d8\u00ff")                             "image/jpeg"
    (or (starts-with? bs 0 "GIF87a") (starts-with? bs 0 "GIF89a")) "image/gif"
    (and (starts-with? bs 0 "RIFF") (starts-with? bs 8 "WEBP"))    "image/webp"))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  parse-image
  "An uploaded file's `:bytes` as an image `{:mime :data :digest}` — its media type read from
   its signature, never from what the browser claimed, one of PNG, JPEG, GIF or WebP, and at most
   5 MB — or `{:refused why}`, in words the composer shows. The only place an upload becomes an
   image: everything the host hands on was made here."
  [{:keys [^bytes bytes]}]
  (let [mime (when bytes (sniff bytes))]
    (cond
      (or (nil? bytes) (zero? (alength bytes))) {:refused "The upload was empty."}
      (> (alength bytes) max-image-bytes)       {:refused "The image is larger than 5 MB."}
      (nil? mime)                               {:refused "That is not an image the agent accepts: PNG, JPEG, GIF or WebP."}
      :else {:mime   mime
             :data   (.encodeToString (Base64/getEncoder) bytes)
             :digest (apply str (map #(format "%02x" %)
                                     (.digest (MessageDigest/getInstance "SHA-256") bytes)))})))

(defn- read-bounded
  "At most `limit` + 1 bytes of `in`, so an over-size body is known as one without reading it all."
  ^bytes [^InputStream in limit]
  (let [out (ByteArrayOutputStream.)
        buf (byte-array 65536)]
    (loop []
      (let [n (.read in buf)]
        (when (and (pos? n) (<= (.size out) limit))
          (.write out buf 0 n)
          (recur))))
    (.toByteArray out)))

(defn- attachments-event [{:keys [view staged]}]
  (wire/patch-elements-event (html (part view :attachments {:staged @staged}))))

(defn ^{:malli/schema [:=> [:cat :map :map] :any]}
  take-staged!
  "Move every image staged on host `from` onto host `to`'s stage, after what `to` holds already,
   and re-render both stages on their open panels. How a first message written in a panel with
   no conversation yet carries the images staged there into the conversation it starts. An image
   `to` already holds is not staged twice, and `to` holds at most four."
  [from to]
  (locking (:staged from)
    (locking (:staged to)
      (let [taken @(:staged from)]
        (when (seq taken)
          (reset! (:staged from) [])
          (swap! (:staged to)
                 (fn [xs] (let [held (set (map :digest xs))]
                            (vec (take max-staged (into xs (remove #(held (:digest %))) taken))))))
          (broadcast! from (attachments-event from))
          (broadcast! to (attachments-event to)))))))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  staged?
  "Whether any image is staged on `host` for its next message."
  [host]
  (boolean (seq @(:staged host))))

(defn- status-of [view t] (part view :status {:transcript t}))

(defn- status-event [view t]
  (wire/patch-elements-event (html (status-of view t))))

(defn- busy-event [{:keys [id]} t]
  (wire/patch-signals-event (signals-patch id {:busy (boolean (:busy? t))})))

(defn- log-of [view items] (part view :log {:transcript {:items items}}))

(defn ^{:malli/schema [:=> [:cat :map :Conversation :Conversation] :any]}
  changed-events
  "The patches that take a page from transcript `old` to `new`: an item that is new is appended to
   the log, one that changed is morphed in place by its id, the status row is re-rendered when
   what it says changes, and the busy signal is set when a turn starts or ends. Items are only
   appended within a conversation, so position is identity; a `new` with fewer items than `old`
   is a conversation started over, and re-renders the whole log, the status row and the busy
   signal."
  [{:keys [id] :as view} old new]
  (let [old-items (:items old)]
    (if (< (count (:items new)) (count old-items))
      [(wire/patch-elements-event (html (log-of view (:items new))))
       (status-event view new)
       (busy-event view new)]
      (concat
       (for [[i item] (map-indexed vector (:items new))
             :let [before (get old-items i)]
             :when (not= before item)]
         (let [markup (html (part view :item {:item item}))]
           (if before
             (wire/patch-elements-event markup)
             (wire/patch-elements-event markup {:selector (names/selector (:id panel/panel) id :log) :mode "append"}))))
       (when (not= (status-of view old) (status-of view new))
         [(status-event view new)])
       (when (not= (:busy? old) (:busy? new))
         [(busy-event view new)])))))

(defn- stream [{:keys [chat view streams opening staged] :as h} req]
  (http/as-channel
   req
   {:on-open  (fn [ch]
                (http/send! ch {:status 200 :headers {"Content-Type"  "text/event-stream"
                                                      "Cache-Control" "no-cache"}} false)
                (let [t ((via chat :transcript chat/transcript) chat)]
                  ;; Under the stage's lock, which every stage change broadcasts under: the
                  ;; staged images this stream opens with are current until it is registered.
                  (locking staged
                    (http/send! ch (str (wire/patch-elements-event
                                         (html (log-of view (:items t))))
                                        (status-event view t)
                                        (busy-event view t)
                                        (when (:attach? view) (attachments-event h))
                                        (apply str (when opening (opening))))
                                false)
                    (swap! streams assoc ch {:sent t}))
                  (let [unsub ((via chat :subscribe! chat/subscribe!)
                               chat
                               (fn [t']
                                 (let [{:keys [sent]} (get @streams ch)]
                                   (when sent
                                     (swap! streams assoc-in [ch :sent] t')
                                     (doseq [e (changed-events view sent t')] (http/send! ch e false))))))]
                    (swap! streams assoc-in [ch :unsubscribe] unsub))))
    :on-close (fn [ch _]
                (when-let [unsub (:unsubscribe (get @streams ch))] (unsub))
                (swap! streams dissoc ch))}))

(defn- send-message
  "Send the posted draft with its references and the conversation's staged images. Sent, the
   draft is cleared, the images it took leave the stage on every panel, and whatever `:on-sent`
   adds (events) follows; refused — a turn is running, there are neither words nor staged images,
   or the panel's decoder refused the body — nothing changes, so the draft and the staged images are kept. The
   acceptance runs inside the caller's `:guard`."
  [{:keys [chat view refs on-sent guard staged] :as h} req]
  (let [fields (:ok (decode :send (:id view) req))
        words  (str/trim (str (:text fields)))
        ;; Under the stage's lock, so no attach or unattach falls between the images this
        ;; message takes, its acceptance and their leaving the stage.
        images (locking staged
                 (let [images @staged]
                   (when (and fields
                              (or (seq words) (seq images))
                              (let [accept #((via chat :send! chat/send!) chat words (vec (refs (assoc req :fields fields))) images)]
                                (if guard (guard accept) (accept))))
                     (reset! staged [])
                     (when (seq images) (broadcast! h (attachments-event h)))
                     images)))]
    (if images
      (do (apply sse (wire/patch-signals-event (signals-patch (:id view) {:text ""}))
                 (when on-sent (on-sent))))
      (sse))))

(defn- attach
  "Stage the posted image for the next message, or refuse it, saying why, as plain text the
   composer shows. An image already staged is not staged twice."
  [{:keys [staged] :as h} req]
  (if (>= (count @staged) max-staged)
    {:status 409 :headers {"Content-Type" "text/plain; charset=utf-8"}
     :body (str "A message carries at most " max-staged " images.")}
    (let [img (parse-image {:bytes (when-let [b (:body req)] (with-open [in (io/input-stream b)]
                                                                 (read-bounded in max-image-bytes)))})]
      (if-let [why (:refused img)]
        {:status 422 :headers {"Content-Type" "text/plain; charset=utf-8"} :body why}
        ;; Checked again under the stage's lock: a concurrent attach may have filled it since.
        (if (locking staged
              (let [xs @staged]
                (cond (some #(= (:digest %) (:digest img)) xs) true
                      (>= (count xs) max-staged)              false
                      :else (do (reset! staged (conj xs img))
                                (broadcast! h (attachments-event h))
                                true))))
          {:status 204}
          {:status 409 :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body (str "A message carries at most " max-staged " images.")})))))

(defn- image
  "A staged or sent image's bytes by digest, cacheable for good: a digest names its bytes. A sent
   one is the chat's to answer — from its acceptance on, so none falls between the stage and it."
  [{:keys [chat staged]} digest]
  (if-let [{:keys [mime data]} (or (some #(when (= digest (:digest %)) %) @staged)
                                   ((via chat :image chat/image) chat digest))]
    {:status 200
     :headers {"Content-Type" mime "Cache-Control" "private, max-age=31536000, immutable"}
     :body (java.io.ByteArrayInputStream. (.decode (Base64/getDecoder) ^String data))}
    {:status 404}))

(defn ^{:malli/schema [:=> [:cat :map :map] [:maybe :map]]}
  handle!
  "The response to a request under the host's prefix — `stream` (GET), `send`, `cancel`, `reset`,
   `permission/<item>/<option>` (POST), and for a panel whose view offers attaching,
   `attach`, `unattach/<digest>` (POST) and `image/<digest>` (GET) — or nil for any other request,
   including another verb under the same prefix, which is the caller's to answer. A reset refused
   because a turn runs changes nothing and answers the same; every open panel hears an accepted
   one through its stream, the images it had staged dropped with it."
  [{:keys [chat prefix view staged] :as host} {:keys [uri request-method] :as req}]
  (let [segs (vec (remove str/blank? (str/split (str uri) #"/")))
        n    (count prefix)]
    (when (= prefix (take n segs))
      (case [request-method (get segs n)]
        [:get "stream"]      (stream host req)
        [:post "send"]       (send-message host req)
        [:post "cancel"]     (do ((via chat :cancel! chat/cancel!) chat) {:status 204})
        ;; Under the stage's lock, as a send's acceptance is: no send falls between the reset's
        ;; acceptance and the stage it discards, so the old images never reach the new conversation.
        [:post "reset"]      (do (locking staged
                                   (when (and ((via chat :reset! chat/reset!) chat) (seq @staged))
                                     (reset! staged [])
                                     (broadcast! host (attachments-event host))))
                                 {:status 204})
        [:post "permission"] (do (when (= (+ n 3) (count segs))
                                   ((via chat :answer-permission! chat/answer-permission!) chat (get segs (inc n)) (get segs (+ n 2))))
                                 {:status 204})
        (when (:attach? view)
          (case [request-method (get segs n)]
            [:post "attach"]   (attach host req)
            [:post "unattach"] (do (locking staged
                                     (swap! staged (fn [xs] (vec (remove #(= (get segs (inc n)) (:digest %)) xs))))
                                     (broadcast! host (attachments-event host)))
                                   (sse))
            [:get "image"]     (image host (get segs (inc n)))
            nil))))))
