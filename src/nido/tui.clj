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
   [charm.components.spinner :as spinner]
   [charm.components.text-input :as text-input]
   [charm.components.viewport :as viewport]
   [charm.message :as msg]
   [charm.program :as program]
   [charm.style.core :as style]
   [clojure.pprint]
   [clojure.string :as str]
   [nido.charm-patch :as charm-patch]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.promote :as promote]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.runs-clean :as runs-clean]
   [nido.coordinator.runs-view :as runs-view]
   [nido.coordinator.tickets-view :as tickets-view]
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
;;        | [:add p s]
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

(defn- ticket-group-rows
  "Header row + one row per ticket for a single group. Returns nil when the
   group is empty so the caller can `concat` without conditionals."
  [label tickets]
  (when (seq tickets)
    (cons {:title       (str "── " label " (" (count tickets) ") ──")
           :description ""
           :data        ::group-header}
          (mapv (fn [t]
                  {:title       (tickets-view/format-row t)
                   :description (or (tickets-view/last-activity t) "")
                   :data        t})
                tickets))))

(defn- ticket-rows []
  (let [groups (tickets-view/grouped-tickets (tickets-view/read-all-tickets))
        rows   (concat
                (ticket-group-rows "Ready to implement" (:ready groups))
                (ticket-group-rows "In progress"        (:in-progress groups))
                (ticket-group-rows "Skipped"            (:skipped groups)))]
    (if (empty? rows)
      [{:title       "No tickets yet — triage has to run and be acked first."
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

;; Single-line picker list (no descriptions) for the modal choosers. Items are
;; `{:title <string> :data <value>}`; `enter` reads `selected-item`'s :data.
;; Replaces the per-modal cursor/up/down/clamp bookkeeping with the same list
;; component the main screens use.
(defn- picker-list [items]
  (item-list/item-list items
                       :show-descriptions false
                       :cursor-style (style/style :fg style/cyan :bold true)))

(defn- picker-selected
  "The :data of the highlighted picker item, or nil when empty."
  [state]
  (some-> (:picker (:modal-target state)) item-list/selected-item :data))

(defn- picker-route
  "Route a navigation key to the modal's picker list and store it back."
  [state msg]
  (let [[lst cmd] (item-list/list-update (:picker (:modal-target state)) msg)]
    [(assoc-in state [:modal-target :picker] lst) cmd]))

(defn- rebuild-list [state items]
  (assoc state
         :items items
         :list (list-component items)))

(defn- refresh-list
  "Update the list's items IN PLACE via `item-list/set-items`, preserving the
   cursor (clamped when the list shrinks). Used by the live-refresh tick —
   unlike `rebuild-list`, which builds a fresh component and resets the cursor
   to the top (correct for screen switches, wrong for a timer repaint)."
  [state items]
  (-> state
      (assoc :items items)
      (update :list item-list/set-items items)))

(defn- current-rows
  "Rows for the active screen — the source the live-refresh tick re-reads.
   Mirrors `set-screen`'s screen→rows mapping."
  [state]
  (case (:screen state)
    :runs     (run-rows)
    :tickets  (ticket-rows)
    :sessions (session-rows (:project state))
    :projects (project-rows)))

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
               :tickets  (ticket-rows)
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

(defn- selected-promotable-ticket
  "The highlighted ticket map when it's a `:triaged` ticket (the only
   promotable state); nil for group-headers, the empty sentinel, or any
   in-progress/skipped ticket."
  [state]
  (let [data (selected-data state)]
    (when (and (map? data) (:br-id data) (= :triaged (:status data)))
      data)))

;; ---------------------------------------------------------------------------
;; Elm: init / update / view
;; ---------------------------------------------------------------------------

;; Live-refresh: a self-perpetuating tick re-reads the active screen's rows on
;; an interval so the dashboard (run states, coordinator status, session ports)
;; stays current without a keypress. The fn blocks in a core.async go block
;; (charm's execute-cmd!), parking one go-pool thread — fine because exactly one
;; tick is ever in flight: we only re-arm on receipt.
(def ^:private refresh-ms 1500)

(defn- tick-cmd []
  (program/cmd (fn [] (Thread/sleep refresh-ms) {:type ::tick})))

(defn- init-fn []
  [(enter-projects {}) (tick-cmd)])

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

;; Defined in the View section (needs the terminal-height stash); used by
;; finish-action below to build the failure panel.
(declare text-viewport)

;; ---------------------------------------------------------------------------
;; In-app async actions (prototype: session:down)
;;
;; Unlike the heavy verbs that quit-and-re-enter via the bb wrapper, `down`
;; runs INSIDE the TUI as a charm cmd: we set `:busy`, kick off the work on a
;; background go block, and show an animated spinner until a result message
;; arrives. Four rules make this safe:
;;   • catch-don't-throw — a thrown action becomes ::action-failed, not an
;;     :error message (which charm turns into a program-stopping crash);
;;   • silence stdout — the action's println/log-step output is captured to a
;;     sink, else it prints into charm's alt-screen buffer and corrupts it;
;;   • busy-guard — while `:busy`, update-fn swallows input so a second action
;;     can't race the first (ctrl+c and the action/spinner messages still flow);
;;   • the spinner self-drives via :spinner-tick (tag-matched, so the stale
;;     ticks from a finished action are ignored).
;; The cmd fn blocks one go-pool thread for the action's duration — fine at
;; one-action-at-a-time; a future would free the pool if this ever fans out.
;; ---------------------------------------------------------------------------

(defn- destroy-session!
  "destroy! + verify: if the underlying `git worktree remove --force` swallowed
   an error and left the worktree behind, remove it. In-app replacement for the
   bb wrapper's old destroy-and-verify! (fs/delete-tree instead of shelling out;
   the println is captured to the action sink)."
  [sn {:keys [project] :as opts}]
  (lifecycle/destroy! sn opts)
  (let [{:keys [sessions]} (lifecycle/list-all-data {:project project})
        wt (some #(when (= sn (:name %)) (:worktree %)) sessions)]
    (when (and wt (fs/exists? wt))
      (println (str "worktree survived destroy; removing " wt))
      (fs/delete-tree wt))))

;; Per-verb display words. `:fn` (when present) is the lifecycle call for the
;; session-shaped verbs — up!/down!/destroy-session!/up! (add) all take
;; [name {:project ...}] and log to *out* (captured below). `:promote` has no
;; :fn — it acts on a ticket and is driven by its own runner (start-promote).
;; `:fn` holds the VAR (not the fn value) so the call is late-bound — invoking a
;; var resolves its current root, which keeps the table mockable and avoids
;; stale captures.
(def ^:private action-defs
  {:up      {:fn #'lifecycle/up!    :gerund "Starting"   :past "Started"   :failed "start"}
   :down    {:fn #'lifecycle/down!  :gerund "Stopping"   :past "Stopped"   :failed "stop"}
   :destroy {:fn #'destroy-session! :gerund "Destroying" :past "Destroyed" :failed "destroy"}
   :add     {:fn #'lifecycle/up!    :gerund "Creating"   :past "Created"   :failed "create"}
   :promote {                       :gerund "Promoting"  :past "Promoted"  :failed "promote"}})

(defn- with-spinner
  "Common scaffold for an in-app action: a fresh spinner, `:busy` state carrying
   `verb`/`subject`, and a batch of [spinner-tick, work-cmd]. `work-fn` is the
   already-built charm cmd that runs the action and returns a result message."
  [state verb subject work-cmd]
  (let [[spin spin-cmd] (spinner/spinner-init
                         (spinner/spinner :dots :style (style/style :fg style/cyan)))]
    [(assoc state :busy {:verb verb :subject subject :spinner spin})
     (program/batch spin-cmd work-cmd)]))

(defn- captured-cmd
  "A charm cmd that runs `thunk` with *out*/*err* captured to a sink (so its
   println/log-step can't corrupt charm's alt-screen buffer), then maps the
   outcome to a result message via `->msg` (fn [{:keys [ok? value error output]}])."
  [thunk ->msg]
  (program/cmd
   (fn []
     (let [sink (java.io.StringWriter.)]
       (try
         (let [value (binding [*out* sink *err* sink] (thunk))]
           (->msg {:ok? true :value value :output (str sink)}))
         (catch Throwable t
           (->msg {:ok? false :error t :output (str sink)})))))))

(defn- start-session-action
  "Begin a session-shaped verb (`:up`/`:down`/`:destroy`/`:add`) for `sn` in
   project `p`. The verb's :fn takes [name {:project ...}]; any throw is a
   failure carrying the captured output."
  [verb state p sn]
  (let [action-fn (get-in action-defs [verb :fn])]
    (with-spinner
      state verb sn
      (captured-cmd
       (fn [] (action-fn sn {:project p}))
       (fn [{:keys [ok? error output]}]
         (if ok?
           {:type ::action-done :verb verb :subject sn}
           {:type ::action-failed :verb verb :subject sn :error error :output output}))))))

(defn- start-promote
  "Begin an in-app `promote` for ticket `br-id` in `project`. Unlike the session
   verbs, promote! returns a {:decision …} map rather than throwing — a non
   `:promote` decision is a refusal, surfaced in the failure panel."
  [state project br-id]
  (with-spinner
    state :promote br-id
    (captured-cmd
     (fn [] (promote/promote! (keyword project) br-id))
     (fn [{:keys [ok? value error output]}]
       (cond
         (not ok?) {:type ::action-failed :verb :promote :subject br-id :error error :output output}
         (= :promote (:decision value)) {:type ::action-done :verb :promote :subject br-id}
         :else {:type ::action-failed :verb :promote :subject br-id
                :error (ex-info (str "promote refused: " (name (:decision value))) {})
                :output output})))))

(defn- start-session-down    [state p sn] (start-session-action :down    state p sn))
(defn- start-session-up      [state p sn] (start-session-action :up      state p sn))
(defn- start-session-destroy [state p sn] (start-session-action :destroy state p sn))
(defn- start-session-add     [state p sn] (start-session-action :add     state p sn))

(defn- update-spinner-tick
  "Advance the busy spinner (no-op when not busy / tag mismatch)."
  [state msg]
  (if-let [spin (-> state :busy :spinner)]
    (let [[spin' cmd] (spinner/spinner-update spin msg)]
      [(assoc-in state [:busy :spinner] spin') cmd])
    [state nil]))

(defn- action-error-content
  "Panel text for a failed action: the error, then the captured [nido] output."
  [verb subject msg]
  (let [out (:output msg)]
    (str "✗ Failed to " (get-in action-defs [verb :failed]) " " subject "\n"
         (ex-message (:error msg)) "\n\n"
         "─── captured output ───\n"
         (if (seq out) out "(no output)"))))

(defn- finish-action
  "Clear :busy on a terminal action message. On success: a status line + refresh
   so the subject's new state shows immediately (output discarded). On failure:
   a status line + a scrollable :action-error panel over the captured output."
  [state msg]
  (let [{:keys [verb subject]} (:busy state)
        base (dissoc state :busy)]
    (if (= ::action-done (msg/msg-type msg))
      [(-> base
           (assoc :status (str (get-in action-defs [verb :past]) " " subject))
           (refresh-list (current-rows base)))
       nil]
      [(-> base
           (assoc :status (str "Failed to " (get-in action-defs [verb :failed]) " " subject))
           (assoc :modal :action-error
                  :modal-target {:subject subject
                                 :viewport (text-viewport base (action-error-content verb subject msg))}))
       nil])))

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

    ;; `up` and `down` both run in-app (async, spinner) rather than quitting to
    ;; the wrapper. `up` is the long one (PG clone + JVM + app); the spinner
    ;; earns its keep here.
    (msg/key-match? msg "u")
    (with-selected-session state start-session-up)

    (msg/key-match? msg "d")
    (with-selected-session state start-session-down)

    (msg/key-match? msg "x")
    (with-selected-session state
      (fn [s p sn] (open-confirm-destroy s p sn)))

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
                                 :error "(no manual triggers for this project)"}))
       nil]
      [(-> state
           (assoc :modal :fire-pick-trigger)
           (assoc :modal-target
                  {:project project-kw
                   :picker (picker-list
                            (mapv (fn [t] {:title (name (:name t)) :data t}) trigs))}))
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
           (assoc :modal-target
                  {:picker (picker-list (mapv (fn [p] {:title p :data p}) projects))}))
       nil])))

(defn- update-fire-pick-project [state msg]
  (cond
    (msg/key-match? msg "escape") [(close-modal state) nil]

    (msg/key-match? msg "enter")
    (if-let [p (picker-selected state)]
      (open-fire-pick-trigger state p)
      [state nil])

    :else (picker-route state msg)))

(defn- update-fire-pick-trigger [state msg]
  (let [{:keys [project picker error]} (:modal-target state)]
    (cond
      (msg/key-match? msg "escape") [(close-modal state) nil]

      (or error (nil? picker)) [state nil]

      (msg/key-match? msg "enter")
      (if-let [t (picker-selected state)]
        (start-payload-input state project t)
        [state nil])

      :else (picker-route state msg))))

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

;; ---------------------------------------------------------------------------
;; Coordinator brakes modals (runs screen, `h` / `c` keys)
;;
;; `h` toggles the global halt: open :halt-confirm when running, or
;; :halt-resume-confirm when already halted. `c` opens :clear-breaker — a
;; picker over tripped triggers; ↵ clears the highlighted one. Empty
;; tripped list is a no-op (no modal opened) so users don't see an empty
;; picker. Modal arms are wired into `update-fn` next to the other modal
;; cases.
;; ---------------------------------------------------------------------------

(defn- open-halt-confirm [state]
  (if (halt/halted?)
    [(-> state (assoc :modal :halt-resume-confirm)) nil]
    [(-> state (assoc :modal :halt-confirm)) nil]))

(defn- update-halt-confirm [state msg]
  (cond
    (or (msg/key-match? msg "y") (msg/key-match? msg "Y"))
    (do (halt/halt! {:source :user :note "from TUI"})
        [(-> state (close-modal) (assoc :status "Coordinator halted.")) nil])
    :else [(close-modal state) nil]))

(defn- update-halt-resume-confirm [state msg]
  (cond
    (or (msg/key-match? msg "y") (msg/key-match? msg "Y"))
    (do (halt/resume!)
        [(-> state (close-modal) (assoc :status "Coordinator resumed.")) nil])
    :else [(close-modal state) nil]))

(defn- open-clear-breaker-picker [state]
  (let [tripped (breakers/tripped-triggers)]
    (if (empty? tripped)
      [state nil]
      [(-> state
           (assoc :modal :clear-breaker)
           (assoc :modal-target
                  {:picker (picker-list
                            (mapv (fn [{:keys [project trigger info] :as t}]
                                    {:title (str (name project) "/" (name trigger)
                                                 "  —  " (runs-view/breaker-reason info))
                                     :data  t})
                                  tripped))}))
       nil])))

(defn- update-clear-breaker [state msg]
  (cond
    (msg/key-match? msg "escape") [(close-modal state) nil]

    (msg/key-match? msg "enter")
    (if-let [{:keys [project trigger]} (picker-selected state)]
      (do (breakers/enable! project trigger)
          [(-> state (close-modal)
               (assoc :status (str "Breaker cleared: "
                                   (name project) "/" (name trigger))))
           nil])
      [state nil])

    :else (picker-route state msg)))

;; Defined in the View section (needs label-style); used by the `d` arm below.
(declare run-details-viewport)

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
      [state (queue-action! [:enter-run (name (:project run)) (:session-name run) :home (:id run)])]
      [state nil])

    (msg/key-match? msg "w")
    (if-let [run (selected-run state)]
      [state (queue-action! [:enter-run (name (:project run)) (:session-name run) :worktree (:id run)])]
      [state nil])

    (msg/key-match? msg "d")
    (if-let [run (selected-run state)]
      [(-> state
           (assoc :modal :run-details)
           (assoc :modal-target {:run run :viewport (run-details-viewport state run)}))
       nil]
      [state nil])

    ;; Capital D — deliberate deletion of the highlighted Run.
    (msg/key-match? msg "D")
    (if-let [run (selected-run state)]
      [(-> state
           (assoc :modal :delete-run-confirm)
           (assoc :modal-target {:run run}))
       nil]
      [state nil])

    (msg/key-match? msg "f")
    (open-fire-trigger state)

    (msg/key-match? msg "h")
    (open-halt-confirm state)

    (msg/key-match? msg "c")
    (open-clear-breaker-picker state)

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
      ;; Confirmed: close the modal and run destroy in-app (async, spinner) —
      ;; same path as up/down, just gated behind this confirmation first.
      (start-session-destroy (close-modal state) project session))

    ;; n / esc / anything else cancels
    :else
    [(close-modal state) nil]))

(defn- update-session-info
  "Only `esc` closes the info panel; other keys are swallowed so the
   modal stays open until explicitly dismissed."
  [state msg]
  (if (msg/key-match? msg "escape")
    [(close-modal state) nil]
    [state nil]))

(defn- update-viewport-modal
  "Shared handler for read-only scrollable modals (run-details, action-error).
   `esc` closes; ↑↓ / pgup-pgdn / g-G scroll the modal's :viewport (other keys
   are swallowed by the viewport's :else)."
  [state msg]
  (if (msg/key-match? msg "escape")
    [(close-modal state) nil]
    (let [[vp cmd] (viewport/viewport-update (:viewport (:modal-target state)) msg)]
      [(assoc-in state [:modal-target :viewport] vp) cmd])))

(defn- update-create-session [state msg]
  (cond
    (msg/key-match? msg "escape")
    [(close-modal state) nil]

    (msg/key-match? msg "enter")
    (let [name (str/trim (text-input/value (:modal-input state)))
          {:keys [project]} (:modal-target state)]
      (if (seq name)
        ;; Create + start the new session in-app (async, spinner) — `up!`
        ;; creates the worktree if missing, so `add` is just `up` on a new name.
        (start-session-add (close-modal state) project name)
        [(close-modal state) nil]))

    :else
    (let [[ti cmd] (text-input/text-input-update (:modal-input state) msg)]
      [(assoc state :modal-input ti) cmd])))


(defn- update-delete-run-confirm
  "Modal handler for :delete-run-confirm. Opened by capital D on the runs screen.
   - y: delete the Run's dir + session-home + state dir, refresh the list.
   - n / esc / anything else: close modal.
   Deletes any Run regardless of state — the confirmation dialog is the safety
   layer. Live runs are deleted with `:allow-live? true`."
  [state msg]
  (let [run (-> state :modal-target :run)]
    (cond
      (or (msg/key-match? msg "y") (msg/key-match? msg "Y"))
      (do
        (try
          (let [plan (runs-clean/plan-clean {:state       #{(:state run)}
                                             :allow-live? true})]
            ;; Filter to only the highlighted run so we don't batch-delete more than intended.
            (runs-clean/execute! (filter #(= (:id run) (-> % :run :id)) plan)))
          (catch Exception _e
            nil))
        [(-> state
             close-modal
             (assoc :status (str "Deleted: " (:id run)))
             (rebuild-list (run-rows)))
         nil])

      :else
      [(close-modal state) nil])))

(defn- update-tickets
  "Tickets screen: `↵`/`p` promotes the highlighted `:triaged` ticket in-app
   (async, spinner) via `start-promote`; on success the live-refresh moves the
   ticket into `In progress`, and a refusal/error opens the failure panel. Other keys
   fall through to list navigation."
  [state msg]
  (cond
    (or (msg/key-match? msg "enter") (msg/key-match? msg "p"))
    (if-let [{:keys [project br-id]} (selected-promotable-ticket state)]
      ;; Provision the impl session in-app (async, spinner) rather than quitting.
      (start-promote state (name project) br-id)
      [(assoc state :status "Select a ticket in “Ready to implement” to promote.") nil])

    :else
    (let [[lst cmd] (item-list/list-update (:list state) msg)]
      [(assoc state :list lst) cmd])))

(defn- update-fn [state msg]
  (cond
    ;; Charm fires a window-size on startup and on every resize. In alt-screen
    ;; mode charm's loop resizes JLine's Display but never clears the physical
    ;; screen, stranding the previous frame; charm-patch/clear-on-resize! does
    ;; the missing wipe + cache-invalidate. We stash the dims (the run-details
    ;; viewport sizes its scroll window from the height) and let charm's
    ;; post-update render! redraw the full frame onto the cleared screen.
    (msg/window-size? msg)
    (do (charm-patch/clear-on-resize!)
        [(assoc state :size [(:width msg) (:height msg)]) nil])

    ;; In-app async action machinery (see start-session-down). These flow even
    ;; while :busy — they ARE the busy lifecycle — so they precede the guard.
    (spinner/tick-msg? msg)
    (update-spinner-tick state msg)

    (#{::action-done ::action-failed} (msg/msg-type msg))
    (finish-action state msg)

    ;; Live-refresh tick. Re-read the active screen's rows and splice them in
    ;; with set-items (cursor preserved). Skip the refresh while a modal OR a
    ;; busy action is in flight (list hidden; some modals own a text-input) —
    ;; but always re-arm so the dashboard is fresh the moment it clears.
    (= ::tick (msg/msg-type msg))
    (if (or (:modal state) (:busy state))
      [state (tick-cmd)]
      [(refresh-list state (current-rows state)) (tick-cmd)])

    ;; ctrl+c always quits. `q` only quits when no modal is active so the
    ;; create-session text-input can accept it as a literal character.
    (msg/key-match? msg "ctrl+c")
    [state (queue-action! :quit)]

    ;; Busy-guard: while an in-app action runs, swallow all other input so a
    ;; second action can't race it (action/spinner/quit messages already
    ;; handled above).
    (:busy state)
    [state nil]

    (and (nil? (:modal state)) (msg/key-match? msg "q"))
    [state (queue-action! :quit)]

    ;; Modal takes priority over screen handlers.
    (= :confirm-destroy (:modal state))
    (update-confirm-destroy state msg)

    (= :session-info (:modal state))
    (update-session-info state msg)

    (#{:run-details :action-error} (:modal state))
    (update-viewport-modal state msg)

    (= :create-session (:modal state))
    (update-create-session state msg)

    (= :fire-pick-project (:modal state))
    (update-fire-pick-project state msg)

    (= :fire-pick-trigger (:modal state))
    (update-fire-pick-trigger state msg)

    (= :fire-input-payload (:modal state))
    (update-fire-input-payload state msg)

    (= :halt-confirm (:modal state))
    (update-halt-confirm state msg)

    (= :halt-resume-confirm (:modal state))
    (update-halt-resume-confirm state msg)

    (= :clear-breaker (:modal state))
    (update-clear-breaker state msg)

    (= :delete-run-confirm (:modal state))
    (update-delete-run-confirm state msg)

    ;; Tab-style navigation between screens. Always available (outside modals),
    ;; so users can flip from any screen to runs and back to sessions when a
    ;; project context exists. Without a project the sessions screen has
    ;; nothing to show, so the `s` switch is gated on `(:project state)`.
    (and (nil? (:modal state)) (msg/key-match? msg "r") (not= :runs (:screen state)))
    [(set-screen state :runs) nil]

    (and (nil? (:modal state)) (msg/key-match? msg "s") (not= :sessions (:screen state)) (:project state))
    [(set-screen state :sessions) nil]

    (and (nil? (:modal state)) (msg/key-match? msg "t") (not= :tickets (:screen state)))
    [(set-screen state :tickets) nil]

    :else
    (case (:screen state)
      :projects (update-projects state msg)
      :sessions (update-sessions state msg)
      :runs     (update-runs state msg)
      :tickets  (update-tickets state msg))))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(def ^:private title-style    (style/style :fg style/magenta :bold true))
(def ^:private subtle-style   (style/style :fg 240))
(def ^:private status-style   (style/style :fg style/yellow))
(def ^:private warning-style  (style/style :fg style/red :bold true))
(def ^:private label-style    (style/style :fg 244))

(defn- status-bar
  "Top-of-runs-screen line showing the coordinator daemon's reachability,
   slots-in-use, executor in-flight/cap/queued, halt state, and breaker count.
   `:unreachable` and halted states render in warning-style (red). The breaker
   pill appears only when at least one trigger is tripped."
  []
  (let [{:keys [status reachable? slots-in-use alerts executor]} (runs-view/read-coordinator-status)
        {:keys [halted? halt-source halt-note breakers breakers-paused breakers-failing]} alerts
        {:keys [in-flight cap queued]} executor]
    (str (style/render label-style "Coordinator: ")
         (style/render (if halted? warning-style
                         (if reachable? status-style warning-style))
                       (if halted?
                         (str "halted"
                              (when halt-source (str " (" (name halt-source) ")")))
                         (name status)))
         (when (and halted? halt-note) (str " — " halt-note))
         "  •  "
         (style/render label-style "Slots: ")
         (or slots-in-use 0)
         "  •  "
         (style/render label-style "in-flight: ")
         (or in-flight 0) "/" (or cap 0)
         " · queued: " (or queued 0)
         ;; Split the breaker pill: failures need attention (red ⚠), paused
         ;; triggers are deliberate (dim). Press `c` to see the per-trigger why.
         (when (pos? breakers)
           (str "  •  "
                (when (pos? breakers-failing)
                  (style/render warning-style (str "⚠ " breakers-failing " failing")))
                (when (and (pos? breakers-failing) (pos? breakers-paused)) " · ")
                (when (pos? breakers-paused)
                  (style/render label-style (str breakers-paused " paused"))))))))

(defn- header [state]
  (style/render title-style
                (case (:modal state)
                  :confirm-destroy "nido — confirm destroy"
                  :create-session  (str "nido — " (-> state :modal-target :project)
                                        " · new session")
                  :session-info    (str "nido — " (-> state :modal-target :project)
                                        " · " (-> state :modal-target :session)
                                        " · info")
                  :run-details         (str "nido — run · " (-> state :modal-target :run :id))
                  :action-error        (str "nido — action failed · " (-> state :modal-target :subject))
                  :delete-run-confirm  (str "nido — delete run · " (-> state :modal-target :run :id))
                  :fire-pick-project
                  "nido — fire trigger · pick project"
                  :fire-pick-trigger
                  (str "nido — fire trigger · "
                       (name (-> state :modal-target :project)))
                  :fire-input-payload
                  (str "nido — fire trigger · "
                       (name (-> state :modal-target :project)) " · "
                       (name (-> state :modal-target :trigger :name)))
                  :halt-confirm         "nido — halt coordinator?"
                  :halt-resume-confirm  "nido — resume coordinator?"
                  :clear-breaker        "nido — clear breaker"
                  (case (:screen state)
                    :projects "nido — projects"
                    :sessions (str "nido — " (:project state) " · sessions")
                    :runs     "nido — runs"
                    :tickets  "nido — tickets"))))

(defn- footer [state]
  (style/render subtle-style
                (case (:modal state)
                  :confirm-destroy    "[y] destroy  [n/esc] cancel"
                  :create-session     "[↵] create  [esc] cancel"
                  :session-info       "[esc] back"
                  :run-details            "[↑↓/pgup/pgdn] scroll  [esc] back"
                  :action-error           "[↑↓/pgup/pgdn] scroll  [esc] dismiss"
                  :delete-run-confirm     "[y] delete  [n/esc] cancel"
                  :fire-pick-project  "[↑↓] move  [↵] pick  [esc] cancel"
                  :fire-pick-trigger  "[↑↓] move  [↵] pick  [esc] cancel"
                  :fire-input-payload "[↵] next field  [esc] cancel"
                  :halt-confirm         "[y] halt  [n/esc] cancel"
                  :halt-resume-confirm  "[y] resume  [n/esc] cancel"
                  :clear-breaker        "[↑↓] move  [↵] clear  [esc] cancel"
                  (case (:screen state)
                    :projects "[↵] open  [r]uns  [t]ickets  [q]uit"
                    :sessions "[↵/e] enter  [w]orktree  [i]nfo  [a]dd  [u]p  [d]own  [x] destroy  [r]uns  [t]ickets  [esc] back  [q]uit"
                    :runs     "[↵] resume  [w] inspect worktree  [d]etails  [D]elete  [f]ire  [h]alt  [c]lear breaker  [s]essions  [t]ickets  [q]uit"
                    :tickets  "[↵/p] promote (start impl)  [r]uns  [s]essions  [q]uit"))))

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

(defn- run-details-content
  "Full read-only run panel as one string: the run map pretty-printed, then the
   tail of its agent.log. Fed to a scrollable viewport, so we keep a generous
   tail (the viewport bounds what's visible)."
  [run]
  (let [log-path (cstate/run-agent-log (:id run))
        log-tail (when (fs/exists? log-path)
                   (->> (str/split-lines (slurp log-path))
                        (take-last 500)
                        (str/join "\n")))]
    (str (with-out-str (clojure.pprint/pprint run))
         "\n"
         (style/render label-style "─── last 500 lines of agent.log (↑↓ scroll) ───") "\n"
         (or log-tail "(no agent.log yet)"))))

(defn- text-viewport
  "Viewport over `content`, sized to the stashed terminal height (minus
   header/footer chrome); falls back to 22 visible lines before the first
   window-size message arrives. Shared by the run-details and action-error
   panels."
  [state content]
  (let [h  (or (some-> state :size second) 28)
        vh (max 5 (- h 6))]
    (viewport/viewport content :height vh)))

(defn- run-details-viewport [state run]
  (text-viewport state (run-details-content run)))

(defn- modal-body [state]
  (case (:modal state)
    :confirm-destroy
    (let [{:keys [project session]} (:modal-target state)]
      (str (style/render warning-style "destroy ")
           project "/" session
           (style/render warning-style " ?")))

    :session-info
    (let [{:keys [project session data]} (:modal-target state)]
      (session-info-body project session data))

    :run-details
    (viewport/viewport-view (:viewport (:modal-target state)))

    :action-error
    (viewport/viewport-view (:viewport (:modal-target state)))

    :delete-run-confirm
    (let [{:keys [run]} (:modal-target state)
          live?         (contains? runs-clean/live-states (:state run))]
      (str (style/render warning-style "Delete ") (:id run) " [" (name (:state run)) "]"
           (style/render warning-style " ?")
           (when live?
             (str "\n\n"
                  (style/render warning-style
                                (str "⚠ Run is still live (" (name (:state run))
                                     ") — deleting may orphan a running agent/session."))))
           "\n\nThis removes:\n"
           "  • The run dir\n"
           "  • The session-home\n"
           "  • The state dir"))

    :create-session
    (text-input/text-input-view (:modal-input state))

    :fire-pick-project
    (item-list/list-view (:picker (:modal-target state)))

    :fire-pick-trigger
    (let [{:keys [picker error]} (:modal-target state)]
      (if error
        error
        (item-list/list-view picker)))

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
                              (str "  " (name fk) " = " v)))))))

    :halt-confirm
    "Halt the coordinator? New envelopes will queue; existing in-flight\nRuns continue to terminal state."

    :halt-resume-confirm
    (str "Resume coordinator?\n\n"
         (when-let [h (halt/read-halt-info)]
           (str "Currently halted by " (name (:source h))
                (when (:note h) (str " (" (:note h) ")"))
                ".")))

    :clear-breaker
    (item-list/list-view (:picker (:modal-target state)))))

(defn- busy-label [verb]
  (str (get-in action-defs [verb :gerund] "Working on") " "))

(defn- view [state]
  (cond
    ;; In-app action in flight: spinner panel, input swallowed by the guard.
    (:busy state)
    (let [{:keys [verb subject spinner]} (:busy state)]
      (str (header state) "\n\n"
           (spinner/spinner-view spinner) "  "
           (busy-label verb) subject " …\n\n"
           (style/render subtle-style "please wait — this can't be interrupted")))

    (:modal state)
    (str (header state) "\n\n"
         (modal-body state) "\n\n"
         (footer state))

    :else
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

   Alt-screen (full-screen) mode. charm's alt-screen path historically stranded
   the previous frame on resize (it resizes JLine's Display but never clears the
   physical screen); `nido.charm-patch` vendors the fix — its create-renderer
   wrapper captures the renderer, and `update-fn`'s window-size handler calls
   `clear-on-resize!` to wipe + invalidate the cache before charm's render!.
   `install!` is idempotent, so calling it on every run-once is safe."
  []
  (reset! exit-action :quit)
  (charm-patch/install!)
  (program/run {:init     init-fn
                :update   update-fn
                :view     view
                :alt-screen true})
  @exit-action)
