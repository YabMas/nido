(ns nido.ui.views
  "Hiccup view functions for the nido dashboard."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.coordinator.report :as report]
            [nido.coordinator.triggers :as triggers]
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
        .action-err { color: #f87171; background: #2a1616; border: 1px solid #4a2020;
                      border-radius: 4px; padding: 8px 10px; margin-top: 16px;
                      font-size: 12px; }
        .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
        .badge { display:inline-flex; align-items:center; justify-content:center;
                 width:18px; height:18px; border-radius:4px; font-size:11px; font-weight:bold; }
        .b-notion{ background:#2a3a4a; color:#aee0ff; } .b-github{ background:#2a2a1a; color:#facc15; }
        .b-slack{ background:#3a2a3a; color:#e0a0e0; } .b-scratch{ background:#252540; color:#9a9ac0; }
        .gate-wrap { display:grid; grid-template-columns: 38% 62%; min-height:80vh; }
        .queue-col { border-right:1px solid #2a2a4a; display:flex; flex-direction:column;
                     min-width:0; min-height:0; }
        .pickup { margin:12px 16px 10px; padding:12px 14px; border:1px solid #2a2a4a;
                  border-radius:6px; background:#16213e; }
        .pickup-label { color:#f0f0f5; font-size:11px; text-transform:uppercase; letter-spacing:0.04em; margin-bottom:6px; }
        .pickup-row { display:flex; gap:6px; }
        .pickup input { flex:1; box-sizing:border-box; background:#0f0f1e; border:1px solid #2a2a4a;
                        border-radius:4px; color:#fff; font:inherit; font-size:12px; padding:6px 9px; }
        .pickup input::placeholder { color:#8a8aa8; }
        .pickup input:focus { outline:none; border-color:#3a5a7a; }
        .pickup .btn { color:#fff; }
        .pickup-result { margin-top:2px; }
        .tabs { display:flex; gap:4px; padding:10px 16px 6px; }
        .tab { padding:4px 10px; border-radius:4px; color:#888; font-size:12px;
               text-transform:uppercase; border:1px solid transparent; }
        .tab:hover { color:#ccc; text-decoration:none; }
        .tab.active { background:#2a4a6a; color:#aee0ff; border-color:#3a5a7a; }
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
        .c-rv-ok{ background:#1a3a2a; color:#4ade80; } .c-rv-warn{ background:#2a2a1a; color:#facc15; }
        .c-rv-bad{ background:#3a1a1a; color:#f87171; } .c-rv-run{ background:#1a2a3a; color:#7eb8da; }
        .c-rv-neutral{ background:#22223a; color:#9a9ac0; }
        .c-p0, .c-p1 { background:#3a1a1a; color:#f87171; } .c-p2 { background:#2a2a1a; color:#facc15; }
        .c-p3 { background:#22223a; color:#9a9ac0; }
        .rv-rounds { margin-top:14px; }
        .rv-round { border:1px solid #20203a; border-radius:6px; background:#12122a;
                    padding:10px 12px; margin:8px 0; }
        .rv-round-head { display:flex; gap:8px; align-items:baseline; margin-bottom:4px; }
        .rv-round-head .meta { flex:1; min-width:0; white-space:nowrap; overflow:hidden;
                               text-overflow:ellipsis; }
        .rv-foldable { cursor:pointer; user-select:none; }
        .rv-foldable:hover .rv-mark, .rv-foldable:hover strong { color:#fff; }
        .rv-mark { display:inline-block; width:10px; color:#666; font-size:11px; }
        .rv-finding-line { padding:2px 0; }
        .rv-phase { padding:3px 0; }
        .rv-phase-head { display:flex; gap:8px; align-items:baseline; }
        .rv-glyph { width:12px; color:#7eb8da; text-align:center; }
        .rv-label { color:#cdcde0; min-width:52px; font-size:12.5px; }
        .rv-findings { margin:4px 0 6px 20px; padding-left:14px; }
        .rv-finding { padding:3px 0; }
        .rv-loc { color:#8a8ab0; font-size:11.5px; margin-left:26px; }
        .rv-prose { margin:3px 0 6px 26px; }
        .rv-prose .md { background:none; border:none; padding:0; color:#9a9ac0;
                        font-size:12.5px; line-height:1.6; }
        .rv-prose .md p { margin:3px 0; }
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
        .viewer { margin:12px 0 8px; border:1px solid #2a2a4a; border-radius:6px;
                  background:#0f0f1e; overflow:hidden; }
        .viewer-bar { display:flex; gap:10px; align-items:baseline; padding:8px 12px;
                      background:#16162c; border-bottom:1px solid #2a2a4a; }
        .viewer-bar .lk { font-size:11px; text-transform:uppercase; color:#8a8ab0; }
        .viewer-bar .lt { flex:1; min-width:0; white-space:nowrap; overflow:hidden;
                          text-overflow:ellipsis; color:#e8e8e8; }
        .viewer-close { background:none; border:none; color:#8a8ab0; cursor:pointer;
                        font-size:13px; padding:0 2px; line-height:1; }
        .viewer-close:hover { color:#e8e8e8; }
        .viewer > .md { border:none; border-radius:0; background:none; }
        .md { font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',system-ui,sans-serif;
              font-size:13.5px; line-height:1.7; color:#cdcde0; background:#0f0f1e;
              border:1px solid #2a2a4a; border-radius:6px; padding:16px 18px; }
        .md code { background:#1c1c33; padding:1px 5px; border-radius:3px; color:#aee0ff; }
        .reply { margin-top:16px; border:1px solid #2a2a4a; border-radius:6px; background:#13132a; padding:12px 14px; }
        .reply textarea { width:100%; min-height:62px; background:#0f0f1e; border:1px solid #2a2a4a;
                          border-radius:4px; color:#e0e0e0; font:inherit; font-size:13px; padding:9px 11px; }
        .links { margin:6px 0 14px; }
        .link-row { display:flex; gap:10px; align-items:baseline; font-size:12px;
                    line-height:1.9; }
        .link-k { color:#666; font-size:11px; text-transform:uppercase;
                  letter-spacing:0.04em; min-width:82px; }"
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
     .rail-health-btn { background:none; border:none; color:inherit; font:inherit;
                        cursor:pointer; padding:0; display:flex; align-items:center; }
     .ops-wrap { position:fixed; left:14px; bottom:14px; width:340px; max-width:360px;
                 max-height:70vh; overflow-y:auto; z-index:50; }
     .ops-panel .card { background:#16213e; }
     .ops-panel .fire-row { display:flex; align-items:center; gap:6px; flex-wrap:wrap;
                             margin:6px 0; }
     .ops-panel .fire-row input { background:#0f0f1e; border:1px solid #2a2a4a;
                                   border-radius:4px; color:#fff; font:inherit;
                                   font-size:12px; padding:4px 8px; width:100px; }
     .dot { width:8px; height:8px; border-radius:50%; display:inline-block; margin-right:6px; }
     .dot-up { background:#4ade80; box-shadow:0 0 6px #4ade80; }
     .dot-halted { background:#f87171; box-shadow:0 0 6px #f87171; }
     .dot-breaker { background:#fb923c; }
     .dot-down { background:#555; }
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
     .ws-section-rows.ws-open { max-height:4000px; opacity:1; }
     .winddown, .dismissed { opacity: 0.65; }"))

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
   current surface (changing only scope, preserving the workstreams tab); a surface link
   carries the current scope. Selecting a project changes what you see, never where you are."
  [{:keys [active needs-count daemon scope projects tab]}]
  (let [surface-path {:needs "/" :workstreams "/workstreams"}
        q (fn [scope-val workstreams?]
            (let [parts (cond-> []
                          (and scope-val (not= "all" scope-val)) (conj (str "scope=" scope-val))
                          (and workstreams? tab (not= view-state/default-tab tab)) (conj (str "tab=" (name tab))))]
              (if (seq parts) (str "?" (str/join "&" parts)) "")))
        dest (fn [id href label]
               [:a {:class (str "rail-link" (when (= id active) " active"))
                    :href (str href (q scope (= id :workstreams)))}
                [:span label]
                (when (= id :needs) (rail-needs-badge needs-count))])
        scope-link (fn [scope-val label]
                     [:a {:class (when (= scope scope-val) "active")
                          :href (str (surface-path active) (q scope-val (= active :workstreams)))}
                      label])]
    [:nav.rail
     [:a.rail-brand {:href "/" :title "nido"}
      [:img.rail-logo {:src "/nido-logo.png" :alt "nido" :width 84 :height 84}]]
     (dest :needs "/" "Needs you")
     (dest :workstreams "/workstreams" "Workstreams")
     [:div.rail-scope
      [:div.meta "Scope"]
      (scope-link "all" "All projects")
      (for [p projects] (scope-link p p))]
     (rail-health daemon)]))

(defn rail-status-fragment
  "The two live rail elements, for SSE patching alongside a surface fragment."
  [{:keys [needs-count daemon]}]
  (str (h/html (rail-needs-badge needs-count))
       (h/html (rail-health daemon))))

(defn fire-signal
  "Signal name for one fire-form input: JS-identifier-safe (hyphens → underscores).
   The /ops/fire route reads the SAME name back out of the signal body — keep the
   two sides on this one fn."
  [trigger-name k]
  (str "fire_" (str/replace (name trigger-name) "-" "_")
       "_" (str/replace (name k) "-" "_")))

(defn ops-panel-fragment
  "The ambient ops chrome: daemon state, halt/resume, open breakers with
   per-trigger clear, and a fire form per manual trigger (placeholder-less →
   one click; placeholder-carrying → one input per {{event/*}} key, signals
   fire_<trigger>_<key>). No route of its own — lives behind the rail dot."
  [{:keys [daemon halt breakers triggers]}]
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
     [:div.card
      [:strong "Breakers"]
      (if (seq breakers)
        (for [{:keys [project trigger]} breakers]
          [:div.actions
           [:span.mono (str (name project) "/" (name trigger))]
           [:button.btn {"data-on:click"
                         (str "@post('/ops/breakers/" (name project) "/" (name trigger) "/clear')")}
            "clear"]])
        [:span.meta "none tripped"])]
     [:div.card
      [:strong "Fire trigger"]
      (for [[project ts] triggers
            {:keys [name payload] :as _t} ts]
        (let [ks (triggers/placeholder-keys (or payload "{}"))]
          [:div.fire-row
           [:span.mono (str (clojure.core/name project) "/" (clojure.core/name name))]
           (for [k ks]
             [:input {"data-bind" (fire-signal name k)
                      :placeholder (clojure.core/name k)}])
           [:button.btn {"data-on:click"
                         (str "@post('/ops/fire/" (clojure.core/name project)
                              "/" (clojure.core/name name) "')")}
            "fire"]]))]])))

(defn shell
  "Page chrome: persistent rail + content area. Replaces `layout`. `ctx` carries
   {:active :title :needs-count :daemon :scope :projects :tab}; `content` is hiccup.
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
        [:style shell-css]]
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
;; Gate inbox (cross-project human-decision queue over nido.work)

(defn origin-badge [origin]
  (let [[ch cls] (case origin
                   :notion ["N" "b-notion"] :github ["G" "b-github"]
                   :slack ["S" "b-slack"]   ["·" "b-scratch"])]
    [:span {:class (str "badge " cls)} ch]))

(defn- chip [stage]
  [:span {:class (str "chip c-" (name stage))} (name stage)])

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
   primary (first) external ref when there is one. The badge stays outside the
   anchor so only the title is clickable."
  [origin label links]
  (let [primary (:url (first links))]
    [:h1 (origin-badge origin) " "
     (if primary
       [:a {:href primary :target "_blank"} label " ↗"]
       label)]))

(defn- screen-query
  "Query string (leading ?) rebuilding the active scope + tab from the screen,
   with optional overrides. `:sel` adds the selection (\"project:ws-id\"); `:tab`
   overrides the tab (used by the tab links). The single place scope, tab and
   selection are serialized — so a row link, a poll refresh, and a deep link all
   carry the identical view-state. The default tab is omitted, keeping
   /workstreams clean."
  [{:keys [scope tab]} & [overrides]]
  (let [tb    (get overrides :tab tab)
        sel   (:sel overrides)
        pairs (cond-> []
                (and scope (not= "all" scope)) (conj (str "scope=" scope))
                (and tb (not= view-state/default-tab tb)) (conj (str "tab=" (name tb)))
                sel (conj (str "sel=" sel)))]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))

;; The workstream pane's READING POSITION — {:project :ws-id :entry :rounds} —
;; and the one expression that navigates to it. Everything the reader can move
;; is in that map: which ledger entry is open (nil = none, the resting state)
;; and which of its review rounds are unfolded. Every affordance in the pane
;; (an index row, a round's fold, the viewer's close, the 3s poll) is the same
;; position with one field changed, so none of them can disagree about where the
;; reader is — and the poll, which re-renders the whole pane, lands them back
;; exactly where they were rather than resetting the fold under their cursor.

(defn- pane-fragment
  "`@get(…)` expression for the pane fragment at reading position `pos`. Omits
   whatever is at rest, so a pane nobody has opened anything in polls the bare
   URL."
  [{:keys [project ws-id entry rounds]}]
  (let [ps (cond-> []
             entry        (conj (str "entry=" entry))
             (seq rounds) (conj (str "rounds=" (str/join "," (sort rounds)))))]
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

(defn- gate-card
  "One inbox row; links to the gate pane. `sel?` highlights the open gate. `href`
   carries the view-state so selecting a gate preserves scope + selection. A
   `:pending?` gate (its agent was resumed / is mid-resolve) shows 'working…'.
   `:error-msg` (a gate action that came back failed) renders on the row — the
   resolve is async, so this is the only trace a failed Apply leaves."
  [{:keys [project origin stage label report session resume-error pending? error-msg]} sel? href]
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
                                            (name (:reason resume-error)))])
   (when error-msg [:div.gate-err "⚠ " error-msg])])

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

(defn- gate-action-fragment
  "Shared shape for the immediate, action-keyed gate-action pane fragment:
   headline `msg` over ws-id + the follow-it links. `pane-id` is the element it
   patches — \"gate-pane\" on the Needs-you page, \"ws-pane\" on the overview."
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

(defn gate-action-confirm-fragment
  "Immediate, action-keyed confirmation toast for an action that actually ran.
   :promote is intentionally destination-neutral: a :ready ticket provisions the
   work session, an :incoming Slack report starts triage."
  ([action-id project ws-id] (gate-action-confirm-fragment action-id project ws-id "gate-pane"))
  ([action-id project ws-id pane-id]
   (gate-action-fragment
    (case action-id
      :promote "Promoting…"
      :apply   "Applying… resuming the agent to write the verdict."
      :dismiss "✓ Dismissed — off your radar. Nothing written to Notion; restore it from the Dismissed band."
      :restore "✓ Restored — back in the triage queue."
      :start-triage "Starting triage… spawning the agent to investigate."
      :drop    "✓ Dropped — not pursued."
      :done    "✓ Marked done."
      :reply   "Resuming… re-hydrating the session if needed, then resuming the conversation."
      "Done.")
    project ws-id pane-id)))

(defn gate-action-skip-fragment
  "Rendered instead of gate-action-confirm-fragment when the gate action did NOT
   actually run — e.g. server/gate-resolve!'s in-flight guard dropped a
   cross-action click. Same shape as the confirm toast, but `msg` is the
   caller-supplied honest copy (server/resolve-failure-msg's :already-in-flight
   sentence) rather than a per-action success claim about something that never
   happened."
  ([msg project ws-id] (gate-action-skip-fragment msg project ws-id "gate-pane"))
  ([msg project ws-id pane-id]
   (gate-action-fragment msg project ws-id pane-id)))

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
  [{:keys [title determination summary confidence design-frame directions
           notion-writes trail]}]
  [:div.md
   (when title [:h2 title])
   [:div.report-meta
    [:span {:class (str "chip c-det-" (name determination))} (name determination)]
    (conf-chip confidence)
    (when-let [layer (:defect-layer design-frame)]
      [:span {:class (str "chip c-layer-" (name layer))} (name layer) " defect"])
    [:span.meta (:reason confidence)]]
   (md/render summary)
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
   `unknowns` is a smell rather than a clean bill of health — a survey that found
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

(defn- merged-card [{:keys [pr url title merged-at]}]
  [:div.md
   [:h2 "Merged"]
   [:p [:strong title] " — " [:a {:href url :target "_blank"} url]]
   [:p [:code pr] (when merged-at (str " · " merged-at))]])

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
  "Terminal review status → chip tone. Only :review-failed is an error; the rest
   of the non-clean statuses stopped short of converging, which is a result, not a
   failure (mirrors nido.review's own reading — see tasks.nido-review/exit-code)."
  {:converged :ok :clean :ok :dry-run :ok :review-failed :bad})

(defn- review-chip [tone text]
  [:span {:class (str "chip c-rv-" (name tone))} text])

(defn- review-glyph
  "Phase status → its mark. Same vocabulary as the terminal render (review/render)
   so a review reads identically in the pane and in the loop that produced it."
  [status]
  (case status "ok" "✓" "error" "✗" "running" "…" "·"))

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
   arbiter's decision, where each fix landed. nil while it is still running.

   `fix-findings` and `commit` are read alongside their replacements so a
   report.json written before layers existed still renders."
  [{:keys [phase findings overall-correctness decision rulings fix-findings
           commit fixes fixed-count dispositions layers status]}]
  (case phase
    "review" (when (= "ok" status)
               (let [skipped (count (filter #(= "skipped" (:status %)) layers))]
                 (str (count findings) " finding" (when (not= 1 (count findings)) "s")
                      (when (pos? skipped) (str " · " skipped " layer"
                                                (when (not= 1 skipped) "s") " skipped"))
                      (when overall-correctness (str " · " overall-correctness)))))
    "warden" (when (= "ok" status)
               (str (count dispositions) " disposition"
                    (when (not= 1 (count dispositions)) "s")))
    ("arbiter" "judge") (when decision
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
   the arbiter's reasoning. Only ever rendered inside an unfolded round, so both
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
   on one line — findings count, the arbiter's decision, the fix's commit — and its
   findings as titles with their severity. That is the shape of the round; a
   converged review is mostly rounds you never need to read past this.

   UNFOLDED it is the phases in full, findings bodies and the arbiter's reasoning
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
   itself carries, then the per-round detail (review · arbiter · fix, each round's
   findings under it) once `:detail` is hydrated. Degrades to the verdict alone
   when the run dir that held report.json is gone.

   Rounds start FOLDED — see review-round — and unfold through `pos`, the pane's
   reading position, so the 3s poll re-renders them exactly as the reader left
   them. A nil `pos` (the gate pane, which has no such position) renders every
   round unfolded: with nothing to click, the detail has to be on the page."
  [{:keys [status base base-rev rounds findings-fixed findings-remaining
           report-path summary detail]} pos]
  (let [cwd    (get-in detail [:target :cwd])
        files  (get-in detail [:target :files])
        rounds* (:rounds detail)
        open   (set (:rounds pos))]
    [:div.md
     [:h2 "Review"]
     [:div.report-meta
      (review-chip (get review-status-tone status :warn) (name status))
      [:span.meta rounds " round" (when (not= 1 rounds) "s")
       " · " findings-fixed " fixed · " findings-remaining " remaining"]]
     [:p.meta "base " base
      (when base-rev (str " @ " (subs base-rev 0 (min 12 (count base-rev)))))
      (when (seq files) (str " · " (count files) " file"
                             (when (not= 1 (count files)) "s") " changed"))]
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
  [{:keys [verdict round reason invariants-held invariants-broken
           load-bearing-held load-bearing-broken findings-classified needs]}]
  [:div.md
   [:h2 "Design verdict"]
   [:div.report-meta
    [:span {:class (str "chip c-verdict-" (name verdict))} (name verdict)]
    [:span.meta "after round " round]]
   (md/render reason)
   (when (seq invariants-held)
     [:div [:h3 "Confirmed this round"]
      (into [:ul] (for [i invariants-held] [:li i]))])
   (when (seq invariants-broken)
     [:div [:h3 "Contradicted"]
      (into [:ul]
            (for [{:keys [invariant finding]} invariants-broken]
              [:li invariant [:div.meta "by: " finding]]))])
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
   (when needs
     [:div [:h3 "Needs a decision"] [:p needs]])])

(defn- record-findings-list
  "Findings from a round over a record. What each cites leads, because that is
   what separates a finding from an opinion here."
  [findings]
  (into [:ul]
        (for [{:keys [cites claim evidence]} findings]
          [:li [:strong (str/join "; " cites)]
           [:div claim]
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
     [:div [:h3 (if (= :underscoped verdict)
                  "What the bound leaves out"
                  "Claims the code does not support")]
      (record-findings-list findings)])
   (when (not= :accurate verdict)
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
     :pr-opened                (pr-opened-card report)
     :merged                   (merged-card report)
     :ship-submitted           (ship-submitted-card report)
     :baseline-review          (baseline-review-card report)
     :design-decision          (design-decision-card report)
     :design-verdict           (design-verdict-card report)
     :review-report            (review-card report pos)
     :findings                 (md/render (report/report->markdown report))
     :proposed-ticket          (md/render (report/report->markdown report))
     (md/render (:markdown report))))))

(defn gate-pane
  "The detail pane: rendered report + follow-actions. nil -> calm placeholder.
   A `:pending?` gate (its agent is running after Apply/Reply) shows 'working…'
   and no action buttons — deriving action availability from the agent's live
   phase, so a poll can't flip it back to a fresh Apply button. `:error-msg` (a
   failed action) renders above the buttons, which stay clickable to retry."
  [{:keys [ws-id project origin label links report actions session pending? error-msg] :as gate}]
  (str
   (h/html
    (if-not gate
      [:div {:id "gate-pane"} [:p.empty "Nothing needs you right now."]]
      [:div {:id "gate-pane"}
       (pane-heading origin label links)
       (links-row links)
       (when report [:div.meta (some-> report :format name) " · " (:at report)])
       (report-body report)
       (if pending?
         [:div.actions {:style "margin-top:16px"} [:span.meta "working… the agent is running."]]
         (list (when error-msg [:div.action-err "⚠ " error-msg])
               (action-bar project ws-id actions session)))]))))

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
   [:winding-down "WindingDown"]
   [:triage      "Triage"]
   [:incoming    "Incoming"]
   [:dismissed   "Dismissed"]])

(defn- ws-fold-signal
  "Collapsed-flag signal name for a stage, e.g. :in-progress → \"wsFoldInProgress\"."
  [stage]
  (str "wsFold" (some (fn [[s suf]] (when (= s stage) suf)) ws-fold-stages)))

(def ^:private ws-fold-storage-key "nidoWsFold")

(def ^:private ws-fold-default-collapsed
  "Bands that start COLLAPSED when localStorage has no opinion. Dismissed is the
   archive of your own vetoes — reachable on purpose, but not something to scroll
   past on every visit."
  #{"Dismissed"})

(def ^:private ws-fold-signals-init
  "data-signals__ifmissing expression for the persistent chrome: seed each stage's
   collapsed flag from localStorage on load. `===true` coerces a missing/false key
   to expanded (the default); a band in ws-fold-default-collapsed inverts that with
   `!==false`. __ifmissing makes the whole thing a no-op once the signals exist — so
   a full-page reload restores state without clobbering it."
  (str "{"
       (str/join ", "
                 (for [[_ suf] ws-fold-stages]
                   (str "wsFold" suf
                        ": (JSON.parse(localStorage.getItem('" ws-fold-storage-key
                        "')||'{}')." suf ")"
                        (if (contains? ws-fold-default-collapsed suf) "!==false" "===true"))))
       "}"))

(def ^:private ws-fold-persist-js
  "JS appended to every section toggle: after a flag flips, write the full fold
   state back to localStorage so both a reload and the 5s poll restore it."
  (str "localStorage.setItem('" ws-fold-storage-key "', JSON.stringify({"
       (str/join ", " (for [[_ suf] ws-fold-stages] (str suf ":$wsFold" suf)))
       "}))"))

(defn- ws-tab-sections
  "Flatten one {:project :grouped} into [{:project :stage :rows}] for `tab`,
   taking the band list + order from work/tab-bands — the single place the
   band→tab mapping lives. Kept as a plain fn (not inline hiccup) so the
   fragment's `for` stays readable."
  [tab {:keys [project grouped]}]
  (for [[stage rows] (work/tab-bands tab grouped)]
    {:project project :stage stage :rows rows}))

(defn- ws-list-row
  "One selectable list row. Its link carries the view-state (scope + selection),
   so selecting a workstream lands on the SAME list. `sel-id` highlights the
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

(defn- winddown-row
  "One winding-down row: closed workstream still holding live sessions. Muted;
   one action. A :pending? row shows 'stopping…' (no re-clickable button); the
   5s poll drops the row once its sessions are down. `q` is the current
   screen's query string (screen-query) so the POST preserves scope + tab
   instead of the fragment's response resetting the view to defaults. An
   :error-msg (from a failed bring-down!) renders inline AND keeps the button —
   clicking retry sets :stopping, which overwrites the :failed entry, so retry
   self-clears the error."
  [{:keys [project ws-id origin label outcome sessions rss-str pending? error-msg]} q]
  [:div.gate-card.winddown
   [:div.gate-top (origin-badge origin) [:span.lbl label]]
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
        {"data-on:click" (str "@post('/workstreams/" project "/" ws-id "/winddown" q "')")}
        "Bring down"]))]])

(defn- dismissed-row
  "One dismissed row: taken off the radar nido-side, with NOTHING written to Notion —
   which is exactly why this band exists. The upstream ticket is still open wherever
   it lives, so hiding these outright would be silent loss. Muted, one action:
   Restore clears the ticket status and puts it back in the triage queue.
   POSTs to the generic pane gate route, so no dedicated endpoint is needed."
  [{:keys [project ws-id origin label last-activity]}]
  [:div.gate-card.dismissed
   [:div.gate-top (origin-badge origin) [:span.lbl label]]
   [:div.gate-sub
    [:span project]
    (when last-activity [:span.meta last-activity])
    [:button.btn
     {"data-on:click" (str "@post('/workstreams/" project "/" ws-id "/gate/restore')")}
     "Restore"]]])

(defn workstreams-fragment
  "The selected tab's stage-grouped selectable list across projects, rendered
   from the screen. Selection is threaded from the screen so a poll refresh keeps
   the open row's highlight instead of clearing it."
  [{:keys [groups selection tab] :as screen}]
  (let [sel-id (:ws-id selection)
        wd-q   (screen-query screen)]
    (str
     (h/html
      [:div {:id "workstreams"}
       ;; The fold signals live on the persistent chrome (see workstreams-page);
       ;; here we only re-bind the click toggle, the reactive fold marker, and the
       ;; row list's data-class reveal. These re-attach to the already-declared
       ;; signals on every 5s poll, which is harmless — the state persists.
       (for [{:keys [project stage rows]} (mapcat #(ws-tab-sections tab %) groups)]
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
                 :winding-down (winddown-row (assoc r :project project) wd-q)
                 :dismissed    (dismissed-row (assoc r :project project))
                 (ws-list-row screen sel-id project r)))]]))]))))

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

(defn- ledger-index
  "The workstream's ledger: every entry, newest first. A row @gets the pane with
   ?entry=<seq>, which OPENS that entry in the viewer below — the ledger itself
   stays put, whole, whether or not something is open. It renders for a
   single-entry ledger too: nothing opens by default now, so the index is the only
   way to reach the one entry there is."
  [pos entries]
  (into [:div.ledger-index]
        (for [{:keys [seq kind at title]} entries]
          [:a {:class (str "ledger-row" (when (= seq (:entry pos)) " sel"))
               "data-on:click" (pane-fragment (at-entry pos seq))}
           [:span.lk (clojure.core/name kind)]
           [:span.meta (day at)]
           [:span.lt title]])))

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

(defn- ledger-browser
  "The ledger, then — only once the reader opens one — the entry they opened.
   `entries` is the newest-first index; `report` is the open entry's report, nil
   when nothing is open."
  [pos entries report]
  [:div
   (when (seq entries) (ledger-index pos entries))
   (when report
     (report-viewer pos (first (filter #(= (:entry pos) (:seq %)) entries)) report))])

(defn- pane-route
  "POST target for the pane-scoped resolve route — responses patch #ws-pane so the
   action stays inside the overview/detail pane rather than the home gate inbox."
  [project ws-id action-id]
  (str "/workstreams/" project "/" ws-id "/gate/" (name action-id)))

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

   `report` is the entry being read, and it is only ever the CURRENT one (callers
   gate on :on-latest?). It reaches gate-actions because a parked :in-progress
   gate showing a design decision offers a different question from one showing
   anything else — and the pane and the gate inbox must not disagree about which."
  [project ws-id origin stage sessions report]
  (let [parked? (boolean (some :parked? sessions))
        session (:name (first (filter :parked? sessions)))]
    (action-bar project ws-id
                (work/gate-actions stage parked? origin
                                   {:report-format (:format report)})
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
  [{:keys [project ws-id origin label stage br-id notion-status error-msg]}]
  (let [actions       (work/gate-actions stage false origin {:bare? true})
        start-triage? (some #(= :start-triage (:id %)) actions)]
    [:div {:id "ws-pane"
           :data-on-interval__duration.3s
           (str "@get('/_fragment/workstream/" project "/" ws-id "')")}
     [:h1 (origin-badge origin) " " label]
     [:p.meta (str/join " · " (keep identity [(name stage) br-id
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
     (action-bar project ws-id actions nil pane-route)]))

(defn workstream-pane
  "Read-only ledger pane: header · stage · ledger summary · report · the one
   ENVIRONMENT block (the workstream's `:environment` session — dev-env controls,
   URL, ports, mem/heap facts), or 'no runnable version yet'. A `:bare?` ws (a
   watched Notion page with no workstream) renders bare-pane-body instead; a
   label-less ws renders the empty placeholder. `session-dev-states`
   is a map of session-name → {:state … :url :error-msg} (the view does no IO).
   `machine-facts` is a map of session-name → {:pg-port :nrepl-port :app-port
   :repl-rss :pg-rss :heap-max} (also no IO — a projection the caller injects).
   `:error-msg` on the ws (a gate action that came back failed) renders above the
   action bar, whose buttons stay clickable to retry.
   Polls its own fragment AT THE READER'S POSITION (`:selected-seq` + `:open-rounds`
   — see pane-fragment), so transient dev-env states (starting…) self-advance
   without the refresh closing whatever the reader has open."
  ([ws session-dev-states] (workstream-pane ws session-dev-states {}))
  ([{:keys [project ws-id origin stage label links ledger report entries selected-seq open-rounds sessions environment on-latest? error-msg bare? br-id notion-status]
     :or {on-latest? true}} session-dev-states machine-facts]
   (let [pos {:project project :ws-id ws-id :entry selected-seq :rounds open-rounds}]
     (str
      (h/html
       (if-not label
         [:div {:id "ws-pane"} [:p.empty "Select a workstream."]]
         (if bare?
           (bare-pane-body {:project project :ws-id ws-id :origin origin :label label
                            :stage stage :br-id br-id :notion-status notion-status
                            :error-msg error-msg})
           [:div {:id "ws-pane" :data-on-interval__duration.3s (pane-fragment pos)}
            (pane-heading origin label links)
            [:p.meta (name stage)]
            (links-row links)
            (when ledger
              [:div.card [:strong "ledger "] (:key ledger) " · " (some-> ledger :status name)
               " · " (:report-count ledger) " report(s)"])
            (ledger-browser pos entries report)
            ;; Live actions only on the current ledger entry — older entries are read-back.
            (when (and on-latest? error-msg)
              [:div.action-err "⚠ " error-msg])
            (when on-latest? (pane-action-bar project ws-id origin stage sessions report))
            (when (= :done stage) (file-findings-form project ws-id))
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

(defn- tab-row
  "The board's two tabs — Intake | Active. A tab selects BANDS, not rows: every
   workstream in the tab renders whatever its origin, so nothing is hidden by
   default. Switching tabs preserves scope + selection."
  [{:keys [tab] :as screen}]
  [:div.tabs
   (for [id view-state/tabs]
     [:a {:class (str "tab" (when (= id tab) " active"))
          :href  (str "/workstreams" (screen-query screen {:tab id}))}
      (str/capitalize (name id))])])

(defn pickup-bar
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

(defn- pickup-blocked-note
  "The warning under a queued pickup, worded for the reason it won't run — or
   nil when it will. Each reason has a different fix, so they can't share copy:
   a down daemon needs starting, a halted one needs resuming, and an open
   breaker needs clearing while the daemon keeps running normally."
  [blocked-by trigger project]
  (when blocked-by
    [:p.meta
     (case blocked-by
       :daemon-down "⚠ daemon is down — queued, but it won't run until the daemon is back up."
       :halted      "⚠ coordinator is halted — queued, but it won't run until you resume it (bb nido:coordinator:resume)."
       :breaker     (str "⚠ " (name trigger) "'s breaker is open — queued, but it won't run "
                         "until it's cleared (bb nido:trigger:enable :project " project
                         " " (name trigger) ").")
       nil)]))

(defn pickup-result-fragment
  "HTML string (root #pickup-result) reporting the outcome of a pickup POST.
   `result` is pickup!'s return; opts is
   {:project <str> :blocked-by <kw or nil> :trigger <kw>}."
  [result {:keys [project blocked-by trigger]}]
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
          "Notion lookup failed — try again.")]
       (let [{:keys [continuing? ws-id ref]} result
             {:keys [id title]} ref]
         [:div
          (if continuing?
            [:p "✓ Continuing " [:strong id] " \"" title "\" → "
             [:a {:href (str "/workstreams/" project "/" ws-id)} "workstream ↗"]
             " (session spinning up…)"]
            [:p "✓ Starting " [:strong id] " \"" title "\" (new workstream) — "
             "it'll appear in the spine shortly."])
          (pickup-blocked-note blocked-by trigger project)]))])))

(defn workstreams-page
  "Overview + ledger pane, rendered from the screen. The list, its poll query,
   and the pane all derive from the one screen value, so overview and detail
   never disagree and a poll preserves the selection + tab."
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
       (tab-row screen)
       [:div.inbox {:data-on-interval__duration.5s (str "@get('/_fragment/workstreams" q "')")}
        (h/raw (workstreams-fragment screen))]]
      [:div.pane (h/raw (workstream-pane (:ws selection) (:dev-states selection) (:machine selection)))]])))

(defn not-found-page []
  (shell {:title "404"} [:h1 "Not found"]))
