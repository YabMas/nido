(ns nido.ui.catalog.scenes
  "Every dashboard block's named renderings over literal sample data, and the registry the
   catalog is a reading of.

   HIDES the sample data — which values make a block show the state worth looking at. A scene is
   a literal value and a router that goes nowhere, so rendering one needs no ledger, no
   registry, no session and no clock. That is not a convenience: it is how
   `blocks-render-from-a-value` is checked. A block that started reading something would make a
   scene throw here before it misled anyone on a real page.

   A scene's `:note` says what the scene is FOR — the state it exists to show — because that is
   what a person reviewing the block reads before they look at it. A scene that needs no note is
   usually a scene that shows nothing new.

   `:name` on a block is the published function's own name, and the catalog test holds the
   registry to covering every one of them."
  (:require [nido.libraries.ui.block :as block]
            [nido.ui.components.ask :as ask]
            [nido.ui.components.chat :as chat]
            [nido.ui.components.row :as row]
            [nido.ui.components.state :as state]))

;; ---------------------------------------------------------------------------
;; Routers that go nowhere
;;
;; A block that is clickable takes how a click is routed. A scene gives it one that posts to the
;; catalog's own dead end, so the markup is the markup the app renders — every data-on:click
;; present, every URL shaped as the real one — and clicking changes nothing. The card's stepper
;; and its choices are page state, so Previous/Next and marking a question answered work for
;; real in the catalog without any of this being reached.

(defn- nowhere-router
  "A block's router that goes nowhere: every action to the catalog's own dead end, its params
   in the path so the URL is shaped like the real one."
  [action params]
  (str "/_catalog/nowhere/" (name action)
       (apply str (for [[_ v] (sort-by key params)] (str "/" (if (keyword? v) (name v) v))))))

(defn- registered
  "A block registration's own scenes, as the catalog's group entry under the name its namespace
   publishes it by: each a thunk rendering the registration over the scene's props, on an
   instance id of its own."
  [published-as blk]
  {:name   published-as
   :note   (:doc blk)
   :scenes (vec (map-indexed
                 (fn [i {:keys [title note props]}]
                   {:title  title
                    :note   note
                    :render #(block/render blk (or (:ws-id props) (str "scene-" i)) props {}
                                           nowhere-router)})
                 (:scenes blk)))})

(def ^:private no-click
  "The Datastar expression a scene gives a row whose click patches a fragment in place."
  "$catalogNowhere = true")

;; ---------------------------------------------------------------------------
;; State marks

(def ^:private origins [:notion :github :slack :scratch nil])

(def ^:private board-stages
  [:triage :in-progress :awaiting-gate :shipping :winding-down :done :ready])

(def ^:private positions
  "Every position the pipeline can place a workstream at, in pipeline order. The scene over this
   is the one a person reads when they are judging the WORDS rather than the layout."
  [:intake :intent-stated :baselined :baseline-verified :designed :design-decided :design-cleared
   :design-approved :implementing :implemented :reviewed :triage-awaiting :awaiting-gate
   :published :shipped :findings-open :blocked :premise-retracted :design-retracted
   :intent-retracted :design-invalidated :goal-superseded :unplaceable])

(defn- rail
  "Several renderings side by side, each under the value that produced it — how a mark vocabulary
   is actually judged: not one at a time, but against its siblings."
  [label-fn xs render-fn]
  (into [:div.cat-rail]
        (for [x xs]
          [:div.cat-rail-i
           [:span.cat-scene-fn (label-fn x)]
           (render-fn x)])))

(defn- state-scenes []
  [{:name "origin-badge"
    :note "Where a workstream came from. One hue and one letter each. A workstream's own page
           carries it beside its title; the overview rows do not — they lead with the status
           mark instead."
    :scenes [{:title "every origin"
              :note "Four sources and the fallback. A scratch workstream and an unknown origin
                     both read as ·, which is deliberate — neither came from anywhere a person
                     can click through to."
              :render #(rail (fn [o] (if o (name o) "nil")) origins state/origin-badge)}]}
   {:name "stage-chip"
    :note "Which band of the board a row is grouped under."
    :scenes [{:title "every board stage"
              :note "Only triage, in-progress and ready have a hue of their own; the rest fall
                     through to the base chip. Worth seeing together: the three that are coloured
                     are not obviously the three that matter most."
              :render #(rail name board-stages state/stage-chip)}]}
   {:name "position-chip"
    :note "Where the pipeline says the work stands. Muted by default, lit only when the next
           move is a person's."
    :scenes [{:title "nido's move, and yours"
              :note "The whole point of the chip is this contrast — a board is scanned for the
                     lit ones. If they do not separate at a glance, the chip has failed."
              :render #(rail (fn [p] (name (:mode (:next p))))
                             [{:at :baselined :next {:stage :verify-baseline :mode :authoring}}
                              {:at :blocked :next {:stage :answer-blocker :mode :human}}]
                             state/position-chip)}
             {:title "with layers, and with a caller's tip"
              :note "A layered position carries done/of inside the chip; the second arity adds a
                     caller's phrase to the title, after the next move."
              :render #(list (state/position-chip {:at :implementing
                                                   :layers {:done 2 :of 5}
                                                   :next {:stage :implement :mode :authoring}})
                             " "
                             (state/position-chip {:at :blocked
                                                   :next {:stage :answer-blocker :mode :human}}
                                                  "waiting since Tuesday"))}
             {:title "no position"
              :note "A bare watched page, or a band the projection is not computed for. It must
                     render nothing at all rather than an empty chip."
              :render #(or (state/position-chip nil) [:span.cat-empty "renders nothing"])}]}
   {:name "phase-chip"
    :note "Which landing of a phased plan this is. Beside the position chip, not inside it."
    :scenes [{:title "phased, and not"
              :render #(list (state/phase-chip {:current 2 :of 3}) " "
                             (or (state/phase-chip {}) [:span.cat-empty "unphased: nothing"]))}]}
   {:name "needs-mark"
    :note "The next move is a person's. The one mark a board is scanned for, so it is the same dot
           on a board row, a needs-you row and an arc stage, with only its title saying how."
    :scenes [{:title "plain, and titled by its caller"
              :note "Hover each: a row's dot reads its default title, an arc stage's says what it
                     waits on."
              :render #(list (state/needs-mark) " " (state/needs-mark "waiting on you"))}]}
   {:name "state-mark"
    :note "Whether anything is happening right now, as one mark and no words. What runs and who
           owes the next move are the mark's title — hover to read them."
    :scenes [{:title "running, paused, stalled, quiet"
              :note "The four a board row can be in. Paused is a person's decision and stalled
                     is a fault, so they must not read alike — and quiet must render nothing at
                     all: a row where nothing is happening is the common case, and a mark on
                     every row would drown the three that mean something."
              :render #(rail first
                             [["live" {:owed-by :agent :live true} {:source :claim :kind :diff-review
                                                                    :progress {:round 2 :phase :fixing
                                                                               :findings 3}}]
                              ["paused" {:owed-by :agent :paused true} nil]
                              ["stalled" {:owed-by :agent :stalled true} nil]
                              ["quiet" {:owed-by :person} nil]]
                             (fn [[_ st doing]]
                               (or (state/state-mark st doing)
                                   [:span.cat-empty "nothing"])))}
             {:title "a merge keeps its words"
              :note "The one exception to no-words: a blocked merge has to read red on a board
                     that is scanned rather than read."
              :render #(rail first
                             [["queued" {:source :merge :phase :queued}]
                              ["driving" {:source :merge :phase :driving}]
                              ["blocked" {:source :merge :phase :blocked}]]
                             (fn [[_ d]] (state/state-mark {:owed-by :machine :live true} d)))}]}
   {:name "round-label"
    :note "The words beside a running arc stage — where a review round has got, and nothing for
           any other activity."
    :scenes [{:title "a round, and everything else"
              :render #(rail first
                             [["claim" {:source :claim :kind :design
                                        :progress {:round 3 :phase :judging :findings 0}}]
                              ["session" {:source :session :phase :implementing}]
                              ["merge" {:source :merge :phase :driving}]]
                             (fn [[_ d]] (if-let [l (state/round-label d)]
                                           [:span l]
                                           [:span.cat-empty "nothing"])))}]}
   {:name "status-heading"
    :note "The Status section's heading, carrying the position itself. A string, not markup —
           the pane wraps it."
    :scenes [{:title "every position, as a person reads it"
              :note "This is the wording review. Read the right-hand column alone and ask whether
                     each phrase says where the work stands to somebody who has never seen the
                     pipeline's keywords."
              :render #(into [:div]
                             (for [p (cons nil positions)]
                               [:div.cat-pair
                                [:span.cat-scene-fn (if p (str p) "nil")]
                                [:span (state/status-heading (when p {:at p}))]]))}]}
   {:name "arc-name"
    :note "An arc stage's heading."
    :scenes [{:title "the four stages, and an unknown one"
              :note "An unknown stage prints its own keyword rather than vanishing: an arc
                     written by a newer nido should degrade to something a person can act on."
              :render #(rail str [:intent :baseline :design :implementation :rebaseline]
                             (fn [s] [:span (state/arc-name s)]))}]}
   {:name "arc-mark"
    :note "The mark for one arc stage. What is happening now outranks what the records say."
    :scenes [{:title "every record state"
              :note "Five marks because there are five states and no two mean the same thing: ⊘
                     is a stage the work went past without writing one, · one it has not reached,
                     ↺ one whose records the ledger no longer stands behind."
              :render #(rail str [:done :current :stale :skipped :ahead]
                             (fn [s] (state/arc-mark s nil)))}
             {:title "what is happening now"
              :note "Waiting on a person — the needs-you dot, yellow — and an agent working — a
                     breathing dot, blue. Both outrank the record state beneath them, so they are
                     what a reader scanning an arc lands on."
              :render #(rail str [:awaiting :running]
                             (fn [n] (state/arc-mark :done n)))}]}])

;; ---------------------------------------------------------------------------
;; The chat panel

(defn- chat-routes
  "Where the scene panel `id` posts: the catalog's dead end, for every control. Every scene's
   panel has an id of its own, because the catalog draws the scenes on one page and a panel's
   signals and DOM ids are keyed by it — two scenes sharing one would share their open, busy and
   draft state, and a toggle in one would move the other."
  [id]
  {:id         id
   :send-url   "/_catalog/nowhere/chat/send"
   :cancel-url "/_catalog/nowhere/chat/cancel"
   :reset-url  "/_catalog/nowhere/chat/reset"
   :answer-url (fn [item option] (str "/_catalog/nowhere/chat/permission/" item "/" option))
   :unpick-url (fn [i] (str "/_catalog/nowhere/chat/unpick/" i))})

(def ^:private scene-image
  "A stand-in picture for the image scenes: a scene has no conversation to fetch an image from,
   so its routes name this one, drawn here, for every digest."
  (str "data:image/svg+xml;base64,"
       (.encodeToString
        (java.util.Base64/getEncoder)
        (.getBytes
         "<svg xmlns='http://www.w3.org/2000/svg' width='160' height='100'><rect width='160' height='100' fill='darkslateblue'/><circle cx='50' cy='40' r='14' fill='lightsteelblue'/><path d='M0 100 L60 55 L95 80 L120 62 L160 100 Z' fill='slateblue'/></svg>"
         "UTF-8"))))

(defn- attach-routes
  "`chat-routes` for a panel that offers attaching, its images all the stand-in picture."
  [id]
  (assoc (chat-routes id)
         :attach-url   "/_catalog/nowhere/chat/attach"
         :unattach-url (fn [d] (str "/_catalog/nowhere/chat/unattach/" d))
         :image-url    (constantly scene-image)))

(def ^:private two-images
  [{:mime "image/png" :data "" :digest "a1"} {:mime "image/png" :data "" :digest "b2"}])

(defn- transcript
  "A literal transcript of `items`, each given the id its position would give it. A running one
   carries no start time — a scene renders the same markup at any hour, so it cannot say when
   its turn began — and so shows no elapsed time; `nido.ui.components.chat-test` covers it."
  [busy? & items]
  {:busy? busy? :items (vec (map-indexed (fn [i item] (assoc item :id (str "i" i))) items))})

(def ^:private chip
  {:label "span.pos-chip" :title "position-chip · every position · span.pos-chip"})

(def ^:private you
  {:kind :user :text "Make the needs-you chip a little quieter — it shouts beside the title."
   :refs [chip]})

(def ^:private you-two
  {:kind :user :text "These two should read as one family — same weight, same tint."
   :refs [chip
          {:label "span.row-stage" :title "row · a running workstream · span.row-stage"}]})

(def ^:private reply
  {:kind :agent :text "The chip takes `--needs` at full strength. I will move it to `--needs-dim`
for the text and keep the tint, so it still reads as **yours** without competing with the title."})

(def ^:private edit-call
  {:kind :tool :title "Edit src/nido/ui/stylesheet.clj" :status :completed :tool-kind :edit
   :body nil
   :diffs [{:path "src/nido/ui/stylesheet.clj"
            :old ".pos-you { background:var(--needs-bg); color:var(--needs); }"
            :new ".pos-you { background:var(--needs-bg); color:var(--needs-dim); }"}]})

(def ^:private asking
  {:kind :permission :title "Run bb nido:test :only nido.ui" :request-id 3 :answer nil
   :options [{:id "allow" :name "Allow" :kind :allow_once}
             {:id "always" :name "Always allow" :kind :allow_always}
             {:id "deny" :name "Deny" :kind :reject_once}]})

(def ^:private change-routes
  {:publish-url "/_catalog/nowhere/chat/publish" :revert-url "/_catalog/nowhere/chat/revert"})

(def ^:private a-change
  {:description "Tighten the catalog chat's composer\n\nSend sits inside the message box."
   :files [{:path "src/nido/ui/components/chat.clj" :status :modified}
           {:path "src/nido/ui/stylesheet.clj" :status :modified}
           {:path "src/nido/ui/catalog/new_scene.clj" :status :added}]})

(def ^:private a-newer-change
  {:description "Give the composer's Send a quieter border"
   :files [{:path "src/nido/ui/stylesheet.clj" :status :modified}]
   :rev "fedcba9876543210"})

(defn- chat-scenes []
  [{:name "chat-panel"
    :note "A conversation with an agent: its transcript, the turn's status, then the composer.
           Reads a transcript value only, so a workstream pane or a question card can host one
           as the catalog does."
    :scenes [{:title "empty"
              :note "Nothing said yet: only the composer, waiting for the first message."
              :render #(chat/chat-panel (transcript false) (chat-routes "scene-chat-1"))}
             {:title "sent, nothing back yet"
              :note "The moment after Send: the message with the elements it carried, and the
                     status row saying the turn is under way before the agent has said anything."
              :render #(chat/chat-panel (transcript true you-two) (chat-routes "scene-chat-2"))}
             {:title "a streaming reply"
              :note "The reply grows, the caret marks where, the thought before it has folded,
                     the status row says writing, and Send is Stop."
              :render #(chat/chat-panel
                        (transcript true you
                                    {:kind :thought :text "The chip sits beside an --fs-lg title…"}
                                    (assoc reply :text "The chip takes `--needs` at full strength. I will"
                                           :streaming? true))
                        (chat-routes "scene-chat-3"))}
             {:title "a finished reply"
              :note "A turn ended: the reply settled, a tool call folded, no status, and Send is back."
              :render #(chat/chat-panel (transcript false you edit-call reply) (chat-routes "scene-chat-4"))}
             {:title "a permission request"
              :note "The agent asks before a tool runs: the request and its answers, each a
                     button, and the status row saying it waits for you."
              :render #(chat/chat-panel (transcript true you asking) (chat-routes "scene-chat-5"))}
             {:title "an error"
              :note "A turn that failed: what went wrong, and the composer ready again."
              :render #(chat/chat-panel
                        (transcript false you {:kind :error :text "The agent process stopped."})
                        (chat-routes "scene-chat-6"))}]}
   {:name "side"
    :note "The panel as a side panel: a toggle that opens and closes it, leading a bar over the
           panel. Closed, the toggle is all that shows; the page gives it the width."
    :scenes [{:title "open"
              :note "The toggle points out, the bar names the chat and offers a new conversation,
                     and the panel is under it. New conversation asks in the bar before it starts
                     over, and is hidden while a turn runs."
              :render #(vector :div.cat-sidepanel
                               (chat/side (transcript false you reply) (chat-routes "scene-chat-7") {:open? true}))}
             {:title "closed"
              :note "Only the toggle, pointing in."
              :render #(vector :div.cat-sidepanel
                               (chat/side (transcript false you reply) (chat-routes "scene-chat-8") {:open? false}))}
             {:title "closed while a turn runs"
              :note "The running dot on the toggle's corner says the agent is still at work."
              :render #(vector :div.cat-sidepanel
                               (chat/side (transcript true you) (chat-routes "scene-chat-9") {:open? false}))}]}
   {:name "chat-log"
    :note "The transcript alone, as it scrolls: it opens at the bottom, a message you send is
           pinned to its top with room below for the reply, and your own scroll lets go."
    :scenes [{:title "a long conversation, in a short box"
              :note "Opens on the newest message, at the top, with the reply under it and the room
                     the reply still has. Scroll up for the earlier turns."
              :render #(vector :div.cat-scroll-box
                               (chat/chat-log "scene-log"
                                              (:items (transcript false you edit-call reply
                                                                  you-two reply))
                                              (chat-routes "scene-log")))}]}
   {:name "log-id"
    :note "The id a stream appends a new item under. Words, not markup — but the one name a stream
           and the panel must agree on."
    :scenes [{:title "one per panel"
              :render #(into [:div]
                             (for [id ["catalog-chat" "nido-chat"]]
                               [:div.cat-pair [:span.cat-scene-fn id] [:span.mono (chat/log-id id)]]))}]}
   {:name "change-bar"
    :note "The conversation's change that main does not have, above the log, and what a person
           can do with it: Publish lands it on main, Cancel throws it away, each confirmed in place."
    :scenes [{:title "no change"
              :note "Nothing unlanded and nothing to say: the empty element, which takes no space."
              :render #(chat/change-bar "scene-change-1" nil {} change-routes)}
             {:title "a change"
              :note "Its files fold away; what it would land as is its description."
              :render #(chat/change-bar "scene-change-2" a-change {} change-routes)}
             {:title "a change not yet described"
              :note "Publish waits: an undescribed change would land unnamed."
              :render #(chat/change-bar "scene-change-3" (assoc a-change :description "") {} change-routes)}
             {:title "publishing"
              :note "The landing verb's current step and the change it lands, which stays shown until the
                     worktree is back on main. Nothing
                     has been made since Publish, so there is nothing pending beside it."
              :render #(chat/change-bar "scene-change-4" nil {:running? true :step "running bb nido:test at 0123456789ab"
                                                              :landing a-change}
                                        change-routes)}
             {:title "publishing, with newer edits"
              :note "The chat stays open while a change lands: what the agent made since Publish is
                     listed apart, and waits for the landing before it can be published."
              :render #(chat/change-bar "scene-change-10" a-newer-change
                                        {:running? true :step "running bb nido:test at 0123456789ab"
                                         :landing a-change}
                                        change-routes)}
             {:title "conflicting with main"
              :note "Brought onto main after a landing, the newer edits conflict: the paths are named,
                     and Publish waits for the agent to resolve them."
              :render #(chat/change-bar "scene-change-11" (assoc a-newer-change :conflicts ["src/nido/ui/stylesheet.clj"])
                                        {} change-routes)}
             {:title "refused"
              :note "Why it did not land, with the change still there to fix and publish again."
              :render #(chat/change-bar "scene-change-5" a-change {:refused "bb nido:test is red at the tip"}
                                        change-routes)}
             {:title "published"
              :note "Landed: the commit it landed as, and nothing made since."
              :render #(chat/change-bar "scene-change-6" nil {:landed "0123456789abcdef"} change-routes)}
             {:title "published, restart owed"
              :note "The landing's restart is owed, and the bar names what the daemon waits for."
              :render #(chat/change-bar "scene-change-7" nil
                                        {:landed  "0123456789abcdef"
                                         :restart {:landing {:state :owed :commit "0123456789abcdef"
                                                             :owed-at "2026-11-17T12:00:00Z" :by-pid 4242}
                                                   :waits ["1 run(s) to finish: 2026-11-17-nido-review-1a2b"]}}
                                        change-routes)}
             {:title "running the landing"
              :note "After the restart: the bar says which commit the dashboard runs, with no publish in its memory."
              :render #(chat/change-bar "scene-change-8" nil
                                        {:restart {:landing {:state :running :commit "0123456789abcdef"
                                                             :owed-at "2026-11-17T12:00:00Z"
                                                             :since "2026-11-17T12:03:10Z" :pid 5151}}}
                                        change-routes)}
             {:title "landing unreadable"
              :note "A record nobody can read is said, never taken for no landing."
              :render #(chat/change-bar "scene-change-9" nil
                                        {:restart {:unreadable "restart-owed.edn does not hold a landing"}}
                                        change-routes)}]}
   {:name "turn-status"
    :note "The status row at the foot of the log: from Send to the turn's end it names what the
           agent is doing now and counts the seconds. Each phase against the others."
    :scenes [{:title "waiting"
              :note "Sent; nothing back from the agent yet."
              :render #(chat/turn-status "scene-chat-12" (transcript true you))}
             {:title "thinking"
              :render #(chat/turn-status "scene-chat-13"
                                         (transcript true you {:kind :thought :streaming? true
                                                               :text "The chip sits…"}))}
             {:title "running a tool"
              :note "Names the tool, by its title."
              :render #(chat/turn-status "scene-chat-14"
                                         (transcript true you {:kind :tool :title "bb nido:test"
                                                               :status :in-progress :diffs []}))}
             {:title "writing"
              :render #(chat/turn-status "scene-chat-15"
                                         (transcript true you (assoc reply :streaming? true)))}
             {:title "waiting for your answer"
              :note "Still: nothing moves until you answer."
              :render #(chat/turn-status "scene-chat-16" (transcript true you asking))}
             {:title "no turn running"
              :note "Renders an empty row, which takes no space."
              :render #(chat/turn-status "scene-chat-17" (transcript false you reply))}]}
   {:name "chat-item"
    :note "One transcript item, under the DOM id a stream patches it by."
    :scenes [{:title "your message with picks"
              :note "The elements it carried, numbered as they were marked, above the words."
              :render #(chat/chat-item (assoc you-two :id "i0") (chat-routes "scene-chat-18"))}
             {:title "your message with an image"
              :note "The image it carried, as a thumbnail that opens it, above the words."
              :render #(chat/chat-item {:id "i0" :kind :user :text "What is wrong with this row?" :refs []
                                        :images (subvec two-images 0 1)}
                                       (attach-routes "scene-chat-31"))}
             {:title "your message without picks"
              :note "Words alone: no chips above them."
              :render #(chat/chat-item {:id "i0" :kind :user
                                        :text "Why does the board sort parked rows first?"}
                                       (chat-routes "scene-chat-29"))}
             {:title "a thought, streaming and done"
              :note "Open while it streams; folded to one line once the next item starts."
              :render #(list (chat/chat-item {:id "i0" :kind :thought :streaming? true
                                              :text "The chip sits beside an --fs-lg title…"}
                                             (chat-routes "scene-chat-19"))
                             (chat/chat-item {:id "i1" :kind :thought
                                              :text "The chip sits beside an --fs-lg title…"}
                                             (chat-routes "scene-chat-20")))}
             {:title "a tool call, collapsed"
              :note "How a tool call reads in passing: its status mark and what it did, one line."
              :render #(chat/chat-item (assoc edit-call :id "i0") (chat-routes "scene-chat-21"))}
             {:title "a tool call, expanded"
              :note "Opened: its output and its diff. Click the line to fold it."
              :render #(update (chat/chat-item (assoc edit-call :id "i0") (chat-routes "scene-chat-22")) 2
                               ;; The same markup with the disclosure opened, as a click opens it.
                               (fn [details] (into [(first details) {:open true}] (rest details))))}
             {:title "a running and a failed tool call"
              :note "The failed one opens itself, so its output is read without a click."
              :render #(list (chat/chat-item {:id "i0" :kind :tool :title "bb nido:test" :status :in-progress
                                              :diffs []} (chat-routes "scene-chat-23"))
                             (chat/chat-item {:id "i1" :kind :tool :title "Read src/missing.clj"
                                              :status :failed :body "File does not exist." :diffs []}
                                             (chat-routes "scene-chat-24")))}
             {:title "an answered permission request"
              :note "Once answered, a request folds to one line saying what was chosen."
              :render #(chat/chat-item {:id "i0" :kind :permission :title "Run bb nido:test"
                                        :answer "allow" :request-id 3
                                        :options [{:id "allow" :name "Allow" :kind :allow_once}]}
                                       (chat-routes "scene-chat-25"))}]}
   {:name "picks"
    :note "The elements picked for the next message, as chips: numbered as the page marks them,
           each removable."
    :scenes [{:title "two picks"
              :render #(chat/picks "scene-chat-26" (:refs you-two) (chat-routes "scene-chat-26"))}
             {:title "none"
              :note "Renders an empty row, which takes no space."
              :render #(chat/picks "scene-chat-27" [] (chat-routes "scene-chat-27"))}]}
   {:name "attachments"
    :note "The images staged for the next message, as thumbnails, each removable. The same on
           every page showing the conversation: the host holds them, not the page."
    :scenes [{:title "two images"
              :render #(chat/attachments "scene-chat-32" two-images (attach-routes "scene-chat-32"))}
             {:title "none"
              :note "Renders an empty row, which takes no space."
              :render #(chat/attachments "scene-chat-33" [] (attach-routes "scene-chat-33"))}]}
   {:name "composer"
    :note "The composer alone: the pick chips, the text box, and Send — or Stop, by the busy signal.
           Where its routes carry an attach route, an Image control too, and the box takes a
           pasted image and the panel a dropped one."
    :scenes [{:title "ready" :render #(chat/composer "scene-chat-28" (chat-routes "scene-chat-28"))}
             {:title "with picks"
              :note "Two elements picked for the next message: their chips above the text box."
              :render #(let [routes (chat-routes "scene-chat-30")]
                         ;; The composer renders its chips empty; a pick patches them in by
                         ;; `picks`, so the same markup with that patch made, as a pick makes it.
                         (assoc (chat/composer "scene-chat-30" routes) 2
                                (chat/picks "scene-chat-30" (:refs you-two) routes)))}
             {:title "with images attached"
              :note "Two images staged for the next message: their thumbnails above the text box,
                     and the Image control beside Send."
              :render #(let [routes (attach-routes "scene-chat-34")
                             c      (vec (chat/composer "scene-chat-34" routes))]
                         ;; The composer renders its staged images empty; the stream patches them
                         ;; in by `attachments`, so the same markup with that patch made.
                         (assoc c 3 (list (chat/attachments "scene-chat-34" two-images routes)
                                          (second (nth c 3)))))}]}
   {:name "signals"
    :note "The page-state names a panel keeps its draft, its picks and its busy flag under.
           Words, not markup — but the one thing two panels on one page must not share."
    :scenes [{:title "one set per panel"
              :render #(into [:div]
                             (for [id ["catalog-chat" "ws-20261113-609f4c-chat"]]
                               [:div.cat-pair
                                [:span.cat-scene-fn id]
                                [:span.cat-ret (pr-str (chat/signals id))]]))}]}])

;; ---------------------------------------------------------------------------
;; The registry

(defn ^{:malli/schema [:=> [:cat] :any]}
  groups
  "One group per block vocabulary. The chat panel comes first, because the catalog is where a
   person talks to it about changes; the rest follow in the order the levels stack: the marks a row is
   written in, the rows a page is written in, and the Ask a gate is answered on.

   A group is {:id :title :note :blocks}, and a block is {:name :note :scenes} where `:name` is
   the published function's own name — which is what lets the catalog test hold this registry to
   covering every one of them. A scene is {:title :note :render}, and `:render` is a thunk so
   that a broken block fails where it is rendered rather than when this registry is read."
  []
  [{:id "chat" :title "Chat"
    :note "A conversation with an agent. The panel is a block over a transcript, so every state
           here is a literal transcript; the buttons post to a dead end."
    :blocks (chat-scenes)}
   {:id "state" :title "State marks"
    :note "A workstream's state as a reader sees it. Every one of these is judged against its
           siblings rather than alone — a mark only means something relative to the other marks
           on the same row."
    :blocks (state-scenes)}
   {:id "row" :title "Rows"
    :note "The scannable lists. A row is read in a column of forty others, so what matters is
           which one your eye lands on: compare the ordinary row against the ones that need
           something."
    :blocks [(registered "board-row" row/board-row) (registered "gate-row" row/gate-row)
             (registered "winddown-row" row/winddown-row) (registered "arc-row" row/arc-row)]}
   {:id "ask" :title "The Ask"
    :note "One decision, offered for answer. Datastar is live on this page, so the stepper, the
           choices and the answered-markers work exactly as they do on a real gate; the buttons
           post to a dead end."
    :blocks [(registered "card" ask/card) (registered "bar" ask/bar)]}])
