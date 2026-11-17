(ns nido.ui.side-chat
  "The dashboard's chat: a conversation with an agent in a side panel on every page the server
   draws through the shell with the dashboard's context — the catalog, which keeps its own chat,
   aside — chosen by the place the page shows.

   HIDES which conversation each place has — at an open workstream that workstream's own chat, in
   a session on it that holds no seat; anywhere else nido's operator, one session of nido's,
   which follows the person from page to page — what each agent is told, the paths each
   conversation is served under, and that whether the panel is open is kept in a cookie, so the
   next page renders it the same way rather than flashing it open.

   Where a message was sent from is not the panel's: every send, to either conversation, carries
   `nido.ui.place`'s place of the send request, read on the server at that moment, so nothing the
   page set up in the browser decides it, and a send whose page cannot be read still says so.

   The operator operates nido and a workstream's chat talks about its workstream; neither changes
   code. Each reads and acts through nido's own bb verbs, and is denied, like every chat, the
   commands that push or land."
  (:require [clojure.string :as str]
            [nido.coordinator.work :as work]
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

(defn- workstream-instructions
  "What a workstream's chat agent is told, appended to its system prompt."
  [project ws-id]
  (str "You are the chat of workstream " ws-id " of project " project ": the agent in the chat panel beside
that workstream on nido's dashboard. The person runs nido, the orchestrator of their development
workstreams, and talks to you about this one — what it is for, where it stands, what was decided and
why, what its code does — and to steer it. Each message ends with a <nido-context> block saying
where the person was when they sent it, read by nido at that moment: the page, and on a page showing
a workstream that workstream with its stage, the gate open on it with its question, and the ledger
entry open in its pane. When it names one, \"this entry\" or \"this gate\" means it; when it does
not, say what you cannot resolve instead of choosing.

Before you answer about the workstream's goal or its design, read them from its ledger, never from
memory: `bb --config <nido>/bb.edn
nido:workstream:show :project " project " :ws-id " ws-id "` prints its ledger, and its entries are
files under ~/.nido/projects/" project "/workstreams/" ws-id "/entries/ — the goal is its newest
:intent, the design its newest :design. This directory is a working copy of your own; the work of the
workstream's sessions is in theirs.

You hold no seat on the workstream: you write no code and no design, and you do not instruct its
sessions. When the person wants the work steered — a change of direction, an answer the work is
waiting for, something to do next — pass it on to the workstream's conductor, which decides what its
sessions do: `bb --config <nido>/bb.edn nido:workstream:tell :text \"…\"`, run from this directory,
in the person's terms and with what the conductor needs to act on it. Pass on what the person asked
you to, or what plainly calls for the conductor after you said you would; not every remark. The
conductor answers with one decision, a :conduct-decision entry in the ledger. A workstream that is not
activated has no conductor: say so instead. An act that is the person's — answering a blocker, closing
or deactivating the workstream — you run when they asked for it.

When the person's word is to land the workstream's PR — \"/land\", \"land it\", \"merge it\" — that word
is their grant, and they should not be asked again: pass it on with `:grants merge` beside the
:text, or, when it answers the blocker the workstream waits on, record it with
`nido:workstream:blocker:answer :answer \"…\" :grants merge`. The seat then lands the workstream's own
PR once. Pass a grant only when the person said so; never infer one they did not give.

Never push, fast-forward or move main, and never record a landing.

Answer briefly, and name workstreams and tickets as the dashboard does."))

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

(defn- conversation
  "The conversation at `place` — nil, or {:project :ws-id} for a page showing that workstream —
   as {:chat :prefix :heading}: the workstream's own chat when the work plane says the place has
   one to offer, under a path naming it, and nido's operator for every other place."
  [place]
  (let [{:keys [project ws-id]} place]
    (if-let [{:keys [session]} (when (and project ws-id) (work/chat-place project ws-id))]
      {:chat    {:project      (name project) :session session :name "workstream"
                 :instructions (workstream-instructions (name project) ws-id)
                 :ready!       (fn [_] (work/open-chat! project ws-id))}
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
    (panel/side (chat/transcript chat) (routes-under prefix)
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
