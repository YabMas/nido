(ns nido.ui.components.dialog
  "A dialog, as a block: a native <dialog> opened and closed by command/commandfor.

   HIDES how a dialog is opened, labelled and closed. The platform owns all three: the trigger
   names the dialog in `commandfor` and asks it to `show-modal`, the browser puts it in the top
   layer, moves focus into it, closes it on Escape — the topmost one only — and hands focus back
   to the trigger. Whether it is open lives in the DOM alone, as the element's own `open`
   attribute; no script and no signal holds it, so nothing can disagree with what is on screen.

   Every part carries data-scope and data-part, and its id, and the ARIA tying the dialog to its
   title and its body, derive from one instance id, so the title labels the dialog and two
   dialogs on a page share nothing. Its state is the presence of the element's own `open`
   attribute, which is what a rule selects an open dialog by.

   It is always modal: `show-modal` is the one command that opens a dialog, and a dialog shown
   any other way would need a script to do it.

   The caller owns what is in it. Its slots are render functions, each handed the attrs map the
   part it renders must spread — `:trigger` the control that opens it, `:body` what it says, and
   `:footer` its actions — and the footer is also handed `close`, a function of a return value
   giving the attrs of a control that closes the dialog with that value. Which control closed it
   is the dialog's one signal, `returned`, set as it closes; a host reads it like any other.

   nido's block, internal to nido: the UI library supplies the machinery it is built on and
   exports no dialog."
  (:require [nido.libraries.ui.block :as block]))

(defn- dialog-id [{:keys [part]}] (:id (part :root)))

(defn- render-dialog [{:keys [props part signal] :as kit}]
  (let [{:keys [title trigger body footer]} props
        id       (dialog-id kit)
        title-id (:id (part :title))
        body-id  (:id (part :body))
        close    (fn [value] {:type "button" :command "close" :commandfor id :value (str value)})]
    (list
     (trigger (merge (part :trigger)
                     {:type "button" :commandfor id :command "show-modal" :aria-haspopup "dialog"}))
     [:dialog.dlg (merge (part :root)
                         {:aria-labelledby title-id :aria-describedby body-id
                          "data-on:close" (str "$" (signal :returned) " = el.returnValue")})
      [:div.dlg-head (part :head)
       [:h2.dlg-title (part :title) title]
       [:button.dlg-x (merge (part :close) (close "dismiss") {:aria-label "Close"}) "✕"]]
      (body (merge (part :body) {:class "dlg-body"}))
      (when footer
        (footer (merge (part :footer) {:class "dlg-foot"}) close))])))

(def ^:private dialog-css
  ".dlg { border:1px solid var(--border); border-radius:var(--radius-6); background:var(--surface-raised);
         color:var(--text); padding:var(--space-16) var(--space-18); max-width:min(var(--measure), 92vw);
         max-height:85vh; box-shadow:var(--shadow-side); }
   .dlg[open] { display:flex; flex-direction:column; }
   .dlg::backdrop { background:color-mix(in srgb, var(--bg) 70%, transparent); }
   .dlg-head { display:flex; gap:var(--space-10); align-items:baseline; margin:0 0 var(--space-10); }
   .dlg-title { margin:0; flex:1; font-size:var(--fs-lg); color:var(--text-strong); }
   .dlg-x { flex:none; background:none; border:1px solid transparent; border-radius:var(--radius-4);
            color:var(--text-muted); cursor:pointer; padding:var(--space-2) var(--space-6); }
   .dlg-x:hover { border-color:var(--border); color:var(--text-strong); }
   .dlg-body { color:var(--text); font-size:var(--fs-md); overflow:auto; min-height:0; }
   .dlg-foot { display:flex; gap:var(--space-8); justify-content:flex-end; margin:var(--space-16) 0 0; }")

;; ---------------------------------------------------------------------------
;; Scenes

(defn- open-button [label] (fn [attrs] [:button.btn attrs label]))

(defn- prose [& ps] (fn [attrs] (into [:div attrs] (map (fn [p] [:p p]) ps))))

(defn- confirm-footer [confirm-label]
  (fn [attrs close]
    [:div attrs
     [:button.btn (close "cancel") "Back"]
     [:button.btn.btn-primary (close "confirm") confirm-label]]))

;; ---------------------------------------------------------------------------
;; The registration

(def ^{:malli/schema :map}
  dialog
  "A dialog opened by its own trigger."
  (block/block
   {:id      :dialog
    :doc     "A dialog opened by its own trigger: a native <dialog> the platform opens, focuses,
              closes on Escape and hands focus back from. Props: `:title`, which labels it;
              `:trigger`, `:body` and optionally `:footer`, render functions each handed the attrs
              its part must spread (the footer also `close`, a function of a return value giving
              a closing control's attrs)."
    :render  render-dialog
    :props   [:map
              [:title :string]
              [:trigger fn?]
              [:body fn?]
              [:footer {:optional true} fn?]]
    :css     dialog-css
    :actions {}
    :morph   {:signals {:returned ""}}
    :scenes
    [{:title "a confirmation"
      :note  "Open it, then press Escape: the browser closes it and focus returns to the trigger.
              Back and Publish close it too, each with its own return value."
      :props {:title   "Publish to main?"
              :trigger (open-button "Publish…")
              :body    (prose "Tests and the landing check run first. The frame shows main once it lands.")
              :footer  (confirm-footer "Publish")}}
     {:title "nothing to decide"
      :note  "A dialog that only says something: no footer, and the close control in its head is
              the one way out besides Escape."
      :props {:title   "What the catalog is"
              :trigger (open-button "About")
              :body    (prose "Every scene is a block rendered over literal values, in the app's own stylesheet."
                              "Nothing here reads a ledger, a session or a clock.")}}
     {:title "a long body"
      :note  "More than fits: the body scrolls inside the dialog, and the head and the actions stay
              where they are."
      :props {:title   "What a landing runs"
              :trigger (open-button "Landing steps")
              :body    (apply prose (for [step ["fetch main" "rebase the arc onto main's tip"
                                                "check for conflicts" "run bb nido:test"
                                                "run bb nido:land:check" "fast-forward main"
                                                "push main" "record the landing"
                                                "rebase the root checkout" "owe the daemon a restart"]]
                                      (str step " — and if it refuses, nothing after it runs.")))
              :footer  (confirm-footer "Land")}}]}))

