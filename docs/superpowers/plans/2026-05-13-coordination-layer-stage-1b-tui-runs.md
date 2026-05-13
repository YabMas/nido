# Coordination Layer — Stage 1b (TUI runs screen) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Run-overview surface to nido's existing TUI so the user can see in-flight Runs, route into their chat sessions, and fire new manual triggers without leaving the keyboard.

**Architecture:** Extends the existing charm.clj-based TUI (`src/nido/tui.clj`) with a third screen `:runs`. The TUI stays read-mostly: it presents data from `~/.nido/runs/*/run.edn` and `~/.nido/coordinator/status.edn`, queues an action via the existing `exit-action` atom on key-press, then quits — and `tasks.nido-tui` re-enters the TUI after handling. No new tick/refresh loop; freshness comes from quit-and-reenter, same as today's sessions screen. The fire-trigger modal writes a queue file via `nido.coordinator.queue/enqueue!` (Stage 1a infra).

**Tech Stack:** Babashka, charm.clj (existing TUI framework), Malli (read-side validation already in place), clojure.test.

**Spec:** [`docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`](../specs/2026-05-13-nido-coordination-layer-design.md), §"Run overview TUI surface". This plan implements that section, **minus the halt (`h`) and circuit-breaker-clear (`c`) controls** which depend on Stage 2 brakes that don't exist yet. Those land in Stage 2's plan alongside the underlying mechanism.

**Out of scope for Stage 1b (deferred to later plans):**
- `h` (halt coordinator) and `c` (clear circuit breaker) keybindings → Stage 2
- Live polling refresh (status updates every 1s while screen is open) — current quit-and-reenter pattern is consistent with sessions screen; live refresh is a Stage 2+ improvement
- Inline artifact viewing beyond the details modal (open in `$PAGER`) — defer; the details modal showing artifact filenames is enough for v1
- "Snooze / defer" and any UI for resolving Runs without entering the chat — the chat IS the review UI by design

---

## File structure overview

```
src/nido/coordinator/
└── runs_view.clj           # NEW: pure runs-overview data layer (grouping, formatting, status reading)

src/nido/
└── tui.clj                 # MODIFY: add :runs screen, tab nav, ⚙ marker on sessions

src/tasks/
└── nido_tui.clj            # MODIFY: handle new actions (:enter-run, :enter-run-worktree, :fire-trigger)

test/nido/coordinator/
└── runs_view_test.clj      # NEW: unit tests for runs_view
```

Tests for the TUI itself stay out of scope — charm.clj isn't easily testable without rendering, and the existing `tui.clj` has no tests. The runs-view data layer IS tested because that's where the logic lives.

---

## Task 0: runs-view — pure data layer

**Files:**
- Create: `src/nido/coordinator/runs_view.clj`
- Create: `test/nido/coordinator/runs_view_test.clj`

Pure functions that take filesystem state and return display-ready data. The TUI's update/view functions consume this; no charm-specific concerns here.

**Public API:**
- `read-all-runs` — return a sorted vector of all Run records on disk
- `classify` — given a Run, return `:needs-attention | :in-flight | :recent | :archive`
- `grouped-runs` — partition all runs into the three display groups (`needs-attention`, `in-flight`, `recent` ≤10, newest first); archive bucket holds the rest
- `format-row` — given a Run, return a display string `"[state] project · trigger · payload-key  age"`
- `format-age` — given an ISO timestamp, return a human-readable age like `"12m ago"`, `"3d ago"`, `"just now"`
- `read-coordinator-status` — read `~/.nido/coordinator/status.edn`, return a map with `:status`, `:heartbeat-at`, `:slots-in-use`, `:reachable?` (false if heartbeat older than 5s or file absent)

- [ ] **Step 1: Write the failing test**

Create `test/nido/coordinator/runs_view_test.clj`:

```clojure
(ns nido.coordinator.runs-view-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.runs-view :as rv]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def base-run
  {:id "2026-05-13-brian-investigate-bug-a1b2c3"
   :project :brian
   :trigger :investigate-bug
   :source {:type :manual :fired-at "T" :fired-by "u"}
   :event-payload {:url "https://x"}
   :skill :investigate-bug
   :first-message "/investigate-bug https://x"
   :agent :claude
   :session-name "run-brian-investigate-bug-a1b2c3"
   :claude-session-id nil
   :limits {:budget "30m"}
   :state :queued
   :state-history [{:at "2026-05-13T09:14:22Z" :state :queued}]
   :artifacts []
   :error nil})

(defn- with-tmp-runs-dir [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest read-all-runs-empty-when-no-runs
  (with-tmp-runs-dir
    (fn []
      (is (= [] (rv/read-all-runs))))))

(deftest read-all-runs-finds-and-validates-runs
  (with-tmp-runs-dir
    (fn []
      (fs/create-dirs (cstate/run-dir (:id base-run)))
      (runs/write-run! base-run)
      (let [loaded (rv/read-all-runs)]
        (is (= 1 (count loaded)))
        (is (= (:id base-run) (-> loaded first :id)))))))

(deftest classify-by-state
  (is (= :needs-attention (rv/classify (assoc base-run :state :awaiting-review))))
  (is (= :needs-attention (rv/classify (assoc base-run :state :failed))))
  (is (= :needs-attention (rv/classify (assoc base-run :state :halted))))
  (is (= :in-flight       (rv/classify (assoc base-run :state :queued))))
  (is (= :in-flight       (rv/classify (assoc base-run :state :running))))
  (is (= :recent          (rv/classify (assoc base-run :state :done))))
  (is (= :archive         (rv/classify (assoc base-run :state :dry-run-would-fire)))))

(deftest grouped-runs-buckets-correctly
  (let [r-queued    (assoc base-run :id "a" :state :queued)
        r-running   (assoc base-run :id "b" :state :running)
        r-awaiting  (assoc base-run :id "c" :state :awaiting-review)
        r-failed    (assoc base-run :id "d" :state :failed)
        r-done      (assoc base-run :id "e" :state :done)
        groups      (rv/grouped-runs [r-queued r-running r-awaiting r-failed r-done])]
    (is (= #{"c" "d"} (set (map :id (:needs-attention groups)))))
    (is (= #{"a" "b"} (set (map :id (:in-flight groups)))))
    (is (= #{"e"}     (set (map :id (:recent groups)))))))

(deftest grouped-runs-recent-capped-at-10
  (let [done-runs (for [i (range 15)]
                    (assoc base-run :id (format "r%02d" i) :state :done))
        groups    (rv/grouped-runs done-runs)]
    (is (= 10 (count (:recent groups))))))

(deftest format-row-shape
  (is (= "[awaiting       ] brian · investigate-bug · 2026-05-13-brian-investigate-bug-a1b2c3"
         (rv/format-row (assoc base-run :state :awaiting-review)))))

(deftest format-row-uses-payload-key-when-trigger-config-present
  ;; runs-view doesn't know about trigger config — Stage 1b uses the
  ;; run-id suffix as the subject. The trigger's :payload-key would be
  ;; consulted here in a later iteration once runs-view loads triggers
  ;; too. For 1b, run-id suffix is enough.
  (is (= "[done           ] brian · investigate-bug · 2026-05-13-brian-investigate-bug-a1b2c3"
         (rv/format-row (assoc base-run :state :done)))))

(deftest format-age
  (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:00Z")]
    (is (= "just now" (rv/format-age "2026-05-13T09:59:55Z")))
    (is (= "30s ago"  (rv/format-age "2026-05-13T09:59:30Z")))
    (is (= "12m ago"  (rv/format-age "2026-05-13T09:48:00Z")))
    (is (= "3h ago"   (rv/format-age "2026-05-13T07:00:00Z")))
    (is (= "2d ago"   (rv/format-age "2026-05-11T10:00:00Z")))))

(deftest read-coordinator-status-when-absent
  (with-tmp-runs-dir
    (fn []
      (let [s (rv/read-coordinator-status)]
        (is (= :unreachable (:status s)))
        (is (false? (:reachable? s)))))))

(deftest read-coordinator-status-fresh-heartbeat
  (with-tmp-runs-dir
    (fn []
      (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:00Z")]
        (cstate/ensure-dirs!)
        (io/write-edn! (cstate/status-path)
                       {:status :running :slots-in-use 1 :heartbeat-at "2026-05-13T09:59:59Z"})
        (let [s (rv/read-coordinator-status)]
          (is (= :running (:status s)))
          (is (= 1 (:slots-in-use s)))
          (is (true? (:reachable? s))))))))

(deftest read-coordinator-status-stale-heartbeat
  (with-tmp-runs-dir
    (fn []
      (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:00Z")]
        (cstate/ensure-dirs!)
        (io/write-edn! (cstate/status-path)
                       {:status :running :slots-in-use 0 :heartbeat-at "2026-05-13T09:59:30Z"}) ; 30s old
        (let [s (rv/read-coordinator-status)]
          (is (false? (:reachable? s))
              "30s-old heartbeat should be considered unreachable (>5s threshold)"))))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.runs-view-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 3: Implement**

Create `src/nido/coordinator/runs_view.clj`:

```clojure
(ns nido.coordinator.runs-view
  "Pure data layer for the TUI runs screen: reads runs from disk, classifies
   by state, formats display rows, computes ages. No charm dependencies —
   the TUI's update/view functions consume this. See spec §Run overview
   TUI surface."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn read-all-runs
  "Read every run.edn under ~/.nido/runs/. Skips malformed files silently
   (invalid runs are noise; the daemon's monitoring is responsible for
   surfacing real errors)."
  []
  (let [d (cstate/runs-dir)]
    (if (fs/exists? d)
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (map (comp str fs/file-name))
           sort
           (keep (fn [run-id]
                   (try (runs/read-run run-id)
                        (catch Exception _ nil))))
           vec)
      [])))

(defn classify
  "Categorize a Run by state for the overview's grouped display:
   - :needs-attention — :awaiting-review, :failed, :halted (user should look)
   - :in-flight       — :queued, :running (daemon's working)
   - :recent          — :done (terminal happy path)
   - :archive         — :dry-run-would-fire and any other terminal/unknown"
  [{:keys [state]}]
  (cond
    (#{:awaiting-review :failed :halted} state) :needs-attention
    (#{:queued :running} state)                 :in-flight
    (= :done state)                             :recent
    :else                                       :archive))

(defn- last-state-at [run]
  ;; Most recent state-history entry's timestamp; falls back to nil if
  ;; absent so sorting can still progress (older entries sink to bottom).
  (some-> run :state-history last :at))

(defn grouped-runs
  "Partition a vector of Runs into display groups.
   - :needs-attention and :in-flight include all matching runs
   - :recent caps at 10, newest first, from the last 7 days
   Excludes the :archive bucket (not shown on the overview)."
  [all-runs]
  (let [now-iso (clock/now-iso)
        by-cat  (group-by classify all-runs)
        recent? (fn [r]
                  ;; rough cutoff — 7 days ago in ISO comparison space
                  (when-let [t (last-state-at r)]
                    (pos? (compare t (subs (str (.minusSeconds
                                                  (java.time.Instant/parse now-iso)
                                                  (* 7 24 3600))) 0 19)))))
        recent  (->> (:recent by-cat [])
                     (filter recent?)
                     (sort-by last-state-at #(compare %2 %1))
                     (take 10)
                     vec)]
    {:needs-attention (vec (:needs-attention by-cat []))
     :in-flight       (vec (:in-flight by-cat []))
     :recent          recent}))

(defn format-row
  "Display string for a single Run: `[state-padded] project · trigger · subject`.
   Subject is the run-id (full id, sortable + unique). A future iteration
   can substitute the trigger's :payload-key value if needed."
  [{:keys [state project trigger id]}]
  (format "[%-15s] %s · %s · %s"
          (name state)
          (name project)
          (name trigger)
          id))

(defn format-age
  "Human-readable age string for an ISO-8601 timestamp relative to now.
   Buckets: just now (<10s), Ns ago, Nm ago, Nh ago, Nd ago."
  [iso-ts]
  (try
    (let [now     (java.time.Instant/parse (clock/now-iso))
          then    (java.time.Instant/parse iso-ts)
          seconds (.getSeconds (java.time.Duration/between then now))]
      (cond
        (< seconds 10)        "just now"
        (< seconds 60)        (str seconds "s ago")
        (< seconds 3600)      (str (quot seconds 60) "m ago")
        (< seconds 86400)     (str (quot seconds 3600) "h ago")
        :else                 (str (quot seconds 86400) "d ago")))
    (catch Exception _ "?")))

(def heartbeat-stale-after-seconds 5)

(defn read-coordinator-status
  "Read ~/.nido/coordinator/status.edn and decide reachability.
   Returns {:status <kw or :unreachable> :slots-in-use <int> :heartbeat-at <iso or nil> :reachable? <bool>}.
   `:reachable?` is true iff heartbeat is within `heartbeat-stale-after-seconds` of now."
  []
  (let [p (cstate/status-path)
        absent {:status :unreachable :reachable? false :heartbeat-at nil :slots-in-use 0}]
    (if-not (fs/exists? p)
      absent
      (try
        (let [s (io/read-edn p)
              hb (:heartbeat-at s)
              fresh? (and hb
                          (try
                            (let [now  (java.time.Instant/parse (clock/now-iso))
                                  then (java.time.Instant/parse hb)
                                  age  (.getSeconds (java.time.Duration/between then now))]
                              (<= age heartbeat-stale-after-seconds))
                            (catch Exception _ false)))]
          (-> s
              (assoc :reachable? (boolean fresh?))
              (cond-> (not fresh?) (assoc :status :unreachable))))
        (catch Exception _ absent)))))
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.runs-view-test
```

Expected: PASS — 10 tests, ~17 assertions.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): runs-view data layer for TUI overview"
jj new
```

---

## Task 1: Sessions-screen ⚙ marker for Run-owned sessions

**Files:**
- Modify: `src/nido/tui.clj` (session-rows function)

Lightweight integration that surfaces "this session is owned by a Run" on the existing sessions screen.

- [ ] **Step 1: Locate the session row builder**

```bash
grep -n "session-rows\|defn- session-row" /Users/yabmas/Code/nido/src/nido/tui.clj | head -5
```

You're looking for the function that builds rows for the sessions screen. It composes `{:title ... :description ...}` maps from session state on disk.

- [ ] **Step 2: Read the function and identify the title/description fields**

```bash
sed -n '60,120p' /Users/yabmas/Code/nido/src/nido/tui.clj
```

Note where the session name is rendered into `:title`. The session.edn file contains `:owned-by-run <run-id>` (from Task 13 of Stage 1a) when a session is Run-owned.

- [ ] **Step 3: Prepend `⚙ ` to the title for Run-owned sessions**

In the session row builder, after loading `session-edn` for each session, check `(:owned-by-run session-edn)`. If present, prefix the title with `"⚙ "`. Keep all other formatting unchanged.

Example (the actual file shape may differ slightly):

```clojure
(defn- session-rows [project]
  (let [sessions (engine/list-sessions project)]
    (mapv (fn [{:keys [name session-edn] :as data}]
            (let [run-owned? (boolean (:owned-by-run session-edn))
                  title      (cond->> name run-owned? (str "⚙ "))]
              {:title title
               :description (session-description data)
               :data data}))
          sessions)))
```

If the existing code reads session-edn differently, adapt — the contract is just "title gets `⚙ ` prefix when `:owned-by-run` is set."

- [ ] **Step 4: Smoke**

```bash
cd /Users/yabmas/Code/nido && bb tasks 2>&1 | grep nido:tui
# Then: open the TUI manually if you have a Run-owned session lying around.
# Otherwise this is a visual-only change; correctness is verified by reading.
```

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(tui): mark Run-owned sessions with ⚙ on sessions screen"
jj new
```

---

## Task 2: TUI tab navigation — `:runs` screen scaffold

**Files:**
- Modify: `src/nido/tui.clj`

Add a third screen `:runs` parallel to `:projects` and `:sessions`. Pressing `r` from any screen switches to runs; pressing `s` from the runs screen goes back to sessions. The screen renders an empty placeholder for now — Task 3 fills in the content.

- [ ] **Step 1: Add a `set-screen` helper near the existing screen-switching code**

Search for `(:screen state)` in `src/nido/tui.clj` to find the dispatch in `update-fn` (around line 311). Add a tiny helper above it:

```clojure
(defn- set-screen [state screen]
  ;; Drop any modal + selection state that doesn't apply across screens.
  (-> state
      (assoc :screen screen)
      (dissoc :modal :modal-target :modal-input)))
```

- [ ] **Step 2: Add `r` and `s` keybindings to switch screens**

In `update-fn`, BEFORE the `:else case` that dispatches to per-screen handlers, add:

```clojure
    ;; Tab-style navigation between screens. Always available, modal-aware.
    (and (nil? (:modal state)) (msg/key-match? msg "r") (not= :runs (:screen state)))
    [(set-screen state :runs) nil]

    (and (nil? (:modal state)) (msg/key-match? msg "s") (not= :sessions (:screen state)) (:project state))
    [(set-screen state :sessions) nil]
```

The `s`-switch is gated on `(:project state)` — without a project context, sessions screen has nothing to show; user should go via the projects screen first.

- [ ] **Step 3: Add the `:runs` case to the dispatcher**

In the same `update-fn`'s final `case`, add an arm:

```clojure
    (case (:screen state)
      :projects (update-projects state msg)
      :sessions (update-sessions state msg)
      :runs     (update-runs state msg))
```

- [ ] **Step 4: Define a placeholder `update-runs`**

Above `update-fn`, near the other `update-*` functions:

```clojure
(defn- update-runs
  "Runs screen update handler. Task 3 fills in row navigation and actions;
   for now we only handle list arrow keys via the embedded list component."
  [state msg]
  (let [[lst cmd] (item-list/list-update (:list state) msg)]
    [(assoc state :list lst) cmd]))
```

- [ ] **Step 5: Add header/footer entries for `:runs`**

In `header`, extend the screen-case:

```clojure
:runs (str "nido — runs")
```

In `footer`:

```clojure
:runs "[↵] enter session  [w]orktree  [d]etails  [f]ire trigger  [s]essions  [q]uit"
```

- [ ] **Step 6: Seed the runs list when switching to the screen**

The list component holds rows. When `set-screen state :runs` runs, the list still holds session rows. Fix by adding a list-builder for runs and calling it on screen change. Add near the top of the file (after the existing list builders):

```clojure
(defn- run-rows []
  ;; Stage 1b minimal: one row per Run, no group separators yet — those land in Task 3.
  ;; Order: just sort by id so the screen has something to show after scaffold lands.
  (->> (runs-view/read-all-runs)
       (sort-by :id #(compare %2 %1))
       (mapv (fn [r]
               {:title       (runs-view/format-row r)
                :description (some-> r :state-history last :at)
                :data        r}))))
```

Add `[nido.coordinator.runs-view :as runs-view]` to the `:require` block.

Then update `set-screen`:

```clojure
(defn- set-screen [state screen]
  (let [rows (case screen
               :runs     (run-rows)
               :sessions (session-rows (:project state))
               :projects (project-rows))]
    (-> state
        (assoc :screen screen
               :list   (item-list/list-init rows))
        (dissoc :modal :modal-target :modal-input))))
```

(If `item-list/list-init` isn't the right constructor in this codebase, match the constructor used elsewhere for list state — search for how `:list` is initialized today.)

- [ ] **Step 7: Manual smoke**

```bash
cd /Users/yabmas/Code/nido && bb nido:tui
# Press 'r' — should switch to a "nido — runs" screen with run rows.
# Press 's' (after entering a project from :projects) — back to sessions.
# Press 'q' to quit.
```

If you have no Runs on disk, the runs screen shows an empty list. That's expected — Task 3's grouped view adds an empty-state message.

- [ ] **Step 8: Commit**

```bash
jj desc -m "feat(tui): :runs screen scaffold + r/s tab navigation"
jj new
```

---

## Task 3: Runs screen content — grouped sections + status header

**Files:**
- Modify: `src/nido/tui.clj`

Replace the flat `run-rows` from Task 2 with the spec's three-group layout (Needs attention / In flight / Recent), add the coordinator-status line at the top of the runs view, and handle the empty-state.

Since `charm.components.list` expects a flat row list, we encode group headers as non-selectable "header" rows. The runs-view groupings drive the order.

- [ ] **Step 1: Build the grouped row list**

Replace the `run-rows` defn from Task 2 with a richer version. Group headers are rendered as `{:title "── Needs attention (N) ──" :description "" :data nil}` rows; the update handler skips them when computing the selected Run.

```clojure
(defn- run-group-rows [label runs]
  (when (seq runs)
    (cons {:title       (str "── " label " (" (count runs) ") ──")
           :description ""
           :data        ::group-header}
          (mapv (fn [r]
                  {:title       (runs-view/format-row r)
                   :description (or (some-> r :_run-status-note) "")
                   :data        r})
                runs))))

(defn- run-rows []
  (let [groups (runs-view/grouped-runs (runs-view/read-all-runs))]
    (vec
      (concat
        (run-group-rows "Needs attention" (:needs-attention groups))
        (run-group-rows "In flight"       (:in-flight groups))
        (run-group-rows "Recent"          (:recent groups))))))
```

- [ ] **Step 2: Skip group-header rows in selection**

When `update-runs` (Task 2) gets `↵`/`w`/`d` keys, it needs to know whether the highlighted row is a real Run or a group header. Helper:

```clojure
(defn- selected-run [state]
  (let [data (-> state :list item-list/selected-item :data)]
    (when (and data (not= data ::group-header)) data)))
```

(If `item-list/selected-item` isn't the right accessor in this codebase, use whatever the sessions screen uses to get the highlighted row's data.)

- [ ] **Step 3: Add the status header to the runs screen view**

The existing `view` function renders header + list + status + footer. Augment it for `:runs`:

```clojure
(defn- status-bar []
  (let [{:keys [status reachable? slots-in-use heartbeat-at]} (runs-view/read-coordinator-status)]
    (str (style/render label-style "Coordinator: ")
         (style/render
           (if reachable? status-style warning-style)
           (name status))
         "  •  "
         (style/render label-style "Slots: ")
         (or slots-in-use 0))))
```

Modify `view` to insert the status bar on the runs screen, between header and list:

```clojure
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
```

- [ ] **Step 4: Empty-state message**

If `run-rows` returns `[]`, the list renders empty. Add a sentinel row:

```clojure
(defn- run-rows []
  (let [groups (runs-view/grouped-runs (runs-view/read-all-runs))
        rows   (concat
                 (run-group-rows "Needs attention" (:needs-attention groups))
                 (run-group-rows "In flight"       (:in-flight groups))
                 (run-group-rows "Recent"          (:recent groups)))]
    (if (empty? rows)
      [{:title "No runs yet. Press 'f' to fire a manual trigger."
        :description ""
        :data ::empty}]
      (vec rows))))
```

Treat `::empty` like `::group-header` in `selected-run`: it's not actionable.

- [ ] **Step 5: Manual smoke**

```bash
cd /Users/yabmas/Code/nido && bb nido:tui
# Press 'r'. If no Runs: see the empty-state row.
# If you have a coordinator running: status bar shows "Coordinator: running ... Slots: N".
# If not: status bar shows "Coordinator: unreachable" in red.
```

Optionally, drop a fake Run for visual check:

```bash
mkdir -p ~/.nido/runs/2026-05-13-test-foo-zzzzzzzz
cat > ~/.nido/runs/2026-05-13-test-foo-zzzzzzzz/run.edn <<'EOF'
{:id "2026-05-13-test-foo-zzzzzzzz" :project :test :trigger :foo
 :source {:type :manual :fired-at "2026-05-13T10:00:00Z" :fired-by "u"}
 :event-payload {} :skill :foo :first-message "/foo"
 :agent :claude :session-name "run-test-foo-zzzzzzzz"
 :claude-session-id nil :limits {} :state :awaiting-review
 :state-history [{:at "2026-05-13T10:00:00Z" :state :queued}
                 {:at "2026-05-13T10:01:00Z" :state :running}
                 {:at "2026-05-13T10:02:00Z" :state :awaiting-review}]
 :artifacts [] :error nil}
EOF
bb nido:tui   # then 'r' — see "Needs attention (1)" group + the test row
rm -rf ~/.nido/runs/2026-05-13-test-foo-zzzzzzzz
```

- [ ] **Step 6: Commit**

```bash
jj desc -m "feat(tui): runs screen — grouped rows + coordinator status header"
jj new
```

---

## Task 4: Enter the run's session-home (`↵`) and worktree (`w`)

**Files:**
- Modify: `src/nido/tui.clj` (update-runs handler)
- Modify: `src/tasks/nido_tui.clj` (action dispatch)

`↵` writes the run's session-home path to `~/.nido/.last-cd` and exits the TUI; the `nido` shell wrapper cd's there. `w` does the same but for the worktree. Reuses the same action plumbing the sessions screen uses.

- [ ] **Step 1: Read the existing enter-action shape**

```bash
grep -n "queue-action!.*:enter\|:enter\|:up\b\|exit-action" /Users/yabmas/Code/nido/src/nido/tui.clj /Users/yabmas/Code/nido/src/tasks/nido_tui.clj | head -20
```

Find how `[:enter project session target]` is structured in the existing code. Match its shape.

- [ ] **Step 2: Define the run-targeted actions**

In `update-runs` (the placeholder from Task 2), handle `↵` and `w`:

```clojure
(defn- update-runs [state msg]
  (cond
    (msg/key-match? msg "enter")
    (if-let [run (selected-run state)]
      [state (queue-action! [:enter-run (:project run) (:session-name run) :home])]
      [state nil])

    (msg/key-match? msg "w")
    (if-let [run (selected-run state)]
      [state (queue-action! [:enter-run (:project run) (:session-name run) :worktree])]
      [state nil])

    :else
    (let [[lst cmd] (item-list/list-update (:list state) msg)]
      [(assoc state :list lst) cmd])))
```

- [ ] **Step 3: Handle the new action in the bb wrapper**

Read `src/tasks/nido_tui.clj` to find where existing actions are dispatched (search for `[:enter` or `case action`). Add an arm for `[:enter-run project session target]`:

```clojure
;; In the action dispatch of tasks.nido-tui:
[:enter-run project session target]
(session-lifecycle/enter! session {:project project :cd target})
```

The existing `session-lifecycle/enter!` already writes `~/.nido/.last-cd`. Run-owned sessions are still sessions — the same enter mechanism works.

- [ ] **Step 4: Manual smoke**

```bash
# Set up a fake Run with a session-name that actually exists, OR use
# a real run from Stage 1a's smoke. For an isolated test:
bb nido:tui
# 'r' → select a Run → '↵' → TUI exits → check ~/.nido/.last-cd
cat ~/.nido/.last-cd   # should contain the session-home path
```

If the session for the selected Run doesn't exist (e.g., a `:failed` Run whose session was torn down), `enter!` will error visibly. That's acceptable Stage 1b behavior — Stage 2 will gracefully skip terminal Runs whose sessions are gone.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(tui): runs screen ↵ enters session-home, w enters worktree"
jj new
```

---

## Task 5: Details modal (`d`)

**Files:**
- Modify: `src/nido/tui.clj`

`d` opens a read-only modal showing the selected Run's pretty-printed `run.edn` and the last 50 lines of `agent.log`. `esc` closes. No `o <n>` artifact-open yet (deferred — `bb nido:runs:show <id>` covers it from CLI).

- [ ] **Step 1: Add the modal open/close**

In `update-runs`, add a branch:

```clojure
(msg/key-match? msg "d")
(if-let [run (selected-run state)]
  [(-> state
       (assoc :modal :run-details)
       (assoc :modal-target {:run run}))
   nil]
  [state nil])
```

In `update-fn`, add the modal handler alongside the existing ones:

```clojure
(= :run-details (:modal state))
(update-run-details state msg)
```

And:

```clojure
(defn- update-run-details [state msg]
  (if (msg/key-match? msg "escape")
    [(close-modal state) nil]
    [state nil]))
```

- [ ] **Step 2: Render the modal body**

In `modal-body`, add an arm:

```clojure
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
```

Add the needed requires: `[babashka.fs :as fs]`, `[clojure.pprint]`, `[nido.coordinator.state :as cstate]`. If `fs` is already required, skip. If `cstate` is already required under another alias, reuse it.

- [ ] **Step 3: Add header + footer for the modal**

In `header`:

```clojure
:run-details (str "nido — run · " (-> state :modal-target :run :id))
```

In `footer`:

```clojure
:run-details "[esc] back"
```

- [ ] **Step 4: Manual smoke**

```bash
# Make a Run visible in the TUI (or use one already on disk)
bb nido:tui
# 'r' → select a Run → 'd' → see run.edn + agent.log tail
# 'esc' → back to runs list
```

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(tui): runs screen details modal"
jj new
```

---

## Task 6: Fire-trigger modal (`f`)

**Files:**
- Modify: `src/nido/tui.clj`
- Modify: `src/tasks/nido_tui.clj`

Pressing `f` walks the user through: pick project → pick trigger → fill payload kwargs → submit. Submission writes a queue file via `nido.coordinator.queue/enqueue!`.

For simplicity (charm.clj's modal model has one state at a time), the modal goes through three sub-states: `:project-pick`, `:trigger-pick`, `:payload-input`. We use the existing text-input component for payload fields, one at a time.

- [ ] **Step 1: Add modal-state types**

In `update-fn`, add modal handlers for the three sub-states:

```clojure
(= :fire-pick-project (:modal state))
(update-fire-pick-project state msg)

(= :fire-pick-trigger (:modal state))
(update-fire-pick-trigger state msg)

(= :fire-input-payload (:modal state))
(update-fire-input-payload state msg)
```

- [ ] **Step 2: Open the modal from the runs screen**

In `update-runs`, add:

```clojure
(msg/key-match? msg "f")
(open-fire-trigger state)
```

Where `open-fire-trigger` is:

```clojure
(defn- open-fire-trigger [state]
  (let [projects (vec (keys (project/list-projects)))]
    (cond
      (empty? projects)
      [state nil]   ; no projects registered → nothing to fire

      (= 1 (count projects))
      ;; Skip project picker; jump straight to trigger picker.
      (open-fire-pick-trigger state (first projects))

      :else
      [(-> state
           (assoc :modal :fire-pick-project)
           (assoc :modal-target {:projects projects :cursor 0}))
       nil])))
```

- [ ] **Step 3: Project picker (when >1 project)**

```clojure
(defn- update-fire-pick-project [state msg]
  (let [{:keys [projects cursor]} (:modal-target state)]
    (cond
      (msg/key-match? msg "escape") [(close-modal state) nil]

      (msg/key-match? msg "up")
      [(assoc-in state [:modal-target :cursor] (max 0 (dec cursor))) nil]

      (msg/key-match? msg "down")
      [(assoc-in state [:modal-target :cursor] (min (dec (count projects)) (inc cursor))) nil]

      (msg/key-match? msg "enter")
      (open-fire-pick-trigger state (nth projects cursor))

      :else [state nil])))
```

- [ ] **Step 4: Trigger picker**

```clojure
(defn- open-fire-pick-trigger [state project-str]
  (let [project-kw (keyword project-str)
        triggers   (->> (triggers/load-for-project project-kw)
                        (filter #(= :manual (-> % :source :type)))
                        vec)]
    (if (empty? triggers)
      [(-> state
           (assoc :modal :fire-pick-trigger)
           (assoc :modal-target {:project project-kw
                                 :triggers []
                                 :error "(no manual triggers for this project)"}))
       nil]
      [(-> state
           (assoc :modal :fire-pick-trigger)
           (assoc :modal-target {:project project-kw
                                 :triggers triggers
                                 :cursor 0}))
       nil])))

(defn- update-fire-pick-trigger [state msg]
  (let [{:keys [project triggers cursor]} (:modal-target state)]
    (cond
      (msg/key-match? msg "escape") [(close-modal state) nil]

      (or (empty? triggers) (nil? cursor)) [state nil]

      (msg/key-match? msg "up")
      [(assoc-in state [:modal-target :cursor] (max 0 (dec cursor))) nil]

      (msg/key-match? msg "down")
      [(assoc-in state [:modal-target :cursor] (min (dec (count triggers)) (inc cursor))) nil]

      (msg/key-match? msg "enter")
      (start-payload-input state project (nth triggers cursor))

      :else [state nil])))
```

Add to `:require`: `[nido.coordinator.triggers :as triggers]`.

- [ ] **Step 5: Payload input — one field at a time**

A trigger's `:payload` template like `"url={{event/url}}"` has placeholder names extracted by a small helper. We then prompt for each in turn.

```clojure
(defn- placeholder-keys
  "Return ordered vector of placeholder names from a trigger's :payload
   template. `{{event/url}}` → `:url`."
  [payload-template]
  (->> (re-seq #"\{\{event/([^}/]+)\}\}" payload-template)
       (map second)
       distinct
       (mapv keyword)))

(defn- start-payload-input [state project trigger]
  (let [keys (placeholder-keys (:payload trigger))]
    (if (empty? keys)
      ;; No placeholders → fire immediately.
      (do
        (queue/enqueue! {:target  {:project project :trigger (:name trigger)}
                         :payload {}})
        [(-> state
             (assoc :status (str "queued: " project "/" (name (:name trigger)))))
         (close-modal-cmd)])    ; close-modal-cmd helper below — see Step 6
      [(-> state
           (assoc :modal :fire-input-payload)
           (assoc :modal-target {:project project
                                 :trigger trigger
                                 :keys    keys
                                 :idx     0
                                 :values  {}})
           (assoc :modal-input (text-input/text-input-init {:placeholder (str (first keys))})))
       nil])))
```

- [ ] **Step 6: Payload input handler**

```clojure
(defn- update-fire-input-payload [state msg]
  (let [{:keys [project trigger keys idx values]} (:modal-target state)]
    (cond
      (msg/key-match? msg "escape")
      [(close-modal state) nil]

      (msg/key-match? msg "enter")
      (let [v          (str/trim (text-input/value (:modal-input state)))
            k          (nth keys idx)
            values'    (assoc values k v)
            next-idx   (inc idx)]
        (if (< next-idx (count keys))
          [(-> state
               (assoc-in [:modal-target :values] values')
               (assoc-in [:modal-target :idx] next-idx)
               (assoc :modal-input (text-input/text-input-init
                                     {:placeholder (str (nth keys next-idx))})))
           nil]
          ;; Done — enqueue.
          (do
            (queue/enqueue! {:target  {:project project :trigger (:name trigger)}
                             :payload values'})
            [(-> state
                 (close-modal)
                 (assoc :status (str "queued: " (name project) "/" (name (:name trigger))
                                     " — refresh with 'r' to see it")))
             nil])))

      :else
      (let [[ti cmd] (text-input/text-input-update (:modal-input state) msg)]
        [(assoc state :modal-input ti) cmd]))))
```

Add `[nido.coordinator.queue :as queue]` to `:require`.

- [ ] **Step 7: Modal headers + footers + body**

In `header`:

```clojure
:fire-pick-project "nido — fire trigger · pick project"
:fire-pick-trigger (str "nido — fire trigger · " (-> state :modal-target :project name))
:fire-input-payload (str "nido — fire trigger · " (-> state :modal-target :project name) " · "
                         (-> state :modal-target :trigger :name name))
```

In `footer`:

```clojure
:fire-pick-project  "[↑↓] move  [↵] pick  [esc] cancel"
:fire-pick-trigger  "[↑↓] move  [↵] pick  [esc] cancel"
:fire-input-payload "[↵] next field  [esc] cancel"
```

In `modal-body`:

```clojure
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
                             (str (if (= i cursor) "▸ " "  ") (name (:name t))))
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
                        (for [[k v] values]
                          (str "  " (name k) " = " v)))))))
```

- [ ] **Step 8: Manual smoke**

```bash
# Set up a trigger:
mkdir -p ~/.nido/projects/brian
cat > ~/.nido/projects/brian/triggers.edn <<'EOF'
{:triggers
 [{:name :smoke :source {:type :manual} :skill :echo :payload "msg={{event/msg}}"}]}
EOF

# Run TUI:
bb nido:tui
# 'r' → 'f' → (project pick if multiple) → pick :smoke → type "hello" → ↵ →
# see "queued: brian/smoke" status. Verify:
ls ~/.nido/coordinator/queue/
rm ~/.nido/coordinator/queue/*.edn
```

- [ ] **Step 9: Commit**

```bash
jj desc -m "feat(tui): runs screen fire-trigger modal"
jj new
```

---

## Task 7: Closing checklist + spec self-review

- [ ] **Step 1: Run the full test suite**

```bash
cd /Users/yabmas/Code/nido && bb nido:test 2>&1 | tail -3
```

Expected: 51 tests (41 from 1a + 10 new from Task 0), 0 failures.

- [ ] **Step 2: Walk-through smoke**

```bash
# Make sure brian's coordinator + a session can be exercised:
bb nido:tui
# 's' → enter a project → see ⚙ on any Run-owned sessions
# 'r' → see runs screen with status header + grouped sections
# 'f' → fire a trigger → watch it appear in :in-flight
# 'd' on a Run → details modal → 'esc'
# '↵' on a Run → enter session-home → cd via nido shell wrapper
# 'q' → quit
```

- [ ] **Step 3: Spec coverage map (add to commit)**

Append to the existing `docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md` (or note in commit body): Stage 1b delivers §"Run overview TUI surface" minus `h`/`c` (deferred to Stage 2) and live polling (deferred — quit-and-reenter is consistent with sessions screen).

- [ ] **Step 4: Commit if any tidying landed**

```bash
jj desc -m "docs(coordinator): Stage 1b complete — TUI runs surface"
jj new
```

---

## Spec coverage map

| Spec section (TUI surface) | Task |
|---|---|
| Layout: tabs `[s]essions [r]uns` | Task 2 |
| Status header (coordinator + slots) | Task 3 |
| Groups: Needs attention / In flight / Recent | Tasks 0, 3 |
| Empty state message | Task 3 |
| Row format `[state] project · trigger · subject` | Tasks 0, 3 |
| `↑/↓` selection | Task 2 (existing list component) |
| `↵` enter session | Task 4 |
| `w` worktree | Task 4 |
| `d` details modal | Task 5 |
| `f` fire-trigger modal | Task 6 |
| `q` quit | Existing TUI |
| `h` halt | **Out of scope — Stage 2** |
| `c` clear circuit breaker | **Out of scope — Stage 2** |
| Sessions-screen ⚙ marker | Task 1 |
| Refresh cadence (1s poll) | **Out of scope — current quit-and-reenter pattern; revisit in Stage 2+** |

---

## Notes for the implementer

- **charm.clj quirks:** the existing `tui.clj` documents JLine ghosting and works around it by rendering inline. Match that pattern (don't switch to alt-screen).
- **The action atom:** the existing TUI uses an `exit-action` atom that bb-task wrappers read after `program/run` returns. Match this pattern — don't try to invoke session lifecycle from inside `update-fn`.
- **`item-list/...` API specifics:** if `list-init`, `list-update`, `list-view`, or `selected-item` aren't quite those names in your codebase version, search the existing TUI for the equivalents and use those. The task descriptions name the *role*, not the exact symbol.
- **clj-kondo noise:** new bb-task entries trigger "unused public var" warnings. Standard noise in this codebase; ignore.
