(ns nido.ui.side-chat
  "The dashboard's side chats: each one nido's operator in a conversation of its own, in a side
   panel on every page the server draws through the shell with the dashboard's context — the
   catalog, which keeps its own chat, aside. A chat belongs to the place it was started from: it
   is shown there, and keeps working while the person is elsewhere, until the person unlinks it,
   when it follows them from page to page — or pins it again, at the place they are. A panel
   shows every chat visible where the person is: those pinned to that place and those that follow
   the person.

   HIDES the operator — what its agent is told, where it runs (its session's home, not its
   worktree: see `operator-home`), and that it reaches a workstream's conductor only through
   `bb nido:workstream:tell`, by its own judgment, so nothing the person sends is a core command
   — each chat's anchor log and where it is kept, when the person last had each chat open, the
   paths a chat's actions are served under, and that whether the panel is open is kept in a
   cookie, so the next page renders it the same way rather than flashing it open.

   Where a chat belongs is its anchor log, which only grows: `started` at the anchor of the place
   it was begun from, `unlinked` where the person took it from, `pinned` at the anchor of the
   place they pinned it at, `closed` where they ended it. The first entry is where it came from;
   the last says where it is now. A place's anchor is its workstream, by project and id, when it
   has one open, else its page's path — the entry or gate open on a workstream is not part of it.
   The log is kept apart from the conversation, under nido's home, so a dashboard restart or a
   reset of the conversation leaves it whole.

   Where a message was sent from is read on the server for each send, from the send request:
   `nido.ui.place`'s place of it, and the chat's anchor at that moment beside it, so nothing the
   page set up in the browser decides either, and a send whose page cannot be read still says so.
   A page draws its panel from its own location; every later change of the location — the pane
   replacing it as its reading position moves, a step back or forward — has the panel ask the
   server again for the chats visible at the place the request states. Each image a message
   carries is named to the agent by digest and kept in the side chat's own store, so a relay can
   pass it on.

   The operator operates nido through its own bb verbs and changes no code, and is denied, like
   every chat, the commands that push or land."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [nido.coordinator.work.core :as wcore]
            [nido.libraries.ui.block :as block]
            [nido.libraries.ui.names :as names]
            [nido.libraries.ui.wire :as wire]
            [nido.platform.core :as core]
            [nido.session.chat :as chat]
            [nido.session.lifecycle :as lifecycle]
            [nido.session.state :as state]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]
            [nido.ui.place :as place]))

(def ^:private panel-id "nido-chat")
(def ^:private draft-id "chat-new")
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

The block also says where this chat belongs (\"chat · …\"): pinned to a workstream or a page, where
the person left it and will come back to it, or following them from page to page. A chat pinned to
a workstream is about that workstream unless the person says otherwise. A chat that follows them
may have earlier messages sent from other places.

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

;; ---------------------------------------------------------------------------
;; Anchors

(defn- anchor-of
  "The anchor of `place`: its workstream by project and id when it has one open, else its page's
   path without the query, or nil for an unknown place."
  [{:keys [known? page subject]}]
  (when known?
    (if (= :workstream (:kind subject))
      {:project (name (:project subject)) :ws-id (str (:ws-id subject))}
      {:path (first (str/split (str (:path page)) #"\?" 2))})))

(defn- anchor-words [{:keys [project ws-id path]}]
  (if project (str project "/" ws-id) path))

;; ---------------------------------------------------------------------------
;; The store: one directory per chat under nido's home, holding its anchor log (one AnchorEntry
;; per line, appended and never rewritten) and its state (its label, and when its turn last ended
;; and the person last had it open), which is rewritten. A stored log is trusted only through
;; `parse-log`.

(defonce ^:private store-lock (Object.))

(defn- store-dir [] (fs/path (core/nido-root) "side-chats"))

(defn- valid-id? [id] (boolean (and (string? id) (re-matches #"[a-z0-9][a-z0-9-]{0,63}" id))))

(defn- log-file [id] (fs/path (store-dir) id "anchor.log"))
(defn- state-file [id] (fs/path (store-dir) id "state.edn"))

(def ^:private AnchorEntry
  "One entry of a chat's anchor log: what happened, at which anchor — a workstream by project and
   id, or a page's path — and when. A `pinned` entry, and an `unlinked` one (where the chat was
   taken from), always name one; a `started` entry names one when the chat was begun at a known
   place, and none when it was begun following the person; a `closed` one names where it was
   closed, or none for an unknown place."
  (let [anchor [:or [:map {:closed true} [:project :string] [:ws-id :string]]
                    [:map {:closed true} [:path :string]]]
        entry  (fn [ev a] [:map {:closed true} [:event [:= ev]] [:anchor a] [:at :int]])]
    (m/schema [:multi {:dispatch :event}
               [:started  (entry :started [:maybe anchor])]
               [:pinned   (entry :pinned anchor)]
               [:unlinked (entry :unlinked anchor)]
               [:closed   (entry :closed [:maybe anchor])]])))

(defn- parse-log
  "The text of a stored anchor log as `{:log entries}`, oldest first, when every line is an
   AnchorEntry and the log is legal — one `started`, first, and nothing after a `closed` — or
   `{:refused why}`. The one place stored text becomes a log anything here trusts."
  [text]
  (let [lines (remove str/blank? (str/split-lines (str text)))
        entries (map-indexed (fn [i line]
                               (let [e (try (edn/read-string line) (catch Exception _ ::unreadable))]
                                 (if (m/validate AnchorEntry e) e {::bad (inc i)})))
                             lines)]
    (if-let [{n ::bad} (some #(when (::bad %) %) entries)]
      {:refused (str "line " n " is not an anchor entry")}
      (let [log (vec entries)]
        (cond
          (empty? log)                                   {:refused "the log is empty"}
          (not= :started (:event (first log)))           {:refused "the log does not begin with its start"}
          (some #(= :started (:event %)) (rest log))     {:refused "the log starts twice"}
          (some #(= :closed (:event %)) (butlast log))   {:refused "the log goes on after its close"}
          :else                                          {:log log})))))

(defn- stored
  "Chat `id` as the store holds it — `{:log entries}`, `{:refused why}` for a stored log that does
   not parse, which nothing shows and nothing writes to again — or nil for no such chat."
  [id]
  (let [f (log-file id)]
    (when (and (valid-id? id) (fs/exists? f))
      (parse-log (slurp (str f))))))

(defn- read-log
  "Chat `id`'s trusted anchor log, oldest first, or nil for a chat with none or one refused."
  [id]
  (:log (stored id)))

(defn- append!
  "Append one AnchorEntry, `event` at `anchor` (nil for none), to chat `id`'s log. A write that
   fails throws, failing the action that asked for it; the line is written whole or not at all
   as far as the parse can tell, since a torn line refuses the log rather than misreading it."
  [id event anchor]
  (locking store-lock
    (fs/create-dirs (fs/parent (log-file id)))
    (spit (str (log-file id))
          (str (pr-str {:event event :anchor anchor :at (System/currentTimeMillis)}) "\n")
          :append true)))

(defn- read-state [id]
  (let [f (state-file id)]
    (or (when (fs/exists? f) (try (edn/read-string (slurp (str f))) (catch Exception _ nil))) {})))

(defn- update-state! [id f & args]
  (locking store-lock
    (fs/create-dirs (fs/parent (state-file id)))
    (spit (str (state-file id)) (pr-str (apply f (read-state id) args)))))

(defn- where
  "Where chat `id`'s log says it is: `{:anchor a}` pinned to `a`, `{:anchor nil}` following the
   person, `{:closed? true}` shown nowhere, nil for a chat that has no log."
  [log]
  (when-let [{:keys [event anchor]} (peek log)]
    (case event
      (:started :pinned) {:anchor anchor}
      :unlinked          {:anchor nil}
      :closed            {:closed? true})))

(def ^:private legacy
  "The one conversation the side chat was before chats had places, which the store takes in as a
   chat that follows the person, so it is not lost."
  "operator")

(defn- chat-ids
  "Every chat the store holds, closed ones included."
  []
  (locking store-lock
    (when-not (fs/exists? (store-dir))
      (fs/create-dirs (store-dir))
      (when (chat/started? {:project "nido" :session "operator" :name legacy})
        (append! legacy :started nil)
        (update-state! legacy assoc :label "Earlier conversation")))
    (->> (fs/list-dir (store-dir))
         (map (comp str fs/file-name))
         (filter #(and (valid-id? %) (fs/exists? (log-file %))))
         sort)))

(defn- live-chat?
  "Whether `id` names a chat whose log parses and which is not closed: the only chats a request
   may act on, so nothing is appended to a refused log or after a close."
  [id]
  (let [w (some-> (read-log id) where)]
    (boolean (and w (not (:closed? w))))))

(defn- new-id []
  (str "c-" (System/currentTimeMillis) "-" (subs (str (java.util.UUID/randomUUID)) 0 8)))

(defn- seen!
  "Record that the person has chat `id` open now."
  [id]
  (update-state! id assoc :seen-at (System/currentTimeMillis)))

;; ---------------------------------------------------------------------------
;; The operator, one conversation per chat

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

(declare chat-host)

(defonce ^:private shown
  ;; chat id -> whether the last panel to report on it had it as its open row with the panel
  ;; expanded. A panel reports when it is drawn and whenever it is expanded or collapsed.
  (atom {}))

(defn- watching?
  "Whether the person has chat `id` open now: an expanded panel shows it as its open row, and its
   stream is live. A collapsed panel's stream keeps the toggle's mark live but is not the person
   reading the chat."
  [id]
  (boolean (and (get @shown id) (seq @(:streams (chat-host id))))))

(defonce ^:private watched
  ;; chat id -> when the permission it waits on was first seen, or nil when it waits on none. A
  ;; chat is watched from its first send in this process; a restart drops what it waited on, as
  ;; it drops the agent that asked.
  (atom {}))

(defn- asking? [t]
  (boolean (some #(and (= :permission (:kind %)) (nil? (:answer %))) (:items t))))

(declare operator)

(defn- watch!
  "Keep track of when chat `id` begins waiting on a permission, once per process."
  [id]
  (when-not (contains? @watched id)
    (swap! watched assoc id nil)
    (chat/subscribe! (operator id)
                     (fn [t]
                       (if (asking? t)
                         (when-not (get @watched id)
                           (swap! watched assoc id (System/currentTimeMillis))
                           (when (watching? id) (seen! id)))
                         (swap! watched assoc id nil))))))

(defn- label-of
  "A chat's label from a message's words: their first line, cut to 60; nil when they have none,
   so a message of images alone leaves the chat to be labelled by the next one with words."
  [words]
  (let [line (str/trim (str (first (str/split-lines (str words)))))]
    (cond (str/blank? line)    nil
          (> (count line) 60) (str (subs line 0 59) "…")
          :else               line)))

(defn- operator
  "nido's operator in chat `id`'s conversation: one session of nido's, told `instructions`, run in
   its home (`operator-home`). A send keeps each image it carries for a relay before the agent
   hears of it, names it to the agent by digest, and gives a chat its label from its first
   message with words; a turn's end is when the chat has something the person has not seen, unless a panel
   has it open."
  [id]
  {:project "nido" :session "operator" :name id :instructions instructions
   :dir operator-home
   :on-turn-end (fn [_]
                  (update-state! id assoc :turn-ended-at (System/currentTimeMillis))
                  (when (watching? id) (seen! id)))
   :backend {:send! (fn [c words refs images]
                      (doseq [img images] (wcore/keep-chat-image! img))
                      (when-let [sent (chat/send! c words (into (vec refs) (image-refs images)) images)]
                        (when-let [label (when (str/blank? (:label (read-state id))) (label-of words))]
                          (update-state! id assoc :label label))
                        (watch! id)
                        sent))}})

(defn- anchor-refs
  "Chat `id`'s anchor as the reference a message carries: where the chat belongs at the send."
  [id]
  (let [{:keys [anchor]} (where (read-log id))]
    [(if anchor
       {:label  (str "chat · pinned to " (anchor-words anchor))
        :detail (str "This chat is pinned to "
                     (if (:project anchor)
                       (str "workstream " (:project anchor) " " (:ws-id anchor)
                            ": it is shown wherever that workstream is open")
                       (str "the page " (:path anchor) ": it is shown there"))
                     ", where the person comes back to it.")}
       {:label  "chat · follows the person"
        :detail (str "This chat is pinned nowhere: it follows the person from page to page, so its "
                     "earlier messages may have been sent from other places.")})]))

(defn- view-of [id]
  {:id (str "chat-" id) :router (host/router (str "/_chat/c/" id)) :attach? true})

(def ^:private draft-chat
  "What the panel with no conversation yet stands on: a transcript that is empty and never
   changes, so its stream carries only the images staged for the first message — which starts a
   chat and takes them with it."
  {:backend {:transcript (fn [_] {:items []})
             :subscribe! (fn [_ _] (fn []))}})

(defn- draft-host
  "The host serving the empty panel's composer under /_chat/new: its stream, and the images
   staged before any chat exists."
  []
  (host/host draft-chat {:id draft-id :router (host/router "/_chat/new") :attach? true}
             ["_chat" "new"] (constantly [])))

(defn- chat-host
  "The host serving chat `id`'s conversation under /_chat/c/<id>."
  [id]
  (host/host (operator id) (view-of id) ["_chat" "c" id]
             (fn [req] (concat (place/references (place/place req)) (anchor-refs id)))))

;; ---------------------------------------------------------------------------
;; What a place shows

(defn- visible
  "The ids of the chats visible at `anchor`: those pinned to it and those following the person."
  [anchor]
  (for [id (chat-ids)
        :let [w (where (read-log id))]
        :when (and w (not (:closed? w))
                   (or (nil? (:anchor w)) (and anchor (= anchor (:anchor w)))))]
    id))

(defn- unseen?
  "Whether chat `id` ended a turn, or began waiting on a permission, after the person last had it
   open."
  [id]
  (let [{:keys [seen-at turn-ended-at]} (read-state id)
        seen (or seen-at 0)
        asked (get @watched id)]
    (boolean (or (and turn-ended-at (> turn-ended-at seen))
                 (and asked (> asked seen))))))

(defn- status-of [id t]
  (cond (asking? t)  :asking
        (:busy? t)   :busy
        (unseen? id) :unseen))

(def ^:private follow-js
  "Announce every change of the page's location as a `nido-location` event on the window, once per
   page. No `$` or `@` in it: Datastar would read either as a signal or an action."
  "(function(){
  if (window.nidoLocation) return;
  window.nidoLocation = location.href;
  function moved(){ if (location.href !== window.nidoLocation) { window.nidoLocation = location.href; window.dispatchEvent(new Event('nido-location')); } }
  ['pushState', 'replaceState'].forEach(function(k){ var f = history[k]; history[k] = function(){ var r = f.apply(this, arguments); moved(); return r; }; });
  window.addEventListener('popstate', moved);
  window.addEventListener('pageshow', function(e){ if (e.persisted) window.dispatchEvent(new Event('nido-location')); });
})()")

(defn- render-panel
  "The side panel showing the chats visible at `anchor`, open or closed as `open?` says: those that
   follow the person first, then those pinned here, each newest first, with the one the person
   last had open, open."
  [anchor open?]
  (let [ids     (visible anchor)
        logs    (into {} (map (juxt identity read-log)) ids)
        states  (into {} (map (juxt identity read-state)) ids)
        latest  (fn [id] (max (or (:at (peek (logs id))) 0)
                              (or (get-in states [id :seen-at]) 0)
                              (or (get-in states [id :turn-ended-at]) 0)))
        pinned? (fn [id] (some? (:anchor (where (logs id)))))
        ordered (sort-by (juxt pinned? (comp - latest)) ids)
        open-id (when (seq ordered)
                  (apply max-key #(or (get-in states [% :seen-at]) 0) (reverse ordered)))
        open    (names/signal (:id panel/panel) panel-id :open)
        busy-of (when open-id (str "chat-" open-id))]
    (list
     ;; The toggle lights while the open chat's turn runs, reading that chat's busy signal; the
     ;; signal is seeded here, ahead of the toggle, since an expression reading a signal that
     ;; does not exist yet never runs again.
     (when busy-of
       [:div {:id "nido-chat-busy" :hidden true
              "data-signals__ifmissing"
              (json/generate-string
               (assoc-in {} (conj (names/signal-root (:id panel/panel) busy-of) "busy")
                         (boolean (:busy? (chat/transcript (operator open-id))))))}])
     (block/render
     panel/panel panel-id
     {:transcript {:items []}
      :open?      open?
      :heading    "nido"
      :busy-of    busy-of
      :chats      (vec (for [id ordered
                             :let [t  (chat/transcript (operator id))
                                   o? (= id open-id)]]
                         {:id      id
                          :label   (or (not-empty (get-in states [id :label])) "New chat")
                          :where   (when-not (pinned? id) "follows you")
                          :pinned? (boolean (pinned? id))
                          :status  (status-of id t)
                          :open?   o?
                          :body    (when o?
                                     (let [{vid :id router :router} (view-of id)]
                                       (block/render panel/panel vid
                                                     {:transcript t :bare? true :attach? true :live? true}
                                                     {} router)))}))
      :empty      (block/render panel/panel draft-id
                                {:transcript {:items []} :bare? true :attach? true :live? true}
                                {} (host/router "/_chat/new"))
      :extra      [[:span {:hidden true
                           "data-effect" (str "document.cookie = '" cookie "=' + ($" open " ? 1 : 0)"
                                              " + '; path=/; max-age=31536000; samesite=lax'")
                           "data-on:keydown__window"
                           (str "if ((evt.metaKey || evt.ctrlKey) && (evt.key === 'i' || evt.key === 'I'))"
                                " { evt.preventDefault(); $" open " = !$" open " }")}]
                   ;; Whether the person is reading the open chat: reported when the panel is
                   ;; drawn and whenever it is expanded or collapsed.
                   (when open-id
                     [:span {:hidden true
                             "data-effect" (str "$" open " ? @post('/_chat/shown/" open-id "', {filterSignals: {include: /^$/}})"
                                                " : @post('/_chat/hidden/" open-id "', {filterSignals: {include: /^$/}})")}])
                   ;; Every change of the location after the page was drawn — the pane's
                   ;; replaceState as its reading position moves, a step back or forward, a page
                   ;; restored from the browser's cache — has the panel ask again for the chats
                   ;; at the place the request states (its Referer).
                   [:span {:hidden true
                           "data-init" follow-js
                           "data-on:nido-location__window"
                           (str "@post('/_chat/here', {filterSignals: {include: /^$/}})")}]]}
     {} (host/router "/_chat")))))

;; ---------------------------------------------------------------------------
;; The interface

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  open?
  "Whether the request's page should render the chat open: what the person last left it as."
  [req]
  (boolean (some->> (get-in req [:headers "cookie"])
                    (re-find (re-pattern (str "(?:^|;\\s*)" cookie "=1(?:;|$)"))))))

(defn ^{:malli/schema [:=> [:cat :map :boolean] :any]}
  panel
  "The side panel for the page `req` draws, open or closed as `open?` says: the chats visible at
   that page's own place, with what keeps it honest across pages — the cookie that remembers it
   open or closed, ⌘I/Ctrl+I to open and close it, and its following the location as it changes."
  [req open?]
  (render-panel (anchor-of (place/page-place req)) open?))

(defn ^{:malli/schema [:=> [:cat] [:set :map]]}
  unseen
  "The anchors of workstreams with a chat pinned to them that ended a turn, or began waiting on a
   permission, after the person last had it open — each `{:project :ws-id}`."
  []
  (into #{}
        (keep (fn [id]
                (let [{:keys [anchor closed?]} (where (read-log id))]
                  (when (and (:project anchor) (not closed?) (unseen? id)) anchor))))
        (chat-ids)))

(defn- panel-patch
  "The SSE event redrawing the panel for the place `req` was sent from."
  [req]
  (wire/patch-elements-event (host/html (render-panel (anchor-of (place/place req)) (open? req)))))

(defn- body-bytes [req]
  (when-let [b (:body req)] (if (string? b) (.getBytes ^String b "UTF-8") (.readAllBytes ^java.io.InputStream b))))

(defn- with-body [req ^bytes bs]
  (assoc req :body (when bs (java.io.ByteArrayInputStream. bs))))

(defn- start-from-draft!
  "A first message written where no chat was visible: a chat pinned to the place it was sent from,
   started with it. Nothing starts when the draft has neither words nor staged images, or when the
   panel's decoder refused the body."
  [req]
  (let [bs     (body-bytes req)
        fields (:ok (host/decode :send draft-id (with-body req bs)))
        words  (str/trim (str (:text fields)))]
    (if (or (nil? fields) (and (str/blank? words) (not (host/staged? (draft-host)))))
      (host/sse)
      (let [id     (new-id)
            p      (place/place req)]
        (append! id :started (anchor-of p))
        (seen! id)
        ;; The images staged in the empty panel go with the message that starts the chat.
        (host/take-staged! (draft-host) (chat-host id))
        (host/handle! (assoc (chat-host id)
                             :view (assoc (view-of id) :id draft-id :attach? false)
                             :on-sent (fn [] [(panel-patch req)]))
                      (with-body (assoc req :uri (str "/_chat/c/" id "/send")) bs))))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  handle!
  "One request under /_chat: the chats visible at the place the request states (`here`), a new
   chat pinned there (`new`, or a first message sent from the empty panel, `new/send`, taking the
   images staged there — `new/attach`, `new/unattach`, `new/image`, over `new/stream`), opening,
   pinning, unlinking or closing one (`open`, `pin`, `unlink`, `close`, each `/<id>`), the panel
   saying it shows one expanded or collapsed (`shown`, `hidden`), or one
   request of one chat's conversation (`c/<id>/…`) — or nil for any other path."
  [{:keys [uri request-method] :as req}]
  (let [[root verb id & more] (vec (remove str/blank? (str/split (str uri) #"/")))
        now-at (fn [] (anchor-of (place/place req)))
        redraw (fn [] (host/sse (panel-patch req)))]
    (when (= "_chat" root)
      (if (and (= "new" verb) (#{"stream" "attach" "unattach" "image"} id))
        (host/handle! (draft-host) req)
        (case [request-method verb]
          [:post "here"]   (redraw)
          [:post "new"]    (if (= "send" id)
                             (start-from-draft! req)
                             (let [nid (new-id)] (append! nid :started (now-at)) (seen! nid) (redraw)))
          [:post "open"]   (when (live-chat? id) (swap! shown assoc id true) (seen! id) (redraw))
          [:post "shown"]  (when (live-chat? id) (swap! shown assoc id true) (seen! id) {:status 204})
          [:post "hidden"] (when (live-chat? id) (swap! shown assoc id false) {:status 204})
          [:post "pin"]    (when (live-chat? id)
                             (when-let [a (now-at)] (append! id :pinned a))
                             (redraw))
          [:post "unlink"] (when (live-chat? id)
                             (when-let [a (:anchor (where (read-log id)))] (append! id :unlinked a))
                             (redraw))
          [:post "close"]  (when (live-chat? id) (append! id :closed (now-at)) (redraw))
          (when (and (= "c" verb) (live-chat? id) (seq more))
            (host/handle! (chat-host id) req)))))))
