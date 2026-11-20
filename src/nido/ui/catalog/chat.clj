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

   The change bar above the log is the session's unlanded change. Publish seals the change the bar
   showed and lands that revision through the landing verb, reporting its steps as they happen;
   Cancel throws the change away. Neither starts while a turn runs — a change still being made is
   not one to land — but the conversation stays open while a change lands: the agent's next edits
   go on a change above the sealed one, listed apart from it, and are brought onto main between
   turns once it has landed.

   One lock, the worktree's, orders everything that writes the design worktree's history from
   here: a send's acceptance (so no turn starts mid-seal), a seal — held until the landing verb has
   loaded its code from the sealed tree — and bringing the worktree onto main.

   The panel's markup is the chat block's and the page's is the catalog's; the conversation and the
   preview are the Session band's, and keeping a panel live — its stream, send, stop and permission
   answers — is `nido.ui.chat-host`'s. What this adds to the host is the picks, the frame and the
   change bar."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [nido.coordinator.control :as control]
            [nido.libraries.ui.block :as block]
            [nido.session.change :as change]
            [nido.session.chat :as chat]
            [nido.session.preview :as preview]
            [nido.ui.catalog :as catalog]
            [nido.ui.chat-host :as host]
            [nido.ui.components.chat :as panel]
            [nido.libraries.ui.wire :as wire]
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

Between your turns the person may Publish the change or Cancel it. Publish fixes the change as it
stood and starts a new, empty change above it for you; while that lands on main through
`bb nido:land` you may keep working, and between your turns nido brings this worktree onto the new
main, dropping what landed. If that rebase conflicts with main, the conflicted files are in this
worktree for you to resolve in your next turn. Cancel throws every unlanded edit away and leaves the
worktree on main. Check `jj st` and `jj log -r 'main@origin..@'` before building on what you did
earlier.

Never push, fast-forward or move main, and never record a landing: landing is the person's own
deliberate act — Publish in the catalog, or a terminal — never a consequence of a chat message.")

(defonce ^:private publishing
  ;; what the change bar says beside the change: {:running? :step :sealed}, {:landed :sealed},
  ;; {:refused}, {:reverted? true} or {} — see nido.ui.components.chat/change-bar. :sealed is the
  ;; revision a publish fixed, held from its seal until the worktree is back on main; lost with the
  ;; process, after which the bar shows one pending change until the next rejoin.
  (atom {}))

(defonce ^:private worktree-lock
  ;; The design worktree's history is written from here by a send's acceptance, a seal and a rejoin,
  ;; one at a time. A semaphore, not a monitor: a Publish takes it on a request thread and its
  ;; landing releases it from another, once the verb has loaded its code.
  (java.util.concurrent.Semaphore. 1))

(defonce ^:private noted-conflicts
  ;; The conflicted paths last said in the conversation, so a rejoin checked again on every stream
  ;; opening says them once.
  (atom nil))

(defonce ^:private rejoin-deferred
  ;; A rejoin that found the worktree lock held, owed to whoever releases it next.
  (atom false))

(declare rejoin!)

(defn- release-worktree!
  "Release the worktree lock, then make a rejoin that found it held."
  []
  (.release worktree-lock)
  (when (compare-and-set! rejoin-deferred true false)
    (rejoin!)))

(defn- with-worktree-lock
  "Run `f` holding the worktree lock, waiting for it."
  [f]
  (.acquire worktree-lock)
  (try (f) (finally (release-worktree!))))

(defn- bar-value
  "What the change bar renders: how the last publish went, the change it is landing, and the
   latest landing as Control reports it at this render — asked each time, because the daemon
   restarting onto a landing is what erases `publishing`, and the record is what survives it."
  []
  (let [{:keys [sealed] :as p} @publishing]
    (cond-> (assoc (dissoc p :sealed) :restart (control/latest-landing))
      ;; As long as the sealed revision is held — after it lands too, until the worktree rejoins.
      sealed (assoc :landing (try (change/landing project design-session sealed)
                                                 (catch Exception _ nil))))))

(def ^:private view
  "The panel's view: every action of the panel and its change bar under /_catalog/chat. It offers
   attaching images, as the dashboard's own chat does; the host answers those requests itself."
  {:id panel-id :router (host/router "/_catalog/chat") :attach? true})

(def ^:private html host/html)

(def ^:private frame-src
  "Where the frame loads the scene page from: always this dashboard, which forwards it to the
   design session's preview once the conversation exists."
  "/_catalog?embed=1")

(declare design-chat chat-host)

(defn- pending-change
  "The design session's unlanded change, or nil — also when there is no session to read yet. While
   a publish holds a sealed revision, only what the worktree holds above it."
  []
  (try (change/pending project design-session (:sealed @publishing)) (catch Exception _ nil)))

(defn- change-bar
  "The change bar as it stands: the design session's unlanded change and how the last publish
   went."
  []
  (block/render panel/change-bar panel-id {:change (pending-change) :publishing (bar-value)}
                {} (:router view)))

(defn- bar-event []
  (wire/patch-elements-event (html (change-bar))))

(defn- reload-frames!
  "Reload every open page's frame. The value only has to change: the page keeps the scene it was
   on and loads it from its own origin."
  []
  (host/broadcast! chat-host (wire/patch-signals-event
               (json/generate-string {:catcFrame (str "#" (System/currentTimeMillis))}))))

(defn- refresh-frame!
  "After a turn: bring the worktree onto main if a landing left it behind, then the preview up to
   date with what the agent changed, and reload every open page's frame from it. When the sync
   refuses or the preview will not start, the frame is left on what it showed and the conversation
   says why, under the turn: the edit is in the worktree but not on screen, and a person must not
   read the old frame as the new code."
  [_]
  (rejoin!)
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

(defn- parse-picks
  "The picks signal's JSON text as a vector of string-keyed maps; empty when absent or not an
   array of objects."
  [text]
  (let [v (try (some-> text not-empty json/parse-string) (catch Exception _ nil))]
    (if (and (sequential? v) (every? map? v)) (vec v) [])))

(defn- posted-picks
  "The picks a request posted through the panel's action `k`, read by the panel's decoder; empty
   when the decoder refused the body."
  [k req]
  (parse-picks (:picks (:ok (host/decode k panel-id req)))))

(defn- picks-event [ps]
  (wire/patch-elements-event
   (html (host/part view :picks {:picks (mapv #(select-keys (pick-ref %) [:label :title]) ps)}))))

(def ^:private chat-host
  (assoc (host/host design-chat view ["_catalog" "chat"]
                    (fn [{:keys [fields]}] (mapv pick-ref (parse-picks (:picks fields)))))
         ;; A sent message took its picks with it.
         :on-sent (fn [] [(wire/patch-signals-event (host/signals-patch panel-id {:picks "[]"}))
                          (picks-event [])])
         ;; A send waits while a seal or a rejoin holds the worktree; a landing never refuses one.
         :guard   with-worktree-lock
         ;; A dashboard restarted after a landing rejoins the worktree when the catalog is opened.
         :opening (fn [] (rejoin!) [(bar-event)])))

(defn- unpick [req i]
  (let [ps   (posted-picks :unpick req)
        kept (vec (keep-indexed (fn [j p] (when (not= j i) p)) ps))]
    (host/sse (wire/patch-signals-event (host/signals-patch panel-id {:picks (json/generate-string kept)}))
              (picks-event kept))))

(def ^:private step-line #"^land · (.+)$")
(def ^:private refused-line #"^(?:land|land:record) REFUSED · (.+)$")
(def ^:private pushed-line #"^land pushed · ([0-9a-f]+)$")
(def ^:private failed-line #"^land · coordinator no restart owed · FAILED · (.+)$")

(defn- after-change!
  "Bring the preview and every open frame to what the worktree holds now, and say so in the bar.
   While a turn runs only the bar: the turn's own end refreshes the preview."
  []
  (when-not (:busy? (chat/transcript design-chat))
    (try (preview/refresh! project design-session) (reload-frames!)
         (catch Exception e
           (chat/note-error! design-chat (str "The preview was not refreshed: " (ex-message e))))))
  (host/broadcast! chat-host (bar-event)))

(defn- onto-main!
  "Holding the worktree lock, with no turn running: bring the worktree onto origin's main if it is
   not already there, forget the sealed revision once it is, and say in the conversation which
   paths a rebase left conflicted — once for each set of them. A worktree that cannot be read is
   left as it is."
  []
  (when-let [paths (try (change/onto-main! project design-session) (catch Exception _ nil))]
    (swap! publishing dissoc :sealed)
    (when (and (seq paths) (not= paths @noted-conflicts))
      (chat/note! design-chat (str "Bringing the newer edits onto main left conflicts in "
                                   (str/join ", " paths) "; resolve them before publishing.")))
    (reset! noted-conflicts (not-empty paths))))

(defn- rejoin-held!
  "Holding the worktree lock: `onto-main!`, when neither a turn nor a publish runs."
  []
  (when-not (or (:busy? (chat/transcript design-chat)) (:running? @publishing))
    (onto-main!)))

(defn- rejoin!
  "Bring the worktree onto main, as `rejoin-held!` says. When something holds the worktree now —
   a send, seal or rejoin — the rejoin is made when it releases the lock. Never waits for the lock,
   so a caller already holding it cannot deadlock on it."
  []
  ;; Owed before the attempt: a holder releasing between a failed attempt and the mark would miss it.
  (reset! rejoin-deferred true)
  (when (.tryAcquire worktree-lock)
    (reset! rejoin-deferred false)
    (try (rejoin-held!) (finally (release-worktree!)))))

(defn- land!
  "Land the sealed revision `sealed` — the worktree lock held, and released here as soon as the
   landing verb prints its first `land · ` line, by which it has loaded its code from the sealed
   tree. The bar follows its steps; a push origin's main took is a landing, whatever went wrong
   after it, and anything before it is a refusal that leaves every edit where it was."
  [sealed]
  (let [seen     (atom {})
        released (atom false)
        release! #(when (compare-and-set! released false true) (release-worktree!))
        exit     (try (change/publish! project design-session sealed
                                       (fn [line]
                                         (when-let [[_ st] (re-find step-line line)]
                                           (release!)
                                           (swap! publishing assoc :step st)
                                           (host/broadcast! chat-host (bar-event)))
                                         (when-let [[_ why] (re-find refused-line line)]
                                           (swap! seen update :refused #(or % why)))
                                         (when-let [[_ why] (re-find failed-line line)]
                                           (swap! seen assoc :failed why))
                                         (when-let [[_ c] (re-find pushed-line line)]
                                           (swap! seen assoc :landed c))))
                      (catch Exception e (swap! seen assoc :refused (ex-message e)) -1)
                      (finally (release!)))
        {:keys [landed refused failed]} @seen]
    (if landed
      (do (reset! publishing {:landed landed :sealed sealed})
          (chat/note! design-chat
                      (str "Published to main as " landed "."
                           (when-not (zero? exit)
                             (str " What followed the push did not complete: "
                                  (or failed refused (str "bb nido:land exited " exit)) ".")))))
      (let [why (or refused (str "bb nido:land exited " exit))]
        (reset! publishing {:refused why})
        (chat/note! design-chat (str "Not published: " why ". Nothing was pushed, and every edit, "
                                     "the ones made since Publish included, is still in the worktree."))))
    (rejoin!)
    (after-change!)))

(defn- publish!
  "Publish the change the bar showed as `rev`: under the worktree lock, unless a turn or a publish
   is running, bring the worktree onto main if a landing left it behind, then seal `rev` and land
   it. A change that is no longer `rev` by then — the agent changed it, or the rejoin moved it — is
   not sealed, and the bar is rendered again to show what it is now."
  [rev]
  (when (and rev
             (not (:busy? (chat/transcript design-chat)))
             (not (:running? (first (swap-vals! publishing #(if (:running? %) % (assoc % :running? true)))))))
    (future
      (.acquire worktree-lock)
      (let [sealed (try (when-not (:busy? (chat/transcript design-chat))
                          (onto-main!)
                          (change/seal! project design-session rev))
                        (catch Exception e
                          (chat/note-error! design-chat (str "The change could not be sealed: " (ex-message e)))
                          nil))]
        (if sealed
          (do (reset! publishing {:running? true :sealed sealed})
              (host/broadcast! chat-host (bar-event))
              (land! sealed))
          (do (swap! publishing dissoc :running?)
              (release-worktree!)
              (host/broadcast! chat-host (bar-event))))))
    true))

(defn- revert!
  "Throw the design session's change away, unless a turn or a publish is running."
  []
  (when (with-worktree-lock
          #(when-not (or (:busy? (chat/transcript design-chat)) (:running? @publishing))
             (try (change/revert! project design-session)
                  (reset! publishing {:reverted? true})
                  (chat/note! design-chat "The person threw away every unlanded edit; the worktree is on main.")
                  (catch Exception e
                    (reset! publishing {:refused (str "the edits could not be thrown away: " (ex-message e))})))
             true))
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
                   (catalog/chat-page (chat/transcript design-chat) frame-src view
                                      (boolean (params "chat=1"))
                                      (change-bar))))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :map]]}
  handle!
  "One request under /_catalog/chat — the stream, a send, a cancel, a reset, a permission answer,
   the chips for the posted picks, removing one pick, a publish or a revert — or one of the frame's
   requests while it is the preview's (`forwarded?`); nil for any other. A reset leaves the design
   session's change as it is, but until the next send the frame is this dashboard's own."
  [{:keys [uri request-method] :as req}]
  (let [segs (vec (remove str/blank? (str/split (str uri) #"/")))]
    (if (forwarded? req segs)
      (preview/forward! project design-session req)
      (or (host/handle! chat-host req)
          (when (= ["_catalog" "chat"] (take 2 segs))
            (case [request-method (get segs 2)]
              [:post "picks"]   (host/sse (picks-event (posted-picks :sync-picks req)))
              [:post "unpick"]  (if-let [i (some-> (get segs 3) parse-long)]
                                  (unpick req i)
                                  {:status 404 :body ""})
              [:post "publish"] (do (publish! (some->> (:query-string req) (re-find #"(?:^|&)rev=([0-9a-f]+)") second))
                                    {:status 204})
              [:post "revert"]  (do (revert!) {:status 204})
              {:status 404 :body ""}))))))
