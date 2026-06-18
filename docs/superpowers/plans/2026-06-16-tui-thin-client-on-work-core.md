# TUI as a Thin `nido.work` Client (Plan B) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. This is a **jj** repo — the `jujutsu` skill MUST be active before any VCS step, and the controller keeps `@` a clean empty change per task so each `jj commit` captures only that task's files (planning artifacts stay uncommitted in the working copy).

**Goal:** Rebuild `nido.tui` as a thin client of the `nido.work` core: one stage-grouped board (origin = badge + filter, not per-source tabs), the tiny `open`/`new`/`promote`/`done` work keymap, and a separate **system surface** for daemon/brakes/fire and session plumbing.

**Architecture:** The TUI stops holding model logic. Its board reads `nido.work/grouped`; its detail reads `nido.work/workstream`; its verbs call `nido.work/{open-target,new!,set-stage!,default-target}`. The five per-source views (`view-defs`) and the engagement-grouping fork are deleted. Coordinator controls (`h`/`c`/`f`) and session plumbing (`u`/`d`/`x`/`w`/`i`) move off the board onto a new `:system` screen. Everything that was already async/charm machinery (the action channel, spinner actions, Warp tab spawning, live-refresh tick, `enter-session`) is preserved and reused.

**Tech Stack:** Babashka/Clojure, charm.clj (Elm-style `init`/`update`/`view`), `clojure.test`. The new model logic lives in `nido.work` (Plan A, already shipped); this plan only touches the TUI.

**Altitude note (read before starting):** The TUI is a charm program; its *pure* functions and `update-*` transitions are unit-testable (feed `charm.message/key-press`, inspect returned state — see `test/nido/tui_test.clj`), but the `view` rendering and the program loop are verified by running `bb nido:tui`. This plan gives **concrete code + TDD tests for the new pure logic and state transitions**, and **precise wiring instructions + a manual smoke step** (`bb nido:tui`) for the charm glue. Where it says "rewire X to call Y", the implementer edits the existing handler in place — it is not a from-scratch rewrite of the charm scaffolding.

**Design:** `docs/superpowers/specs/2026-06-16-coherent-workstream-core-and-thin-surfaces-design.md`. Depends on Plan A (`nido.work`), already merged.

---

## File Structure

- **Modify `src/nido/tui.clj`** — the only production file. Net effect across the plan:
  - `view-defs` / `view-for-id` / `step-view` / `next-view` / `prev-view` / `tab-bar` → **deleted**, replaced by an origin-filter vocab (`origin-filters`, `step-origin`) and a single board.
  - `workstream-list-rows` (wsv grouping + engagement fork) → **replaced** by `board-rows` (reads `work/grouped`).
  - `update-workstreams` → **rewired** to the work keymap (`open`/`i`/`n`/`p`/`P`/`d` + filter + `s`).
  - `update-sessions` (the ops keymap) + coordinator modals (`update-halt-*`, `update-clear-breaker`, fire-trigger) + `status-bar` → **moved** under a new `:system` screen.
  - `ws-detail-rows` → **replaced** by a `work/workstream`-driven detail (autonomy axis + ledger).
  - `current-rows` / `view` / `header` / `footer` / `update-fn` → updated for the new screens.
- **Modify `test/nido/tui_test.clj`** — delete the stale `view-defs`/cycling/tab-bar tests; add tests for the origin filter, board rows, the work-verb transitions, and the system-surface transitions. The new tests reference `nido.work`, `nido.coordinator.triggers`, and `nido.project` in `with-redefs`, so add those to the test ns `:require` as the tasks that introduce them land (Task 1.2 → `[nido.work]`; Task 3.3 → `[nido.coordinator.triggers]`, `[nido.project]`).
- **Modify `CLAUDE.md`** — remove the stale "press `r` for the runs surface" line; update the TUI keymap description.

The TUI is large; this plan does NOT split it — but each phase below leaves a runnable TUI. Commit per task.

---

## Phase 1 — Board on the spine

### Task 1.1: Origin-filter vocabulary (replaces `view-defs` cycling)

**Files:** Modify `src/nido/tui.clj`; Test `test/nido/tui_test.clj`

The board is filtered by origin, not split into source tabs. The filter cycles `:all → :notion → :github → :slack → :scratch → :all`.

- [ ] **Step 1: Write the failing test** (append to `test/nido/tui_test.clj`):

```clojure
(deftest origin-filter-cycles-all-then-each-origin
  (is (= [:all :notion :github :slack :scratch] (mapv :id @#'tui/origin-filters)))
  (is (= :notion  (#'tui/step-origin :all 1)))
  (is (= :scratch (#'tui/step-origin :slack 1)))
  (is (= :all     (#'tui/step-origin :scratch 1)) "wraps forward")
  (is (= :scratch (#'tui/step-origin :all -1)) "wraps back"))
```

- [ ] **Step 2: Run** `bb nido:test :only nido.tui` → FAIL (unresolved `origin-filters`/`step-origin`).

- [ ] **Step 3: Implement.** In `src/nido/tui.clj`, REPLACE the `view-defs` / `view-for-id` / `step-view` / `next-view` / `prev-view` block with:

```clojure
;; ---------------------------------------------------------------------------
;; Origin filter: the board is one stage-grouped list (nido.work/grouped); the
;; workstream's origin is a badge per row and a filter, not a separate screen.
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
```

- [ ] **Step 4: Run** `bb nido:test :only nido.tui` → the new test PASSES. (Other tests will be broken by the `view-defs` removal — that is expected; Task 4.2 rewrites them. To keep this task green in isolation, ALSO delete the now-unresolvable tests `view-order-is-notion-github-scratch-sessions`, `github-view-is-a-workstreams-view-on-the-github-source`, `cycling-wraps-both-directions`, `view-for-id-resolves`, and `tab-bar-marks-the-active-view` from `test/nido/tui_test.clj` now — they test deleted vars.)

- [ ] **Step 5: Commit**

```bash
jj commit -m "refactor(tui): replace source-tab view-defs with an origin filter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 1.2: `board-rows` from `nido.work/grouped` with origin badges

**Files:** Modify `src/nido/tui.clj`; Test `test/nido/tui_test.clj`

The board's rows come from `work/grouped` (spine groups: Ready / In progress / Triage in-flight+queued), filtered by origin, each row tagged with an origin badge. Reuse the existing `stage-group-rows` / `triage-group-rows` / `group-header` / `strip-leading-blank` helpers (keep them).

- [ ] **Step 1: Write the failing test** (append):

```clojure
(deftest origin-badge-tags-each-source
  (is (= "N" (#'tui/origin-badge :notion)))
  (is (= "G" (#'tui/origin-badge :github)))
  (is (= "S" (#'tui/origin-badge :slack)))
  (is (= "·" (#'tui/origin-badge :scratch))))

(deftest board-rows-group-by-spine-and-filter-by-origin
  (with-redefs [nido.work/grouped
                (fn [_ _]
                  {:ready       [{:ws-id "r1" :origin :notion :label "BR-1 · a"
                                  :needs-you true :engagement :idle}]
                   :in-progress [{:ws-id "p1" :origin :scratch :label "spike"
                                  :needs-you false :engagement :active}]
                   :triage      {:in-flight [] :queued []}})
                nido.tui/live-session-names (constantly #{})]
    (let [all (#'tui/board-rows "brian" :all)
          labels (keep #(get-in % [:data :ws-id]) all)]
      (is (= ["r1" "p1"] (vec labels)) "ready then in-progress, all origins")
      (is (some #(re-find #"Ready to pick up" (:title %)) all))
      (is (some #(re-find #"In progress" (:title %)) all)))
    (let [scratch-only (#'tui/board-rows "brian" :scratch)
          ids (keep #(get-in % [:data :ws-id]) scratch-only)]
      (is (= ["p1"] (vec ids)) "origin filter keeps only scratch rows"))))
```

- [ ] **Step 2: Run** `bb nido:test :only nido.tui` → FAIL (unresolved `origin-badge`/`board-rows`).

- [ ] **Step 3: Implement.** Add `[nido.work :as work]` to the `nido.tui` ns `:require`. Replace `workstream-list-rows` with:

```clojure
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
  "Triage rendered as in-flight (expanded) + a queued count line — same split the
   old board used, but reading work/grouped's :triage map."
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
```

- [ ] **Step 4: Run** `bb nido:test :only nido.tui` → new tests PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(tui): board-rows reads nido.work/grouped with origin badges + filter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 1.3: Wire the board screen + work-verb keymap

**Files:** Modify `src/nido/tui.clj`; Test `test/nido/tui_test.clj`

Replace the board's `:view`-based state with `:origin`, point `current-rows`/`enter-board` at `board-rows`, and rewire `update-workstreams` to the work keymap. The verbs delegate to `nido.work` (the default `p`/`d`/`n` behave exactly as today because `set-stage!`/`new!` wrap the same coordinator calls). System keys (`f`/`h`/`c`) and the ops view leave the board (handled in Phase 3 — for now, remove `f`/`h`/`c` from the board keymap and add `s`).

- [ ] **Step 1: Write the failing tests** (append). These assert the board transition table:

```clojure
(defn- board-state [origin]
  {:screen :board :origin origin :project "brian" :list (#'tui/list-component [])})

(deftest board-open-routes-through-open-target
  (with-redefs [nido.work/open-target (fn [_ _] {:project :brian :session "live"})
                nido.tui/selected-workstream (fn [_] {:ws-id "w1"})
                nido.tui/enter-session (fn [s _ sn _] (assoc s ::opened sn))]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "o"))]
      (is (= "live" (::opened s')) "open resolves the session via work/open-target"))))

(deftest board-promote-uses-default-target
  (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :promote-id "BR-1"})
                nido.work/default-target (fn [_ action] (is (= :promote action)) :in-progress)
                nido.work/set-stage! (fn [_ id target] {:decision :promote :id id :target target})
                nido.tui/current-rows (constantly [])]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "p"))]
      (is (re-find #"promoted|in progress" (:status s'))))))

(deftest board-done-sets-stage-done
  (let [calls (atom [])]
    (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :br-id "BR-1"})
                  nido.work/set-stage! (fn [_ id target] (swap! calls conj [id target]) {:decision :done})
                  nido.tui/current-rows (constantly [])]
      (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "d"))]
        (is (= [["w1" :done]] @calls) "d → set-stage! :done")))))

(deftest board-n-opens-create-session
  (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "n"))]
    (is (= :create-session (:modal s')) "n opens the new-workstream modal"))
  ;; `s` → enter-system → session-rows (disk); stub it so the test is hermetic.
  (with-redefs [nido.tui/session-rows (constantly [])]
    (is (= :system (:screen (first (#'tui/update-board (board-state :all) (msg/key-press "s")))))
        "s opens the system surface")))

(deftest board-tab-cycles-origin-filter
  (with-redefs [nido.tui/current-rows (constantly [])]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "tab"))]
      (is (= :notion (:origin s'))))))
```

- [ ] **Step 2: Run** → FAIL (unresolved `update-board`; `:origin` not wired).

- [ ] **Step 3: Implement.**
  1. In `current-rows`, replace the `:board` branch:
     ```clojure
     :board  (board-rows (:project state) (:origin state))
     ```
     (delete the `active-view`/`:ops` dispatch).
  2. In `enter-board`, set `:origin :all` instead of `:view :notion`:
     ```clojure
     (defn- enter-board [state project-name]
       (let [s (assoc state :screen :board :project project-name :origin :all :status nil)]
         (rebuild-list s (current-rows s))))
     ```
  3. Replace `update-workstreams` with `update-board` (rename + rewire):
     ```clojure
     ;; NOTE on project type: pass `(:project state)` (a STRING, as the rest of the
     ;; TUI does) to every nido.work fn. They are all name-tolerant (cstate paths
     ;; use (name …); default-target's project-entry tries string AND symbol keys),
     ;; so no (keyword …) wrapping is needed — keep it consistent and string-typed.
     (defn- open-selected
       "Open the highlighted workstream's session/chat via work/open-target."
       [state]
       (if-let [ws (selected-workstream state)]
         (if-let [{:keys [session]} (work/open-target (:project state) (:ws-id ws))]
           (enter-session state (:project state) session :home)
           [(assoc state :status "(no session to open yet)") nil])
         [(assoc state :status "(no workstream selected)") nil]))

     (defn- promote-selected [state]
       (if-let [ws (selected-workstream state)]
         (let [target   (work/default-target (:project state) :promote)
               decision (:decision (work/set-stage! (:project state) (:ws-id ws) target))]
           [(-> state (refresh-list (current-rows state))
                (assoc :status (wsv/promote-result-message (:promote-id ws) decision)))
            nil])
         [(assoc state :status "(no workstream selected)") nil]))

     (defn- done-selected [state]
       (if-let [ws (selected-workstream state)]
         (do (work/set-stage! (:project state) (:ws-id ws) :done)
             [(-> state (refresh-list (current-rows state))
                  (assoc :status (str "marked " (or (:br-id ws) (:ws-id ws)) " done")))
              nil])
         [(assoc state :status "(no workstream selected)") nil]))

     (defn- update-board [state msg]
       (cond
         (msg/key-match? msg "escape") [(enter-projects state) nil]
         (or (msg/key-match? msg "enter") (msg/key-match? msg "o")) (open-selected state)
         (msg/key-match? msg "i")
         (if-let [ws (selected-workstream state)]
           [(enter-workstream state (:ws-id ws) (:label ws)) nil] [state nil])
         (msg/key-match? msg "n") (open-create-session state (:project state))
         (msg/key-match? msg "p") (promote-selected state)
         (msg/key-match? msg "P") (open-stage-picker state)        ; Phase 2 defines open-stage-picker
         (msg/key-match? msg "d") (done-selected state)
         (msg/key-match? msg "s") [(enter-system state) nil]        ; Phase 3 defines enter-system
         :else (let [[lst cmd] (item-list/list-update (:list state) msg)]
                 [(assoc state :list lst) cmd])))
     ```
     NOTE: `open-stage-picker` (Task 2.2) and `enter-system` (Task 3.1) don't exist yet. To keep THIS task compiling and green, add temporary stubs at the top of the file's update section:
     ```clojure
     (declare open-stage-picker enter-system)
     ```
     and define minimal placeholders now that the later tasks REPLACE:
     ```clojure
     (defn- open-stage-picker [state] [(assoc state :status "(stage picker — Phase 2)") nil])
     (defn- enter-system [state] (assoc state :status "(system surface — Phase 3)"))
     ```
  4. In `update-fn`, change the board view-cycling keys to cycle the origin filter, and route the board to `update-board`:
     ```clojure
     ;; origin-filter cycling on the board
     (and (nil? (:modal state)) (= :board (:screen state))
          (or (msg/key-match? msg "tab") (msg/key-match? msg "right")))
     [(set-origin state (step-origin (:origin state) 1)) nil]
     (and (nil? (:modal state)) (= :board (:screen state))
          (or (msg/key-match? msg "shift+tab") (msg/key-match? msg "left")))
     [(set-origin state (step-origin (:origin state) -1)) nil]
     ```
     and in the trailing `case (:screen state)`:
     ```clojure
     :board (update-board state msg)
     ```
     Add `set-origin` (mirrors the deleted `set-view`):
     ```clojure
     (defn- set-origin [state origin]
       (let [s (assoc state :screen :board :origin origin :status nil)]
         (-> s (rebuild-list (current-rows s))
             (dissoc :modal :modal-target :modal-input :ws-id :ws-label))))
     ```
  5. Update `header` and `footer` board branches to read `:origin` and advertise the new keymap:
     ```clojure
     ;; header :board branch:
     :board (str "nido — " (:project state) " · " (name (:origin state)))
     ;; footer :board branch:
     :board "[↵/o] open  [i]nspect  [n]ew  [p]romote  [P] promote to…  [d]one  [⇄ tab] origin  [s]ystem  [esc] back  [q]uit"
     ```
  6. Update `view` to render an origin-filter strip instead of `tab-bar` and drop the board status-bar (moves to system in Phase 3):
     ```clojure
     ;; in `view`, replace the (tab-bar ...) line with an origin strip:
     (when (= :board (:screen state))
       (str (origin-strip (:origin state)) "\n"))
     ;; and REMOVE the (status-bar) block from the board view.
     ```
     Add `origin-strip` (parallels the deleted `tab-bar`):
     ```clojure
     (defn- origin-strip [active-id]
       (->> origin-filters
            (map (fn [{:keys [id label]}]
                   (if (= id active-id)
                     (style/render active-tab-style (str "[" label "]"))
                     (style/render inactive-tab-style (str " " label " ")))))
            (str/join "  ")))
     ```

- [ ] **Step 4: Run** `bb nido:test :only nido.tui` → all present tests PASS. Then **smoke-test**: `bb nido:tui`, drill into a project, confirm the board shows one stage-grouped list with N/G/S/· badges, `tab` cycles the origin filter, `p`/`d`/`n` behave as before, `s` shows the Phase-3 placeholder status. Press `q` to exit.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(tui): board screen on nido.work — work verbs + origin filter

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 2 — Detail view + promote override

### Task 2.1: Workstream detail on `nido.work/workstream` (autonomy axis + ledger)

**Files:** Modify `src/nido/tui.clj`; Test `test/nido/tui_test.clj`

The detail screen is read-only: it lists the workstream's sessions on the autonomy axis (`:autonomy-level`, `:status`, `:parked?`, `:brakes`) plus a ledger line, with `↵`/`o` opening the highlighted session and `esc` back to the board.

- [ ] **Step 1: Write the failing test** (append):

```clojure
(deftest detail-rows-render-sessions-on-the-autonomy-axis
  (with-redefs [nido.work/workstream
                (fn [_ _]
                  {:ws-id "w1" :origin :notion :stage :triage :label "BR-1 · a"
                   :ledger {:key "BR-1" :status :investigating :report-count 1}
                   :sessions [{:name "auto" :autonomy-level :autonomous :parked? true
                               :status :parked :brakes {:budget "30m"}}
                              {:name "me" :autonomy-level :interactive :parked? false
                               :status :up :brakes nil}]})]
    (let [rows (#'tui/detail-rows "brian" "w1")
          titles (mapv :title rows)]
      (is (some #(re-find #"ledger: BR-1" %) titles) "ledger line rendered first")
      (is (some #(re-find #"auto" %) titles))
      (is (some #(re-find #"parked|autonomous" %) titles) "autonomy state shown")
      (is (some #(re-find #"me" %) titles))
      ;; a ledger row precedes the sessions, so find the session row by name
      ;; rather than by position:
      (is (some #(= "auto" (-> % :data :name)) rows) "session rows carry :name for open"))))
```

- [ ] **Step 2: Run** → FAIL (unresolved `detail-rows`).

- [ ] **Step 3: Implement.** Replace `ws-detail-rows` with a `work/workstream`-driven `detail-rows`:

```clojure
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
```

Point `enter-workstream` and `current-rows` (the `:workstream` branch) at `detail-rows` instead of `ws-detail-rows`. Update `update-workstream` so `↵`/`o` opens the highlighted session via `enter-session` and `esc` returns to the board (`set-origin state (:origin state)`):

```clojure
(defn- update-workstream [state msg]
  (cond
    (msg/key-match? msg "escape") [(set-origin state (:origin state)) nil]
    (or (msg/key-match? msg "enter") (msg/key-match? msg "o"))
    ;; detail-rows session data carries only :name (it's a work/workstream facet,
    ;; not a registry row), so open with the screen's own project string. The
    ;; ::ledger / ::empty sentinels aren't maps with :name, so some-> yields nil
    ;; and the key no-ops on them.
    (if-let [sname (some-> (selected-data state) :name)]
      (enter-session state (:project state) sname :home)
      [state nil])
    :else (let [[lst cmd] (item-list/list-update (:list state) msg)]
            [(assoc state :list lst) cmd])))
```

NOTE: this drops the dependency on `selected-session-row` for the detail screen (it required `:project` on the row, which the facet data lacks). Leave `selected-session-row` defined if anything else uses it; otherwise Task 4.1 removes it.

- [ ] **Step 4: Run** `bb nido:test :only nido.tui` → PASS. Smoke: `bb nido:tui` → `i` on a workstream shows the ledger + autonomy-axis sessions; `↵` opens one; `esc` returns to the board at the same origin filter.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(tui): read-only workstream detail on nido.work (autonomy axis + ledger)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 2.2: Promote override — stage picker (`P`)

**Files:** Modify `src/nido/tui.clj`; Test `test/nido/tui_test.clj`

`p` promotes to the per-project default; `P` opens a picker over the spine stages and `set-stage!`s to the chosen one. Reuse the existing `picker-list`/`picker-selected`/`picker-route` modal plumbing.

- [ ] **Step 1: Write the failing test** (append):

```clojure
(deftest stage-picker-promotes-to-the-chosen-target
  (let [calls (atom [])]
    (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :promote-id "BR-1"})
                  nido.work/set-stage! (fn [_ id t] (swap! calls conj [id t]) {:decision :advanced})
                  nido.tui/current-rows (constantly [])]
      (let [opened (first (#'tui/open-stage-picker (board-state :all)))]
        (is (= :stage-picker (:modal opened)))
        ;; pick :ready and confirm
        (let [picked (assoc-in opened [:modal-target :picker]
                               (#'tui/picker-list [{:title "ready" :data :ready}]))
              [s' _] (#'tui/update-stage-picker picked (msg/key-press "enter"))]
          (is (= [["w1" :ready]] @calls) "P → set-stage! to the picked stage")
          (is (nil? (:modal s')) "picker closes after pick"))))))
```

- [ ] **Step 2: Run** → FAIL (unresolved `update-stage-picker`; `open-stage-picker` is still the Phase-1 placeholder).

- [ ] **Step 3: Implement.** REPLACE the Phase-1 `open-stage-picker` placeholder with the real one + its update handler:

```clojure
(defn- open-stage-picker
  "Open a picker over the spine stages to aim `promote` at a target (the override
   for the default `p`). Empty when no workstream is selected."
  [state]
  (if-let [ws (selected-workstream state)]
    [(-> state
         (assoc :modal :stage-picker)
         (assoc :modal-target
                {:ws-id (:ws-id ws) :promote-id (:promote-id ws)
                 :picker (picker-list (mapv (fn [s] {:title (name s) :data s}) work/stages))}))
     nil]
    [(assoc state :status "(no workstream selected)") nil]))

(defn- update-stage-picker [state msg]
  (cond
    (msg/key-match? msg "escape") [(close-modal state) nil]
    (msg/key-match? msg "enter")
    (if-let [target (picker-selected state)]
      (let [{:keys [ws-id promote-id]} (:modal-target state)
            decision (:decision (work/set-stage! (:project state) ws-id target))]
        [(-> state close-modal
             (refresh-list (current-rows state))
             (assoc :status (wsv/promote-result-message promote-id decision)))
         nil])
      [state nil])
    :else (picker-route state msg)))
```

Wire `:stage-picker` into `update-fn`'s modal dispatch (next to the other modal cases):

```clojure
(= :stage-picker (:modal state)) (update-stage-picker state msg)
```

and add header/footer entries:

```clojure
;; header: :stage-picker "nido — promote to…"
;; footer: :stage-picker "[↑↓] move  [↵] promote here  [esc] cancel"
;; modal-body: :stage-picker (item-list/list-view (:picker (:modal-target state)))
```

- [ ] **Step 4: Run** `bb nido:test :only nido.tui` → PASS. Smoke: `bb nido:tui` → `P` shows the stage list; pick `ready` → status reflects the advance.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(tui): P opens a stage picker to aim promote (set-stage override)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 3 — System surface

### Task 3.1: `:system` screen scaffold + navigation

**Files:** Modify `src/nido/tui.clj`; Test `test/nido/tui_test.clj`

A new top-level screen for machine-health. It opens from the board (`s`), `esc` returns to the board. Its list is the project's sessions (the relocated ops view); its top line is the coordinator status-bar; its keymap carries the relocated system levers (added in 3.2/3.3).

- [ ] **Step 1: Write the failing test** (append):

```clojure
(deftest system-surface-opens-from-board-and-returns
  ;; enter-system reads session-rows; esc → set-origin → current-rows. Stub both.
  (with-redefs [nido.tui/session-rows (constantly [])
                nido.tui/current-rows (constantly [])]
    (let [opened (#'tui/enter-system (board-state :all))]
      (is (= :system (:screen opened)))
      (let [[back _] (#'tui/update-system opened (msg/key-press "escape"))]
        (is (= :board (:screen back)) "esc returns to the board")))))
```

- [ ] **Step 2: Run** → FAIL (`enter-system` is the Phase-1 placeholder; `update-system` unresolved).

- [ ] **Step 3: Implement.** REPLACE the Phase-1 `enter-system` placeholder:

```clojure
(defn- enter-system
  "Drill from the board into the system surface (daemon health + session plumbing)."
  [state]
  (let [s (assoc state :screen :system :status nil)]
    (rebuild-list s (session-rows (:project state)))))
```

Add the `:system` branch to `current-rows`:

```clojure
:system (session-rows (:project state))
```

Add `update-system` (the relocated ops keymap — body filled in 3.2/3.3; for now nav + list):

```clojure
(defn- update-system [state msg]
  (cond
    (msg/key-match? msg "escape") [(set-origin state (:origin state)) nil]
    :else (let [[lst cmd] (item-list/list-update (:list state) msg)]
            [(assoc state :list lst) cmd])))
```

Route `:system` in `update-fn`'s trailing `case`:

```clojure
:system (update-system state msg)
```

Add `header`/`footer`/`view` entries for `:system` (header: `"nido — " (:project state) " · system"`; footer filled in 3.2/3.3). In `view`, render the status-bar above the list ONLY for `:system`:

```clojure
(when (= :system (:screen state)) (str (status-bar) "\n"))
```

- [ ] **Step 4: Run** `bb nido:test :only nido.tui` → PASS. Smoke: `s` from the board shows the sessions list + coordinator status line; `esc` returns.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(tui): system surface scaffold (daemon health + sessions), board [s] entry

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 3.2: Relocate session plumbing (`u`/`d`/`x`/`w`/`i`) onto the system surface

**Files:** Modify `src/nido/tui.clj`; Test `test/nido/tui_test.clj`

Move the old `update-sessions` ops keymap (enter/worktree/info/up/down/destroy) into `update-system`. The async action machinery (`start-session-up/down/destroy`, the spinner, `confirm-destroy`, `session-info` modals) is unchanged — only its entry point moves from the board's deleted ops view to the system surface. NOTE: `a` (add) does NOT come here — creating a workstream is `n` on the board (`work/new!`).

- [ ] **Step 1: Write the failing test** (append):

```clojure
(deftest system-down-runs-the-async-action
  (with-redefs [nido.tui/selected-data (fn [_] {:name "sess"})
                nido.tui/start-session-down (fn [s _ sn] (assoc s ::down sn))]
    (let [st (assoc (board-state :all) :screen :system)
          [s' _] (#'tui/update-system st (msg/key-press "d"))]
      (is (= "sess" (::down s')) "d on the system surface stops the highlighted session"))))

(deftest system-x-opens-confirm-destroy
  (with-redefs [nido.tui/selected-data (fn [_] {:name "sess"})]
    (let [st (assoc (board-state :all) :screen :system)
          [s' _] (#'tui/update-system st (msg/key-press "x"))]
      (is (= :confirm-destroy (:modal s'))))))
```

- [ ] **Step 2: Run** → FAIL.

- [ ] **Step 3: Implement.** Extend `update-system` with the session-plumbing keys (lift them verbatim from the old `update-sessions`, which is being deleted):

```clojure
(defn- update-system [state msg]
  (cond
    (msg/key-match? msg "escape") [(set-origin state (:origin state)) nil]
    (or (msg/key-match? msg "enter") (msg/key-match? msg "e"))
    (with-selected-session state (fn [s p sn] (enter-session s p sn :home)))
    (msg/key-match? msg "w") (with-selected-session state (fn [s p sn] (enter-session s p sn :worktree)))
    (msg/key-match? msg "u") (with-selected-session state start-session-up)
    (msg/key-match? msg "d") (with-selected-session state start-session-down)
    (msg/key-match? msg "x") (with-selected-session state (fn [s p sn] (open-confirm-destroy s p sn)))
    (msg/key-match? msg "i") (with-selected-session state (fn [s p sn] (open-session-info s p sn)))
    ;; system levers (fire/halt/clear-breaker) added in Task 3.3
    :else (let [[lst cmd] (item-list/list-update (:list state) msg)]
            [(assoc state :list lst) cmd])))
```

Then DELETE `update-sessions` (now fully relocated). The `confirm-destroy`/`session-info`/`action-error` modals and `finish-action`'s `refresh-list (current-rows …)` already work for any screen, so they need no change.

- [ ] **Step 4: Run** `bb nido:test :only nido.tui` → PASS. Smoke: on the system surface, `u`/`d` start the spinner action, `x` confirms destroy, `i` shows session info, `↵` enters a session, `w` lands in the worktree.

- [ ] **Step 5: Commit**

```bash
jj commit -m "refactor(tui): relocate session plumbing (u/d/x/w/i) to the system surface

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 3.3: Relocate coordinator levers (`f` fire, `h` halt, `c` clear-breaker) onto the system surface

**Files:** Modify `src/nido/tui.clj`; Test `test/nido/tui_test.clj`

Move the fire-trigger / halt / clear-breaker entry points off the board onto `update-system`. The modal handlers (`update-fire-*`, `update-halt-*`, `update-clear-breaker`) and their `update-fn` modal cases are unchanged — only the keys that OPEN them move.

- [ ] **Step 1: Write the failing test** (append):

```clojure
(deftest system-f-opens-fire-trigger
  ;; Stub the project list (1 project skips the project picker) and triggers so
  ;; fire opens the trigger picker in its no-triggers error state — hermetic.
  (with-redefs [nido.project/list-projects (constantly {"brian" {}})
                nido.coordinator.triggers/load-for-project (constantly [])]
    (let [st (assoc (board-state :all) :screen :system)
          [s' _] (#'tui/update-system st (msg/key-press "f"))]
      (is (= :fire-pick-trigger (:modal s'))))))

(deftest board-no-longer-handles-system-levers
  ;; f/h/c on the board must NOT open coordinator modals anymore
  (doseq [k ["f" "h" "c"]]
    (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press k))]
      (is (nil? (:modal s')) (str "board key " k " no longer opens a coordinator modal")))))
```

- [ ] **Step 2: Run** → FAIL (board still or not-yet-wired; system `f` not wired).

- [ ] **Step 3: Implement.** Add to `update-system` (before the `:else`):

```clojure
    (msg/key-match? msg "f") (open-fire-trigger state)
    (msg/key-match? msg "h") (open-halt-confirm state)
    (msg/key-match? msg "c") (open-clear-breaker-picker state)
```

Confirm `update-board` (Task 1.3) has NO `f`/`h`/`c` cases (it shouldn't). Update the `:system` footer to advertise everything:

```clojure
;; footer :system branch:
"[↵/e] enter  [w]orktree  [i]nfo  [u]p  [d]own  [x] destroy  •  [f]ire  [h]alt  [c]lear breaker  [esc] back  [q]uit"
```

- [ ] **Step 4: Run** `bb nido:test :only nido.tui` → PASS. Smoke: on the system surface, `f` walks the fire wizard, `h` toggles halt, `c` clears a breaker; on the board those keys do nothing.

- [ ] **Step 5: Commit**

```bash
jj commit -m "refactor(tui): relocate fire/halt/clear-breaker to the system surface

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Phase 4 — Cleanup, tests, docs

### Task 4.1: Delete dead code

**Files:** Modify `src/nido/tui.clj`

- [ ] **Step 1:** Grep for stragglers and remove them:

Run: `grep -nE "view-defs|view-for-id|active-view|tab-bar|workstream-list-rows|update-sessions|ws-detail-rows|set-view|update-workstreams|step-view|next-view|prev-view|grouped-by-engagement" src/nido/tui.clj`

Expected after Phases 1–3: only the *definitions* that are already deleted should be gone; any REMAINING references are bugs. Remove every now-unused private def: `active-view`, `set-view` (replaced by `set-origin`), `update-workstreams` (replaced by `update-board`), and any `wsv/grouped-by-engagement` usage. Confirm `nido.coordinator.workstreams-view` is still required only for the helpers still used (`format-row`, `label`, `promote-result-message`, `session-rows`); drop the require if nothing remains.

- [ ] **Step 2:** Run `bb nido:test :only nido.tui` AND load the namespace clean: `bb -e "(require 'nido.tui :reload)"` → no unresolved-symbol or unused-var errors. Smoke `bb nido:tui` end-to-end once more.

- [ ] **Step 3: Commit**

```bash
jj commit -m "refactor(tui): remove dead source-tab + ops-view code

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 4.2: Rewrite the TUI tests

**Files:** Modify `test/nido/tui_test.clj`

- [ ] **Step 1:** Ensure the stale tests are gone (deleted in Task 1.1) and the suite now covers: `origin-filter-cycles-*`, `origin-badge-*`, `board-rows-*`, the `update-board` verb transitions (open/promote/done/new/system/tab), `detail-rows-*`, `stage-picker-*`, `system-surface-*`, `system-down/x`, `system-f`, `board-no-longer-handles-system-levers`. Keep the still-valid action tests (`add-births-a-scratch-workstream`, `up-births-*`, `destroy-reaps-*`, `down-touches-no-workstream`, `live-session-names-*`) — they test `run-session-action!`/`live-session-names`, which are unchanged. Update `scratch-view-a-opens-create-session`, `a-does-not-create-sessions-on-ref-sourced-views`, `scratch-footer-*`, `ref-sourced-footer-*` → replace with the new board's `n`-opens-create-session and the new single footer (no per-source footers anymore).

- [ ] **Step 2:** Run `bb nido:test :only nido.tui` → all green, no references to deleted vars.

- [ ] **Step 3: Commit**

```bash
jj commit -m "test(tui): rewrite for the spine board, work verbs, and system surface

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

### Task 4.3: Reconcile CLAUDE.md

**Files:** Modify `CLAUDE.md`

- [ ] **Step 1:** Remove the stale line *"Or in the TUI press `r` for the runs surface."* (under the coordinator/triggers section). Update any TUI keymap prose: the board is now `[↵/o] open · [i]nspect · [n]ew · [p]romote · [P] promote to… · [d]one · [tab] origin · [s]ystem`; system levers (`h` halt, `c` clear-breaker, fire) and session plumbing live on the **system surface** (`s`), not the board. Keep the enter/worktree (`e`/`w`/`↵`) handoff description accurate (now reached via the system surface for raw sessions, and via `open` on the board for workstreams).

- [ ] **Step 2:** `grep -nE "press .r. for the runs|Tab/←→|source tab|Sessions/ops" CLAUDE.md` → no stale references remain.

- [ ] **Step 3: Commit**

```bash
jj commit -m "docs: reconcile CLAUDE.md TUI keymap with the spine board + system surface

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification

- [ ] `bb nido:test :only nido.tui` → 0 failures, 0 errors.
- [ ] `bb nido:test :only nido.work` → still 0 failures (Plan B must not touch the core).
- [ ] `bb nido:test` → no NEW failures vs. the Plan-A baseline (the 3 pre-existing `nido.tui_test` failures from commit `7ca0b01` are REPLACED by this plan's rewrite, so they should now be gone — the suite should be cleaner, not dirtier).
- [ ] `bb nido:tui` full manual pass: projects → board (badges + filter + open/inspect/new/promote/P/done) → detail (autonomy axis + ledger, open) → system (sessions plumbing + fire/halt/clear-breaker) → back. Quit clean.

---

## Self-review notes (for the implementer)

- **The work verbs are rewires, not reinventions.** Default `p`/`d`/`n` behave exactly as the old TUI because `work/set-stage!`/`work/new!` wrap the same coordinator calls (`promote-workstream!`, `close!`, `lifecycle/up!`+`scratch/birth!`). If behavior changes for the default path, something is wired wrong.
- **`new` only enters at `:in-progress`** (the Plan-A limitation). Do NOT add a stage picker to `n`; only `P` (promote) gets the override. Surfacing a `new`-at-stage chooser would hit the origin/stage coupling bug.
- **Origin stays a display concern.** The board badges/filters on `:origin` from `work/grouped`; it never re-derives origin itself.
- **Don't disturb the charm machinery.** The action channel (`queue-action!`/`exit-action`), `enter-session` (Warp tab vs cd-handoff), the spinner actions, `charm-patch`, and the live-refresh tick are load-bearing and unchanged. Phase 3 MOVES entry points; it does not rewrite these.
- **Phase ordering matters:** Tasks 1.3/3.1 add placeholders for `open-stage-picker`/`enter-system` that Tasks 2.2/3.1 replace — keep the `declare` so intermediate commits compile and tests pass.
- **System status-bar moved, not deleted:** `status-bar` now renders only on `:system`. The board no longer shows coordinator health (it's not workstream state).
