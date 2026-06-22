(ns nido.ui.views
  "Hiccup view functions for the nido dashboard."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.process :as process]
            [nido.ui.markdown :as md]))

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
        .inbox { border-right:1px solid #2a2a4a; overflow:auto; }
        .gate-card { display:block; padding:10px 16px; border-bottom:1px solid #20203a; color:inherit; }
        .gate-card:hover { background:#181830; text-decoration:none; }
        .gate-card.sel { background:#16213e; border-left:3px solid #7eb8da; padding-left:13px; }
        .gate-top { display:flex; align-items:center; gap:8px; }
        .gate-top .lbl { flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:#e8e8e8; }
        .needs { width:7px; height:7px; border-radius:50%; background:#facc15; box-shadow:0 0 6px #facc15; }
        .gate-sub { display:flex; gap:8px; color:#666; font-size:11px; margin:3px 0 0 26px; }
        .gate-prev { color:#7a7a98; font-size:11.5px; margin:5px 0 0 26px;
                     white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .gate-err { color:#f87171; font-size:11px; margin:4px 0 0 26px; }
        .chip { padding:0 7px; border-radius:3px; font-size:10.5px; text-transform:uppercase; }
        .c-triage{ background:#2a2a1a; color:#facc15; } .c-ready{ background:#1a3a2a; color:#4ade80; }
        .c-in-progress{ background:#1a2a3a; color:#7eb8da; }
        .pane { padding:18px 24px; overflow:auto; }
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
     .rail-brand { font-weight:bold; color:#fff; font-size:15px; margin-bottom:14px; }
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
     .dot-down { background:#555; }"))

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
     [:a.rail-brand {:href "/"} "nido"]
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

(defn- gate-card
  "One inbox row; links to the gate pane. `sel?` highlights the open gate."
  [{:keys [ws-id project origin stage label report session resume-error]} sel?]
  [:a {:class (str "gate-card" (when sel? " sel"))
       :href  (str "/gate/" project "/" ws-id)}
   [:div.gate-top (origin-badge origin) [:span.lbl label] [:span.needs {:title "needs you"}]]
   [:div.gate-sub [:span project] (chip stage)
    [:span (if session (str "parked · " session) "decide")]]
   [:div.gate-prev (or (some-> report :markdown
                               (str/replace #"^#.*\n+" "")
                               str/split-lines first)
                       "—")]
   (when resume-error
     [:div.gate-err "⚠ resume failed: " (or (:message resume-error)
                                            (name (:reason resume-error)))])])

(defn needs-fragment
  "The needs-you queue column — initial render + SSE refresh. `sel` is the open ws-id."
  [gates sel]
  (str
   (h/html
    (if (seq gates)
      [:div {:id "needs"} (for [g gates] (gate-card g (= sel (:ws-id g))))]
      [:div {:id "needs"} [:p.empty "Nothing needs you right now."]]))))

(defn gate-action-confirm-fragment
  "Pane confirmation after a gate action: the per-action outcome + follow-links to
   where the item now lives. Patches the pane (#gate-pane). The action runs on a
   background future; this is the immediate, action-keyed feedback."
  [action-id project ws-id]
  (let [msg (case action-id
              :promote "Promoting → in-progress… provisioning the work session."
              :dismiss "✓ Dismissed — off your radar, won't be re-triaged."
              :drop    "✓ Dropped — not pursued."
              :done    "✓ Marked done."
              :reply   "Resuming… re-hydrating the session if needed, then resuming the conversation."
              "Done.")]
    (str
     (h/html
      [:div {:id "gate-pane"}
       [:h1 msg]
       [:p.meta "ws " ws-id]
       [:p "Follow it: "
        [:a {:href (str "/workstreams/" project "/" ws-id)} "open workstream →"]
        " · "
        [:a {:href "/workstreams"} "workstreams →"]]]))))

(defn gate-pane
  "The detail pane: rendered report + follow-actions. nil -> calm placeholder."
  [{:keys [ws-id project origin label report actions session] :as gate}]
  (str
   (h/html
    (if-not gate
      [:div {:id "gate-pane"} [:p.empty "Nothing needs you right now."]]
      [:div {:id "gate-pane"}
       [:h1 (origin-badge origin) " " label]
       (when report [:div.meta (some-> report :kind name) " · " (:at report)])
       (md/render (:markdown report))
       [:div.actions {:style "margin-top:16px"}
        (for [{:keys [id label kind]} actions :when (= kind :mutation)]
          [:button.btn {:class (if (#{:dismiss :drop} id) "btn-danger" "btn-primary")
                        "data-on:click" (str "@post('/gate/" project "/" ws-id "/" (name id) "')")}
           label])]
       (when (some #(= :reply (:kind %)) actions)
         [:div.reply
          [:div.meta {:style "text-transform:uppercase;font-size:11px"} "Reply & resume"]
          [:textarea {"data-bind" "reply" :placeholder "Tell the agent what to do next…"}]
          [:div {:style "margin-top:9px"}
           [:button.btn.btn-primary
            {"data-on:click" (str "@post('/gate/" project "/" ws-id "/reply')")}
            "Send & resume ▸"]
           (when session [:span.meta {:style "margin-left:10px"} "resumes " session])]])]))))

(defn needs-page
  "Home: the needs-you master-detail inside the shell. `ctx` is the rail context."
  [ctx gates sel]
  (let [q (if (= "all" (:scope ctx)) "" (str "?scope=" (:scope ctx)))]
    (shell
     (assoc ctx :active :needs :title "Needs you")
     [:div.gate-wrap
      [:div.inbox {:data-on-interval__duration.3s (str "@get('/_fragment/needs" q "')")}
       (h/raw (needs-fragment gates (:ws-id sel)))]
      [:div.pane (h/raw (gate-pane sel))]])))

;; ---------------------------------------------------------------------------
;; Workstreams (overview + ledger) — replaces the board + ws-detail.

(defn- ws-stage-sections
  "Flatten one {:project :grouped} into [{:project :stage :rows}] in spine order,
   dropping empty stages. Kept as a plain fn (not inline hiccup) for the same
   SCI reason the old stage-sections was."
  [{:keys [project grouped]}]
  (->> [[:inbox (:inbox grouped)]
        [:triage (concat (-> grouped :triage :in-flight) (-> grouped :triage :queued))]
        [:ready (:ready grouped)]
        [:in-progress (:in-progress grouped)]]
       (keep (fn [[stage rows]] (when (seq rows) {:project project :stage stage :rows rows})))))

(defn- ws-list-row [project sel {:keys [ws-id origin label needs-you]}]
  [:a {:class (str "gate-card" (when (= sel ws-id) " sel"))
       :href  (str "/workstreams/" project "/" ws-id)}
   [:div.gate-top (origin-badge origin) [:span.lbl label]
    (when needs-you [:span.needs {:title "needs you"}])]
   [:div.gate-sub [:span project]]])

(defn workstreams-fragment
  "Stage-grouped selectable list across projects. `groups` = [{:project :grouped}]."
  [groups sel]
  (str
   (h/html
    [:div {:id "workstreams"}
     (for [{:keys [project stage rows]} (mapcat ws-stage-sections groups)]
       [:div [:h3 (name stage)]
        (for [r rows] (ws-list-row project sel r))])])))

(defn workstream-pane
  "Read-only ledger pane: header · ledger summary · report markdown · sessions · route-in."
  [{:keys [origin stage label ledger report sessions]} live-url]
  (str
   (h/html
    (if-not label
      [:div {:id "ws-pane"} [:p.empty "Select a workstream."]]
      [:div {:id "ws-pane"}
       [:h1 (origin-badge origin) " " label]
       [:p.meta (name stage)
        (when live-url [:span " · " [:a {:href live-url :target "_blank"} "open session ↗"]])]
       (when ledger
         [:div.card [:strong "ledger "] (:key ledger) " · " (some-> ledger :status name)
          " · " (:report-count ledger) " report(s)"])
       (when (:markdown report) (md/render (:markdown report)))
       [:h2 "Sessions"]
       (if (seq sessions)
         [:table
          [:thead [:tr [:th "session"] [:th "axis"] [:th "status"] [:th "brakes"]]]
          [:tbody
           (for [{:keys [name autonomy-level parked? status brakes]} sessions]
             [:tr [:td name]
              [:td (clojure.core/name autonomy-level) (when parked? " · gate")]
              [:td (clojure.core/name (or status :down))]
              [:td.meta (when brakes (pr-str brakes))]])]]
         [:p.empty "No sessions."])]))))

(defn workstreams-page
  [ctx groups sel-ws live-url]
  (let [q (if (= "all" (:scope ctx)) "" (str "?scope=" (:scope ctx)))]
    (shell
     (assoc ctx :active :workstreams :title "Workstreams")
     [:div.gate-wrap
      [:div.inbox {:data-on-interval__duration.5s (str "@get('/_fragment/workstreams" q "')")}
       (h/raw (workstreams-fragment groups (:ws-id sel-ws)))]
      [:div.pane (h/raw (workstream-pane sel-ws live-url))]])))

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
