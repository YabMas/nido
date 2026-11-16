(ns nido.ui.catalog.chat
  "The catalog's chat: which conversation the catalog talks to, the page that hosts it, and the
   requests its panel makes.

   HIDES which conversation that is — one design session of nido's, `catalog-design`, made on the
   first send and resumed after — how a picked element is worded for the agent, that the frame's
   requests are forwarded to the design session's preview, that a finished turn refreshes the
   preview and reloads the frame, and how the session's unlanded change is published or thrown
   away.

   The frame always loads from this dashboard's origin. Until the design conversation exists its
   scene page and its scenes' posts are this dashboard's own; after, they are forwarded to the
   design session's preview — the code the agent is editing, synced and restarted at the end of
   every turn — so the page names no other port. The daemon's dashboard never loads that code.

   The change bar above the log is the session's unlanded change. Publish runs the landing verb in
   the session's worktree and reports its steps as they happen; Cancel throws the change away.
   Neither runs while a turn does, and no message is sent while a publish runs: a change still
   being made is not one to land, and a change being landed is not one to edit.

   The panel's markup is the chat block's and the page's is the catalog's; the conversation and the
   preview are the Session band's, and keeping a panel live — its stream, send, stop and permission
   answers — is `nido.ui.chat-host`'s. What this adds to the host is the picks, the frame and the
   change bar."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [nido.session.change :as change]
            [nido.session.chat :as chat]
            [nido.session.preview :as preview]
            [nido.ui.catalog :as catalog]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]
            [nido.ui.datastar :as datastar]
            [nido.ui.views :as views]))

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

Keep the working copy's description current: at the end of every turn that changed code, run
`jj describe -m '<message>'` so it describes everything this worktree holds that main does not,
following the commit-message doctrine in CLAUDE.md. The catalog shows that description as what
Publish would land.

Between your turns the person may Publish the change (it lands on main through `bb nido:land`, and
the worktree is left on the new main) or Cancel it (every unlanded edit is thrown away and the
worktree is left on main). Check `jj st` and `jj log -r 'main@origin..@'` before building on what
you did earlier.

Never push, fast-forward or move main, and never record a landing: landing is the person's own
deliberate act — Publish in the catalog, or a terminal — never a consequence of a chat message.")

(defonce ^:private publishing
  ;; what the change bar says beside the change: {:running? :step}, {:landed}, {:refused},
  ;; {:reverted? true} or {} — see nido.ui.components.chat/change-bar
  (atom {}))

(def ^:private routes
  {:id         panel-id
   :send-url   "/_catalog/chat/send"
   :cancel-url "/_catalog/chat/cancel"
   :stream-url "/_catalog/chat/stream"
   :answer-url (fn [item option] (str "/_catalog/chat/permission/" item "/" option))
   :unpick-url (fn [i] (str "/_catalog/chat/unpick/" i))
   :picks-url  "/_catalog/chat/picks"})

(def ^:private change-routes
  {:publish-url "/_catalog/chat/publish"
   :revert-url  "/_catalog/chat/revert"})

(def ^:private html host/html)

(def ^:private frame-src
  "Where the frame loads the scene page from: always this dashboard, which forwards it to the
   design session's preview once the conversation exists."
  "/_catalog?embed=1")

(declare design-chat chat-host)

(defn- pending-change
  "The design session's unlanded change, or nil — also when there is no session to read yet."
  []
  (try (change/pending project design-session) (catch Exception _ nil)))

(defn- bar-event []
  (datastar/patch-elements-event
   (html (panel/change-bar panel-id (pending-change) @publishing change-routes))))

(defn- reload-frames!
  "Reload every open page's frame. The value only has to change: the page keeps the scene it was
   on and loads it from its own origin."
  []
  (host/broadcast! chat-host (datastar/patch-signals-event
               (json/generate-string {:catcFrame (str "#" (System/currentTimeMillis))}))))

(defn- refresh-frame!
  "After a turn: bring the preview up to date with what the agent changed, and reload every open
   page's frame from it. When the sync refuses or the preview will not start, the frame is left on
   what it showed and the conversation says why, under the turn: the edit is in the worktree but
   not on screen, and a person must not read the old frame as the new code."
  [_]
  (try
    (preview/refresh! project design-session)
    (reload-frames!)
    (catch Exception e
      (chat/note-error! design-chat (str "The preview was not refreshed, so the frame still shows "
                                         "the code before this turn: " (ex-message e)))))
  (host/broadcast! chat-host (bar-event)))

(def ^:private design-chat
  {:project project :session design-session :name "catalog"
   :instructions instructions :on-turn-end refresh-frame!})

(defn- pick-ref
  "A pick as the agent reads it: its label, which the person reads on its chip — only the element
   as tag.class, since its numbered mark on the scene already says where it is — the title the
   chip shows on hover, block · scene · element, and the detail that lets the agent find the
   element in the code, where the full path is."
  [{:strs [block scene path text html]}]
  (let [el (-> (str path) (str/split #" > ") last (str/replace #":\d+$" ""))]
  {:label  el
   :title  (str block " · " scene " · " el)
   :detail (str "Selected in nido's component catalog.\n"
                "block: " block " (a function in nido.ui.components.*)\n"
                "scene: " scene " (in nido.ui.catalog.scenes)\n"
                "element path from the scene's frame: " path "\n"
                (when (seq text) (str "its text: " text "\n"))
                "it rendered as: " html)}))

(defn- posted-picks
  "The picks signal a request posted, as a vector of string-keyed maps; empty when absent or not
   an array of objects."
  [body]
  (let [v (try (some-> (get body (:picks (panel/signals panel-id))) not-empty json/parse-string)
               (catch Exception _ nil))]
    (if (and (sequential? v) (every? map? v)) (vec v) [])))

(defn- picks-event [ps]
  (datastar/patch-elements-event
   (html (panel/picks panel-id (mapv #(select-keys (pick-ref %) [:label :title]) ps) routes))))

(def ^:private chat-host
  (assoc (host/host design-chat routes ["_catalog" "chat"] (fn [body] (mapv pick-ref (posted-picks body))))
         ;; A sent message took its picks with it.
         :on-sent (fn [] [(datastar/patch-signals-event
                           (json/generate-string {(:picks (panel/signals panel-id)) "[]"}))
                          (picks-event [])])
         ;; No message is sent while a publish runs: a change being landed is not one to edit.
         :holds?  (fn [] (boolean (:running? @publishing)))
         :opening (fn [] [(bar-event)])))

(defn- unpick [req i]
  (let [ps   (posted-picks (host/body-json req))
        kept (vec (keep-indexed (fn [j p] (when (not= j i) p)) ps))]
    (host/sse (datastar/patch-signals-event
          (json/generate-string {(:picks (panel/signals panel-id)) (json/generate-string kept)}))
         (picks-event kept))))

(def ^:private step-line #"^land · (.+)$")
(def ^:private refused-line #"^(?:land|land:record) REFUSED · (.+)$")
(def ^:private landed-line #"^land ok · ([0-9a-f]+) ")

(defn- after-change!
  "Bring the preview and every open frame to what the worktree holds now, and say so in the bar."
  []
  (try (preview/refresh! project design-session) (reload-frames!)
       (catch Exception e
         (chat/note-error! design-chat (str "The preview was not refreshed: " (ex-message e)))))
  (host/broadcast! chat-host (bar-event)))

(defn- publish!
  "Start landing the design session's change, unless a turn or a publish is running; the bar
   follows the verb's steps, then says how it ended, and the conversation gets a note."
  []
  (when (and (not (:busy? (chat/transcript design-chat)))
             (not (:running? (first (swap-vals! publishing #(if (:running? %) % {:running? true}))))))
    (host/broadcast! chat-host (bar-event))
    (future
      (let [seen (atom {})
            exit (try (change/publish! project design-session
                                       (fn [line]
                                         (when-let [[_ st] (re-find step-line line)]
                                           (swap! publishing assoc :step st)
                                           (host/broadcast! chat-host (bar-event)))
                                         (when-let [[_ why] (re-find refused-line line)]
                                           (swap! seen assoc :refused why))
                                         (when-let [[_ c] (re-find landed-line line)]
                                           (swap! seen assoc :landed c))))
                      (catch Exception e (swap! seen assoc :refused (ex-message e)) -1))
            {:keys [landed refused]} @seen]
        (if (zero? exit)
          (do (reset! publishing {:landed (or landed "the change")})
              (chat/note! design-chat (str "Published to main as " (or landed "a new commit") ".")))
          (do (reset! publishing {:refused (or refused (str "bb nido:land exited " exit))})
              (chat/note! design-chat (str "Not published: " (or refused (str "bb nido:land exited " exit))))))
        (after-change!)))
    true))

(defn- revert!
  "Throw the design session's change away, unless a turn or a publish is running."
  []
  (when-not (or (:busy? (chat/transcript design-chat)) (:running? @publishing))
    (try (change/revert! project design-session)
         (reset! publishing {:reverted? true})
         (chat/note! design-chat "The person threw away every unlanded edit; the worktree is on main.")
         (catch Exception e
           (reset! publishing {:refused (str "the edits could not be thrown away: " (ex-message e))})))
    (after-change!)))

(defn- forwarded?
  "Whether `req` is the frame's — the scene page or a scene's post — and so the preview's to answer
   once the design conversation exists."
  [{:keys [request-method query-string]} segs]
  (and (chat/started? design-chat)
       (case request-method
         :get  (and (= ["_catalog"] segs)
                    (contains? (set (str/split (str query-string) #"&")) "embed=1"))
         :post (= ["_catalog" "nowhere"] (take 2 segs))
         false)))

(defn ^{:malli/schema [:=> [:cat [:maybe :string] [:=> [:cat] :map]] :string]}
  page
  "/_catalog: the scene page alone when it is the frame (`embed=1`), else the catalog with its
   chat — closed, unless the query string says `chat=1` — inside the dashboard's page chrome.
   `chrome` returns that chrome's context (see `nido.ui.views/shell`); it is called only for the
   page around the frame, so a frame reload never pays for it."
  [query-string chrome]
  (let [params (set (str/split (str query-string) #"&"))]
    (if (params "embed=1")
      (catalog/catalog-page query-string)
      (views/shell (assoc (chrome) :title "UI Catalog" :bleed? true)
                   (catalog/chat-page (chat/transcript design-chat) frame-src routes
                                      (boolean (params "chat=1"))
                                      (panel/change-bar panel-id (pending-change) @publishing change-routes))))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  handle!
  "One request under /_catalog/chat — the stream, a send, a cancel, a permission answer, the chips
   for the posted picks, removing one pick, a publish or a revert — or one of the frame's requests
   while it is the preview's (`forwarded?`); nil for any other."
  [{:keys [uri request-method] :as req}]
  (let [segs (vec (remove str/blank? (str/split (str uri) #"/")))]
    (if (forwarded? req segs)
      (preview/forward! project design-session req)
      (or (host/handle! chat-host req)
          (when (= ["_catalog" "chat"] (take 2 segs))
            (case [request-method (get segs 2)]
              [:post "picks"]   (host/sse (picks-event (posted-picks (host/body-json req))))
              [:post "unpick"]  (if-let [i (some-> (get segs 3) parse-long)]
                                  (unpick req i)
                                  {:status 404 :body ""})
              [:post "publish"] (do (publish!) {:status 204})
              [:post "revert"]  (do (revert!) {:status 204})
              {:status 404 :body ""}))))))
