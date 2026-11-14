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
            [nido.ui.components.chat :as chat]
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
        .cat-picking .cat-frame * { cursor:crosshair; }
        .cat-pick-hover { outline:2px dashed var(--accent); outline-offset:1px; }
        .cat-pick-mark { outline:2px solid var(--accent); outline-offset:1px; position:relative; }
        .cat-pick-mark::after { content:attr(data-pick-n); position:absolute; top:-9px; left:-9px; z-index:1;
                                min-width:16px; height:16px; border-radius:8px; padding:0 3px;
                                background:var(--accent-bg); color:var(--accent-strong);
                                border:1px solid var(--accent-border); font:var(--fs-xs)/14px var(--font-mono);
                                text-align:center; pointer-events:none; }
        /* The page around the frame. Closed, the frame is the whole page and the launcher the one
           chat control; open, the panel pushes the frame narrower, and below 1000px covers it. */
        .catc { grid-column:1 / -1; display:grid; grid-template-columns:1fr 0px; height:100vh;
                transition:grid-template-columns 0.18s ease-out; }
        .catc.open { grid-template-columns:1fr 420px; }
        .catc-frame { border:0; width:100%; height:100vh; background:var(--bg); }
        .catc-side { border-left:1px solid var(--border); display:flex; flex-direction:column; background:var(--bg);
                     padding:12px 14px; gap:10px; min-height:0; height:100vh; overflow:hidden; min-width:0; }
        .catc:not(.open) .catc-side { visibility:hidden; padding:0; border:0; }
        .catc-bar { display:flex; gap:8px; align-items:center; }
        .catc-bar h1 { margin:0; font-size:var(--fs-md); flex:1; }
        .catc-pick.on { background:var(--accent-bg); border-color:var(--accent-border); color:var(--accent-strong); }
        .catc-x { background:none; border:none; color:var(--text-muted); cursor:pointer; font-size:var(--fs-md); }
        .catc-x:hover { color:var(--text-strong); }
        .catc-side .chat { flex:1; }
        .catc-launch { position:fixed; top:10px; right:14px; z-index:2; display:flex; gap:6px; align-items:center; }
        .catc-launch kbd { font-family:var(--font-mono); font-size:var(--fs-xs); color:var(--text-faint); }
        .catc-launch .chat-spin { display:none; }
        .catc-launch.busy .chat-spin { display:block; }
        @media (max-width: 999px) {
          .catc.open { grid-template-columns:1fr 0px; }
          .catc.open .catc-side { position:fixed; top:0; right:0; bottom:0; width:min(420px, 100vw); z-index:3;
                                  box-shadow:-8px 0 24px var(--surface-sunken); }
        }
        @media (prefers-reduced-motion: reduce) { .catc { transition:none; } }
")

(def ^:private params
  "The query-string names this page reads its selection from. Read by `select` below and by every
   link the page writes, so neither side can be renamed without the other."
  {:block "block" :surface "surface" :width "width" :embed "embed"})

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
    {:group group :surface (pick surfaces :surface) :width (pick widths :width)
     :embed? (= "1" (get q (:embed params)))}))

(defn- href
  "A link to this page with one field of the selection changed."
  [{:keys [group surface width embed?]} k v]
  (let [cur {:block (:id group) :surface (first surface) :width (first width)
             :embed (if embed? "1" "0")}]
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
      [:div {:class (str "cat-frame " (nth surface 2) " " (nth width 2))
             "data-block" name "data-scene" title}
       (render)]])))

;; ---------------------------------------------------------------------------
;; The element picker

(def ^:private picker-js
  "Select mode and the marks, inside the embedded scene page; it does nothing in a page that is
   not framed. The page around it drives it by message:

     nido-pick  {on}     select mode on or off. On, hovering outlines an element of a scene and a
                         click names it in a `nido-picked` message — block, scene, path, and what it
                         renders as. A click in select mode does nothing else, so a scene's own
                         controls are not pressed by picking them; Esc ends select mode and says so
                         (`nido-pick-off`). Select mode stays on across picks.
     nido-marks {marks}  the picked elements, each {n block scene path}: each is outlined with its
                         number, and every element not in the list is unmarked.

   The path is one step per element from the scene's frame down: its tag, its first class, and
   its position among its element siblings. A scene is a literal value rendered by a function
   that reads nothing, so the same element gets the same path on every render of the same code —
   which is what lets a mark find its element again after the frame reloads.

   It also tells the page around it where it is (`nido-location`), and passes ⌘I/Ctrl+I up
   (`nido-key`), so the chat opens from inside the frame as from outside it."
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
        (when (:embed? sel) [:script (h/raw picker-js)])
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

;; ---------------------------------------------------------------------------
;; The catalog with its chat

(def ^:private host-js
  "The page around the scene frame. It owns the chat's open state, select mode and the marks:

   - Open and closed: the launcher, the panel's ✕ and ⌘I/Ctrl+I (here or in the frame) flip the
     `catcOpen` signal; `nidoChat.open` follows it into the URL (`chat=1`), and closing ends select
     mode and clears the frame's marks, so a closed chat leaves nothing on the scenes.
   - Picks: a `nido-picked` message toggles that element in the panel's picks signal, written
     through its hidden bound input — the one way plain script sets Datastar page state — and the
     hidden sync button posts the picks so the server renders their chips. `nidoChat.marks`
     follows the picks signal into the frame as numbered marks, and again after every frame load.
   - Reload: the server reloads the frame by setting `catcFrame` to the origin it should load
     from; `nidoReloadFrame` loads it where it last said it was, keeping its block."
  "(function(){
  var root = document.querySelector('.catc');
  var frame = document.getElementById('catc-frame');
  var btn = document.getElementById('catc-pick');
  var input = document.getElementById('catc-picks');
  var at = null, picking = false, marks = [];
  function post(m){ frame.contentWindow && frame.contentWindow.postMessage(m, '*'); }
  function isOpen(){ return root.classList.contains('open'); }
  function setPicking(v){ picking = v; btn.classList.toggle('on', v); post({type: 'nido-pick', on: v}); }
  function sendMarks(){ post({type: 'nido-marks', marks: isOpen() ? marks : []}); }
  function picks(){ try { var v = JSON.parse(input.value || '[]'); return Array.isArray(v) ? v : []; } catch (e) { return []; } }
  function toggleOpen(){ document.getElementById(isOpen() ? 'catc-close' : 'catc-launch').click(); }
  btn.addEventListener('click', function(){ setPicking(!picking); });
  window.addEventListener('keydown', function(e){
    if ((e.metaKey || e.ctrlKey) && (e.key === 'i' || e.key === 'I')) { e.preventDefault(); toggleOpen(); }
    if (e.key === 'Escape' && picking) setPicking(false);
  });
  window.addEventListener('message', function(e){
    var d = e.data || {};
    if (d.type === 'nido-location') { at = d.href; sendMarks(); if (picking) post({type: 'nido-pick', on: true}); }
    if (d.type === 'nido-pick-off') setPicking(false);
    if (d.type === 'nido-key') toggleOpen();
    if (d.type === 'nido-picked') {
      var ps = picks(), key = d.block + '|' + d.scene + '|' + d.path;
      var kept = ps.filter(function(p){ return p.block + '|' + p.scene + '|' + p.path !== key; });
      if (kept.length === ps.length) kept.push({block: d.block, scene: d.scene, path: d.path, text: d.text, html: d.html});
      input.value = JSON.stringify(kept);
      input.dispatchEvent(new Event('input', {bubbles: true}));
      document.getElementById('catc-picks-sync').click();
    }
  });
  window.nidoChat = {
    open: function(v){
      var u = new URL(location.href);
      if (v) u.searchParams.set('chat', '1'); else u.searchParams.delete('chat');
      history.replaceState(null, '', u.toString());
      if (!v && picking) setPicking(false);
      sendMarks();
    },
    marks: function(json){
      var ps; try { ps = JSON.parse(json || '[]'); } catch (e) { ps = []; }
      marks = (Array.isArray(ps) ? ps : []).map(function(p, i){ return {n: i + 1, block: p.block, scene: p.scene, path: p.path}; });
      sendMarks();
    }
  };
  window.nidoReloadFrame = function(base){
    base = (base || '').split('#')[0];
    var u = new URL(base + (at || '/_catalog?embed=1'), location.href);
    u.searchParams.set('t', Date.now());
    frame.src = u.toString();
  };
})();")

(defn ^{:malli/schema [:=> [:cat :Conversation :string :map :boolean] :string]}
  chat-page
  "The catalog with its chat: the scene page in a frame at `frame-src`, and the chat panel over
   `transcript`, with `panel` its id and routes (see `nido.ui.components.chat`; `:picks-url` is
   where the picks are posted for their chips). Closed — `open?` false — the frame is the whole
   page and a launcher is the only chat control; open, the panel slides in beside the frame with
   Select, which puts the frame into select mode. Reads nothing: the conversation, where the frame
   points and whether the chat starts open are its caller's."
  [transcript frame-src panel open?]
  (let [{:keys [picks busy]} (chat/signals (:id panel))]
    (str
     (h/html
      [:html {:lang "en"}
       [:head
        [:meta {:charset "utf-8"}]
        [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
        [:link {:rel "icon" :type "image/png" :href "/favicon.png"}]
        [:title "nido components"]
        (datastar/datastar-script)
        [:style (h/raw (css/stylesheet))]
        [:style (h/raw chrome-css)]]
       [:body
        [:div.catc {:class (when open? "open")
                    "data-signals" (str "{catcFrame: '', catcOpen: " (boolean open?) "}")
                    "data-class:open" "$catcOpen"
                    "data-effect" "$catcFrame && window.nidoReloadFrame($catcFrame)"}
         [:iframe#catc-frame.catc-frame {:src frame-src :title "Components"}]
         [:aside.catc-side {:aria-label "Chat with the agent"}
          [:div.catc-bar
           [:h1 "Chat"]
           [:button#catc-pick.btn.catc-pick {:type "button"
                                             :title "Pick elements of a scene to talk about (Esc to stop)"}
            "Select"]
           [:button#catc-close.catc-x {:type "button" :title "Close the chat (⌘I)"
                                       "data-on:click" "$catcOpen = false"} "✕"]]
          (-> (chat/chat-panel transcript panel)
              (conj [:input#catc-picks {:type "hidden" "data-bind" picks}]
                    [:button#catc-picks-sync {:type "button" :hidden true
                                              "data-on:click" (str "@post('" (:picks-url panel) "')")}]))]
         [:button#catc-launch.btn.catc-launch {:type "button" :title "Chat with the agent (⌘I)"
                                               "data-show" "!$catcOpen"
                                               "data-on:click" "$catcOpen = true"
                                               "data-class:busy" (str "$" busy)}
          [:span.chat-spin {:aria-hidden "true"}] "Chat" [:kbd "⌘I"]]
         [:span {:hidden true "data-effect" "window.nidoChat && window.nidoChat.open($catcOpen)"}]
         [:span {:hidden true "data-effect" (str "window.nidoChat && window.nidoChat.marks($" picks ")")}]]
        [:script (h/raw host-js)]]]))))
