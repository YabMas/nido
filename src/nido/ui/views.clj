(ns nido.ui.views
  "Hiccup view functions for the nido dashboard."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]))

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
      [:script {:type "module" :src "https://cdn.jsdelivr.net/npm/@sudodevnull/datastar@1/dist/datastar.min.js"}]
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
        .status-error { background: #3a1a1a; color: #f87171; }
        .status-interrupted { background: #2a1a2a; color: #c084fc; }
        .severity-major { color: #f87171; }
        .severity-minor { color: #facc15; }
        .severity-nitpick { color: #888; }
        .card { background: #16213e; border: 1px solid #2a2a4a; border-radius: 6px;
                padding: 16px; margin: 8px 0; }
        .finding { border-left: 3px solid #2a2a4a; padding: 8px 12px; margin: 6px 0; }
        .finding-major { border-left-color: #f87171; }
        .finding-minor { border-left-color: #facc15; }
        .finding-nitpick { border-left-color: #555; }
        .meta { color: #666; font-size: 12px; }
        .project-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
        .empty { color: #666; font-style: italic; }
        @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.5; } }
        .pulse { animation: pulse 2s ease-in-out infinite; }"]]
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
            [:tr [:td.meta "implementer"] [:td (or (get-in iter [:implementer :session-id]) "—")]]
            [:tr [:td.meta "critic"]
             [:td (or (get-in iter [:critic :session-id]) "—")
              " "
              [:a {:href (str "/" project-name "/vsdd/" run-id "/report/" slug "/" n)}
               "view report"]]]
            [:tr [:td.meta "judge"]
             [:td
              (when (:verdict judge)
                [:span (name (:verdict judge))
                 (when (:structural? judge)
                   " (structural)")])]]
            (when arch
              [:tr [:td.meta "architect"]
               [:td (or (:session-id arch) "—")
                (when (:auto-resolved arch)
                  [:span.meta " (auto-resolved)"])]])]]])
       [:p.empty "No iterations yet."])])))

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
        [:a {:href (str "/" name "/vsdd/")}
         [:div.card
          [:h2 {:style "margin: 0 0 4px"} name]
          [:div.meta (:directory entry)]]])]
     [:p.empty "No projects registered."])))

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

   (let [findings (:findings report)
         by-severity (group-by :severity findings)]
     (if (seq findings)
       [:div
        (for [sev [:major :minor :nitpick]
              :let [items (get by-severity sev)]
              :when (seq items)]
          [:div
           [:h2 (str (name sev) " (" (count items) ")")]
           (for [f items]
             [:div {:class (str "finding finding-" (name sev))}
              [:div [:strong (:rule f)] " "
               (when (:level f)
                 [:span.meta (str "[" (name (:level f)) "]")])]
              (when (:location f)
                [:div.meta (:location f)])
              [:div (:description f)]
              (when (:suggested-fix f)
                [:div {:style "color: #7eb8da; margin-top: 4px"}
                 (:suggested-fix f)])])])]
       [:p.empty "No findings."]))))

(defn not-found-page []
  (layout "404" [:h1 "Not found"]))
