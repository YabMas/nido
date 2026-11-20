(ns nido.libraries.ui.catalog-page
  "The component catalog over an app's registry.

   A registry is a map:
     :title     what the catalog is of
     :groups    [{:id :title :note :blocks [{:name :note :scenes [{:title :note :render}]}]}],
                each scene's :render a thunk returning hiccup — the app's own call of the block
     :surfaces  [{:id :label :background}] the backgrounds the app's blocks sit on, :background a
                CSS value in the app's own vocabulary (var(--surface)); the first is the default

   HIDES the catalog's chrome — how a scene is framed, labelled and selected, the rules it is
   drawn with, the query-string names it reads its selection from, and the element picker an
   embedding page drives. Its chrome reads none of the app's tokens: it is drawn in system
   colours, so the only thing on the page in the app's visual language is what the app renders.
   Every link and request it writes is relative, so it runs unchanged under any prefix an app
   mounts it at."
  (:require [clojure.string :as str]
            [hiccup2.core :as h]
            [nido.libraries.ui.wire :as wire]))

(def ^:private chrome-css
  ".cat { grid-column:1 / -1; display:grid; grid-template-columns: 220px 1fr; min-height:100vh; }
.cat-side { border-right:1px solid color-mix(in srgb, CanvasText 18%, transparent); padding:16px 0; min-width:0; }
.cat-side h1 { margin:0 16px 4px; font-size:1.1em; }
.cat-sub { margin:0 16px 14px; opacity:.6; font-size:.8em; }
.cat-group { display:block; padding:5px 16px; opacity:.75; font-size:.9em; color:inherit; text-decoration:none; }
.cat-group:hover { background:color-mix(in srgb, CanvasText 8%, transparent); }
.cat-group.on { opacity:1; background:color-mix(in srgb, CanvasText 12%, transparent); }
.cat-main { padding:18px 24px 60px; min-width:0; }
.cat-bar { display:flex; gap:22px; align-items:baseline; flex-wrap:wrap; padding-bottom:14px;
           border-bottom:1px solid color-mix(in srgb, CanvasText 12%, transparent); margin-bottom:20px; }
.cat-axis { display:flex; gap:6px; align-items:baseline; }
.cat-k { opacity:.6; font-size:.75em; text-transform:uppercase; letter-spacing:0.04em; }
.cat-opt { font-size:.75em; padding:2px 8px; border:1px solid color-mix(in srgb, CanvasText 25%, transparent);
           border-radius:3px; color:inherit; opacity:.75; text-decoration:none; }
.cat-opt.on { opacity:1; border-color:currentColor; }
.cat-scene { margin-bottom:30px; }
.cat-scene-head { display:flex; gap:10px; align-items:baseline; margin-bottom:4px; }
.cat-scene-t { font-size:.9em; font-weight:600; }
.cat-scene-fn { font-family:ui-monospace, monospace; font-size:.75em; opacity:.6; }
.cat-scene-n { opacity:.7; font-size:.8em; max-width:72ch; margin:0 0 9px; }
.cat-frame { border:1px dashed color-mix(in srgb, CanvasText 35%, transparent); border-radius:4px; padding:12px; }
.cat-frame.w-narrow { max-width:38vw; }
.cat-frame.w-pane { max-width:62vw; }
.cat-picking .cat-frame * { cursor:crosshair; }
.cat-pick-hover { outline:2px dashed Highlight; outline-offset:1px; }
.cat-pick-mark { outline:2px solid Highlight; outline-offset:1px; position:relative; }
.cat-pick-mark::after { content:attr(data-pick-n); position:absolute; top:-9px; left:-9px; z-index:1;
                        min-width:16px; height:16px; border-radius:8px; padding:0 3px;
                        background:Highlight; color:HighlightText; font:11px/16px ui-monospace, monospace;
                        text-align:center; pointer-events:none; }")

(def ^:private params
  "The query-string names the selection is read from, read by `select` and written by every link,
   so neither side can be renamed without the other."
  {:block "block" :surface "surface" :width "width" :embed "embed"})

(def ^:private widths
  "How wide a scene's column is: a pane, a narrow column, or the whole page — a block that only
   reads well at full width reads badly where it actually lives."
  [{:id "pane" :label "≈ pane" :class "w-pane"}
   {:id "narrow" :label "≈ queue" :class "w-narrow"}
   {:id "full" :label "full" :class ""}])

(defn- query-map [query-string]
  (into {}
        (for [pair (str/split (or query-string "") #"&")
              :when (str/includes? pair "=")
              :let [[k v] (str/split pair #"=" 2)]
              :when (not (str/blank? k))]
          [k (java.net.URLDecoder/decode (str v) "UTF-8")])))

(defn- select
  "The selection: a group, a surface and a width. An unknown or absent value takes the first
   option, so a hand-written URL cannot produce a page that renders nothing."
  [query-string {:keys [groups surfaces]}]
  (let [q    (query-map query-string)
        pick (fn [options k] (let [want (get q (get params k))]
                               (or (some #(when (= want (:id %)) %) options) (first options))))]
    {:group   (pick groups :block)
     :surface (pick surfaces :surface)
     :width   (pick widths :width)
     :embed?  (= "1" (get q (:embed params)))}))

(defn- href
  "A relative link to this page with one field of the selection changed."
  [{:keys [group surface width embed?]} k v]
  (let [cur {:block (:id group) :surface (:id surface) :width (:id width) :embed (if embed? "1" "0")}]
    (str "?" (str/join "&" (for [[field n] params]
                             (str n "=" (java.net.URLEncoder/encode (str (if (= field k) v (get cur field)))
                                                                    "UTF-8")))))))

(defn- axis [sel label k options]
  [:div.cat-axis
   [:span.cat-k label]
   (for [{:keys [id] :as o} options]
     [:a {:class (str "cat-opt" (when (= id (:id (get sel k))) " on")) :href (href sel k id)}
      (:label o)])])

(defn- scene-block
  "One block's scenes, each framed on the selected surface at the selected width. The frame is
   dashed so its edge is never mistaken for the block's own border."
  [{:keys [surface width]} {:keys [name note scenes]}]
  (list
   [:div.cat-scene-head [:span.cat-scene-t name] [:span.cat-scene-fn (str "(" name " …)")]]
   (when note [:p.cat-scene-n note])
   (for [{:keys [title render] :as scene} scenes]
     [:div.cat-scene
      [:div.cat-scene-head [:span.cat-scene-fn "scene"] [:span.cat-scene-t title]]
      (when-let [n (:note scene)] [:p.cat-scene-n n])
      [:div {:class (str "cat-frame " (:class width))
             :style (when-let [b (:background surface)] (str "background:" b))
             "data-block" name "data-scene" title}
       (render)]])))

(def ^:private picker-js
  "Select mode and marks, for a catalog framed by another page; it does nothing in a page that is
   not framed. The page around it drives it by message:

     nido-pick  {on}     select mode on or off. On, hovering outlines an element of a scene and a
                         click names it in a `nido-picked` message — block, scene, path, and what
                         it renders as — and does nothing else; Esc ends select mode and says so
                         (`nido-pick-off`).
     nido-marks {marks}  the picked elements, each {n block scene path}, outlined with their number.

   A path is one step per element from the scene's frame down — tag, first class, and position
   among element siblings — so the same element of the same scene has the same path on every
   render. It also says where it is (`nido-location`) and passes ⌘I/Ctrl+I up (`nido-key`)."
  "(function(){
  if (window.parent === window) return;
  var on = false, hovered = null;
  function frameOf(el){ return el && el.closest ? el.closest('.cat-frame') : null; }
  function step(el){
    var i = 0, s = el;
    while ((s = s.previousElementSibling)) i++;
    var cls = (el.classList && el.classList[0]) ? '.' + el.classList[0] : '';
    return el.tagName.toLowerCase() + cls + ':' + i;
  }
  function path(el, frame){
    var steps = [];
    for (var n = el; n && n !== frame; n = n.parentElement) steps.unshift(step(n));
    return steps.join(' > ');
  }
  function resolve(m){
    var frames = document.querySelectorAll('.cat-frame'), f = null;
    for (var k = 0; k < frames.length; k++)
      if (frames[k].dataset.block === m.block && frames[k].dataset.scene === m.scene) { f = frames[k]; break; }
    if (!f || !m.path) return null;
    var node = f, steps = m.path.split(' > ');
    for (var s = 0; s < steps.length && node; s++) {
      var at = steps[s].lastIndexOf(':'), tag = steps[s].slice(0, at).split('.')[0];
      node = node.children[Number(steps[s].slice(at + 1))];
      if (node && node.tagName.toLowerCase() !== tag) node = null;
    }
    return node;
  }
  function mark(marks){
    var old = document.querySelectorAll('.cat-pick-mark');
    for (var k = 0; k < old.length; k++) { old[k].classList.remove('cat-pick-mark'); old[k].removeAttribute('data-pick-n'); }
    (marks || []).forEach(function(m){
      var el = resolve(m);
      if (el) { el.classList.add('cat-pick-mark'); el.setAttribute('data-pick-n', m.n); }
    });
  }
  function unhover(){ if (hovered) hovered.classList.remove('cat-pick-hover'); hovered = null; }
  function setOn(v){ on = v; document.body.classList.toggle('cat-picking', v); if (!v) unhover(); }
  document.addEventListener('mouseover', function(e){
    if (!on) return;
    var f = frameOf(e.target);
    unhover();
    if (f && e.target !== f) { hovered = e.target; hovered.classList.add('cat-pick-hover'); }
  }, true);
  document.addEventListener('click', function(e){
    if (!on) return;
    var f = frameOf(e.target);
    e.preventDefault(); e.stopPropagation();
    if (!f || e.target === f) return;
    var el = e.target;
    el.classList.remove('cat-pick-hover');
    window.parent.postMessage({type: 'nido-picked', block: f.dataset.block, scene: f.dataset.scene,
      path: path(el, f), text: (el.textContent || '').trim().slice(0, 160),
      html: el.outerHTML.replace(/ (class=\"[^\"]*)cat-pick-(mark|hover)/g, ' $1').slice(0, 600)}, '*');
  }, true);
  document.addEventListener('keydown', function(e){
    if (e.key === 'Escape' && on) { setOn(false); window.parent.postMessage({type: 'nido-pick-off'}, '*'); }
    if ((e.metaKey || e.ctrlKey) && (e.key === 'i' || e.key === 'I')) {
      e.preventDefault(); window.parent.postMessage({type: 'nido-key', key: 'i'}, '*');
    }
  });
  window.addEventListener('message', function(e){
    var d = e.data || {};
    if (d.type === 'nido-pick') setOn(!!d.on);
    if (d.type === 'nido-marks') mark(d.marks);
  });
  window.parent.postMessage({type: 'nido-location', href: location.pathname + location.search}, '*');
})();")

(defn ^{:malli/schema [:=> [:cat :map :string [:maybe :string]] :string]}
  catalog-page
  "The catalog for `registry`, as an HTML document: a sidebar of its groups, and every scene of
   the selected one, rendered in `stylesheet` — the app's own, as text — with Datastar live, so a
   block's data- attributes behave as they do on the app's pages. `query-string` is the request's
   own; `?embed=1` loads the element picker for a page that frames this one."
  [registry stylesheet query-string]
  (let [sel   (select query-string registry)
        group (:group sel)]
    (str
     "<!DOCTYPE html>\n"
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:title (str (:title group) " — " (:title registry))]
        (wire/script)
        [:style (h/raw stylesheet)]
        [:style (h/raw (str "@layer catalog {\n" chrome-css "\n}"))]]
       [:body
        (when (:embed? sel) [:script (h/raw picker-js)])
        [:div.cat
         [:nav.cat-side
          [:h1 "Components"]
          [:p.cat-sub (:title registry)]
          (for [g (:groups registry)]
            [:a {:class (str "cat-group" (when (= (:id g) (:id group)) " on"))
                 :href  (href sel :block (:id g))}
             (:title g)])]
         [:main.cat-main
          [:div.cat-bar
           (when (seq (:surfaces registry)) (axis sel "surface" :surface (:surfaces registry)))
           (axis sel "width" :width widths)]
          [:h2 (:title group)]
          (when-let [n (:note group)] [:p.cat-scene-n n])
          (map #(scene-block sel %) (:blocks group))]]]]))))
