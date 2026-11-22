(ns nido.ui.side-chat
  "The dashboard's chat: a conversation with an agent in a side panel on every page the server
   draws through the shell with the dashboard's context — the catalog, which keeps its own chat,
   aside — chosen by the place the page shows.

   HIDES which conversation each place has — at a workstream the core holds, that workstream's
   own chat, which the core keeps and its conductor answers in turns; anywhere else nido's
   operator, one session of nido's, which follows the person from page to page — what the operator
   is told, the paths each conversation is served under, and that whether the panel is open is
   kept in a cookie, so the next page renders it the same way rather than flashing it open.

   Where a message was sent from is not the panel's: every send, to either conversation, carries
   `nido.ui.place`'s place of the send request, read on the server at that moment, so nothing the
   page set up in the browser decides it, and a send whose page cannot be read still says so.

   The operator operates nido through its own bb verbs and changes no code, and is denied, like
   every chat, the commands that push or land. A workstream's chat acts only as the conductor's
   turns do, by the same commands a surface's controls send."
  (:require [clojure.string :as str]
            [nido.coordinator.work.core :as wcore]
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

(defn- routes-under
  "The panel's routes for a conversation served under the path segments `prefix`."
  [prefix]
  (let [base (str "/" (str/join "/" prefix))]
    {:id         panel-id
     :send-url   (str base "/send")
     :cancel-url (str base "/cancel")
     :reset-url  (str base "/reset")
     :stream-url (str base "/stream")
     :answer-url (fn [item option] (str base "/permission/" item "/" option))
     :unpick-url   (fn [i] (str base "/unpick/" i))
     :attach-url   (str base "/attach")
     :unattach-url (fn [digest] (str base "/unattach/" digest))
     :image-url    (fn [digest] (str base "/image/" digest))}))

(def ^:private operator-chat
  {:project "nido" :session "operator" :name "operator" :instructions instructions})

(defn- core-transcript
  "Workstream chat `w` (as `wcore/held` holds it) as a transcript the panel renders: the person's
   messages and the conductor's replies, in the order the core committed them, busy while a
   conductor turn runs."
  [w]
  {:items (vec (map-indexed (fn [i {:keys [from text]}]
                              (if (= :person from)
                                {:id (str "i" i) :kind :user :text (str text) :refs [] :images []}
                                {:id (str "i" i) :kind :agent :text (str text)}))
                            (wcore/chat w)))
   :busy? (boolean (some #(and (nil? (:unit %)) (= :running (:status %)))
                         (vals (get-in w [:state :turns]))))})

(defn- core-backend
  "Workstream `ws-id`'s chat as the core holds it: each message a person sends is a command
   putting it in the conductor's inbox, and what the panel shows is what the feed delivered."
  [project ws-id]
  (let [held #(wcore/held-ws (wcore/held) project ws-id)]
    {:transcript (fn [_] (core-transcript (held)))
     :subscribe! (fn [_ f]
                   (let [k (keyword (str "chat-" (random-uuid)))
                         wk (wcore/ws-key project ws-id)]
                     (add-watch wcore/mirror k
                                (fn [_ _ old new]
                                  (let [o (get-in old [:ws wk]) n (get-in new [:ws wk])]
                                    (when-not (identical? o n) (f (core-transcript n))))))
                     #(remove-watch wcore/mirror k)))
     :send!      (fn [_ words _refs _images]
                   (wcore/admitted? (wcore/act! project ws-id {:command :post-chat
                                                               :message (str "m-" (random-uuid))
                                                               :text words})))
     :cancel!    (fn [_] nil)
     :reset!     (fn [_] false)
     :image      (fn [_ _] nil)
     :answer-permission! (fn [& _] nil)}))

(defn- conversation
  "The conversation at `place` — nil, or {:project :ws-id} for a page showing that workstream —
   as {:chat :prefix :heading}: the workstream's own chat, held by the core, under a path naming
   it, when the core holds that workstream; nido's operator for every other place."
  [place]
  (let [{:keys [project ws-id]} place]
    (if (and project ws-id (wcore/held-ws (wcore/held) project ws-id))
      {:chat    {:project (name project) :session ws-id :name "workstream"
                 :backend (core-backend (name project) ws-id)}
       :prefix  ["_chat" "ws" (name project) ws-id]
       :heading "This workstream"}
      {:chat operator-chat :prefix ["_chat"] :heading "nido"})))

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
  (let [{:keys [open]} (panel/signals panel-id)
        {:keys [chat prefix heading]} (conversation place)]
    (panel/side ((host/via chat :transcript chat/transcript) chat) (routes-under prefix)
                {:open?   open?
                 :heading heading
                 :extra   [[:span {:hidden true
                                   "data-effect" (str "document.cookie = '" cookie "=' + ($" open " ? 1 : 0)"
                                                      " + '; path=/; max-age=31536000; samesite=lax'")
                                   "data-on:keydown__window"
                                   (str "if ((evt.metaKey || evt.ctrlKey) && (evt.key === 'i' || evt.key === 'I'))"
                                        " { evt.preventDefault(); $" open " = !$" open " }")}]]})))

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
        (host/handle! (host/host chat (routes-under prefix) prefix (comp place/references place/place)) req)))))
