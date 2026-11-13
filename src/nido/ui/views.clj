(ns nido.ui.views
  "Hiccup view functions for the nido dashboard."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.coordinator.report :as report]
            [nido.coordinator.view.recoveries :as recoveries-view]
            [nido.coordinator.view.runs :as runs-view]
            [nido.coordinator.view.workstreams :as wsv]
            [nido.coordinator.control :as control]
            [nido.platform.process :as process]
            [nido.ui.markdown :as md]
            [nido.ui.stylesheet :as css]
            [nido.ui.components.state :as state]
            [nido.ui.components.row :as row]
            [nido.ui.components.ask :as ask]
            [nido.coordinator.work :as work]))

;; ---------------------------------------------------------------------------
;; Layout

;; ---------------------------------------------------------------------------
;; Shell (persistent rail + content area) — replaces per-page headers.

(defn- rail-needs-badge [n]
  [:span {:id "rail-needs-count" :class (str "rail-badge" (when (zero? (or n 0)) " zero"))} n])

(defn- rail-health [{:keys [state]}]
  (let [s (name (or state :down))]
    [:div {:id "rail-health" :class "rail-health"}
     [:button.rail-health-btn {"data-on:click" "$opsOpen = !$opsOpen"
                               :title "ops panel"}
      [:span {:class (str "dot dot-" s)}] s]]))

(defn- rail
  "The persistent navigation rail. Scope is a sticky dimension: a scope link stays on the
   current surface (changing only scope); a surface link carries the current scope.
   Selecting a project changes what you see, never where you are."
  [{:keys [active needs-count daemon scope projects]}]
  (let [surface-path {:needs "/" :workstreams "/workstreams" :operations "/operations"}
        q (fn [scope-val]
            (if (and scope-val (not= "all" scope-val)) (str "?scope=" scope-val) ""))
        ;; Operations is the one destination scope does not reach — see the
        ;; comment on its link below — so it is linked bare and never carries
        ;; the scope the reader arrived with.
        dest (fn [id href label]
               [:a {:class (str "rail-link" (when (= id active) " active"))
                    :href (if (= id :operations)
                            href
                            (str href (q scope)))}
                [:span label]
                (when (= id :needs) (rail-needs-badge needs-count))])
        scope-link (fn [scope-val label]
                     [:a {:class (when (= scope scope-val) "active")
                          :href (str (surface-path active) (q scope-val))}
                      label])]
    [:nav.rail
     [:a.rail-brand {:href "/" :title "nido"}
      [:img.rail-logo {:src "/nido-logo.png" :alt "nido" :width 84 :height 84}]]
     (dest :needs "/" "Needs you")
     (dest :workstreams "/workstreams" "Workstreams")
     ;; A third destination, and the first that is not workstream-shaped: its
     ;; unit is the proposal. /system was dissolved for being a second view of
     ;; the plane the board already showed; this is a different plane — records
     ;; that are not a stage of any arc, read across workstreams.
     (dest :operations "/operations" "Operations")
     ;; No scope selector on Operations. Scope picks which project's WORK you
     ;; are looking at, and this surface is not looking at work — it is nido
     ;; operating on itself, across every project at once. Offering the control
     ;; where it means nothing invites the reading that an empty project scope
     ;; means an empty backlog.
     (when-not (= :operations active)
       [:div.rail-scope
        [:div.meta "Scope"]
        (scope-link "all" "All projects")
        (for [p projects] (scope-link p p))])
     (rail-health daemon)]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  rail-status-fragment
  "The two live rail elements, for SSE patching alongside a surface fragment."
  [{:keys [needs-count daemon]}]
  (str (h/html (rail-needs-badge needs-count))
       (h/html (rail-health daemon))))

(defn ^{:malli/schema [:=> [:cat :any :keyword] :string]}
  fire-signal
  "Signal name for one fire-form input: JS-identifier-safe (hyphens → underscores).
   The /ops/fire route reads the SAME name back out of the signal body — keep the
   two sides on this one fn."
  [trigger-name k]
  (str "fire_" (str/replace (name trigger-name) "-" "_")
       "_" (str/replace (name k) "-" "_")))

(defn- fleet-idle-str [{:keys [idle-ms]}]
  (if (nil? idle-ms)
    "no agent"
    (let [h (quot idle-ms 3600000)]
      (if (>= h 48) (str (quot h 24) "d") (str h "h")))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  fleet-card
  "Fleet memory: what every live session costs together, and what is idle enough
   to reclaim.

   Deliberately carries NO per-session memory column. `work/all-machine-rows`
   already owns that number and already renders it on the workstream pane and
   the winding-down band; a third copy here would drift from the two already on
   the board. What this card knows that the board cannot is the AGGREGATE — the
   machine total every session adds up against — and which sessions nothing is
   driving. That is the question the board has no way to ask.

   This is where the 2026-07-21 dissolve-system design put it: `/system` was the
   only surface that revealed the RAM-eater, and its global, not-per-workstream
   residue was rehomed to exactly this ambient chrome.

   `nil` renders as unavailable rather than as an empty fleet — a probe that
   failed must not read as a machine with nothing on it."
  [{:keys [sessions in-use machine over? candidates signals-ok?] :as fleet}]
  [:div.card {:class (when over? "fleet-over")}
   [:strong "Fleet"]
   (if (nil? fleet)
     [:div [:span.meta "unavailable"]]
     (let [frac (when (and in-use machine (pos? machine))
                  (min 1.0 (/ (double in-use) machine)))
           more (max 0 (- (count candidates) 3))]
       (list
        [:div.fleet-head
         [:span (process/human-bytes in-use) " / " (process/human-bytes machine)]
         [:span.fleet-pct.meta (when frac (str (Math/round (* 100.0 frac)) "%"))]]
        [:div.fleet-bar [:span {:style (str "width:" (Math/round (* 100.0 (or frac 0))) "%")}]]
        [:div.meta (str sessions " live session" (when (not= 1 sessions) "s"))]
        (cond
          (seq candidates)
          (list
           [:div.meta {:style "margin-top:7px"} "Nothing driving these:"]
           (for [c (take 3 candidates)]
             (list
              [:div.fleet-cand
               [:span.nm (str (:project c) "/" (:session c))]
               [:span.sz (process/human-bytes (:bytes c)) " · " (fleet-idle-str c)]
               (if (:pending? c)
                 [:span.meta "stopping…"]
                 [:button.btn
                  {"data-on:click"
                   (str "@post('/ops/fleet/" (:project c) "/"
                        (java.net.URLEncoder/encode (str (:session c)) "UTF-8")
                        "/down')")}
                  "down"])]
              ;; A stop that failed keeps its button — the action stays retryable —
              ;; and says why. The panel polls every 5s, so this is the only place
              ;; the click's outcome is ever reported.
              (when-let [err (:error-msg c)]
                [:div.meta {:style "color:var(--failed)"} err])))
           (when (pos? more) [:div.meta (str "+" more " more")]))

          ;; A blind probe also produces no candidates, and "nothing is idle" is a
          ;; claim the snapshot cannot make from an absent signal. Same defect as
          ;; the predicate's, one layer up: silence must not be read as an answer.
          (false? signals-ok?)
          [:div.meta {:style "margin-top:7px"} "Idle check unavailable."]

          :else
          [:div.meta {:style "margin-top:7px"} "Nothing idle — every session was touched today."]))))])

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  ops-panel-fragment
  "The ambient ops chrome: daemon state, halt/resume, open breakers with why
   each is open — clear for a trip, resume for a person's pause — and a fire form per manual trigger (placeholder-less →
   one click; placeholder-carrying → one input per {{event/*}} key, signals
   fire_<trigger>_<key>). No route of its own — lives behind the rail dot."
  [{:keys [daemon halt breakers triggers fleet]}]
  (str
   (h/html
    [:div {:id "ops-panel" :class "ops-panel"}
     [:div.card
      [:span {:class (str "dot dot-" (name (or (:state daemon) :down)))}]
      " daemon " (name (or (:state daemon) :down))
      (when-let [hb (:heartbeat-at daemon)] [:span.meta " · heartbeat " hb])]
     [:div.card
      (if halt
        (list [:span "⏸ halted by " (name (:source halt))
               (when (:note halt) (str " — " (:note halt)))]
              [:button.btn.btn-primary {"data-on:click" "@post('/ops/resume')"} "Resume"])
        (list [:span "running"]
              [:button.btn.btn-danger {"data-on:click" "@post('/ops/halt')"} "Halt"]))]
     (fleet-card fleet)
     [:div.card
      [:strong "Breakers"]
      (if (seq breakers)
        ;; A pause is a person's decision and a trip is a fault, so each gets its own lever:
        ;; clearing a trip never lifts a pause, and only "resume" does.
        (for [{:keys [project trigger info]} breakers
              :let [lever (fn [verb label]
                            [:button.btn {"data-on:click"
                                          (str "@post('/ops/breakers/" (name project) "/" (name trigger) "/" verb "')")}
                             label])]]
          [:div.actions
           [:span.mono (str (name project) "/" (name trigger))]
           [:span.meta (str " — " (runs-view/breaker-reason info))]
           (when (:tripped? info) (lever "clear" "clear"))
           (when (:disabled-by-user? info) (lever "resume" "resume"))])
        [:span.meta "none tripped"])]
     [:div.card
      [:strong "Fire trigger"]
      (for [[project ts] triggers
            {:keys [name payload] :as _t} ts]
        (let [ks (control/trigger-placeholders (or payload "{}"))]
          [:div.fire-row
           [:span.mono (str (clojure.core/name project) "/" (clojure.core/name name))]
           (for [k ks]
             [:input {"data-bind" (fire-signal name k)
                      :placeholder (clojure.core/name k)}])
           [:button.btn {"data-on:click"
                         (str "@post('/ops/fire/" (clojure.core/name project)
                              "/" (clojure.core/name name) "')")}
            "fire"]]))]])))

(defn ^{:malli/schema [:=> [:cat :map :any] :any]}
  shell
  "Page chrome: persistent rail + content area. Replaces `layout`. `ctx` carries
   {:active :title :needs-count :daemon :scope :projects}; `content` is hiccup.
   The ops poll carries the current :scope (when not \"all\") so the badge count
   the poll patches in stays scoped instead of periodically clobbering it back
   to the global count."
  [{:keys [title scope] :as ctx} & content]
  (let [ops-q (if (and scope (not= "all" scope)) (str "?scope=" scope) "")]
    (str
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]
        [:title (or title "nido") " — nido"]
        [:script {:type "module" :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.1/bundles/datastar.js"}]
        [:style (h/raw (css/stylesheet))]]
       [:body {:data-signals__ifmissing "{opsOpen: false}"}
        (rail ctx)
        (into [:main.content] content)
        [:div.ops-wrap {:data-show "$opsOpen"
                        :data-on-interval__duration.5s
                        (str "$opsOpen && @get('/_fragment/ops" ops-q "')")}
         [:div {:id "ops-panel"} [:p.meta "…"]]]]]))))

;; ---------------------------------------------------------------------------
;; Components

;; ---------------------------------------------------------------------------
;; Fragments (for SSE updates)

;; ---------------------------------------------------------------------------
;; Gate inbox (cross-project human-decision queue over nido.coordinator.work)

(defn- pause-line
  "The pane's pause control under its Status heading: while a person's pause stands, that it does
   and why, with Resume; otherwise a quiet Pause. Pausing is the person's choice not to push the
   workstream forward for now, so it is offered on every open workstream and asks nothing."
  [project ws-id pause]
  (let [post (fn [verb] (str "@post('/workstreams/" project "/" ws-id "/" verb "')"))]
    (if pause
      [:div.pause-line
       [:span (str "Paused" (when-let [n (:note pause)] (str " — " n)))]
       [:button.btn.pause-ctl {"data-on:click" (post "resume")} "Resume"]]
      [:div.pause-line
       [:button.btn.pause-ctl {"data-on:click" (post "pause") :title "stop pushing this forward for now"} "Pause"]])))

(defn- links-row
  "The workstream's followable external refs under a pane heading — one anchor per
   ref, keyed by its adapter label. Renders nothing when there are none, so a
   ref-less workstream gets no empty box."
  [links]
  (when (seq links)
    [:div.links
     (for [{:keys [label id title url]} links]
       [:div.link-row
        [:span.link-k label]
        [:a {:href url :target "_blank" :title title} (or id url) " ↗"]])]))

(defn- pane-heading
  "A detail pane's <h1>: origin badge + label, with the label linking out to the
   primary (first) external ref when there is one, and the stable tag beneath it,
   selectable in one click for pasting into a terminal. The badge stays outside
   the anchor so only the title is clickable."
  [origin label links tag]
  (let [primary (:url (first links))]
    (list
     [:h1 (state/origin-badge origin) " "
      (if primary
        [:a {:href primary :target "_blank"} label " ↗"]
        label)]
     (when tag [:p.pane-tag tag]))))

(defn- screen-query
  "Query string (leading ?) rebuilding the active scope from the screen, with
   optional overrides. `:sel` adds the selection (\"project:ws-id\"). The single
   place scope and selection are serialized — so a row link, a poll refresh, and
   a deep link all carry the identical view-state. Scope \"all\" is omitted,
   keeping /workstreams clean."
  [{:keys [scope]} & [overrides]]
  (let [sel   (:sel overrides)
        pairs (cond-> []
                (and scope (not= "all" scope)) (conj (str "scope=" scope))
                sel (conj (str "sel=" sel)))]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))

;; The workstream pane's READING POSITION —
;; {:project :ws-id :entry :rounds :stage :history?} — and the one expression
;; that navigates to it. Everything the reader can move is in that map: which
;; ledger entry is open (nil = none, the resting state), which of its review
;; rounds are unfolded, which arc stage is expanded, and whether the raw ledger
;; index is showing. Every affordance in the pane
;; (an index row, a stage, a round's fold, the viewer's close, the 3s poll) is the same
;; position with one field changed, so none of them can disagree about where the
;; reader is — and the poll, which re-renders the whole pane, lands them back
;; exactly where they were rather than resetting the fold under their cursor.

(defn- pane-fragment
  "`@get(…)` expression for the pane fragment at reading position `pos`. Omits
   whatever is at rest, so a pane nobody has opened anything in polls the bare
   URL."
  [{:keys [project ws-id entry rounds stage history?]}]
  (let [ps (cond-> []
             entry        (conj (str "entry=" entry))
             (seq rounds) (conj (str "rounds=" (str/join "," (sort rounds))))
             stage        (conj (str "stage=" (name stage)))
             history?     (conj "history=1"))]
    (str "@get('/_fragment/workstream/" project "/" ws-id
         (when (seq ps) (str "?" (str/join "&" ps))) "')")))

(defn- at-entry
  "`pos` with ledger entry `seq` open (nil closes the viewer). Clears the round
   folds either way: they index into the report being closed."
  [pos seq]
  (assoc pos :entry seq :rounds nil))

(defn- toggle-round
  "`pos` with review round `n` flipped between folded and not. Rounds are numbered
   within the open entry's report, so this only ever means anything alongside an
   :entry — which is the only place the fold is rendered."
  [pos n]
  (let [open (set (:rounds pos))]
    (assoc pos :rounds (if (open n) (disj open n) (conj open n)))))

(defn- at-stage
  "`pos` with arc stage `st` expanded, or collapsed again when it already was.
   Clears any open entry: the entry a reader had open belonged to the stage they
   just closed, and leaving it open would strand a viewer under a section that is
   no longer on the page."
  [pos st]
  (assoc pos :stage (when-not (= st (:stage pos)) st) :entry nil :rounds nil))

(defn- toggle-history
  "`pos` with the raw ledger index shown or hidden. Independent of everything else
   in the position — the log is a second way to reach the same entries, not a
   different place to be."
  [pos]
  (update pos :history? not))

(defn- denial-line
  "One auto-mode denial a session nido drives recorded: when, in which session, which tool."
  [{:keys [session at tool-name tool-input]}]
  [:li [:span.meta at] " · " session " · " [:code (or tool-name "?")]
   (when tool-input [:span.meta " " tool-input])])

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  needs-fragment
  "The needs-you list — initial render + SSE refresh: one row per gate, each opening that
   workstream's pane, where its question is answered."
  [{:keys [gates]}]
  (str
   (h/html
    (if (seq gates)
      [:div {:id "needs"}
       (for [g gates]
         (row/gate-row g (str "/workstreams/" (:project g) "/" (:ws-id g))))]
      [:div {:id "needs"} [:p.empty "Nothing needs you right now."]]))))

(defn- gate-action-fragment
  "Shared shape for the immediate, action-keyed gate-action pane fragment:
   headline `msg` over ws-id + the follow-it links. `pane-id` is the element it
   patches — \"ws-pane\", the workstream pane the action was taken from."
  [msg project ws-id pane-id]
  (str
   (h/html
    [:div {:id pane-id}
     [:h1 msg]
     [:p.meta "ws " ws-id]
     [:p "Follow it: "
      [:a {:href (str "/workstreams/" project "/" ws-id)} "open workstream →"]
      " · "
      [:a {:href "/workstreams"} "workstreams →"]]])))

(defn ^{:malli/schema [:=> [:cat :any :ProjectName :WorkstreamId :string] :any]}
  gate-action-confirm-fragment
  "Immediate, action-keyed confirmation toast for an action that actually ran.
   :promote is intentionally destination-neutral: a :ready ticket provisions the
   work session, an :incoming Slack report starts triage."
  ([action-id project ws-id pane-id]
   (gate-action-fragment
    (cond
      (work/option-action? action-id)
      ;; Honest for both outcomes: the answer is always recorded on the ledger,
      ;; and the resume happens only if a session is still parked to hear it.
      ;; Which letter it was is on the card the reader is looking at; repeating it
      ;; here would be the one copy free to be wrong.
      "Recording your answer… resuming the agent if one is still listening."

      :else
      (case action-id
        :promote "Promoting…"
        ;; Says what actually happens. It used to promise the agent was resumed
        ;; to write the verdict, and apply! is defined as accepting the verdict
        ;; WITHOUT resuming anybody — so the one sentence a reader got about this
        ;; button described a different button.
        :apply   "Applying the routing and recording your acceptance on the ledger."
        :defer   "Applying the routing with every question left open for whoever builds it."
        :dismiss "✓ Dismissed — off your radar. Nothing written to Notion."
        :restore "✓ Restored — back in the triage queue."
        :start-triage "Starting triage… spawning the agent to investigate."
        :drop    "✓ Dropped — not pursued."
        :done    "✓ Marked done."
        :reply   (str "Resuming the session if one is listening… With nobody listening, your "
                      "words are recorded on the ledger as the blocker's answer.")
        ;; Both say what is being WRITTEN, because that is the whole of what
        ;; happens: neither answer resumes anything, and the next round reads the
        ;; ledger. Naming the record is also what makes the two distinguishable
        ;; afterwards — one retracts, one grants, and they leave different marks.
        :redesign    (str "✓ Retracting the design — the verdict's broken invariants go on "
                          "the record as the evidence. Write the superseding design next.")
        :hold-design (str "✓ Held — recording a grant against the verdict, which is the "
                          "ledger saying you read it and stand by the design.")
        :assert-gate (str "Recording the gate with your evidence and opening the next "
                          "phase — its implementation is owed next.")
        "Done."))
    project ws-id pane-id)))

(defn ^{:malli/schema [:=> [:cat :string :ProjectName :WorkstreamId :string] :any]}
  gate-action-skip-fragment
  "Rendered instead of gate-action-confirm-fragment when the gate action did NOT
   actually run — e.g. server/gate-resolve!'s in-flight guard dropped a
   cross-action click. Same shape as the confirm toast, but `msg` is the
   caller-supplied honest copy (server/resolve-failure-msg's :already-in-flight
   sentence) rather than a per-action success claim about something that never
   happened."
  ([msg project ws-id pane-id]
   (gate-action-fragment msg project ws-id pane-id)))

(defn- conf-chip [{:keys [level]}]
  [:span {:class (str "chip c-conf-" (name level))} (name level)])

(defn- triage-report-card
  "Curated render of a typed triage report: determination + confidence chips, summary,
   directions, an On-apply block, and a collapsed investigation trail (§5)."
  [{:keys [title determination summary confidence design-frame notion-writes trail] :as report}]
  [:div.md
   (when title [:h2 title])
   [:div.report-meta
    [:span {:class (str "chip c-det-" (name determination))} (name determination)]
    (conf-chip confidence)
    (when-let [layer (:defect-layer design-frame)]
      [:span {:class (str "chip c-layer-" (name layer))} (name layer) " defect"])
    [:span.meta (:reason confidence)]]
   (md/render summary)
   (let [questions (report/triage-questions report)] (list
   (when design-frame
     (let [{:keys [governing violated note]} design-frame]
       [:div
        [:h3 "Design frame"]
        (when note [:p note])
        (when (seq governing)
          [:p.meta "Governed by: " (str/join ", " governing)])
        (when (seq violated)
          (into [:ul]
                (for [{:keys [rule source evidence]} violated]
                  [:li rule " " [:span.meta "(" source ")"] " — " [:code evidence]])))]))
   ;; One section per question the report asks, each its directions. The letters a choice is
   ;; recorded by stay in the record and off the screen: what is read is the label.
   (for [{:keys [question context recommendation after directions]} questions
         :when (seq directions)]
     [:div
      [:h3 (or question "Solution directions")]
      (when-not (str/blank? context) [:p context])
      (when-not (str/blank? recommendation) [:p [:span.meta "Recommended: "] recommendation])
      (when-not (str/blank? after) [:p [:span.meta "Once answered: "] after])
      (into [:div.options]
            (for [{:keys [label shape effort confidence]} directions]
              [:div.option
               [:div.option-head
                [:strong label]
                [:span.meta (report/effort-label effort)
                 (when confidence (str " · " (name (:level confidence)) " confidence"))]]
               [:p shape]]))])
   (when notion-writes
     [:div
      [:h3 "On apply →"]
      (into [:ul]
            (concat
             [[:li "Type: " (or (:type notion-writes) "unchanged")]
              ;; What the ticket's size will be, which is not simply the report's
              ;; once directions can be chosen: the largest chosen size lands
              ;; (work/accepted-report). Asked of report/answerable? rather than of
              ;; the buttons, which this block cannot see — it is the predicate
              ;; gate-actions offers Accept under.
              [:li "Effort: "
               (if (and (some (comp seq :directions) questions)
                        (every? (comp report/answerable? :directions) questions))
                 (str "the largest size among the fixes you choose, or "
                      (if (= :squirrel (:effort notion-writes))
                        "left open if you defer"
                        (str (name (:effort notion-writes)) " if you defer")))
                 (report/effort-label (:effort notion-writes)))]]
             (when-let [[from to] (:status-transition notion-writes)]
               [[:li "Status: " [:code from] " → " [:code to]]])
             [[:li "Title: " (:title notion-writes)]]))])
   (when (seq trail)
     [:details.trail
      [:summary "Investigation trail (" (count trail) ")"]
      (into [:ul]
            (for [{:keys [ref note]} trail]
              [:li [:code ref] " — " note]))])))])

(defn- intent-card
  "What the task is for. Never folded: it is the yardstick goal-served is checked
   against, and a collapsed yardstick is one nobody checks."
  [{:keys [goal done-when context]}]
  [:div.md
   [:h2 "Intent — what this is for"]
   (md/render goal)
   [:h3 "Done when"]
   (into [:ul] (for [d done-when] [:li d]))
   (when context [:details.trail [:summary "Context"] (md/render context)])])

(defn- baseline-card
  "Curated render of a baseline: the area and what bounded it, the shape, then the
   two lists that do the routing work — what cannot move, and where it can. Both
   are always shown; they are what every later judgement in the workstream is made
   against, and a collapsed yardstick is one nobody checks.

   `read` and `unknowns` collapse together into the provenance fold: they matter
   when the baseline is doubted, and are noise the rest of the time. An empty
   `unknowns` is a smell rather than a clean bill of health — a baseline that found
   nothing it could not determine usually did not look — so the fold says how many
   there were instead of hiding the count."
  [{:keys [area bounded-by shape load-bearing extension-points health governing
           drift read unknowns]}]
  [:div.md
   [:h2 "Baseline — the current design"]
   [:div.report-meta
    [:span.chip "area"]
    [:span.meta area]
    (when (seq governing)
      [:span.meta " · governed by: " (str/join ", " governing)])]
   [:p.meta "Bounded by: " bounded-by]
   [:h3 "Shape"]
   (md/render shape)
   [:h3 "Load-bearing"]
   (into [:ul]
         (for [{:keys [property evidence] lb-drift :drift} load-bearing]
           [:li property
            [:span.meta " — " (str/join ", " evidence)]
            (when lb-drift [:div.meta "drift from the stance: " lb-drift])]))
   (when (seq extension-points)
     [:div [:h3 "Extension points"]
      (into [:ul]
            (for [{:keys [at how]} extension-points]
              [:li at " — " how]))])
   (when (seq health)
     [:div [:h3 "Health"]
      (for [[axis label] [[:design "Design health"] [:implementation "Implementation health"]]
            :let [items (filter #(= axis (:axis %)) health)]
            :when (seq items)]
        [:div
         [:h4 label]
         (into [:ul]
               (for [{:keys [id observation evidence invisibly-incomplete?]} items]
                 [:li [:code id] " " observation
                  [:span.meta " — " (str/join ", " evidence)]
                  (when invisibly-incomplete?
                    [:div.meta "invisibly incomplete — deferring this leaves the branch untrue"])]))])])
   (when (seq drift)
     [:div [:h3 "Drift from the stance"] (into [:ul] (for [d drift] [:li d]))])
   [:details.trail
    [:summary "Read (" (count read) ") · not determined (" (count unknowns) ")"]
    (into [:ul] (for [r read] [:li [:code r]]))
    (when (seq unknowns)
      (into [:ul] (for [u unknowns] [:li u])))]])

(defn- design-card
  "Curated render of a typed design record: stance + effort chips, the shape and
   its invariants (always shown — they are what review checks against), then the
   optional sections. `assumes` is collapsed: it is the captured inference, useful
   when a design is questioned and noise the rest of the time."
  [{:keys [summary shape invariants standing baseline intent assumes routes
           rejected layers phases seams open supersedes effort]}]
  [:div.md
   [:h2 "Design"]
   [:div.report-meta
    [:span {:class (str "chip c-rel-" (name (:relation standing)))}
     (name (:relation standing))]
    [:span.meta "Effort: " (name effort)]
    (when-let [ps (seq (:principles standing))]
      [:span.meta " · " (str/join ", " ps)])]
   (when-let [n (:note standing)] [:blockquote n])
   (when baseline
     (let [{:keys [relation at breaks note]} baseline]
       [:div
        [:p.report-meta
         [:span {:class (str "chip c-rel-" (name relation))} (name relation)]
         [:span.meta "against baseline entry " (:seq baseline)
          (when at (str " — at: " at))]]
        (when (seq breaks)
          [:div [:p.meta "Load-bearing properties this breaks:"]
           (into [:ul] (for [b breaks] [:li b]))])
        (when note [:blockquote note])]))
   (when intent
     [:p.meta "For: entry " (:seq intent)])
   (when supersedes
     [:p.meta "Supersedes entry " (:seq supersedes) " — " (:why supersedes)])
   (md/render summary)
   [:h3 "Shape"]
   (md/render shape)
   [:h3 "Invariants"]
   (into [:ul]
         (for [i invariants]
           (let [{t :invariant h :holds} (report/invariant i)]
             [:li t (when (= :on-completion h)
                      [:span.meta " (holds on completion)"])])))
   (when (seq phases)
     [:div [:h3 "Phases"]
      (into [:ol]
            (for [{:keys [claim habitable exit undo]} phases]
              [:li claim
               [:ul
                [:li [:span.meta "live meanwhile: "] habitable]
                [:li [:span.meta "exit (" (name (:kind exit)) "): "] (:criterion exit)]
                [:li [:span.meta "undo: "]
                 (case (:how undo)
                   :revert  (str "revert — " (:by undo))
                   :forward (str "forward only — " (:by undo))
                   :none    [:strong "point of no return — " (:why undo)])]]]))])
   (when (seq layers)
     [:div [:h3 "Intended layers"]
      (into [:ol]
            (for [{:keys [claim mode]} layers]
              [:li claim " " [:span.meta "(" (name mode) ")"]]))])
   (when (seq routes)
     [:div [:h3 "Routed from the baseline's health"]
      (into [:ul]
            (for [{:keys [health-id to why ref]} routes]
              [:li [:code health-id] " → "
               [:span {:class (str "chip c-route-" (name to))} (name to)]
               (when why [:span.meta " — " why])
               (when ref [:span.meta " (" ref ")"])]))])
   (when (seq rejected)
     [:div [:h3 "Rejected"]
      (into [:ul]
            (for [{:keys [alternative why-not]} rejected]
              [:li [:strong alternative] " — " why-not]))])
   (when (seq seams)
     [:div [:h3 "Seams"]
      (into [:ul]
            (for [{:keys [what visible-how] :as seam} seams]
              [:li what " — visible as: " visible-how
               (when-let [c (report/seam-closure seam)]
                 [:span.meta "; " c])]))])
   (when (seq open)
     [:div [:h3 "Open"] (into [:ul] (for [o open] [:li o]))])
   (when (seq assumes)
     [:details.trail
      [:summary "Assumes — current design, as inferred (" (count assumes) ")"]
      (into [:ul]
            (for [{:keys [about read drift]} assumes]
              [:li about
               (when (seq read)
                 [:span.meta " — read: " (str/join ", " read)])
               (when drift [:div.meta "drift from the stance: " drift])]))])])

(defn- plan-card [{:keys [summary direction effort steps]}]
  [:div.md
   [:h2 "Implementation plan"]
   [:div.report-meta [:span.meta "Direction: " direction " · Effort: " (name effort)]]
   (md/render summary)
   (when (seq steps)
     [:div [:h3 "Steps"] (into [:ul] (for [s steps] [:li s]))])])

(defn- completed-card [{:keys [summary artifacts design-delta open]}]
  [:div.md
   [:h2 "Implementation completed"]
   (md/render summary)
   [:h3 "Artifacts"]
   (into [:ul]
         (for [{:keys [kind ref url]} artifacts]
           [:li [:strong (name kind)] " " [:code ref]
            (when url [:span " — " [:a {:href url :target "_blank"} url]])]))
   (when design-delta
     (let [{:keys [held? deviations note]} design-delta]
       [:div
        [:h3 "Design " [:span {:class (str "chip c-held-" (if held? "yes" "no"))}
                        (if held? "held" "did not hold")]]
        (when note [:p note])
        (when (seq deviations)
          (into [:ul] (for [d deviations] [:li d])))]))
   (when (seq open)
     [:div [:h3 "Still open"] (into [:ul] (for [o open] [:li o]))])])

(defn- option-card
  "One lettered branch of a blocker. The letter is the whole affordance: it is
   what the button below the report says, so a reader picks by matching a letter
   rather than by re-reading two paragraphs of prose to work out which button is
   which. Derived from position (report/option-letter) at both ends."
  [i {:keys [label consequence recommended?] :as opt}]
  [:div.option
   [:div.option-head
    [:span.option-letter (report/option-letter i)]
    [:strong label]
    (when recommended? [:span.chip.c-opt-rec "recommended"])]
   [:p (:summary opt)]
   (when consequence [:p.meta consequence])])

(defn- blocker-card [{:keys [summary needs options]}]
  [:div.md
   [:h2 "Blocker"]
   (md/render summary)
   [:h3 "Needs"]
   [:p needs]
   (when (seq options)
     [:div
      [:h3 "Options"]
      (into [:div.options] (map-indexed option-card options))])])

(defn- blocker-answered-card
  "The human's answer to a blocker, as it reads back on the timeline. `:resumed`
   is stated either way — an answer nobody was live to hear is still the decision,
   and the next session to pick this workstream up is what acts on it."
  [{:keys [blocker-seq letter label summary resumed]}]
  [:div.md
   [:h2 "Answered"]
   (if letter
     [:div.option
      [:div.option-head
       [:span.option-letter letter]
       [:strong label]]
      [:p summary]]
     ;; An answer a person gave in words, recorded from the session's chat.
     [:blockquote summary])
   [:p.meta "answers the blocker at entry " blocker-seq
    (if resumed
      (list " · resumed " [:code resumed])
      " · no session was live to resume — the next one reads it here")]])

(defn- triage-accepted-card
  "A human's acceptance of a triage verdict, as it reads back on the timeline: one line per
   question it answered, either the fix chosen or that the question was left open. An open
   question is written out rather than left blank, because leaving it open is an answer too."
  [{:keys [triage-seq] :as accepted}]
  (let [answers (report/accepted-answers accepted)]
    [:div.md
     [:h2 "Accepted"]
     (if (seq answers)
       (into [:div.options]
             (for [{:keys [question direction]} answers]
               [:div.option
                (when question [:p.meta question])
                (if direction
                  (list [:div.option-head
                         [:strong (:label direction)]
                         [:span.meta (report/effort-label (:effort direction))]]
                        [:p (:shape direction)])
                  [:p "Left open — for whoever builds it."])]))
       [:p "No direction chosen — the routing stands and the implementation "
        "direction is left open."])
     [:p.meta "accepts the triage report at entry " triage-seq]]))

(defn- pr-opened-card [{:keys [url title summary]}]
  [:div.md
   [:h2 "PR opened"]
   [:p [:strong title] " — " [:a {:href url :target "_blank"} url]]
   (when summary (md/render summary))])

(defn- merged-card [{:keys [pr commit url title merged-at]}]
  [:div.md
   [:h2 "Merged"]
   [:p [:strong title] " — " [:a {:href url :target "_blank"} url]]
   [:p [:code (or pr commit)] (when merged-at (str " · " merged-at))]])

(defn- ship-submitted-card [{:keys [session]}]
  [:div.md
   [:h2 "Ship submitted"]
   [:p [:code session] " handed to the merge lane."]])

;; --- review report ---------------------------------------------------------
;; The ledger event carries the verdict + counts; work/hydrate attaches `:detail`
;; — the review-loop's own report.json (target, rounds, phases, findings) — when
;; the run dir still holds it. The card reads top-down like the terminal frontend:
;; verdict, then one block per round, each round three phase lines deep.

(def ^:private review-status-tone
  "Terminal review status → chip tone. Only a run that produced no review at all
   is an error — whether the review broke or no reviewer could be run; the rest
   of the non-clean statuses stopped short of converging, which is a result, not a
   failure (mirrors nido.review's own reading — see tasks.nido-review/exit-code)."
  {:converged :ok :clean :ok :dry-run :ok
   :review-failed :bad :reviewer-unavailable :bad :timed-out :bad})

(defn- review-chip [tone text]
  [:span {:class (str "chip c-rv-" (name tone))} text])

(defn- review-glyph
  "Phase status → its mark. Same vocabulary as the terminal render (review/render)
   so a review reads identically in the pane and in the loop that produced it."
  [status]
  (case status "ok" "✓" ("error" "refused" "failed" "unjudged") "✗" "running" "…" "·"))

(defn- rel-path
  "`file` relative to the reviewed worktree — findings carry absolute paths, and
   the worktree prefix is noise the reader already knows. Unchanged when it sits
   outside `cwd` (or `cwd` is unknown)."
  [cwd file]
  (let [f (str file)]
    (if (and (not (str/blank? (str cwd))) (str/starts-with? f (str cwd "/")))
      (subs f (inc (count (str cwd))))
      f)))

(defn- review-phase-note
  "The one-line outcome of a phase: what each layer found for review, the
   warden's decision, where each fix landed. nil while it is still running.

   `fix-findings` and `commit` are read alongside their replacements so a
   report.json written before layers existed still renders."
  [{:keys [phase findings overall-correctness decision rulings fix-findings
           commit fixes fixed-count layers status]}]
  (case phase
    "review" (when (= "ok" status)
               (let [skipped (count (filter #(= "skipped" (:status %)) layers))]
                 (str (count findings) " finding" (when (not= 1 (count findings)) "s")
                      (when (pos? skipped) (str " · " skipped " layer"
                                                (when (not= 1 skipped) "s") " skipped"))
                      (when overall-correctness (str " · " overall-correctness)))))
    ("warden" "arbiter" "judge") (when decision
               (str "→ " decision
                    (when-let [n (seq (filter #(= "fix" (name (or (:disposition %) ""))) rulings))]
                      (str " (fix " (count n) ")"))
                    (when (seq fix-findings)
                      (str " (fix " (str/join "," fix-findings) ")"))))
    "fix"    (cond
               (seq fixes)       (str/join " · "
                                          (map #(str (or (:layer %) "branch") " "
                                                     (subs (str (:commit %)) 0
                                                           (min 8 (count (str (:commit %))))))
                                               fixes))
               commit            (str "commit " (subs commit 0 (min 8 (count commit)))
                                      (when fixed-count (str " · " fixed-count " fixed")))
               (= "ok" status)   "no changes")
    nil))

(defn- finding-title
  "A codex finding's title. Codex repeats the priority as a `[P2] ` prefix; the
   chip beside it already says that, so the prefix is dropped rather than printed
   twice."
  [title]
  (str/replace (str title) #"^\[P\d\]\s*" ""))

(defn- finding-chip [priority]
  [:span {:class (str "chip c-p" (min 3 (or priority 3)))} "P" (if priority priority "?")])

(defn- review-finding-line
  "A finding at a glance: severity + title, one line. This is all a folded round
   shows — the shape of what the review found, without its argument."
  [{:keys [title priority]}]
  [:li.rv-finding-line (finding-chip priority) " " [:strong (finding-title title)]])

(defn- review-finding
  "One codex finding in full: severity + title, then where it is and what it says."
  [cwd {:keys [title body priority file line-start line-end]}]
  [:li.rv-finding
   [:div (finding-chip priority) " " [:strong (finding-title title)]]
   (when file
     [:div.rv-loc (rel-path cwd file) ":" line-start
      (when (and line-end (not= line-end line-start)) (str "-" line-end))])
   (when-not (str/blank? body)
     [:div.rv-prose (md/render body)])])

(defn- review-phase
  "One phase of a round: its line, then what it produced — the review's findings,
   the warden's reasoning. Only ever rendered inside an unfolded round, so both
   run inline: the reader asked for exactly this."
  [cwd {:keys [phase findings reason status error] :as ph}]
  [:div.rv-phase
   [:div.rv-phase-head
    [:span.rv-glyph (review-glyph status)]
    [:span.rv-label phase]
    [:span.meta (review-phase-note ph)]]
   (when (seq findings)
     (into [:ul.rv-findings] (map #(review-finding cwd %) findings)))
   (when-not (str/blank? reason)
     [:div.rv-prose (md/render reason)])
   (when error [:div.error-msg error])])

(defn- review-round
  "One round, folded or not.

   FOLDED (the default) it is a summary: the round's status, its phases' outcomes
   on one line — findings count, the warden's decision, the fix's commit — and its
   findings as titles with their severity. That is the shape of the round; a
   converged review is mostly rounds you never need to read past this.

   UNFOLDED it is the phases in full, findings bodies and the warden's reasoning
   included. `toggle` is the @get that flips it, nil where the surface has no
   position to navigate to (the gate pane) — there the round renders unfolded,
   since a reader who cannot open it must not be shown the closed half."
  [cwd open? toggle {:keys [round status phases]}]
  (let [findings (mapcat :findings phases)]
    [:div.rv-round
     [:div (cond-> {:class (str "rv-round-head" (when toggle " rv-foldable"))}
             toggle (assoc "data-on:click" toggle))
      (when toggle [:span.rv-mark (if open? "▾" "▸")])
      [:strong "Round " round]
      (review-chip (case status "clean" :ok "failed" :bad "running" :run :neutral)
                   status)
      (when-not open?
        [:span.meta (str/join " · " (keep review-phase-note phases))])]
     (if open?
       (into [:div] (map #(review-phase cwd %) phases))
       (when (seq findings)
         (into [:ul.rv-findings] (map review-finding-line findings))))]))

(defn- review-card
  "Curated render of a `:review` ledger event: the verdict + counts the event
   itself carries, then the per-round detail (review · warden · fix, each round's
   findings under it) once `:detail` is hydrated. Degrades to the verdict alone
   when the run dir that held report.json is gone.

   Rounds start FOLDED — see review-round — and unfold through `pos`, the pane's
   reading position, so the 3s poll re-renders them exactly as the reader left
   them. A nil `pos` (the gate pane, which has no such position) renders every
   round unfolded: with nothing to click, the detail has to be on the page."
  [{:keys [status base base-rev rounds findings-fixed fix-attempts defects-settled
           findings-remaining findings-kept remaining-handed
           targets-reviewed targets-skipped report-path summary detail]} pos]
  (let [cwd    (get-in detail [:target :cwd])
        files  (get-in detail [:target :files])
        rounds* (:rounds detail)
        open   (set (:rounds pos))]
    [:div.md
     [:h2 "Review"]
     [:div.report-meta
      (review-chip (get review-status-tone status :warn) (name status))
      ;; Defects removed leads and repairs dispatched follows, because they are
      ;; different sizes and only the first has a later reviewer's silence
      ;; behind it. An entry from before the rename carries only the dispatch
      ;; count and shows that alone.
      ;;
      ;; `remaining` overlaps the dispatch count, and the parenthesis says by
      ;; how much: a repair landed in the final round is dispatched and still
      ;; owed, since nothing re-read the layer.
      ;;
      ;; `kept` is its own number rather than part of `remaining`: a declined
      ;; defect was decided, so the branch owes nothing on it and nobody should
      ;; be sent to look. It is shown at all because deciding to ship a defect
      ;; is a fact about the branch, and a card that omits it reads identically
      ;; to one where the reviewers found nothing.
      [:span.meta rounds " round" (when (not= 1 rounds) "s")
       ;; A run settled from its run dir can hold a nil dispatch count — a fix
       ;; phase that kept no account — and says `unknown` rather than falling
       ;; through to the pre-rename count it does not have.
       " · " (if findings-fixed
               (str findings-fixed " dispatched")
               (str defects-settled " settled (" (if (some? fix-attempts) fix-attempts "unknown")
                    " dispatched)"))
       " · " findings-remaining " remaining"
       (when (pos? (or remaining-handed 0))
         (str " (" remaining-handed " already repaired, unverified)"))
       (when (pos? (or findings-kept 0))
         (str " · " findings-kept " kept"))]]
     [:p.meta "base " base
      (when base-rev (str " @ " (subs base-rev 0 (min 12 (count base-rev)))))
      (when (seq files) (str " · " (count files) " file"
                             (when (not= 1 (count files)) "s") " changed"))
      ;; What the verdict is a verdict on. A skipped target carries an earlier
      ;; run's answer, so a chip reading `clean` over three of eight targets and
      ;; one over all eight are the same chip and different evidence.
      (when targets-reviewed
        (str " · " (if (pos? (or targets-skipped 0))
                     (str targets-reviewed " of "
                          (+ targets-reviewed targets-skipped)
                          " targets read this run")
                     (str "all " targets-reviewed " target"
                          (when (not= 1 targets-reviewed) "s") " read this run"))))]
     (when summary (md/render summary))
     (if (seq rounds*)
       (into [:div.rv-rounds]
             (map (fn [{:keys [round] :as r}]
                    (review-round cwd
                                  (or (nil? pos) (contains? open round))
                                  (when pos (pane-fragment (toggle-round pos round)))
                                  r))
                  rounds*))
       [:p.empty (if (str/blank? (str report-path))
                   "No round detail — this event recorded no report path."
                   "Round detail is no longer on disk (the run dir was cleaned).")])
     (when report-path [:p.meta "full report → " [:code report-path]])]))

(defn- design-verdict-card
  "A carried verdict is labelled on the meta line rather than rendered as a
   fresh one. The card's whole claim is that a judge read this code against this
   record; a carry is the same judgment standing, and letting the two look alike
   turns a held position into repeated confirmation."
  [{:keys [verdict round reason invariants-held invariants-broken invariants-unmet
           invariants-unverified load-bearing-held load-bearing-broken findings-classified
           unraised standing-answered needs carried-from]}]
  [:div.md
   [:h2 "Design verdict"]
   [:div.report-meta
    [:span {:class (str "chip c-verdict-" (name verdict))} (name verdict)]
    [:span.meta "after round " round]
    (when carried-from
      [:span.meta "· still standing from entry " carried-from])]
   (md/render reason)
   (when (seq invariants-held)
     [:div [:h3 "Confirmed this round"]
      (into [:ul] (for [i invariants-held] [:li i]))])
   (when (seq invariants-broken)
     [:div [:h3 "Contradicted"]
      (into [:ul]
            (for [{:keys [invariant finding]} invariants-broken]
              [:li invariant [:div.meta "by: " finding]]))])
   (when (seq invariants-unmet)
     [:div [:h3 "Not met yet — the design stands"]
      (into [:ul]
            (for [{:keys [invariant finding]} invariants-unmet]
              [:li invariant [:div.meta "open: " finding]]))])
   (when (seq invariants-unverified)
     [:div [:h3 "Not verified — evidence owed before merge"]
      (into [:ul]
            (for [{:keys [invariant missing]} invariants-unverified]
              [:li invariant [:div.meta "missing: " missing]]))])
   (when (seq load-bearing-broken)
     [:div [:h3 "Broken without being declared"]
      [:p.meta "Properties the area relied on that this change did not say it
                would move — the design failing to be what it said it was."]
      (into [:ul]
            (for [{:keys [invariant finding]} load-bearing-broken]
              [:li invariant [:div.meta "by: " finding]]))])
   (when (seq load-bearing-held)
     [:details.trail
      [:summary "Load-bearing properties still standing (" (count load-bearing-held) ")"]
      (into [:ul] (for [i load-bearing-held] [:li i]))])
   (when (seq findings-classified)
     [:details.trail
      [:summary "Findings by layer (" (count findings-classified) ")"]
      (into [:ul]
            (for [{:keys [finding as]} findings-classified]
              [:li [:span.meta "[" (name as) "] "] finding]))])
   (when (seq unraised)
     [:div [:h3 report/unraised-heading]
      (into [:ul] (for [{:keys [where what]} unraised] [:li [:code where] " — " what]))])
   (when (seq standing-answered)
     [:details.trail
      [:summary "Standing items answered (" (count standing-answered) ")"]
      (into [:ul] (for [{:keys [item answer]} standing-answered]
                    [:li item [:div.meta answer]]))])
   (when needs
     [:div [:h3 (report/verdict-needs-heading verdict)] [:p needs]])])

(defn- record-findings-list
  "Findings from a round over a record. What each cites leads, because that is
   what separates a finding from an opinion here."
  [findings]
  (into [:ul]
        (for [{:keys [cites claim evidence blocks needs]} findings]
          [:li [:strong (str/join "; " cites)]
           [:div claim]
           ;; A gap says which derivation it blocks and what the baseline would
           ;; have to say; without those two a reader sees a claim and no reason.
           (when blocks
             [:div "blocks " [:code (name blocks)] " — needs " needs])
           (when (seq evidence)
             [:div.meta (str/join ", " evidence)])])))

(defn- baseline-review-card
  [{:keys [verdict baseline-seq reason confirmed findings]}]
  [:div.md
   [:h2 "Baseline review"]
   [:div.report-meta
    [:span {:class (str "chip c-verdict-" (name verdict))} (name verdict)]
    [:span.meta "of entry " baseline-seq]]
   (md/render reason)
   (when (seq findings)
     [:div [:h3 (report/findings-heading verdict)]
      (record-findings-list findings)])
   (when-not (report/verdict-holds verdict)
     [:blockquote "Re-survey — the design may be sound on a bad premise."])
   (when (seq confirmed)
     [:details.trail
      [:summary "Confirmed against the code (" (count confirmed) ")"]
      (into [:ul] (for [c confirmed] [:li c]))])])

(defn- design-decision-card
  "The pre-implementation decision. What was DERIVED is never folded: it is the
   whole point of the round, and a reader who cannot see what was already ruled
   out has been handed an unreduced question after all."
  [{:keys [recommend design-seq reason checks asks findings]}]
  [:div.md
   [:h2 "Design decision"]
   [:div.report-meta
    [:span {:class (str "chip c-recommend-" (name recommend))} (name recommend)]
    [:span.meta "of entry " design-seq]]
   (md/render reason)
   [:h3 "Derived — already ruled on"]
   (into [:ul]
         (for [{:keys [check status held? note]} checks]
           [:li [:span.meta (case (or status (if held? :held :broken))
                              :held "✓ " :broken "✗ " :underivable "— ")]
            (name check)
            [:div.meta note]]))
   (when (seq findings)
     [:div [:h3 "What the derivation found"] (record-findings-list findings)])
   [:div [:h3 "For you to decide"] [:p asks]]])

(defn- degraded-card
  "A typed entry this reader could not parse. Shown as what it is — an intact
   record and an inadequate reader — with the payload in a <pre> rather than
   through md/render, which has no code block and turns every line of EDN into
   its own paragraph.

   Naming which of the two failures it is matters: an unknown kind means this
   process predates the record and the fix is to restart it on newer code, while
   a schema mismatch means the record and this reader disagree about a kind they
   both know, which is a different problem with a different owner."
  [{:keys [markdown]} {:keys [kind reason]}]
  [:div.md
   [:h2 "Not rendered by this reader"]
   [:blockquote
    (case reason
      :unknown-kind
      (list "This reader has no schema for " [:code (str kind)]
            " — it is older than the entry. The record is intact; restart the "
            "daemon on code that knows this kind.")
      (list "This entry does not match the schema this reader has for "
            [:code (str kind)] " — the two disagree about a kind they both know."))]
   [:pre markdown]])

(defn- report-body
  "Dispatch a gate/ledger report on :format — each typed event gets its curated card,
   markdown reports render through md/render. `pos` is the workstream pane's reading
   position, for the one card that folds (review — see review-card); the gate pane,
   which has no position of its own, omits it."
  ([report] (report-body report nil))
  ([report pos]
   (if-let [d (:degraded report)]
     (degraded-card report d)
     (case (:format report)
     :triage-report            (triage-report-card report)
     :intent                   (intent-card report)
     :baseline                 (baseline-card report)
     :design                   (design-card report)
     :implementation-plan      (plan-card report)
     :implementation-completed (completed-card report)
     :blocker                  (blocker-card report)
     :blocker-answered         (blocker-answered-card report)
     :triage-accepted          (triage-accepted-card report)
     :pr-opened                (pr-opened-card report)
     :merged                   (merged-card report)
     :ship-submitted           (ship-submitted-card report)
     :baseline-review          (baseline-review-card report)
     :design-decision          (design-decision-card report)
     :design-verdict           (design-verdict-card report)
     :review-report            (review-card report pos)
     :findings                 (md/render (report/report->markdown report))
     :phase-gate               (md/render (report/report->markdown report))
     :proposed-ticket          (md/render (report/report->markdown report))
     :proposed-tickets         (md/render (report/report->markdown report))
     (md/render (:markdown report))))))

;; The position and arc vocabulary the workstream pane defines below; the gate pane names a
;; position and marks a stage exactly as it does.
(defn ^{:malli/schema [:=> [:cat :map :map] :any]}
  needs-page
  "Home: the list of what waits on you, inside the shell, rendered from the screen. No pane of its
   own: each row opens its workstream's pane, where the question is answered, so the context a
   reader re-orients by is the workstream's and is not drawn a second time here. The poll query
   carries the scope so the 3s refresh keeps it."
  [ctx screen]
  (shell
   (assoc ctx :active :needs :title "Needs you")
   [:div.inbox {:data-on-interval__duration.3s (str "@get('/_fragment/needs" (screen-query screen nil) "')")}
    (h/raw (needs-fragment screen))]))

;; ---------------------------------------------------------------------------
;; Workstreams (overview + ledger) — replaces the board + ws-detail.

;; Section fold state. One boolean signal per stage — $wsFold<Suffix>, true =
;; collapsed — declared on the PERSISTENT page chrome (.gate-wrap), never inside
;; the 5s-polled #workstreams fragment (so a poll can't reset it). Datastar v1.0.1
;; has no data-persist plugin, so persistence rides plain localStorage: seed on
;; load via data-signals__ifmissing, rewrite on every toggle. The suffix must be a
;; safe JS identifier (":in-progress" → "InProgress"), so it's derived from a fixed
;; table shared by the initializer, the per-section toggles, and the persist write.
(def ^:private ws-fold-stages
  [[:shipping    "Shipping"]
   [:in-progress "InProgress"]
   [:awaiting-gate "AwaitingGate"]
   [:triage "Triage"]
   [:winding-down "WindingDown"]])

(defn- ws-fold-signal
  "Collapsed-flag signal name for a stage, e.g. :in-progress → \"wsFoldInProgress\"."
  [stage]
  (str "wsFold" (some (fn [[s suf]] (when (= s stage) suf)) ws-fold-stages)))

(def ^:private ws-fold-storage-key "nidoWsFold")

(def ^:private ws-fold-signals-init
  "data-signals__ifmissing expression for the persistent chrome: seed each stage's
   collapsed flag from localStorage on load. `===true` coerces a missing/false key
   to expanded, so every band starts open. __ifmissing makes the whole thing a no-op once the signals exist — so
   a full-page reload restores state without clobbering it."
  (str "{"
       (str/join ", "
                 (for [[_ suf] ws-fold-stages]
                   (str "wsFold" suf
                        ": (JSON.parse(localStorage.getItem('" ws-fold-storage-key
                        "')||'{}')." suf ")===true")))
       "}"))

(def ^:private ws-fold-persist-js
  "JS appended to every section toggle: after a flag flips, write the full fold
   state back to localStorage so both a reload and the 5s poll restore it."
  (str "localStorage.setItem('" ws-fold-storage-key "', JSON.stringify({"
       (str/join ", " (for [[_ suf] ws-fold-stages] (str suf ":$wsFold" suf)))
       "}))"))

(defn- board-sections
  "Flatten one {:project :grouped} into [{:project :stage :rows}], taking the
   band list + order from work/board-bands — the single place it lives. Kept as
   a plain fn (not inline hiccup) so the fragment's `for` stays readable."
  [{:keys [project grouped]}]
  (for [[stage rows] (work/board-bands grouped)]
    {:project project :stage stage :rows rows}))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  workstreams-fragment
  "The board's stage-grouped selectable list across projects, rendered from
   the screen. Selection is threaded from the screen so a poll refresh keeps
   the open row's highlight instead of clearing it."
  [{:keys [groups selection] :as screen}]
  (let [sel-id (:ws-id selection)
        wd-q   (screen-query screen)]
    (str
     (h/html
      [:div {:id "workstreams"}
       ;; The fold signals live on the persistent chrome (see workstreams-page);
       ;; here we only re-bind the click toggle, the reactive fold marker, and the
       ;; row list's data-class reveal. These re-attach to the already-declared
       ;; signals on every 5s poll, which is harmless — the state persists.
       (for [{:keys [project stage rows]} (mapcat board-sections groups)]
         (let [sig (ws-fold-signal stage)]
           [:div.ws-section
            [:h3.ws-fold-header
             {"data-on:click" (str "$" sig " = !$" sig ", " ws-fold-persist-js)}
             [:span.ws-fold-mark {:data-show (str "!$" sig)} "▾"]
             [:span.ws-fold-mark {:data-show (str "$" sig)} "▸"]
             [:span.ws-fold-label (name stage)]
             ;; Count lives in the header (outside the animated wrapper) so it stays
             ;; visible whether the section is open or collapsed.
             [:span.ws-fold-count (str "(" (count rows) ")")]]
            ;; Animated reveal. The wrapper is collapsed by DEFAULT in CSS and gets
            ;; .ws-open only when the signal says expanded — so the server-rendered
            ;; state (which can't know localStorage) paints hidden, never as a
            ;; full-height list. On reload, expanded sections slide in and collapsed
            ;; ones stay shut: no flash of unfolded content. The 5s poll re-applies
            ;; the class in the same task before paint, so it never re-animates.
            [:div.ws-section-rows {:data-class (str "{'ws-open': !$" sig "}")}
             (for [r rows]
               (case stage
                 :winding-down (row/winddown-row
                                (assoc r :project project)
                                (str "/workstreams/" project "/" (:ws-id r) "/winddown" wd-q))
                 (row/board-row project r
                                {:href (str "/workstreams"
                                            (screen-query screen {:sel (str project ":" (:ws-id r))}))
                                 :selected? (= sel-id (:ws-id r))})))]]))]))))

(defn- session-dev-cell
  "DEV ENVIRONMENT controls (start/stop/restart/Open-app) for the workstream's
   environment block, driven by the session's derived dev-resource state. Actions
   POST to the per-session lifecycle route; the session name is URL-encoded so a
   slash-namespaced name (feat/foo) stays a single path segment."
  [project ws-id session {:keys [state url error-msg]}]
  (let [enc (java.net.URLEncoder/encode (str session) "UTF-8")
        act (fn [a] (str "@post('/workstreams/" project "/" ws-id
                         "/sessions/" enc "/dev/" a "')"))]
    (case state
      :running
      [:div.actions
       [:a.btn.btn-primary {:href url :target "_blank"} "Open app ↗"]
       [:button.btn {"data-on:click" (act "stop")} "stop"]
       [:button.btn {"data-on:click" (act "restart")} "restart"]]
      (:starting :stopping :restarting) [:span.meta "working…"]
      :failed
      [:div
       [:button.btn.btn-primary {"data-on:click" (act "start")} "retry"]
       (when error-msg [:div.error-msg error-msg])]
      ;; :down or nil
      [:button.btn.btn-primary {"data-on:click" (act "start")} "start"])))

(defn- day [at] (when at (let [s (str at)] (subs s 0 (min 10 (count s))))))

(defn- hold-card
  "One standing record: what it is, which entry it is, how many revisions it took,
   and a line of what it says. `foot` is the record-specific verdict line."
  [k {:keys [seq revisions]} body foot]
  [:div.hold
   [:div.hh [:span.hk k]
    [:span.hs (str "entry " seq
                   (when (and revisions (> revisions 1))
                     (str " · " revisions " revisions")))]]
   [:div.hb body]
   (when foot [:div.hf foot])])

(defn- holds-block
  "The intent, the baseline and the design that CURRENTLY hold.

   Three cards, never three lists. A baseline that took seven rounds appended seven
   superseding records and the reader wants the seventh; the other six are in the
   log below, marked superseded, exactly where they were. An absent record renders
   as an empty card rather than vanishing, so the shape of the arc is legible from
   what is missing as much as from what is there.

   Stacked one per row and shown whole. Three columns fitted them on screen at
   the cost of the thing they are for: an area or a summary runs to several
   sentences, and a narrow column clamped at four lines showed a fragment of
   one — which reads as a card that has nothing to say rather than one that
   was cut off. The text itself is collapsed rather than preserved — a record's
   line breaks come from the EDN a human typed, not from the prose."
  [{:keys [intent baseline design]}]
  [:div.holds
   (if intent
     (hold-card "intent" intent
                (or (get-in intent [:record :goal])
                    (get-in intent [:record :title])
                    "—")
                (when (= :triage (:kind intent))
                  [:span.meta "from triage — the design may cite it"]))
     [:div.hold.empty [:div.hh [:span.hk "intent"]] [:div.hb "not stated"]])
   (if baseline
     (hold-card "baseline" baseline
                (or (get-in baseline [:record :area]) "—")
                (if (:verified? baseline)
                  [:span.ok "✓ verified — a decision can be made against it"]
                  [:span.meta "not verified yet — a design citing it cannot be decided until it is"]))
     [:div.hold.empty [:div.hh [:span.hk "baseline"]] [:div.hb "no baseline yet"]])
   (if design
     (hold-card "design" design
                (or (get-in design [:record :summary]) "—")
                (cond
                  (:blocked design)
                  [:span.no (or (:detail (:blocked design)) "does not stand")]
                  (:cleared? design)
                  [:span.ok (str (if (:decided? design) "✓ approved" "✓ cleared — no approval owed")
                                 (when-let [p (:premise design)]
                                   (str " · on the baseline at entry " p)))]
                  :else
                  [:span.no "not yet cleared to build"]))
     [:div.hold.empty [:div.hh [:span.hk "design"]] [:div.hb "nothing committed to"]])])

(defn- ledger-index
  "The workstream's ledger: every entry, newest first. A row @gets the pane with
   ?entry=<seq>, which OPENS that entry in the viewer below — the ledger itself
   stays put, whole, whether or not something is open. It renders for a
   single-entry ledger too: nothing opens by default now, so the index is the only
   way to reach the one entry there is.

   A row a later entry amended is struck through and badged with the seq that
   replaced it. It stays clickable and stays in place: a superseded record is
   history the ledger keeps on purpose (/design §5), so this marks it as read-back
   rather than hiding it. The relation is computed in work/mark-superseded — no
   row can see it alone."
  [pos entries]
  (into [:div.ledger-index]
        (for [{:keys [seq kind at title superseded-by]} entries]
          [:a {:class (str "ledger-row"
                           (when (= seq (:entry pos)) " sel")
                           (when superseded-by " superseded"))
               "data-on:click" (pane-fragment (at-entry pos seq))}
           [:span.lk (clojure.core/name kind)]
           [:span.meta (day at)]
           [:span.lt title]
           (when superseded-by
             [:span.lx (str "superseded by " superseded-by)])])))

(defn- report-viewer
  "The reader for ONE ledger entry — opened from the index, and closable again.
   Separate from the ledger by design: reading an entry is optional, so the viewer
   is a panel that appears over the pane's resting state rather than a permanent
   half of it. Its ✕ @gets the pane with no ?entry, i.e. back to rest.

   `entry` is the index row the viewer is showing, for the title bar; nil when the
   report is the intake fallback (no ledger to have a row in), which is not
   closable — there is nothing to close back to."
  [pos entry report]
  [:div.viewer
   (when entry
     [:div.viewer-bar
      [:span.lk (clojure.core/name (:kind entry))]
      [:span.meta (day (:at entry))]
      [:span.lt (:title entry)]
      [:button.viewer-close {"data-on:click" (pane-fragment (at-entry pos nil))
                             :title "close"} "✕"]])
   (report-body report pos)])

(defn- arc-entry-rows
  "The entries of one stage, newest first, as rows that open into the viewer. The
   same affordance the ledger index offers, over a stage's slice of it — which is
   the whole of what `history at stage granularity` means here."
  [pos entries seqs]
  (let [want (set seqs)]
    (into [:div.ledger-index]
          (for [{:keys [seq kind at title superseded-by]} entries
                :when (want seq)]
            [:a {:class (str "ledger-row" (when (= seq (:entry pos)) " sel")
                             (when superseded-by " superseded"))
                 "data-on:click" (pane-fragment (at-entry pos seq))}
             [:span.lk (clojure.core/name kind)]
             [:span.meta (day at)]
             [:span.lt title]
             (when superseded-by [:span.lx (str "superseded by " superseded-by)])]))))

(defn- arc-block
  "The arc: every stage in order, the expanded one showing its records, and the
   open record shown under the stage it belongs to.

   This is what the pane leads with. The Status heading above says where the
   workstream IS; this says how it got there — which stages produced something,
   which were skipped, and which it has been sent back to. The two are the same
   ledger read two ways and cannot disagree, because both fold the one
   correspondence nido.coordinator.lane.pipeline keeps.

   `report-here?` says whether the open entry belongs to the expanded stage. When
   it does the viewer renders inside the expansion, next to the row that opened
   it; when it does not, the entry was reached from the raw index and the viewer
   renders down there instead. One viewer either way — reading an entry twice on
   one page is worse than reaching it from two places."
  [pos {:keys [stages excursions]} entries report report-here? doing-label]
  [:div
   (into [:div.arc]
         (mapcat (fn [{:keys [stage seqs] :as facet}]
                   (cond-> [(row/arc-row facet doing-label
                                             {:open?    (= stage (:stage pos))
                                              :on-click (pane-fragment (at-stage pos stage))})]
                     (= stage (:stage pos))
                     (conj [:div.arc-sub
                            (arc-entry-rows pos entries seqs)
                            (when report-here?
                              (report-viewer pos (first (filter #(= (:entry pos) (:seq %)) entries))
                                             report))])))
                 stages))
   (when (seq excursions)
     [:p.meta "off the arc · "
      (str/join " · " (map (fn [{:keys [stage entries]}]
                             (str (name stage) " ×" entries))
                           excursions))])])

(defn- pane-route
  "POST target for the pane-scoped resolve route — responses patch #ws-pane so the
   action stays inside the overview/detail pane rather than the home gate inbox."
  [project ws-id action-id]
  (str "/workstreams/" project "/" ws-id "/gate/" (name action-id)))

(defn- plan-block
  "A phased workstream's plan, phase by phase: which landed, which is underway, which
   are ahead, the gate each one's successor waits on, and the evidence a person gave
   for each gate already passed. Leads with where the plan stands, because the arc
   below draws only the phase underway — earlier phases are read here, not there.

   The gate owed NOW is marked where it sits: under the landed phase whose exit it is."
  [{:keys [current of waiting? phases]}]
  [:div.plan
   [:h3 (str "Plan — phase " current " of " of
             (if waiting? " · landed, waiting on its gate" ""))]
   (into [:ol]
         (for [{:keys [n claim exit undo state opened-by]} phases]
           [:li {:class (name state)}
            [:div.ph [:span.mk (case state :landed "✓" :current "●" "·")]
             [:span.cl (str n ". " claim)]]
            (when opened-by
              [:div.pm (str "opened " (some-> (:at opened-by) (subs 0 10)) " — " (:evidence opened-by))])
            (when (and exit (< n of))
              [:div {:class (str "pm" (when (and waiting? (= n current)) " owed"))}
               (cond (and waiting? (= n current)) "gate owed now: "
                     (< n current)                  "gate passed: "
                     :else                          "gate to the next: ")
               (:criterion exit) " (" (name (:kind exit)) ")"])
            (when (= :none (:how undo))
              [:div.pm.pnr (str "point of no return — " (:why undo))])]))])

(defn- pane-action-bar
  "Stage-appropriate gate actions rendered below the reader pane, driven by
   `work/gate-actions` (different stages → different actions; a parked triage adds
   Apply/Reply). Takes `origin` for call-site compatibility; it no longer changes
   the action set. Buttons POST to the pane-scoped route. Shown only for the CURRENT
   ledger entry — callers gate on :on-latest?. Renders nothing when the stage offers
   no actions.

   Only ever called for a real (non-bare) workstream — bare-pane-body computes its
   own action set via work/gate-actions directly and calls action-bar itself, so
   its copy and its buttons read off the same value and can never disagree.

   `report` is the workstream's CURRENT report (ws :action-report), NOT the entry
   the viewer has open — the resting pane has nothing open and still acts on the
   workstream as it stands. It reaches gate-actions because a parked :in-progress
   gate showing a design decision offers a different question from one showing a
   blocker that named its branches, and the pane and the gate inbox must not
   disagree about which. Callers still gate on :on-latest?, so an older entry
   being read renders no action bar at all.

   `position` is the workstream's pipeline position — the pane already has it for
   its heading — and is here because one of the buttons is owed by a PERSON
   rather than by a parked agent (work/awaiting-human)."
  [project ws-id origin stage sessions report position plan replyable?]
  (let [parked? (or (boolean (some :parked? sessions)) (boolean replyable?))
        session (:name (first (filter :parked? sessions)))]
    (ask/action-bar project ws-id
                ;; Rendered only for a workstream that is no gate, which owes no Ask: an action that
                ;; answers a verdict's questions, or Defer, which accepts with every one left open,
                ;; would accept questions nobody was shown, so neither is offered here — a gate's
                ;; are the question card's.
                (into []
                 (remove (fn [{:keys [answers id]}] (or (= :questions answers) (= :defer id))))
                 (work/gate-actions stage parked? origin
                                   {:report-format (:format report)
                                    :grantable?    (work/grantable? project ws-id)
                                    :options       (:options report)
                                    :questions     (report/triage-questions report)
                                    ;; The pane draws the same button the inbox
                                    ;; does, off the same reading of the same
                                    ;; position — a design decision is owed a
                                    ;; person whether or not an agent is parked.
                                    :awaiting      (work/awaiting-human position)
                                    :gate-criterion (get-in plan [:owed-gate :criterion])
                                    :seq           (:seq report)}))
                session pane-route)))

(defn- file-findings-form
  "Findings-filing form shown on a shipped (:done) workstream's pane. One finding
   per line `severity | area | summary`. @post → /workstreams/:project/:ws-id/findings,
   which files the round (reopening the workstream) and patches #ws-pane. The
   `findings`/`staging` signals auto-serialize into the JSON body (Datastar default)."
  [project ws-id]
  [:div.card
   [:strong "File staging findings"]
   [:p.meta "One per line:  severity | area | summary   (severity: blocker · tweak · nice-to-have)"]
   [:textarea {"data-bind" "findings" :rows "5" :style "width:100%;box-sizing:border-box;"
               :placeholder "blocker | Login | Save button 500s"}]
   [:input {"data-bind" "staging" :style "width:100%;box-sizing:border-box;margin-top:6px;"
            :placeholder "staging ref (optional)"}]
   [:button {:style "margin-top:8px;"
             "data-on:click" (str "@post('/workstreams/" project "/" ws-id "/findings')")}
    "File findings & reopen"]])

(defn- bare-pane-body
  "The pane for a bare watched-view row: a page in a watched Notion view that no
   nido workstream covers, so there is no ledger, report, session or environment
   to render. A bare row's :stage can be :triage, :ready, :in-progress, :done —
   session/notion-stage's range — OR :dismissed, the nido-side veto to-spine
   folds in ahead of that range (work/bare-pane). Not just :triage, the only
   stage the board ever offers Start-triage/Dismiss for, and not just :dismissed,
   the only stage that offers Restore. work/gate-actions is computed ONCE here
   and threaded to both the copy and the action bar, so the two can never
   disagree: a stage with no workstream-less action (e.g. bare :ready, whose
   normal Promote/Drop would only ever no-op — see workstream-less-actions) gets
   an explicit 'nothing to do' line instead of a card that promises an action no
   button beneath it can perform, while a stage with a live action set outside
   Start-triage/Dismiss (:dismissed's [:restore]) gets neither canned line —
   just the action bar itself.

   Keeps the 3s poll for two reasons: it is what carries :error-msg back from a
   failed action, and it is what upgrades this pane to the full one once Start
   triage's workstream exists (work/workstream resolves the page to it)."
  [{:keys [project ws-id origin label tag stage br-id notion-status error-msg]}]
  (let [actions       (work/gate-actions stage false origin {:bare? true})
        start-triage? (some #(= :start-triage (:id %)) actions)]
    [:div {:id "ws-pane"
           :data-on-interval__duration.3s
           (str "@get('/_fragment/workstream/" project "/" ws-id "')")}
     [:h1 (state/origin-badge origin) " " label]
     (when-let [t (or tag br-id)] [:p.pane-tag t])
     [:p.meta (str/join " · " (keep identity [(name stage)
                                              (when notion-status
                                                (str "notion: " notion-status))]))]
     [:div.card
      [:strong "No nido workstream yet"]
      (if (= :triage stage)
        [:p "This ticket is in the watched Notion view but nido has never triaged it."]
        [:p (str "Notion has this ticket at " (or notion-status "an unrecorded status")
                 " — nido never picked it up.")])
      ;; Gated separately: start-triage? describes the Start-triage/Dismiss buttons
      ;; specifically, while "nothing to do" must describe the action set as a
      ;; whole — else a non-empty, non-start-triage set (e.g. :dismissed's
      ;; [:restore]) reads as "nothing to do" while a live button sits right below.
      (cond
        start-triage?    [:p.meta "Start triage spawns the triage agent now. Dismiss takes it off the "
                           "board without writing anything to Notion."]
        (empty? actions) [:p.meta "Nothing to do from here — the ticket is tracked in Notion, not nido."]
        :else            nil)]
     (when error-msg [:div.action-err "⚠ " error-msg])
     (ask/action-bar project ws-id actions nil pane-route)]))

(defn ^{:malli/schema [:=> [:cat :map [:? :any]] :any]}
  workstream-pane
  "Read-only ledger pane: header · stage · ledger summary · report · the one
   ENVIRONMENT block (the workstream's `:environment` session — dev-env controls,
   URL, ports, mem/heap facts), or 'no runnable version yet'. A `:bare?` ws (a
   watched Notion page with no workstream) renders bare-pane-body instead; a
   label-less ws renders the empty placeholder. `session-dev-states`
   is a map of session-name → {:state … :url :error-msg} (the view does no IO).
   `machine-facts` is a map of session-name → {:pg-port :nrepl-port :app-port
   :repl-rss :pg-rss :heap-max} (also no IO — a projection the caller injects).
   A ws carrying `:gate` (it waits on the person) renders its question card after the
   status section and before History, and the card is then the pane's only control; `:pending?` marks an action
   on it still resolving. `:error-msg` (a gate action that came back failed) renders
   at the top of the card, or above the action bar when there is no gate — the
   buttons stay clickable to retry either way.
   Polls its own fragment AT THE READER'S POSITION (`:selected-seq` + `:open-rounds`
   — see pane-fragment), so transient dev-env states (starting…) self-advance
   without the refresh closing whatever the reader has open."
  ([ws session-dev-states] (workstream-pane ws session-dev-states {}))
  ([{:keys [project ws-id origin stage label tag links ledger report action-report entries selected-seq open-rounds open-stage history? sessions environment on-latest? error-msg bare? br-id notion-status position holds arc doing plan status replyable? denials gate pending? pause]
     :or {on-latest? true}} session-dev-states machine-facts]
   (let [pos  {:project project :ws-id ws-id :entry selected-seq :rounds open-rounds
               :stage open-stage :history? history?}
         ;; Does the open entry belong to the expanded stage? Decides which of the
         ;; two places that can reach an entry renders the viewer, so that opening
         ;; one never shows it twice and never shows it somewhere the reader is
         ;; not looking.
         in-stage? (boolean (and report selected-seq open-stage
                                 (some #(and (= open-stage (:stage %))
                                             (some #{selected-seq} (:seqs %)))
                                       (:stages arc))))]
     (str
      (h/html
       (if-not label
         [:div {:id "ws-pane"} [:p.empty "Select a workstream."]]
         (if bare?
           (bare-pane-body {:project project :ws-id ws-id :origin origin :label label :tag tag
                            :stage stage :br-id br-id :notion-status notion-status
                            :error-msg error-msg})
           [:div {:id "ws-pane" :data-on-interval__duration.3s (pane-fragment pos)}
            (pane-heading origin label links tag)
            (links-row links)

            ;; Two sections, and the split is the pane's whole claim: what is
            ;; TRUE of this workstream, and what HAPPENED to it. They were one
            ;; stream, so the standing records and the log of every superseded
            ;; revision of them read as the same kind of thing — which is exactly
            ;; the confusion the standing records were added to end.
            [:h2 (state/status-heading position) " " (state/state-mark status doing)]
            (when-not (= :done stage) (pause-line project ws-id pause))
            ;; The arc leads. The heading above states where the workstream is;
            ;; this states how it got there, and it is the one thing on the page
            ;; that can show the work having been sent back to an earlier stage.
            (when plan (plan-block plan))
            (when (seq (:stages arc))
              (arc-block pos arc entries report in-stage? (state/round-label doing)))
            (when (seq holds) (holds-block holds))
            (when (seq denials)
              [:div.denials [:p.meta "Permission denials while nido drives it:"]
               (into [:ul] (map denial-line) denials)])
            ;; What waits on the person comes after the context that orients them,
            ;; and above the log. The card owns the gate's error line and every
            ;; control, so the pane offers neither of its own beside it.
            (when gate
              (ask/question-card project ws-id (:ask gate) (:actions gate)
                             {:route pane-route :pending? (boolean (or (:working? gate) pending?))
                              :error-msg error-msg :session (:session gate)}))
            ;; The actions belong HERE and not under the log, because they act on
            ;; the workstream as it stands — pane-action-bar reads :action-report,
            ;; the CURRENT report, never the entry a reader happens to have open.
            ;; Left below the ledger they read as actions on the thing being read,
            ;; which is the one thing they are not.
            (when (and on-latest? error-msg (not gate))
              [:div.action-err "⚠ " error-msg])
            (when (and on-latest? (not gate)) (pane-action-bar project ws-id origin stage sessions action-report position plan replyable?))
            (when (= :done stage) (file-findings-form project ws-id))

            ;; Collapsed by default. The arc above is the reading a driver
            ;; wants — the raw log is one row per append, which is the ledger's
            ;; own order rather than the work's, and it is what you open when the
            ;; arc is not enough rather than what you scroll past to reach it.
            [:h2 "History"]
            [:span.hist-head {"data-on:click" (pane-fragment (toggle-history pos))}
             (if history? "▾" "▸")
             [:span.hc (str (count entries) " ledger entr"
                            (if (= 1 (count entries)) "y" "ies"))]
             (when ledger [:span.hc (str "· " (:report-count ledger) " report(s)")])]
            (when history?
              [:div
               (when ledger
                 [:div.card [:strong "ledger "] (:key ledger) " · " (some-> ledger :status name)])
               (when (seq entries) (ledger-index pos entries))])
            ;; Reached from the raw index rather than from a stage — the arc
            ;; showed it already when it could.
            (when (and report (not in-stage?))
              (report-viewer pos (first (filter #(= (:entry pos) (:seq %)) entries)) report))

            [:h2 "Environment"]
            (if-let [env-name (:name environment)]
              (let [dev (get session-dev-states env-name)
                    {:keys [pg-port nrepl-port app-port repl-rss pg-rss heap-max]}
                    (get machine-facts env-name)]
                [:div.card.env
                 [:div.env-head [:strong env-name] " " (session-dev-cell project ws-id env-name dev)]
                 (when-let [url (:url dev)]
                   [:div [:a {:href url :target "_blank"} url]])
                 [:div.mono (str/join " · " (keep (fn [[l p]] (when p (str l " " p)))
                                                  [["pg" pg-port] ["repl" nrepl-port] ["app" app-port]]))]
                 [:div.meta (list (when repl-rss (str "jvm " (process/human-bytes repl-rss) " "))
                                  (when pg-rss (str "pg " (process/human-bytes pg-rss) " "))
                                  (when heap-max (str "max " heap-max)))]])
              [:p.empty "no runnable version yet"])])))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  pickup-bar
  "Paste-a-ticket bar at the top of /workstreams. Binds a `pickup` signal, POSTs
   it to /workstreams/pickup/<project> (Enter or the button), and reserves an
   empty #pickup-result the SSE response patches. Lives on the page chrome, NOT
   inside workstreams-fragment, so the 5s poll never clobbers the result."
  [project]
  (let [post (str "@post('/workstreams/pickup/" project "')")]
    [:div.pickup
     [:div.pickup-label "Drive a ticket"]
     [:div.pickup-row
      [:input {"data-bind" "pickup"
               "data-on:keydown" (str "evt.key === 'Enter' && (" post ")")
               :placeholder "paste Notion URL / page id / BR-#…"}]
      [:button.btn {"data-on:click" post} "Drive →"]]
     [:div {:id "pickup-result" :class "pickup-result"}]]))

(defn ^{:malli/schema [:=> [:cat :map :map] :string]}
  pickup-result-fragment
  "HTML string (root #pickup-result) reporting the outcome of a pickup POST.
   `result` is pickup!'s return; opts is {:project <str>}."
  [result {:keys [project]}]
  (str
   (h/html
    [:div {:id "pickup-result" :class "pickup-result" :style "margin-top:8px;"}
     (if (= :unresolved (:decision result))
       [:p.meta
        (case (:error result)
          :no-token           "No Notion token in keychain."
          (:not-found
           :not-a-ticket)     (h/raw "Couldn't find that ticket.")
          :unrecognized-input "Paste a Notion URL, page id, or BR-####."
          :awaiting-gate      "Its technical analysis is waiting in the gate — accept it there to start."
          :ticket-unreadable  "Couldn't read the ticket to put it on the ledger — try again."
          :name-held          "Its session name belongs to another workstream."
          :closed             "Its workstream is closed."
          "Notion lookup failed — try again.")]
       (let [{:keys [continuing? ws-id ref]} result
             {:keys [id title]} ref]
         [:div
          (if continuing?
            [:p "✓ Continuing " [:strong id] " \"" title "\" → "
             [:a {:href (str "/workstreams/" project "/" ws-id)} "workstream ↗"]
             " (session spinning up…)"]
            [:p "✓ Started " [:strong id] " \"" title "\" → "
             [:a {:href (str "/workstreams/" project "/" ws-id)} "workstream ↗"]
             " (session spinning up…)"])]))])))

(defn ^{:malli/schema [:=> [:cat :map :map] :any]}
  workstreams-page
  "Overview + ledger pane, rendered from the screen. The list, its poll query,
   and the pane all derive from the one screen value, so overview and detail
   never disagree and a poll preserves the selection."
  [ctx {:keys [selection] :as screen}]
  (let [sel-id  (:ws-id selection)
        q       (screen-query screen (when sel-id {:sel (str (:project selection) ":" sel-id)}))
        project (if (= "all" (:scope screen)) "brian" (:scope screen))]
    (shell
     (assoc ctx :active :workstreams :title "Workstreams")
     ;; Section-fold signals are declared here, on the stable page chrome that the
     ;; 5s poll never replaces (the poll only patches #workstreams inside .inbox).
     ;; __ifmissing seeds them from localStorage on load without clobbering live
     ;; state; a full-page reload re-runs this and restores the persisted fold.
     [:div.gate-wrap {:data-signals__ifmissing ws-fold-signals-init}
      [:div.queue-col
       (pickup-bar project)
       [:div.inbox {:data-on-interval__duration.5s (str "@get('/_fragment/workstreams" q "')")}
        (h/raw (workstreams-fragment screen))]]
      [:div.pane (h/raw (workstream-pane (:ws selection) (:dev-states selection) (:machine selection)))]])))

;; ---------------------------------------------------------------------------
;; Operations — session recovery

(defn- ago [iso]
  (if (str/blank? (str iso)) "" (runs-view/format-age iso)))

(defn- until [iso]
  (try
    (let [secs (.getSeconds (java.time.Duration/between (java.time.Instant/now)
                                                        (java.time.Instant/parse iso)))]
      (cond
        (<= secs 0)     "now"
        (< secs 3600)   (str "in " (max 1 (quot secs 60)) "m")
        (< secs 86400)  (str "in " (quot secs 3600) "h")
        :else           (str "in " (quot secs 86400) "d")))
    (catch Exception _ "?")))

(def ^:private recovery-state-label
  {:needs-you  "needs you"
   :recovering "recovering"
   :waiting    "waiting"
   :owed       "owed"
   :restored   "restored"
   :dismissed  "dismissed"})

(defn- recovery-state-chip [state]
  [:span {:class (str "prop-verdict rec-" (name state))} (recovery-state-label state state)])

(defn- recovery-did
  "What the recovery did about a cause, in the words its records use."
  [{:keys [landed prs restores]}]
  (let [parts (concat
               (for [{:keys [commit url title]} landed]
                 [:span "landed "
                  [:a {:href url :target "_blank"} (subs (str commit) 0 (min 8 (count (str commit))))]
                  (when title (str " " title))])
               (for [{:keys [url title]} prs]
                 [:span "PR " [:a {:href url :target "_blank"} (or title url)]])
               (when (seq restores)
                 [[:span (str/join " · " (for [[k n] (sort-by key restores)]
                                           (str n " " (name k))))]]))]
    (when (seq parts)
      (into [:p.rec-did] (interpose " · " parts)))))

(defn- recovery-row
  [{:keys [state cause ws-id gate-ws sessions failures message first-at last-at runs
           due-at failed-in-a-row diagnosis closed] :as r}]
  [:div {:class (str "prop rec" (when (#{:restored :dismissed} state) " settled"))}
   [:div.prop-head
    (recovery-state-chip state)
    [:span.prop-where (str/join ", " sessions)]
    [:span.meta (str (count failures) " failed start" (when (not= 1 (count failures)) "s")
                     (when last-at (str " · last " (ago last-at))))]]
   (when message [:p.prop-ev message])
   (when diagnosis
     [:p.prop-fix [:b (name (:verdict diagnosis))] " — " (:cause diagnosis)
      (when-let [remedy (:remedy diagnosis)] [:span.meta (str " · " remedy)])])
   (recovery-did r)
   [:div.prop-meta
    [:span.mono (str "cause " cause)]
    (when (= :waiting state)
      [:span (str failed-in-a-row " failed recover" (if (= 1 failed-in-a-row) "y" "ies")
                  " · next " (until due-at))])
    (when (pos? (or runs 0)) [:span (str runs " recovery run" (when (not= 1 runs) "s"))])
    (when closed [:span (str "closed " (name (:outcome closed)) " " (ago (:at closed)))])
    (when first-at [:span (str "first " (ago first-at))])
    (when gate-ws [:a {:href (str "/workstreams/nido/" gate-ws)} "open the gate"])
    (when ws-id [:a {:href (str "/workstreams?sel=nido:" ws-id)} ws-id])]])

(defn- feed-href
  "The recovery page at feed position `pos`; the newest page when nil."
  [pos]
  (if pos
    (str "/operations/recovery?feed=" (java.net.URLEncoder/encode (str pos) "UTF-8"))
    "/operations/recovery"))

(defn- activity-line
  [{:keys [at kind session message verdict text outcomes state reason outcome commit url count]}]
  [:div.rec-evt
   [:span.rec-evt-at (ago at)]
   [:span {:class (str "rec-evt-kind rec-k-" (name kind))} (str/replace (name kind) "-" " ")]
   [:span.rec-evt-what
    (case kind
      :failure-kept   (str session " — " message)
      :recovery-fired (str "for " count " failure" (when (not= 1 count) "s"))
      :recovery-ended (str (name state) (when reason (str " · " (name reason))))
      :diagnosed      (str (name verdict) " — " text)
      :restore        (str/join " · " (for [[k n] (sort-by key outcomes)] (str n " " (name k))))
      :parked         text
      :landed         (list [:a {:href url :target "_blank"} (subs (str commit) 0 (min 8 (clojure.core/count (str commit))))]
                            (str " " text))
      :pr-opened      [:a {:href url :target "_blank"} text]
      :closed         (name outcome)
      "")]])

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :string]}
  recovery-fragment
  "Session recovery, as the Operations page shows it: what is being recovered,
   what needs a person, what is waiting, what came back, and the trail of what
   happened. Patched under #recovery by the page's poll.

   Settled causes and the feed fold away; a cause that needs someone or is in
   flight does not. `overview` nil means it could not be read — said, rather
   than rendered as a section with nothing in it."
  [overview]
  (let [{:keys [counts causes feed]} overview
        activity (:events feed)
        {live false settled true} (group-by #(boolean (#{:restored :dismissed} (:state %))) causes)]
    (str
     (h/html
      [:div {:id "recovery"}
       [:h3.rec-title "Session recovery"]
       (if (nil? overview)
         [:p.ops-empty "Session recovery could not be read — see the coordinator log."]
         (list
          [:div.ops-head
           [:strong (str (:needs-you counts) " need you")]
           [:span.prop-verdict.rec-recovering (str (:recovering counts) " recovering")]
           [:span.prop-verdict.rec-waiting (str (:waiting counts) " waiting")]
           [:span.meta (str (:owed counts) " failed start" (when (not= 1 (:owed counts)) "s")
                            " not yet settled · " (:restored counts) " restored in the last "
                            recoveries-view/window-days " days")]]
          (if (and (empty? causes) (empty? activity) (nil? (:from feed)))
            [:p.ops-empty "No session has failed to start."]
            (list
             (for [r live] (recovery-row r))
             (when (seq settled)
               [:details.trail
                [:summary (str (clojure.core/count settled) " settled in the last "
                               recoveries-view/window-days " days")]
                (for [r settled] (recovery-row r))])
             [:details.trail {:open (boolean (or (seq live) (:from feed)))}
              [:summary (str "Activity" (when (:from feed) " · older"))]
              [:div.rec-feed (for [e activity] (activity-line e))]
              [:div.prop-meta {:style "margin-top:8px"}
               (when (:from feed) [:a {:href (feed-href nil)} "newest"])
               (when (:next feed) [:a {:href (feed-href (:next feed))} "older →"])]]))))]))))

(defn- hold-row
  [{:keys [project ws-id title state started-at ended-at]}]
  (let [ws-link [:a {:href (str "/workstreams?sel=" project ":" ws-id)} ws-id]]
    (case state
      :stuck
      [:div.prop
       [:div.prop-head
        [:span.prop-verdict.sweep-stuck "sweep stuck"]
        [:span.prop-where title]]
       [:p.prop-sum "No session is working on " ws-link
        (if ended-at
          (str " — its last session ended " (ago ended-at) ".")
          " — no session was ever started for it.")
        " It holds the improvement sweep, so nothing is planned or implemented until it is closed."]
       [:p.prop-fix "Close it to release the sweep: "
        [:code (str "bb nido:workstream:close :project " project " :ws-id " ws-id " :outcome dropped")]
        " — or " [:code ":outcome vetoed"] " to return the proposals it covered to the backlog."]]
      :waiting-on-you
      [:div.prop
       [:div.prop-head
        [:span.prop-verdict.prop-waiting "sweep waiting on you"]
        [:span.prop-where title]]
       [:p.prop-sum ws-link " is parked at a gate, and the sweep waits until it is answered. "
        [:a {:href (str "/workstreams/" project "/" ws-id)} "open the gate"]]]
      :working
      [:p.meta "Sweep working on " ws-link " — " title
       (when started-at (str " · started " (ago started-at)))])))

(defn ^{:malli/schema [:=> [:cat [:maybe [:vector :map]]] :string]}
  sweep-fragment
  "What holds the improvement sweep, patched under #sweep by the page's poll.
   Sits under the backlog heading because the backlog is what a hold stops.

   Nothing is rendered when nothing holds it: a free sweep needs no one, and the
   backlog's own counts say what it has to do. `holds` nil means the holds could
   not be read — said, because an empty section would read as a free sweep."
  [holds]
  (str
   (h/html
    [:div {:id "sweep"}
     (if (nil? holds)
       [:p.ops-empty "What holds the improvement sweep could not be read — see the dashboard log."]
       (map hold-row holds))])))

;; ---------------------------------------------------------------------------
;; Operations — nido's own improvement backlog

(defn- prop-decision-chip [{:keys [verdict decided-by]}]
  [:span {:class (str "prop-verdict v-" (name verdict))}
   (name verdict) (when decided-by (str " · " decided-by))])

(defn- prop-outcome-chip
  "What became of the decision, beside what the decision was.

   Three states, and the middle one is the whole reason this exists. A declined
   proposal is finished. A landed one is finished. An APPROVED one is a promise
   the board is holding, and nothing in nido carries it out — so it sat under
   the same green `approved` chip as one that had shipped, and a reader could
   not tell the two apart from the surface that was asking them to decide."
  [{:keys [verdict]} landed disposition]
  (cond
    ;; Without a rev for a landing recorded before there was anything to record
    ;; it in. Saying "landed" and nothing more is the whole of what is known;
    ;; the note beneath carries the account that era did write.
    landed              [:span.prop-verdict.prop-landed
                         (str "landed" (when-let [r (:rev landed)] (str " · " r)))]
    ;; A plan settled it, and settling it meant deciding nothing would be
    ;; written. There is no landing to point at and there never will be, so
    ;; unless the chip says so the row reads as one still owed.
    (= :file disposition)  [:span.prop-verdict.prop-disposed "filed as follow-up"]
    (= :no-op disposition) [:span.prop-verdict.prop-disposed "no change needed"]
    (= :approved verdict) [:span.prop-verdict.prop-waiting "not yet implemented"]
    :else nil))

(defn- prop-note
  "A decision's or a landing's own words. Both were written to the ledger and
   neither was ever rendered — including the reason a decline gives, which is
   the one field the vocabulary makes a decline meaningless without."
  [label note]
  (when-not (str/blank? (str note))
    [:p.prop-note [:b (str label ": ")] note]))

(defn- proposal-card
  "One proposal, with what earned it and what was decided.

   Evidence is rendered, not folded away. It is the whole reason a proposal is
   worth acting on — the producing skill makes it mandatory and calls it what
   separates an observation from an opinion — and a surface that hides it asks
   for a decision about a claim while showing only the claim."
  [{:keys [project ws-id analysis-seq observation at-seq kind where summary evidence
           proposal run-id reviewed rounds status at decision landed disposition
           reserved?] :as _p}]
  (let [addr (str analysis-seq "." observation)
        base (str "/operations/" project "/" ws-id "/" analysis-seq "/" observation
                  "?entry=" at-seq)
        ;; Settled means nothing further is owed — declined, or approved and
        ;; landed. An approval on its own is not settled however old it is.
        ;;
        ;; A decline is NOT settled once a claim covering this address has been
        ;; reserved. The veto deadline has passed, so the landing happens anyway,
        ;; and rendering it as settled would tell a reader they stopped something
        ;; they did not. It settles when the landing lands.
        late?    (and (= :declined (:verdict decision)) reserved?)
        settled? (or landed
                     (#{:file :no-op} disposition)
                     (and (= :declined (:verdict decision)) (not late?)))]
    [:div {:class (str "prop" (when settled? " settled"))}
     [:div.prop-head
      [:span {:class (str "chip c-" (name kind))} (name kind)]
      [:span.prop-where where]
      (when decision (prop-decision-chip decision))
      ;; NOT gated on a decision. Most of what has landed was never decided —
      ;; the sweep carries an undecided proposal exactly as it carries an
      ;; approved one — and gating the outcome chip on the verdict is what left
      ;; the landed majority rendering as though nothing had happened to them.
      (prop-outcome-chip decision landed disposition)]
     [:p.prop-sum summary]
     (when-not (str/blank? (str evidence)) [:p.prop-ev evidence])
     [:p.prop-fix proposal]
     [:div.prop-meta
      [:span (str "proposal " addr)]
      (when reviewed [:span (str "reviewed " reviewed)])
      (when status [:span (str status " · " rounds " round" (when (not= 1 rounds) "s"))])
      (when at [:span (subs (str at) 0 (min 10 (count (str at))))])
      (when run-id [:span.mono (subs (str run-id) 0 (min 14 (count (str run-id))))])]
     (prop-note "decided" (:note decision))
     (prop-note "landed" (:note landed))
     (when late?
       [:div.prop-note
        [:strong "declined too late"]
        " — a claim covering this proposal was already reserved, so the change lands anyway. "
        "The other proposals that claim covered return to the backlog."])
     ;; Decline is offered wherever nothing has landed, not only where nothing
     ;; has been decided. Approval is no longer what lets the sweep carry a
     ;; proposal — a decline is what stops it — so a veto that could not be
     ;; reached for an already-approved row would be no veto at all, and most of
     ;; the backlog is approved.
     (when-not landed
       [:div.actions {:style "margin-top:10px"}
        (when-not decision
          [:button.btn.btn-primary {"data-on:click" (str "@post('" base "&verdict=approved')")} "Approve"])
        (when-not (= :declined (:verdict decision))
          [:button.btn {"data-on:click" (str "@post('" base "&verdict=declined')")} "Decline"])])]))

(defn- proposal-band
  "Which of the three bands a row is in: what you still owe an answer, what you
   answered and nobody has carried out, and what is finished.

   `:waiting` is a band rather than a shade of `decided`, because it is the only
   one that is nobody's turn. An open proposal is waiting on a reader and a
   settled one is waiting on no one; an approved-and-unlanded proposal is
   waiting on work that no part of nido will start by itself (FU-32), so it
   disappears unless something keeps saying it is there.

   WHAT BECAME OF A ROW IS ASKED BEFORE WHO DECIDED IT, and that order is the
   whole correctness of this. Under the veto model an approval is no longer what
   lets the sweep carry a proposal — a decline is what stops it — so the sweep
   lands proposals nobody ever decided, and asking `(nil? decision)` first put
   every one of them in the band marked as the reader's to answer. It read 82
   awaiting a human when 34 were open and none of them needed anyone.

   A plan settles a row too, and settles it by writing nothing: `:file` and
   `:no-op` mean nothing landed and nothing is going to, so they record no
   landing, so the row stays in the backlog for as long as the ledger stands
   unless the disposition is read here.

   `:queued` is what a plan put in a `:land` claim. It is still owed — the sweep
   has not carried it — but it is not the reader's to answer: under the veto
   model it goes ahead unless declined. Counting it as open read 487 awaiting a
   person the day a hand-condensed plan queued 477 of them. `:open` plus
   `:queued` is the owed set."
  [{:keys [decision landed disposition]}]
  (cond
    landed                            :settled
    (= :declined (:verdict decision)) :settled
    (#{:file :no-op} disposition)     :settled
    (= :land disposition)             :queued
    (nil? decision)                   :open
    :else                             :waiting))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  operations-fragment
  "The proposal list, patched by the poll. Open first, then approved-and-not-done
   — a question you have not answered and a promise nobody has kept are both
   live, and only the finished rows fold away.

   The middle band used to be folded into the trail with the settled ones, on
   the reasoning that a decided proposal is a record. It is not: approving is
   the start of the work rather than the end of it, and putting it behind a
   disclosure marked `already decided` is how three approvals could sit
   untouched with the board reporting nothing awaiting anyone."
  [proposals]
  (let [{:keys [open queued waiting settled] :or {open [] queued [] waiting [] settled []}}
        (group-by proposal-band proposals)]
    (str
     (h/html
      [:div {:id "operations"}
       [:div.ops-head
        [:strong (str (count open) " awaiting you")]
        (when (seq queued)
          [:span.meta (str (count queued) " queued for implementation")])
        (when (seq waiting)
          [:span.prop-verdict.prop-waiting
           (str (count waiting) " approved, not yet implemented")])
        [:span.meta (str (count settled) " settled · " (count proposals) " in all")]]
       (if (empty? proposals)
         [:p.ops-empty "No review-loop analysis has proposed anything yet."]
         (list (for [p open] (proposal-card p))
               (when (seq queued)
                 [:details.trail
                  [:summary (str (count queued) " queued for implementation — the sweep carries "
                                 "these unless you decline one")]
                  (for [p queued] (proposal-card p))])
               (when (seq waiting)
                 (list
                  [:p.meta {:style "margin:18px 0 8px"}
                   "Approved — nothing in nido acts on an approval, so these are "
                   "waiting on someone to do them."]
                  (for [p waiting] (proposal-card p))))
               (when (seq settled)
                 [:details.trail
                  [:summary (str (count settled) " settled")]
                  (for [p settled] (proposal-card p))])))]))))

(defn- ops-card
  "One concern on the Operations home: its name, the one number that says
   whether it needs a person, and the line under it. `tone` colours the number —
   :you when something waits on a reader, :busy when nido is working, nil when
   the concern asks nothing."
  [{:keys [href title headline tone lines]}]
  [:a.ops-card {:href href}
   [:div.ops-card-title title]
   [:div {:class (str "ops-card-num" (when tone (str " ops-" (name tone))))} headline]
   (for [l lines :when l] [:div.meta l])])

(defn- review-queue-card
  "The review-queue card: what the latest grooming is waiting on. Undecided
   items are the only thing on it that asks for a person."
  [queues]
  (let [q (first (sort-by (comp name :project) queues))
        {:keys [stage counts]} q
        more (when (next queues) (str "+" (dec (count queues)) " more project(s)"))]
    (ops-card
     {:href     "/operations/review-queue"
      :title    "Review queue"
      :headline (case stage
                  nil        "could not be read"
                  :none      "never groomed"
                  :planning  "planning…"
                  :failed    "last run failed"
                  :deciding  (str (:undecided counts) " to decide")
                  :unfired   "apply not queued"
                  :applying  "applying…"
                  :applied   (str (:applied counts) " applied"))
      :tone     (case stage
                  (nil :failed :unfired) :you
                  :deciding     (when (pos? (:undecided counts)) :you)
                  (:planning :applying) :busy
                  nil)
      :lines    [(when q (str (name (:project q)) " · " (count (:tickets q)) " tickets in Review"))
                 (when (= :deciding stage)
                   (str (:approved counts) " approved · " (:skipped counts) " skipped"))
                 more]})))

(defn- notice-card
  "An operational notice: something to know that asks no move on any workstream. Not a link —
   it has no page of its own; its lines say what to do, or link to the workstream it is about."
  [{:keys [title headline lines]}]
  [:div.ops-card
   [:div.ops-card-title title]
   [:div.ops-card-num headline]
   (for [l lines :when l] [:div.meta l])])

(defn- casualties-notice
  "The workstreams that died with the host, and the command that brings them back. A command and no
   button: reviving opens terminal tabs, which only a process in the person's terminal session
   can do, and the dashboard runs inside the daemon."
  [casualties]
  (notice-card
   {:title    "Host casualties"
    :headline (str (count casualties) (if (= 1 (count casualties)) " session" " sessions") " to revive")
    :lines    [(list "run " [:code "nido session:revive"] " in a terminal — "
                     [:code ":dry-run true"] " to preview")
               (str/join ", " (map #(str (:project %) "/" (or (:session %) (:instance-id %))) casualties))]}))

(defn- denials-notice
  "Every workstream whose driven session recorded an auto-mode denial, each linking to the
   workstream, whose pane lists them."
  [denied]
  (notice-card
   {:title    "Permission denials"
    :headline (str (reduce + (map (comp count :denials) denied)) " in "
                   (count denied) (if (= 1 (count denied)) " workstream" " workstreams"))
    :lines    (for [{:keys [project ws-id label denials]} denied]
                [:a {:href (str "/workstreams/" project "/" ws-id)}
                 (or label ws-id) " — latest: " (or (:tool-name (peek denials)) "?")])}))

(defn ^{:malli/schema [:=> [:cat :any [:maybe :map] [:maybe [:vector :map]] [:maybe [:vector :map]] [:maybe :map]] :any]}
  operations-home-cards
  "The card for each operational concern, read from the same values their own
   pages render — so a card and the page behind it cannot disagree about a count.
   A value that could not be read (nil) is said on its card rather than shown as
   zero, since zero is the reading that nothing needs anyone.

   `notices` — {:casualties [..] :denials [..]} — are what nido wants a person to know that is no
   workstream's question, so they are here and never in the needs-you list. Shown only when there
   is one: a notice of nothing is noise."
  [proposals recovery holds queues {:keys [casualties denials]}]
  (let [{:keys [open queued waiting]} (group-by proposal-band proposals)
        stuck (count (filter #(#{:stuck :waiting-on-you} (:state %)) holds))
        {:keys [needs-you recovering waiting-rec]}
        (let [c (:counts recovery)]
          {:needs-you (:needs-you c) :recovering (:recovering c) :waiting-rec (:waiting c)})]
    [(ops-card
      {:href     "/operations/improvements"
       :title    "Improvement backlog"
       :headline (str (count open) " awaiting you")
       :tone     (when (seq open) :you)
       :lines    [(when (seq queued)
                    (str (count queued) " queued for implementation"))
                  (when (seq waiting)
                    (str (count waiting) " approved, not yet implemented"))
                  (cond (nil? holds)  "sweep state could not be read"
                        (pos? stuck)  (str "sweep held: " stuck " need" (when (= 1 stuck) "s") " you")
                        :else         nil)
                  (str (count proposals) " proposals in all")]})
     (ops-card
      {:href     "/operations/recovery"
       :title    "Session recovery"
       :headline (if recovery (str needs-you " need you") "could not be read")
       :tone     (cond (nil? recovery)     :you
                       (pos? needs-you)    :you
                       (pos? recovering)   :busy)
       :lines    (when recovery
                   [(str recovering " recovering · " waiting-rec " waiting")
                    (str (:restored (:counts recovery)) " restored in the last "
                         recoveries-view/window-days " days")])})
     ;; Only where some project declares the grooming trigger: a card for a job
     ;; nothing can run would offer a button that fires at nothing.
     (when (or (nil? queues) (seq queues))
       (if (nil? queues)
         (review-queue-card [{:stage nil}])
         (review-queue-card queues)))
     (when (seq casualties) (casualties-notice casualties))
     (when (seq denials) (denials-notice denials))]))

(defn ^{:malli/schema [:=> [:cat [:vector :any]] :string]}
  operations-home-fragment
  "The home's card grid, patched under #ops-home by the home's poll."
  [cards]
  (str (h/html [:div {:id "ops-home" :class "ops-cards"} (seq (remove nil? cards))])))

(defn ^{:malli/schema [:=> [:cat :map [:vector :any]] :any]}
  operations-home-page
  "Operations' home: one card per concern nido operates on itself — each saying
   whether it needs a person — and each opening its own page.

   A home rather than one long page, because the concerns share nothing but the
   fact that none of them is a workstream: recovery is what is happening now, the
   backlog is a queue of decisions, and a third concern stacked under both is a
   section nobody scrolls to."
  [ctx cards]
  (shell
   (assoc ctx :active :operations :title "Operations")
   [:div.ops-col {:data-on-interval__duration.5s "@get('/_fragment/operations')"}
    [:h1 "Operations"]
    (h/raw (operations-home-fragment cards))]))

(defn- ops-crumb [title]
  [:div.ops-crumb [:a {:href "/operations"} "Operations"] " / " title])

(defn ^{:malli/schema [:=> [:cat :map :any [:maybe [:vector :map]]] :any]}
  improvements-page
  "Every proposal nido's review-loop analyses have made, and what was decided.

   Its unit is the proposal rather than the workstream: these records are not a
   stage of any arc, and they are read ACROSS workstreams — the same defect
   proposed by four runs is four rows here and four unrelated workstreams there.
   Unscoped, and the poll is too: the project a proposal is filed under is always
   nido whatever it reviewed.

   What holds the improvement sweep sits above the backlog, because the backlog
   is what a hold stops."
  [ctx proposals holds]
  (shell
   (assoc ctx :active :operations :title "Improvement backlog")
   [:div.ops-col {:data-on-interval__duration.5s "@get('/_fragment/operations/improvements')"}
    (ops-crumb "Improvement backlog")
    ;; Rendered inline, not left as a placeholder for the first poll to fill:
    ;; the poll is a refresh, and a surface that is blank until it fires reads
    ;; as a surface with nothing on it.
    (h/raw (sweep-fragment holds))
    (h/raw (operations-fragment proposals))]))

(defn ^{:malli/schema [:=> [:cat :map [:maybe :map]] :any]}
  recovery-page
  "Session recovery on its own page: what is being recovered, what needs a
   person, and the activity trail."
  [ctx recovery]
  (shell
   (assoc ctx :active :operations :title "Session recovery")
   ;; The poll asks for the feed page the reader is on, so reading an older page
   ;; does not snap back to the newest every five seconds.
   [:div.ops-col {:data-on-interval__duration.5s
                  (str "@get('/_fragment/operations/recovery"
                       (when-let [pos (-> recovery :feed :from)]
                         (str "?feed=" (java.net.URLEncoder/encode (str pos) "UTF-8")))
                       "')")}
    (ops-crumb "Session recovery")
    (h/raw (recovery-fragment recovery))]))

(defn- rq-base [project run-id]
  (str "/operations/review-queue/" (name project) "/" run-id))

(def ^:private rq-kind-label
  {:priority "priority" :brief "QA brief" :correct "correct" :follow-up "follow-up"
   ;; kinds only plans from before the Priority buckets carry, kept so the log renders them
   :rank "rank" :blocker "release blocker"
   :delete "delete" :fold "fold"
   :clear-rank "clear rank" :schema "schema" :view-sort "view sort"})

(defn- rq-item
  "One proposed write: what it does, the full text behind it folded under the
   summary, and — while the plan is being decided — the two answers. After the
   apply run reaches it, what happened instead of the buttons."
  [project run-id stage {:keys [n kind summary detail decision result]}]
  (let [post (fn [v] (str "@post('" (rq-base project run-id) "/items/" n "?verdict=" v "')"))]
    [:div {:class (str "rq-item" (when (= :skipped decision) " rq-skipped"))}
     [:div.prop-head
      [:span.meta (str "[" n "]")]
      [:span {:class (str "chip rq-k-" (name (or kind :other)))}
       (get rq-kind-label kind (some-> kind name))]
      [:span.rq-sum summary]
      (case decision
        :approved [:span.prop-verdict.v-approved "approved"]
        :skipped  [:span.prop-verdict.prop-disposed "skipped"]
        nil)
      (when-let [{:keys [outcome note]} result]
        [:span {:class (str "prop-verdict rq-o-" (name outcome))}
         (str (name outcome) (when note (str " — " note)))])]
     (when-not (str/blank? (str detail))
       ;; A signal rather than <details>: the 5s poll re-patches #review-queue, and
       ;; a native element's open state is DOM state the patch overwrites, so an
       ;; expanded brief snapped shut under the reader. __ifmissing keeps the
       ;; signal across patches; the text is hidden by CSS and revealed by class,
       ;; so a poll never flashes it open.
       (let [sig (str "rqOpen" n)]
         [:div.rq-detail {:data-signals__ifmissing (str "{" sig ": false}")}
          [:span.rq-toggle {"data-on:click" (str "$" sig " = !$" sig)}
           [:span {:data-show (str "!$" sig)} "show"]
           [:span {:data-show (str "$" sig)} "hide"]]
          ;; Line by line rather than pre-wrapped: a brief's line breaks are its
          ;; structure (sections, numbered steps), and the stylesheet keeps no
          ;; pre-wrap rule.
          [:div.rq-pre {:data-class (str "{'rq-shown': $" sig "}")}
           (for [l (str/split-lines (str detail))]
             [:div (if (str/blank? l) (h/raw "&nbsp;") l)])]]))
     (when (= :deciding stage)
       [:div.actions
        [:button {:class (str "btn" (when (not= :approved decision) " btn-primary"))
                  "data-on:click" (post "approved")} "Approve"]
        [:button.btn {"data-on:click" (post "skipped")} "Skip"]])]))

(defn- rq-ticket [project run-id stage {:keys [priority priority-was rank br title url lifecycle qa why items]}]
  [:div.prop
   [:div.prop-head
    [:span.rq-rank (cond (and priority (not= priority priority-was))
                         (str (or priority-was "no Priority") " → " priority)
                         priority priority
                         rank     (str "#" rank)
                         :else    "–")]
    [:a.prop-where {:href url :target "_blank"} br]
    [:span.rq-title title]
    (when lifecycle [:span.meta lifecycle])
    (when qa [:span {:class (str "prop-verdict rq-qa-" (name qa))} (str "QA: " (name qa))])]
   (when why [:p.rq-why why])
   (if (seq items)
     (for [it items] (rq-item project run-id stage it))
     [:p.meta "Nothing to change."])])

(defn- rq-stage-line [{:keys [stage started-at counts]} requested?]
  (cond
    requested?          "Requested — the daemon starts the run within a few seconds."
    (= :none stage)     "Never groomed."
    (= :planning stage) (str "Reading the queue and drafting a plan (started " (ago started-at) ").")
    (= :failed stage)   (str "The run that started " (ago started-at) " ended without a plan — see its session.")
    (= :deciding stage) (str (:items counts) " proposed writes · " (:approved counts) " approved · "
                             (:skipped counts) " skipped · " (:undecided counts) " undecided")
    (= :unfired stage)  "Apply was pressed but could not be queued."
    (= :applying stage) (str "Applying " (:approved counts) " approved writes · "
                             (:applied counts) " done so far"
                             (when (pos? (:failed counts)) (str " · " (:failed counts) " failed")))
    (= :applied stage)  (str (:applied counts) " applied · " (:failed counts) " failed · "
                             (:skipped counts) " skipped")))

(def ^:private rq-decider-label {:nightly "decided by nido" :person "decided by a person"})

(defn- local-minute
  "An ISO instant as `yyyy-MM-dd HH:mm` on this machine's clock — the clock the
   nightly grooming's `:at` is read on unless its source names a zone — or nil."
  [iso]
  (try (.format (.atZone (java.time.Instant/parse (str iso)) (java.time.ZoneId/systemDefault))
                (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm"))
       (catch Exception _ nil)))

(defn- rq-log-line
  "One grooming in the log: when, how it was started, who decided it, and what came of it."
  [{:keys [started-at nightly? decided-by stage counts]}]
  [:li
   [:b (or (local-minute started-at) "–")]
   (str " · " (if nightly? "nightly" "started from this page")
        " · " (get rq-decider-label decided-by "undecided")
        " · "
        (case stage
          :applied  (str (:applied counts) " applied · " (:failed counts) " failed · "
                         (:passed counts) " skipped")
          :applying (str "applying " (:approved counts) " writes")
          :unfired  "apply not queued"
          :deciding (if (zero? (:items counts)) "nothing to write" "plan awaiting a decision")
          :planning "planning"
          :failed   "ended without a plan"
          (some-> stage name)))])

(defn- rq-section
  "One project's grooming: where it stands, the levers that fit that stage, the
   queue by Priority bucket with each ticket's proposed writes, the flags, and the log
   of its recent groomings."
  [{:keys [project stage run-id tickets general flags counts nightly? decided-by log] :as q} requested?]
  (let [run-url   (str "/operations/review-queue/" (name project) "/run")
        idle?     (and (not requested?) (#{:none :failed :deciding :applied} stage))]
    [:div.rq-section
     [:div.ops-head
      [:strong (str "Review queue · " (name project))]
      [:span.meta (rq-stage-line q requested?)
       (when-let [l (get rq-decider-label decided-by)] (str " · " l))]]
     [:div.actions {:style "margin-bottom:14px"}
      (when idle?
        [:button {:class (str "btn" (when (#{:none :applied :failed} stage) " btn-primary"))
                  "data-on:click" (str "@post('" run-url "')")}
         (if (= :none stage) "Run grooming" "Run a fresh grooming")])
      (when (and (= :deciding stage) (pos? (:undecided counts)))
        [:button.btn {"data-on:click" (str "@post('" (rq-base project run-id) "/approve-all')")}
         (str "Approve the " (:undecided counts) " undecided")])
      (when (and (= :deciding stage) (zero? (:undecided counts)) (pos? (:approved counts)))
        [:button.btn.btn-primary {"data-on:click" (str "@post('" (rq-base project run-id) "/apply')")}
         (str "Apply " (:approved counts) " approved")])
      (when (= :unfired stage)
        [:button.btn.btn-primary {"data-on:click" (str "@post('" (rq-base project run-id) "/apply')")}
         "Apply again"])]
     (when (and nightly? (#{:planning :deciding} stage) (not requested?))
       [:p.meta "Nightly grooming — nido approves every undecided write and applies the plan once it is done."])
     (when (and (= :deciding stage) (not requested?) (not nightly?))
       [:p.meta (if (pos? (:undecided counts))
                  "Approve or skip every item to apply. Nothing is written to Notion until you press Apply."
                  "Nothing is written to Notion until you press Apply.")])
     (when (= :unfired stage)
       [:p.meta "Your decisions are frozen, but the apply run was never queued. Apply again queues it with the same decisions."])
     (when (seq tickets)
       (list
        ;; Tickets arrive sorted by bucket, so a bucket is a run of them. A plan from
        ;; before the buckets has none, and keeps its ranked order list.
        (if (some :priority tickets)
          (for [grp (partition-by :priority tickets)]
            (list [:h3.rec-title (or (:priority (first grp)) "No Priority")]
                  (for [t grp] (rq-ticket project run-id stage t))))
          (list
           [:h3.rec-title "Order"]
           [:ol.rq-order
            (for [{:keys [br title why]} tickets]
              [:li [:b br] " " title (when why [:span.meta (str " — " why)])])]
           [:h3.rec-title "Tickets"]
           (for [t tickets] (rq-ticket project run-id stage t))))))
     (when (seq general)
       (list [:h3.rec-title "Across the queue"]
             [:div.prop (for [it general] (rq-item project run-id stage it))]))
     (when (seq flags)
       (list [:h3.rec-title "Flags — for a person, not acted on"]
             [:ul.rq-flags (for [f flags] [:li f])]))
     (when (seq log)
       (list [:h3.rec-title "Log"]
             [:ul.rq-log (for [e log] (rq-log-line e))]))]))

(defn ^{:malli/schema [:=> [:cat [:maybe [:vector :map]] [:set :any]] :string]}
  review-queue-fragment
  "The review-queue page's body, patched under #review-queue by its poll.
   `requested` is the projects whose run was asked for and has not appeared yet —
   so the button is not offered twice for one click. `queues` nil means they
   could not be read, said rather than rendered as a queue with nothing in it."
  [queues requested]
  (str
   (h/html
    [:div {:id "review-queue"}
     (cond
       (nil? queues)   [:p.ops-empty "The review queue could not be read — see the dashboard log."]
       (empty? queues) [:p.ops-empty "No project declares the :review-queue trigger."]
       :else           (for [q queues] (rq-section q (contains? requested (:project q)))))])))

(defn ^{:malli/schema [:=> [:cat :map [:maybe [:vector :map]] [:set :any]] :any]}
  review-queue-page
  "The review-queue grooming: run it, go through what it proposes ticket by
   ticket, approve or skip each write, and apply what was approved."
  [ctx queues requested]
  (shell
   (assoc ctx :active :operations :title "Review queue")
   [:div.ops-col {:data-on-interval__duration.5s "@get('/_fragment/operations/review-queue')"}
    (ops-crumb "Review queue")
    (h/raw (review-queue-fragment queues requested))]))

(defn ^{:malli/schema [:=> [:cat :map :any] :any]}
  proposal-result-fragment
  "What a decision click patches back. The row is replaced by the same card, now
   carrying its decision — or, when the ledger moved under the reader, by the
   refusal saying so, because a decision made against a page that has moved is
   not a decision about what is there now."
  [result proposals]
  (if (= :stale (:decision result))
    (str (h/html [:div {:id "operations"}
                  [:div.card
                   [:strong "The page moved."]
                   [:p.meta (str "This workstream's ledger is now at entry " (:latest result)
                                 ". Nothing was recorded — the next poll shows what is there now.")]]])
         (operations-fragment proposals))
    (operations-fragment proposals)))

(defn ^{:malli/schema [:=> [:cat] :any]}
  not-found-page []
  (shell {:title "404"} [:h1 "Not found"]))
