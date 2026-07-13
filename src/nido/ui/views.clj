(ns nido.ui.views
  "Hiccup view functions for the nido dashboard."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.coordinator.report :as report]
            [nido.process :as process]
            [nido.ui.markdown :as md]
            [nido.ui.view-state :as view-state]
            [nido.work :as work]))

;; ---------------------------------------------------------------------------
;; Layout

(def ^:private shell-css
  (str
   "*, *::before, *::after { box-sizing: border-box; }
        body { font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
               font-size: 14px; line-height: 1.6; color: #e0e0e0;
               background: #1a1a2e; margin: 0; padding: 20px 40px; }
        a { color: #7eb8da; text-decoration: none; }
        a:hover { text-decoration: underline; }
        h1 { font-size: 20px; color: #fff; margin: 0 0 8px; }
        h2 { font-size: 16px; color: #ccc; margin: 24px 0 8px; }
        h3 { font-size: 14px; color: #aaa; margin: 16px 0 4px; }
        table { border-collapse: collapse; width: 100%; }
        th, td { padding: 6px 12px; text-align: left; border-bottom: 1px solid #2a2a4a; }
        th { color: #888; font-weight: normal; font-size: 12px; text-transform: uppercase; }
        .card { background: #16213e; border: 1px solid #2a2a4a; border-radius: 6px;
                padding: 16px; margin: 8px 0; }
        .meta { color: #666; font-size: 12px; }
        .empty { color: #666; font-style: italic; }
        @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.5; } }
        .pulse { animation: pulse 2s ease-in-out infinite; }
        .actions { display: flex; gap: 6px; flex-wrap: wrap; }
        .btn { background: #2a2a4a; color: #e0e0e0; border: 1px solid #3a3a5a;
               padding: 4px 10px; border-radius: 3px; font-size: 12px;
               cursor: pointer; font-family: inherit; }
        .btn:hover { background: #3a3a5a; }
        .btn-primary { background: #2a4a6a; border-color: #3a5a7a; color: #aee0ff; }
        .btn-primary:hover { background: #3a5a7a; }
        .btn-danger { background: #4a2a2a; border-color: #6a3a3a; color: #ffaeae; }
        .btn-danger:hover { background: #6a3a3a; }
        .app-state { padding: 2px 8px; border-radius: 3px; font-size: 11px;
                     text-transform: uppercase; letter-spacing: 0.04em; }
        .app-state-running { background: #1a3a2a; color: #4ade80; }
        .app-state-idle { background: #2a2a1a; color: #facc15; }
        .app-state-starting, .app-state-stopping, .app-state-restarting
          { background: #1a2a3a; color: #7eb8da;
            animation: pulse 1.5s ease-in-out infinite; }
        .app-state-failed { background: #3a1a1a; color: #f87171; }
        .app-state-dormant { background: #2a1a2a; color: #c084fc; }
        .error-msg { color: #f87171; font-size: 11px; margin-top: 4px;
                     max-width: 420px; overflow: hidden; text-overflow: ellipsis;
                     white-space: nowrap; }
        .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
        .badge { display:inline-flex; align-items:center; justify-content:center;
                 width:18px; height:18px; border-radius:4px; font-size:11px; font-weight:bold; }
        .b-notion{ background:#2a3a4a; color:#aee0ff; } .b-github{ background:#2a2a1a; color:#facc15; }
        .b-slack{ background:#3a2a3a; color:#e0a0e0; } .b-scratch{ background:#252540; color:#9a9ac0; }
        .gate-wrap { display:grid; grid-template-columns: 38% 62%; min-height:80vh; }
        .queue-col { border-right:1px solid #2a2a4a; display:flex; flex-direction:column;
                     min-width:0; min-height:0; }
        .inbox { overflow:auto; flex:1; }
        .gate-card { display:block; padding:10px 16px; border-bottom:1px solid #20203a; color:inherit; }
        .gate-card:hover { background:#181830; text-decoration:none; }
        .gate-card.sel { background:#16213e; border-left:3px solid #7eb8da; padding-left:13px; }
        .gate-top { display:flex; align-items:center; gap:8px; }
        .gate-top .lbl { flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:#e8e8e8; }
        .needs { width:7px; height:7px; border-radius:50%; background:#facc15; box-shadow:0 0 6px #facc15; }
        .gate-sub { display:flex; align-items:center; gap:8px; color:#666; font-size:11px; margin:3px 0 0 26px; }
        .gate-sub span:last-child { flex:1; min-width:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .gate-prev { color:#7a7a98; font-size:11.5px; margin:5px 0 0 26px;
                     white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .gate-err { color:#f87171; font-size:11px; margin:4px 0 0 26px; }
        .chip { padding:0 7px; border-radius:3px; font-size:10.5px; text-transform:uppercase; }
        .c-triage{ background:#2a2a1a; color:#facc15; } .c-ready{ background:#1a3a2a; color:#4ade80; }
        .c-in-progress{ background:#1a2a3a; color:#7eb8da; }
        .c-conf-high{ background:#1a3a2a; color:#4ade80; } .c-conf-medium{ background:#2a2a1a; color:#facc15; }
        .c-conf-low{ background:#3a1a1a; color:#f87171; }
        .c-det-bug{ background:#3a2a1a; color:#fbbf24; } .c-det-not-a-bug{ background:#22223a; color:#9a9ac0; }
        .c-det-needs-info{ background:#1a2a3a; color:#7eb8da; }
        .report-meta { margin:0 0 10px; display:flex; gap:6px; flex-wrap:wrap; }
        details.trail { margin-top:14px; } details.trail summary { cursor:pointer; color:#888; font-size:12px; }
        .pane { padding:18px 24px; overflow:auto; }
        .ledger-index { margin:14px 0 8px; border:1px solid #20203a; border-radius:6px; overflow:hidden; }
        .ledger-row { display:flex; gap:10px; align-items:baseline; padding:7px 12px;
                      border-bottom:1px solid #1a1a30; color:#cdcde0; cursor:pointer; }
        .ledger-row:last-child { border-bottom:none; }
        .ledger-row:hover { background:#16162c; }
        .ledger-row.sel { background:#1a2238; }
        .ledger-row .lk { font-size:11px; text-transform:uppercase; color:#8a8ab0; min-width:54px; }
        .ledger-row .meta { min-width:78px; }
        .ledger-row .lt { flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:#e8e8e8; }
        .md { font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif;
              font-size:13.5px; line-height:1.7; color:#cdcde0; background:#0f0f1e;
              border:1px solid #2a2a4a; border-radius:6px; padding:16px 18px; }
        .md code { background:#1c1c33; padding:1px 5px; border-radius:3px; color:#aee0ff; }
        .reply { margin-top:16px; border:1px solid #2a2a4a; border-radius:6px; background:#13132a; padding:12px 14px; }
        .reply textarea { width:100%; min-height:62px; background:#0f0f1e; border:1px solid #2a2a4a;
                          border-radius:4px; color:#e0e0e0; font:inherit; font-size:13px; padding:9px 11px; }"
   ;; rail + content-area chrome
   " body { margin:0; padding:0; display:grid; grid-template-columns:180px 1fr; min-height:100vh; }
     .content { padding:20px 28px; overflow:auto; }
     .rail { background:#13132a; border-right:1px solid #2a2a4a; padding:18px 14px;
             display:flex; flex-direction:column; gap:3px; }
     .rail-brand { display:block; margin-bottom:16px; line-height:0; }
     .rail-brand:hover { text-decoration:none; }
     .rail-logo { display:block; width:84px; height:84px; border-radius:50%;
                  margin:0 auto; transition:filter .15s ease; }
     .rail-brand:hover .rail-logo { filter:brightness(1.12); }
     .rail-link { display:flex; align-items:center; gap:8px; padding:7px 10px; border-radius:5px;
                  color:#aaa; border-left:3px solid transparent; }
     .rail-link:hover { background:#181830; text-decoration:none; }
     .rail-link.active { background:#16213e; color:#fff; border-left-color:#7eb8da; }
     .rail-badge { margin-left:auto; background:#2a4a6a; color:#aee0ff; border-radius:9px;
                   padding:0 7px; font-size:11px; min-width:18px; text-align:center; }
     .rail-badge.zero { background:#222240; color:#666; }
     .rail-scope { margin-top:16px; padding-top:14px; border-top:1px solid #2a2a4a; font-size:12px; }
     .rail-scope a { display:block; padding:3px 6px; color:#9a9ac0; }
     .rail-scope a.active { color:#fff; }
     .rail-health { margin-top:auto; padding-top:14px; font-size:12px; color:#888; }
     .dot { width:8px; height:8px; border-radius:50%; display:inline-block; margin-right:6px; }
     .dot-up { background:#4ade80; box-shadow:0 0 6px #4ade80; }
     .dot-halted { background:#f87171; box-shadow:0 0 6px #f87171; }
     .dot-breaker { background:#fb923c; }
     .dot-down { background:#555; }
     .filters { padding:10px 16px 6px; display:flex; flex-direction:column; gap:4px; }
     .filter-row { display:flex; align-items:center; gap:6px; flex-wrap:wrap; padding:2px 0; }
     .filter-label { color:#888; font-size:11px; text-transform:uppercase; min-width:72px; }
     .chip.active { background:#2a4a6a; color:#aee0ff; border:1px solid #3a5a7a; }
     .ship-badge { font-size:.8em; padding:0 .4em; border-radius:.3em; }
     .badge-findings { color:#e0a34a; border:1px solid #4a3a20; border-radius:4px; padding:1px 6px; font-size:11px; margin-left:6px; }
     .ship-blocked { background:#b00020; color:#fff; font-weight:600; }
     .ship-driving { background:#1d4ed8; color:#fff; }
     .ship-awaiting-merge { background:#555; color:#fff; }
     .ship-queued { background:#777; color:#fff; }
     .ws-section { margin-top:2px; }
     .ws-fold-header { cursor:pointer; user-select:none; display:flex; align-items:center;
                       gap:6px; }
     .ws-fold-header:hover { color:#ccc; }
     .ws-fold-mark { display:inline-block; width:10px; color:#666; font-size:11px; }
     .ws-fold-count { color:#666; font-size:11px; }
     .ws-section-rows { overflow:hidden; max-height:0; opacity:0;
                        transition:max-height .28s ease, opacity .2s ease; }
     .ws-section-rows.ws-open { max-height:4000px; opacity:1; }"))

;; ---------------------------------------------------------------------------
;; Shell (persistent rail + content area) — replaces per-page headers.

(defn- rail-needs-badge [n]
  [:span {:id "rail-needs-count" :class (str "rail-badge" (when (zero? (or n 0)) " zero"))} n])

(defn- rail-health [{:keys [state]}]
  (let [s (name (or state :down))]
    [:div {:id "rail-health" :class "rail-health"}
     [:span {:class (str "dot dot-" s)}] s]))

(defn- rail
  "The persistent navigation rail. Identical on every surface except the active
   highlight + the two live elements (badge, health dot)."
  [{:keys [active needs-count daemon scope projects]}]
  (let [dest (fn [id href label]
               [:a {:class (str "rail-link" (when (= id active) " active")) :href href}
                [:span label]
                (when (= id :needs) (rail-needs-badge needs-count))])]
    [:nav.rail
     [:a.rail-brand {:href "/" :title "nido"}
      [:img.rail-logo {:src "/nido-logo.png" :alt "nido" :width 84 :height 84}]]
     (dest :needs "/" "Needs you")
     (dest :workstreams "/workstreams" "Workstreams")
     (dest :system "/system" "System")
     [:div.rail-scope
      [:div.meta "Scope"]
      ;; Static for now; the scope task wires these to real project filters.
      [:a {:class (when (= scope "all") "active") :href "/"} "All projects"]
      (for [p projects]
        [:a {:class (when (= scope p) "active") :href (str "/?scope=" p)} p])]
     (rail-health daemon)]))

(defn rail-status-fragment
  "The two live rail elements, for SSE patching alongside a surface fragment."
  [{:keys [needs-count daemon]}]
  (str (h/html (rail-needs-badge needs-count))
       (h/html (rail-health daemon))))

(defn shell
  "Page chrome: persistent rail + content area. Replaces `layout`. `ctx` carries
   {:active :title :needs-count :daemon :scope :projects}; `content` is hiccup."
  [{:keys [title] :as ctx} & content]
  (str
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]
      [:title (or title "nido") " — nido"]
      [:script {:type "module" :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.1/bundles/datastar.js"}]
      [:style shell-css]]
     [:body
      (rail ctx)
      (into [:main.content] content)]])))

;; ---------------------------------------------------------------------------
;; Components

;; ---------------------------------------------------------------------------
;; Fragments (for SSE updates)

;; ---------------------------------------------------------------------------
;; Gate inbox (cross-project human-decision queue over nido.work)

(defn origin-badge [origin]
  (let [[ch cls] (case origin
                   :notion ["N" "b-notion"] :github ["G" "b-github"]
                   :slack ["S" "b-slack"]   ["·" "b-scratch"])]
    [:span {:class (str "badge " cls)} ch]))

(defn- chip [stage]
  [:span {:class (str "chip c-" (name stage))} (name stage)])

(defn- enc-val [v]
  (java.net.URLEncoder/encode (if (keyword? v) (name v) (str v)) "UTF-8"))

(defn- screen-query
  "Query string (leading ?) rebuilding the active scope/source/facets from the
   screen, with optional overrides. `:sel` adds the selection (\"project:ws-id\");
   `:source`/`:facets` override the corresponding selection (used by the filter
   chips). The single place selection + filters are serialized — so a row link,
   a poll refresh, and a deep link all carry the identical view-state."
  [{:keys [scope source facets]} & [overrides]]
  (let [src  (get overrides :source source)
        facs (merge facets (:facets overrides))
        sel  (:sel overrides)
        pairs (cond-> []
                (and scope (not= "all" scope)) (conj (str "scope=" scope))
                (and src (not= :all src))       (conj (str "source=" (name src)))
                :always (into (for [[k v] facs :when (not= :all v)]
                                (str (name k) "=" (enc-val v))))
                sel (conj (str "sel=" sel)))]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))

(defn- gate-card
  "One inbox row; links to the gate pane. `sel?` highlights the open gate. `href`
   carries the view-state so selecting a gate preserves scope + selection. A
   `:pending?` gate (its agent was resumed / is mid-resolve) shows 'working…'."
  [{:keys [project origin stage label report session resume-error pending?]} sel? href]
  [:a {:class (str "gate-card" (when sel? " sel"))
       :href  href}
   [:div.gate-top (origin-badge origin) [:span.lbl label] [:span.needs {:title "needs you"}]]
   [:div.gate-sub [:span project] (chip stage)
    [:span (cond pending? "working…" session (str "parked · " session) :else "decide")]]
   [:div.gate-prev (or (some-> report :markdown
                               (str/replace #"^#.*\n+" "")
                               str/split-lines first)
                       "—")]
   (when resume-error
     [:div.gate-err "⚠ resume failed: " (or (:message resume-error)
                                            (name (:reason resume-error)))])])

(defn needs-fragment
  "The needs-you queue column — initial render + SSE refresh. Renders from the
   screen so a poll preserves the open gate's highlight (selection is threaded
   from the screen, not reset to nil each tick). Each gate's link carries the
   view-state (scope + selection) so selecting one preserves scope."
  [{:keys [gates selection] :as screen}]
  (let [sel-id (:ws-id selection)]
    (str
     (h/html
      (if (seq gates)
        [:div {:id "needs"}
         (for [g gates]
           (gate-card g (= sel-id (:ws-id g))
                      (str "/" (screen-query screen {:sel (str (:project g) ":" (:ws-id g))}))))]
        [:div {:id "needs"} [:p.empty "Nothing needs you right now."]])))))

(defn gate-action-confirm-fragment
  "Immediate, action-keyed confirmation toast. `pane-id` is the element it
   patches — \"gate-pane\" on the Needs-you page, \"ws-pane\" on the overview.
   :promote is intentionally destination-neutral: a :ready ticket provisions the
   work session, an :incoming Slack report starts triage."
  ([action-id project ws-id] (gate-action-confirm-fragment action-id project ws-id "gate-pane"))
  ([action-id project ws-id pane-id]
   (let [msg (case action-id
               :promote "Promoting…"
               :apply   "Applying… resuming the agent to write the verdict."
               :dismiss "✓ Dismissed — off your radar, won't be re-triaged."
               :drop    "✓ Dropped — not pursued."
               :done    "✓ Marked done."
               :reply   "Resuming… re-hydrating the session if needed, then resuming the conversation."
               "Done.")]
     (str
      (h/html
       [:div {:id pane-id}
        [:h1 msg]
        [:p.meta "ws " ws-id]
        [:p "Follow it: "
         [:a {:href (str "/workstreams/" project "/" ws-id)} "open workstream →"]
         " · "
         [:a {:href "/workstreams"} "workstreams →"]]])))))

(defn- style-class [style]
  (case style :primary "btn btn-primary" :danger "btn btn-danger" "btn"))

(defn- gate-route
  "Default POST target for a gate action: the home /gate route (patches #gate-pane)."
  [project ws-id action-id]
  (str "/gate/" project "/" ws-id "/" (name action-id)))

(defn- action-button [project ws-id {:keys [id label style]} route]
  [:button {:class (style-class style)
            "data-on:click" (str "@post('" (route project ws-id id) "')")}
   label])

(defn action-bar
  "Render an action set: one-click buttons (mutations + preset-input resumes) in a row,
   plus a free-text reply textarea when a resume action without :input is present.
   `session` (optional) labels the resume target. `route` (optional, default the home
   /gate route) builds each button's POST url from (project ws-id action-id) — pass the
   pane route to keep responses inside the overview pane. The single renderer behind
   every gate."
  ([project ws-id actions session] (action-bar project ws-id actions session gate-route))
  ([project ws-id actions session route]
   (let [buttons   (filter #(or (= :mutation (:kind %)) (:input %)) actions)
         free-text (some #(and (= :resume (:kind %)) (not (:input %))) actions)]
     (list
      (when (seq buttons)
        (into [:div.actions {:style "margin-top:16px"}]
              (for [a buttons] (action-button project ws-id a route))))
      (when free-text
        [:div.reply
         [:div.meta {:style "text-transform:uppercase;font-size:11px"} "Reply & resume"]
         [:textarea {"data-bind" "reply" :placeholder "Tell the agent what to do next…"}]
         [:div {:style "margin-top:9px"}
          [:button.btn.btn-primary
           {"data-on:click" (str "@post('" (route project ws-id :reply) "')")}
           "Send & resume ▸"]
          (when session [:span.meta {:style "margin-left:10px"} "resumes " session])]])))))

(defn- conf-chip [{:keys [level]}]
  [:span {:class (str "chip c-conf-" (name level))} (name level)])

(defn- triage-report-card
  "Curated render of a typed triage report: determination + confidence chips, summary,
   directions, an On-apply block, and a collapsed investigation trail (§5)."
  [{:keys [title determination summary confidence directions notion-writes trail]}]
  [:div.md
   (when title [:h2 title])
   [:div.report-meta
    [:span {:class (str "chip c-det-" (name determination))} (name determination)]
    (conf-chip confidence)
    [:span.meta (:reason confidence)]]
   (md/render summary)
   [:h3 "Solution directions"]
   (into [:ul]
         (for [{:keys [label shape effort confidence]} directions]
           [:li [:strong label] " · " (name effort) " · " (name (:level confidence))
            " — " shape]))
   (when notion-writes
     [:div
      [:h3 "On apply →"]
      (into [:ul]
            (concat
             [[:li "Type: " (or (:type notion-writes) "unchanged")]
              [:li "Effort: " (name (:effort notion-writes))]]
             (when-let [[from to] (:status-transition notion-writes)]
               [[:li "Status: " [:code from] " → " [:code to]]])
             [[:li "Title: " (:title notion-writes)]]))])
   (when (seq trail)
     [:details.trail
      [:summary "Investigation trail (" (count trail) ")"]
      (into [:ul]
            (for [{:keys [ref note]} trail]
              [:li [:code ref] " — " note]))])])

(defn- plan-card [{:keys [summary direction effort steps]}]
  [:div.md
   [:h2 "Implementation plan"]
   [:div.report-meta [:span.meta "Direction: " direction " · Effort: " (name effort)]]
   (md/render summary)
   (when (seq steps)
     [:div [:h3 "Steps"] (into [:ul] (for [s steps] [:li s]))])])

(defn- completed-card [{:keys [summary artifacts open]}]
  [:div.md
   [:h2 "Implementation completed"]
   (md/render summary)
   [:h3 "Artifacts"]
   (into [:ul]
         (for [{:keys [kind ref url]} artifacts]
           [:li [:strong (name kind)] " " [:code ref]
            (when url [:span " — " [:a {:href url :target "_blank"} url]])]))
   (when (seq open)
     [:div [:h3 "Still open"] (into [:ul] (for [o open] [:li o]))])])

(defn- blocker-card [{:keys [summary needs]}]
  [:div.md
   [:h2 "Blocker"]
   (md/render summary)
   [:h3 "Needs"]
   [:p needs]])

(defn- pr-opened-card [{:keys [url title summary]}]
  [:div.md
   [:h2 "PR opened"]
   [:p [:strong title] " — " [:a {:href url :target "_blank"} url]]
   (when summary (md/render summary))])

(defn- report-body
  "Dispatch a gate/ledger report on :format — each typed event gets its curated card,
   markdown reports render through md/render."
  [report]
  (case (:format report)
    :triage-report            (triage-report-card report)
    :implementation-plan      (plan-card report)
    :implementation-completed (completed-card report)
    :blocker                  (blocker-card report)
    :pr-opened                (pr-opened-card report)
    :findings                 (md/render (report/report->markdown report))
    (md/render (:markdown report))))

(defn gate-pane
  "The detail pane: rendered report + follow-actions. nil -> calm placeholder.
   A `:pending?` gate (its agent is running after Apply/Reply) shows 'working…'
   and no action buttons — deriving action availability from the agent's live
   phase, so a poll can't flip it back to a fresh Apply button."
  [{:keys [ws-id project origin label report actions session pending?] :as gate}]
  (str
   (h/html
    (if-not gate
      [:div {:id "gate-pane"} [:p.empty "Nothing needs you right now."]]
      [:div {:id "gate-pane"}
       [:h1 (origin-badge origin) " " label]
       (when report [:div.meta (some-> report :format name) " · " (:at report)])
       (report-body report)
       (if pending?
         [:div.actions {:style "margin-top:16px"} [:span.meta "working… the agent is running."]]
         (action-bar project ws-id actions session))]))))

(defn needs-page
  "Home: the needs-you master-detail inside the shell, rendered from the screen.
   The selected gate (matched from the screen's gates by the view-state
   selection) fills the pane; the poll query carries scope + selection so the
   3s refresh preserves both."
  [ctx {:keys [gates selection] :as screen}]
  (let [sel-id   (:ws-id selection)
        sel-gate (first (filter #(= sel-id (:ws-id %)) gates))
        q        (screen-query screen (when sel-id {:sel (str (:project selection) ":" sel-id)}))]
    (shell
     (assoc ctx :active :needs :title "Needs you")
     [:div.gate-wrap
      [:div.queue-col
       [:div.inbox {:data-on-interval__duration.3s (str "@get('/_fragment/needs" q "')")}
        (h/raw (needs-fragment screen))]]
      [:div.pane (h/raw (gate-pane sel-gate))]])))

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
   [:ready       "Ready"]
   [:triage      "Triage"]
   [:incoming    "Incoming"]])

(defn- ws-fold-signal
  "Collapsed-flag signal name for a stage, e.g. :in-progress → \"wsFoldInProgress\"."
  [stage]
  (str "wsFold" (some (fn [[s suf]] (when (= s stage) suf)) ws-fold-stages)))

(def ^:private ws-fold-storage-key "nidoWsFold")

(def ^:private ws-fold-signals-init
  "data-signals__ifmissing expression for the persistent chrome: seed each stage's
   collapsed flag from localStorage on load. `===true` coerces a missing/false key
   to expanded (the default), and __ifmissing makes the whole thing a no-op once
   the signals exist — so a full-page reload restores state without clobbering it."
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

(defn- ws-stage-sections
  "Flatten one {:project :grouped} into [{:project :stage :rows}] in most-advanced-
   first order (shipping → in-progress → ready → triage → incoming), dropping empty
   stages. Kept as a plain fn (not inline hiccup) for the same SCI reason the old
   stage-sections was."
  [{:keys [project grouped]}]
  (->> [[:shipping (:shipping grouped)]
        [:in-progress (:in-progress grouped)]
        [:ready (:ready grouped)]
        [:triage (concat (-> grouped :triage :in-flight) (-> grouped :triage :queued))]
        [:incoming (:incoming grouped)]]
       (keep (fn [[stage rows]] (when (seq rows) {:project project :stage stage :rows rows})))))

(defn- ws-list-row
  "One selectable list row. Its link carries the full view-state (scope + source
   + facets) plus the selection, so selecting a workstream lands on the SAME
   filtered list rather than a differently-filtered one. `sel-id` highlights the
   open row (threaded from the screen so a poll preserves it)."
  [screen sel-id project {:keys [ws-id origin label needs-you stage ship-substate open-findings]}]
  [:a {:class (str "gate-card" (when (= sel-id ws-id) " sel"))
       :href  (str "/workstreams" (screen-query screen {:sel (str project ":" ws-id)}))}
   [:div.gate-top (origin-badge origin) [:span.lbl label]
    (when needs-you [:span.needs {:title "needs you"}])
    (when (pos? (or open-findings 0))
      [:span.badge-findings (str "⚑ " open-findings " open findings")])
    (when (= :shipping stage)
      [:span {:class (str "ship-badge ship-" (name (or ship-substate :queued)))}
       (case ship-substate
         :blocked        "⚠ blocked"
         :driving        "driving"
         :awaiting-merge "awaiting merge"
         "queued")])]
   [:div.gate-sub [:span project]]])

(defn workstreams-fragment
  "Stage-grouped selectable list across projects, rendered from the screen.
   Selection is threaded from the screen so a poll refresh keeps the open row's
   highlight instead of clearing it."
  [{:keys [groups selection] :as screen}]
  (let [sel-id (:ws-id selection)]
    (str
     (h/html
      [:div {:id "workstreams"}
       ;; The fold signals live on the persistent chrome (see workstreams-page);
       ;; here we only re-bind the click toggle, the reactive fold marker, and the
       ;; row list's data-class reveal. These re-attach to the already-declared
       ;; signals on every 5s poll, which is harmless — the state persists.
       (for [{:keys [project stage rows]} (mapcat ws-stage-sections groups)]
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
             (for [r rows] (ws-list-row screen sel-id project r))]]))]))))

(defn- session-dev-cell
  "Per-session DEV ENVIRONMENT controls for the Sessions table, driven by the
   session's derived dev-resource state. Actions POST to the per-session
   lifecycle route; the session name is URL-encoded so a slash-namespaced name
   (feat/foo) stays a single path segment."
  [project ws-id session {:keys [state url error-msg]}]
  (let [enc (java.net.URLEncoder/encode (str session) "UTF-8")
        act (fn [a] (str "@post('/workstreams/" project "/" ws-id
                         "/sessions/" enc "/dev/" a "')"))]
    (case state
      :running
      [:div.actions
       [:a.btn.btn-primary {:href url :target "_blank"} "Open app ↗"]
       [:button.btn {"data-on:click" (act "stop")} "stop"]]
      (:starting :stopping :restarting) [:span.meta "working…"]
      :failed
      [:div
       [:button.btn.btn-primary {"data-on:click" (act "start")} "retry"]
       (when error-msg [:div.error-msg error-msg])]
      ;; :down or nil
      [:button.btn.btn-primary {"data-on:click" (act "start")} "start"])))

(defn- ledger-browser
  "The entry index (only when >1 entry) above the selected entry's report. Rows
   @get the pane fragment with ?entry=<seq>, patching #ws-pane. `entries` is the
   newest-first index; `report` is the already-selected report."
  [project ws-id entries selected-seq report]
  [:div
   (when (and (seq entries) (> (count entries) 1))
     (into [:div.ledger-index]
           (for [{:keys [seq kind at title]} entries]
             [:a {:class (str "ledger-row" (when (= seq selected-seq) " sel"))
                  "data-on:click"
                  (str "@get('/_fragment/workstream/" project "/" ws-id "?entry=" seq "')")}
              [:span.lk (clojure.core/name kind)]
              [:span.meta (when at (let [s (str at)] (subs s 0 (min 10 (count s)))))]
              [:span.lt title]])))
   (when report (report-body report))])

(defn- pane-route
  "POST target for the pane-scoped resolve route — responses patch #ws-pane so the
   action stays inside the overview/detail pane rather than the home gate inbox."
  [project ws-id action-id]
  (str "/workstreams/" project "/" ws-id "/gate/" (name action-id)))

(defn- pane-action-bar
  "Stage-appropriate gate actions rendered below the reader pane, driven by
   `work/gate-actions` (different stages → different actions; a parked triage adds
   Apply/Reply). Takes `origin` so Notion `:triage` rows drop Dismiss (kept for
   Slack). Buttons POST to the pane-scoped route. Shown only for the CURRENT
   ledger entry — callers gate on :on-latest?. Renders nothing when the stage offers
   no actions."
  [project ws-id origin stage sessions]
  (let [parked? (boolean (some :parked? sessions))
        session (:name (first (filter :parked? sessions)))]
    (action-bar project ws-id (work/gate-actions stage parked? origin) session pane-route)))

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

(defn workstream-pane
  "Read-only ledger pane: header · stage · ledger summary · report · Sessions table
   with per-row dev-env controls. `session-dev-states` is a map of session-name →
   {:state … :url :error-msg} (the view does no IO). Polls its own fragment so
   transient dev-env states (starting…) self-advance."
  [{:keys [project ws-id origin stage label ledger report entries selected-seq sessions on-latest?]
    :or {on-latest? true}} session-dev-states]
  (str
   (h/html
    (if-not label
      [:div {:id "ws-pane"} [:p.empty "Select a workstream."]]
      [:div {:id "ws-pane"
             :data-on-interval__duration.3s
             (str "@get('/_fragment/workstream/" project "/" ws-id
                  (when selected-seq (str "?entry=" selected-seq)) "')")}
       [:h1 (origin-badge origin) " " label]
       [:p.meta (name stage)]
       (when ledger
         [:div.card [:strong "ledger "] (:key ledger) " · " (some-> ledger :status name)
          " · " (:report-count ledger) " report(s)"])
       (ledger-browser project ws-id entries selected-seq report)
       ;; Live actions only on the current ledger entry — older entries are read-back.
       (when on-latest? (pane-action-bar project ws-id origin stage sessions))
       (when (= :done stage) (file-findings-form project ws-id))
       [:h2 "Sessions"]
       (if (seq sessions)
         [:table
          [:thead [:tr [:th "session"] [:th "axis"] [:th "status"] [:th "dev env"] [:th "brakes"]]]
          [:tbody
           (for [{:keys [name autonomy-level parked? status brakes]} sessions]
             [:tr [:td name]
              [:td (clojure.core/name autonomy-level) (when parked? " · gate")]
              [:td (clojure.core/name (or status :down))]
              [:td (session-dev-cell project ws-id name (get session-dev-states name))]
              [:td.meta (when brakes (pr-str brakes))]])]]
         [:p.empty "No sessions."])]))))

(defn- chip-link [label active? href]
  [:a {:class (str "chip" (when active? " active")) :href href} label])

(defn- source-row
  "Source filter chips, rendered from the screen. A chip changes the source and
   drops the current selection (a filter change resets the pane). There is no
   cross-source 'All' chip — the page always shows exactly one source."
  [{:keys [source source-counts] :as screen}]
  [:div.filter-row
   [:span.filter-label "Source"]
   (for [id view-state/sources
         :let [label (str/capitalize (name id))
               n     (get source-counts id 0)]]
     (chip-link (str label " (" n ")")
                (= id source)
                (str "/workstreams" (screen-query screen {:source id}))))])

(defn- facet-rows [{:keys [facet-dims facets] :as screen} groups]
  (for [k facet-dims
        :let [present (->> groups (mapcat (fn [g] (work/grouped-rows (:grouped g))))
                           (mapcat (fn [r] (let [v (get-in r [:facets k])]
                                             (cond (nil? v) nil (coll? v) v :else [v]))))
                           distinct)
              vals (concat present (when (some (fn [g]
                                                 (some #(empty? (or (get-in % [:facets k]) []))
                                                       (work/grouped-rows (:grouped g)))) groups)
                                     [:unclassified]))
              sel  (get facets k :all)]]
    [:div.filter-row
     [:span.filter-label (->> (str/split (name k) #"-") (map str/capitalize) (str/join " "))]
     (chip-link "All" (= :all sel) (str "/workstreams" (screen-query screen {:facets {k :all}})))
     (for [v vals
           :let [lbl (if (keyword? v) (str/capitalize (name v)) (str v))]]
       (chip-link lbl (= v sel) (str "/workstreams" (screen-query screen {:facets {k v}}))))]))

(defn workstreams-page
  "Overview + ledger pane, rendered from the screen. The list, its poll query,
   and the pane all derive from the one screen value, so overview and detail
   never disagree and a poll preserves the selection + filters."
  [ctx {:keys [selection] :as screen}]
  (let [sel-id (:ws-id selection)
        q      (screen-query screen (when sel-id {:sel (str (:project selection) ":" sel-id)}))]
    (shell
     (assoc ctx :active :workstreams :title "Workstreams")
     ;; Section-fold signals are declared here, on the stable page chrome that the
     ;; 5s poll never replaces (the poll only patches #workstreams inside .inbox).
     ;; __ifmissing seeds them from localStorage on load without clobbering live
     ;; state; a full-page reload re-runs this and restores the persisted fold.
     [:div.gate-wrap {:data-signals__ifmissing ws-fold-signals-init}
      ;; Filters live INSIDE the left queue column (not a full-width band across
      ;; the top) so the detail pane on the right always starts at the top,
      ;; independent of how many filter rows a source contributes.
      [:div.queue-col
       [:div.filters (source-row screen) (facet-rows screen (:groups screen))]
       [:div.inbox {:data-on-interval__duration.5s (str "@get('/_fragment/workstreams" q "')")}
        (h/raw (workstreams-fragment screen))]]
      [:div.pane (h/raw (workstream-pane (:ws selection) (:dev-states selection)))]])))

;; ---------------------------------------------------------------------------
;; System (cross-project ops) — replaces the live board + per-project sessions list.

(defn- system-row
  "One cross-project table row: pending-state badges (app-state-*), heap-max, RSS,
   error messages, and transient/failed state logic. Action URLs use the
   /system/:project/:name/:action path."
  [{:keys [project name entry live? pending-state repl-rss pg-rss heap-max]}]
  (let [url (:url entry)
        pg-port (:pg-port entry)
        repl-port (:nrepl-port entry)
        app-port (:app-port entry)
        pending-kw  (cond
                      (map? pending-state)     (:state pending-state)
                      (keyword? pending-state) pending-state)
        pending-err (when (map? pending-state) (:error-msg pending-state))
        state (cond
                live?        :running
                pending-kw   pending-kw
                (not entry)  :dormant
                :else        :idle)
        state-class (str "app-state app-state-" (clojure.core/name state))
        action-base (str "/system/" project "/" name)
        transient?  (#{:starting :stopping :restarting} state)
        failed?     (= state :failed)
        pg-rss-str  (process/human-bytes pg-rss)
        jvm-rss-str (process/human-bytes repl-rss)]
    [:tr
     [:td [:strong name]]
     [:td.mono project]
     [:td [:span {:class state-class :title (or pending-err "")} (clojure.core/name state)]
      (when (and failed? pending-err)
        [:div.error-msg pending-err])]
     [:td (if (and live? url)
            [:a {:href url :target "_blank"} url]
            [:span.meta "—"])]
     [:td.mono (or pg-port "—")
      (when pg-rss [:div.meta pg-rss-str])]
     [:td.mono (or repl-port "—")
      (when repl-rss [:div.meta jvm-rss-str])
      (when heap-max [:div.meta (str "max " heap-max)])]
     [:td.mono (or app-port "—")]
     [:td [:div.actions
           (cond
             transient? [:span.meta "working…"]
             failed?
             [:button.btn.btn-primary {"data-on:click" (str "@post('" action-base "/restart')")} "retry"]
             (= state :dormant)
             [:button.btn.btn-primary {"data-on:click" (str "@post('" action-base "/start')")} "start"])
           (when (and entry (not transient?))
             [:button.btn {"data-on:click" (str "@post('" action-base "/restart')")} "restart"])
           (when (and entry (not transient?))
             [:button.btn {"data-on:click" (str "@post('" action-base "/stop')")} "stop"])]]]))

(defn system-fragment
  "Daemon banner + cross-project session table. `daemon` is the health map.
   Renders pending-state badges, heap-max, RSS, and error messages per row.
   Action URLs use the /system/:project/:name/:action path."
  [rows {:keys [state heartbeat-at] :as _daemon}]
  (str
   (h/html
    [:div {:id "system"}
     [:div.card
      [:span {:class (str "dot dot-" (clojure.core/name (or state :down)))}]
      "daemon " (clojure.core/name (or state :down))
      (when heartbeat-at [:span.meta " · heartbeat " heartbeat-at])]
     (if (seq rows)
       [:table
        [:thead [:tr [:th "session"] [:th "project"] [:th "state"] [:th "dev url"]
                 [:th "pg"] [:th "repl"] [:th "app"] [:th "actions"]]]
        [:tbody (for [r rows] (system-row r))]]
       [:p.empty "No sessions."])])))

(defn system-page
  "System surface: daemon health banner + cross-project session table in the shell."
  [ctx rows daemon]
  (let [q (if (= "all" (:scope ctx)) "" (str "?scope=" (:scope ctx)))]
    (shell
     (assoc ctx :active :system :title "System")
     [:div {:data-on-interval__duration.3s (str "@get('/_fragment/system" q "')")}
      (h/raw (system-fragment rows daemon))])))

(defn not-found-page []
  (shell {:title "404"} [:h1 "Not found"]))
