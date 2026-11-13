(ns nido.ui.catalog
  "The component catalog: GET /_catalog.

   A sidebar of the dashboard's block vocabularies, every scene of the selected one rendered in
   the app's own stylesheet with Datastar live, and the two axes these blocks vary over in the
   app — which surface a block sits on, and how wide its column is.

   HIDES the catalog's own chrome: how a scene is framed, labelled and selected, and the three
   query-string names it reads its selection from.

   It holds no copy of anything it shows. A scene is rendered by calling the same function the
   dashboard calls, in the stylesheet `nido.ui.stylesheet` holds, so a scene that looks wrong is
   the block being wrong rather than this page disagreeing with the app.

   It parses its own query string instead of going through `nido.ui.view-state`, and that is the
   one place in this band where a URL name is not repeated on two sides: `params` below is read
   both by the parser and by every link this page writes, so there is nothing to rename twice.
   The rest of the dashboard shares its query-string names by literal across five namespaces,
   which is the design's `url-params-shared-by-literal`.

   Not linked from anywhere in the product. It is served by the one dashboard handler on every
   dashboard — a flag that decided otherwise would make the dashboard behave differently
   depending on how it was started, which is worse than a route a person reaches by typing it —
   and it reads nothing: sample data, and its own query string."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.ui.catalog.scenes :as scenes]
            [nido.ui.datastar :as datastar]
            [nido.ui.stylesheet :as css]))

;; ---------------------------------------------------------------------------
;; The selection, and the one table that names it

(def ^:private chrome-css
  "The catalog's own chrome: its sidebar, its scene frames and its selectors. Here rather than in
   `nido.ui.stylesheet`, because that module holds the class rules the dashboard's pages and
   blocks name, and nothing in the dashboard names any of these.

   It declares no custom property and writes no colour or size of its own: every value is a token
   `nido.ui.stylesheet` declares, which is what keeps `one-token-block` true of this file too.
   The scene frame's three backgrounds ARE the three the blocks sit on in the app, which is what
   makes the surface selector a reading of the app rather than a palette of its own.

   The stylesheet makes `body` the dashboard shell's grid — a 180px rail, then the content — and
   the catalog has no rail, so `.cat` spans the whole of it; in the rail's cell alone its main
   column was left a word wide."
  "        .cat { grid-column:1 / -1; display:grid; grid-template-columns: 220px 1fr; min-height:100vh; }
        .cat-side { border-right:1px solid var(--border); padding:16px 0; min-width:0; }
        .cat-side h1 { margin:0 16px 4px; }
        .cat-side .cat-sub { margin:0 16px 14px; color:var(--text-faint); font-size:var(--fs-xs); }
        .cat-group { display:block; padding:5px 16px; color:var(--text-muted); font-size:var(--fs-sm); }
        .cat-group:hover { background:var(--surface-hover); text-decoration:none; }
        .cat-group.on { background:var(--surface-selected); color:var(--text-strong); }
        .cat-main { padding:18px 24px 60px; min-width:0; }
        .cat-bar { display:flex; gap:22px; align-items:baseline; flex-wrap:wrap;
                   padding-bottom:14px; border-bottom:1px solid var(--border-subtle);
                   margin-bottom:20px; }
        .cat-axis { display:flex; gap:6px; align-items:baseline; }
        .cat-k { color:var(--text-faint); font-size:var(--fs-xs); text-transform:uppercase;
                 letter-spacing:0.04em; }
        .cat-opt { font-size:var(--fs-xs); padding:2px 8px; border:1px solid var(--border);
                   border-radius:3px; color:var(--text-muted); }
        .cat-opt:hover { background:var(--surface-hover); text-decoration:none; }
        .cat-opt.on { background:var(--accent-bg); border-color:var(--accent-border);
                      color:var(--accent-strong); }
        .cat-scene { margin-bottom:30px; }
        .cat-scene-head { display:flex; gap:10px; align-items:baseline; margin-bottom:4px; }
        .cat-scene-t { color:var(--text-strong); font-size:var(--fs-sm); }
        .cat-scene-fn { font-family:var(--font-mono); font-size:var(--fs-xs);
                        color:var(--text-faint); }
        .cat-scene-n { color:var(--text-muted); font-size:var(--fs-xs); max-width:var(--measure);
                       margin:0 0 9px; }
        .cat-frame { border:1px dashed var(--border-strong); border-radius:4px; padding:12px; }
        .cat-frame.s-page { background:var(--bg); }
        .cat-frame.s-card { background:var(--surface); }
        .cat-frame.s-sunken { background:var(--surface-sunken); }
        .cat-frame.w-narrow { max-width:38vw; }
        .cat-frame.w-pane { max-width:62vw; }
        .cat-ret { font-family:var(--font-mono); font-size:var(--fs-sm);
                   color:var(--accent-strong); }
        .cat-empty { color:var(--text-faint); font-style:italic; }
        .cat-rail { display:flex; flex-wrap:wrap; gap:18px; align-items:flex-start; }
        .cat-rail-i { display:flex; flex-direction:column; gap:4px; align-items:flex-start; }
        .cat-pair { display:flex; gap:14px; align-items:baseline; }
        .cat-pair .cat-scene-fn { min-width:190px; }
")

(def ^:private params
  "The query-string names this page reads its selection from. Read by `select` below and by every
   link the page writes, so neither side can be renamed without the other."
  {:block "block" :surface "surface" :width "width"})

(def ^:private surfaces
  "Which background a scene is framed on — the three the dashboard's blocks actually sit on. A
   chip whose tint matches the surface under it disappears, and this is the axis that shows it.

   NOT a light/dark toggle: the dashboard declares one palette and nothing selects a second, so
   there is no second theme to switch to. This switches which of the one palette's surfaces the
   block is seen against."
  [["page" "page" "s-page"]
   ["card" "card" "s-card"]
   ["pane" "sunken pane" "s-sunken"]])

(def ^:private widths
  "How wide the scene's column is. The two narrow ones are the shell's own grid — 38% for the
   queue column and 62% for the pane — taken against the viewport rather than the content area,
   so they are close rather than exact. A row that only reads well at full width reads badly
   where it actually lives."
  [["pane" "≈ pane" "w-pane"]
   ["narrow" "≈ queue" "w-narrow"]
   ["full" "full" ""]])

(defn- query-map
  "`query-string` as a map of name to its first value. Nothing is decoded beyond + and %XX,
   because every value this page reads is one of a fixed set and is matched rather than used."
  [query-string]
  (into {}
        (for [pair (str/split (or query-string "") #"&")
              :when (str/includes? pair "=")
              :let [[k v] (str/split pair #"=" 2)]
              :when (not (str/blank? k))]
          [k (java.net.URLDecoder/decode (str v) "UTF-8")])))

(defn- select
  "What the reader has selected: the group, and one option of each axis. An unknown or absent
   value takes the axis's first option, so a hand-written URL cannot produce a page that renders
   nothing."
  [query-string groups]
  (let [q     (query-map query-string)
        pick  (fn [axis k] (let [want (get q (get params k))]
                             (or (some #(when (= want (first %)) %) axis) (first axis))))
        group (let [want (get q (:block params))]
                (or (some #(when (= want (:id %)) %) groups) (first groups)))]
    {:group group :surface (pick surfaces :surface) :width (pick widths :width)}))

(defn- href
  "A link to this page with one field of the selection changed."
  [{:keys [group surface width]} k v]
  (let [cur {:block (:id group) :surface (first surface) :width (first width)}]
    (str "/_catalog?"
         (str/join "&" (for [[field name'] params]
                         (str name' "=" (if (= field k) v (get cur field))))))))

;; ---------------------------------------------------------------------------
;; The page

(defn- axis
  "One selector: its label, and one link per option with the current one marked."
  [sel label k options]
  [:div.cat-axis
   [:span.cat-k label]
   (for [[value shown _] options]
     [:a {:class (str "cat-opt" (when (= value (first (get sel k))) " on"))
          :href  (href sel k value)}
      shown])])

(defn- scene-block
  "One block's scenes, each framed on the selected surface at the selected width. The frame is
   dashed so its edge is never mistaken for the block's own border — the question a reviewer is
   asking is where the block's edges are."
  [{:keys [surface width]} {:keys [name note scenes]}]
  (list
   [:div.cat-scene-head [:span.cat-scene-t name] [:span.cat-scene-fn (str "(" name " …)")]]
   (when note [:p.cat-scene-n note])
   (for [{:keys [title render] :as scene} scenes]
     [:div.cat-scene
      [:div.cat-scene-head [:span.cat-scene-fn "scene"] [:span.cat-scene-t title]]
      (when-let [n (:note scene)] [:p.cat-scene-n n])
      [:div {:class (str "cat-frame " (nth surface 2) " " (nth width 2))}
       (render)]])))

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] :string]}
  catalog-page
  "The catalog, from its own query string. Emits the app's stylesheet and loads Datastar from the
   one place a dashboard page loads it from, so a block's `data-*` attributes behave here exactly
   as they do on a real page: the question card's stepper steps, and its choices mark themselves
   answered, because those are page state rather than a server round trip. Every POST a scene
   renders goes to /_catalog/nowhere and changes nothing."
  [query-string]
  (let [groups (scenes/groups)
        sel    (select query-string groups)
        group  (:group sel)]
    (str
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]
        [:title (:title group) " — nido components"]
        (datastar/datastar-script)
        [:style (h/raw (css/stylesheet))]
        [:style (h/raw chrome-css)]]
       [:body
        [:div.cat
         [:nav.cat-side
          [:h1 "Components"]
          [:p.cat-sub "the dashboard's blocks, over sample data"]
          (for [g groups]
            [:a {:class (str "cat-group" (when (= (:id g) (:id group)) " on"))
                 :href  (href sel :block (:id g))}
             (:title g)])]
         [:main.cat-main
          [:div.cat-bar
           (axis sel "surface" :surface surfaces)
           (axis sel "width" :width widths)]
          [:h2 (:title group)]
          (when-let [n (:note group)] [:p.cat-scene-n n])
          (map #(scene-block sel %) (:blocks group))]]]]))))
