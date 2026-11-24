(ns nido.ui.side-chat
  "The dashboard's chat: a conversation with an agent in a side panel on every page the server
   draws through the shell with the dashboard's context — the catalog, which keeps its own chat,
   aside — chosen by the place the page shows.

   HIDES the one side chat and the contexts it runs in. Every context shares the machinery — a
   `nido.session.chat` conversation served through a chat host, whose sends carry the page's place
   and name each image to the agent by digest — and supplies only what is its own: what the agent
   is told, where it runs, and the target it relays to. At a workstream the core holds, the context
   is that workstream: an agent run in its code that answers from its ledger and relays to its
   conductor, keeping a message's images for the relay and showing what reached the conductor;
   anywhere else, nido's operator, one session of nido's that follows the person from page to page
   and relays to nothing. Also the paths each conversation is served under, and that whether the
   panel is open is kept in a cookie, so the next page renders it the same way rather than
   flashing it open.

   Where a message was sent from is not the panel's: every send, to either conversation, carries
   `nido.ui.place`'s place of the send request, read on the server at that moment, so nothing the
   page set up in the browser decides it, and a send whose page cannot be read still says so.

   The operator operates nido through its own bb verbs and changes no code, and is denied, like
   every chat, the commands that push or land. A workstream's chat changes no code either; it
   reaches the core only through `bb nido:workstream:tell`, so what it does not pass on never
   reaches the core. The panel shows its conversation, and, at the point this process first saw
   each, every message the conductor received in the workstream's chat — with its images, which
   the core serves — and every reply of the conductor's."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.coordinator.work :as work]
            [nido.coordinator.work.core :as wcore]
            [nido.libraries.ui.block :as block]
            [nido.libraries.ui.names :as names]
            [nido.session.chat :as chat]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]
            [nido.ui.place :as place]))

(def ^:private panel-id "nido-chat")
(def ^:private cookie "nidoChat")

(def ^:private instructions
  "What the operator's agent is told, appended to its system prompt."
  "You are nido's operator: the agent in the chat panel beside every page of nido's dashboard. The
person runs nido, the orchestrator of their development workstreams, and asks you about the work it
holds — workstreams, tickets, runs, sessions, triggers, breakers, reviews — and to act on it.

Each message ends with a <nido-context> block saying where the person was when they sent it, read
by nido at that moment: the page (\"page · …\"), and on a workstream page the workstream by project
and id with its stage (\"workstream · …\"), the gate open on it with its question and answers
(\"gate · open\"), and the ledger entry open in its pane (\"entry · …\"). \"This\", \"here\", \"the
open one\" and the like mean that subject — the workstream, its gate, its entry — so act on it
without asking which. When the block names no subject (\"nothing is open on it\"), or the place is
unknown (\"place · unknown\"), or what it names does not fit what was asked, do not choose one: say
what you cannot resolve and ask. Each block is the place at that message; an earlier one says
where the person was then, not now.

Work through nido's own bb verbs, run as your session briefing shows (`bb --config <nido>/bb.edn
...`); `bb --config <nido>/bb.edn tasks` lists them. `nido:workstream:list :project <p>` says where
a project's workstreams stand, `nido:workstream:show` reads one, `nido:workstream:start :project <p>
<session> :brief \"…\"` starts a new one from a goal, and `nido:workstream:close` and
`nido:deactivate` stop one. Read freely. An action that changes something —
a workstream, ticket, session, trigger or breaker, or anything posted to Slack, Notion or GitHub —
you take when the person asked for that action; when it is your own idea, say what you would run and
wait for them.

You operate nido; you do not change it. Do not edit code in this worktree — if something in nido
itself looks wrong, say so. Never push, fast-forward or move main, and never record a landing.

Answer briefly, and name workstreams and tickets as the dashboard does.")

(defn- view-under
  "The panel's view for a conversation served under the path segments `prefix`: it offers
   attaching."
  [prefix]
  {:id      panel-id
   :router  (host/router (str "/" (str/join "/" prefix)))
   :attach? true})

(def ^:private nido-dir
  "The nido checkout this dashboard runs from, whose bb.edn a workstream's chat runs nido's verbs
   with."
  (or (some-> (io/resource "nido/ui/side_chat.clj") io/file .getParentFile .getParentFile
              .getParentFile .getParentFile str)
      (str (System/getProperty "user.home") "/Code/nido")))

(defn- workstream-instructions
  "What workstream `ws-id`'s chat agent is told, appended to its system prompt."
  [project ws-id]
  (let [bb (str "bb --config " nido-dir "/bb.edn")]
    (str "You are the chat of workstream " ws-id " of project " project ": the agent in the chat panel beside
that workstream on nido's dashboard. The person runs nido, the orchestrator of their development
workstreams, and asks you about this one — what it is for, where it stands, what was decided and
why, what its code does. Answer them yourself. This directory holds the workstream's code; read it
freely, and change nothing in it.

Read the goal and the design from the workstream's ledger, never from memory: `" bb " nido:workstream:show
:project " project " :ws-id " ws-id "` prints it — the goal is its newest intent, the design its newest
design.

Each message ends with a <nido-context> block saying where the person was when they sent it — the
page, and on a workstream page the workstream, the gate open on it and the ledger entry open in its
pane — and naming each image the message carries, in order, as \"image n · <digest>\". When the
block names what \"this\" means, act on it; when it does not, say what you cannot resolve.

The workstream's conductor decides what its work does next; you hold no seat on it and do not
instruct its sessions. Pass on to the conductor only what the work needs: a change of direction, an
answer the work is waiting on, or a grant the person gives — and only when the person asked you to,
or plainly meant it. Questions, explanations and remarks stay here: what you do not pass on never
reaches the conductor. Pass word on with

  " bb " nido:workstream:tell :project " project " :ws-id " ws-id " :text \"…\"

in the person's terms and with what the conductor needs to act on it. When an answer is waiting on
the person, the text answers it. Add `:images <digest>,<digest>` to pass on the images of the
person's message the conductor needs to see, by the digests the context block names. When the
person's word is to land the workstream — \"land it\", \"merge it\" — that word is their grant: add
`:grants merge`. Never infer a grant they did not give.

Never push, fast-forward or move main, and never record a landing.

Answer briefly, and name workstreams and tickets as the dashboard does.")))

(defn- image-refs
  "A reference per image a message carries, in order, naming its digest — so the agent can pass an
   image on by digest."
  [images]
  (map-indexed (fn [i {:keys [digest]}]
                 {:label  (str "image " (inc i) " · " (subs digest 0 12))
                  :detail (str "Image " (inc i) " of this message, attached after its words, digest " digest ".")})
               images))

(defonce ^:private anchors
  ;; [project ws-id] -> {core chat message id -> how many of the conversation's items there were
  ;; when this process first saw it}. Where each message the conductor received, or its reply,
  ;; sits among the conversation's items. In memory only: after a restart they are placed after
  ;; the conversation's history.
  (atom {}))

(defn- merge-items
  "The panel's items: the conversation's `items`, with each of the core's `chat` entries — a
   message the conductor received (:from :person) as a :relay item, a conductor reply as a
   :conductor item — after the conversation item count `at` gives for its id, in core order; one
   `at` does not place comes last. Re-numbered by position."
  [items chat at]
  (let [core   (map-indexed (fn [i {:keys [id from text images]}]
                              [(get at id Long/MAX_VALUE) i
                               (if (= :conductor from)
                                 {:kind :conductor :text (str text)}
                                 {:kind :relay :text (str text) :images (mapv (fn [d] {:digest d}) images)})])
                            chat)
        placed (sort-by (juxt first second) core)
        out    (loop [out [] n 0 items items placed placed]
                 (let [[[k _ item] & more] placed]
                   (cond
                     (and (seq placed) (<= k n)) (recur (conj out item) n items more)
                     (seq items)                 (recur (conj out (first items)) (inc n) (rest items) placed)
                     (seq placed)                (recur (conj out item) n items more)
                     :else                       out)))]
    (vec (map-indexed (fn [i item] (assoc item :id (str "i" i))) out))))

(defn- relayed-transcript
  "The conversation `chat` as the panel shows it in a context whose relay target is `relay`: its
   transcript with what reached that target merged in (`merge-items`). An entry first seen here is
   placed after the conversation's items as they stand — unless the conversation is still to be
   loaded, when it waits to be placed until the history is."
  [chat {:keys [key entries]}]
  (let [t       (chat/transcript chat)
        entries (entries)
        n       (count (:items t))]
    (when-not (and (zero? n) (chat/started? chat))
      (swap! anchors update key (fn [m] (reduce #(if (contains? %1 (:id %2)) %1 (assoc %1 (:id %2) n))
                                                (or m {}) entries))))
    (assoc t :items (merge-items (:items t) entries (get @anchors key {})))))

(defn- side-chat
  "The one side chat, in `context`: a `nido.session.chat` conversation named by the context's :id,
   told its :instructions, run where its :dir or its session says. Every context shares the same
   machinery — the chat host, the conversation, and a send that names each image a message carries
   to the agent by digest. A context with a :relay — the target the agent passes word on to — also
   keeps those images for the relay, shows what reached the target and its replies in the
   transcript, and serves the images the target names."
  [{:keys [id instructions dir relay]}]
  (let [chat (cond-> (assoc id :instructions instructions) dir (assoc :dir dir))]
    (cond-> chat
      true  (assoc-in [:backend :send!]
                      (fn [c words refs images]
                        (when relay (doseq [img images] ((:keep-image! relay) img)))
                        (chat/send! c words (into (vec refs) (image-refs images)) images)))
      relay (update :backend merge
                    {:transcript (fn [c] (relayed-transcript c relay))
                     :subscribe! (fn [c f]
                                   (let [tell! (fn [_] (f (relayed-transcript c relay)))
                                         unsub (chat/subscribe! c tell!)
                                         unwatch ((:watch! relay) #(tell! nil))]
                                     #(do (unsub) (unwatch))))
                     :image      (fn [c digest] (or (chat/image c digest) ((:image relay) digest)))}))))

(def ^:private operator-context
  "Anywhere but a workstream the core holds: nido's operator, one session of nido's. It acts
   through nido's verbs and relays to nothing."
  {:id {:project "nido" :session "operator" :name "operator"} :instructions instructions
   :prefix ["_chat"] :heading "nido"})

(defn- workstream-context
  "At a workstream the core holds: its own chat, run in its code, whose relay target is the
   workstream's conductor — reached by `bb nido:workstream:tell` alone, so what the agent does not
   pass on never reaches the core."
  [project ws-id]
  (let [held #(wcore/held-ws (wcore/held) project ws-id)
        wk   (wcore/ws-key project ws-id)]
    {:id           {:project project :session ws-id :name "workstream"}
     :instructions (workstream-instructions project ws-id)
     :dir          (fn [_] (or (work/chat-dir (keyword project) ws-id)
                               (throw (ex-info (str "Workstream " ws-id " has no working copy to chat in.") {}))))
     :prefix       ["_chat" "ws" project ws-id]
     :heading      "This workstream"
     :relay        {:key         [project ws-id]
                    :entries     #(wcore/chat (held))
                    :keep-image! wcore/keep-chat-image!
                    :image       #(wcore/image (held) %)
                    :watch!      (fn [f]
                                   (let [k (keyword (str "chat-" (random-uuid)))]
                                     (add-watch wcore/mirror k
                                                (fn [_ _ old new]
                                                  (when-not (identical? (get-in old [:ws wk :state :chat])
                                                                        (get-in new [:ws wk :state :chat]))
                                                    (f))))
                                     #(remove-watch wcore/mirror k)))}}))

(defn- conversation
  "The conversation at `place` — nil, or {:project :ws-id} for a page showing that workstream —
   as {:chat :prefix :heading}: the side chat in the context the place gives — the workstream's,
   when the core holds it; the operator's for every other place."
  [place]
  (let [{:keys [project ws-id]} place
        ctx (if (and project ws-id (wcore/held-ws (wcore/held) project ws-id))
              (workstream-context (name project) ws-id)
              operator-context)]
    {:chat (side-chat ctx) :prefix (:prefix ctx) :heading (:heading ctx)}))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  open?
  "Whether the request's page should render the chat open: what the person last left it as."
  [req]
  (boolean (some->> (get-in req [:headers "cookie"])
                    (re-find (re-pattern (str "(?:^|;\\s*)" cookie "=1(?:;|$)"))))))

(defn ^{:malli/schema [:=> [:cat :boolean [:maybe :map]] :any]}
  panel
  "The chat's side panel for a dashboard page showing `place` (see `conversation`), open or closed
   as `open?` says, with what keeps it honest across pages: the cookie that remembers it open or
   closed, and ⌘I/Ctrl+I to open and close it. It holds nothing about the page — the place a
   message names is read from the send."
  [open? place]
  (let [open (names/signal (:id panel/panel) panel-id :open)
        {:keys [chat prefix heading]} (conversation place)
        {:keys [router]} (view-under prefix)]
    (block/render panel/panel panel-id
                {:transcript ((host/via chat :transcript chat/transcript) chat)
                 :open?   open?
                 :heading heading
                 :reset?  true
                 :attach? true
                 :live?   true
                 :extra   [[:span {:hidden true
                                   "data-effect" (str "document.cookie = '" cookie "=' + ($" open " ? 1 : 0)"
                                                      " + '; path=/; max-age=31536000; samesite=lax'")
                                   "data-on:keydown__window"
                                   (str "if ((evt.metaKey || evt.ctrlKey) && (evt.key === 'i' || evt.key === 'I'))"
                                        " { evt.preventDefault(); $" open " = !$" open " }")}]]}
                {} router)))

(defn- place-of
  "The place a request under /_chat is for, read off its path: {:project :ws-id} for one under
   /_chat/ws/<project>/<ws-id>, nil for the operator's."
  [uri]
  (let [[a b project ws-id] (remove str/blank? (str/split (str uri) #"/"))]
    (when (and (= "_chat" a) (= "ws" b) project ws-id)
      {:project project :ws-id ws-id})))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  handle!
  "One request under /_chat — the stream, a send, a stop, a reset, a permission answer, of the
   conversation its path names — or nil for any other path, and for a workstream's chat path that
   names no open workstream."
  [{:keys [uri] :as req}]
  (when (str/starts-with? (str uri) "/_chat/")
    (let [place (place-of uri)
          {:keys [chat prefix]} (conversation place)]
      ;; A workstream path whose workstream has no chat to offer would otherwise be the operator's.
      (when (= (boolean place) (not= ["_chat"] prefix))
        (host/handle! (host/host chat (view-under prefix) prefix (comp place/references place/place)) req)))))
