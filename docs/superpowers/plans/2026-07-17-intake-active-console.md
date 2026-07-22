# Intake / Active console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `/workstreams`' source + facet filter chips with two tabs — **Intake** (triage + incoming) and **Active** (in-progress + shipping) — so every open workstream is reachable from the default landing.

**Architecture:** The tab is a **band selector, not a row filter**. Today's chips filter rows by a property (origin); tabs instead choose which part of the stage spine is on screen, and every row in that part renders regardless of origin. `nido.work/screen` stops filtering rows entirely and returns the scoped groups whole; a new `nido.work/tab-bands` owns the band→tab mapping as the single source of truth; the views pick bands per tab. This also removes a layering inversion: `nido.work` (the model core) currently requires `nido.ui.view-state` (a UI namespace) purely to borrow the source-filter defaults.

**Tech Stack:** Babashka + Clojure, hiccup2, Datastar v1.0.1 (SSE fragments), `clojure.test` via `bb nido:test`.

**Spec:** `docs/superpowers/specs/2026-07-17-intake-active-console-design.md`

## Why this is worth doing (do not lose this in review)

Measured on live data at design time, 35 open workstreams across 4 projects:

- `:in-progress` is **100% `:scratch`** (8 rows in brian, 5 in nido — all hand-made worktree sessions). Zero Notion-origin rows.
- `/workstreams` defaults to `source=notion` and has deliberately no cross-source "All".

So the default landing renders `IN-PROGRESS 0` for **every** project while the chips advertise `Scratch (15)`. The "managing/orchestrating work in-progress" half of nido's purpose is invisible by default, hidden behind a filter chip set to the wrong value. **Task 2 is what fixes that bug**; Task 3 gives it the right shape.

## Global Constraints

- **This repo is jj (Jujutsu), not git.** Never run bare `git` — a shell guard blocks it in session worktrees, and in the root checkout it binds to the wrong repo. Use `jj st`, `jj log`, `jj diff`, `jj split`.
- **Never commit planning artifacts, and in jj this needs deliberate handling.** The spec (`docs/superpowers/specs/2026-07-17-intake-active-console-design.md`) and this plan are **already in `@`** — jj has no staging area, so every new file is automatically part of the working-copy commit. They must never reach a described commit or `main`.
- **Therefore: commit with `jj split`, and never run `jj new` in this plan.** `jj split -m "msg" <paths>` moves *only* the named paths into a new described parent commit and leaves everything else (the two docs) behind in `@`. `jj new` would do the opposite — it turns `@`, docs and all, into an ancestor of every later commit, and they would ride to main. There is no `jj desc` step either; `-m` supplies the message and skips the editor.
- **Always name paths explicitly in `jj split`.** With no filesets it goes interactive and will hang a non-interactive agent.
- **Commit-driven development.** One logical change per commit — three tasks, three commits, each split off `@` in order. `jj log` before and after each split to confirm.
- **No PRs.** nido lands on `main` directly (`jj git push --bookmark main`). Do not open a PR or leave a feature branch on origin. Pushing happens once at the end, only if the user asks.
- **Run a load check after every source edit**, even a docstring-only one: `bb -e "(require 'nido.work)"`. A broken ns form fails here, not in the tests.
- **hiccup2 escapes `>` and `<` inside the inline `<style>` block.** Never use a CSS child combinator (`.a > .b`) in `shell-css` — it silently voids the rule. Use descendant selectors.
- **Test command:** `bb nido:test :only <ns-prefix>` (e.g. `bb nido:test :only nido.work`). Bare `bb nido:test` runs everything.
- The running daemon reads `src/` once at startup. To *see* the change in a browser: `bb nido:coordinator:restart`. Not needed for tests.
- Conventional commit subjects, matching repo history: `feat(scope):`, `fix(scope):`, `refactor(scope):`, `test(scope):`.

## File Structure

| File | Responsibility after this plan |
|---|---|
| `src/nido/work.clj` | Model core. Owns `tab-bands` (the band→tab mapping) and a `screen` that scopes but never filters rows. Requires **nothing** under `nido.ui`. |
| `src/nido/ui/view_state.clj` | Request → view-state. Owns `tabs` / `default-tab` (the URL vocabulary). No source/facet parsing. |
| `src/nido/ui/views.clj` | Rendering. Owns `tab-row` and the tab labels. No filter chrome. |
| `src/nido/ui/server.clj` | Routing + impure wiring. Loses `facet-dims-for`. **No route change** — `?tab=` flows through `view-state/parse` → `derive-screen` for free. |

Facet machinery (`work/facet-match?`, `facet-dimensions`, `facet-values`, `nido.coordinator.facets`) **stays** — the TUI still uses it (`tui.clj:244`, `:488`, `:1493`). This plan is web-only.

---

### Task 1: `work/screen` stops filtering; delete the dead filter machinery

Removes row filtering from the model core, deletes the four now-unreachable helpers, and drops the `nido.ui.view-state` require so the core no longer depends on a UI namespace.

**Files:**
- Modify: `src/nido/work.clj` (ns `:33`; `source-match?` `:563-568`; `filter-grouped` `:579-588`; `visible-pred` `:637-642`; `source-counts` `:644-658`; `screen` `:660-682`)
- Modify: `src/nido/ui/server.clj` (`facet-dims-for` `:136-144`; `derive-screen` `:146-170`)
- Test: `test/nido/work_test.clj`, `test/nido/ui/server_test.clj`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `(work/screen view-state data)` → `{:scope :groups :gates :needs-count}`. Keys `:source`, `:facets`, `:facet-dims`, `:source-counts` are **gone**. `:groups` is the scoped groups **unfiltered**. `work/grouped-rows`, `work/facet-match?`, `work/facet-values`, `work/facet-dimensions` keep their current signatures.

- [ ] **Step 1: Confirm the starting state**

```bash
jj st
```

Expected: exactly two `A` entries — the spec and the plan under `docs/superpowers/`. Nothing else. Do **not** run `jj new`; leave these two files in `@` for the whole plan.

- [ ] **Step 2: Delete the tests that assert the behaviour being removed**

In `test/nido/work_test.clj`, delete these four deftests **entirely**:
- `source-match-honours-origin` (~`:680`)
- `filter-grouped-keeps-shape-drops-nonmatching` (~`:693`)
- `screen-source-counts-include-incoming-under-its-source` (~`:821`)
- `screen-passes-injected-facet-dims-through` (~`:838`)
- `screen-source-counts-under-selected-source` (~`:843`)

(That is five — `source-counts` has two tests.)

In `test/nido/ui/server_test.clj`, delete these two deftests entirely:
- `workstreams-route-narrows-by-source-and-facet-end-to-end` (~`:236`)
- `workstreams-route-shows-slack-incoming-only-under-the-slack-source` (~`:295`)

Keep `facet-values-distinct-plus-unclassified`, `facet-match-composes-and-handles-vectors`, `facet-dimensions-from-config`, `facet-dimensions-is-source-aware`, `grouped-rows-flattens-all-bands` — the TUI still uses those fns.

- [ ] **Step 3: Write the failing tests**

In `test/nido/work_test.clj`, **replace** `screen-overview-and-detail-groups-are-identical` (~`:805`) with this version, and add the two new deftests after it:

```clojure
(deftest screen-overview-and-detail-groups-are-identical
  ;; The same view-state must produce the same :groups regardless of whether a
  ;; selection is present (the overview vs detail bug).
  (let [grouped {:incoming [] :triage {:in-flight [] :queued []}
                 :in-progress [{:ws-id "p1" :origin :github :stage :in-progress}]
                 :shipping []}
        groups [{:project "brian" :grouped grouped}]
        vs-over {:surface :workstreams :scope "all" :selection nil}
        vs-det  (assoc vs-over :selection {:project "brian" :ws-id "p1"})
        g1 (:groups (work/screen vs-over {:groups groups :gates [] :pending #{}}))
        g2 (:groups (work/screen vs-det  {:groups groups :gates [] :pending #{}}))]
    (is (= g1 g2) "selection must not change the list")
    (is (= ["p1"] (map :ws-id (get-in (first g1) [:grouped :in-progress])))
        "rows survive regardless of origin — screen does not filter")))

(deftest screen-does-not-filter-rows
  ;; The regression guard for the bug this design fixes: a scratch :in-progress
  ;; row used to be invisible because the surface defaulted to source=notion.
  ;; screen must now return every row it is given, whatever the origin.
  (let [grouped {:incoming [{:ws-id "i1" :origin :slack :stage :incoming}]
                 :triage {:in-flight [{:ws-id "t1" :origin :notion :stage :triage}]
                          :queued []}
                 :in-progress [{:ws-id "p1" :origin :scratch :stage :in-progress}]
                 :shipping [{:ws-id "s1" :origin :github :stage :shipping}]}
        s (work/screen {:surface :workstreams :scope "all" :selection nil}
                       {:groups [{:project "brian" :grouped grouped}]
                        :gates [] :pending #{}})
        ids (set (map :ws-id (work/grouped-rows (:grouped (first (:groups s))))))]
    (is (= #{"i1" "t1" "p1" "s1"} ids) "every row survives, every origin")
    (is (not (contains? s :source-counts)) "source-counts is gone")
    (is (not (contains? s :facet-dims)) "facet-dims is gone")))

(deftest work-core-does-not-require-a-ui-namespace
  ;; Layering: nido.work is the model core every surface wraps. It must not
  ;; depend on a UI namespace (it used to require nido.ui.view-state purely to
  ;; borrow the source-filter defaults). Read via io/resource, not a relative
  ;; path: `bb --config ~/Code/nido/bb.edn` runs with the caller's cwd (that is
  ;; how the `nido` shell wrapper dispatches), so "src/nido/work.clj" would not
  ;; resolve. "src" is on :paths, so the file is a classpath resource.
  (let [ns-form  (read-string (slurp (io/resource "nido/work.clj")))
        required (->> ns-form
                      (filter list?)
                      (filter #(= :require (first %)))
                      (mapcat rest)
                      (map first)
                      (map str))]
    (is (seq required) "sanity: the ns form was actually parsed")
    (is (not-any? #(str/starts-with? % "nido.ui") required)
        "the model core must not require a UI namespace")))
```

`test/nido/work_test.clj` already requires `[clojure.string :as str]` — leave it. Add `[clojure.java.io :as io]` to its `:require`, keeping every other require exactly as it is.

- [ ] **Step 4: Run the tests to verify they fail**

```bash
bb nido:test :only nido.work
```

Expected: FAIL. `screen-does-not-filter-rows` fails because `screen` still filters by `:source` (defaulting to `:notion`), so the scratch/slack/github rows are dropped and `ids` is `#{"t1"}`. `work-core-does-not-require-a-ui-namespace` fails on `nido.ui.view-state`.

- [ ] **Step 5: Rewrite `screen` and delete the dead helpers in `src/nido/work.clj`**

Delete these four forms outright: `source-match?`, `filter-grouped`, `visible-pred`, `source-counts`.

Replace `screen` with:

```clojure
(defn screen
  "The single pure derivation from view-state to the screen-model. Every render
   site (full page + SSE poll, overview + detail) renders a slice of THIS value,
   so they cannot disagree. `data` injects what only IO can produce:
     :groups  (all-grouped)  :gates (all-gates)  :pending (#{\"project/ws-id\"} optimistic bridge keys).
   Selection detail is attached by the caller (needs work/workstream + dev-states).

   NO row filtering: every workstream the model emits is reachable from the
   surface. The board's tabs select BANDS, not rows — filtering here is what hid
   every :in-progress row behind the source chip's `source=notion` default.
   `:tab` is passed through verbatim for the surface to render; defaulting it is
   view-state's job, not the core's (borrowing that default is what pulled a UI
   require into this namespace)."
  [{:keys [scope tab] :or {scope "all"}}
   {:keys [groups gates pending] :or {groups [] gates [] pending #{}}}]
  (let [scoped     (scope-keep scope groups)
        kept-gates (->> (scope-keep scope gates)
                        (mapv (fn [g] (assoc g :pending?
                                             (or (boolean (:working? g))
                                                 (contains? pending (str (:project g) "/" (:ws-id g))))))))]
    {:scope       scope
     :tab         tab
     :groups      scoped
     :gates       kept-gates
     :needs-count (count kept-gates)}))
```

Remove `[nido.ui.view-state :as view-state]` from the ns `:require` (`:33`).

Fix the now-stale docstring on `source-match?`'s neighbour `facet-dimensions` only if it references the deleted fns — otherwise leave surrounding code alone.

- [ ] **Step 6: Load-check `work`**

```bash
bb -e "(require 'nido.work)"
```

Expected: no output, exit 0. A dangling reference to a deleted fn fails here.

- [ ] **Step 7: Drop the facet-dims injection in `src/nido/ui/server.clj`**

Delete `facet-dims-for` (`:136-144`) entirely, and remove its injection from `derive-screen`:

```clojure
(defn derive-screen
  "Impure wiring: gather what only IO can produce (grouped rows, gates, in-flight
   resolve keys), hand off to the pure work/screen, then attach the selection
   detail. Selection detail is attached HERE (not in work) because it needs the
   dev layer, which work must not depend on. Every /workstreams + / render route
   runs through this one function, so no two render sites disagree."
  [view-state]
  (let [screen (work/screen view-state
                            {:groups  (work/all-grouped)
                             :gates   (work/all-gates)
                             :pending (dev/pending-resolve-keys)})
        sel (:selection view-state)]
    (assoc screen :selection
           (when sel
             (let [ws (when (= :workstreams (:surface view-state))
                        (work/workstream (:project sel) (:ws-id sel) (:entry view-state)))]
               (cond-> {:project (:project sel) :ws-id (:ws-id sel)}
                 ws (assoc :ws ws
                           :dev-states (dev/ws-session-dev-states (:project sel) ws))))))))
```

Leave the `[nido.project :as project]` require — `rail-ctx` / `rail-context` still use `project/list-projects`.

- [ ] **Step 8: Load-check `server` and run the tests**

```bash
bb -e "(require 'nido.ui.server)"
bb nido:test :only nido.work
bb nido:test :only nido.ui
```

Expected: PASS for all three. The views still compile: `source-row` destructures `:source`/`:source-counts` off the screen, now gets `nil`, and renders inert chips — ugly for one commit, deleted in Task 2.

- [ ] **Step 9: Split the source changes into their own commit**

```bash
jj st
```

Expected: the four source/test files, plus the two doc files still showing as `A`.

Now peel off only the source files — the docs stay behind in `@`:

```bash
jj split -m "refactor(work): screen stops filtering rows; drop the ui.view-state require

Row filtering in the model core is what hid every :in-progress row behind
/workstreams' source=notion default (measured: IN-PROGRESS 0 on every project
while the chips read Scratch (15)). screen now scopes and returns the groups
whole; the board's tabs become a band selector instead.

Deletes source-match?, filter-grouped, visible-pred and source-counts (no
callers outside work + its tests; the TUI filters origin with its own
filter-origin), and the server's facet-dims-for. Dropping :source/:facets lets
work stop requiring nido.ui.view-state — the model core no longer depends on a
UI namespace." src/nido/work.clj src/nido/ui/server.clj test/nido/work_test.clj test/nido/ui/server_test.clj

jj log -r '@- | @' --no-graph -T 'change_id.short() ++ " " ++ description.first_line() ++ "\n"'
jj st
```

Expected: `@-` carries the described refactor; `@` still holds **only** the two doc files. If `jj st` shows `@` is empty, the docs were swallowed into the commit — undo with `jj undo` and re-split naming the paths explicitly.

---

### Task 2: Delete the filter chrome

Removes the source + facet chips and their URL vocabulary. **This is the task that fixes the bug**: with no source filter, `/workstreams` shows every row — including the 13 `:scratch` in-progress sessions that were unreachable by default. The list is a single un-tabbed group until Task 3.

**Files:**
- Modify: `src/nido/ui/view_state.clj` (`sources` `:14-19`; `default-source` `:21-23`; `parse` `:38-62`)
- Modify: `src/nido/ui/views.clj` (ns `:8`; `enc-val` `:222`; `screen-query` `:225-241`; `chip-link` `:678-679`; `source-row` `:681-693`; `facet-rows` `:695-711`; `workstreams-page` `:713-734`; CSS `:127-130`)
- Test: `test/nido/ui/view_state_test.clj`, `test/nido/ui/views_test.clj`

**Interfaces:**
- Consumes: `work/screen` from Task 1 (no `:source`/`:facets`/`:source-counts`/`:facet-dims`).
- Produces: `(view-state/parse req)` → `{:surface :scope :selection :entry}`. `view-state/sources` and `view-state/default-source` **no longer exist**. `screen-query` is `[{:keys [scope]} & [overrides]]`, honouring only `:sel`.

- [ ] **Step 1: Confirm the starting state**

```bash
jj st
```

Expected: only the two doc files (`A`), Task 1's commit at `@-`. No `jj new`.

- [ ] **Step 2: Delete the tests that assert the chrome**

In `test/nido/ui/views_test.clj`, delete these four deftests entirely:
- `workstreams-filter-bar-renders-source-chips-with-counts` (~`:246`)
- `workstreams-filter-bar-hides-facets-for-facetless-source` (~`:258`)
- `workstreams-filter-bar-renders-facet-values-from-rows` (~`:266`)
- `screen-query-encodes-facet-values` (~`:282`)

In `test/nido/ui/view_state_test.clj`, delete these three deftests entirely:
- `parse-unclassified-facet` (~`:28`)
- `parse-url-decodes-facet-values` (~`:32`)
- `parse-ignores-datastar-signals-param` (~`:38`) — it asserted the `datastar` param did not become a bogus facet filter; with facets gone there is nothing to protect.

- [ ] **Step 3: Write the failing tests**

In `test/nido/ui/view_state_test.clj`, **replace** `parse-defaults` (~`:5`) and `parse-filters-and-selection` (~`:19`) with:

```clojure
(deftest parse-defaults
  (let [v (vs/parse {:uri "/workstreams" :query-string nil})]
    (is (= :workstreams (:surface v)))
    (is (= "all" (:scope v)))
    (is (nil? (:selection v)))
    (is (nil? (:entry v)))
    (is (not (contains? v :source)) "no source filter — the board shows every origin")
    (is (not (contains? v :facets)) "no facet filter")))

(deftest parse-scope-and-selection
  (let [v (vs/parse {:uri "/workstreams" :query-string "scope=brian&sel=brian%3Aws-1&entry=3"})]
    (is (= "brian" (:scope v)))
    (is (= {:project "brian" :ws-id "ws-1"} (:selection v)))
    (is (= 3 (:entry v)))))

(deftest parse-ignores-a-legacy-filter-bookmark
  ;; An old ?source=/facet bookmark must parse cleanly and filter nothing.
  (let [v (vs/parse {:uri "/workstreams" :query-string "source=scratch&app-domain=Teacher"})]
    (is (= "all" (:scope v)))
    (is (not (contains? v :source)))
    (is (not (contains? v :facets)))))
```

In `test/nido/ui/views_test.clj`, **replace** `workstreams-fragment-preserves-selection-and-filters` (~`:142`) and `workstreams-page-has-shell-and-poll` (~`:190`) with:

```clojure
(deftest workstreams-fragment-preserves-selection
  ;; a poll refresh keeps the open row highlighted, and each row link preserves
  ;; the view-state so selecting one lands on the SAME list.
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection {:project "brian" :ws-id "w1"}
                                          :scope "all"})]
    (is (str/includes? html "gate-card sel") "selected row keeps its highlight")
    (is (str/includes? html "sel=brian:w1"))
    (is (not (str/includes? html "source=")) "no source filter in row links")))

(deftest workstreams-page-has-shell-and-poll
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "all" :projects []}
                                     {:scope "all" :selection nil
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (str/includes? html "rail-link active"))
    (is (str/includes? html "/_fragment/workstreams"))))

(deftest workstreams-page-renders-no-filter-chrome
  ;; The source + facet chips are gone: no filter row, no Source label, and a
  ;; :scratch in-progress row renders on the default landing (it used to be
  ;; hidden behind source=notion).
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "all" :projects []}
                                     {:scope "all" :selection nil
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (not (str/includes? html "filter-row")))
    (is (not (str/includes? html "filter-label")))
    (is (not (str/includes? html ">Source<")))
    (is (str/includes? html "spike") "the scratch in-progress row is visible by default")))
```

`sample-grouped` (~`:115`) already carries the `:scratch` `:in-progress` row labelled `"spike"` — leave it as it is. It has no `:incoming`/`:shipping` keys, which is deliberate: the band flattener must tolerate absent keys.

- [ ] **Step 4: Run the tests to verify they fail**

```bash
bb nido:test :only nido.ui
```

Expected: FAIL. `workstreams-page-renders-no-filter-chrome` fails on `filter-row` still rendering; `parse-defaults` fails because `:source` is still present.

- [ ] **Step 5: Strip the filter vocabulary from `src/nido/ui/view_state.clj`**

Delete `sources` and `default-source` outright. Replace `parse` with:

```clojure
(defn parse
  "Request map -> view-state:
     {:surface :needs|:workstreams|:other
      :scope   \"all\"|<project>
      :selection {:project _ :ws-id _}|nil
      :entry   <long>|nil}

   No source/facet filtering: the board shows every origin, and its tabs select
   BANDS rather than rows (see nido.work/tab-bands). A legacy ?source= / facet
   bookmark parses cleanly and constrains nothing."
  [{:keys [uri query-string]}]
  (let [ps (pairs query-string)]
    {:surface   (surface uri)
     :scope     (or (some (fn [[k v]] (when (= k "scope") v)) ps) "all")
     :selection (selection ps)
     :entry     (some (fn [[k v]] (when (= k "entry") (parse-long v))) ps)}))
```

`decode` stays — `selection` uses it. The `reserved` set goes with the facets it protected.

- [ ] **Step 6: Delete the chrome from `src/nido/ui/views.clj`**

Delete these forms outright: `enc-val` (`:222`), `chip-link` (`:678-679`), `source-row` (`:681-693`), `facet-rows` (`:695-711`).

Keep `chip` (`:219`) — it renders the stage chip on `gate-card` (`:251`) and is unrelated.

Replace `screen-query` with:

```clojure
(defn- screen-query
  "Query string (leading ?) rebuilding the active scope from the screen, with
   optional overrides. `:sel` adds the selection (\"project:ws-id\"). The single
   place scope + selection are serialized — so a row link, a poll refresh, and a
   deep link all carry the identical view-state."
  [{:keys [scope]} & [overrides]]
  (let [sel   (:sel overrides)
        pairs (cond-> []
                (and scope (not= "all" scope)) (conj (str "scope=" scope))
                sel (conj (str "sel=" sel)))]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))
```

In `workstreams-page`, drop the `.filters` div — the `[:div.queue-col …]` becomes:

```clojure
      [:div.queue-col
       [:div.inbox {:data-on-interval__duration.5s (str "@get('/_fragment/workstreams" q "')")}
        (h/raw (workstreams-fragment screen))]]
```

Update `ws-list-row`'s docstring (`:513-517`): it claims the link "carries the full view-state (scope + source + facets)" — it now carries scope + selection.

Remove `[nido.ui.view-state :as view-state]` from the ns `:require` (`:8`) — `source-row` was its only user. (Task 3 re-adds it for the tab list; that churn is deliberate, so each commit is internally clean.)

In `shell-css`, delete the three now-dead rules (`:127-130`):

```
     .filters { … }
     .filter-row { … }
     .filter-label { … }
     .chip.active { … }
```

`.chip` itself stays (the stage chip uses it). `.chip.active` had no user but the chips.

- [ ] **Step 7: Load-check and run the tests**

```bash
bb -e "(require 'nido.ui.views)"
bb -e "(require 'nido.ui.view-state)"
bb nido:test :only nido.ui
bb nido:test :only nido.work
```

Expected: PASS for all four.

- [ ] **Step 8: Split the source changes into their own commit**

```bash
jj st
jj split -m "fix(ui): drop the /workstreams source + facet chips — show every row

The page always showed exactly one source and defaulted to notion, while
:in-progress is 100% :scratch — so the default landing rendered IN-PROGRESS 0
on every project with 13 live sessions hidden behind a chip. Origin is a badge,
not a filter: intake arrives via various streams and is read whole.

Deletes source-row, facet-rows, chip-link, enc-val and the ?source=/?facets=
URL vocabulary; a legacy bookmark now parses cleanly and constrains nothing.
Facets stay in the model — the TUI still uses them." src/nido/ui/view_state.clj src/nido/ui/views.clj test/nido/ui/view_state_test.clj test/nido/ui/views_test.clj

jj log -r '@- | @' --no-graph -T 'change_id.short() ++ " " ++ description.first_line() ++ "\n"'
jj st
```

Expected: `@` still holds only the two doc files.

---

### Task 3: Intake / Active tabs

Adds the two tabs and the band→tab mapping. `work/tab-bands` is the single place the mapping lives.

**Files:**
- Modify: `src/nido/work.clj` (add `tab-bands` after `stages` `:35-38`)
- Modify: `src/nido/ui/view_state.clj` (add `tabs` / `default-tab`; `parse`)
- Modify: `src/nido/ui/views.clj` (ns; `screen-query`; `ws-stage-sections` `:501-511` → `ws-tab-sections`; `workstreams-fragment` `:534-565`; `workstreams-page`; CSS)
- Test: `test/nido/work_test.clj`, `test/nido/ui/view_state_test.clj`, `test/nido/ui/views_test.clj`, `test/nido/ui/server_test.clj`

**Interfaces:**
- Consumes: `work/screen` → `{:scope :tab :groups :gates :needs-count}` (Task 1); `screen-query` `[{:keys [scope]} & [overrides]]` (Task 2).
- Produces:
  - `work/tab-bands` — `(tab-bands tab grouped)` → vector of `[stage rows]` pairs, empty bands dropped. `tab` is `:intake` | `:active`; anything else behaves as `:intake`.
  - `view-state/tabs` → `[:intake :active]`; `view-state/default-tab` → `:intake`.
  - `(view-state/parse req)` gains `:tab`.
  - `screen-query` gains a `:tab` override.

- [ ] **Step 1: Confirm the starting state**

```bash
jj st
```

Expected: only the two doc files (`A`), Tasks 1–2 committed below. No `jj new`.

- [ ] **Step 2: Write the failing tests**

In `test/nido/work_test.clj`, add:

```clojure
(deftest tab-bands-splits-the-spine-into-two-jobs
  (let [grouped {:incoming [{:ws-id "i"}]
                 :triage {:in-flight [{:ws-id "tf"}] :queued [{:ws-id "tq"}]}
                 :in-progress [{:ws-id "p"}]
                 :shipping [{:ws-id "s"}]}]
    (is (= [[:triage ["tf" "tq"]] [:incoming ["i"]]]
           (for [[stage rows] (work/tab-bands :intake grouped)]
             [stage (mapv :ws-id rows)]))
        "intake = triage (in-flight then queued) then incoming")
    (is (= [[:shipping ["s"]] [:in-progress ["p"]]]
           (for [[stage rows] (work/tab-bands :active grouped)]
             [stage (mapv :ws-id rows)]))
        "active = shipping then in-progress, most-advanced-first")))

(deftest tab-bands-union-covers-every-row-exactly-once
  ;; The guarantee this whole design exists for: nothing can be hidden by
  ;; default again. Every row the model emits is reachable from exactly one tab.
  (let [grouped {:incoming [{:ws-id "i"}]
                 :triage {:in-flight [{:ws-id "tf"}] :queued [{:ws-id "tq"}]}
                 :in-progress [{:ws-id "p"}]
                 :shipping [{:ws-id "s"}]}
        rows-of (fn [tab] (mapcat second (work/tab-bands tab grouped)))
        intake  (set (map :ws-id (rows-of :intake)))
        active  (set (map :ws-id (rows-of :active)))]
    (is (= (set (map :ws-id (work/grouped-rows grouped)))
           (into intake active))
        "union of both tabs = every row grouped-rows emits")
    (is (empty? (set/intersection intake active))
        "and no row appears in both tabs")))

(deftest tab-bands-drops-empty-bands-and-tolerates-absent-keys
  (is (= [] (work/tab-bands :active {:in-progress [] :shipping []}))
      "empty bands are dropped")
  (is (= [] (work/tab-bands :active {}))
      "absent keys are not an error")
  (is (= [[:triage ["t"]]]
         (for [[stage rows] (work/tab-bands :intake {:triage {:in-flight [{:ws-id "t"}] :queued []}})]
           [stage (mapv :ws-id rows)]))
      "a band with rows survives while its empty sibling is dropped"))

(deftest tab-bands-unknown-tab-behaves-as-intake
  (let [grouped {:triage {:in-flight [{:ws-id "t"}] :queued []} :in-progress [{:ws-id "p"}]}]
    (is (= (work/tab-bands :intake grouped) (work/tab-bands :bogus grouped)))))

(deftest screen-passes-the-tab-through
  (let [s (work/screen {:surface :workstreams :scope "all" :tab :active}
                       {:groups [] :gates [] :pending #{}})]
    (is (= :active (:tab s)))))
```

Add `[clojure.set :as set]` to `test/nido/work_test.clj`'s ns requires if absent.

In `test/nido/ui/view_state_test.clj`, add:

```clojure
(deftest parse-tab
  (is (= :intake (:tab (vs/parse {:uri "/workstreams" :query-string nil})))
      "defaults to the first tab")
  (is (= :intake vs/default-tab))
  (is (= :active (:tab (vs/parse {:uri "/workstreams" :query-string "tab=active"}))))
  (is (= :intake (:tab (vs/parse {:uri "/workstreams" :query-string "tab=bogus"})))
      "an unknown tab falls back to the default rather than rendering an empty list"))
```

In `test/nido/ui/views_test.clj`, add:

```clojure
(deftest workstreams-page-renders-both-tabs-with-the-active-one-marked
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "all" :projects []}
                                     {:scope "all" :tab :intake :selection nil
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (str/includes? html "Intake"))
    (is (str/includes? html "Active"))
    (is (str/includes? html "tab active") "the current tab is marked")
    (is (str/includes? html "tab=active") "the other tab is one click away")))

(deftest workstreams-fragment-intake-shows-triage-not-in-progress
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection nil :scope "all" :tab :intake})]
    (is (str/includes? html "triage"))
    (is (str/includes? html "BR-1 · a"))
    (is (not (str/includes? html "spike")) "the in-progress row belongs to the Active tab")
    (is (not (str/includes? html "ready")) "no :ready band — the backlog lives in Notion")))

(deftest workstreams-fragment-active-shows-in-progress-not-triage
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection nil :scope "all" :tab :active})]
    (is (str/includes? html "in-progress"))
    (is (str/includes? html "spike") "the scratch session is reachable — the bug this fixes")
    (is (not (str/includes? html "BR-1 · a")) "the triage row belongs to the Intake tab")))

(deftest tab-links-preserve-scope-and-selection
  (let [html (views/workstreams-page {:active :workstreams :needs-count 0 :daemon {:state :up}
                                      :scope "brian" :projects []}
                                     {:scope "brian" :tab :intake
                                      :selection {:project "brian" :ws-id "w1"}
                                      :groups [{:project "brian" :grouped sample-grouped}]})]
    (is (str/includes? html "scope=brian"))
    (is (str/includes? html "sel=brian:w1"))))
```

`workstreams-fragment-groups-and-links` (~`:131`) passes no `:tab`, so it will render Intake and its `"in-progress"` assertion will fail. **Replace** it with:

```clojure
(deftest workstreams-fragment-groups-and-links
  (let [html (views/workstreams-fragment {:groups [{:project "brian" :grouped sample-grouped}]
                                          :selection nil :scope "all" :tab :intake})]
    (is (str/includes? html "id=\"workstreams\""))
    (is (str/includes? html "triage"))
    (is (not (str/includes? html "ready")) "no :ready band section")
    (is (str/includes? html "BR-1 · a"))
    (is (str/includes? html "sel=brian:w1"))            ; rows carry the selection in the view-state
    (is (str/includes? html ">N<"))))
```

In `test/nido/ui/server_test.clj`, add:

```clojure
(deftest workstreams-route-renders-the-requested-tab-end-to-end
  ;; parse → derive-screen → views: ?tab= flows through with no route of its own.
  ;; Same four stubs the deleted source/facet end-to-end test used.
  (with-redefs [nido.work/all-grouped
                (fn [] [{:project "brian"
                         :grouped {:incoming []
                                   :triage {:in-flight [{:ws-id "t" :origin :notion :stage :triage
                                                         :label "Triage-row"}]
                                            :queued []}
                                   :in-progress [{:ws-id "p" :origin :scratch :stage :in-progress
                                                  :label "Scratch-row"}]
                                   :shipping []}}])
                nido.work/all-gates (fn [] [])
                project/list-projects (fn [] {"brian" {:directory "/x"}})
                nido.ui.server/read-rail-daemon (fn [] {:state :up})]
    (let [intake (:body (server/handle-request {:request-method :get :uri "/workstreams"}))
          active (:body (server/handle-request {:request-method :get :uri "/workstreams"
                                                :query-string "tab=active"}))]
      (is (str/includes? intake "Triage-row"))
      (is (not (str/includes? intake "Scratch-row")))
      (is (str/includes? active "Scratch-row") "the scratch row is reachable — the bug this fixes")
      (is (not (str/includes? active "Triage-row"))))))
```

`test/nido/ui/server_test.clj` already requires everything this needs (`clojure.string :as str`, `nido.ui.server :as server`, `nido.work`, `nido.project :as project`) — add nothing.

- [ ] **Step 3: Run the tests to verify they fail**

```bash
bb nido:test :only nido.work
```

Expected: FAIL — `Unable to resolve symbol: tab-bands`.

- [ ] **Step 4: Add `tab-bands` to `src/nido/work.clj`**

Directly after `stages` (`:35-38`):

```clojure
(defn tab-bands
  "Ordered [stage rows] pairs for `tab` out of a `grouped` map, empty bands
   dropped. The ONE place the band→tab mapping lives, so no surface can disagree
   about where a band belongs.

     :intake — :triage (in-flight then queued) + :incoming   — work arriving via
               the various streams, awaiting a verdict.
     :active — :shipping + :in-progress (most-advanced first) — work nido is
               driving.

   These are nido's two jobs. The backlog (:ready) and the archive (:done) live
   in Notion and are never emitted by grouped-by-stage, so they are not bands
   here. Their union is every row `grouped-rows` emits — a workstream is always
   reachable from exactly one tab, which is the guarantee that nothing can be
   hidden by default (a source filter defaulting to :notion once hid every
   :in-progress row). An unrecognized `tab` reads as :intake."
  [tab grouped]
  (->> (case tab
         :active [[:shipping    (:shipping grouped)]
                  [:in-progress (:in-progress grouped)]]
         [[:triage   (concat (-> grouped :triage :in-flight)
                             (-> grouped :triage :queued))]
          [:incoming (:incoming grouped)]])
       (into [] (keep (fn [[stage rows]] (when (seq rows) [stage (vec rows)]))))))
```

- [ ] **Step 5: Run the work tests to verify they pass**

```bash
bb -e "(require 'nido.work)"
bb nido:test :only nido.work
```

Expected: PASS.

- [ ] **Step 6: Add the tab vocabulary to `src/nido/ui/view_state.clj`**

Where `sources` used to live:

```clojure
(def tabs
  "The /workstreams surfaces, in display order. The FIRST entry is the default.
   A tab is a BAND selector — which part of the stage spine is on screen (see
   nido.work/tab-bands) — NOT a row filter: every row in the tab renders
   whatever its origin. They are nido's two jobs: intake via the various
   streams, and orchestrating work in progress."
  [:intake :active])

(def default-tab
  "The tab /workstreams opens on when none is selected — the first one."
  (first tabs))
```

Add `:tab` to `parse`'s returned map and its docstring:

```clojure
     :tab       (let [t (some (fn [[k v]] (when (= k "tab") (keyword v))) ps)]
                  (if (some #{t} tabs) t default-tab))
```

- [ ] **Step 7: Render the tabs in `src/nido/ui/views.clj`**

Re-add `[nido.ui.view-state :as view-state]` to the ns `:require` (Task 2 removed it; the tab list lives there).

Give `screen-query` a `:tab` override:

```clojure
(defn- screen-query
  "Query string (leading ?) rebuilding the active scope + tab from the screen,
   with optional overrides. `:sel` adds the selection (\"project:ws-id\"); `:tab`
   overrides the tab (used by the tab links). The single place scope, tab and
   selection are serialized — so a row link, a poll refresh, and a deep link all
   carry the identical view-state. The default tab is omitted, keeping
   /workstreams clean."
  [{:keys [scope tab]} & [overrides]]
  (let [tb    (get overrides :tab tab)
        sel   (:sel overrides)
        pairs (cond-> []
                (and scope (not= "all" scope)) (conj (str "scope=" scope))
                (and tb (not= view-state/default-tab tb)) (conj (str "tab=" (name tb)))
                sel (conj (str "sel=" sel)))]
    (if (seq pairs) (str "?" (str/join "&" pairs)) "")))
```

Add `tab-row` just above `workstreams-page`:

```clojure
(defn- tab-row
  "The board's two tabs — Intake | Active. A tab selects BANDS, not rows: every
   workstream in the tab renders whatever its origin, so nothing is hidden by
   default. Switching tabs preserves scope + selection."
  [{:keys [tab] :as screen}]
  [:div.tabs
   (for [id view-state/tabs]
     [:a {:class (str "tab" (when (= id tab) " active"))
          :href  (str "/workstreams" (screen-query screen {:tab id}))}
      (str/capitalize (name id))])])
```

Replace `ws-stage-sections` (`:501-511`) with:

```clojure
(defn- ws-tab-sections
  "Flatten one {:project :grouped} into [{:project :stage :rows}] for `tab`,
   taking the band list + order from work/tab-bands — the single place the
   band→tab mapping lives. Kept as a plain fn (not inline hiccup) so the
   fragment's `for` stays readable."
  [tab {:keys [project grouped]}]
  (for [[stage rows] (work/tab-bands tab grouped)]
    {:project project :stage stage :rows rows}))
```

In `workstreams-fragment` (`:534-538`), take the tab off the screen and thread it:

```clojure
(defn workstreams-fragment
  "The selected tab's stage-grouped selectable list across projects, rendered
   from the screen. Selection is threaded from the screen so a poll refresh keeps
   the open row's highlight instead of clearing it."
  [{:keys [groups selection tab] :as screen}]
  (let [sel-id (:ws-id selection)]
```

and its section loop (`:547`):

```clojure
       (for [{:keys [project stage rows]} (mapcat #(ws-tab-sections tab %) groups)]
```

In `workstreams-page`, put `tab-row` where `.filters` was:

```clojure
      [:div.queue-col
       (tab-row screen)
       [:div.inbox {:data-on-interval__duration.5s (str "@get('/_fragment/workstreams" q "')")}
        (h/raw (workstreams-fragment screen))]]
```

Add the CSS to `shell-css`, next to where `.filters` was. **Descendant selectors only — hiccup2 escapes `>` in the inline style block and silently voids the rule:**

```
     .tabs { display:flex; gap:4px; padding:10px 16px 6px; }
     .tab { padding:4px 10px; border-radius:4px; color:#888; font-size:12px;
            text-transform:uppercase; border:1px solid transparent; }
     .tab:hover { color:#ccc; text-decoration:none; }
     .tab.active { background:#2a4a6a; color:#aee0ff; border-color:#3a5a7a; }
```

`ws-fold-stages` (`:468-472`) stays as-is — all four stages still fold, two per tab.

- [ ] **Step 8: Run every test**

```bash
bb -e "(require 'nido.ui.views)"
bb -e "(require 'nido.ui.view-state)"
bb -e "(require 'nido.ui.server)"
bb nido:test
```

Expected: PASS across the suite.

- [ ] **Step 9: Verify in the real dashboard**

```bash
bb nido:coordinator:restart
```

Then open `http://localhost:8800/workstreams` and confirm by eye:
- Two tabs, Intake active, no Source/facet chips anywhere.
- **Active shows the scratch sessions** (`feat/…`, `fix/…`) — the rows that were invisible before this change. This is the whole point; if Active is empty, something is wrong.
- Clicking a row still opens the ledger pane; the tab and selection survive the 5s poll.

- [ ] **Step 10: Split the source changes into their own commit**

```bash
jj st
jj split -m "feat(ui): /workstreams Intake | Active tabs

nido's two jobs, one tab each: intake via the various streams (triage +
incoming) and orchestrating work in progress (shipping + in-progress). The tab
is a BAND selector, not a row filter — every row renders whatever its origin,
so nothing is hidden by default.

work/tab-bands owns the band→tab mapping as the single source of truth; the
union of both tabs is every row grouped-rows emits, enforced by test. :ready and
:done remain absent — the backlog and the archive live in Notion." src/nido/work.clj src/nido/ui/view_state.clj src/nido/ui/views.clj test/nido/work_test.clj test/nido/ui/view_state_test.clj test/nido/ui/views_test.clj test/nido/ui/server_test.clj

jj log -r 'main@origin..@' --no-graph -T 'change_id.short() ++ " " ++ description.first_line() ++ "\n"'
jj st
```

Expected: three described commits between `main@origin` and `@`, and `@` holding nothing but the two doc files.

---

## Done criteria

- `bb nido:test` passes.
- `/workstreams` has two tabs and no filter chips; Active lists the scratch sessions.
- `bb -e "(require 'nido.work)"` passes and `work.clj`'s ns form requires nothing under `nido.ui`.
- Three described commits on top of `main`, and `jj st` shows `@` still holding **only** the spec + plan. `jj diff -r 'main@origin..@-'` must not mention `docs/`.
- Landing (only when the user asks): `main` moves to `@-`, not `@` — `jj bookmark set main -r @-` then `jj git push --bookmark main`. Pointing it at `@` would publish the planning docs.
- **Not done here** (spec's follow-on list): needs-you as state rather than place; Notion truth-telling (`notion-driven?`, `:ball-ids`, `:notion-priority`, `:bare?`, ticket link-outs, the local-vs-Notion status contradiction); staleness; the TUI's own Intake/Active cut. The two model forks (`:intake` vs `:incoming`; the "Review" disagreement between `notion_sync` and `notion-stage`) are also still open.
