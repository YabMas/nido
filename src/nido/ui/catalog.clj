(ns nido.ui.catalog
  "The component catalog: GET /_catalog, and the page that frames it beside the catalog chat.

   The catalog itself is the UI library's catalog over nido's registry, in the stylesheet every
   nido chrome emits: a sidebar of the dashboard's block vocabularies, every scene of the selected
   one, and the two axes these blocks vary over in the app. Its chrome, its selection and its
   element picker are the library's; nido supplies only the registry and the stylesheet.

   HIDES that nido's catalog is the library's catalog over nido's registry and stylesheet, and how
   it sits beside the catalog chat: the page around the frame, which owns the chat's open state,
   select mode and the marks on the scenes.

   It holds no copy of anything it shows. A scene is rendered by calling the same function the
   dashboard calls, so a scene that looks wrong is the block being wrong rather than this page
   disagreeing with the app.

   Not linked from anywhere in the product. It is served by the one dashboard handler on every
   dashboard — a flag that decided otherwise would make the dashboard behave differently
   depending on how it was started, which is worse than a route a person reaches by typing it —
   and it reads nothing: sample data, and its own query string."
  (:require [hiccup2.core :as h]
            [nido.libraries.ui.block :as block]
            [nido.libraries.ui.catalog-page :as library]
            [nido.ui.components.chat :as chat]
            [nido.ui.components.control :as c]
            [nido.ui.registry :as registry]))

;; ---------------------------------------------------------------------------
;; The catalog

(defn ^{:malli/schema [:=> [:cat [:maybe :string]] :string]}
  catalog-page
  "The catalog, from its own query string: the library's catalog page over nido's registry, in
   the stylesheet every nido chrome emits. Every POST a scene renders goes to /_catalog/nowhere
   and changes nothing; `embed=1` loads the element picker the page around the frame drives."
  [query-string]
  (library/catalog-page (registry/registry) (registry/assembled-stylesheet) query-string))

;; ---------------------------------------------------------------------------
;; The catalog with its chat

(def ^:private host-js
  "The page around the scene frame. It owns the chat's open state, select mode and the marks:

   - Open and closed: the side panel's toggle and ⌘I/Ctrl+I (here or in the frame) flip its open
     signal; `nidoChat.open` follows it into the URL (`chat=1`), and closing ends select
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
  function setPicking(v){ picking = v; btn.setAttribute('aria-pressed', v);
    root.classList.toggle('picking', v); post({type: 'nido-pick', on: v}); }
  function sendMarks(){ post({type: 'nido-marks', marks: isOpen() ? marks : []}); }
  function picks(){ try { var v = JSON.parse(input.value || '[]'); return Array.isArray(v) ? v : []; } catch (e) { return []; } }
  function toggleOpen(){ document.querySelector('.chat-toggle').click(); }
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

(defn ^{:malli/schema [:=> [:cat :Conversation :string :map :boolean :any] :any]}
  chat-page
  "The catalog with its chat: the scene page in a frame at `frame-src`, and the chat panel over
   `transcript`, on the panel `view` (see `nido.ui.components.chat`; the panel's `:sync-picks` action
   posts the picks for their chips), under `change-bar` — the conversation's unlanded
   change, as the `nido.ui.components.chat/change-bar` block renders it. The panel is
   `nido.ui.components.chat/panel`: closed — `open?` false — it narrows to its toggle beside the
   frame; open, it slides in with Pick, which puts the frame into select mode. Hiccup for the content area of the
   dashboard's page chrome, which fills that area; the chrome is the caller's. Reads nothing: the
   conversation, its change, where the frame points and whether the chat starts open are its
   caller's."
  [transcript frame-src view open? change-bar]
  (let [kit   (block/kit chat/panel (:id view) {} {} (:router view))
        picks ((:signal kit) :picks)
        open  ((:signal kit) :open)]
    (list
        [:div.catc {:class (when open? "open")
                    "data-signals" "{catcFrame: ''}"
                    "data-class:open" (str "$" open)
                    "data-effect" "$catcFrame && window.nidoReloadFrame($catcFrame)"}
         [:iframe#catc-frame.catc-frame {:src frame-src :title "Components"}]
         (block/render chat/panel (:id view)
                    {:transcript transcript
                     :open?  open?
                     :attach? (boolean (:attach? view))
                     :reset? true
                     :live?  true
                     :bar    [(c/icon-button
                               {:id "catc-pick" :class "catc-pick" :aria-pressed "false" :label "Pick elements"
                                :title "Pick elements of a scene to talk about (Esc to stop)"}
                               [:span.catc-tool-i {:aria-hidden "true"} "⌖"])]
                     :top    [[:p.catc-hint "Click elements in the scene to attach them. Click again to remove one; "
                               [:kbd "Esc"] " to stop."]
                              change-bar]
                     :extra  [(c/hidden-input {:id "catc-picks" "data-bind" picks})
                              (c/button {:id "catc-picks-sync" :hidden true
                                         "data-on:click" ((:action kit) :sync-picks)})]}
                    {} (:router view))
         [:span {:hidden true "data-effect" (str "window.nidoChat && window.nidoChat.open($" open ")")}]
         [:span {:hidden true "data-effect" (str "window.nidoChat && window.nidoChat.marks($" picks ")")}]]
        [:script (h/raw host-js)])))
