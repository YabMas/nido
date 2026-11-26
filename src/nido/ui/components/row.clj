(ns nido.ui.components.row
  "The dashboard's scannable list rows: the Workstreams board row, the needs-you row, the
   winding-down row and one stage of a workstream's arc.

   HIDES what a row puts where and how dense it is — which facts get a line of their own, which
   become a mark, and which are left to a mark's title.

   Each is a block registration rendered over the row's value as props, plus how a click on it
   is routed: a URL in the props for a row that navigates, the page's router for one whose click
   is an action it declares. None of them builds its own — a link carrying the view-state, and
   the reading position inside a pane, are the page's vocabulary, and a row that reached for
   either could be listed by exactly one page. That inversion is what lets the same row appear on
   the board, in a scene, and in whatever lists one next.

   Their rules are nido's own, in nido.ui.stylesheet: `.gate-card` and its parts are shared by
   three of them and by the pages around them, and a shared class's rules sit in nido's layer,
   above every block's.

   Written in `nido.ui.components.state`: a row asks for a mark and never for a word."
  (:require [clojure.string :as str]
            [nido.libraries.ui.block :as block]
            [nido.ui.components.state :as state]))

(defn- status-slot
  "The row's leading mark: where the workstream stands, as an icon with its words in the title —
   the state mark (needs you, working, paused, stalled), else an empty slot of the same width so
   every title starts in one column. Where a
   workstream came from is not overview information; its links are on the workstream."
  [mark]
  [:span.row-st mark])

(def ^:private chat-mark
  "The mark a row listing a workstream carries when a side chat pinned to it finished a turn, or
   waits on a permission, since the person last had it open: the unread-chat kind's mark."
  (state/kind-mark :chat "A chat here finished, or waits on you, since you last looked"))

(defn- tag-line
  "The workstream's stable identifier (wsv/tag — ticket id, else worktree name) on
   its own line under the generated title, which is what a person cannot search
   for: it moves whenever its basis does."
  [tag]
  (when tag [:div.gate-tag tag]))

;; ---------------------------------------------------------------------------
;; The lists

(defn- render-board-row
  "One selectable Workstreams row. `:href` is where clicking it goes and `:selected?` whether it
   is the open one. The row builds neither — a link that carries the view-state is the page's
   business, and a row that reached for the screen to build its own could only ever be listed
   by that page."
  [{:keys [part] {:keys [project href selected? chat?]
                  {:keys [label tag open-findings position phase stage] :as row} :row} :props}]
  [:a (merge (part :root) {:class (str "gate-card" (when selected? " sel")) :href href})
   [:div.gate-top
    (status-slot (when-not (= :merge (:source (:doing row)))
                   (state/state-mark (:status row) (:doing row))))
    [:span.lbl label]
    (when chat? chat-mark)
    (when (pos? (or open-findings 0))
      [:span.badge-findings (str "⚑ " open-findings " open findings")])]
   (tag-line tag)
   ;; Where the workstream stands, and one mark for whether anything is happening in it. The
   ;; next move, who owes it and what is running are the marks' titles, not words on the row.
   ;; A :shipping row's merge phase comes through state-mark, which the projection folds
   ;; ship-substate into, so it is never said twice.
   [:div.gate-sub [:span project] (state/position-chip position nil (get-in row [:status :needs]))
    ;; A merge keeps its words (state-mark's doing badge) here: a blocked merge reads red.
    (when (= :merge (:source (:doing row))) (state/state-mark (:status row) (:doing row)))
    (state/phase-chip phase)]
   ;; What the gate waits on, where the person owing it will look. The exit
   ;; criterion is the current phase's: its exit is the gate that opens the next.
   (when (= :awaiting-gate stage)
     (when-let [c (get-in phase [:exit :criterion])]
       [:div.gate-criterion [:span.meta "gate: "] c]))])

(defn- render-gate-row
  "One needs-you row, linking to `:href` — where the page opens the gate's workstream — and marked
   as the open one when the gate carries `:selected?`. A
   `:pending?` gate (its agent was resumed / is mid-resolve) shows 'working…'.
   `:error-msg` (a gate action that came back failed) renders on the row — the
   resolve is async, so this is the only trace a failed Apply leaves.
   `:doing` renders the same activity badge the workstream list shows: waiting on
   a person and having something underway are separate facts, and a gate can hold
   both at once."
  [{:keys [part] {:keys [href chat?]
                  {:keys [project stage label tag report ask note session resume-error pending? error-msg
                          doing status needs denials selected?]} :gate} :props}]
  [:a (merge (part :root) {:class (str "gate-card" (when selected? " sel")) :href href})
   [:div.gate-top (status-slot (state/needs-kind-mark needs)) [:span.lbl label] (when chat? chat-mark)]
   (tag-line tag)
   [:div.gate-sub [:span project] (state/stage-chip stage)
    [:span (state/gate-standing {:pending? pending? :session session :needs needs})]
    ;; What a gate is PARKED on is the line above; this is what is running in it
    ;; right now. A claim outranks session state, so the two coexist — and the
    ;; queue would otherwise be the one surface silent about the round underway.
    (state/state-mark (select-keys status [:live :owed-by]) doing)]
   ;; The question the card asks, so a reader scanning the list sees what each one wants of them —
   ;; or, for a card that asks nothing (a landing, a failed start), what it says instead.
   [:div.gate-prev (or (:question ask)
                       note
                       (some-> report :markdown
                               (str/replace #"^#.*\n+" "")
                               str/split-lines first)
                       "—")]
   (when resume-error
     [:div.gate-err "⚠ resume failed: " (or (:message resume-error)
                                            (name (:reason resume-error)))])
   (when error-msg [:div.gate-err "⚠ " error-msg])])

(defn- render-winddown-row
  "One winding-down row: a closed workstream still holding live sessions. Muted;
   one action, :bring-down, routed by the page — so the scope the reader is on rides
   along instead of the response resetting the view. A :pending? row
   shows 'stopping…' (no re-clickable button); the 5s poll drops the row once its
   sessions are down. An
   :error-msg (from a failed bring-down!) renders inline AND keeps the button —
   clicking retry sets :stopping, which overwrites the :failed entry, so retry
   self-clears the error."
  [{:keys [part action] {:keys [chat?] {:keys [project ws-id label outcome sessions rss-str pending? error-msg]} :row} :props}]
  [:div.gate-card.winddown (part :root)
   [:div.gate-top (status-slot nil) [:span.lbl label] (when chat? chat-mark)]
   [:div.gate-sub
    [:span project]
    [:span.meta (state/winddown-words {:outcome outcome :session-count (count sessions) :rss-str rss-str})]
    (if pending?
      [:span.meta (state/winddown-words {:pending? true})]
      (list
       (when error-msg [:div.error-msg error-msg])
       [:button.btn.btn-danger
        {"data-on:click" (action :bring-down {:params {:project project :ws-id ws-id}})}
        "Bring down"]))]])

;; ---------------------------------------------------------------------------
;; The arc

(defn- arc-meta
  "The one line of detail a stage row carries, or nil when it holds nothing worth
   a phrase. Never the stage's own name reworded — a row whose detail restates its
   label is the arc line that was removed for exactly that."
  [{:keys [entries state]}]
  (cond
    ;; The count is what a live stage says; a stale one has to say what
    ;; happened to it instead, because the records are still there and counting
    ;; them is exactly the reading that misleads.
    (= :stale state) (str entries " record" (when (not= 1 entries) "s")
                          " · no longer stands")
    (pos? entries) (str entries " record" (when (not= 1 entries) "s"))
    (= :skipped state) "no record"
    :else nil))

(defn- render-arc-row
  "One stage of the arc. `:open?` says whether this stage's records are expanded; a click on it
   is its :open action, routed by the pane — because where a reader is in a pane is the pane's
   own vocabulary and a row that named it could be listed by nothing else. Clickable only when it
   holds records — an empty stage has nothing to expand, and offering the click would promise a
   section that opens empty.

   The re-entry count renders only above one, and in the colour the pane already
   uses for `needs you`. A stage entered once is the ordinary case and saying so
   on every row would bury the case that matters: `2 visits` on a baseline means a
   later stage sent the work back to it, which is the fact the ledger index cannot
   show and a forward-only track cannot show either."
  [{:keys [part action] {:keys [round-label open?] {:keys [stage state visits now needs] :as facet} :facet} :props}]
  (let [live? (pos? (:entries facet))]
    [:div (cond-> (merge (part :root)
                         {:class (str "arc-row " (name state)
                                      (when (= :awaiting now) " awaiting")
                                      (when live? " live") (when open? " open"))})
            live? (assoc "data-on:click" (action :open {:params {:stage stage}})))
     [:span.arc-g (state/arc-mark state now needs)]
     [:span.arc-n (state/arc-name stage)]
     (when-let [m (arc-meta facet)] [:span.arc-m m])
     ;; The Land rung's line: the landing's trail, and a merge underway in the merge's own words.
     (when-let [t (state/land-trail (:trail facet))] [:span.arc-d (str "· " t)])
     (when-let [d (:doing facet)] [:span.arc-d (state/state-mark nil d)])
     (when (and (= :running now) round-label) [:span.arc-d (str "· " round-label)])
     (when (> visits 1)
       [:span.arc-v {:title "the work left this stage and came back"}
        (str "· " visits " visits")])]))

;; ---------------------------------------------------------------------------
;; Scenes: literal rows

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

(defn- board [row & {:as more}] (merge {:project "nido" :row row :href "#"} more))

(defn- gate [g] {:gate g :href "#"})

(def ^:private a-winddown
  {:project "nido" :ws-id "ws-wd" :origin :scratch :label "component-catalog" :outcome :done})

(defn- arc [facet & {:as more}] (merge {:facet facet :open? false} more))

;; ---------------------------------------------------------------------------
;; The registrations

(def ^{:malli/schema :map}
  board-row
  "One selectable Workstreams row."
  (block/block
   {:id      :board-row
    :doc     "One selectable Workstreams row. It leads with where the workstream stands, as one
              icon — needs you, working, paused or stalled — and never with where it came from.
              The page gives it the URL and whether it is the open one; everything else is the
              row's value."
    :render  render-board-row
    :props   [:map [:project :string] [:row :map] [:href :string]
              [:selected? {:optional true} [:maybe :boolean]]
              [:chat? {:optional true} :boolean]]
    :css     ""
    :actions {}
    :morph   {}
    :scenes
    [{:title "the ordinary row"
      :note  "Nothing owes a person anything and something is running. This is most of the board,
              and it is the one the others have to stand out against."
      :props (board a-board-row)}
     {:title "selected"
      :note  "The open row. The highlight has to survive a poll, so it is given rather than
              derived — a row cannot know which one the reader opened."
      :props (board a-board-row :selected? true)}
     {:title "it needs you"
      :note  "A lit position chip and the needs dot. Compare against the ordinary row above: this
              is the comparison a board is scanned for."
      :props (board (assoc a-board-row :needs-you true :status (assoc (:status a-board-row) :needs-you true :owed-by :person :needs :ask)
                           :position {:at :blocked :next {:stage :answer-blocker :mode :human}}))}
     {:title "open findings"
      :note  "A shipped workstream a staging round reopened."
      :props (board (assoc a-board-row :open-findings 3
                           :position {:at :findings-open :next {:stage :address-findings :mode :authoring}}))}
     {:title "a gate and its criterion"
      :note  "The only row that gets a second line: what the gate waits on, where the person owing
              it will look."
      :props (board (assoc a-board-row :stage :awaiting-gate :needs-you true
                           :status (assoc (:status a-board-row) :needs-you true :owed-by :person :needs :ask)
                           :position {:at :awaiting-gate :next {:stage :assert-gate :mode :human}}
                           :phase {:current 2 :of 3
                                   :exit {:criterion "the dashboard serves /_catalog on a daemon that has been up for an hour"}}))}
     {:title "a chat here has news"
      :note  "A chat pinned to this workstream finished a turn, or waits on a permission, since the
              person last had it open: the bubble after the title, until they open it."
      :props (board a-board-row :chat? true)}
     {:title "a long title"
      :note  "The row has to survive a generated label that does not fit. If it wraps to three
              lines or pushes the marks off the end, the row is wrong, not the ticket."
      :props (board (assoc a-board-row :label "A really quite long generated workstream title that nobody wrote on purpose and which keeps going well past the width of any column it will ever be shown in"))}]}))

(def ^{:malli/schema :map}
  gate-row
  "One needs-you row."
  (block/block
   {:id      :gate-row
    :doc     "One needs-you row. It previews the question, so a reader scanning the list sees what
              each one wants of them."
    :render  render-gate-row
    :props   [:map [:gate :map] [:href :string] [:chat? {:optional true} :boolean]]
    :css     ""
    :actions {}
    :morph   {}
    :scenes
    [{:title "one question"
      :note  "The ordinary needs-you row: the question it asks, under the workstream it is on."
      :props (gate a-gate)}
     {:title "working"
      :note  "Its agent was resumed and is mid-resolve. No action is offered anywhere until it
              comes back."
      :props (gate (assoc a-gate :pending? true))}
     {:title "a failed action"
      :note  "The resolve is async, so this line is the only trace a failed Apply leaves."
      :props (gate (assoc a-gate :error-msg "apply refused: the design no longer stands"))}
     {:title "a resume that failed"
      :note  "The parked session could not be resumed, and the row says why."
      :props (gate (assoc a-gate :resume-error {:reason :no-session}))}
     {:title "no Ask, only a report"
      :note  "An older gate whose question was never written as an Ask. The preview falls back to
              the report's first prose line."
      :props (gate (-> a-gate (dissoc :ask :session)
                       (assoc :report {:markdown "# Verdict\n\nbug — reproduced against the running app."})))}
     {:title "something running in it"
      :note  "Waiting on a person and having something underway are separate facts, and a gate can
              hold both at once."
      :props (gate (assoc a-gate :doing {:source :claim :kind :diff-review}))}]}))

(def ^{:malli/schema :map}
  winddown-row
  "One winding-down row."
  (block/block
   {:id      :winddown-row
    :doc     "A closed workstream still holding live sessions. Muted, one action."
    :render  render-winddown-row
    :props   [:map [:row [:map [:project :string] [:ws-id :string]]] [:chat? {:optional true} :boolean]]
    :css     ""
    :actions {:bring-down [:post :winddown {}]}
    :morph   {}
    :scenes
    [{:title "live sessions"
      :note  "Its sessions and what they cost, beside the one thing to do about them."
      :props {:row (assoc a-winddown :sessions [{} {}] :rss-str "3.9 GB")}}
     {:title "stopping"
      :note  "No re-clickable button while a bring-down is in flight; the 5s poll drops the row
              once its sessions are down."
      :props {:row (assoc a-winddown :sessions [{}] :pending? true)}}
     {:title "a bring-down that failed"
      :note  "The error renders inline AND the button stays, because clicking retry overwrites the
              failed entry — retry self-clears the error."
      :props {:row (assoc a-winddown :outcome :dropped :sessions [{}]
                          :error-msg "pg_ctl: could not create lock file")}}]}))

(def ^{:malli/schema :map}
  arc-row
  "One stage of a workstream's arc."
  (block/block
   {:id      :arc-row
    :doc     "One stage of a workstream's arc. Clickable only when it holds records."
    :render  render-arc-row
    :props   [:map [:facet :map] [:open? :boolean] [:round-label {:optional true} [:maybe :string]]]
    :css     ""
    :actions {:open [:get :stage {}]}
    :morph   {}
    :scenes
    [{:title "running"
      :note  "A stage an agent is working in, with where its review round has got."
      :props (arc {:stage :design :state :current :visits 1 :entries 2 :now :running}
                  :round-label "design round 3 · judging · 0 findings")}
     {:title "waiting on you"
      :note  "The needs dot outranks the record state beneath it. A stage a person is being waited
              on at is the one fact a reader scans an arc for."
      :props (arc {:stage :design :state :done :visits 1 :entries 2 :now :awaiting})}
     {:title "revisited"
      :note  "`3 visits` means a later stage sent the work back here — the fact the ledger index
              cannot show and a forward-only track cannot show either. It renders only above one,
              so the ordinary case stays quiet."
      :props (arc {:stage :baseline :state :done :visits 3 :entries 5})}
     {:title "stale"
      :note  "A stale stage must not say how many records it has as though they counted; it says
              they no longer stand."
      :props (arc {:stage :baseline :state :stale :visits 1 :entries 4})}
     {:title "skipped"
      :note  "A skipped stage says there is no record."
      :props (arc {:stage :design :state :skipped :visits 0 :entries 0})}
     {:title "expanded"
      :note  "The stage whose records are open beneath it."
      :props (arc {:stage :intent :state :done :visits 1 :entries 1} :open? true)}
     {:title "ahead, and unclickable"
      :note  "An empty stage offers no click at all: offering one would promise a section that
              opens empty."
      :props (arc {:stage :implementation :state :ahead :visits 0 :entries 0})}
     {:title "landing under way"
      :note  "The Land rung past the stages while the person's grant stands: its line says the steps
              observed so far, an ejection kept after the re-queue that followed it, and a merge
              waiting its turn in the merge's own words."
      :props (arc {:stage :land :state :current :visits 1 :entries 4
                   :doing {:source :merge :phase :queued}
                   :trail {:granted? true
                           :steps [{:format :landing :step :submitted :pr "o/r#57"}
                                   {:format :landing :step :queued}
                                   {:format :landing :step :ejected :reason "CI failed"}
                                   {:format :landing :step :queued}]}})}
     {:title "landing waits on you"
      :note  "The person awaited past the stages, said on the rung that is current."
      :props (arc {:stage :land :state :current :visits 1 :entries 1 :now :awaiting :needs :ask
                   :trail {:granted? true :steps [{:format :landing :step :submitted :pr "o/r#57"}]}})}
     {:title "landed"
      :note  "The merge spent the grant, so the line holds only what was recorded."
      :props (arc {:stage :land :state :done :visits 1 :entries 3
                   :trail {:granted? false
                           :steps [{:format :landing :step :submitted :pr "o/r#57"}
                                   {:format :landing :step :queued}
                                   {:format :merged}]}})}]}))

