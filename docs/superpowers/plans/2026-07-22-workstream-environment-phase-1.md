# Workstream Environment (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the workstream's "bag of sessions" into a single **environment** panel — one Start/Stop/Restart + Open-in-browser + Enter, bound to the workstream's latest heavy (impl) session — on both the TUI and the web dashboard.

**Architecture:** A new resolver `work/environment` picks the workstream's one current session (latest live `:heavy` session, by `:created-at`, `nil` when none). Both surfaces render a single environment block off it instead of a session list/table. The environment's lifecycle reuses the existing `dev/dev-action!` (its `"start"` already does a full `lifecycle/up!` + app probe; `"stop"`/`"restart"` map to down/restart) — no new lifecycle plumbing. This is Phase 1 of the decomplection spec; Phase 2 (ledger provenance) is a separate plan.

**Tech Stack:** Babashka/Clojure, charm.clj (TUI), hiccup2 + Datastar (web), Malli, `bb nido:test` runner, jj VCS.

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-07-22-workstream-environment-decomplection-design.md`.
- **Single environment only.** Multiple live environments per workstream is an explicit non-goal — resolve exactly one or none.
- **Do NOT rename nido's underlying `session`.** "Environment" is a UI-level name for "the workstream's one current session"; the engine/registry/lifecycle keep the name `session`.
- **VCS is jj, not git.** Commit with `jj commit <paths> -m "…"` (paths keep the planning docs out of the commit). NEVER bare `git` (a shell guard blocks it in worktrees; the root is colocated but stay on jj).
- **Never commit planning artifacts.** `docs/superpowers/specs/**` and `docs/superpowers/plans/**` stay uncommitted — pass explicit source/test paths to `jj commit`.
- **Test runner:** `bb nido:test :only <ns-prefix>` for one namespace; `bb nido:test` for the full suite. Load-check any edited namespace with `bb -e "(require 'the.ns :reload)"` before committing (a nido rule — even comment-only edits).

## Starting state (read before Task 1)

The working copy carries **superseded TUI edits from this session** (a session-list trim + an inline session-info panel in `src/nido/tui.clj` and `test/nido/tui_test.clj`). Phase 1 rewrites that same code — do **not** try to preserve those edits; the tasks below replace them. The uncommitted spec/plan docs also sit in the working copy; leave them uncommitted.

## File Structure

- `src/nido/work.clj` — add `work/environment` resolver; add `:environment` to the `work/workstream` map. (~30 lines added.)
- `src/nido/tui.clj` — the `:workstream` screen becomes an environment block (no list); `update-workstream` keys act on the resolved environment; remove `detail-rows`/list plumbing for `:workstream`.
- `src/nido/ui/views.clj` — `workstream-pane` collapses the Sessions table to one environment block.
- Tests: `test/nido/work_test.clj`, `test/nido/tui_test.clj`, `test/nido/ui/views_test.clj`.

---

## Task 1: `work/environment` resolver + `:environment` on the workstream map

**Files:**
- Modify: `src/nido/work.clj` (add `environment`; add `:environment` key to `workstream`'s returned map, ~line 331)
- Test: `test/nido/work_test.clj`

**Interfaces:**
- Consumes: `nido.coordinator.session/list-sessions` (aliased `csession` in work.clj) → vector of Session records `{:name :weight (:light|:heavy) :substrate (:live|:archived) :created-at :autonomy …}`.
- Produces: `(work/environment project ws-id) => session-record | nil`. `work/workstream` gains `:environment <session-record|nil>`.

- [ ] **Step 1: Write the failing tests**

Add to `test/nido/work_test.clj`:

```clojure
(deftest environment-resolves-latest-live-heavy-session
  (with-redefs [nido.coordinator.session/list-sessions
                (fn [_ _]
                  [{:name "triage-BR-1" :weight :light :substrate :live :created-at "2026-07-20T10:00:00Z"}
                   {:name "impl-BR-1"   :weight :heavy :substrate :live :created-at "2026-07-21T10:00:00Z"}
                   {:name "impl-BR-1b"  :weight :heavy :substrate :live :created-at "2026-07-22T10:00:00Z"}])]
    (is (= "impl-BR-1b" (:name (work/environment :brian "ws-1")))
        "latest heavy session by :created-at")))

(deftest environment-nil-when-no-heavy-session
  (with-redefs [nido.coordinator.session/list-sessions
                (fn [_ _] [{:name "triage-BR-1" :weight :light :substrate :live :created-at "2026-07-20T10:00:00Z"}])]
    (is (nil? (work/environment :brian "ws-1"))
        "triage-only (light) workstream has no environment")))

(deftest environment-excludes-archived-heavy
  (with-redefs [nido.coordinator.session/list-sessions
                (fn [_ _]
                  [{:name "impl-old" :weight :heavy :substrate :archived :created-at "2026-07-22T10:00:00Z"}
                   {:name "impl-new" :weight :heavy :substrate :live     :created-at "2026-07-21T10:00:00Z"}])]
    (is (= "impl-new" (:name (work/environment :brian "ws-1")))
        "an archived heavy session is excluded even if newer")))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bb nido:test :only nido.work`
Expected: FAIL — `Unable to resolve symbol: environment` (or similar).

- [ ] **Step 3: Implement `work/environment`**

In `src/nido/work.clj`, add above the `workstream` defn:

```clojure
(defn environment
  "The workstream's single current environment: its latest live HEAVY (impl)
   session record, or nil when none exists yet (still triage, or a light-only
   scratch). Resolved by weight + recency, NOT by liveness — a down-but-provisioned
   impl session is still the environment (you Start it). :archived (torn-down)
   sessions are excluded. Callers use (:name env) to resolve dev-state/facts."
  [project ws-id]
  (->> (csession/list-sessions project ws-id)
       (filter #(= :heavy (:weight %)))
       (remove #(= :archived (:substrate %)))
       (sort-by :created-at)
       last))
```

- [ ] **Step 4: Add `:environment` to the `workstream` map**

In `work/workstream`'s returned map (the `{:ws-id … :sessions …}` literal, ~line 331), add one key alongside `:sessions`:

```clojure
        :environment  (environment project ws-id)
        :sessions     (mapv session-facet sessions)}))))
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `bb nido:test :only nido.work`
Expected: PASS (all three new tests).

- [ ] **Step 6: Load-check and commit**

```bash
bb -e "(require 'nido.work :reload)"
jj commit src/nido/work.clj test/nido/work_test.clj \
  -m "feat(work): environment resolver — the workstream's one current session"
```

---

## Task 2: TUI environment-block renderer

**Files:**
- Modify: `src/nido/tui.clj` (replace `workstream-detail-info` with `environment-block`; keep `session-facts`, `session-info-body`, `session-link-entries`, `render-link-rows`, `info-row`)
- Test: `test/nido/tui_test.clj`

**Interfaces:**
- Consumes: `work/environment`, `dev/session-dev-state` (→ `{:state :url}`), existing `session-facts`/`session-info-body`.
- Produces: `(#'tui/environment-block project ws-id) => string` — a status line + the session's machine-facts block, or the empty-state string.

- [ ] **Step 1: Write the failing tests**

Replace the current `detail-view-shows-selected-session-info-inline` and `detail-view-info-nil-for-the-empty-placeholder` tests in `test/nido/tui_test.clj` with:

```clojure
(deftest environment-block-renders-resolved-session-facts
  (with-redefs [nido.work/environment (fn [_ _] {:name "impl-br-1" :weight :heavy})
                nido.session.dev/session-dev-state (fn [_ _] {:state :running :url "http://localhost:3100"})
                nido.session.lifecycle/list-all-data
                (fn [_] {:sessions [{:name "impl-br-1" :app-port 3100 :pg-port 5500
                                     :nrepl-port 6100 :worktree "/wt/impl-br-1"}]})
                nido.session.state/session-home-dir (fn [_ _] "/home/brian/impl-br-1")
                nido.tui/session-link-entries (fn [_] [])]
    (let [block (#'tui/environment-block "brian" "w")]
      (is (str/includes? block "running") "live dev-state status shown")
      (is (str/includes? block "http://localhost:3100") "dev URL shown")
      (is (str/includes? block "5500") "pg port shown"))))

(deftest environment-block-empty-state-when-no-env
  (with-redefs [nido.work/environment (fn [_ _] nil)]
    (is (str/includes? (#'tui/environment-block "brian" "w") "no runnable version")
        "empty state when the workstream has no heavy session")))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bb nido:test :only nido.tui`
Expected: FAIL — `environment-block` unresolved.

- [ ] **Step 3: Replace `workstream-detail-info` with `environment-block`**

In `src/nido/tui.clj`, delete `workstream-detail-info` and add (near `session-info-body`):

```clojure
(defn- environment-block
  "The workstream's environment panel body: a live status line (from the probed
   dev-state) plus the resolved session's machine facts (dev URL, ports, home,
   worktree, links). The empty-state string when the workstream has no runnable
   version yet. Reads dev-state + lifecycle facts; the view calls it each render."
  [project ws-id]
  (if-let [sname (:name (work/environment project ws-id))]
    (let [st    (dev/session-dev-state project sname)
          glyph (case (:state st)
                  :running                          "●"
                  (:starting :stopping :restarting) "◐"
                  "○")
          head  (info-row "status" (str glyph "  " (clojure.core/name (or (:state st) :down))))]
      (str head "\n" (session-info-body project sname (session-facts project sname))))
    (style/render subtle-style "no runnable version yet")))
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `bb nido:test :only nido.tui`
Expected: the two new tests PASS. (Other `:workstream` tests may fail until Task 3 — that's expected; note which and proceed.)

- [ ] **Step 5: Load-check and commit**

```bash
bb -e "(require 'nido.tui :reload)"
jj commit src/nido/tui.clj test/nido/tui_test.clj \
  -m "feat(tui): environment-block renderer bound to work/environment"
```

---

## Task 3: TUI `:workstream` screen → environment panel (view + keys + footer)

**Files:**
- Modify: `src/nido/tui.clj` (`view` `:else` branch for `:workstream`; `update-workstream`; footer; `enter-workstream`; `current-rows`)
- Test: `test/nido/tui_test.clj`

**Interfaces:**
- Consumes: `environment-block`, `work/environment`, existing `dev-start!`/`dev-stop!`/`dev-restart!` (wrap `dev/dev-action!`), `enter-session`, `open-confirm-destroy`.
- Produces: the `:workstream` screen renders only the environment block; `update-workstream` keys act on the resolved environment (no list selection).

Key map for the environment panel: `↵` enter chat · `o` open browser · `w` worktree · `u` start · `d` stop · `r` restart · `X` destroy · `esc` back · `q` quit. (`u`/`d` keep their up/down meaning; Start=`dev-action "start"` which does a full session up, Stop=`"stop"`.)

- [ ] **Step 1: Write the failing tests**

Replace the `:workstream` key tests (`detail-u-starts-the-selected-session`, `detail-w-on-a-non-session-row-sets-no-session-status`, `workstream-detail-transitions`, `detail-footer-lists-the-plumbing-verbs`) with:

```clojure
(deftest workstream-esc-returns-to-board
  (with-redefs [nido.tui/current-rows (constantly [])]
    (let [st (assoc (board-state :all) :screen :workstream :ws-id "w1" :ws-label "x")
          [back _] (#'tui/update-workstream st (msg/key-press "escape"))]
      (is (= :board (:screen back)) "esc returns to the board"))))

(deftest workstream-enter-opens-the-environment-chat
  (with-redefs [nido.work/environment (fn [_ _] {:name "impl-br-1"})
                nido.tui/enter-session (fn [s _ sn _] [(assoc s ::opened sn) nil])]
    (let [st (assoc (board-state :all) :screen :workstream :project "brian" :ws-id "w1")
          [s' _] (#'tui/update-workstream st (msg/key-press "enter"))]
      (is (= "impl-br-1" (::opened s')) "enter opens the resolved environment's chat"))))

(deftest workstream-u-starts-the-environment
  (let [calls (atom [])]
    (with-redefs [nido.work/environment (fn [_ _] {:name "impl-br-1"})
                  nido.session.dev/dev-action! (fn [p w s a] (swap! calls conj [p w s a]) (future nil))]
      (let [st (assoc (board-state :all) :screen :workstream :project "brian" :ws-id "w1")
            [_ _] (#'tui/update-workstream st (msg/key-press "u"))]
        (is (= [["brian" "w1" "impl-br-1" "start"]] @calls) "u starts the environment via dev-action")))))

(deftest workstream-key-no-env-sets-status
  (with-redefs [nido.work/environment (fn [_ _] nil)]
    (let [st (assoc (board-state :all) :screen :workstream :project "brian" :ws-id "w1")
          [s' cmd] (#'tui/update-workstream st (msg/key-press "u"))]
      (is (str/includes? (:status s') "no runnable version"))
      (is (nil? cmd)))))

(deftest workstream-footer-lists-environment-verbs
  (let [f (#'tui/footer {:screen :workstream})]
    (doseq [verb ["[u] start" "[d] stop" "[r] restart" "[o] browser" "[w]orktree"]]
      (is (str/includes? f verb)))))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bb nido:test :only nido.tui`
Expected: FAIL (new behavior not yet implemented).

- [ ] **Step 3: Add the environment resolver + open-browser helpers**

In `src/nido/tui.clj`, add near the other `with-*` helpers:

```clojure
(defn- with-environment
  "Resolve the workstream's environment session and hand its name to `f`
   (fn [state project ws-id session-name]). Sets a status hint when there's no
   runnable version yet."
  [state f]
  (if-let [sname (:name (work/environment (:project state) (:ws-id state)))]
    (f state (:project state) (:ws-id state) sname)
    [(assoc state :status "(no runnable version yet)") nil]))

(defn- open-browser!
  "Open `url` in the default browser (macOS `open`), fire-and-forget. No-op on blank."
  [url]
  (when (seq url)
    (try (babashka.process/process ["open" url]) (catch Exception _ nil))))
```

Ensure `[babashka.process :as process]` (or `babashka.process`) is required — check the ns `:require`; if absent add `[babashka.process :as bprocess]` and use `bprocess/process`.

- [ ] **Step 4: Rewrite `update-workstream`**

Replace the whole `update-workstream` defn with:

```clojure
(defn- update-workstream
  "Workstream detail = the environment panel for one workstream. Keys act on the
   resolved environment (work/environment), not a list selection:
   ↵ enter chat · o open browser · w worktree · u start · d stop · r restart ·
   X destroy · esc back. Start/Stop/Restart route through dev/dev-action! (its
   `start` does a full session up + app probe). No env yet → a status hint."
  [state msg]
  (cond
    (msg/key-match? msg "escape") [(set-origin state (:origin state)) nil]
    (msg/key-match? msg "enter")
    (with-environment state (fn [s p _ sn] (enter-session s p sn :home)))
    (msg/key-match? msg "w")
    (with-environment state (fn [s p _ sn] (enter-session s p sn :worktree)))
    (msg/key-match? msg "o")
    (with-environment state
      (fn [s p _ sn]
        (open-browser! (:url (dev/session-dev-state p sn)))
        [(assoc s :status (str "opening " sn " in browser…")) nil]))
    (msg/key-match? msg "u")
    (with-environment state
      (fn [s _ ws-id sn] (dev-start! (:project s) ws-id sn)
        [(assoc s :status (str "starting " sn "…")) nil]))
    (msg/key-match? msg "d")
    (with-environment state
      (fn [s _ ws-id sn] (dev-stop! (:project s) ws-id sn)
        [(assoc s :status (str "stopping " sn "…")) nil]))
    (msg/key-match? msg "r")
    (with-environment state
      (fn [s _ ws-id sn] (dev-restart! (:project s) ws-id sn)
        [(assoc s :status (str "restarting " sn "…")) nil]))
    (msg/key-match? msg "X")
    (with-environment state (fn [s p _ sn] (open-confirm-destroy s p sn)))
    :else [state nil]))
```

- [ ] **Step 5: Render the environment block in the view (no list)**

In `view`'s `:else` branch, replace the `:workstream` handling so the screen renders the block instead of the list. Change:

```clojure
         "\n"
         (item-list/list-view (:list state)) "\n"
         (when (= :workstream (:screen state))
           (when-let [info (workstream-detail-info state)]
             (str "\n" info "\n")))
         "\n"
```

to:

```clojure
         "\n"
         (if (= :workstream (:screen state))
           (str (environment-block (:project state) (:ws-id state)) "\n\n")
           (str (item-list/list-view (:list state)) "\n\n"))
```

- [ ] **Step 6: Make `enter-workstream` / `current-rows` list-free for `:workstream`**

`enter-workstream` no longer needs rows — simplify:

```clojure
(defn- enter-workstream
  "Drill from the workstreams list into one workstream's environment panel."
  [state ws-id label]
  (-> state
      (assoc :screen :workstream :ws-id ws-id :ws-label label :status nil)
      (rebuild-list [])))
```

In `current-rows`, make `:workstream` return `[]`:

```clojure
    :workstream []
```

- [ ] **Step 7: Update the footer**

Replace the `:workstream` footer string:

```clojure
                    :workstream "[↵] chat  [o] browser  [w]orktree  [u] start  [d] stop  [r] restart  [X] destroy  [esc] back  [q]uit"))))
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `bb nido:test :only nido.tui`
Expected: PASS. If `main-list-height`'s `:workstream` special-case or `detail-rows` now cause failures, they're removed in Task 5 — confirm the failures are only "unused"/dead-code, not behavioral.

- [ ] **Step 9: Load-check and commit**

```bash
bb -e "(require 'nido.tui :reload)"
jj commit src/nido/tui.clj test/nido/tui_test.clj \
  -m "feat(tui)!: workstream detail is the environment panel (start/stop/open), no session list"
```

---

## Task 4: Web `workstream-pane` → single environment block

**Files:**
- Modify: `src/nido/ui/views.clj` (`workstream-pane`, ~line 758)
- Test: `test/nido/ui/views_test.clj`

**Interfaces:**
- Consumes: `:environment` on the ws map (Task 1), `session-dev-states` (name→`{:state :url}`), `machine-facts` (name→ports/RSS), existing `session-dev-cell`.
- Produces: the pane renders one environment block (or empty state) instead of the Sessions table.

- [ ] **Step 1: Write the failing tests**

Add to `test/nido/ui/views_test.clj`:

```clojure
(deftest pane-renders-single-environment-block
  (let [ws  {:project "brian" :ws-id "ws-1" :origin :notion :stage :in-progress
             :label "BR-1 · x" :environment {:name "impl-br-1" :weight :heavy}
             :sessions [{:name "impl-br-1" :autonomy-level :interactive}]}
        html (views/workstream-pane ws
                                    {"impl-br-1" {:state :running :url "http://localhost:3100"}}
                                    {"impl-br-1" {:app-port 3100 :pg-port 5500 :nrepl-port 6100}})]
    (is (str/includes? html "Environment") "an Environment section, not Sessions")
    (is (not (str/includes? html "<table")) "no session table")
    (is (str/includes? html "http://localhost:3100") "the environment URL is shown")))

(deftest pane-environment-empty-state
  (let [ws  {:project "brian" :ws-id "ws-1" :origin :notion :stage :triage
             :label "BR-1 · x" :environment nil :sessions []}
        html (views/workstream-pane ws {} {})]
    (is (str/includes? html "no runnable version") "empty state when no environment")))
```

Ensure the test ns requires `clojure.string :as str` (add if missing).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bb nido:test :only nido.ui.views`
Expected: FAIL — pane still renders "Sessions"/a table.

- [ ] **Step 3: Replace the Sessions table with the environment block**

In `workstream-pane`, add `environment` to the destructure:

```clojure
  ([{:keys [project ws-id origin stage label ledger report entries selected-seq environment on-latest?]
     :or {on-latest? true}} session-dev-states machine-facts]
```

Replace the `[:h2 "Sessions"] … (if (seq sessions) [:table …] [:p.empty "No sessions."])` block with:

```clojure
        [:h2 "Environment"]
        (if-let [env-name (:name environment)]
          (let [dev (get session-dev-states env-name)
                {:keys [pg-port nrepl-port app-port repl-rss pg-rss heap-max]}
                (get machine-facts env-name)]
            [:div.card.env
             [:div.env-head [:strong env-name] " " (session-dev-cell project ws-id env-name dev)]
             (when-let [url (:url dev)]
               [:div [:a {:href url :target "_blank"} url]])
             [:div.mono (str/join " · " (keep (fn [[l p]] (when p (str l " " p)))
                                              [["pg" pg-port] ["repl" nrepl-port] ["app" app-port]]))]
             [:div.meta (list (when repl-rss (str "jvm " (process/human-bytes repl-rss) " "))
                              (when pg-rss (str "pg " (process/human-bytes pg-rss) " "))
                              (when heap-max (str "max " heap-max)))]])
          [:p.empty "no runnable version yet"])])))))
```

(`process/human-bytes` is already used in this file and required.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `bb nido:test :only nido.ui.views`
Expected: PASS (both new tests).

- [ ] **Step 5: Load-check and commit**

```bash
bb -e "(require 'nido.ui.views :reload)"
jj commit src/nido/ui/views.clj test/nido/ui/views_test.clj \
  -m "feat(ui)!: workstream pane shows one environment block, not a session table"
```

---

## Task 5: Remove dead session-list code + full-suite green

**Files:**
- Modify: `src/nido/tui.clj` (remove now-unused `detail-rows`, `format-detail-session`, and the `main-list-height` `:workstream` special-case + the `rebuild-list`/`refresh-list` `:items`-before-sizing tweak if no longer needed)
- Test: `test/nido/tui_test.clj` (delete tests of removed fns)

**Interfaces:**
- Consumes: nothing new.
- Produces: no dead private vars; full suite green.

- [ ] **Step 1: Find the now-dead vars**

Run: `bb -e "(require 'nido.tui :reload)"` and read the clj-kondo diagnostics, plus:
Run: `grep -n "detail-rows\|format-detail-session" src/nido/tui.clj test/nido/tui_test.clj`
Expected: `detail-rows` and `format-detail-session` are referenced only by each other / removed tests — confirm no live caller (`current-rows` `:workstream` now returns `[]`).

- [ ] **Step 2: Remove them**

Delete the `detail-rows` and `format-detail-session` defns from `src/nido/tui.clj`. Revert the `main-list-height` `:workstream` special-case (the list is gone for `:workstream`, so size normally) back to:

```clojure
(defn- main-list-height [state]
  (let [term-height (or (some-> state :size second) 28)
        facet-line? (and (= :board (:screen state))
                         (not (str/blank?
                               (facet-strip (:project state)
                                            (:origin state)
                                            (or (:facet-filter state) {})))))
        chrome (+ 5
                  (if (= :board (:screen state)) 1 0)
                  (if facet-line? 1 0)
                  (if (:status state) 1 0))]
    (max 1 (quot (max 2 (- term-height chrome)) 2))))
```

Leave `rebuild-list`/`refresh-list`'s `:items`-before-sizing form as-is (harmless, and correct for the board). Delete any `test/nido/tui_test.clj` tests that referenced `detail-rows`/`format-detail-session`.

- [ ] **Step 3: Load-check**

Run: `bb -e "(require 'nido.tui :reload)"`
Expected: clean load, no unused-private-var diagnostics for `detail-rows`/`format-detail-session`.

- [ ] **Step 4: Run the full suite**

Run: `bb nido:test`
Expected: `0 failures, 0 errors.`

- [ ] **Step 5: Commit**

```bash
jj commit src/nido/tui.clj test/nido/tui_test.clj \
  -m "refactor(tui): drop the dead session-list rendering after the environment collapse"
```

---

## Self-Review

**Spec coverage** (against `…-decomplection-design.md`, Component 1 / Phase 1):
- Resolver `work/environment` (single, stage/weight-resolved, nil when none) → Task 1. ✅
- Environment panel with status/URL/ports + Start/Stop/Restart/Open/Enter/Worktree → TUI Tasks 2–3, web Task 4. ✅
- Empty state "no runnable version yet" → Task 2 (TUI), Task 4 (web). ✅
- Action consolidation to one lifecycle (Start=`dev-action "start"`=full up; Stop/Restart) → Task 3 (TUI keys), Task 4 (web reuses `session-dev-cell`). ✅
- Session **list deleted** on both surfaces → Task 3 (TUI), Task 4 (web), Task 5 (dead-code). ✅
- Non-goals respected: no rename of underlying `session`; single environment only; no storage change. ✅
- Phase 2 (ledger provenance) intentionally **out of scope** — separate plan.

**Placeholder scan:** No TBD/TODO; every code step shows the full code. "Restart" semantics resolved concretely (`dev-action "restart"`).

**Type consistency:** `work/environment` returns a session record everywhere; consumers use `(:name env)` consistently (Task 1 map key `:environment`, TUI `with-environment`/`environment-block`, web destructure). `dev/dev-action!` arg order `[project ws-id session action]` matches Task 3's assertion and existing `dev-start!` wrappers. `session-dev-state` returns `{:state :url}` used identically in TUI (`environment-block`, `open-browser!`) and web (`:url dev`).
