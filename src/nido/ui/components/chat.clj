(ns nido.ui.components.chat
  "A conversation with an agent, as a side panel: a bar with its toggle, then its transcript, the
   state of the running turn, and the composer; and beside it, the bar saying what the
   conversation changed that main does not have.

   Registered with the UI library as two blocks: `panel`, the conversation, and `change-bar`, its
   unlanded change. Both render over props and the page's router, and every control is an action
   one of them declares, so the panel names no URL and no posted field of its own: a host routes
   the actions and reads a post back through `decode`.

   HIDES how a transcript reads — the order of its items, the turn's status row, how a reply
   streams, reasoning and tool calls disclosed, the permission prompt's buttons, the composer and
   its pick chips. Reads nothing: it takes a transcript value (`nido.acp.transcript` describes
   it, and this namespace reads only that).

   A stream keeps a panel live by patching its parts — an item, the status row, the chips, the
   staged images, the whole log. The panel renders one of them alone when its props name it in
   `:part` (`:item` with the item in `:item`), on the same instance, so the part carries the names
   the whole panel gives it and the patch lands in place.

   Disclosure follows what is happening: a thought is open while it streams and collapsed once it
   is finished; a tool call is collapsed unless it failed. The status row names the turn's phase
   from the transcript alone; the seconds it shows are counted by the browser from the turn's
   start, which the transcript carries.

   A panel with `:attach?` also offers attaching images — a picker, paste into the text box, drop
   onto the panel — each uploaded as it is chosen, raw, by the browser's own fetch, to where the
   router sends `:attach`; the images staged so far are the host's, rendered by `attachments` and
   patched in by the stream. Images, staged or sent, are only ever `<img>`s naming the router's
   `:image` of their digest: no image's bytes are in the panel's markup.

   The composer's page state is the panel's signals — the draft (`:text`), the picks (`:picks`, a
   JSON array, each pick `{block scene path label}` and whatever else its host keeps), whether a
   turn is running (`:busy`), whether the side panel is open (`:open`) and whether New
   conversation is asking (`:reset`) — seeded by the panel's morph contract, so a patch never
   resets them. Send and Stop switch on the busy signal, so a turn starting or ending never
   re-renders the composer and never loses a draft or a pick."
  (:require [clojure.string :as str]
            [nido.libraries.ui.block :as block]
            [nido.libraries.ui.names :as names]
            [nido.ui.markdown :as md]))

(def ^:private scope
  "The panel's scope: what every name it derives, and the change bar's reach to its busy signal,
   are derived under."
  :chat)

(def ^:private panel-actions
  {:send       [:post :send {:text  [:signal :text :string]
                             :picks [:signal :picks :string {:optional true}]}]
   :cancel     [:post :cancel {}]
   :reset      [:post :reset {}]
   :stream     [:get :stream {}]
   :answer     [:post :permission {}]
   :unpick     [:post :unpick {:picks [:signal :picks :string]}]
   :unattach   [:post :unattach {}]
   :sync-picks [:post :picks {:picks [:signal :picks :string]}]})

;; ---------------------------------------------------------------------------
;; Items

(def ^:private tool-status
  "A tool call's status as its mark: a glyph, the class that colours it, and its words."
  {:pending     ["○" "chat-st-waiting" "waiting to run"]
   :in-progress ["●" "chat-st-running" "running"]
   :completed   ["✓" "chat-st-ok" "done"]
   :failed      ["✗" "chat-st-failed" "failed"]})

(defn- tool-item [{:keys [title status body diffs]}]
  (let [[glyph cls words] (tool-status status (tool-status :pending))]
    [:details.chat-tool (when (= :failed status) {:open true})
     [:summary [:span {:class (str "chat-st " cls) :title words} glyph]
      [:span.chat-tool-t title]]
     (when (seq body) [:pre.chat-tool-out body])
     (for [{:keys [path old new]} diffs]
       [:div.chat-diff
        [:div.chat-diff-path path]
        (when (seq old) [:pre.chat-diff-old old])
        [:pre.chat-diff-new new]])]))



(defn- permission-item [{:keys [title options answer] item-id :id} {:keys [action]}]
  (if answer
    [:div.chat-perm.answered
     [:span.chat-st.chat-st-ok {:title "answered"} "✓"]
     [:span.chat-perm-t title]
     [:span.chat-perm-a (or (some #(when (= answer (:id %)) (:name %)) options) answer)]]
    [:div.chat-perm
     [:p.chat-perm-q title]
     (into [:div.chat-perm-opts]
           (for [{oid :id oname :name kind :kind} options]
             [:button {:class (case kind
                                (:allow_once :allow_always) "btn btn-primary"
                                (:reject_once :reject_always) "btn btn-danger"
                                "btn")
                       "data-on:click" (action :answer {:params {:item item-id :option oid}})}
              oname]))]))

(defn- sent-picks
  "The picks a sent message carried, numbered as the person numbered them."
  [refs]
  (when (seq refs)
    (into [:ol.chat-picks.sent]
          (map-indexed (fn [i {:keys [label title]}]
                         [:li.chat-pick {:title (or title label)} [:span.chat-pick-n (inc i)]
                          [:span.chat-pick-l label]])
                       refs))))

(defn- sent-images
  "The images a sent message carried, as thumbnails that open the image itself — on a panel that
   offers attaching; one that does not shows none."
  [images {:keys [props router]}]
  (when (and (:attach? props) (seq images))
    (into [:div.chat-imgs]
          (for [{:keys [digest]} images
                :let [src (router :image {:digest digest})]]
            [:a {:href src :target "_blank" :rel "noopener"}
             [:img.chat-img {:src src :alt "Attached image" :loading "lazy"}]]))))

(defn- item-attrs
  "The attrs of one transcript item: a part of its own, named by the item's transcript id, which
   is what lets a stream morph it in place."
  [{:keys [id]} item]
  (assoc (names/part-attrs scope id (keyword (str "item-" (:id item)))) "data-part" "item"))

(defn- render-item [kit item]
  (let [a (item-attrs kit item)]
    (case (:kind item)
      :user       [:div.chat-msg.chat-you a
                   (sent-picks (:refs item))
                   (sent-images (:images item) kit)
                   (md/render (:text item))]
      :agent      [:div.chat-msg.chat-agent a
                   (md/render (:text item))
                   (when (:streaming? item) [:span.chat-caret {:title "writing"} "▍"])]
      :thought    [:details.chat-thought (cond-> a (:streaming? item) (assoc :open true))
                   [:summary (if (:streaming? item) "Thinking…" "Thought")]
                   [:div.chat-thought-text (md/render (:text item))]]
      :tool       [:div a (tool-item item)]
      :permission [:div a (permission-item item kit)]
      :error      [:div.chat-err a [:span.chat-st.chat-st-failed {:title "failed"} "⚠"]
                   [:span (:text item)]]
      :note       [:p.chat-note a (:text item)]
      [:div a])))

;; ---------------------------------------------------------------------------
;; The turn's status

(defn- phase
  "What a running turn is doing now, read off its transcript: nothing yet is waiting, a request
   still unanswered anywhere in the turn is asking, a tool call still pending or running anywhere
   in it is running, and otherwise the newest item decides."
  [{:keys [items]}]
  (let [turn    (reverse (take-while #(not= :user (:kind %)) (reverse items)))
        latest  (last turn)
        asking  (some #(and (= :permission (:kind %)) (nil? (:answer %))) turn)
        running (last (filter #(and (= :tool (:kind %)) (#{:pending :in-progress} (:status %))) turn))]
    (cond
      (nil? latest)                                              [:waiting "Waiting for the agent"]
      asking                                                     [:asking "Waiting for your answer"]
      running                                                    [:running (str "Running " (:title running))]
      (and (= :thought (:kind latest)) (:streaming? latest))     [:thinking "Thinking"]
      (and (= :agent (:kind latest)) (:streaming? latest))       [:writing "Writing"]
      :else                                                      [:working "Working"])))

(def ^:private elapsed-js
  "Time since the turn started, written into the element as 42s or 3m 07s — counted here because
   the panel reads no clock."
  "var s = Math.max(0, Math.round((Date.now() - Number(el.dataset.since)) / 1000)); el.textContent = s < 60 ? s + 's' : Math.floor(s / 60) + 'm ' + String(s % 60).padStart(2, '0') + 's'")


(defn- render-status [{:keys [part]} {:keys [busy? started-at] :as transcript}]
  [:div.chat-status (merge (part :status) {:role "status" :aria-live "polite"})
   (when busy?
     (let [[ph label] (phase transcript)]
       (list [:span {:class (str "chat-spin chat-spin-" (name ph)) :aria-hidden "true"}]
             [:span.chat-status-l {:title label} label]
             (when started-at
               [:span.chat-elapsed {:data-since (str started-at)
                                    "data-init" elapsed-js
                                    "data-on-interval__duration.1s" elapsed-js}]))))])

;; ---------------------------------------------------------------------------
;; The composer

(defn- render-picks [{:keys [part action]} ps]
  (into [:div.chat-picks (part :picks)]
        (map-indexed (fn [i {:keys [label title]}]
                       [:span.chat-pick {:title (or title label)}
                        [:span.chat-pick-n (inc i)]
                        [:span.chat-pick-l label]
                        [:button.chat-pick-x {:title "Remove" :type "button"
                                              "data-on:click" (action :unpick {:params {:index i}})}
                         "✕"]])
                     ps)))

(defn- render-attachments [{:keys [part action router]} images]
  (into [:div.chat-attachments (part :attachments)]
        (for [{:keys [digest]} images]
          [:span.chat-att
           [:img {:src (router :image {:digest digest}) :alt "Image to send"}]
           [:button.chat-att-x {:type "button" :title "Remove" :aria-label "Remove image"
                                "data-on:click" (action :unattach {:params {:digest digest}})}
            "✕"]])))

(def ^:private attach-types
  "The media types the browser offers to upload: the ones the agent accepts. The host checks the
   bytes again; this only spares an upload that would be refused."
  ["image/png" "image/jpeg" "image/gif" "image/webp"])

(def ^:private attach-max-bytes
  "The largest image the browser uploads; the host refuses anything larger again."
  (* 5 1024 1024))

(def ^:private attach-js
  "How images reach the host: chosen in the picker, pasted into the text box or dropped on the
   panel, each is checked for type and size here, then posted raw, one at a time, to the attach
   route. A refusal — here, or the host's answer — is said in the composer's error line and
   touches nothing else; an accepted one comes back to every page as the host's chips.

   No `$` or `@` in it: Datastar would read either as a signal or an action."
  "(function(box){
  if (box._chatAttach) return; box._chatAttach = true;
  var url = box.dataset.attachUrl, max = Number(box.dataset.attachMax), types = box.dataset.attachTypes.split(' ');
  var err = box.querySelector('.chat-attach-err'), panel = box.closest('.chat') || box;
  var input = box.querySelector('input[type=file]'), text = box.querySelector('textarea');
  function say(t){ err.textContent = t || ''; }
  async function upload(files){
    say('');
    for (var i = 0; i < files.length; i++) {
      var f = files[i], name = f.name || 'The image';
      if (types.indexOf(f.type) < 0) { say(name + ' is not an image the agent accepts: PNG, JPEG, GIF or WebP.'); continue; }
      if (f.size > max) { say(name + ' is larger than 5 MB.'); continue; }
      try {
        var r = await fetch(url, {method: 'POST', headers: {'Content-Type': f.type}, body: f});
        if (!r.ok) say(await r.text());
      } catch (e) { say(name + ' could not be uploaded.'); }
    }
  }
  function files(dt){ return dt && dt.files ? Array.prototype.slice.call(dt.files) : []; }
  box.querySelector('.chat-attach').addEventListener('click', function(){ input.click(); });
  input.addEventListener('change', function(){ var fs = files(input); input.value = ''; upload(fs); });
  text.addEventListener('paste', function(e){ var fs = files(e.clipboardData); if (fs.length) { e.preventDefault(); upload(fs); } });
  panel.addEventListener('dragover', function(e){
    if (e.dataTransfer && Array.prototype.indexOf.call(e.dataTransfer.types, 'Files') >= 0) { e.preventDefault(); panel.classList.add('chat-drop'); } });
  panel.addEventListener('dragleave', function(e){ if (!panel.contains(e.relatedTarget)) panel.classList.remove('chat-drop'); });
  panel.addEventListener('drop', function(e){
    panel.classList.remove('chat-drop');
    var fs = files(e.dataTransfer); if (fs.length) { e.preventDefault(); upload(fs); } });
})(el)")


(defn- render-composer [{:keys [props part signal action router] :as kit}]
  (let [{:keys [attach? picks staged]} props
        busy (signal :busy)]
    [:div.chat-compose (cond-> (part :compose)
                         attach? (assoc "data-attach-url" (router :attach nil)
                                        "data-attach-max" (str attach-max-bytes)
                                        "data-attach-types" (str/join " " attach-types)
                                        "data-init" attach-js))
     (render-picks kit (or picks []))
     (when attach?
       (list (render-attachments kit (or staged []))
             [:p.chat-attach-err (merge (part :attach-err) {:role "alert"})]))
     [:div.chat-field
      [:textarea {"data-bind" (signal :text) :rows 3 :placeholder "Message the agent"
                  "data-on:keydown" (str "if (!$" busy " && evt.key === 'Enter' && !evt.shiftKey && !evt.isComposing) "
                                         "{ evt.preventDefault(); " (action :send) " }")}]
      [:div.chat-actions
       (when attach?
         (list [:input {:type "file" :accept (str/join "," attach-types) :multiple true :hidden true
                        :aria-hidden "true" :tabindex "-1"}]
               [:button.btn.chat-attach {:type "button" :title "Attach an image — or paste or drop one"
                                         :aria-label "Attach an image"}
                "Image"]))
       [:button.btn.btn-primary {"data-show" (str "!$" busy)
                                 "data-on:click" (action :send)} "Send"]
       [:button.btn {"data-show" (str "$" busy)
                     "data-on:click" (action :cancel)} "Stop"]]]]))

;; ---------------------------------------------------------------------------
;; The log

(def ^:private scroll-js
  "How the log scrolls — brian's chat model, for a log that scrolls itself:

   - It opens at the bottom, and follows new content there until the person scrolls.
   - A message the person sends is pinned to the top of the log, and the log keeps room below
     the newest message (`--chat-tail`, under `.chat-log::after`) so the reply grows into the
     space under it rather than pushing it away. The room shrinks as the reply fills it, so the
     log never scrolls past the reply's end.
   - The person's own wheel, touch or scroll ends following and the pin; only a scroll this code
     made is not theirs.

   No `$` or `@` in it: Datastar would read either as a signal or an action."
  "(function(log){
  if (log._chatScroll) return;
  var st = log._chatScroll = {follow: true, until: 0};
  var MARGIN = 8;
  function mine(ms){ st.until = Math.max(st.until, Date.now() + ms); }
  function newest(){ var ys = log.querySelectorAll('.chat-you'); return ys.length ? ys[ys.length - 1] : null; }
  function topOf(m){ return m.getBoundingClientRect().top - log.getBoundingClientRect().top + log.scrollTop; }
  function room(){
    var m = newest(), last = log.lastElementChild;
    if (!m || !last) { log.style.removeProperty('--chat-tail'); return; }
    var below = last.getBoundingClientRect().bottom - m.getBoundingClientRect().top;
    var gap = parseFloat(getComputedStyle(log).rowGap) || 0;
    log.style.setProperty('--chat-tail', Math.max(0, log.clientHeight - below - gap - MARGIN) + 'px');
  }
  function bottom(){ mine(80); log.scrollTop = log.scrollHeight; }
  function pin(m){ st.follow = false; room(); mine(600); log.scrollTo({top: topOf(m) - MARGIN, behavior: 'smooth'}); }
  function release(){ st.follow = false; }
  log.addEventListener('wheel', release, {passive: true});
  log.addEventListener('touchmove', release, {passive: true});
  log.addEventListener('scroll', function(){ if (Date.now() >= st.until) release(); }, {passive: true});
  new MutationObserver(function(ms){
    var sent = null;
    ms.forEach(function(r){ r.addedNodes.forEach(function(n){
      if (n.nodeType === 1 && n.classList.contains('chat-you') && n === log.lastElementChild) sent = n; }); });
    if (sent) { pin(sent); return; }
    room();
    if (st.follow) bottom();
  }).observe(log, {childList: true, subtree: true, characterData: true});
  if (window.ResizeObserver) new ResizeObserver(function(){ room(); if (st.follow) bottom(); }).observe(log);
  room(); bottom();
})(el)")


(defn- render-log [{:keys [part] :as kit} items]
  (into [:div.chat-log (assoc (part :log) "data-init" scroll-js)] (map #(render-item kit %) items)))

;; ---------------------------------------------------------------------------
;; The panel

(defn- panel-icon
  "A right-hand panel glyph with a chevron drawn by `chevron` (an SVG path): pointing in, the
   panel opens; pointing out, it closes. The toggle stacks both and fades between them."
  [cls chevron]
  [:svg {:class cls :viewBox "0 0 24 24" :fill "none" :stroke "currentColor"
         :stroke-width "2" :stroke-linecap "round" :stroke-linejoin "round" :aria-hidden "true"}
   [:rect {:width "18" :height "18" :x "3" :y "3" :rx "2"}]
   [:path {:d "M15 3v18"}]
   [:path {:d chevron}]])


(defn- render-side
  [{:keys [props part signal action] :as kit}]
  (let [{:keys [transcript open? heading reset? bar top extra]} props
        open    (signal :open)
        busy    (signal :busy)
        confirm (signal :reset)
        asking  (str "$" confirm " && !$" busy)]
    (-> [:aside.chat-side (merge (part :root)
                                 {:class (when open? "open")
                                  :aria-label "Chat with the agent"
                                  "data-class:open" (str "$" open)})
         (into [:div.chat-side-bar
                [:button.chat-toggle (merge (part :toggle)
                                            {:type "button"
                                             :title "Chat with the agent (⌘I)" :aria-label "Chat with the agent"
                                             :aria-expanded (str (boolean open?))
                                             "data-attr:aria-expanded" (str "$" open)
                                             "data-on:click" (str "$" open " = !$" open)
                                             "data-class:busy" (str "$" busy)})
                 (panel-icon "chat-i-open" "m10 15-3-3 3-3")
                 (panel-icon "chat-i-close" "m8 9 3 3-3 3")
                 [:span.chat-spin {:aria-hidden "true"}]]
                [:h1 (when reset? {"data-show" (str "!(" asking ")")}) (or heading "Chat")]
                (when reset?
                  [:button.btn.chat-new {:type "button" :title "Start a new conversation"
                                         "data-show" (str "!$" confirm " && !$" busy)
                                         "data-on:click" (str "$" confirm " = true")}
                   "New conversation"])
                (when reset?
                  [:div.chat-new-ask {"data-show" asking}
                   [:span "Start over? This conversation can't be brought back."]
                   [:button.btn {:type "button" "data-on:click" (str "$" confirm " = false")} "Back"]
                   [:button.btn.btn-danger {:type "button"
                                            "data-on:click" (str "$" confirm " = false; " (action :reset))}
                    "Start over"]])]
               bar)]
        (into top)
        (conj (into [:section.chat (cond-> (part :panel)
                                     (:live? props) (assoc "data-init" (action :stream)))
                     (render-log kit (:items transcript))
                     (render-status kit transcript)
                     (render-composer kit)]
                    extra)))))

(defn- render-panel
  "The side panel, or the one part of it `:part` names — what a stream patches in place."
  [{:keys [props] :as kit}]
  (let [{:keys [transcript picks staged item]} props]
    (case (:part props)
      :log         (render-log kit (:items transcript))
      :item        (render-item kit item)
      :status      (render-status kit transcript)
      :picks       (render-picks kit (or picks []))
      :attachments (render-attachments kit (or staged []))
      (render-side kit))))

;; ---------------------------------------------------------------------------
;; The change bar

(defn- first-line [s] (some-> s str/trim not-empty str/split-lines first))

(defn- landing-line
  "The latest landing, as `nido.coordinator.control/latest-landing` answers it, in one line; nil for
   none."
  [{:keys [landing waits unreadable]}]
  (let [short (fn [c] [:code (subs c 0 (min 12 (count c)))])]
    (cond
      unreadable
      [:p.chat-change-refused {:role "alert"} "The latest landing cannot be read: " unreadable]

      (= :owed (:state landing))
      [:p.chat-change-status {:role "status"} "The dashboard restarts onto " (short (:commit landing))
       (if (seq waits) (str " · waiting for " (str/join "; " waits)) " now") "."]

      (= :running (:state landing))
      [:p.chat-change-done {:role "status"} "The dashboard runs " (short (:commit landing))
       " since " (:since landing) "."])))

(defn- files-summary
  "A change's files folded away under `label`."
  [label files]
  (let [n (count files)]
    [:details.chat-change-files
     [:summary (str n (if (= 1 n) " file" " files") " " label)]
     [:ul (for [{:keys [path status]} files]
            [:li [:span.chat-change-st (name status)] " " [:code path]])]]))

(defn- landing-summary
  "The change a publish sealed (`{:description :files}`), its files folded away under `label`; nil
   for none."
  [label landing]
  (when landing
    (list (files-summary label (:files landing))
          [:p.chat-change-desc (or (first-line (:description landing)) [:em "Not described."])])))

(defn- render-change-bar
  [{:keys [id props part signal action]}]
  (let [{:keys [change publishing]} props
        {:keys [running? step landing landed refused reverted? restart]} publishing
        busy      (names/signal scope id :busy)
        confirm   (signal :confirm)
        desc      (first-line (:description change))
        conflicts (seq (:conflicts change))
        held      (or running? (nil? desc) conflicts)
        asking    (fn [v] (str "$" confirm " === '" v "'"))
        publish   (action :publish {:query (if-let [rev (:rev change)] {:rev rev} {})})]
    [:section.chat-change (part :root)
     (cond
       running?
       (list
        [:p.chat-change-status {:role "status"} [:span.chat-spin {:aria-hidden "true"}]
         "Publishing · " (or step "starting")]
        (landing-summary "landing" landing))

       landed
       (list
        [:p.chat-change-done {:role "status"} "Published " [:code (subs landed 0 (min 12 (count landed)))]
         " to main."]
        (landing-summary "landed" landing))

       refused
       [:p.chat-change-refused {:role "alert"} "Not published: " refused]

       reverted?
       [:p.chat-change-done {:role "status"} "Edits thrown away. The frame shows main."])
     (landing-line restart)
     (when change
       (list
        (files-summary (if landing "changed since Publish" "changed, not on main") (:files change))
        (when conflicts
          [:p.chat-change-refused {:role "alert"} "Conflicts with main in "
           (interpose ", " (for [p conflicts] [:code p]))
           " — the agent resolves them in its next turn; Publish waits until then."])
        [:p.chat-change-desc
         (or desc [:em "Not described yet — the agent describes its change as it works."])]
        [:div.chat-change-acts {"data-show" (str "$" confirm " === ''")}
         [:button.btn {:type "button" "data-attr:disabled" (if running? "true" (str "$" busy))
                       "data-on:click" (str "$" confirm " = 'revert'")} "Cancel"]
         [:button.btn.btn-primary (cond-> {:type "button"
                                           "data-attr:disabled" (if held "true" (str "$" busy))
                                           "data-on:click" (str "$" confirm " = 'publish'")}
                                    (nil? desc) (assoc :title "Publish waits for the change's description")
                                    running?    (assoc :title "Publish waits for the landing that is running"))
          "Publish"]]
        [:div.chat-change-ask {"data-show" (asking "publish")}
         [:span "Land this on main? Tests and the landing check run first."]
         [:button.btn {:type "button" "data-on:click" (str "$" confirm " = ''")} "Back"]
         [:button.btn.btn-primary {:type "button" "data-attr:disabled" (if held "true" (str "$" busy))
                                   "data-on:click" (str "$" confirm " = ''; " publish)}
          "Publish"]]
        [:div.chat-change-ask {"data-show" (asking "revert")}
         [:span "Throw these edits away? The conversation stays."]
         [:button.btn {:type "button" "data-on:click" (str "$" confirm " = ''")} "Back"]
         [:button.btn.btn-danger {:type "button" "data-attr:disabled" (if running? "true" (str "$" busy))
                                  "data-on:click" (str "$" confirm " = ''; " (action :revert))}
          "Throw away"]]))]))

;; ---------------------------------------------------------------------------
;; Scenes: literal conversations

(defn- transcript
  "A literal transcript of `items`, each given the id its position would give it. A running one
   carries no start time — a scene renders the same markup at any hour, so it cannot say when
   its turn began — and so shows no elapsed time; `nido.ui.components.chat-test` covers it."
  [busy? & items]
  {:busy? busy? :items (vec (map-indexed (fn [i item] (assoc item :id (str "i" i))) items))})

(def ^:private chip
  {:label "span.pos-chip" :title "position-chip · every position · span.pos-chip"})

(def ^:private you
  {:kind :user :text "Make the needs-you chip a little quieter — it shouts beside the title."
   :refs [chip]})

(def ^:private you-two
  {:kind :user :text "These two should read as one family — same weight, same tint."
   :refs [chip
          {:label "span.row-stage" :title "row · a running workstream · span.row-stage"}]})

(def ^:private reply
  {:kind :agent :text "The chip takes `--needs` at full strength. I will move it to `--needs-dim`
for the text and keep the tint, so it still reads as **yours** without competing with the title."})

(def ^:private edit-call
  {:kind :tool :title "Edit src/nido/ui/stylesheet.clj" :status :completed :tool-kind :edit
   :body nil
   :diffs [{:path "src/nido/ui/stylesheet.clj"
            :old ".pos-you { background:var(--needs-bg); color:var(--needs); }"
            :new ".pos-you { background:var(--needs-bg); color:var(--needs-dim); }"}]})

(def ^:private asking
  {:kind :permission :title "Run bb nido:test :only nido.ui" :request-id 3 :answer nil
   :options [{:id "allow" :name "Allow" :kind :allow_once}
             {:id "always" :name "Always allow" :kind :allow_always}
             {:id "deny" :name "Deny" :kind :reject_once}]})

(def ^:private two-images
  [{:mime "image/png" :data "" :digest "a1"} {:mime "image/png" :data "" :digest "b2"}])

(defn- open-panel [t & {:as more}] (merge {:transcript t :open? true :reset? true} more))

(def ^:private a-change
  {:description "Tighten the catalog chat's composer\n\nSend sits inside the message box."
   :files [{:path "src/nido/ui/components/chat.clj" :status :modified}
           {:path "src/nido/ui/stylesheet.clj" :status :modified}
           {:path "src/nido/ui/catalog/new_scene.clj" :status :added}]})

(def ^:private a-newer-change
  {:description "Give the composer's Send a quieter border"
   :files [{:path "src/nido/ui/stylesheet.clj" :status :modified}]
   :rev "fedcba9876543210"})

;; ---------------------------------------------------------------------------
;; The registrations

(def ^{:malli/schema :map}
  panel
  "A conversation with an agent, as a side panel."
  (block/block
   {:id      scope
    :doc     "A conversation with an agent, as a side panel: a toggle that opens and closes it,
              leading a bar over its transcript, the turn's status and the composer. Closed, the
              toggle is all that shows; the page gives it the width. Reads a transcript value
              only, so a workstream pane or the catalog can host one."
    :render  render-panel
    :props   [:map
              [:transcript [:map [:items [:sequential :map]]]]
              [:open? {:optional true} :boolean]
              [:heading {:optional true} [:maybe :string]]
              [:reset? {:optional true} :boolean]
              [:attach? {:optional true} :boolean]
              [:live? {:optional true} :boolean]
              [:picks {:optional true} [:sequential :map]]
              [:staged {:optional true} [:sequential :map]]
              [:bar {:optional true} :any]
              [:top {:optional true} :any]
              [:extra {:optional true} :any]
              [:part {:optional true} [:enum :log :item :status :picks :attachments]]
              [:item {:optional true} :map]]
    :css     ""
    :actions panel-actions
    :morph   {:signals (fn [{:keys [transcript open?]}]
                         {:text "" :picks "[]" :busy (boolean (:busy? transcript))
                          :open (boolean open?) :reset false})}
    :scenes
    [{:title "empty"
      :note  "Nothing said yet: only the composer, waiting for the first message."
      :props (open-panel (transcript false))}
     {:title "sent, nothing back yet"
      :note  "The moment after Send: the message with the elements it carried, and the status row
              saying the turn is under way before the agent has said anything."
      :props (open-panel (transcript true you-two))}
     {:title "a streaming reply"
      :note  "The reply grows, the caret marks where, the thought before it has folded, the status
              row says writing, and Send is Stop."
      :props (open-panel (transcript true you
                                     {:kind :thought :text "The chip sits beside an --fs-lg title…"}
                                     (assoc reply :text "The chip takes `--needs` at full strength. I will"
                                            :streaming? true)))}
     {:title "a finished reply"
      :note  "A turn ended: the reply settled, a tool call folded, no status, and Send is back."
      :props (open-panel (transcript false you edit-call reply))}
     {:title "a permission request"
      :note  "The agent asks before a tool runs: the request and its answers, each a button, and
              the status row saying it waits for you."
      :props (open-panel (transcript true you asking))}
     {:title "an error"
      :note  "A turn that failed: what went wrong, and the composer ready again."
      :props (open-panel (transcript false you {:kind :error :text "The agent process stopped."}))}
     {:title "closed"
      :note  "Only the toggle, pointing in."
      :props (open-panel (transcript false you reply) :open? false)}
     {:title "closed while a turn runs"
      :note  "The running dot on the toggle's corner says the agent is still at work."
      :props (open-panel (transcript true you) :open? false)}
     {:title "thinking"
      :note  "The status row's phase while a thought streams."
      :props (open-panel (transcript true you {:kind :thought :streaming? true :text "The chip sits…"}))}
     {:title "running a tool"
      :note  "The status row names the tool, by its title. A running and a failed call: the failed
              one opens itself, so its output is read without a click."
      :props (open-panel (transcript true you
                                     {:kind :tool :title "Read src/missing.clj" :status :failed
                                      :body "File does not exist." :diffs []}
                                     {:kind :tool :title "bb nido:test" :status :in-progress :diffs []}))}
     {:title "an answered permission request"
      :note  "Once answered, a request folds to one line saying what was chosen."
      :props (open-panel (transcript false you
                                     {:kind :permission :title "Run bb nido:test" :answer "allow"
                                      :request-id 3 :options [{:id "allow" :name "Allow" :kind :allow_once}]}))}
     {:title "a long conversation"
      :note  "Opens on the newest message, at the top, with the reply under it and the room the
              reply still has. Scroll up for the earlier turns; your own scroll lets go."
      :props (open-panel (transcript false you edit-call reply you-two reply))}
     {:title "picks for the next message"
      :note  "Two elements picked: their chips above the text box, numbered as the page marks them,
              each removable."
      :props (open-panel (transcript false) :picks (:refs you-two))}
     {:title "images attached"
      :note  "A panel that offers attaching: a sent image as a thumbnail that opens it, two staged
              for the next message above the text box, and the Image control beside Send. The box
              takes a pasted image and the panel a dropped one."
      :props (open-panel (transcript false {:kind :user :text "What is wrong with this row?" :refs []
                                            :images (subvec two-images 0 1)})
                         :attach? true :staged two-images)}]}))

(def ^{:malli/schema :map}
  change-bar
  "The conversation's unlanded change, and what a person can do with it."
  (block/block
   {:id      :chat-change
    :doc     "The conversation's change that main does not have, above the log, and the two things
              a person can do with it: Publish lands it on main, Cancel throws it away, each
              confirmed in place. Its instance is the panel's id, and both actions are withheld
              while that panel's turn runs. Props: `:change` — `{:description :files :rev
              :conflicts}`, see `nido.session.change`, or nil for none — and `:publishing`:
              `{:running? :step :landing}` while a publish runs, `:landing` the change being
              landed; `{:landed <commit> :landing}` or `{:refused <why>}` for how the last ended,
              `:landing` shown until the worktree is back on main; `{:reverted? true}` after a
              throw-away; or {} — each beside `:restart`, the latest landing as
              `nido.coordinator.control/latest-landing` answers it. While a change lands,
              `:change` is what the conversation made since, listed apart. Publish posts the
              change's `:rev` as its query, so it lands only the change the bar showed; it is
              withheld while a publish runs, while the change has no description (it would land
              unnamed) or holds a conflict. No change, nothing to say and no landing renders the
              empty element, which takes no space."
    :render  render-change-bar
    :props   [:map [:change [:maybe :map]] [:publishing :map]]
    :css     ""
    :actions {:publish [:post :publish {}]
              :revert  [:post :revert {}]}
    :morph   {:signals {:confirm ""}}
    :scenes
    [{:title "no change"
      :note  "Nothing unlanded and nothing to say: the empty element, which takes no space."
      :props {:change nil :publishing {}}}
     {:title "a change"
      :note  "Its files fold away; what it would land as is its description."
      :props {:change a-change :publishing {}}}
     {:title "a change not yet described"
      :note  "Publish waits: an undescribed change would land unnamed."
      :props {:change (assoc a-change :description "") :publishing {}}}
     {:title "publishing"
      :note  "The landing verb's current step and the change it lands, which stays shown until the
              worktree is back on main. Nothing has been made since Publish, so there is nothing
              pending beside it."
      :props {:change nil :publishing {:running? true :step "running bb nido:test at 0123456789ab"
                                       :landing a-change}}}
     {:title "publishing, with newer edits"
      :note  "The chat stays open while a change lands: what the agent made since Publish is
              listed apart, and waits for the landing before it can be published."
      :props {:change a-newer-change
              :publishing {:running? true :step "running bb nido:test at 0123456789ab"
                           :landing a-change}}}
     {:title "conflicting with main"
      :note  "Brought onto main after a landing, the newer edits conflict: the paths are named,
              and Publish waits for the agent to resolve them."
      :props {:change (assoc a-newer-change :conflicts ["src/nido/ui/stylesheet.clj"])
              :publishing {}}}
     {:title "refused"
      :note  "Why it did not land, with the change still there to fix and publish again."
      :props {:change a-change :publishing {:refused "bb nido:test is red at the tip"}}}
     {:title "published"
      :note  "Landed: the commit it landed as, and nothing made since."
      :props {:change nil :publishing {:landed "0123456789abcdef"}}}
     {:title "published, restart owed"
      :note  "The landing's restart is owed, and the bar names what the daemon waits for."
      :props {:change nil
              :publishing {:landed  "0123456789abcdef"
                           :restart {:landing {:state :owed :commit "0123456789abcdef"
                                               :owed-at "2026-11-17T12:00:00Z" :by-pid 4242}
                                     :waits ["1 run(s) to finish: 2026-11-17-nido-review-1a2b"]}}}}
     {:title "running the landing"
      :note  "After the restart: the bar says which commit the dashboard runs, with no publish in
              its memory."
      :props {:change nil
              :publishing {:restart {:landing {:state :running :commit "0123456789abcdef"
                                               :owed-at "2026-11-17T12:00:00Z"
                                               :since "2026-11-17T12:03:10Z" :pid 5151}}}}}
     {:title "landing unreadable"
      :note  "A record nobody can read is said, never taken for no landing."
      :props {:change nil
              :publishing {:restart {:unreadable "restart-owed.edn does not hold a landing"}}}}]}))
