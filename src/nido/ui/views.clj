(ns nido.ui.views
  "Hiccup view functions for the nido dashboard."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.process :as process]))

;; ---------------------------------------------------------------------------
;; Layout

(defn- layout [title & body]
  (str
   (h/html
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
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
        .tab-active { color: #e0e0e0; background: #0f0f1e; border-color: #2a2a4a; }"]]
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
                        pg-mode repl-rss pg-rss heap-max]}]
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
        mode-label (case pg-mode
                     :shared "shared"
                     :isolated "iso"
                     nil)
        pg-rss-str (process/human-bytes pg-rss)
        jvm-rss-str (process/human-bytes repl-rss)]
    [:tr
     [:td [:a {:href (str "/" project-name "/sessions/" name "/logs/repl")}
           [:strong name]]]
     [:td (if url
            [:a {:href url :target "_blank"} url]
            [:span.meta "—"])]
     [:td.mono (or pg-port "—")
      (when mode-label [:div.meta mode-label])
      (when (and (= pg-mode :isolated) pg-rss)
        [:div.meta pg-rss-str])]
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
       [:tr [:td {:colspan "7"} [:span.empty "No sessions yet — run `bb nido:session:init <name>`."]]]]))))

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
