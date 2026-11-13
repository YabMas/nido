(ns nido.ui.components.row
  "The dashboard's scannable list rows: the Workstreams board row, the needs-you row, the
   winding-down row and one stage of a workstream's arc.

   HIDES what a row puts where and how dense it is — which facts get a line of their own, which
   become a mark, and which are left to a mark's title.

   Each takes the row's value plus how a click on it is routed: a URL for a row that navigates,
   the expression a click evaluates for one that patches a fragment in place. None of them
   builds its own — a link carrying the view-state, and the reading position inside a pane, are
   the page's vocabulary, and a row that reached for either could be listed by exactly one page.
   That inversion is what lets the same row appear on the board, in a scene, and in whatever
   lists one next.

   Written in `nido.ui.components.state`: a row asks for a mark and never for a word."
  (:require [clojure.string :as str]
            [nido.ui.components.state :as state]))

(defn- tag-line
  "The workstream's stable identifier (wsv/tag — ticket id, else worktree name) on
   its own line under the generated title, which is what a person cannot search
   for: it moves whenever its basis does."
  [tag]
  (when tag [:div.gate-tag tag]))

;; ---------------------------------------------------------------------------
;; The lists

(defn ^{:malli/schema [:=> [:cat :ProjectName :map :map] :any]}
  board-row
  "One selectable Workstreams row. `opts` is {:href :selected?}: where clicking it goes, and
   whether it is the open one. The row builds neither — a link that carries the view-state is
   the page's business, and a row that reached for the screen to build its own could only ever
   be listed by that page."
  [project {:keys [ws-id origin label tag needs-you open-findings position phase stage]
            :as row}
   {:keys [href selected?]}]
  [:a {:class (str "gate-card" (when selected? " sel"))
       :href  href}
   [:div.gate-top (state/origin-badge origin) [:span.lbl label]
    (when needs-you [:span.needs {:title "needs you"}])
    (when (pos? (or open-findings 0))
      [:span.badge-findings (str "⚑ " open-findings " open findings")])]
   (tag-line tag)
   ;; Where the workstream stands, and one mark for whether anything is happening in it. The
   ;; next move, who owes it and what is running are the marks' titles, not words on the row.
   ;; A :shipping row's merge phase comes through state-mark, which the projection folds
   ;; ship-substate into, so it is never said twice.
   [:div.gate-sub [:span project] (state/position-chip position) (state/state-mark (:status row) (:doing row))
    (state/phase-chip phase)]
   ;; What the gate waits on, where the person owing it will look. The exit
   ;; criterion is the current phase's: its exit is the gate that opens the next.
   (when (= :awaiting-gate stage)
     (when-let [c (get-in phase [:exit :criterion])]
       [:div.gate-criterion [:span.meta "gate: "] c]))])

(defn ^{:malli/schema [:=> [:cat :map :string] :any]}
  gate-row
  "One needs-you row, linking to `href` — the workstream the gate is answered on. A
   `:pending?` gate (its agent was resumed / is mid-resolve) shows 'working…'.
   `:error-msg` (a gate action that came back failed) renders on the row — the
   resolve is async, so this is the only trace a failed Apply leaves.
   `:doing` renders the same activity badge the workstream list shows: waiting on
   a person and having something underway are separate facts, and a gate can hold
   both at once."
  [{:keys [project origin stage label tag report ask session resume-error pending? error-msg doing denials]} href]
  [:a {:class "gate-card"
       :href  href}
   [:div.gate-top (state/origin-badge origin) [:span.lbl label] [:span.needs {:title "needs you"}]]
   (tag-line tag)
   [:div.gate-sub [:span project] (state/stage-chip stage)
    [:span (cond pending? "working…" session (str "parked · " session) :else "decide")]
    ;; What a gate is PARKED on is the line above; this is what is running in it
    ;; right now. A claim outranks session state, so the two coexist — and the
    ;; queue would otherwise be the one surface silent about the round underway.
    (state/state-mark nil doing)]
   ;; The question the card asks, so a reader scanning the list sees what each one wants of them.
   [:div.gate-prev (or (:question ask)
                       (some-> report :markdown
                               (str/replace #"^#.*\n+" "")
                               str/split-lines first)
                       "—")]
   (when resume-error
     [:div.gate-err "⚠ resume failed: " (or (:message resume-error)
                                            (name (:reason resume-error)))])
   (when error-msg [:div.gate-err "⚠ " error-msg])
   (when (seq denials)
     [:div.gate-err "⚠ " (count denials) " permission denial" (when (< 1 (count denials)) "s")
      " — latest: " (or (:tool-name (peek denials)) "?")])])

(defn ^{:malli/schema [:=> [:cat :map :string] :any]}
  winddown-row
  "One winding-down row: a closed workstream still holding live sessions. Muted;
   one action, posting to `post-url` — which the page builds, so the scope the reader
   is on rides along instead of the response resetting the view. A :pending? row
   shows 'stopping…' (no re-clickable button); the 5s poll drops the row once its
   sessions are down. An
   :error-msg (from a failed bring-down!) renders inline AND keeps the button —
   clicking retry sets :stopping, which overwrites the :failed entry, so retry
   self-clears the error."
  [{:keys [project origin label outcome sessions rss-str pending? error-msg]} post-url]
  [:div.gate-card.winddown
   [:div.gate-top (state/origin-badge origin) [:span.lbl label]]
   [:div.gate-sub
    [:span project]
    [:span.meta (str "closed:" (name (or outcome :done)) " · "
                     (count sessions) " live session(s)"
                     (when rss-str (str " · " rss-str)))]
    (if pending?
      [:span.meta "stopping…"]
      (list
       (when error-msg [:div.error-msg error-msg])
       [:button.btn.btn-danger
        {"data-on:click" (str "@post('" post-url "')")}
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

(defn ^{:malli/schema [:=> [:cat :map [:maybe :string] :map] :any]}
  arc-row
  "One stage of the arc. `opts` is {:open? :on-click}: whether this stage's records are
   expanded, and the expression a click on it evaluates — the pane's, because where a reader is
   in a pane is the pane's own vocabulary and a row that named it could be listed by nothing
   else. Clickable only when it holds records — an empty stage has
   nothing to expand, and offering the click would promise a section that opens
   empty.

   The re-entry count renders only above one, and in the colour the pane already
   uses for `needs you`. A stage entered once is the ordinary case and saying so
   on every row would bury the case that matters: `2 visits` on a baseline means a
   later stage sent the work back to it, which is the fact the ledger index cannot
   show and a forward-only track cannot show either."
  [{:keys [stage state visits now] :as facet} round-label {:keys [open? on-click]}]
  (let [live? (pos? (:entries facet))]
    [:div (cond-> {:class (str "arc-row " (name state)
                              (when (= :awaiting now) " awaiting")
                              (when live? " live") (when open? " open"))}
            (and live? on-click) (assoc "data-on:click" on-click))
     [:span.arc-g (state/arc-mark state now)]
     [:span.arc-n (state/arc-name stage)]
     (when-let [m (arc-meta facet)] [:span.arc-m m])
     (when (and (= :running now) round-label) [:span.arc-d (str "· " round-label)])
     (when (> visits 1)
       [:span.arc-v {:title "the work left this stage and came back"}
        (str "· " visits " visits")])]))
