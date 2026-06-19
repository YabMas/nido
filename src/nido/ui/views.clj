(ns nido.ui.views
  "Hiccup view functions for the nido dashboard."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.process :as process]
            [nido.ui.markdown :as md]))

;; ---------------------------------------------------------------------------
;; Layout

(defn- layout [title & body]
  (str
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]
      [:title title " — nido"]
      [:script {:type "module" :src "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.1/bundles/datastar.js"}]
      [:style
       "*, *::before, *::after { box-sizing: border-box; }
        body { font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
               font-size: 14px; line-height: 1.6; color: #e0e0e0;
               background: #1a1a2e; margin: 0; padding: 20px 40px; }
        a { color: #7eb8da; text-decoration: none; }
        a:hover { text-decoration: underline; }
        h1 { font-size: 20px; color: #fff; margin: 0 0 8px; }
        h2 { font-size: 16px; color: #ccc; margin: 24px 0 8px; }
        h3 { font-size: 14px; color: #aaa; margin: 16px 0 4px; }
        .breadcrumb { color: #888; margin-bottom: 16px; }
        .breadcrumb a { color: #888; }
        .breadcrumb a:hover { color: #7eb8da; }
        table { border-collapse: collapse; width: 100%; }
        th, td { padding: 6px 12px; text-align: left; border-bottom: 1px solid #2a2a4a; }
        th { color: #888; font-weight: normal; font-size: 12px; text-transform: uppercase; }
        .status { padding: 2px 8px; border-radius: 3px; font-size: 12px; }
        .status-converged { background: #1a3a2a; color: #4ade80; }
        .status-in-progress { background: #2a2a1a; color: #facc15; }
        .status-escalated { background: #3a1a1a; color: #f87171; }
        .status-exhausted { background: #2a2a1a; color: #fb923c; }
        .status-error { background: #3a1a1a; color: #f87171; }
        .status-interrupted { background: #2a1a2a; color: #c084fc; }
        .severity-major { color: #f87171; }
        .severity-minor { color: #facc15; }
        .severity-nitpick { color: #888; }
        .card { background: #16213e; border: 1px solid #2a2a4a; border-radius: 6px;
                padding: 16px; margin: 8px 0; }
        .finding { border-left: 3px solid #2a2a4a; padding: 12px 16px; margin: 12px 0;
                   background: #16213e; border-radius: 0 6px 6px 0; }
        .finding-major { border-left-color: #f87171; }
        .finding-minor { border-left-color: #facc15; }
        .finding-nitpick { border-left-color: #555; }
        .finding-header { display: flex; align-items: baseline; gap: 8px; margin-bottom: 6px; }
        .finding-desc { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
                        font-size: 13.5px; line-height: 1.7; color: #c8c8c8; }
        .finding-fix { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
                       font-size: 13.5px; line-height: 1.7;
                       margin-top: 10px; padding: 10px 12px;
                       background: rgba(126, 184, 218, 0.08); border-radius: 4px;
                       color: #7eb8da; }
        .finding-fix-label { font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                             font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em;
                             color: #5a9ab8; margin-bottom: 2px; }
        .meta { color: #666; font-size: 12px; }
        .project-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
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
        .error-msg a { color: inherit; text-decoration: underline; }
        .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
        .log { background: #0f0f1e; border: 1px solid #2a2a4a; border-radius: 4px;
               padding: 12px 16px; max-height: 70vh; overflow: auto; white-space: pre;
               font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
               font-size: 12px; color: #c8c8c8; }
        .log-empty { color: #666; font-style: italic; }
        .tabs { display: flex; gap: 0; margin: 12px 0 -1px; }
        .tab { padding: 6px 14px; border: 1px solid #2a2a4a; border-bottom: none;
               border-radius: 4px 4px 0 0; color: #888; background: #16213e;
               font-size: 12px; text-decoration: none; }
        .tab-active { color: #e0e0e0; background: #0f0f1e; border-color: #2a2a4a; }
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
                          border-radius:4px; color:#e0e0e0; font:inherit; font-size:13px; padding:9px 11px; }"]]
     [:body body]])))

;; ---------------------------------------------------------------------------
;; Components

(defn- status-badge [status]
  (let [label (name (or status :unknown))
        css (str "status status-" label)
        pulsing? (= status :in-progress)]
    [:span {:class (str css (when pulsing? " pulse"))} label]))

(defn- breadcrumb [& parts]
  [:div.breadcrumb
   (interpose " / " parts)])

(defn- module-slug [module-path]
  (-> (str module-path)
      (str/replace #"/$" "")
      (str/split #"/")
      last))

;; ---------------------------------------------------------------------------
;; Fragments (for SSE updates)

(defn vsdd-runs-table-fragment
  "Just the table body — used for both initial render and SSE updates."
  [project-name runs]
  (str
   (h/html
    (if (seq runs)
      [:tbody {:id "vsdd-runs-body"}
       (for [{:keys [run-id manifest]} runs
             :let [{:keys [module status iterations started-at]} manifest]]
         [:tr
          [:td [:a {:href (str "/" project-name "/vsdd/" run-id)} run-id]]
          [:td module]
          [:td (status-badge status)]
          [:td (count iterations)]
          [:td [:span.meta started-at]]])]
      [:tbody {:id "vsdd-runs-body"}
       [:tr [:td {:colspan "5"} [:span.empty "No VSDD runs found."]]]]))))

(defn vsdd-run-detail-fragment
  "Run detail content — used for both initial render and SSE updates."
  [project-name run-id manifest]
  (str
   (h/html
    [:div {:id "vsdd-run-detail"}
     [:div.card
      [:div [:strong "Module: "] (:module manifest)]
      [:div [:strong "Status: "] (status-badge (:status manifest))]
      [:div [:strong "Started: "] [:span.meta (:started-at manifest)]]
      (when (:finished-at manifest)
        [:div [:strong "Finished: "] [:span.meta (:finished-at manifest)]])
      (when (:error manifest)
        [:div {:style "color: #f87171; margin-top: 8px"} "Error: " (:error manifest)])]

     [:h2 "Iterations"]
     (if (seq (:iterations manifest))
       (for [iter (:iterations manifest)
             :let [n (:iteration iter)
                   judge (:judge iter)
                   arch (:architect iter)
                   slug (module-slug (:module manifest))]]
         [:div.card
          [:h3 (str "Iteration " n)]
          [:table
           [:tbody
            (when (get-in iter [:critic :session-id])
              [:tr [:td.meta "critic"]
               [:td (get-in iter [:critic :session-id])
                " "
                [:a {:href (str "/" project-name "/vsdd/" run-id "/report/" slug "/" n)}
                 "view report"]]])
            (when (:verdict judge)
              [:tr [:td.meta "judge"]
               [:td
                [:span (name (:verdict judge))
                 (when (:structural? judge)
                   " (structural)")]]])
            (when (get-in iter [:implementer :session-id])
              [:tr [:td.meta "implementer"]
               [:td (get-in iter [:implementer :session-id])
                " "
                [:a {:href (str "/" project-name "/vsdd/" run-id "/impl-report/" slug "/" n)}
                 "view report"]]])
            (when arch
              [:tr [:td.meta "architect"]
               [:td (or (:session-id arch) "—")
                (when (:auto-resolved arch)
                  [:span.meta " (auto-resolved)"])]])]]])
       [:p.empty "No iterations yet."])

     (when (seq (:unresolved-spec-findings manifest))
       [:div
        [:h2 "Unresolved Spec Findings"]
        [:p.meta "These spec findings were identified but never routed to the architect."]
        (for [{:keys [rule description severity from-iteration]}
              (:unresolved-spec-findings manifest)]
          [:div.card
           [:div
            (when severity [:span (status-badge severity) " "])
            (when rule [:strong rule " — "])
            description]
           [:div.meta (str "From iteration " from-iteration)]])])])))

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

(defn gate-inbox-fragment
  "The inbox column body — initial render + SSE refresh. `sel` is the open ws-id."
  [gates sel]
  (str
   (h/html
    (if (seq gates)
      [:div {:id "gate-inbox"}
       (for [g gates] (gate-card g (= sel (:ws-id g))))]
      [:div {:id "gate-inbox"} [:p.empty "No gates — nothing needs you right now."]]))))

(defn gate-action-confirm-fragment
  "Pane confirmation after a gate action: the per-action outcome + follow-links to
   where the item now lives. Patches the pane (#gate-pane). The action runs on a
   background future; this is the immediate, action-keyed feedback."
  [action-id project ws-id]
  (let [msg (case action-id
              :promote "Promoting → in-progress… provisioning the work session."
              :skip    "✓ Skipped — dropped, not pursued."
              :drop    "✓ Dropped — not pursued."
              :done    "✓ Marked done."
              :reply   "Resuming… re-hydrating the session if needed, then resuming the conversation."
              "Done.")]
    (str
     (h/html
      [:div {:id "gate-pane"}
       [:div.breadcrumb project " / gate"]
       [:h1 msg]
       [:p.meta "ws " ws-id]
       [:p "Follow it: "
        [:a {:href (str "/ws/" project "/" ws-id)} "open workstream →"]
        " · "
        [:a {:href "/board"} "board →"]]]))))

(defn gate-pane
  "The detail pane: rendered report + follow-actions. nil -> placeholder."
  [{:keys [ws-id project origin stage label report actions session] :as gate}]
  (str
   (h/html
    (if-not gate
      [:div {:id "gate-pane"} [:p.empty "Select a gate."]]
      [:div {:id "gate-pane"}
       [:div.breadcrumb project " / " (name stage)]
       [:h1 (origin-badge origin) " " label]
       (when report
         [:div.meta (some-> report :kind name) " · " (:at report)])
       ;; md/render already returns a [:div.md …]; embed it directly (no double wrap)
       (md/render (:markdown report))
       [:div.actions {:style "margin-top:16px"}
        (for [{:keys [id label kind]} actions
              :when (= kind :mutation)]
          [:button.btn {:class (if (#{:skip :drop} id) "btn-danger" "btn-primary")
                        "data-on:click" (str "@post('/gate/" project "/" ws-id "/" (name id) "')")}
           label])]
       (when (some #(= :reply (:kind %)) actions)
         [:div.reply
          [:div.meta {:style "text-transform:uppercase;font-size:11px"} "Reply & resume"]
          [:textarea {"data-bind" "reply"
                      :placeholder "Tell the agent what to do next…"}]
          [:div {:style "margin-top:9px"}
           [:button.btn.btn-primary
            {"data-on:click" (str "@post('/gate/" project "/" ws-id "/reply')")}
            "Send & resume ▸"]
           (when session [:span.meta {:style "margin-left:10px"} "resumes " session])]])]))))

(defn gate-inbox-page
  "Dashboard home: cross-project Gate Inbox (master-detail). `sel` is the open
   gate map (or nil). The inbox column polls; the pane is server-rendered for the
   selected gate (deep-linked via /gate/:project/:ws-id)."
  [gates sel]
  (layout
   "gates"
   [:h1 "nido — gates"]
   [:p.meta [:a {:href "/board"} "board →"] " · " [:a {:href "/system"} "system →"]]
   [:div.gate-wrap
    [:div.inbox {:data-on-interval__duration.3s "@get('/_fragment/gates')"}
     (h/raw (gate-inbox-fragment gates (:ws-id sel)))]
    [:div.pane (h/raw (gate-pane sel))]]))

;; ---------------------------------------------------------------------------
;; Spine board (reflect-only, stage-grouped view across all projects)

(defn- spine-board-row [project {:keys [ws-id origin label needs-you]}]
  [:tr
   [:td (origin-badge origin)]
   [:td [:a {:href (str "/ws/" project "/" ws-id)} label]]
   [:td (when needs-you [:span.needs {:title "needs you"}])]])

(defn- stage-sections
  "Flatten a {:project :grouped} entry into [{:project :stage :rows} …] in spine
   order, dropping empty stages. Pulled out of the hiccup form so hiccup2's
   compiler never walks the literal stage/rows pair vector (under SCI that
   misreads `[:triage …]` as an element tag)."
  [{:keys [project grouped]}]
  (->> [[:triage (concat (-> grouped :triage :in-flight) (-> grouped :triage :queued))]
        [:ready (:ready grouped)]
        [:in-progress (:in-progress grouped)]]
       (keep (fn [[stage rows]]
               (when (seq rows) {:project project :stage stage :rows rows})))))

(defn board-fragment
  "Stage-grouped reflect board across all projects. `groups` is a seq of
   {:project :grouped} (grouped = work/grouped output)."
  [groups]
  (str
   (h/html
    [:div {:id "board"}
     (for [{:keys [project stage rows]} (mapcat stage-sections groups)]
       [:div [:h3 (name stage) " — " project]
        [:table [:tbody (for [r rows] (spine-board-row project r))]]])])))

(defn board-page [groups]
  (layout "board"
   [:h1 "nido — board"]
   [:p.meta [:a {:href "/"} "← gates"] " · " [:a {:href "/system"} "system →"]]
   [:div {:data-on-interval__duration.5s "@get('/_fragment/board')"}
    (h/raw (board-fragment groups))]))

;; ---------------------------------------------------------------------------
;; Workstream detail (read-only: reflect + route-in)

(defn ws-detail-page
  "Read-only workstream detail: origin · stage · ledger · sessions on the autonomy
   axis, plus a route-in link when a live session url is known. Mutations live on
   the gate inbox; this surface reflects + routes in (see spec)."
  [{:keys [origin stage label ledger sessions]} live-url]
  (layout
   (str label " — workstream")
   (breadcrumb [:a {:href "/"} "gates"] [:a {:href "/board"} "board"] label)
   [:h1 (origin-badge origin) " " label]
   [:p.meta (name stage)
    (when live-url [:span " · " [:a {:href live-url :target "_blank"} "open session ↗"]])]
   (when ledger
     [:div.card [:strong "ledger "] (:key ledger) " · " (some-> ledger :status name)
      " · " (:report-count ledger) " report(s)"])
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
     [:p.empty "No sessions."])))

;; ---------------------------------------------------------------------------
;; Pages

(defn home-page
  "Landing page — list of registered projects."
  [projects]
  (layout
   "nido"
   [:h1 "nido"]
   (if (seq projects)
     [:div.project-grid
      (for [[name entry] (sort-by key projects)]
        [:div.card
         [:h2 {:style "margin: 0 0 4px"} name]
         [:div.meta (:directory entry)]
         [:div {:style "margin-top: 10px; display: flex; gap: 12px"}
          [:a {:href (str "/" name "/sessions")} "sessions"]
          [:a {:href (str "/" name "/vsdd/")} "vsdd runs"]]])]
     [:p.empty "No projects registered."])))

;; ---------------------------------------------------------------------------
;; Sessions view

(defn- session-row
  "One table row for a session. pending-state is either a plain keyword
   (for transient or terminal states the port check alone can't express)
   or a map `{:state :failed :error-msg \"...\"}`. Either way it's layered
   on top of the TCP-derived state so actions in flight and terminal
   failures show through."
  [project-name {:keys [name entry live? pending-state
                        repl-rss pg-rss heap-max]}]
  (let [app-port (:app-port entry)
        pg-port (:pg-port entry)
        repl-port (:nrepl-port entry)
        url (:url entry)
        pending-kw (cond
                     (map? pending-state)     (:state pending-state)
                     (keyword? pending-state) pending-state)
        pending-err (when (map? pending-state) (:error-msg pending-state))
        state (cond
                live?      :running
                pending-kw pending-kw
                (not entry) :dormant
                :else      :idle)
        state-class (str "app-state app-state-" (clojure.core/name state))
        action-base (str "/" project-name "/sessions/" name)
        transient? (#{:starting :stopping :restarting} state)
        failed?    (= state :failed)
        pg-rss-str (process/human-bytes pg-rss)
        jvm-rss-str (process/human-bytes repl-rss)]
    [:tr
     [:td [:a {:href (str "/" project-name "/sessions/" name "/logs/repl")}
           [:strong name]]]
     [:td (if url
            [:a {:href url :target "_blank"} url]
            [:span.meta "—"])]
     [:td.mono (or pg-port "—")
      (when pg-rss [:div.meta pg-rss-str])]
     [:td.mono (or repl-port "—")
      (when repl-rss [:div.meta jvm-rss-str])
      (when heap-max [:div.meta (str "max " heap-max)])]
     [:td.mono (or app-port "—")]
     [:td [:span {:class state-class
                  :title (or pending-err "")} (clojure.core/name state)]
      (when (and failed? pending-err)
        [:div.error-msg
         [:a {:href (str "/" project-name "/sessions/" name "/logs/eval")}
          pending-err]])]
     [:td [:div.actions
           (cond
             transient? [:span.meta "working…"]
             failed?
             [:button.btn.btn-primary {"data-on:click" (str "@post('" action-base "/restart')")}
              "retry"]
             (= state :dormant)
             [:button.btn.btn-primary {"data-on:click" (str "@post('" action-base "/start')")}
              "start"])
           (when (and entry (not transient?))
             [:button.btn {"data-on:click" (str "@post('" action-base "/restart')")} "restart"])
           (when (and entry (not transient?))
             [:button.btn {"data-on:click" (str "@post('" action-base "/stop')")} "stop"])]]]))

(defn sessions-table-fragment
  "Just the table body — used for initial render and SSE refresh. Each row
   carries its own :pending-state (sourced from the server-side app-states
   atom) so transient and terminal states persist across polling cycles."
  [project-name rows]
  (str
   (h/html
    (if (seq rows)
      [:tbody {:id "sessions-body"}
       (for [row rows]
         (session-row project-name row))]
      [:tbody {:id "sessions-body"}
       [:tr [:td {:colspan "7"} [:span.empty "No sessions yet — run `bb nido:session:up <name>`."]]]]))))

(defn log-tail-fragment
  "Just the log content div — for SSE refresh."
  [content]
  (str
   (h/html
    [:div {:id "log-content"}
     (if (clojure.string/blank? content)
       [:div.log [:span.log-empty "(log is empty or not yet created)"]]
       [:pre.log content])])))

(defn session-log-page
  "Tail view of a per-session log file (repl or pg). Auto-refreshes every 2s."
  [project-name session-name service content]
  (let [base (str "/" project-name "/sessions/" session-name "/logs")
        fragment-url (str base "/" service "/_fragment")
        tab (fn [svc label]
              [:a {:class (str "tab" (when (= svc service) " tab-active"))
                   :href (str base "/" svc)}
               label])]
    (layout
     (str session-name " — " service " log")
     (breadcrumb [:a {:href "/"} "nido"]
                 project-name
                 [:a {:href (str "/" project-name "/sessions")} "sessions"]
                 session-name
                 "logs")
     [:h1 (str session-name " — logs")]
     [:div.tabs
      (tab "repl" "repl (app + nREPL stdout)")
      (tab "eval" "eval (mount/start output)")
      (tab "pg" "postgres")]
     [:div {:data-on-interval__duration.2s (str "@get('" fragment-url "')")}
      (h/raw (log-tail-fragment content))])))

(defn sessions-page
  "Sessions list for a project."
  [project-name worktrees-dir rows]
  (layout
   (str project-name " — sessions")
   (breadcrumb [:a {:href "/"} "nido"]
               project-name
               "sessions"
               [:a {:href (str "/" project-name "/vsdd/")} "vsdd"])
   [:h1 (str project-name " — Sessions")]
   [:p.meta "Worktrees under " [:span.mono worktrees-dir]]
   [:div {:data-on-interval__duration.3s (str "@get('/" project-name "/sessions/_fragment/list')")}
    [:table
     [:thead
      [:tr [:th "session"] [:th "url"] [:th "pg"] [:th "repl"] [:th "app"] [:th "app-state"] [:th "actions"]]]
     (h/raw (sessions-table-fragment project-name rows))]]))

;; ---------------------------------------------------------------------------
;; Live-sessions board (dashboard home)

(defn- board-row
  "One board row: project · session · status · dev URL. Live sessions get the
   clickable friendly-host link (`:url` in the registry entry — the per-session
   friendly host, opened in a new tab → own cookie jar). Down sessions get a
   real start button that POSTs the existing lifecycle action; the board's 3s
   poll then reflects the `starting…` state (set in the server's app-states
   atom) and finally the live URL. pending-state mirrors session-row so an
   in-flight start/stop shows through between polls."
  [{:keys [project name live? entry pending-state]}]
  (let [url         (:url entry)
        pending-kw  (cond
                      (map? pending-state)     (:state pending-state)
                      (keyword? pending-state) pending-state)
        action-base (str "/" project "/sessions/" name)]
    [:tr
     [:td.mono project]
     [:td [:a {:href (str action-base "/logs/repl")} [:strong name]]]
     [:td (cond
            live?                                  [:span {:style "color:#4ade80"} "● up"]
            (#{:starting :restarting} pending-kw)  [:span.meta "… starting"]
            (= :stopping pending-kw)               [:span.meta "… stopping"]
            :else                                  [:span.meta "○ down"])]
     [:td (cond
            (and live? url)                        [:a {:href url :target "_blank"} url]
            live?                                  [:span.meta "—"]
            (#{:starting :restarting} pending-kw)  [:span.meta "working…"]
            :else
            [:button.btn.btn-primary
             {"data-on:click" (str "@post('" action-base "/start')")}
             "start"])]]))

(defn live-board-fragment
  "Just the board tbody — initial render + SSE refresh."
  [rows]
  (str
   (h/html
    (if (seq rows)
      [:tbody {:id "board-body"}
       (for [row rows] (board-row row))]
      [:tbody {:id "board-body"}
       [:tr [:td {:colspan "4"} [:span.empty "No sessions yet — run `bb nido:session:up <name>`."]]]]))))

(defn live-board-page
  "Dashboard home: a flat, live-first board of every session across projects."
  [rows]
  (layout
   "live sessions"
   [:h1 "nido — live sessions"]
   [:p.meta [:a {:href "/"} "← gates"] " · " [:a {:href "/board"} "board"] " · " [:a {:href "/projects"} "all projects →"]]
   [:div {:data-on-interval__duration.3s "@get('/_fragment/live')"}
    [:table
     [:thead
      [:tr [:th "project"] [:th "session"] [:th "status"] [:th "dev url"]]]
     (h/raw (live-board-fragment rows))]]))

(defn vsdd-runs-page
  "VSDD runs list for a project."
  [project-name runs has-in-progress?]
  (layout
   (str project-name " — vsdd")
   (breadcrumb [:a {:href "/"} "nido"]
               project-name
               "vsdd")
   [:h1 (str project-name " — VSDD Runs")]
   [:div (when has-in-progress?
           {:data-on-interval__duration.3s (str "@get('/" project-name "/vsdd/_fragment/runs')")})
    [:table
     [:thead
      [:tr [:th "run"] [:th "module"] [:th "status"] [:th "iterations"] [:th "started"]]]
     (h/raw (vsdd-runs-table-fragment project-name runs))]]))

(defn vsdd-run-detail-page
  "Detail page for a single VSDD run."
  [project-name run-id manifest]
  (let [in-progress? (= (:status manifest) :in-progress)]
    (layout
     (str run-id " — vsdd")
     (breadcrumb [:a {:href "/"} "nido"]
                 [:a {:href (str "/" project-name "/vsdd/")} project-name]
                 run-id)
     [:h1 (str "Run " run-id)]
     [:div (when in-progress?
             {:data-on-interval__duration.2s
              (str "@get('/" project-name "/vsdd/" run-id "/_fragment/detail')")})
      (h/raw (vsdd-run-detail-fragment project-name run-id manifest))])))

(defn vsdd-report-page
  "Critic report detail page."
  [project-name run-id _module-slug iteration report]
  (layout
   (str "report " iteration " — " run-id)
   (breadcrumb [:a {:href "/"} "nido"]
               [:a {:href (str "/" project-name "/vsdd/")} project-name]
               [:a {:href (str "/" project-name "/vsdd/" run-id)} run-id]
               (str "report " iteration))
   [:h1 (str "Critic Report — Iteration " iteration)]
   [:div.card
    [:div [:strong "Module: "] (:module report)]
    [:div [:strong "Verdict: "] (status-badge (:verdict report))]]

   (let [impl-findings (:findings-for-impl report)
         spec-findings (:findings-for-spec report)
         render-findings
         (fn [findings]
           (let [by-severity (group-by :severity findings)]
             (for [sev [:major :minor :nitpick]
                   :let [items (get by-severity sev)]
                   :when (seq items)]
               [:div
                [:h2 (str (name sev) " (" (count items) ")")]
                (for [f items]
                  [:div {:class (str "finding finding-" (name sev))}
                   [:div.finding-header
                    [:strong (:rule f)]
                    (when (:level f)
                      [:span.meta (str "[" (name (:level f)) "]")])]
                   (when (:location f)
                     [:div.meta {:style "margin-bottom: 8px"} (:location f)])
                   [:div.finding-desc (:description f)]
                   (when (:suggested-fix f)
                     [:div.finding-fix
                      [:div.finding-fix-label "suggested fix"]
                      (:suggested-fix f)])])])))]
     [:div
      [:h2.section-heading "Implementation Findings"]
      (if (seq impl-findings)
        (render-findings impl-findings)
        [:p.empty "No implementation findings."])
      [:h2.section-heading "Spec Findings"]
      (if (seq spec-findings)
        (render-findings spec-findings)
        [:p.empty "No spec findings."])])))

(defn vsdd-impl-report-page
  "Implementer completion report page."
  [project-name run-id _module-slug iteration report]
  (layout
   (str "impl report " iteration " — " run-id)
   (breadcrumb [:a {:href "/"} "nido"]
               [:a {:href (str "/" project-name "/vsdd/")} project-name]
               [:a {:href (str "/" project-name "/vsdd/" run-id)} run-id]
               (str "impl report " iteration))
   [:h1 (str "Implementer Report — Iteration " iteration)]

   (when-let [findings (:findings-addressed report)]
     [:div.card
      [:strong "Findings addressed: "]
      (str/join ", " (map str findings))])

   (let [files (:files-modified report)]
     (if (seq files)
       [:div
        [:h2 (str "Files modified (" (count files) ")")]
        (for [f files]
          [:div.card
           [:div [:strong (:path f)]]
           (when (seq (:changes f))
             [:ul
              (for [c (:changes f)]
                [:li c])])])]
       [:p.empty "No files listed."]))))

(defn not-found-page []
  (layout "404" [:h1 "Not found"]))
