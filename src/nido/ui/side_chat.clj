(ns nido.ui.side-chat
  "The dashboard's chat: nido's operator, in a side panel on every page the server draws through
   the shell with the dashboard's context — the catalog, which keeps its own chat, aside. One
   conversation, the same on every page: moving between pages never changes what the panel shows.

   HIDES the operator — what its agent is told, where it runs (its session's home, not its
   worktree: see `operator`), and that it reaches a workstream's
   conductor only through `bb nido:workstream:tell`, by its own judgment, so nothing the person
   sends is a core command and what the operator does not pass on never reaches the core — the
   path it is served under, and that whether the panel is open is kept in a cookie, so the next
   page renders it the same way rather than flashing it open.

   Where a message was sent from is not the panel's: every send carries `nido.ui.place`'s place of
   the send request, read on the server at that moment, so nothing the page set up in the browser
   decides it, and a send whose page cannot be read still says so. Each image a message carries is
   named to the agent by digest and kept in the side chat's own store, so a relay can pass it on.

   The operator operates nido through its own bb verbs and changes no code, and is denied, like
   every chat, the commands that push or land."
  (:require [clojure.string :as str]
            [nido.coordinator.work.core :as wcore]
            [nido.libraries.ui.block :as block]
            [nido.libraries.ui.names :as names]
            [nido.session.chat :as chat]
            [nido.session.lifecycle :as lifecycle]
            [nido.session.state :as state]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]
            [nido.ui.place :as place]))

(def ^:private panel-id "nido-chat")
(def ^:private cookie "nidoChat")
(def ^:private prefix ["_chat"])

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

The block also names each image the message carries, in order, as \"image n · <digest>\".

Work through nido's own bb verbs, run as `bb <verb> ...` from where you stand, your session's home:
its bb.edn is nido's own, so `bb tasks` lists them. Never point `--config` at another bb.edn — a
copy of nido elsewhere is not the one that runs. `nido:workstream:list :project <p>` says where
a project's workstreams stand, `nido:workstream:show` reads one, `nido:workstream:start :project <p>
<session> :brief \"…\"` starts a new one from a goal, and `nido:workstream:close` and
`nido:deactivate` stop one. Read freely. An action that changes something —
a workstream, ticket, session, trigger or breaker, or anything posted to Slack, Notion or GitHub —
you take when the person asked for that action; when it is your own idea, say what you would run and
wait for them.

A workstream's conductor decides what its work does next. Pass word on to it only when the work needs
it — a change of direction, an answer the work is waiting on, a grant the person gives — and only
when the person asked you to or plainly meant it; questions, explanations and remarks stay here.
Pass word on with `nido:workstream:tell :project <p> :ws-id <id> :text \"…\"`, in the person's terms
and with what the conductor needs to act on it; when an answer is waiting on the person, the text
answers it. Add `:images <digest>,<digest>` for the person's images the conductor needs to see, by
the digests the context block names. When the person's word is to land a workstream — \"land it\",
\"merge it\" — that word is their grant: add `:grants merge`. Never infer a grant they did not give.

You operate nido; you do not change it. Do not edit code in this worktree — if something in nido
itself looks wrong, say so. Never push, fast-forward or move main, and never record a landing.

Answer briefly, and name workstreams and tickets as the dashboard does.")

(def ^:private view
  "The panel's view: its actions routed under /_chat, attaching offered."
  {:id panel-id :router (host/router (str "/" (str/join "/" prefix))) :attach? true})

(defn- image-refs
  "A reference per image a message carries, in order, naming its digest — so the agent can pass an
   image on by digest."
  [images]
  (map-indexed (fn [i {:keys [digest]}]
                 {:label  (str "image " (inc i) " · " (subs digest 0 12))
                  :detail (str "Image " (inc i) " of this message, attached after its words, digest " digest ".")})
               images))

(defn- operator-home
  "Where the operator's agent runs: its session's home, brought up first when it is down. Not the
   session's worktree — on project nido that is a copy of nido which nothing keeps current, and a
   `bb --config bb.edn` run there runs that copy's verbs. The home's bb.edn links nido's own, and
   its briefing is what the agent reads."
  [{:keys [project session]}]
  (let [opts {:project project}]
    (when-not (lifecycle/runtime-of session opts) (lifecycle/up! session opts))
    (state/session-home-dir project session)))

(def ^:private operator
  "nido's operator: one session of nido's, told `instructions`, run in its home (`operator-home`).
   A send keeps each image it carries for a relay before the agent hears of it, and names it to
   the agent by digest."
  {:project "nido" :session "operator" :name "operator" :instructions instructions
   :dir operator-home
   :backend {:send! (fn [c words refs images]
                      (doseq [img images] (wcore/keep-chat-image! img))
                      (chat/send! c words (into (vec refs) (image-refs images)) images))}})

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  open?
  "Whether the request's page should render the chat open: what the person last left it as."
  [req]
  (boolean (some->> (get-in req [:headers "cookie"])
                    (re-find (re-pattern (str "(?:^|;\\s*)" cookie "=1(?:;|$)"))))))

(defn ^{:malli/schema [:=> [:cat :boolean] :any]}
  panel
  "The chat's side panel, open or closed as `open?` says, with what keeps it honest across pages:
   the cookie that remembers it open or closed, and ⌘I/Ctrl+I to open and close it. It holds
   nothing about the page — the place a message names is read from the send."
  [open?]
  (let [open (names/signal (:id panel/panel) panel-id :open)]
    (block/render panel/panel panel-id
                {:transcript (chat/transcript operator)
                 :open?   open?
                 :heading "nido"
                 :reset?  true
                 :attach? true
                 :live?   true
                 :extra   [[:span {:hidden true
                                   "data-effect" (str "document.cookie = '" cookie "=' + ($" open " ? 1 : 0)"
                                                      " + '; path=/; max-age=31536000; samesite=lax'")
                                   "data-on:keydown__window"
                                   (str "if ((evt.metaKey || evt.ctrlKey) && (evt.key === 'i' || evt.key === 'I'))"
                                        " { evt.preventDefault(); $" open " = !$" open " }")}]]}
                {} (:router view))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  handle!
  "One request under /_chat — the stream, a send, a stop, a reset, a permission answer, an image —
   or nil for any other."
  [req]
  (host/handle! (host/host operator view prefix (comp place/references place/place)) req))
