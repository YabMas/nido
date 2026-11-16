(ns nido.ui.components.chat
  "A conversation with an agent, as a panel: its transcript, the state of the running turn, then
   the composer.

   HIDES how a transcript reads — the order of its items, the turn's status row, how a reply
   streams, reasoning and tool calls disclosed, the permission prompt's buttons, the composer and
   its pick chips — and the page-state keys the composer is held under.

   Reads nothing. It takes a transcript value (`nido.acp.transcript` describes it, and this
   namespace reads only that) or a vector of picks, plus where its controls post, so the catalog,
   a workstream pane or a question card can each host one without the panel knowing which. Every
   item renders with its transcript id as its DOM id, and the status row and the chips each have
   one id of their own, which is what lets a stream patch one of them in place.

   Disclosure follows what is happening: a thought is open while it streams and collapsed once it
   is finished; a tool call is collapsed unless it failed. The status row names the turn's phase
   from the transcript alone; the seconds it shows are counted by the browser from the turn's
   start, which the transcript carries.

   The composer's page state is three signals under the panel's key — the draft, the picks (a
   JSON array, each pick `{block scene path label}` and whatever else its host keeps) and whether a
   turn is running — named by `signals`. A server reading a send reads them back by those names;
   one that renders the chips (`picks`) is posted the picks signal. Send and Stop switch on the
   busy signal, so a turn starting or ending never re-renders the composer and never loses a
   draft or a pick."
  (:require [clojure.string :as str]
            [nido.ui.markdown :as md]))

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  signals
  "The names of a panel's page-state signals — `:text` (the draft), `:picks` (the picks, as JSON
   text) and `:busy` (whether a turn is running) — for the panel `id`. Each panel on a page gets
   its own, so two panels never share a draft."
  [id]
  (let [k (str/replace id #"[^A-Za-z0-9]" "")]
    {:text (str k "Text") :picks (str k "Picks") :busy (str k "Busy")}))

(defn- item-dom-id [panel-id item] (str panel-id "-" (:id item)))

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

(defn- permission-item [{:keys [id title options answer]} {:keys [answer-url]}]
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
                       "data-on:click" (str "@post('" (answer-url id oid) "')")}
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

(defn ^{:malli/schema [:=> [:cat :map :map] :any]}
  chat-item
  "One transcript item, under the DOM id the panel gives it. `routes` carries the panel's `:id`
   and `:answer-url`, a function of (item id, option id) giving where answering a permission
   request posts."
  [item {:keys [id] :as routes}]
  (let [dom-id (item-dom-id id item)]
    (case (:kind item)
      :user       [:div.chat-msg.chat-you {:id dom-id}
                   (sent-picks (:refs item))
                   (md/render (:text item))]
      :agent      [:div.chat-msg.chat-agent {:id dom-id}
                   (md/render (:text item))
                   (when (:streaming? item) [:span.chat-caret {:title "writing"} "▍"])]
      :thought    [:details.chat-thought (cond-> {:id dom-id} (:streaming? item) (assoc :open true))
                   [:summary (if (:streaming? item) "Thinking…" "Thought")]
                   [:div.chat-thought-text (md/render (:text item))]]
      :tool       [:div {:id dom-id} (tool-item item)]
      :permission [:div {:id dom-id} (permission-item item routes)]
      :error      [:div.chat-err {:id dom-id} [:span.chat-st.chat-st-failed {:title "failed"} "⚠"]
                   [:span (:text item)]]
      :note       [:p.chat-note {:id dom-id} (:text item)]
      [:div {:id dom-id}])))

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

(defn ^{:malli/schema [:=> [:cat :string :Conversation] :any]}
  turn-status
  "The panel's status row, under the DOM id `<id>-status`: while a turn runs, its phase — waiting,
   thinking, running a named tool, writing, waiting for the person's answer, or working — and the
   seconds since it started; empty when no turn runs. A polite live region, so a screen reader
   hears the turn start, change phase and end, and never each token."
  [id {:keys [busy? started-at] :as transcript}]
  [:div.chat-status {:id (str id "-status") :role "status" :aria-live "polite"}
   (when busy?
     (let [[ph label] (phase transcript)]
       (list [:span {:class (str "chat-spin chat-spin-" (name ph)) :aria-hidden "true"}]
             [:span.chat-status-l label]
             (when started-at
               [:span.chat-elapsed {:data-since (str started-at)
                                    "data-init" elapsed-js
                                    "data-on-interval__duration.1s" elapsed-js}]))))])

(defn ^{:malli/schema [:=> [:cat :string [:sequential :map] :map] :any]}
  picks
  "The composer's pick chips, under the DOM id `<id>-picks`: one per pick, numbered from 1 in the
   order given — the number the host marks the same element with — each with its label and a
   control that posts to `(:unpick-url routes)` of its index. Nothing when there are none."
  [id ps {:keys [unpick-url]}]
  (into [:div.chat-picks {:id (str id "-picks")}]
        (map-indexed (fn [i {:keys [label title]}]
                       [:span.chat-pick {:title (or title label)}
                        [:span.chat-pick-n (inc i)]
                        [:span.chat-pick-l label]
                        [:button.chat-pick-x {:title "Remove" :type "button"
                                              "data-on:click" (str "@post('" (unpick-url i) "')")}
                         "✕"]])
                     ps)))

(defn ^{:malli/schema [:=> [:cat :string :map] :any]}
  composer
  "The composer, under the DOM id the panel gives it: the pick chips, the text box, and Send —
   or Stop while a turn runs, switched by the busy signal rather than by re-rendering."
  [id {:keys [send-url cancel-url] :as routes}]
  (let [{:keys [text busy]} (signals id)]
    [:div.chat-compose {:id (str id "-compose")}
     (picks id [] routes)
     [:div.chat-field
      [:textarea {"data-bind" text :rows 3 :placeholder "Message the agent"
                  "data-on:keydown" (str "if (!$" busy " && (evt.metaKey || evt.ctrlKey) && evt.key === 'Enter') "
                                         "{ evt.preventDefault(); @post('" send-url "') }")}]
      [:div.chat-actions
       [:button.btn.btn-primary {"data-show" (str "!$" busy)
                                 "data-on:click" (str "@post('" send-url "')")} "Send"]
       [:button.btn {"data-show" (str "$" busy)
                     "data-on:click" (str "@post('" cancel-url "')")} "Stop"]]]]))

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

(defn ^{:malli/schema [:=> [:cat :string [:sequential :map] :map] :any]}
  chat-log
  "The transcript, under the DOM id `<id>-log`: each item as `chat-item` renders it, in a log that
   scrolls the way `scroll-js` says. A stream re-rendering the whole log renders it with this, so
   the morph keeps the log's scrolling."
  [id items routes]
  (into [:div.chat-log {:id (str id "-log") "data-init" scroll-js}] (map #(chat-item % routes) items)))

(defn ^{:malli/schema [:=> [:cat :Conversation :map] :any]}
  chat-panel
  "The whole panel: its transcript, the turn's status row, then the composer. `opts` carries the
   panel's `:id`, the `:send-url` and `:cancel-url` its buttons post to, `:answer-url` (see
   `chat-item`), `:unpick-url` (see `picks`), and the `:stream-url` that keeps it live, or nil
   for a panel that is not — a catalog scene."
  [{:keys [items busy?] :as transcript} {:keys [id stream-url] :as opts}]
  (let [{:keys [text picks busy]} (signals id)]
    [:section.chat (cond-> {:id id
                            "data-signals" (str "{" text ": '', " picks ": '[]', " busy ": "
                                                (boolean busy?) "}")}
                     stream-url (assoc "data-init" (str "@get('" stream-url "')")))
     (chat-log id items opts)
     (turn-status id transcript)
     (composer id opts)]))

(defn- first-line [s] (some-> s str/trim not-empty str/split-lines first))

(defn ^{:malli/schema [:=> [:cat :string [:maybe :map] :map :map] :any]}
  change-bar
  "The conversation's unlanded change, under the DOM id `<id>-change`, and the two things a person
   can do with it: publish it to main or throw it away, each asked to confirm first. Renders from
   `change` — `{:description :files}`, see `nido.session.change`, or nil for none — and
   `publishing`: `{:running? :step}` while a publish runs, its last `land · ` step as the status;
   `{:landed <commit>}` or `{:refused <why>}` for how the last one ended; `{:reverted? true}` after a
   throw-away; or {} for nothing to say.

   No change and nothing to say renders the empty element, which takes no space. Publish is
   withheld while the change has no description, since it would land unnamed, and both actions
   are withheld while a turn runs (the panel's busy signal) — a change the agent is still making is
   not one to land or lose. The confirm state is the page's alone: one signal, `<id>Confirm`."
  [id change {:keys [running? step landed refused reverted?]} {:keys [publish-url revert-url]}]
  (let [{:keys [busy]} (signals id)
        confirm  (str (str/replace id "-" "") "Confirm")
        n        (count (:files change))
        desc     (first-line (:description change))
        asking   (fn [v] (str "$" confirm " === '" v "'"))]
    [:section.chat-change {:id (str id "-change") "data-signals" (str "{" confirm ": ''}")}
     (cond
       running?
       [:p.chat-change-status {:role "status"} [:span.chat-spin {:aria-hidden "true"}]
        "Publishing · " (or step "starting")]

       landed
       [:p.chat-change-done {:role "status"} "Published " [:code (subs landed 0 (min 12 (count landed)))]
        " to main. The frame shows main now; the rest of the dashboard follows its next restart."]

       refused
       [:p.chat-change-refused {:role "alert"} "Not published: " refused]

       reverted?
       [:p.chat-change-done {:role "status"} "Edits thrown away. The frame shows main."])
     (when (and change (not running?))
       (list
        [:details.chat-change-files
         [:summary (str n (if (= 1 n) " file" " files") " changed, not on main")]
         [:ul (for [{:keys [path status]} (:files change)]
                [:li [:span.chat-change-st (name status)] " " [:code path]])]]
        [:p.chat-change-desc
         (or desc [:em "Not described yet — the agent describes its change as it works."])]
        [:div.chat-change-acts {"data-show" (str "$" confirm " === ''")}
         [:button.btn {:type "button" "data-attr:disabled" (str "$" busy)
                       "data-on:click" (str "$" confirm " = 'revert'")} "Cancel"]
         [:button.btn.btn-primary (cond-> {:type "button"
                                           "data-attr:disabled" (if desc (str "$" busy) "true")
                                           "data-on:click" (str "$" confirm " = 'publish'")}
                                    (nil? desc) (assoc :title "Publish waits for the change's description"))
          "Publish"]]
        [:div.chat-change-ask {"data-show" (asking "publish")}
         [:span "Land this on main? Tests and the landing check run first."]
         [:button.btn {:type "button" "data-on:click" (str "$" confirm " = ''")} "Back"]
         [:button.btn.btn-primary {:type "button" "data-attr:disabled" (str "$" busy)
                                   "data-on:click" (str "$" confirm " = ''; @post('" publish-url "')")}
          "Publish"]]
        [:div.chat-change-ask {"data-show" (asking "revert")}
         [:span "Throw these edits away? The conversation stays."]
         [:button.btn {:type "button" "data-on:click" (str "$" confirm " = ''")} "Back"]
         [:button.btn.btn-danger {:type "button" "data-attr:disabled" (str "$" busy)
                                  "data-on:click" (str "$" confirm " = ''; @post('" revert-url "')")}
          "Throw away"]]))]))
