(ns nido.ui.side-chat
  "The dashboard's chat: one conversation with an agent that operates nido's work, in a side panel
   on every page the server draws through the shell with the dashboard's context — the catalog,
   which keeps its own chat, aside — following the person from page to page.

   HIDES which conversation that is — one session of nido's, `operator`, made on the first send
   and resumed after — what its agent is told, how a message says which page it was sent from,
   and that whether the panel is open is kept in a cookie, so the next page renders it the same
   way rather than flashing it open.

   The agent operates nido; it does not change it. It reads and acts through nido's own bb verbs,
   and is denied, like every chat, the commands that push or land."
  (:require [clojure.string :as str]
            [nido.session.chat :as chat]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]))

(def ^:private panel-id "nido-chat")
(def ^:private cookie "nidoChat")
(def ^:private page-signal "nidoPage")

(def ^:private instructions
  "What the operator's agent is told, appended to its system prompt."
  "You are nido's operator: the agent in the chat panel beside every page of nido's dashboard. The
person runs nido, the orchestrator of their development workstreams, and asks you about the work it
holds — workstreams, tickets, runs, sessions, triggers, breakers, reviews — and to act on it. Each
message ends with a <nido-context> block naming the dashboard page it was sent from; \"this\" and
\"here\" usually mean what that page shows.

Work through nido's own bb verbs, run as your session briefing shows (`bb --config <nido>/bb.edn
...`); `bb --config <nido>/bb.edn tasks` lists them. Read freely. An action that changes something —
a workstream, ticket, session, trigger or breaker, or anything posted to Slack, Notion or GitHub —
you take when the person asked for that action; when it is your own idea, say what you would run and
wait for them.

You operate nido; you do not change it. Do not edit code in this worktree — if something in nido
itself looks wrong, say so. Never push, fast-forward or move main, and never record a landing.

Answer briefly, and name workstreams and tickets as the dashboard does.")

(def ^:private routes
  {:id         panel-id
   :send-url   "/_chat/send"
   :cancel-url "/_chat/cancel"
   :stream-url "/_chat/stream"
   :answer-url (fn [item option] (str "/_chat/permission/" item "/" option))
   :unpick-url (fn [i] (str "/_chat/unpick/" i))})

(def ^:private operator-chat
  {:project "nido" :session "operator" :name "operator" :instructions instructions})

(defn- page-ref
  "The page a message was sent from, as the agent reads it: the title the person sees and the path
   that names it. None when the page did not say."
  [body]
  (let [page (str/trim (str (get body page-signal)))]
    (when (seq page)
      [{:label  (str "page · " (first (str/split page #" — ")))
        :detail (str "Sent from nido's dashboard, on " page)}])))

(def ^:private chat-host (host/host operator-chat routes ["_chat"] page-ref))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  open?
  "Whether the request's page should render the chat open: what the person last left it as."
  [req]
  (boolean (some->> (get-in req [:headers "cookie"])
                    (re-find (re-pattern (str "(?:^|;\\s*)" cookie "=1(?:;|$)"))))))

(defn ^{:malli/schema [:=> [:cat :boolean] :any]}
  panel
  "The chat's side panel for a dashboard page, open or closed as `open?` says, with what keeps it
   honest across pages: the page it is on, for the next message; the cookie that remembers it open
   or closed; ⌘I/Ctrl+I to open and close it."
  [open?]
  (let [{:keys [open]} (panel/signals panel-id)]
    (panel/side (chat/transcript operator-chat) routes
                {:open?  open?
                 :extra  [[:span {:hidden true
                                  "data-signals" (str "{" page-signal ": ''}")
                                  "data-init" (str "$" page-signal " = document.title + ' — ' + "
                                                   "location.pathname + location.search")
                                  "data-effect" (str "document.cookie = '" cookie "=' + ($" open " ? 1 : 0)"
                                                     " + '; path=/; max-age=31536000; samesite=lax'")
                                  "data-on:keydown__window"
                                  (str "if ((evt.metaKey || evt.ctrlKey) && (evt.key === 'i' || evt.key === 'I'))"
                                       " { evt.preventDefault(); $" open " = !$" open " }")}]]})))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  handle!
  "One request under /_chat — the stream, a send, a stop, a permission answer — or nil for any
   other path."
  [req]
  (host/handle! chat-host req))
