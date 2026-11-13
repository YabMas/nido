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
  (:require [nido.ui.components.ask :as ask]
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

(def ^:private nowhere
  "A route function of (project ws-id action-id), as a pane supplies one."
  (fn [project ws-id action] (str "/_catalog/nowhere/" project "/" ws-id "/" (name action))))

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
    :note "Where a workstream came from. One hue and one letter each."
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
                              {:at :design-decided :next {:stage :approve-design :mode :human}}]
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
              :note "Waiting on a person, and running. Both outrank the record state beneath
                     them, so they are what a reader scanning an arc lands on."
              :render #(rail str [:awaiting :running]
                             (fn [n] (state/arc-mark :done n)))}]}])

;; ---------------------------------------------------------------------------
;; Rows

(def ^:private a-board-row
  {:ws-id "ws-20261113-609f4c" :origin :notion
   :label "BR-7285 · checkout is off by a cent"
   :tag "BR-7285" :stage :in-progress
   :position {:at :designed :next {:stage :decide-design :mode :authoring}}
   :status {:owed-by :agent :live true}})

(def ^:private a-gate
  {:project "brian" :ws-id "ws-1" :origin :notion :stage :triage
   :label "BR-7285 · checkout is off by a cent" :tag "BR-7285"
   :ask {:question "Accept the triage of “Checkout off by a cent”?"}
   :session "auto"})

(defn- row-scenes []
  [{:name "board-row"
    :note "One selectable Workstreams row. The page gives it the URL and whether it is the open
           one; everything else is the row's value."
    :scenes [{:title "the ordinary row"
              :note "Nothing owes a person anything and something is running. This is most of
                     the board, and it is the one the others have to stand out against."
              :render #(row/board-row "nido" a-board-row {:href "#"})}
             {:title "selected"
              :note "The open row. The highlight has to survive a poll, so it is given rather
                     than derived — a row cannot know which one the reader opened."
              :render #(row/board-row "nido" a-board-row {:href "#" :selected? true})}
             {:title "it needs you"
              :note "A lit position chip and the needs dot. Compare against the ordinary row
                     above: this is the comparison a board is scanned for."
              :render #(row/board-row "nido"
                                      (assoc a-board-row
                                             :needs-you true
                                             :position {:at :design-decided
                                                        :next {:stage :approve-design :mode :human}})
                                      {:href "#"})}
             {:title "open findings"
              :note "A shipped workstream a staging round reopened."
              :render #(row/board-row "nido"
                                      (assoc a-board-row :open-findings 3
                                             :position {:at :findings-open
                                                        :next {:stage :address-findings :mode :authoring}})
                                      {:href "#"})}
             {:title "a gate and its criterion"
              :note "The only row that gets a second line: what the gate waits on, where the
                     person owing it will look."
              :render #(row/board-row "nido"
                                      (assoc a-board-row
                                             :stage :awaiting-gate :needs-you true
                                             :position {:at :awaiting-gate
                                                        :next {:stage :assert-gate :mode :human}}
                                             :phase {:current 2 :of 3
                                                     :exit {:criterion "the dashboard serves /_catalog on a daemon that has been up for an hour"}})
                                      {:href "#"})}
             {:title "shipping, phased"
              :note "The merge phase arrives through the state mark, which the projection folds
                     ship-substate into — so it is never said twice on one row."
              :render #(row/board-row "nido"
                                      (assoc a-board-row
                                             :stage :shipping
                                             :position {:at :shipped :next {:stage :land :mode :human}}
                                             :phase {:current 3 :of 3}
                                             :doing {:source :merge :phase :blocked})
                                      {:href "#"})}
             {:title "a long title"
              :note "The row has to survive a generated label that does not fit. If it wraps to
                     three lines or pushes the marks off the end, the row is wrong, not the
                     ticket."
              :render #(row/board-row "nido"
                                      (assoc a-board-row :label
                                             "A really quite long generated workstream title that nobody wrote on purpose and which keeps going well past the width of any column it will ever be shown in")
                                      {:href "#"})}]}
   {:name "gate-row"
    :note "One needs-you row. It previews the question, so a reader scanning the list sees what
           each one wants of them."
    :scenes [{:title "one question"
              :render #(row/gate-row a-gate "#")}
             {:title "working"
              :note "Its agent was resumed and is mid-resolve. No action is offered anywhere
                     until it comes back."
              :render #(row/gate-row (assoc a-gate :pending? true) "#")}
             {:title "a failed action"
              :note "The resolve is async, so this line is the only trace a failed Apply leaves."
              :render #(row/gate-row (assoc a-gate :error-msg "apply refused: the design no longer stands") "#")}
             {:title "a resume that failed"
              :render #(row/gate-row (assoc a-gate :resume-error {:reason :no-session}) "#")}
             {:title "permission denials"
              :note "A session nido drives hit auto-mode denials. The row counts them and names
                     the latest tool."
              :render #(row/gate-row (assoc a-gate :denials [{:tool-name "Bash"} {:tool-name "WebFetch"}]) "#")}
             {:title "no Ask, only a report"
              :note "An older gate whose question was never written as an Ask. The preview falls
                     back to the report's first prose line."
              :render #(row/gate-row (-> a-gate (dissoc :ask :session)
                                         (assoc :report {:markdown "# Verdict\n\nbug — reproduced against the running app."}))
                                     "#")}
             {:title "something running in it"
              :note "Waiting on a person and having something underway are separate facts, and a
                     gate can hold both at once."
              :render #(row/gate-row (assoc a-gate :doing {:source :claim :kind :diff-review}) "#")}]}
   {:name "winddown-row"
    :note "A closed workstream still holding live sessions. Muted, one action."
    :scenes [{:title "live sessions"
              :render #(row/winddown-row {:project "nido" :origin :scratch
                                          :label "component-catalog" :outcome :done
                                          :sessions [{} {}] :rss-str "3.9 GB"}
                                         "#")}
             {:title "stopping"
              :note "No re-clickable button while a bring-down is in flight; the 5s poll drops
                     the row once its sessions are down."
              :render #(row/winddown-row {:project "nido" :origin :scratch :label "component-catalog"
                                          :outcome :done :sessions [{}] :pending? true}
                                         "#")}
             {:title "a bring-down that failed"
              :note "The error renders inline AND the button stays, because clicking retry
                     overwrites the failed entry — retry self-clears the error."
              :render #(row/winddown-row {:project "nido" :origin :scratch :label "component-catalog"
                                          :outcome :dropped :sessions [{}]
                                          :error-msg "pg_ctl: could not create lock file"}
                                         "#")}]}
   {:name "arc-row"
    :note "One stage of a workstream's arc. Clickable only when it holds records."
    :scenes [{:title "every state, in one arc"
              :note "Read down the marks: this is the shape of a workstream's history at a
                     glance, and the row that matters is the one waiting on you."
              :render #(into [:div.arc]
                             (for [[facet rl]
                                   [[{:stage :intent :state :done :visits 1 :entries 1} nil]
                                    [{:stage :baseline :state :done :visits 2 :entries 3} nil]
                                    [{:stage :design :state :current :visits 1 :entries 2
                                      :now :running} "design round 3 · judging · 0 findings"]
                                    [{:stage :implementation :state :ahead :visits 0 :entries 0} nil]]]
                               (row/arc-row facet rl {:open? false :on-click no-click})))}
             {:title "waiting on you"
              :note "◆ outranks the record state beneath it. A stage a person is being waited on
                     at is the one fact a reader scans an arc for."
              :render #(row/arc-row {:stage :design :state :done :visits 1 :entries 2
                                     :now :awaiting}
                                    nil {:open? false :on-click no-click})}
             {:title "revisited"
              :note "`2 visits` means a later stage sent the work back here — the fact the ledger
                     index cannot show and a forward-only track cannot show either. It renders
                     only above one, so the ordinary case stays quiet."
              :render #(row/arc-row {:stage :baseline :state :done :visits 3 :entries 5}
                                    nil {:open? false :on-click no-click})}
             {:title "stale, and skipped"
              :note "A stale stage must not say how many records it has as though they counted;
                     it says they no longer stand. A skipped one says there is no record."
              :render #(list (row/arc-row {:stage :baseline :state :stale :visits 1 :entries 4}
                                          nil {:open? false :on-click no-click})
                             (row/arc-row {:stage :design :state :skipped :visits 0 :entries 0}
                                          nil {:open? false :on-click no-click}))}
             {:title "expanded, and unclickable"
              :note "An empty stage offers no click at all: offering one would promise a section
                     that opens empty."
              :render #(list (row/arc-row {:stage :intent :state :done :visits 1 :entries 1}
                                          nil {:open? true :on-click no-click})
                             (row/arc-row {:stage :implementation :state :ahead :visits 0 :entries 0}
                                          nil {:open? false :on-click no-click}))}]}])

;; ---------------------------------------------------------------------------
;; The Ask

(def ^:private one-question-ask
  {:kind :approve-design
   :question "Approve the component-catalog design?"
   :questions [{:question "Approve the component-catalog design?"
                :context ["The split is behaviour-preserving: no rendered page changes."
                          "It breaks one baseline claim — pages-are-the-interface — and states blocks-are-addressable in its place."]
                :recommendation "Approve. The decision round found nothing it could not derive."
                :after "The implementation starts against the claims as they stand."
                :options [{:letter "A" :label "Approve" :summary "The design stands as written."
                           :consequence "Recorded as the grant; the work starts."
                           :recommended? true :action :approve}
                          {:letter "B" :label "Send it back"
                           :summary "Something in the shape has to change first."
                           :consequence "Recorded as an amendment the next design supersedes."
                           :action :reply}]}]
   :answer-does [{:action :approve :label "Approve" :does "Records your grant on the ledger and starts the work."}
                 {:action :reply :label "Reply" :does "Records what you said and resumes the session."}]})

(def ^:private one-question-actions
  [{:id :approve :label "Approve" :kind :mutation :style :primary}
   {:id :reply :label "Reply" :kind :resume :style :default}])

(def ^:private two-question-ask
  {:kind :accept-triage
   :question "Accept the triage of “Checkout off by a cent”?"
   :questions [{:question "Where should the total be rounded?"
                :context ["Each line rounds independently today, so the error accumulates."]
                :recommendation "Round once — the smallest fix."
                :after "The fix is built and a PR opened."
                :options [{:letter "A" :label "Round once" :summary "On the total."
                           :consequence "Effort: S" :recommended? true}
                          {:letter "B" :label "A money type"
                           :summary "Make the wrong thing unrepresentable."
                           :consequence "Effort: L"}]}
               {:question "Should the receipt show the rounding?"
                :context ["Finance asked; the customer has never asked."]
                :options [{:letter "A" :label "Show it" :summary "One line under the total."
                           :consequence "Effort: XS"}
                          {:letter "B" :label "Leave it" :summary "Nothing on the receipt."
                           :consequence "Effort: none"}]}]
   :submit :apply
   :answer-does [{:action :apply :label "Accept" :does "Records every answer and files the ticket."}
                 {:action :defer :label "Defer" :does "Leaves the questions for the design work."}]})

(def ^:private two-question-actions
  [{:id :apply :label "Accept" :kind :mutation :style :primary :seq 4}
   {:id :defer :label "Defer" :kind :mutation :style :default}
   {:id :reply :label "Reply" :kind :resume :style :default}])

(defn- ask-scenes []
  [{:name "question-card"
    :note "One Ask, answered. The pane supplies how a click is routed; the card reads nothing
           else. Datastar is live here, so the stepper and the choices work."
    :scenes [{:title "one question"
              :note "The ordinary gate. The question leads, its support follows, and the one
                     recommended answer is the one primary button."
              :render #(ask/question-card "nido" "ws-1" one-question-ask one-question-actions
                                          {:route nowhere :session "component-catalog"})}
             {:title "several questions"
              :note "A verdict with one question per defect cannot let each answer give itself,
                     because no one answer is the whole of it. One card, one question at a time,
                     a marker per question, and Accept as the one submitting action. Click
                     Previous/Next and the markers — the stepper is page state and works here."
              :render #(ask/question-card "nido" "ws-1" two-question-ask two-question-actions
                                          {:route nowhere :session "auto"})}
             {:title "pending"
              :note "Its agent is running. Every choice renders with no control at all, so a
                     poll cannot flip it back to a fresh button."
              :render #(ask/question-card "nido" "ws-1" two-question-ask two-question-actions
                                          {:route nowhere :pending? true :session "auto"})}
             {:title "a failed action"
              :note "Set above the first question, and the controls stay clickable so the person
                     can retry."
              :render #(ask/question-card "nido" "ws-1" one-question-ask one-question-actions
                                          {:route nowhere
                                           :error-msg "approve refused: the design cites a baseline no round found sufficient"})}
             {:title "no recommendation"
              :note "The writer had none. With no recommendation and no submit there is no
                     primary button either — which is the honest rendering of a question nobody
                     has an answer to."
              :render (fn []
                        (ask/question-card
                         "nido" "ws-1"
                         (update-in one-question-ask [:questions 0]
                                    (fn [q] (-> q (dissoc :recommendation)
                                                (update :options
                                                        (fn [os] (mapv #(dissoc % :recommended?) os))))))
                         (mapv (fn [a] (dissoc a :style)) one-question-actions)
                         {:route nowhere}))}
             {:title "an evidence box"
              :note "A phase gate is asserted with what you observed on the running system. The
                     box is a row beside what it does, like every other control."
              :render #(ask/question-card
                        "nido" "ws-1"
                        {:kind :assert-gate
                         :question "Has phase 2 landed on the running system?"
                         :questions [{:question "Has phase 2 landed on the running system?"
                                      :context ["The exit criterion is the dashboard serving /_catalog on a daemon that has been up for an hour."]
                                      :options []}]
                         :answer-does [{:action :assert :label "Assert the gate"
                                        :does "Records what you observed and opens phase 3."}]}
                        [{:id :assert :label "Assert the gate" :kind :evidence
                          :criterion "the dashboard serves /_catalog on a daemon that has been up for an hour"}]
                        {:route nowhere})}
             {:title "a long question"
              :note "A question nobody managed to write in one line. Prose is bounded to the
                     reading measure and is never clipped — if this one is cut off or runs the
                     width of the pane, the card is wrong."
              :render #(ask/question-card
                        "nido" "ws-1"
                        (let [q (str "Should the component catalog cover the twenty per-kind record cards as "
                                     "well, given that each one reads a different ledger record's shape and "
                                     "would need a sample of that record to have a scene at all, or is that a "
                                     "different reading job from the blocks a person actually iterates on?")]
                          (-> one-question-ask (assoc :question q)
                              (assoc-in [:questions 0 :question] q)))
                        one-question-actions {:route nowhere})}]}
   {:name "action-bar"
    :note "One action set, rendered. The single renderer behind every gate's controls."
    :scenes [{:title "mutations and a reply"
              :render #(ask/action-bar "nido" "ws-1"
                                       [{:id :promote :label "Promote" :kind :mutation :style :primary}
                                        {:id :drop :label "Drop" :kind :mutation :style :danger}
                                        {:id :reply :label "Reply" :kind :resume}]
                                       "component-catalog" nowhere)}
             {:title "answering a blocker"
              :note "The free text is recorded on the ledger and resumes nobody, so the copy must
                     not promise a resume. That is the whole difference from the row above."
              :render #(ask/action-bar "nido" "ws-1"
                                       [{:id :reply :label "Answer" :kind :resume}]
                                       nil nowhere)}
             {:title "asserting a gate"
              :note "The evidence box comes first, above the buttons, because what you observed
                     is what the answer is."
              :render #(ask/action-bar "nido" "ws-1"
                                       [{:id :assert :label "Assert the gate" :kind :evidence
                                         :criterion "the dashboard serves /_catalog"}
                                        {:id :defer :label "Not yet" :kind :mutation}]
                                       nil nowhere)}]}
   {:name "card-signal"
    :note "The key a workstream's card keeps its step and choices under, in page state. A string,
           not markup — but the one thing two cards on one page must not share, so it is worth
           being able to see."
    :scenes [{:title "one key per workstream"
              :note "Every character a signal name cannot carry becomes _. Two workstreams must
                     never collide here, and the server reads a submit's choices back from the
                     same key."
              :render #(into [:div]
                             (for [id ["ws-20261113-609f4c" "ws-1" "BR-7285" "impl-BR-7285"]]
                               [:div.cat-pair
                                [:span.cat-scene-fn id]
                                [:span.cat-ret (str "$qcard." (ask/card-signal id))]]))}]}])

;; ---------------------------------------------------------------------------
;; The registry

(defn ^{:malli/schema [:=> [:cat] :any]}
  groups
  "One group per block vocabulary, in the order the levels stack: the marks a row is written in,
   the rows a page is written in, and the Ask a gate is answered on.

   A group is {:id :title :note :blocks}, and a block is {:name :note :scenes} where `:name` is
   the published function's own name — which is what lets the catalog test hold this registry to
   covering every one of them. A scene is {:title :note :render}, and `:render` is a thunk so
   that a broken block fails where it is rendered rather than when this registry is read."
  []
  [{:id "state" :title "State marks"
    :note "A workstream's state as a reader sees it. Every one of these is judged against its
           siblings rather than alone — a mark only means something relative to the other marks
           on the same row."
    :blocks (state-scenes)}
   {:id "row" :title "Rows"
    :note "The scannable lists. A row is read in a column of forty others, so what matters is
           which one your eye lands on: compare the ordinary row against the ones that need
           something."
    :blocks (row-scenes)}
   {:id "ask" :title "The Ask"
    :note "One decision, offered for answer. Datastar is live on this page, so the stepper, the
           choices and the answered-markers work exactly as they do on a real gate; the buttons
           post to a dead end."
    :blocks (ask-scenes)}])
