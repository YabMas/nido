(ns nido.ui.components.chat
  "A conversation with an agent, as a panel: its transcript, then the composer.

   HIDES how a transcript reads — the order of its items, how a reply streams, a tool call
   collapsed and opened, the permission prompt's buttons, the composer and the element attached to
   it — and the page-state keys the composer is held under.

   Reads nothing. It takes a transcript value (`nido.acp.transcript` describes its items, and this
   namespace reads only those) plus where its controls post, so the catalog, a workstream pane or a
   question card can each host one without the panel knowing which. Every item renders with its
   transcript id as its DOM id, which is what lets a stream patch one item in place.

   The composer's page state is three signals under the panel's key — the text being written, the
   attached reference and its label — named by `signals`, which a server reading a send reads back
   by the same names."
  (:require [clojure.string :as str]
            [nido.ui.markdown :as md]))

(defn ^{:malli/schema [:=> [:cat :string] :map]}
  signals
  "The names of a panel's page-state signals — the text, the attached reference and its label —
   for the panel `id`. Each panel on a page gets its own, so two panels never share a draft."
  [id]
  (let [k (str/replace id #"[^A-Za-z0-9]" "")]
    {:text (str k "Text") :ref (str k "Ref") :ref-label (str k "RefLabel")}))

(defn- item-dom-id [panel-id item] (str panel-id "-" (:id item)))

(def ^:private tool-status
  "A tool call's status as its mark: a glyph, the class that colours it, and its words."
  {:pending     ["○" "chat-st-waiting" "waiting to run"]
   :in-progress ["●" "chat-st-running" "running"]
   :completed   ["✓" "chat-st-ok" "done"]
   :failed      ["✗" "chat-st-failed" "failed"]})

(defn- tool-item [{:keys [title status body diffs]}]
  (let [[glyph cls words] (tool-status status (tool-status :pending))]
    [:details.chat-tool
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

(defn ^{:malli/schema [:=> [:cat :map :map] :any]}
  chat-item
  "One transcript item, under the DOM id the panel gives it. `routes` carries the panel's `:id`
   and `:answer-url`, a function of (item id, option id) giving where answering a permission
   request posts."
  [item {:keys [id] :as routes}]
  (let [dom-id (item-dom-id id item)]
    (case (:kind item)
      :user       [:div.chat-msg.chat-you {:id dom-id}
                   (when-let [c (:context item)] [:div.chat-ref {:title c} (first (str/split-lines c))])
                   (md/render (:text item))]
      :agent      [:div.chat-msg.chat-agent {:id dom-id}
                   (md/render (:text item))
                   (when (:streaming? item) [:span.chat-caret {:title "writing"} "▍"])]
      :thought    [:details.chat-thought {:id dom-id}
                   [:summary "Thinking" (when (:streaming? item) [:span.chat-caret {:title "thinking"} "▍"])]
                   [:div.chat-thought-text (md/render (:text item))]]
      :tool       [:div {:id dom-id} (tool-item item)]
      :permission [:div {:id dom-id} (permission-item item routes)]
      :error      [:div.chat-err {:id dom-id} [:span.chat-st.chat-st-failed {:title "failed"} "⚠"]
                   [:span (:text item)]]
      [:div {:id dom-id}])))

(defn ^{:malli/schema [:=> [:cat :string :boolean :map] :any]}
  composer
  "The composer, under the DOM id the panel gives it: the attached element (when one is), the text
   box, and Send — or Stop while a turn runs. Re-rendered whole when `busy?` changes."
  [id busy? {:keys [send-url cancel-url]}]
  (let [{:keys [text ref ref-label]} (signals id)]
    [:div.chat-compose {:id (str id "-compose")}
     [:div.chat-ref.attached {"data-show" (str "$" ref " !== ''")}
      [:span {"data-text" (str "$" ref-label)}]
      [:button.chat-ref-x {:title "Detach"
                           "data-on:click" (str "$" ref " = ''; $" ref-label " = ''")} "✕"]]
     [:textarea (cond-> {"data-bind" text :rows 3 :placeholder "Ask for a change…"}
                  (not busy?) (assoc "data-on:keydown"
                                     (str "if ((evt.metaKey || evt.ctrlKey) && evt.key === 'Enter') "
                                          "{ evt.preventDefault(); @post('" send-url "') }")))]
     [:div.chat-actions
      (if busy?
        [:button.btn {"data-on:click" (str "@post('" cancel-url "')")} "Stop"]
        [:button.btn.btn-primary {"data-on:click" (str "@post('" send-url "')")} "Send"])]]))

(defn ^{:malli/schema [:=> [:cat :Conversation :map] :any]}
  chat-panel
  "The whole panel: its transcript, then the composer. `opts` carries the panel's `:id`, the
   `:send-url` and `:cancel-url` its buttons post to, `:answer-url` (see `chat-item`), and the
   `:stream-url` that keeps it live, or nil for a panel that is not — a catalog scene."
  [{:keys [items busy?]} {:keys [id stream-url] :as opts}]
  (let [{:keys [text ref ref-label]} (signals id)]
    [:section.chat (cond-> {:id id
                            "data-signals" (str "{" text ": '', " ref ": '', " ref-label ": ''}")}
                     stream-url (assoc "data-init" (str "@get('" stream-url "')")))
     (into [:div.chat-log {:id (str id "-log")}] (map #(chat-item % opts) items))
     (composer id busy? opts)]))
