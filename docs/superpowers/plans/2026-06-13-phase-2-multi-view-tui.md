# Phase 2: Multi-View TUI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **VCS:** jj (Jujutsu) repo. Activate the `jujutsu` skill before any VCS op; never git. One logical commit per task via `jj commit -m`. The working copy currently carries the two Phase-1/2 planning docs as UNCOMMITTED changes — each implementer must `jj new` off the code tip so the docs stay out of code commits, exactly as in Phase 1. The controller positions the docs after each task.

**Goal:** Make the TUI's primary surface intent-organized: within a project, the user tabs between source-scoped peer **views** — Notion, Scratch, and the demoted flat Sessions (ops) list — instead of landing on the cluttered flat session list.

**Architecture:** Collapse today's two sibling screens (`:sessions`, `:workstreams`) into a single `:board` screen parameterized by an active `:view`. Views are a data-driven, ordered `view-defs` list; each view is either a *workstreams* view (filtered + grouped) or the *ops* view (flat substrate list). `Tab`/`Shift-Tab`/`←`/`→` cycle views (pure in-process state changes, no action-channel exits). The `:projects` picker and the `:workstream` drill-in detail are unchanged; `esc` from detail returns to `:board` preserving the active view. Source classification is derived from the **raw workstream record** (`:stage :scratch` ⇒ Scratch; else Notion), because `workstream-row`'s projected `:stage` is not reliable for scratch. GitHub becomes a fourth view in Phase 3 by inserting one `view-defs` entry.

**Tech Stack:** Babashka, Clojure, charm.clj (`charm.components.list`, `charm.message`, `charm.style`), `clojure.test`. The data layer (`nido.coordinator.workstreams-view`) is headless-unit-tested; the charm render/key paths are verified by running `bb nido:tui` (no charm test harness exists).

**Explicitly OUT of scope (YAGNI for this cut; revisit if the Scratch view's accuracy demands it):**
- **Substrate-sync on up/down** — a scratch workstream's human session stays `:substrate :live` until `destroy`, so the Scratch view shows live-birthed one-offs as engagement `:active` even when their services are down. Acceptable first cut. If/when honest liveness matters, sync the coordinator session's substrate on `session:down`/`up` (task-layer wiring like Phase 1's birth/reap) or join the lifecycle registry in the Scratch view.
- **Registry `:workstream-id` reverse-link** — not needed: the Scratch view is built from workstreams, the ops view from substrate; neither requires the cross-link.

---

## File Structure

- **Modify** `src/nido/coordinator/workstreams_view.clj` — add `ws-source` classification, `:source` on `workstream-row`, and `grouped-by-engagement` (Scratch grouping). Pure; this is the testable seam the TUI consumes.
- **Modify** `test/nido/coordinator/workstreams_view_test.clj` — cover the above.
- **Modify** `src/nido/tui.clj` — `view-defs` + `:view` state; collapse `:sessions`/`:workstreams` into `:board`; cycling; tab-bar render; per-view footer/title; row + update dispatch by view.
- **Create** `test/nido/tui_test.clj` — unit-test the extracted PURE helpers only (`next-view`/`prev-view` cycling, `tab-bar` string, `view-for-id`). No charm runtime.
- **Modify** `src/tasks/nido_tui.clj` — only if the action-channel shape changes (it should NOT; verify).

---

## Task 1: Source classification + Scratch grouping (data layer)

**Files:**
- Modify: `src/nido/coordinator/workstreams_view.clj`
- Test: `test/nido/coordinator/workstreams_view_test.clj`

- [ ] **Step 1: Write failing tests.** Append to `test/nido/coordinator/workstreams_view_test.clj` (read the file first to reuse its existing `with-tmp`/fixture + require aliases — it already tests this ns; match its style and the `wsv`/`workstream`/`session` aliases it uses):

```clojure
(deftest ws-source-classifies-from-the-raw-record
  (is (= :scratch (wsv/ws-source {:stage :scratch :external-refs []})))
  (is (= :notion  (wsv/ws-source {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-1"}]})))
  ;; default/coordinator bucket: ref-less, non-scratch ⇒ :notion (nothing is lost)
  (is (= :notion  (wsv/ws-source {:stage :triaging :external-refs []})))
  ;; future-proof: a github-issue ref classifies as :github
  (is (= :github  (wsv/ws-source {:stage :ready
                                  :external-refs [{:adapter :github-issue :id "42"}]}))))

(deftest workstream-row-carries-source
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "refshot" :weight :light :autonomy nil})
        (is (= :scratch (:source (wsv/workstream-row :brian (workstream/read-ws :brian (:id w))))))))))

(deftest grouped-by-engagement-splits-active-and-idle
  (let [rows [{:engagement :active  :label "a"}
              {:engagement :parked-at-gate :label "b"}
              {:engagement :idle    :label "c"}
              {:engagement :queued  :label "d"}]
        g    (wsv/grouped-by-engagement rows)]
    ;; active band = anything live/working/parked; idle band = idle/settled
    (is (= #{"a" "b" "d"} (set (map :label (:active g)))))
    (is (= #{"c"} (set (map :label (:idle g)))))))
```

- [ ] **Step 2: Run, verify FAIL.** `bb nido:test :only nido.coordinator.workstreams-view-test` — fails (`ws-source`/`grouped-by-engagement` undefined, `:source` missing).

- [ ] **Step 3: Implement.** In `src/nido/coordinator/workstreams_view.clj`:

(a) Add the classifier near `notion-ref` (top of file):

```clojure
(defn ws-source
  "Source bucket of a workstream, classified from its RAW record (not a projected
   row — `workstream-row`'s :stage is unreliable for scratch). :scratch when the
   stored stage is :scratch (a one-off, set by scratch/birth!); :github when it
   carries a :github-issue ref (Phase 3); else :notion — the default/coordinator
   bucket, so a ref-less coordinator workstream is never dropped from every view."
  [ws]
  (cond
    (= :scratch (:stage ws))                                    :scratch
    (some #(= :github-issue (:adapter %)) (:external-refs ws))   :github
    :else                                                        :notion))
```

(b) In `workstream-row` (currently returns the row map), add `:source (ws-source ws)` to the returned map — `ws` (the raw record) is already its argument.

(c) Add the Scratch grouping after `grouped-by-stage`:

```clojure
(def ^:private live-engagements
  "Engagement states where a session is present/working/awaiting you — the
   'active' band of the Scratch view. :idle and :settled fall to the idle band."
  #{:active :parked-at-gate :queued})

(defn grouped-by-engagement
  "Group scratch rows by liveness for the Scratch view (which has no lifecycle
   stage). {:active [...] :idle [...]}, each newest-activity first. needs-you
   rows (parked) sort to the top of the active band."
  [rows]
  (let [newest (fn [rs] (sort-by :last-activity #(compare %2 %1) rs))
        live?  #(contains? live-engagements (:engagement %))]
    {:active (newest (filter live? rows))
     :idle   (newest (remove live? rows))}))
```

- [ ] **Step 4: Run, verify PASS.** `bb nido:test :only nido.coordinator.workstreams-view-test` — all green. Then full `bb nido:test`.

- [ ] **Step 5: Commit.**
```bash
jj commit -m "feat(workstreams-view): source classification + scratch engagement grouping

Classify a workstream's view bucket from its raw record (:stage :scratch ⇒
scratch; else notion); add :source to workstream-row; group scratch rows by
engagement (active/idle) since scratch has no lifecycle stage."
```

---

## Task 2: TUI view model — `view-defs`, `:view` state, pure cycling

**Files:**
- Modify: `src/nido/tui.clj`
- Test: `test/nido/tui_test.clj` (create)

- [ ] **Step 1: Write failing tests** for the pure helpers. Create `test/nido/tui_test.clj`:

```clojure
(ns nido.tui-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.tui :as tui]))

(deftest view-order-is-notion-scratch-sessions
  (is (= [:notion :scratch :sessions] (mapv :id @#'tui/view-defs))))

(deftest cycling-wraps-both-directions
  (is (= :scratch  (#'tui/next-view :notion)))
  (is (= :sessions (#'tui/next-view :scratch)))
  (is (= :notion   (#'tui/next-view :sessions)) "wraps forward")
  (is (= :sessions  (#'tui/prev-view :notion)) "wraps back")
  (is (= :notion   (#'tui/prev-view :scratch))))

(deftest view-for-id-resolves
  (is (= :scratch (:id (#'tui/view-for-id :scratch))))
  (is (= :ops     (:kind (#'tui/view-for-id :sessions))))
  (is (= :workstreams (:kind (#'tui/view-for-id :notion)))))
```

- [ ] **Step 2: Run, verify FAIL.** `bb nido:test :only nido.tui-test`.

- [ ] **Step 3: Implement the view model** in `src/nido/tui.clj` (near the top, after the requires / before the row builders). `wsv` is already required as `nido.coordinator.workstreams-view`.

```clojure
;; ---------------------------------------------------------------------------
;; Views: the source-scoped tabs the user cycles within a project. Ordered.
;; :kind :workstreams → filtered workstream list; :kind :ops → flat substrate.
;; GitHub becomes a 4th entry in Phase 3 — just insert {:id :github ...}.
;; ---------------------------------------------------------------------------

(def ^:private view-defs
  [{:id :notion   :label "Notion"   :kind :workstreams :source :notion}
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
```

- [ ] **Step 4: Run, verify PASS.** `bb nido:test :only nido.tui-test`.

- [ ] **Step 5: Commit.**
```bash
jj commit -m "feat(tui): view-defs + pure view cycling helpers

Ordered source-scoped views (Notion/Scratch/Sessions) with next/prev cycling.
No behaviour wired yet — pure model + tests."
```

---

## Task 3: Collapse `:sessions`/`:workstreams` into a `:board` screen, dispatch by view

**Files:**
- Modify: `src/nido/tui.clj`

This is a charm-runtime refactor — verified by running `bb nido:tui`, not unit tests. Make the smallest edits that route by `:view`.

- [ ] **Step 1: Add `:view` to state on project entry.** In `enter-sessions` (tui.clj:246) — the drill-from-projects fn — set the screen to `:board` and seed the view:
  - Change its returned state to `(assoc … :screen :board :view :notion)` (drop the old `:screen :sessions`).
  - Do the same anywhere `set-screen` defaulted to `:sessions`/`:workstreams`.

- [ ] **Step 2: Rows dispatch.** In `current-rows` (tui.clj:227), replace the `:sessions`/`:workstreams` cases with a single `:board` case that dispatches on the active view:

```clojure
(case (:screen state)
  :board       (let [v (view-for-id (:view state))]
                 (if (= :ops (:kind v))
                   (session-rows (:project state))
                   (workstream-list-rows (:project state) (:source v))))
  :workstream  (ws-detail-rows (:project state) (:ws-id state))
  :projects    (project-rows))
```

- [ ] **Step 3: Parameterize `workstream-list-rows` by source.** It currently takes `[project]` and calls `wsv/grouped-by-stage (wsv/workstream-rows project)`. Change the signature to `[project source]`, filter rows to that source, and group per kind:

```clojure
(defn- workstream-list-rows [project source]
  (let [rows (filterv #(= source (:source %)) (wsv/workstream-rows project))
        g    (if (= :scratch source)
               (wsv/grouped-by-engagement rows)
               (wsv/grouped-by-stage rows))]
    ;; build the same header+row items as today, but iterate g's groups.
    ;; For :notion use the existing stage groups (:ready/:in-progress/:triage);
    ;; for :scratch use the engagement groups (:active/:idle).
    …))
```
Keep the existing header/row rendering helpers (`group-header`, the per-row formatting via `wsv/format-row`); only the set of groups iterated changes. For the Scratch view, render two groups: `Active (N)` from `:active` and `Idle (N)` from `:idle`. Preserve the `::group-header` / `::empty` sentinels so `selected-workstream` still guards correctly.

- [ ] **Step 4: Update dispatch.** In `update-fn` (tui.clj:864) replace the separate `:sessions`/`:workstreams` dispatch with a `:board` dispatch that routes by the active view's kind:

```clojure
:board (let [v (view-for-id (:view state))]
         (if (= :ops (:kind v))
           (update-ops state msg)          ; was update-sessions
           (update-board-workstreams state msg)))  ; was update-workstreams
```
Rename `update-sessions` → `update-ops` and `update-workstreams` → `update-board-workstreams` (keep their bodies; only the drill-in target screen name `:workstream` stays the same, and `esc` now returns to `:board`/`:projects` as before — verify `set-screen`/`enter-projects` targets).

- [ ] **Step 5: View cycling keys.** Replace the old `r`/`s` flip block (tui.clj:938-947) with Tab/Shift-Tab/←/→ cycling on `:board` (guarded by no-modal + project context):

```clojure
(and (nil? (:modal state)) (= :board (:screen state))
     (or (msg/key-match? msg "tab") (msg/key-match? msg "right")))
[(assoc state :view (next-view (:view state))) (refresh-list-cmd)]

(and (nil? (:modal state)) (= :board (:screen state))
     (or (msg/key-match? msg "shift+tab") (msg/key-match? msg "left")))
[(assoc state :view (prev-view (:view state))) (refresh-list-cmd)]
```
(Use whatever the existing pattern is for rebuilding the list after a state change — mirror how `set-screen` rebuilt rows; see `rebuild-list`/`refresh-list` at tui.clj:212/217. The cycle must rebuild rows AND reset the cursor, like a screen switch did.)

- [ ] **Step 6: Verify by running.** `bb nido:tui`, enter the brian project. Confirm: lands on the Notion view; `Tab` cycles Notion → Scratch → Sessions → Notion; `Shift-Tab`/`←`/`→` work; the Scratch view shows the `feat/*`,`fix/*`,`refshot`,`text2speech-latex` one-offs grouped Active/Idle; the Sessions view shows the old flat list; drill-in (`↵`) on a workstream still opens its detail and `esc` returns to the same view. (Charm-runtime; no unit test.)

- [ ] **Step 7: Commit.**
```bash
jj commit -m "feat(tui): collapse sessions/workstreams into a view-cycled board

One :board screen renders the active source-scoped view; Tab/Shift-Tab/←/→
cycle Notion/Scratch/Sessions. Scratch lists one-offs grouped by engagement;
Sessions is the demoted flat ops list. Drill-in/esc preserve the active view."
```

---

## Task 4: Tab-bar render + per-view title & footer

**Files:**
- Modify: `src/nido/tui.clj`
- Test: `test/nido/tui_test.clj`

- [ ] **Step 1: Write a failing test** for the pure tab-bar string in `test/nido/tui_test.clj`:

```clojure
(deftest tab-bar-marks-the-active-view
  (let [s (#'tui/tab-bar :scratch)]
    (is (re-find #"Notion" s))
    (is (re-find #"Scratch" s))
    (is (re-find #"Sessions" s))
    ;; active view is visually distinguished — assert a marker the impl uses
    (is (re-find #"\[ ?Scratch ?\]|●\s*Scratch" s) "active view marked")))
```
(Pick ONE active-marker convention in the implementation and match it here.)

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement `tab-bar`** (pure string builder) and render it. Add near `header` (tui.clj:1000):

```clojure
(defn- tab-bar
  "One-line view switcher: each view label, the active one bracketed/highlighted."
  [active-id]
  (->> view-defs
       (map (fn [{:keys [id label]}]
              (if (= id active-id) (str "[" label "]") (str " " label " "))))
       (str/join "  ")))
```
Render it: in `view` (tui.clj:1172), on the `:board` screen, draw `(tab-bar (:view state))` directly under the header (above the list). (Today the status-bar shows only on `:workstreams`; keep showing the coordinator status-bar only when the active view kind is `:workstreams`, i.e. not on the Sessions/ops view.)

- [ ] **Step 4: Per-view title & footer.** In `header` (tui.clj:1022) and `footer` (tui.clj:1041), replace the `:sessions`/`:workstreams` cases with a `:board` case keyed on the active view kind:
  - Title: `"nido — <project> · <view-label>"`.
  - Footer (workstreams kind): `"[↵] open  [p]romote  [d]one  [f]ire  [h]alt  [c]lear breaker  [⇄ tab] view  [esc] back  [q]uit"`.
  - Footer (ops kind): `"[↵/e] enter  [w]orktree  [i]nfo  [a]dd  [u]p  [d]own  [x] destroy  [⇄ tab] view  [esc] back  [q]uit"`.
  - `:workstream` detail footer: keep, but change `[s]essions` to `[esc] back` only (no cross-tab key from detail).

- [ ] **Step 5: Run tests + visual check.** `bb nido:test :only nido.tui-test` green; `bb nido:tui` shows the tab bar with the active view highlighted, footer changes per view, status-bar only on workstreams views.

- [ ] **Step 6: Commit.**
```bash
jj commit -m "feat(tui): tab bar + per-view title/footer for the board

Visible view switcher with the active tab highlighted; title and footer adapt
to the active view kind; coordinator status-bar shows only on workstreams views."
```

---

## Task 5: Cleanup & full verification

**Files:**
- Modify: `src/nido/tui.clj`, `src/tasks/nido_tui.clj` (verify only)

- [ ] **Step 1: Remove dead code.** Delete any now-unused `:sessions`/`:workstreams` screen remnants (old `set-screen` arms that referenced them, the old `r`/`s` help text, dead `case` branches). Grep `:screen :sessions` / `:screen :workstreams` and confirm none remain except intended.
- [ ] **Step 2: Verify the action channel is unchanged.** Confirm `src/tasks/nido_tui.clj` still only handles `:quit` and `[:enter …]` — view cycling must NOT have introduced any new action-channel exit (it's pure in-process state). No change expected here; just confirm.
- [ ] **Step 3: Full suite.** `bb nido:test` — all green (report totals).
- [ ] **Step 4: End-to-end manual run.** `bb nido:tui`: projects → brian → board. Verify the whole loop: cycle all three views; Scratch shows one-offs (Active/Idle); Notion shows triage/ready/in-progress; Sessions shows substrate with ports; drill into a Notion workstream and back (view preserved); fire/promote/halt still work on workstreams views; up/down/enter still work on the Sessions view; `esc` from board → projects.
- [ ] **Step 5: Commit.**
```bash
jj commit -m "chore(tui): drop dead sessions/workstreams screen code post-board"
```

---

## Self-Review

**Spec coverage (Phase 2 slice of the design doc):**
- "Multi-view TUI; tab navigation; Notion + Scratch + Sessions(ops) views off the adapter-predicate filter" → Tasks 2–4. ✓
- "Notion view = today's workstreams surface" → Task 3 reuses `grouped-by-stage` for the `:notion` source. ✓
- "Scratch = loose workstreams, grouped by engagement" → Tasks 1 + 3 (`grouped-by-engagement`, source from `:stage :scratch`). ✓
- "Sessions demoted to an ops view you act on but don't organize by" → Tasks 3–4 (the `:ops` view, last tab). ✓
- "Adding a source later = one predicate + a tab" → `view-defs` is a data list; Phase 3 inserts one entry. ✓

**Deferred (documented above, not gaps):** substrate-sync / honest Scratch liveness; registry `:workstream-id` reverse-link.

**Type/name consistency:** `wsv/ws-source` returns `:notion|:scratch|:github`; `view-defs` `:source` values match; `workstream-list-rows` takes `[project source]`; `grouped-by-engagement` returns `{:active [] :idle []}`. `view-for-id`/`next-view`/`prev-view`/`step-view`/`tab-bar` are consistent across Tasks 2 and 4.

**Charm-runtime honesty:** Tasks 3 and 5 have no unit tests (no charm harness); they use `bb nido:tui` manual verification with explicit checks. All pure logic (source classification, grouping, view cycling, tab-bar string) IS unit-tested in Tasks 1, 2, 4.

---

## Execution Handoff

Subagent-driven, as in Phase 1. Note the charm-runtime tasks (3, 5) need the controller (or user) to run `bb nido:tui` for verification — an implementer subagent cannot drive an interactive TUI, so those tasks' "verify by running" steps are controller/human checkpoints, not subagent-automated. After Phase 2 lands, Phase 3 (GitHub view) and Phase 4 (workstream-level promote) each get their own plan.
