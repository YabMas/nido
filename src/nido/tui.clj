(ns nido.tui
  "Tiny charm.clj-based terminal UI over the existing session lifecycle.

   Two screens:
     :projects — registered projects (one row per project) → enter drills in
     :sessions — sessions for the active project, with the action keys

   The TUI itself does no service work. Action keys queue an action into
   `exit-action` and quit the program; the bb task wrapper (`tasks.nido-tui`)
   exits charm's alt-screen, runs the matching `nido:session:*` verb in the
   normal terminal, and re-enters the TUI."
  (:require
   [charm.components.list :as item-list]
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [charm.program :as program]
   [charm.style.core :as style]
   [clojure.string :as str]
   [nido.project :as project]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]))

;; ---------------------------------------------------------------------------
;; Action channel: update-fn writes here before returning quit-cmd; the bb
;; task wrapper reads after `program/run` returns to decide what to run next.
;; Shape: :quit | [:enter p s target] | [:up p s] | [:down p s] | [:destroy p s] | [:add p s]
;;        target = :home | :worktree
;; ---------------------------------------------------------------------------

(def ^:private exit-action (atom :quit))

(defn- queue-action!
  "Set the pending action and return charm's quit command."
  [action]
  (reset! exit-action action)
  program/quit-cmd)

;; ---------------------------------------------------------------------------
;; Data → list rows
;; ---------------------------------------------------------------------------

;; charm's list component assumes one line of description per item; embedding
;; an extra newline confuses the diff-based redraw and produces ghost rows
;; when the cursor moves. Keep descriptions to a single line and rely on
;; glyph + dim styling (charm wraps descriptions in :fg 240) for rhythm.

(defn- project-rows []
  (let [registry (state/read-registry)
        running-by-project (reduce (fn [m [_ entry]]
                                     (update m (:project-name entry) (fnil inc 0)))
                                   {}
                                   registry)]
    (->> (project/list-projects)
         (sort-by key)
         (mapv (fn [[name {:keys [directory]}]]
                 (let [running (or (running-by-project name) 0)
                       glyph (if (pos? running) "●" "○")]
                   {:title (str glyph "  " name)
                    :description (str directory "    " running " running")
                    :data {:name name :directory directory}}))))))

(defn- session-rows [project-name]
  (let [{:keys [sessions]} (lifecycle/list-all-data {:project project-name})]
    (mapv (fn [{:keys [name pg-port app-port nrepl-port] :as s}]
            (let [up? (boolean (or pg-port app-port nrepl-port))
                  glyph (if up? "●" "○")
                  parts (cond-> []
                          app-port   (conj (format "app  %s"  app-port))
                          pg-port    (conj (format "pg   %s"  pg-port))
                          nrepl-port (conj (format "repl %s"  nrepl-port)))]
              {:title (str glyph "  " name)
               :description (if up? (str/join "    " parts) "—")
               :data s}))
          sessions)))

;; ---------------------------------------------------------------------------
;; charm list component
;; ---------------------------------------------------------------------------

;; Width/height are intentionally left at 0 (charm's "unconstrained"). The
;; renderer truncates lines to the terminal width itself, and we don't need
;; vertical scrolling for the small lists we show. Keeping the list component
;; dimension-independent means our `view` output is byte-stable across the
;; startup resize message, so JLine's diff-based redraw has no work to do
;; (which avoids stale-row ghosts on first paint).
(defn- list-component [items]
  (item-list/item-list items
                       :show-descriptions true
                       :cursor-style (style/style :fg style/cyan :bold true)))

(defn- rebuild-list [state items]
  (assoc state
         :items items
         :list (list-component items)))

;; ---------------------------------------------------------------------------
;; Screen transitions
;; ---------------------------------------------------------------------------

(defn- enter-projects [state]
  (-> state
      (assoc :screen :projects :project nil :status nil)
      (rebuild-list (project-rows))))

(defn- enter-sessions [state project-name]
  (-> state
      (assoc :screen :sessions :project project-name :status nil)
      (rebuild-list (session-rows project-name))))

(defn- selected-data [state]
  (some-> (item-list/selected-item (:list state)) :data))

;; ---------------------------------------------------------------------------
;; Elm: init / update / view
;; ---------------------------------------------------------------------------

(defn- init-fn []
  [(enter-projects {}) nil])

(defn- update-projects [state msg]
  (cond
    (msg/key-match? msg "enter")
    (if-let [{:keys [name]} (selected-data state)]
      [(enter-sessions state name) nil]
      [state nil])

    :else
    (let [[lst cmd] (item-list/list-update (:list state) msg)]
      [(assoc state :list lst) cmd])))

(defn- with-selected-session [state f]
  (if-let [s (some-> (selected-data state) :name)]
    (f state (:project state) s)
    [(assoc state :status "(no session selected)") nil]))

(defn- open-confirm-destroy [state p s]
  [(assoc state :modal :confirm-destroy :modal-target {:project p :session s}) nil])

(defn- open-create-session [state p]
  [(assoc state
          :modal :create-session
          :modal-target {:project p}
          :modal-input (text-input/text-input :prompt "name: "))
   nil])

(defn- close-modal [state]
  (-> state (dissoc :modal :modal-target :modal-input)))

(defn- update-sessions [state msg]
  (cond
    (msg/key-match? msg "escape")
    [(enter-projects state) nil]

    (or (msg/key-match? msg "enter") (msg/key-match? msg "e"))
    (with-selected-session state
      (fn [s p sn] [s (queue-action! [:enter p sn :home])]))

    (msg/key-match? msg "w")
    (with-selected-session state
      (fn [s p sn] [s (queue-action! [:enter p sn :worktree])]))

    (msg/key-match? msg "u")
    (with-selected-session state
      (fn [s p sn] [s (queue-action! [:up p sn])]))

    (msg/key-match? msg "d")
    (with-selected-session state
      (fn [s p sn] [s (queue-action! [:down p sn])]))

    (msg/key-match? msg "x")
    (with-selected-session state
      (fn [s p sn] (open-confirm-destroy s p sn)))

    (msg/key-match? msg "a")
    (open-create-session state (:project state))

    :else
    (let [[lst cmd] (item-list/list-update (:list state) msg)]
      [(assoc state :list lst) cmd])))

;; ---------------------------------------------------------------------------
;; Modals — handled before the regular screen update so input is captured.
;; ---------------------------------------------------------------------------

(defn- update-confirm-destroy [state msg]
  (cond
    (or (msg/key-match? msg "y") (msg/key-match? msg "Y"))
    (let [{:keys [project session]} (:modal-target state)]
      [(close-modal state) (queue-action! [:destroy project session])])

    ;; n / esc / anything else cancels
    :else
    [(close-modal state) nil]))

(defn- update-create-session [state msg]
  (cond
    (msg/key-match? msg "escape")
    [(close-modal state) nil]

    (msg/key-match? msg "enter")
    (let [name (str/trim (text-input/value (:modal-input state)))
          {:keys [project]} (:modal-target state)]
      (if (seq name)
        [(close-modal state) (queue-action! [:add project name])]
        [(close-modal state) nil]))

    :else
    (let [[ti cmd] (text-input/text-input-update (:modal-input state) msg)]
      [(assoc state :modal-input ti) cmd])))

(defn- update-fn [state msg]
  (cond
    ;; Charm always fires a window-size on startup. Our view is
    ;; dimension-independent, so we ignore it — anything we did here would
    ;; cause a frame-to-frame state change and trigger a redraw that
    ;; ghosts under JLine's non-fullscreen Display.
    (msg/window-size? msg)
    [state nil]

    ;; ctrl+c always quits. `q` only quits when no modal is active so the
    ;; create-session text-input can accept it as a literal character.
    (msg/key-match? msg "ctrl+c")
    [state (queue-action! :quit)]

    (and (nil? (:modal state)) (msg/key-match? msg "q"))
    [state (queue-action! :quit)]

    ;; Modal takes priority over screen handlers.
    (= :confirm-destroy (:modal state))
    (update-confirm-destroy state msg)

    (= :create-session (:modal state))
    (update-create-session state msg)

    :else
    (case (:screen state)
      :projects (update-projects state msg)
      :sessions (update-sessions state msg))))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(def ^:private title-style    (style/style :fg style/magenta :bold true))
(def ^:private subtle-style   (style/style :fg 240))
(def ^:private status-style   (style/style :fg style/yellow))
(def ^:private warning-style  (style/style :fg style/red :bold true))

(defn- header [state]
  (style/render title-style
                (case (:modal state)
                  :confirm-destroy "nido — confirm destroy"
                  :create-session  (str "nido — " (-> state :modal-target :project)
                                        " · new session")
                  (case (:screen state)
                    :projects "nido — projects"
                    :sessions (str "nido — " (:project state) " · sessions")))))

(defn- footer [state]
  (style/render subtle-style
                (case (:modal state)
                  :confirm-destroy "[y] destroy  [n/esc] cancel"
                  :create-session  "[↵] create  [esc] cancel"
                  (case (:screen state)
                    :projects "[↵] open  [q]uit"
                    :sessions "[↵/e] enter  [w]orktree  [a]dd  [u]p  [d]own  [x] destroy  [esc] back  [q]uit"))))

(defn- modal-body [state]
  (case (:modal state)
    :confirm-destroy
    (let [{:keys [project session]} (:modal-target state)]
      (str (style/render warning-style "destroy ")
           project "/" session
           (style/render warning-style " ?")))

    :create-session
    (text-input/text-input-view (:modal-input state))))

(defn- view [state]
  (if (:modal state)
    (str (header state) "\n\n"
         (modal-body state) "\n\n"
         (footer state))
    (str (header state) "\n\n"
         (item-list/list-view (:list state)) "\n\n"
         (when-let [s (:status state)]
           (str (style/render status-style s) "\n"))
         (footer state))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn run-once
  "Run the TUI once. Returns the queued action — :quit | [:verb project session...].

   We render inline (no alt-screen). charm's alt-screen path runs `update-size!`
   after the initial render whenever the alt-screen-reported terminal size
   differs from the size read at renderer construction; that clears JLine
   Display's previous-content cache and the redraw lands one row below the
   first paint, leaving ghost copies of the header and footer. Inline mode
   sidesteps it cleanly."
  []
  (reset! exit-action :quit)
  (program/run {:init     init-fn
                :update   update-fn
                :view     view
                :alt-screen false})
  @exit-action)
