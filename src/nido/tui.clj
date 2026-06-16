(ns nido.tui
  "Tiny charm.clj-based terminal UI over the existing session lifecycle.

   Screens:
     :projects   — registered projects (one row per project) → enter drills in
     :board      — the active project's source-scoped views, cycled with Tab/←→.
                   Each view (see view-defs) is either a filtered workstream list
                   (Notion, Scratch) or the flat substrate list (Sessions/ops).
                   ↵ on a workstream drills into its detail; ↵ on a session enters
                   it in chat.
     :workstream — the highlighted workstream's sessions; ↵ enters one in chat,
                   esc returns to the board at the active view.

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
   [clojure.string :as str]
   [nido.charm-patch :as charm-patch]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.promote :as promote]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.runs-view :as runs-view]
   [nido.coordinator.scratch :as scratch]
   [nido.coordinator.workstream :as workstream]
   [nido.coordinator.workstreams-view :as wsv]
   [nido.coordinator.triggers :as triggers]
   [nido.project :as project]
   [nido.work :as work]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.links :as links]
   [nido.session.state :as state]))

;; ---------------------------------------------------------------------------
;; Action channel: update-fn writes here before returning quit-cmd; the bb
;; task wrapper reads after `program/run` returns to decide what to run next.
;; Shape: :quit | [:enter p s target]
;;        | [:up p s] | [:down p s] | [:destroy p s]
;;        | [:add p s]
;;        target = :home | :worktree
;; `:enter` is the non-Warp fallback: it quits to the wrapper, which `cd`s the
;; parent shell. In Warp `enter-session` spawns a tab in-app and never queues —
;; nido stays up (see `lifecycle/warp?` / `lifecycle/spawn-tab!`).
;; ---------------------------------------------------------------------------

(def ^:private exit-action (atom :quit))

(defn- queue-action!
  "Set the pending action and return charm's quit command."
  [action]
  (reset! exit-action action)
  program/quit-cmd)

;; ---------------------------------------------------------------------------
;; Views: the source-scoped tabs the user cycles within a project. Ordered.
;; :kind :workstreams → filtered workstream list (by :source); :kind :ops → flat substrate.
;; To add a source intake, insert a {:id .. :label .. :kind :workstreams :source ..}
;; whose :source matches the ws-source classifier (workstreams-view/ws-source).
;; ---------------------------------------------------------------------------

(def ^:private view-defs
  [{:id :notion   :label "Notion"   :kind :workstreams :source :notion}
   {:id :github   :label "GitHub"   :kind :workstreams :source :github}
   {:id :slack    :label "Slack"    :kind :workstreams :source :slack}
   {:id :scratch  :label "Scratch"  :kind :workstreams :source :scratch}
   {:id :sessions :label "Sessions" :kind :ops}])

(defn- view-for-id [id]
  (or (some #(when (= id (:id %)) %) view-defs) (first view-defs)))

(defn- step-view [id delta]
  (let [ids (mapv :id view-defs)
        i   (.indexOf ids id)]
    (nth ids (mod (+ (max i 0) delta) (count ids)))))

(defn- next-view [id] (step-view id 1))
(defn- prev-view [id] (step-view id -1))

;; ---------------------------------------------------------------------------
;; Origin filter: the board is one stage-grouped list (nido.work/grouped); the
;; workstream's origin is a badge per row and a filter, not a separate screen.
;; (Plan B; replaces view-defs cycling once the board is rewired.)
;; ---------------------------------------------------------------------------

(def ^:private origin-filters
  [{:id :all     :label "All"}
   {:id :notion  :label "Notion"}
   {:id :github  :label "GitHub"}
   {:id :slack   :label "Slack"}
   {:id :scratch :label "Scratch"}])

(defn- step-origin [id delta]
  (let [ids (mapv :id origin-filters)
        i   (.indexOf ids id)]
    (nth ids (mod (+ (max i 0) delta) (count ids)))))

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
            (let [up?   (boolean (or pg-port app-port nrepl-port))
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
;; Workstreams surface — list (engagement groups) + detail (its sessions)
;; ---------------------------------------------------------------------------

(defn- ws-item-row
  "One workstream display row inside a stage group."
  [r]
  {:title       (wsv/format-row r)
   :description (or (:last-activity r) "")
   :data        r})

(def ^:private group-header-style (style/style :fg style/yellow :bold true))

(defn- group-header
  "A section divider row. The leading newline puts a blank line ABOVE the header
   (the empty :description gives one BELOW it), so sections breathe; the title is
   coloured via `group-header-style`. `::group-header` keeps it non-actionable.
   The very first row's leading blank is stripped in workstream-list-rows."
  [label]
  {:title       (str "\n" (style/render group-header-style (str "── " label " ──")))
   :description ""
   :data        ::group-header})

(defn- stage-group-rows
  "Header + one row per workstream for a fully-expanded stage group. nil when
   empty so callers can `concat` without conditionals."
  [label rows]
  (when (seq rows)
    (cons (group-header (str label " (" (count rows) ")"))
          (mapv ws-item-row rows))))

(defn- triage-group-rows
  "Triage renders as two separate headered sections (see wsv/triage-split):
   `Triage · in flight` — the ≤ :max-in-flight slots nido is currently working
   (parked at the gate for you, or running), expanded and highest-severity-first;
   and `Triage · queued` — the backlog waiting for a free slot, shown as a single
   count line so it never buries the rows you act on. Keeping the two apart is
   what makes the in-flight count reflect the max-5 model instead of summing the
   whole backlog into one `Triage (N)`."
  [{:keys [in-flight queued]}]
  (concat
   (stage-group-rows "Triage · in flight" in-flight)
   (when (seq queued)
     [(group-header (str "Triage · queued (" (count queued) ")"))])))

(defn- strip-leading-blank
  "Drop the leading newline a group-header carries, so the list doesn't open with
   a blank line under the cursor. Only touches the first row's title."
  [rows]
  (if-let [r (first rows)]
    (cons (update r :title #(if (and (string? %) (str/starts-with? % "\n")) (subs % 1) %))
          (rest rows))
    rows))

(defn- live-session-names
  "Set of session names for `project` that are actually up — i.e. hold a pg/app/
   nrepl port in the registry. Threaded into the workstream projection so a human
   one-off's engagement tracks real service state instead of its static
   coordinator-session :substrate (which is never synced on down)."
  [project]
  (->> (lifecycle/list-all-data {:project project})
       :sessions
       (keep (fn [s] (when (or (:pg-port s) (:app-port s) (:nrepl-port s)) (:name s))))
       set))

;; ---------------------------------------------------------------------------
;; Board rows — Plan B spine board (Task 1.2)
;; Reads nido.work/grouped, badges each row by origin, and supports an
;; origin filter. Task 1.3 rewires current-rows to these; for now they
;; coexist with workstream-list-rows (which Task 4.1 will delete).
;; ---------------------------------------------------------------------------

(def ^:private origin-badges
  {:notion "N" :github "G" :slack "S" :scratch "·"})

(defn- origin-badge [origin]
  (get origin-badges origin "?"))

(defn- badged-item-row
  "One workstream row for the spine board: origin badge + the wsv display string."
  [r]
  {:title       (str (origin-badge (:origin r)) "  " (wsv/format-row r))
   :description (or (:last-activity r) "")
   :data        r})

(defn- filter-origin
  "Keep only rows of `origin` (or all rows when `origin` is :all)."
  [origin rows]
  (if (= :all origin) rows (filterv #(= origin (:origin %)) rows)))

(defn- spine-group-rows
  "Header + badged rows for a stage band; nil when empty (so callers `concat`)."
  [label rows]
  (when (seq rows)
    (cons (group-header (str label " (" (count rows) ")"))
          (mapv badged-item-row rows))))

(defn- triage-spine-rows
  "Triage rendered as in-flight (expanded) + a queued count line."
  [{:keys [in-flight queued]}]
  (concat
   (spine-group-rows "Triage · in flight" in-flight)
   (when (seq queued)
     [(group-header (str "Triage · queued (" (count queued) ")"))])))

(defn- board-rows
  "Rows for the spine board: work/grouped, filtered by `origin`, as Ready /
   In progress / Triage bands with origin badges. Empty-state sentinel when none."
  [project origin]
  (let [g    (work/grouped project (live-session-names project))
        keep #(filter-origin origin %)
        rows (concat
              (spine-group-rows "Ready to pick up" (keep (:ready g)))
              (spine-group-rows "In progress"      (keep (:in-progress g)))
              (triage-spine-rows {:in-flight (keep (get-in g [:triage :in-flight]))
                                  :queued    (keep (get-in g [:triage :queued]))}))]
    (if (empty? rows)
      [{:title "No workstreams here. [n] new · [s] system · [f via system] fire"
        :description "" :data ::empty}]
      (vec (strip-leading-blank rows)))))

(defn- workstream-list-rows
  "List rows for one source's workstreams. Notion (and any future stage-driven
   source) groups Ready/In-progress/Triage; Scratch — one-offs with no lifecycle
   stage — groups by engagement (Active/Idle). Real liveness (registry ports)
   drives engagement via live-session-names, so a downed one-off reads Idle."
  [project source]
  (let [rows (filterv #(= source (:source %))
                      (wsv/workstream-rows project (live-session-names project)))
        list-rows (if (= :scratch source)
                    (let [g (wsv/grouped-by-engagement rows)]
                      (concat
                       (stage-group-rows "Active" (:active g))
                       (stage-group-rows "Idle"   (:idle g))))
                    (let [g (wsv/grouped-by-stage rows)]
                      (concat
                       ;; Actionable-first: Triage is high-volume autonomous churn
                       ;; and would bury the rows you act on. Done is omitted.
                       (stage-group-rows  "Ready to pick up" (:ready g))
                       (stage-group-rows  "In progress"      (:in-progress g))
                       (triage-group-rows (:triage g)))))]
    (if (empty? list-rows)
      [{:title (if (= :scratch source)
                 "No one-off sessions here yet."
                 "No workstreams yet. Press 'f' to fire a trigger.")
        :description ""
        :data ::empty}]
      (vec (strip-leading-blank list-rows)))))

(defn- ws-detail-rows [project ws-id]
  (let [rows (wsv/session-rows project ws-id)]
    (if (empty? rows)
      [{:title "No sessions in this workstream yet."
        :description ""
        :data ::empty}]
      (mapv (fn [r]
              {:title       (wsv/format-session-row r)
               :description (or (:last-activity r) "")
               :data        r})
            rows))))

(defn- format-detail-session
  "Display string for a session on the autonomy axis."
  [{:keys [name autonomy-level status parked? brakes]}]
  (format "%s%s  ·  %s  ·  %s%s"
          (if parked? "⏸ " "  ")
          name
          (clojure.core/name (or autonomy-level :?))
          (clojure.core/name (or status :?))
          (if brakes (str "  ·  " (clojure.core/name (ffirst brakes)) " " (val (first brakes))) "")))

(defn- detail-rows
  "Read-only detail rows for one workstream: a ledger line (when present) plus its
   sessions on the autonomy axis. Reads nido.work/workstream (string project ok)."
  [project ws-id]
  (let [{:keys [ledger sessions]} (work/workstream project ws-id)
        ledger-row (when ledger
                     [{:title (str "ledger: " (:key ledger) " · "
                                   (clojure.core/name (or (:status ledger) :?)) " · "
                                   (:report-count ledger) " report(s)")
                       :description "" :data ::ledger}])]
    (vec
     (concat
      ledger-row
      (if (seq sessions)
        (mapv (fn [s] {:title (format-detail-session s) :description "" :data s}) sessions)
        [{:title "No sessions in this workstream yet." :description "" :data ::empty}])))))

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

(defn- active-view
  "The view-def for the board's active :view (defaults to the first view)."
  [state]
  (view-for-id (:view state)))

(defn- current-rows
  "Rows for the active screen — the source the live-refresh tick re-reads.
   On :board, dispatches on origin filter for the spine board.
   On :workstream, shows the detail rows for the highlighted workstream."
  [state]
  (case (:screen state)
    :board      (board-rows (:project state) (:origin state))
    :workstream (detail-rows (:project state) (:ws-id state))
    :projects   (project-rows)))

;; ---------------------------------------------------------------------------
;; Screen transitions
;; ---------------------------------------------------------------------------

(defn- enter-projects [state]
  (-> state
      (assoc :screen :projects :project nil :status nil)
      (rebuild-list (project-rows))))

(defn- enter-board
  "Drill from the projects list into a project's board, on the :all origin."
  [state project-name]
  (let [s (assoc state :screen :board :project project-name :origin :all :status nil)]
    (rebuild-list s (current-rows s))))

(defn- enter-workstream
  "Drill from the workstreams list into one workstream's session detail."
  [state ws-id label]
  (-> state
      (assoc :screen :workstream :ws-id ws-id :ws-label label :status nil)
      (rebuild-list (detail-rows (:project state) ws-id))))

(defn- set-view
  "Switch the board to `view-id` and rebuild the list with that view's rows.
   Clears any open modal and stale detail context so view nav can't trap us
   behind a panel or in a drilled-in workstream. Used by the view-cycling keys
   and by the detail screen's `esc` (back to the board at the current view)."
  [state view-id]
  (let [s (assoc state :screen :board :view view-id :status nil)]
    (-> s
        (rebuild-list (current-rows s))
        (dissoc :modal :modal-target :modal-input :ws-id :ws-label))))

(defn- set-origin
  "Switch the board to `origin` filter and rebuild the list.
   Clears any open modal and stale detail context."
  [state origin]
  (let [s (assoc state :screen :board :origin origin :status nil)]
    (-> s (rebuild-list (current-rows s))
        (dissoc :modal :modal-target :modal-input :ws-id :ws-label))))

(defn- selected-data [state]
  (some-> (item-list/selected-item (:list state)) :data))

(defn- selected-workstream
  "The highlighted workstreams-list row when it's a real workstream (carries
   :ws-id); nil for group-header / empty sentinels."
  [state]
  (let [d (selected-data state)]
    (when (and (map? d) (:ws-id d)) d)))

(defn- selected-session-row
  "The highlighted detail-screen session row (carries :name + :project); nil for sentinels."
  [state]
  (let [d (selected-data state)]
    (when (and (map? d) (:name d) (:project d)) d)))

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
      [(enter-board state name) nil]
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

;; Per-verb display words. `:fn` is the lifecycle call for the
;; session-shaped verbs — up!/down!/destroy-session!/up! (add) all take
;; [name {:project ...}] and log to *out* (captured below).
;; `:fn` holds the VAR (not the fn value) so the call is late-bound — invoking a
;; var resolves its current root, which keeps the table mockable and avoids
;; stale captures.
;; `:after` is the scratch-workstream hook (fn [project-str session-name]) run
;; after a successful lifecycle call, so the TUI's up/add/destroy stay in sync
;; with the loose-workstream model the bb task layer maintains (spec line 82):
;; up/add birth the loose workstream (idempotent — no-ops when the session
;; already belongs to one), destroy reaps it (spares ref-carrying workstreams).
(defn- birth-scratch!   [p sn] (scratch/birth! (keyword p) sn))
(defn- reap-scratch!    [p sn] (scratch/reap!  (keyword p) sn))

(def ^:private action-defs
  {:up      {:fn #'lifecycle/up!    :after birth-scratch! :gerund "Starting"   :past "Started"   :failed "start"}
   :down    {:fn #'lifecycle/down!                        :gerund "Stopping"   :past "Stopped"   :failed "stop"}
   :destroy {:fn #'destroy-session! :after reap-scratch!  :gerund "Destroying" :past "Destroyed" :failed "destroy"}
   :add     {:fn #'lifecycle/up!    :after birth-scratch! :gerund "Creating"   :past "Created"   :failed "create"}})

(defn- run-session-action!
  "Run `verb`'s lifecycle fn for session `sn` in project `p`, then its `:after`
   scratch-workstream hook (birth/reap) when present. Sequential and in the same
   captured-output context as the lifecycle call (see start-session-action), so a
   hook throw surfaces as the action's failure — matching the bb task layer's
   `(lifecycle/up! …) (scratch/birth! …)` ordering."
  [verb p sn]
  (let [{action-fn :fn after :after} (get action-defs verb)
        v (action-fn sn {:project p})]
    (when after (after p sn))
    v))

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
  (with-spinner
    state verb sn
    (captured-cmd
     (fn [] (run-session-action! verb p sn))
     (fn [{:keys [ok? error output]}]
       (if ok?
         {:type ::action-done :verb verb :subject sn}
         {:type ::action-failed :verb verb :subject sn :error error :output output})))))

(defn- enter-session
  "Land the user in session `sn` (target `:home` | `:worktree`). In Warp, open
   a new tab in the *current* window in-app so nido stays up to orchestrate from
   — errors surface as a status line. Every other terminal falls back to the
   `cd-target-file` handoff: queue an `:enter` action and quit so the `nido`
   shell wrapper `cd`s the parent shell there."
  [state p sn target]
  (if (lifecycle/warp?)
    (try
      (let [path (lifecycle/spawn-tab! sn {:project p :cd target})]
        [(assoc state :status (str "Opened tab → " path)) nil])
      (catch Throwable t
        [(assoc state :status (str "✗ " (ex-message t))) nil]))
    [state (queue-action! [:enter p sn target])]))

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
      (fn [s p sn] (enter-session s p sn :home)))

    (msg/key-match? msg "w")
    (with-selected-session state
      (fn [s p sn] (enter-session s p sn :worktree)))

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
;; Fire-trigger modal (workstreams board view, `f` key)
;;
;; Three sub-states cooperate to walk the user through:
;;   :fire-pick-project  — choose project (skipped when only one is registered)
;;   :fire-pick-trigger  — choose a :manual trigger from that project
;;   :fire-input-payload — fill placeholder kwargs one field at a time
;; The final state enqueues an envelope via `queue/enqueue!` and surfaces a
;; status-bar message so the user can confirm by pressing `r` to refresh.
;; Defined here (rather than alongside the other modal handlers below) so
;; `update-workstreams` can call `open-fire-trigger` without a forward declaration.
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
       " — it'll appear on the next refresh"))

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
  "Entry point bound to `f` on a workstreams board view. Routes to the project
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

(defn- open-selected
  "Open the highlighted workstream's session/chat via work/open-target."
  [state]
  (if-let [ws (selected-workstream state)]
    (if-let [{:keys [session]} (work/open-target (:project state) (:ws-id ws))]
      (enter-session state (:project state) session :home)
      [(assoc state :status "(no session to open yet)") nil])
    [(assoc state :status "(no workstream selected)") nil]))

(defn- promote-selected
  "Promote the highlighted workstream via work/default-target + work/set-stage!.
   Surfaces the decision in the status line and refreshes the list."
  [state]
  (if-let [ws (selected-workstream state)]
    (let [target   (work/default-target (:project state) :promote)
          decision (:decision (work/set-stage! (:project state) (:ws-id ws) target))]
      [(-> state (refresh-list (current-rows state))
           (assoc :status (wsv/promote-result-message (:promote-id ws) decision)))
       nil])
    [(assoc state :status "(no workstream selected)") nil]))

(defn- done-selected
  "Mark the highlighted workstream done via work/set-stage! :done."
  [state]
  (if-let [ws (selected-workstream state)]
    (do (work/set-stage! (:project state) (:ws-id ws) :done)
        [(-> state (refresh-list (current-rows state))
             (assoc :status (str "marked " (or (:br-id ws) (:ws-id ws)) " done")))
         nil])
    [(assoc state :status "(no workstream selected)") nil]))

(declare open-stage-picker enter-system)

(defn- open-stage-picker [state] [(assoc state :status "(stage picker — Phase 2)") nil])
(defn- enter-system [state] (assoc state :screen :system :status "(system surface — Phase 3)"))

(defn- update-board [state msg]
  (cond
    (msg/key-match? msg "escape") [(enter-projects state) nil]
    (or (msg/key-match? msg "enter") (msg/key-match? msg "o")) (open-selected state)
    (msg/key-match? msg "i")
    (if-let [ws (selected-workstream state)]
      [(enter-workstream state (:ws-id ws) (:label ws)) nil] [state nil])
    (msg/key-match? msg "n") (open-create-session state (:project state))
    (msg/key-match? msg "p") (promote-selected state)
    (msg/key-match? msg "P") (open-stage-picker state)
    (msg/key-match? msg "d") (done-selected state)
    (msg/key-match? msg "s") [(enter-system state) nil]
    (or (msg/key-match? msg "tab") (msg/key-match? msg "right"))
    [(set-origin state (step-origin (:origin state) 1)) nil]
    (or (msg/key-match? msg "shift+tab") (msg/key-match? msg "left"))
    [(set-origin state (step-origin (:origin state) -1)) nil]
    :else (let [[lst cmd] (item-list/list-update (:list state) msg)]
            [(assoc state :list lst) cmd])))

(defn- update-workstreams
  "A workstreams board view (Notion/GitHub/Scratch). ↵ drills into the highlighted
   workstream's session detail. p promotes the highlighted ticket; d marks it
   done (drops it from the overview); f/h/c carry over fire-trigger / halt /
   clear-breaker. On the Scratch view `a` creates a one-off session (Scratch is
   the home for one-offs — spec line 73); ref-sourced views (Notion/GitHub) come
   from external refs, so creation is meaningless there and `a` is inert.
   Group-header and empty rows are guarded by `selected-workstream` returning nil.
   View cycling (Tab/←→) is handled upstream in `update-fn`."
  [state msg]
  (cond
    (msg/key-match? msg "escape")
    [(enter-projects state) nil]

    (msg/key-match? msg "enter")
    (if-let [ws (selected-workstream state)]
      [(enter-workstream state (:ws-id ws) (:label ws)) nil]
      [state nil])

    (and (msg/key-match? msg "a") (= :scratch (:source (active-view state))))
    (open-create-session state (:project state))

    (msg/key-match? msg "p") (promote-selected state)
    (msg/key-match? msg "d") (done-selected state)

    (msg/key-match? msg "f") (open-fire-trigger state)
    (msg/key-match? msg "h") (open-halt-confirm state)
    (msg/key-match? msg "c") (open-clear-breaker-picker state)

    :else
    (let [[lst cmd] (item-list/list-update (:list state) msg)]
      [(assoc state :list lst) cmd])))

(defn- update-workstream
  "Workstream detail screen. ↵ routes into the highlighted session's home (same
   handoff the Sessions view uses → lands you in the chat). esc returns to the
   board at the active origin."
  [state msg]
  (cond
    (msg/key-match? msg "escape") [(set-origin state (:origin state)) nil]
    (or (msg/key-match? msg "enter") (msg/key-match? msg "o"))
    (if-let [sname (some-> (selected-data state) :name)]
      (enter-session state (:project state) sname :home)
      [state nil])
    :else (let [[lst cmd] (item-list/list-update (:list state) msg)]
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
  "Shared handler for read-only scrollable modals (action-error).
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

(defn- update-fn [state msg]
  (cond
    ;; Charm fires a window-size on startup and on every resize. In alt-screen
    ;; mode charm's loop resizes JLine's Display but never clears the physical
    ;; screen, stranding the previous frame; charm-patch/clear-on-resize! does
    ;; the missing wipe + cache-invalidate. We stash the dims (the action-error
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

    (= :action-error (:modal state))
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

    ;; Origin cycling on the board: Tab / → step forward, Shift-Tab / ← step back.
    ;; Pure in-process state changes (no action-channel exit), available outside
    ;; modals on the board screen only — from a drilled-in workstream you `esc`
    ;; back to the board first.
    (and (nil? (:modal state)) (= :board (:screen state))
         (or (msg/key-match? msg "tab") (msg/key-match? msg "right")))
    [(set-origin state (step-origin (:origin state) 1)) nil]

    (and (nil? (:modal state)) (= :board (:screen state))
         (or (msg/key-match? msg "shift+tab") (msg/key-match? msg "left")))
    [(set-origin state (step-origin (:origin state) -1)) nil]

    :else
    (case (:screen state)
      :projects   (update-projects state msg)
      :board      (update-board state msg)
      :workstream (update-workstream state msg))))

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

(def ^:private active-tab-style   (style/style :fg style/cyan :bold true))
(def ^:private inactive-tab-style (style/style :fg 240))

(defn- tab-bar
  "One-line view switcher rendered above the board list: each view's label, the
   active one bracketed + bright, the rest dim. Pure string (modulo styling)."
  [active-id]
  (->> view-defs
       (map (fn [{:keys [id label]}]
              (if (= id active-id)
                (style/render active-tab-style (str "[" label "]"))
                (style/render inactive-tab-style (str " " label " ")))))
       (str/join "  ")))

(defn- origin-strip
  "One-line origin filter rendered above the board list: each origin label, the
   active one bracketed + bright, the rest dim. Pure string (modulo styling)."
  [active-id]
  (->> origin-filters
       (map (fn [{:keys [id label]}]
              (if (= id active-id)
                (style/render active-tab-style (str "[" label "]"))
                (style/render inactive-tab-style (str " " label " ")))))
       (str/join "  ")))

(defn- header [state]
  (style/render title-style
                (case (:modal state)
                  :confirm-destroy "nido — confirm destroy"
                  :create-session  (str "nido — " (-> state :modal-target :project)
                                        " · new session")
                  :session-info    (str "nido — " (-> state :modal-target :project)
                                        " · " (-> state :modal-target :session)
                                        " · info")
                  :action-error        (str "nido — action failed · " (-> state :modal-target :subject))
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
                    :projects   "nido — projects"
                    :board      (str "nido — " (:project state) " · " (name (:origin state)))
                    :workstream (str "nido — " (:project state) " · " (:ws-label state))))))

(defn- footer [state]
  (style/render subtle-style
                (case (:modal state)
                  :confirm-destroy    "[y] destroy  [n/esc] cancel"
                  :create-session     "[↵] create  [esc] cancel"
                  :session-info       "[esc] back"
                  :action-error           "[↑↓/pgup/pgdn] scroll  [esc] dismiss"
                  :fire-pick-project  "[↑↓] move  [↵] pick  [esc] cancel"
                  :fire-pick-trigger  "[↑↓] move  [↵] pick  [esc] cancel"
                  :fire-input-payload "[↵] next field  [esc] cancel"
                  :halt-confirm         "[y] halt  [n/esc] cancel"
                  :halt-resume-confirm  "[y] resume  [n/esc] cancel"
                  :clear-breaker        "[↑↓] move  [↵] clear  [esc] cancel"
                  (case (:screen state)
                    :projects   "[↵] open  [q]uit"
                    :board      "[↵/o] open  [i]nspect  [n]ew  [p]romote  [P] promote to…  [d]one  [⇄ tab] origin  [s]ystem  [esc] back  [q]uit"
                    :workstream "[↵] open in chat  [esc] back  [q]uit"))))

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

(defn- text-viewport
  "Viewport over `content`, sized to the stashed terminal height (minus
   header/footer chrome); falls back to 22 visible lines before the first
   window-size message arrives. Used by the action-error panel."
  [state content]
  (let [h  (or (some-> state :size second) 28)
        vh (max 5 (- h 6))]
    (viewport/viewport content :height vh)))

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

    :action-error
    (viewport/viewport-view (:viewport (:modal-target state)))

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
         (when (= :board (:screen state))
           (str (origin-strip (:origin state)) "\n"))
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
