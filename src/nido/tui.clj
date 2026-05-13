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
   [babashka.fs :as fs]
   [charm.components.list :as item-list]
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [charm.program :as program]
   [charm.style.core :as style]
   [clojure.pprint]
   [clojure.string :as str]
   [nido.ci.lifecycle :as ci-lifecycle]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.runs-view :as runs-view]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.triggers :as triggers]
   [nido.project :as project]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.links :as links]
   [nido.session.state :as state]))

;; ---------------------------------------------------------------------------
;; Action channel: update-fn writes here before returning quit-cmd; the bb
;; task wrapper reads after `program/run` returns to decide what to run next.
;; Shape: :quit | [:enter p s target] | [:enter-run p s target]
;;        | [:up p s] | [:down p s] | [:destroy p s]
;;        | [:add p s] | [:ci kind p s]   ; kind ∈ #{:run :rerun}
;;        target = :home | :worktree
;; :enter-run is the runs-screen variant of :enter — same handoff, but
;; the highlighted row is a Run so the project comes from (:project run)
;; (a keyword) rather than (:project state) (a string).
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

(defn- run-owned-session-names
  "Set of session-names owned by a Run for the given project. Derived from
   ~/.nido/runs/*/run.edn — the durable indicator that a session is
   Run-owned (the launcher's :owned-by-run flag is only threaded in-memory
   into session-home artifacts, not persisted to the session registry).
   Returns #{} on any read failure so the sessions screen degrades quietly."
  [project-name]
  (try
    (let [proj-kw (keyword project-name)]
      (into #{}
            (comp (filter #(= proj-kw (:project %)))
                  (map :session-name))
            (runs-view/read-all-runs)))
    (catch Exception _ #{})))

(defn- session-rows [project-name]
  (let [{:keys [sessions]} (lifecycle/list-all-data {:project project-name})
        run-owned? (run-owned-session-names project-name)]
    (mapv (fn [{:keys [name pg-port app-port nrepl-port] :as s}]
            (let [up? (boolean (or pg-port app-port nrepl-port))
                  glyph (if up? "●" "○")
                  marker (when (run-owned? name) "⚙ ")
                  parts (cond-> []
                          app-port   (conj (format "app  %s"  app-port))
                          pg-port    (conj (format "pg   %s"  pg-port))
                          nrepl-port (conj (format "repl %s"  nrepl-port)))]
              {:title (str glyph "  " marker name)
               :description (if up? (str/join "    " parts) "—")
               :data s}))
          sessions)))

;; Group-header and empty-state sentinels. Both are namespace-qualified
;; keywords so `selected-run` can filter them out — the list component
;; renders them as ordinary rows but they shouldn't fire row actions.
(defn- run-group-rows
  "Header row + one row per Run for a single group. Returns nil when the
   group is empty so the caller can `concat` without conditionals."
  [label runs]
  (when (seq runs)
    (cons {:title       (str "── " label " (" (count runs) ") ──")
           :description ""
           :data        ::group-header}
          (mapv (fn [r]
                  {:title       (runs-view/format-row r)
                   :description (or (some-> r :state-history last :at) "")
                   :data        r})
                runs))))

(defn- run-rows []
  (let [groups (runs-view/grouped-runs (runs-view/read-all-runs))
        rows   (concat
                (run-group-rows "Needs attention" (:needs-attention groups))
                (run-group-rows "In flight"       (:in-flight groups))
                (run-group-rows "Recent"          (:recent groups)))]
    (if (empty? rows)
      [{:title       "No runs yet. Press 'f' to fire a manual trigger."
        :description ""
        :data        ::empty}]
      (vec rows))))

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

(defn- set-screen
  "Switch to `screen` and rebuild the embedded list with that screen's rows.
   Also clears any open modal so tab-style nav can't trap us behind a panel.
   Used by the `r`/`s` keybindings in `update-fn`."
  [state screen]
  (let [rows (case screen
               :runs     (run-rows)
               :sessions (session-rows (:project state))
               :projects (project-rows))]
    (-> state
        (assoc :screen screen :status nil)
        (rebuild-list rows)
        (dissoc :modal :modal-target :modal-input))))

(defn- selected-data [state]
  (some-> (item-list/selected-item (:list state)) :data))

(defn- selected-run
  "Returns the highlighted row's Run map, or nil when the highlighted row
   is a group-header / empty-state sentinel. Tasks 4-6 use this to decide
   whether ↵/w/d should fire."
  [state]
  (let [data (selected-data state)]
    (when (and data (not (#{::group-header ::empty} data)))
      data)))

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

(defn- prior-failed-run-summary
  "Read the highlighted session's last-run manifest. Returns the
   `nido.ci.lifecycle/last-run-summary` map only when there's at least
   one failed/errored/interrupted step (i.e. rerun would be meaningful).
   Manifests live under the instance state-dir which persists across
   `down`, so this works regardless of whether the session is currently
   up. Any error swallows to nil — the probe must never block CI."
  [{:keys [worktree]}]
  (when worktree
    (try
      (let [instance-id (engine/resolve-instance-id worktree)
            state-dir   (state/instance-state-dir instance-id)
            summary     (ci-lifecycle/last-run-summary
                         {:instance-state-dir state-dir})]
        (when (seq (:failed-step-names summary)) summary))
      (catch Exception _ nil))))

(defn- open-ci-picker [state p s summary]
  [(assoc state
          :modal :ci-picker
          :modal-target {:project p :session s :summary summary})
   nil])

(defn- open-session-info
  "Snapshot the highlighted session's data into the modal so the panel
   stays consistent if the registry shifts underneath."
  [state p s]
  (let [data (selected-data state)]
    [(assoc state
            :modal :session-info
            :modal-target {:project p :session s :data data})
     nil]))

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

    (msg/key-match? msg "c")
    (with-selected-session state
      (fn [s p sn]
        (if-let [summary (prior-failed-run-summary (selected-data state))]
          (open-ci-picker s p sn summary)
          [s (queue-action! [:ci :run p sn])])))

    (msg/key-match? msg "i")
    (with-selected-session state
      (fn [s p sn] (open-session-info s p sn)))

    (msg/key-match? msg "a")
    (open-create-session state (:project state))

    :else
    (let [[lst cmd] (item-list/list-update (:list state) msg)]
      [(assoc state :list lst) cmd])))

;; ---------------------------------------------------------------------------
;; Fire-trigger modal (runs screen, `f` key)
;;
;; Three sub-states cooperate to walk the user through:
;;   :fire-pick-project  — choose project (skipped when only one is registered)
;;   :fire-pick-trigger  — choose a :manual trigger from that project
;;   :fire-input-payload — fill placeholder kwargs one field at a time
;; The final state enqueues an envelope via `queue/enqueue!` and surfaces a
;; status-bar message so the user can confirm by pressing `r` to refresh.
;; Defined here (rather than alongside the other modal handlers below) so
;; `update-runs` can call `open-fire-trigger` without a forward declaration.
;; ---------------------------------------------------------------------------

(defn- placeholder-keys
  "Return ordered vector of placeholder names from a trigger's :payload
   template. `{{event/url}}` → `:url`. Top-level keys only — slash-paths
   are not addressable from the form."
  [payload-template]
  (->> (re-seq #"\{\{event/([^}/]+)\}\}" payload-template)
       (map second)
       distinct
       (mapv keyword)))

(defn- queued-status
  "One-line status message confirming an envelope was enqueued."
  [project trigger-name]
  (str "queued: " (name project) "/" (name trigger-name)
       " — refresh with 'r' to see it"))

(defn- start-payload-input
  "Either enqueue immediately (no placeholders) or open the payload-input
   sub-modal seeded with the first field's text-input."
  [state project trigger]
  (let [ks (placeholder-keys (:payload trigger))]
    (if (empty? ks)
      (do
        (queue/enqueue! {:target  {:project project :trigger (:name trigger)}
                         :payload {}})
        [(-> state
             close-modal
             (assoc :status (queued-status project (:name trigger))))
         nil])
      [(-> state
           (assoc :modal :fire-input-payload)
           (assoc :modal-target {:project project
                                 :trigger trigger
                                 :keys    ks
                                 :idx     0
                                 :values  {}})
           (assoc :modal-input
                  (text-input/text-input :prompt (str (name (first ks)) ": "))))
       nil])))

(defn- open-fire-pick-trigger
  "Load the project's manual triggers and open the trigger-picker.
   When none exist, open the picker in an error state so esc still closes."
  [state project-str]
  (let [project-kw (keyword project-str)
        trigs      (->> (triggers/load-for-project project-kw)
                        (filter #(= :manual (-> % :source :type)))
                        vec)]
    (if (empty? trigs)
      [(-> state
           (assoc :modal :fire-pick-trigger)
           (assoc :modal-target {:project project-kw
                                 :triggers []
                                 :error "(no manual triggers for this project)"}))
       nil]
      [(-> state
           (assoc :modal :fire-pick-trigger)
           (assoc :modal-target {:project project-kw
                                 :triggers trigs
                                 :cursor 0}))
       nil])))

(defn- open-fire-trigger
  "Entry point bound to `f` on the runs screen. Routes to the project
   picker when more than one project is registered; otherwise skips
   straight to the trigger picker."
  [state]
  (let [projects (vec (sort (keys (project/list-projects))))]
    (cond
      (empty? projects)
      [state nil]

      (= 1 (count projects))
      (open-fire-pick-trigger state (first projects))

      :else
      [(-> state
           (assoc :modal :fire-pick-project)
           (assoc :modal-target {:projects projects :cursor 0}))
       nil])))

(defn- update-fire-pick-project [state msg]
  (let [{:keys [projects cursor]} (:modal-target state)]
    (cond
      (msg/key-match? msg "escape") [(close-modal state) nil]

      (msg/key-match? msg "up")
      [(assoc-in state [:modal-target :cursor] (max 0 (dec cursor))) nil]

      (msg/key-match? msg "down")
      [(assoc-in state [:modal-target :cursor]
                 (min (dec (count projects)) (inc cursor))) nil]

      (msg/key-match? msg "enter")
      (open-fire-pick-trigger state (nth projects cursor))

      :else [state nil])))

(defn- update-fire-pick-trigger [state msg]
  (let [{:keys [project triggers cursor]} (:modal-target state)]
    (cond
      (msg/key-match? msg "escape") [(close-modal state) nil]

      (or (empty? triggers) (nil? cursor)) [state nil]

      (msg/key-match? msg "up")
      [(assoc-in state [:modal-target :cursor] (max 0 (dec cursor))) nil]

      (msg/key-match? msg "down")
      [(assoc-in state [:modal-target :cursor]
                 (min (dec (count triggers)) (inc cursor))) nil]

      (msg/key-match? msg "enter")
      (start-payload-input state project (nth triggers cursor))

      :else [state nil])))

(defn- update-fire-input-payload [state msg]
  (let [{:keys [project trigger keys idx values]} (:modal-target state)]
    (cond
      (msg/key-match? msg "escape")
      [(close-modal state) nil]

      (msg/key-match? msg "enter")
      (let [v        (str/trim (text-input/value (:modal-input state)))
            k        (nth keys idx)
            values'  (assoc values k v)
            next-idx (inc idx)]
        (if (< next-idx (count keys))
          [(-> state
               (assoc-in [:modal-target :values] values')
               (assoc-in [:modal-target :idx] next-idx)
               (assoc :modal-input
                      (text-input/text-input
                       :prompt (str (name (nth keys next-idx)) ": "))))
           nil]
          (do
            (queue/enqueue! {:target  {:project project :trigger (:name trigger)}
                             :payload values'})
            [(-> state
                 close-modal
                 (assoc :status (queued-status project (:name trigger))))
             nil])))

      :else
      (let [[ti cmd] (text-input/text-input-update (:modal-input state) msg)]
        [(assoc state :modal-input ti) cmd]))))

(defn- update-runs
  "Runs screen update handler. ↵ enters the highlighted Run's session-home;
   w enters its worktree. Both are terminal — they queue an `:enter-run`
   action whose bb-wrapper arm writes ~/.nido/.last-cd and the parent
   shell wrapper picks it up. Group-header / empty-state rows are guarded
   by `selected-run` returning nil. Other keys fall through to the list
   component for arrow-key navigation."
  [state msg]
  (cond
    (msg/key-match? msg "enter")
    (if-let [run (selected-run state)]
      [state (queue-action! [:enter-run (name (:project run)) (:session-name run) :home])]
      [state nil])

    (msg/key-match? msg "w")
    (if-let [run (selected-run state)]
      [state (queue-action! [:enter-run (name (:project run)) (:session-name run) :worktree])]
      [state nil])

    (msg/key-match? msg "d")
    (if-let [run (selected-run state)]
      [(-> state
           (assoc :modal :run-details)
           (assoc :modal-target {:run run}))
       nil]
      [state nil])

    (msg/key-match? msg "f")
    (open-fire-trigger state)

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

(defn- update-ci-picker [state msg]
  (cond
    (msg/key-match? msg "r")
    (let [{:keys [project session]} (:modal-target state)]
      [(close-modal state) (queue-action! [:ci :rerun project session])])

    (msg/key-match? msg "n")
    (let [{:keys [project session]} (:modal-target state)]
      [(close-modal state) (queue-action! [:ci :run project session])])

    ;; esc / anything else cancels
    :else
    [(close-modal state) nil]))

(defn- update-session-info
  "Only `esc` closes the info panel; other keys are swallowed so the
   modal stays open until explicitly dismissed."
  [state msg]
  (if (msg/key-match? msg "escape")
    [(close-modal state) nil]
    [state nil]))

(defn- update-run-details
  "Only `esc` closes the read-only run-details modal; other keys are
   swallowed so the panel stays put until explicitly dismissed."
  [state msg]
  (if (msg/key-match? msg "escape")
    [(close-modal state) nil]
    [state nil]))

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

    (= :ci-picker (:modal state))
    (update-ci-picker state msg)

    (= :session-info (:modal state))
    (update-session-info state msg)

    (= :run-details (:modal state))
    (update-run-details state msg)

    (= :create-session (:modal state))
    (update-create-session state msg)

    (= :fire-pick-project (:modal state))
    (update-fire-pick-project state msg)

    (= :fire-pick-trigger (:modal state))
    (update-fire-pick-trigger state msg)

    (= :fire-input-payload (:modal state))
    (update-fire-input-payload state msg)

    ;; Tab-style navigation between screens. Always available (outside modals),
    ;; so users can flip from any screen to runs and back to sessions when a
    ;; project context exists. Without a project the sessions screen has
    ;; nothing to show, so the `s` switch is gated on `(:project state)`.
    (and (nil? (:modal state)) (msg/key-match? msg "r") (not= :runs (:screen state)))
    [(set-screen state :runs) nil]

    (and (nil? (:modal state)) (msg/key-match? msg "s") (not= :sessions (:screen state)) (:project state))
    [(set-screen state :sessions) nil]

    :else
    (case (:screen state)
      :projects (update-projects state msg)
      :sessions (update-sessions state msg)
      :runs     (update-runs state msg))))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(def ^:private title-style    (style/style :fg style/magenta :bold true))
(def ^:private subtle-style   (style/style :fg 240))
(def ^:private status-style   (style/style :fg style/yellow))
(def ^:private warning-style  (style/style :fg style/red :bold true))
(def ^:private label-style    (style/style :fg 244))

(defn- status-bar
  "Top-of-runs-screen line showing the coordinator daemon's reachability
   and current slots-in-use count. `:unreachable` renders in warning-style
   (red) so it's visually obvious when the daemon is down."
  []
  (let [{:keys [status reachable? slots-in-use]} (runs-view/read-coordinator-status)]
    (str (style/render label-style "Coordinator: ")
         (style/render
          (if reachable? status-style warning-style)
          (name status))
         "  •  "
         (style/render label-style "Slots: ")
         (or slots-in-use 0))))

(defn- header [state]
  (style/render title-style
                (case (:modal state)
                  :confirm-destroy "nido — confirm destroy"
                  :create-session  (str "nido — " (-> state :modal-target :project)
                                        " · new session")
                  :ci-picker       (str "nido — " (-> state :modal-target :project)
                                        " · ci · " (-> state :modal-target :session))
                  :session-info    (str "nido — " (-> state :modal-target :project)
                                        " · " (-> state :modal-target :session)
                                        " · info")
                  :run-details     (str "nido — run · " (-> state :modal-target :run :id))
                  :fire-pick-project
                  "nido — fire trigger · pick project"
                  :fire-pick-trigger
                  (str "nido — fire trigger · "
                       (name (-> state :modal-target :project)))
                  :fire-input-payload
                  (str "nido — fire trigger · "
                       (name (-> state :modal-target :project)) " · "
                       (name (-> state :modal-target :trigger :name)))
                  (case (:screen state)
                    :projects "nido — projects"
                    :sessions (str "nido — " (:project state) " · sessions")
                    :runs     "nido — runs"))))

(defn- footer [state]
  (style/render subtle-style
                (case (:modal state)
                  :confirm-destroy    "[y] destroy  [n/esc] cancel"
                  :create-session     "[↵] create  [esc] cancel"
                  :ci-picker          "[r] rerun failed  [n] run all  [esc] cancel"
                  :session-info       "[esc] back"
                  :run-details        "[esc] back"
                  :fire-pick-project  "[↑↓] move  [↵] pick  [esc] cancel"
                  :fire-pick-trigger  "[↑↓] move  [↵] pick  [esc] cancel"
                  :fire-input-payload "[↵] next field  [esc] cancel"
                  (case (:screen state)
                    :projects "[↵] open  [r]uns  [q]uit"
                    :sessions "[↵/e] enter  [w]orktree  [i]nfo  [a]dd  [u]p  [d]own  [c]i  [x] destroy  [r]uns  [esc] back  [q]uit"
                    :runs     "[↵] enter session  [w]orktree  [d]etails  [f]ire trigger  [s]essions  [q]uit"))))

(defn- info-row [label value]
  (str (style/render label-style (format "%-13s" label)) " " value))

(defn- session-link-entries
  "Read the highlighted session's links by deriving instance-id from
   worktree. Returns [] when worktree is nil or any error escapes —
   the panel must never throw."
  [worktree]
  (when worktree
    (try
      (links/read-links (engine/resolve-instance-id worktree))
      (catch Exception _ []))))

(def ^:private link-indent "              ")

(defn- render-link-rows
  "Sequence of strings — one type-header row plus one row per link.
   Empty when entries is empty; only types with entries are emitted."
  [entries]
  (when (seq entries)
    (apply concat
           (for [[t ls] (links/group-by-type entries)]
             (cons (str link-indent
                        (style/render label-style
                                      (links/display-labels t (name t))))
                   (for [{:keys [url title]} ls]
                     (str link-indent "  " url
                          (when (seq title) (str " — " title)))))))))

(defn- session-info-body
  "Render the read-only session-info panel. `data` is the session row
   (`:name :worktree :pg-port :app-port :nrepl-port :repl-pid`) snapshotted
   at modal open time. `app-port` nil means the session is down — every
   live-port row falls back to `—` and the dev URL is omitted."
  [project session data]
  (let [{:keys [worktree app-port pg-port nrepl-port]} data
        up?      (boolean (or pg-port app-port nrepl-port))
        dash     "—"
        glyph    (if up? "●" "○")
        status   (if up? "up" "down")
        dev-url  (if app-port (str "http://localhost:" app-port) dash)
        home     (state/session-home-dir project session)
        entries  (session-link-entries worktree)
        link-rows (render-link-rows entries)]
    (str/join "\n"
              (concat
               [(info-row "session"      (str project "/" session))
                (info-row "status"       (str glyph "  " status))
                ""
                (info-row "dev URL"      dev-url)
                (info-row "app port"     (or app-port dash))
                (info-row "pg port"      (or pg-port dash))
                (info-row "nrepl port"   (or nrepl-port dash))
                ""
                (info-row "session home" home)
                (info-row "worktree"     (or worktree dash))]
               (when link-rows
                 (cons "" (cons (info-row "links" "")
                                link-rows)))))))

(defn- modal-body [state]
  (case (:modal state)
    :confirm-destroy
    (let [{:keys [project session]} (:modal-target state)]
      (str (style/render warning-style "destroy ")
           project "/" session
           (style/render warning-style " ?")))

    :ci-picker
    (let [{:keys [summary]} (:modal-target state)
          {:keys [run-id failed-step-names total-steps]} summary]
      (str "prior run: " run-id
           "    failed " (count failed-step-names) " of " total-steps
           "\n\n"
           "[r] rerun failed   [n] run all   [esc] cancel"))

    :session-info
    (let [{:keys [project session data]} (:modal-target state)]
      (session-info-body project session data))

    :run-details
    (let [{:keys [run]} (:modal-target state)
          log-path     (cstate/run-agent-log (:id run))
          log-tail     (when (fs/exists? log-path)
                         (->> (str/split-lines (slurp log-path))
                              (take-last 50)
                              (str/join "\n")))]
      (str
       (with-out-str (clojure.pprint/pprint run))
       "\n\n"
       (style/render label-style "─── last 50 lines of agent.log ───") "\n"
       (or log-tail "(no agent.log yet)")))

    :create-session
    (text-input/text-input-view (:modal-input state))

    :fire-pick-project
    (let [{:keys [projects cursor]} (:modal-target state)]
      (str/join "\n"
                (map-indexed (fn [i p]
                               (str (if (= i cursor) "▸ " "  ") p))
                             projects)))

    :fire-pick-trigger
    (let [{:keys [triggers cursor error]} (:modal-target state)]
      (if error
        error
        (str/join "\n"
                  (map-indexed (fn [i t]
                                 (str (if (= i cursor) "▸ " "  ")
                                      (name (:name t))))
                               triggers))))

    :fire-input-payload
    (let [{:keys [trigger keys idx values]} (:modal-target state)
          k (nth keys idx)]
      (str "Trigger: " (name (:name trigger)) "\n"
           "Payload field " (inc idx) " of " (count keys)
           ": " (name k) "\n\n"
           (text-input/text-input-view (:modal-input state))
           (when (seq values)
             (str "\n\nFilled so far:\n"
                  (str/join "\n"
                            (for [[fk v] values]
                              (str "  " (name fk) " = " v)))))))))

(defn- view [state]
  (if (:modal state)
    (str (header state) "\n\n"
         (modal-body state) "\n\n"
         (footer state))
    (str (header state) "\n"
         (when (= :runs (:screen state))
           (str (status-bar) "\n"))
         "\n"
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
