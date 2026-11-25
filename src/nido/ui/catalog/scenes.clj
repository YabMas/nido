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
            [nido.ui.components.dialog :as dialog]
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

(def ^:private scene-image
  "A stand-in picture for the image scenes: a scene has no conversation to fetch an image from,
   so the router names this one, drawn here, for every digest."
  (str "data:image/svg+xml;base64,"
       (.encodeToString
        (java.util.Base64/getEncoder)
        (.getBytes
         "<svg xmlns='http://www.w3.org/2000/svg' width='160' height='100'><rect width='160' height='100' fill='darkslateblue'/><circle cx='50' cy='40' r='14' fill='lightsteelblue'/><path d='M0 100 L60 55 L95 80 L120 62 L160 100 Z' fill='slateblue'/></svg>"
         "UTF-8"))))

(defn- nowhere-router
  "A block's router that goes nowhere: every action to the catalog's own dead end, its params
   in the path so the URL is shaped like the real one — but an image, which is the stand-in
   picture, since a scene that showed a broken one would say nothing about how images read."
  [action params]
  (if (= :image action)
    scene-image
    (str "/_catalog/nowhere/" (name action)
         (apply str (for [[_ v] (sort-by key params)] (str "/" (if (keyword? v) (name v) v)))))))

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
  (into [:div.scene-rail]
        (for [x xs]
          [:div.scene-rail-i
           [:span.scene-label (label-fn x)]
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
    :note "Where the pipeline says the work stands. Muted by default, lit only when the
           workstream's activity says the person owes the next move."
    :scenes [{:title "nido's move, and yours"
              :note "The whole point of the chip is this contrast — a board is scanned for the
                     lit ones. If they do not separate at a glance, the chip has failed."
              :render #(rail (fn [p] (name (:mode (:next p))))
                             [{:at :baselined :next {:stage :verify-baseline :mode :authoring}}
                              {:at :blocked :next {:stage :answer-blocker :mode :human}}]
                             (fn [p] (state/position-chip p nil ({:human :ask :release :landing} (:mode (:next p))))))}
             {:title "with layers, and with a caller's tip"
              :note "A layered position carries done/of inside the chip; the second arity adds a
                     caller's phrase to the title, after the next move."
              :render #(list (state/position-chip {:at :implementing
                                                   :layers {:done 2 :of 5}
                                                   :next {:stage :implement :mode :authoring}})
                             " "
                             (state/position-chip {:at :blocked
                                                   :next {:stage :answer-blocker :mode :human}}
                                                  "waiting since Tuesday" :ask))}
             {:title "no position"
              :note "A bare watched page, or a band the projection is not computed for. It must
                     render nothing at all rather than an empty chip."
              :render #(or (state/position-chip nil) [:span.scene-empty "renders nothing"])}]}
   {:name "phase-chip"
    :note "Which landing of a phased plan this is. Beside the position chip, not inside it."
    :scenes [{:title "phased, and not"
              :render #(list (state/phase-chip {:current 2 :of 3}) " "
                             (or (state/phase-chip {}) [:span.scene-empty "unphased: nothing"]))}]}
   {:name "needs-mark"
    :note "The next move is a person's. The one mark a board is scanned for, so it is the same dot
           on a board row, a needs-you row and an arc stage, with only its title saying how."
    :scenes [{:title "plain, and titled by its caller"
              :note "Hover each: a row's dot reads its default title, an arc stage's says what it
                     waits on."
              :render #(list (state/needs-mark) " " (state/needs-mark "waiting on you"))}]}
   {:name "error-mark"
    :note "The machine stopped on a failure and the person's move is its recovery: the mark that
           heads a system error's account of what failed, in the pane."
    :scenes [{:title "plain, and titled by its caller"
              :render #(list (state/error-mark) " " (state/error-mark "its conductor stopped"))}]}
   {:name "needs-words"
    :note "The words for the category the person is needed in — an ask, a landing, a system error
           — which every surface's needs-you mark, heading and card say alike."
    :scenes [{:title "each category"
              :note "One concept, three kinds: the title tells a question from a landing from a
                     failure, so a scan of the dots can be followed by a hover that sorts them."
              :render #(rail str [:ask :landing :system-error nil]
                             (fn [n] [:span (state/needs-mark (state/needs-words n)) " "
                                      (state/needs-words n)]))}]}
   {:name "gate-standing"
    :note "Where a needs-you card's request stands, in words: its resolve running, the session it
           is parked on, or the category the person is needed in."
    :scenes [{:title "working, parked, each category"
              :render #(rail str [{:pending? true} {:session "impl-1"} {:needs :ask}
                                  {:needs :landing} {:needs :system-error}]
                             (fn [g] [:span (state/gate-standing g)]))}]}
   {:name "winddown-words"
    :note "Where a closed workstream still holding live sessions stands: stopping, or how it closed
           and what it still holds."
    :scenes [{:title "holding sessions, and stopping"
              :render #(rail str [{:outcome :done :session-count 2 :rss-str "1.2 GB"} {:pending? true}]
                             (fn [r] [:span (state/winddown-words r)]))}]}
   {:name "state-mark"
    :note "Whether anything is happening right now, as one mark and no words. What runs and who
           owes the next move are the mark's title — hover to read them."
    :scenes [{:title "needs you, running, paused, stalled, quiet"
              :note "The five a board row can be in. Needing the person outranks the rest, since
                     it is what a board is scanned for. Paused is a person's decision and stalled
                     is a fault, so they must not read alike — and quiet must render nothing at
                     all: a row where nothing is happening is the common case, and a mark on
                     every row would drown the four that mean something."
              :render #(rail first
                             [["needs you" {:owed-by :person :needs-you true} nil]
                              ["live" {:owed-by :agent :live true} {:source :claim :kind :diff-review
                                                                    :progress {:round 2 :phase :fixing
                                                                               :findings 3}}]
                              ["paused" {:owed-by :agent :paused true} nil]
                              ["stalled" {:owed-by :agent :stalled true} nil]
                              ["quiet" {:owed-by :person} nil]]
                             (fn [[_ st doing]]
                               (or (state/state-mark st doing)
                                   [:span.scene-empty "nothing"])))}]}
   {:name "round-label"
    :note "The words beside a running arc stage — where a review round has got, and nothing for
           any other activity."
    :scenes [{:title "a round, and everything else"
              :render #(rail first
                             [["claim" {:source :claim :kind :design
                                        :progress {:round 3 :phase :judging :findings 0}}]
                              ["core" {:source :core :activity :working}]]
                             (fn [[_ d]] (if-let [l (state/round-label d)]
                                           [:span l]
                                           [:span.scene-empty "nothing"])))}]}
   {:name "status-heading"
    :note "The Status section's heading, carrying the position itself. A string, not markup —
           the pane wraps it."
    :scenes [{:title "every position, as a person reads it"
              :note "This is the wording review. Read the right-hand column alone and ask whether
                     each phrase says where the work stands to somebody who has never seen the
                     pipeline's keywords."
              :render #(into [:div]
                             (for [p (cons nil positions)]
                               [:div.scene-pair
                                [:span.scene-label (if p (str p) "nil")]
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
                             (fn [n] (state/arc-mark :done n)))}
             {:title "waiting on the person, by category"
              :note "The awaiting stage's title says what the person is needed for, in the words
                     the board row and the Needs you card use."
              :render #(rail str [:ask :landing :system-error]
                             (fn [k] (state/arc-mark :current :awaiting k)))}]}])

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
    :blocks [(registered "panel" chat/panel) (registered "change-bar" chat/change-bar)]}
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
    :blocks [(registered "card" ask/card) (registered "bar" ask/bar)]}
   {:id "dialog" :title "Dialog"
    :note "A native dialog the platform opens, focuses and closes. Open one and press Escape."
    :blocks [(registered "dialog" dialog/dialog)]}])
