# Lite Session Profile — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **jj commit hygiene:** This is a jujutsu repo. In every commit step, ALWAYS run `jj log -r '@-..@' --no-graph` BEFORE editing files (if `@` has a description, run `jj new` first), and AGAIN AFTER editing (confirm parent is the previous task's commit, not your own). Two implementers in Plan A mashed two tasks into one commit by skipping this check.

**Goal:** Add a "lite" session shape to nido: no PG/JVM/app services, worktree is a symlink to an existing checkout. Triggers and CLI declare a `:session-profile` to select between `:full` (current default) and `:lite`. Defaults to `:full` everywhere — zero behavior change for existing manual sessions or the smoke trigger.

**Architecture:** A new `nido.session.profiles` namespace owns a per-project registry `~/.nido/projects/<project>/session-profiles.edn` (keyword → profile map). Profile resolution happens once in `lifecycle/up!` and the resolved profile threads through `engine/start-session!` as an extra ctx field. `engine/start-services!` filters `:services` by the profile's allowlist; `lifecycle/up!`'s worktree branch picks `git worktree add` vs `ln -s` based on the profile's `:worktree` strategy. `launcher/write-artifacts!` already short-circuits the postgres MCP entry when no PG service exists — no change needed there.

**Tech Stack:** Babashka (bb), `clojure.test`, Malli, the existing `nido.session.*` namespaces.

**Spec reference:** [2026-05-18-notion-triage-agent-design.md §Session profile registry](../specs/2026-05-18-notion-triage-agent-design.md). This plan delivers Stage 2 of the six-stage rollout.

---

## File Structure

**New:**
- `src/nido/session/profiles.clj` — registry loader + resolver + Malli schema.
- `test/nido/session/profiles_test.clj` — unit tests for loader/resolver.

**Modified:**
- `src/nido/session/lifecycle.clj` — `up!` resolves the profile once and threads it through opts; worktree branch dispatches on `:strategy :symlink` vs default `:git-worktree`. `reset!` and `destroy!` skip PGDATA / git-worktree cleanup on `:lite` and remove the symlink instead.
- `src/nido/session/engine.clj` — `start-services!` filters `:services` by the profile's allowlist.
- `src/nido/coordinator/triggers.clj` — `Trigger` schema gains `[:session-profile {:optional true} keyword?]`.
- `src/nido/coordinator/runs.clj` — `create-run!` persists `:session-profile` on the Run record; `spawn-session-for-run!` passes it to `up!`.
- `src/tasks/nido_session.clj` — `up` task accepts `:session-profile <kw>` from the CLI.
- `test/nido/coordinator/triggers_test.clj` — assertion that `:session-profile` is accepted.
- `test/nido/coordinator/runs_test.clj` — `create-run!` carries `:session-profile` through.
- `test/nido/session/lifecycle_test.clj` — create if missing; assert lite worktree is a symlink.
- `test/nido/session/engine_test.clj` — assert `start-services!` filter respects the profile allowlist.

**Untouched:** `src/nido/session/launcher.clj` (the MCP-config branch already gates on `pg-svc`), `src/nido/session/services/**` (lite skips them by not selecting them), `src/nido/coordinator/executor.clj` (profile is upstream of the executor).

---

## Task 1 — `nido.session.profiles` namespace

**Files:**
- Create: `src/nido/session/profiles.clj`
- Test: `test/nido/session/profiles_test.clj`

A small registry: load → validate → resolve. The on-disk file lives at `~/.nido/projects/<project>/session-profiles.edn`. Shape:

```clojure
{:profiles
 {:full {:services :all                         ;; :all means "use whatever session.edn declares"
         :worktree {:strategy :git-worktree}}
  :lite {:services []                           ;; explicit allowlist; empty = no services start
         :worktree {:strategy :symlink
                    :target   "~/Code/brian"}}}}
```

If the file is missing, the resolver returns built-in defaults: `:full` only, with `:services :all` and `:worktree {:strategy :git-worktree}`. This preserves existing behavior for projects that don't opt in.

- [ ] **Step 1: jj hygiene check.** `jj log -r '@-..@' --no-graph`. If `@` has a description, `jj new`. Confirm `@` is empty.

- [ ] **Step 2: Write the failing tests**

```clojure
(ns nido.session.profiles-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.io :as io]
   [nido.session.profiles :as profiles]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (str (fs/path tmp "projects" "brian")))
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest resolve-returns-builtin-full-when-no-registry-file
  (with-tmp
    (fn [_]
      (let [p (profiles/resolve-profile :brian :full)]
        (is (= :all (:services p)))
        (is (= :git-worktree (-> p :worktree :strategy)))))))

(deftest resolve-reads-registry-when-present
  (with-tmp
    (fn [tmp]
      (let [path (str (fs/path tmp "projects" "brian" "session-profiles.edn"))]
        (io/write-edn! path
          {:profiles {:lite {:services []
                             :worktree {:strategy :symlink :target "/tmp/x"}}}})
        (let [p (profiles/resolve-profile :brian :lite)]
          (is (= [] (:services p)))
          (is (= :symlink (-> p :worktree :strategy)))
          (is (= "/tmp/x" (-> p :worktree :target))))))))

(deftest resolve-unknown-profile-throws
  (with-tmp
    (fn [_]
      (is (thrown? clojure.lang.ExceptionInfo
                   (profiles/resolve-profile :brian :nope))))))

(deftest resolve-expands-tilde-in-symlink-target
  (with-tmp
    (fn [tmp]
      (let [path (str (fs/path tmp "projects" "brian" "session-profiles.edn"))]
        (io/write-edn! path
          {:profiles {:lite {:services []
                             :worktree {:strategy :symlink :target "~/Code/brian"}}}})
        (let [p (profiles/resolve-profile :brian :lite)]
          (is (.startsWith (-> p :worktree :target) (System/getProperty "user.home"))
              "leading ~ should be expanded to the user's home dir"))))))
```

- [ ] **Step 3: Run, verify fail** — `bb test test/nido/session/profiles_test.clj`.

- [ ] **Step 4: Implement**

```clojure
(ns nido.session.profiles
  "Per-project session profile registry. Loads
   ~/.nido/projects/<project>/session-profiles.edn (if present) and
   resolves a profile keyword to a concrete shape:

     {:services <:all | [<service-type-kw>...]>
      :worktree {:strategy <:git-worktree | :symlink>
                 :target   <abs-path>     ; symlink only
                 }}

   The default registry (used when no file exists) defines only :full
   with :services :all and :strategy :git-worktree — preserving the
   pre-profiles behavior."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.io :as io]
   [nido.coordinator.state :as cstate]))

(def builtin-registry
  {:profiles
   {:full {:services :all
           :worktree {:strategy :git-worktree}}}})

(defn- registry-path [project]
  (str (fs/path (cstate/nido-root) "projects" (name project) "session-profiles.edn")))

(defn- expand-tilde [s]
  (if (and (string? s) (str/starts-with? s "~/"))
    (str (fs/path (System/getProperty "user.home") (subs s 2)))
    s))

(defn- normalise-profile [p]
  (cond-> p
    (-> p :worktree :target) (update-in [:worktree :target] expand-tilde)))

(defn load-registry [project]
  (let [path (registry-path project)]
    (if (fs/exists? path)
      (io/read-edn path)
      builtin-registry)))

(defn resolve-profile
  "Resolve a profile keyword (e.g. :full, :lite) for a project."
  [project profile-kw]
  (let [{:keys [profiles]} (load-registry project)]
    (or (some-> (get profiles profile-kw) normalise-profile)
        (throw (ex-info (str "Unknown session profile " profile-kw " for project " project)
                        {:project project :profile profile-kw
                         :known (keys profiles)})))))
```

- [ ] **Step 5: Run, verify all 4 tests pass.**

- [ ] **Step 6: Run full suite** — `bb test`. 169/0/0 expected.

- [ ] **Step 7: jj hygiene check + commit.** `jj log -r '@-..@' --no-graph` (confirm `@` has YOUR diff, parent is Plan A's tip `5d12f273`). Then:

```
jj describe @ -m "feat(session/profiles): per-project profile registry

New nido.session.profiles ns. Loads
~/.nido/projects/<project>/session-profiles.edn (if present);
otherwise returns a built-in :full default that preserves pre-profile
behavior. Resolver normalises tilde paths and throws on unknown
profile keywords. Not wired into session lifecycle yet — see next
task."
```

---

## Task 2 — Thread `:session-profile` through `lifecycle/up!` and `engine/start-session!`

**Files:**
- Modify: `src/nido/session/lifecycle.clj` (`up!` at line 257-271)
- Modify: `src/nido/session/engine.clj` (`start-session!`, `start-services!`)

Plumb a resolved profile (a map, not the keyword) from `up!` down to `start-services!`. The keyword arrives via opts (`{:session-profile :lite}`); resolution happens in `up!` so `start-services!` and downstream code work with a pre-validated map.

The profile field name: **`:session-profile`** in opts and Run records (matches the Trigger schema field). The resolved map travels under **`:profile`** inside the session context (since "session-profile" is a mouthful and `:profile` is unambiguous in that scope).

- [ ] **Step 1: jj hygiene check.** `jj new` if needed.

- [ ] **Step 2: Read the actual code**

```
grep -n "defn up!\|defn start-session!\|defn start-services!" src/nido/session/lifecycle.clj src/nido/session/engine.clj
```

Read `up!` (line 257-271), `start-session!`, and `start-services!` (line 257-305 in engine.clj per the survey — verify). Note: engine.clj and lifecycle.clj both have a function around line 257; do not confuse them.

- [ ] **Step 3: Write a failing test for filter behavior**

Create `test/nido/session/engine_test.clj` if it doesn't exist. Assert that when a resolved profile declares `:services [:postgresql]`, only postgres-typed services from the session.edn are started:

```clojure
(ns nido.session.engine-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.session.engine :as engine]))

(def fake-session-edn
  {:services [{:type :postgresql :name :pg}
              {:type :process    :name :repl}
              {:type :eval       :name :app}]})

(deftest filter-services-by-profile-allowlist
  (testing ":all returns every service"
    (is (= 3 (count (engine/filter-services (:services fake-session-edn) :all)))))
  (testing "empty allowlist returns nothing"
    (is (= 0 (count (engine/filter-services (:services fake-session-edn) [])))))
  (testing "specific allowlist returns matching :type"
    (is (= [:postgresql]
           (mapv :type (engine/filter-services (:services fake-session-edn) [:postgresql]))))))
```

- [ ] **Step 4: Run, verify fail** — `bb test test/nido/session/engine_test.clj`.

- [ ] **Step 5: Implement `filter-services` in `engine.clj`**

Add (placement: near `start-services!`):

```clojure
(defn filter-services
  "Filter a session.edn :services list by an allowlist. Allowlist is
   either :all (return everything) or a vector of allowed :type values."
  [services allowlist]
  (cond
    (= :all allowlist) (vec services)
    (vector? allowlist) (filterv #(contains? (set allowlist) (:type %)) services)
    :else (throw (ex-info "Invalid services allowlist" {:allowlist allowlist}))))
```

- [ ] **Step 6: Run tests, verify pass.**

- [ ] **Step 7: Wire the filter into `start-services!`**

Modify `start-services!` (engine.clj line 257-305). It currently reduces over `(:services session-edn)`. Change to:

```clojure
(let [profile        (:profile ctx)
      allow          (or (:services profile) :all)
      services       (filter-services (:services session-edn) allow)]
  (reduce
    (fn [{:keys [ctx service-states]} svc-def]
      ...))
  ...services...)
```

The `ctx` map already gets threaded around; add `:profile <resolved-map>` to it at the call site in `start-session!`.

- [ ] **Step 8: Update `engine/start-session!`** to accept `:profile` in opts and place it on the context:

```clojure
(defn start-session! [wt-path {:keys [session-name profile] :as opts}]
  (let [...
        ctx (assoc base-ctx :profile (or profile {:services :all
                                                  :worktree {:strategy :git-worktree}}))]
    ...))
```

The default-when-nil keeps existing manual `session:up` calls working unchanged.

- [ ] **Step 9: Resolve the profile in `lifecycle/up!`**

`lifecycle/up!` (line 257-271) takes `opts` that may include `:session-profile :lite` (keyword). Resolve it once, pass the resulting map under `:profile`:

```clojure
(defn up! [name opts]
  (let [{:keys [project ...]} (with-context name opts)
        profile-kw (or (:session-profile opts) :full)
        profile    (profiles/resolve-profile project profile-kw)
        ...]
    ;; existing worktree creation
    (engine/start-session! wt-path
                           (assoc opts :session-name name :profile profile))))
```

Add `[nido.session.profiles :as profiles]` to the requires.

- [ ] **Step 10: Run full suite** — `bb test`. 170+/0/0 expected.

- [ ] **Step 11: jj hygiene + commit**

```
jj describe @ -m "feat(session): thread :session-profile through lifecycle and engine

lifecycle/up! resolves the profile keyword once via
profiles/resolve-profile; engine/start-session! receives the resolved
map on ctx; engine/filter-services applies the profile's :services
allowlist before start-services! reduces. Default profile is :full
(preserves prior behavior); manual `bb nido:session:up` calls without
:session-profile pick up :full automatically."
```

---

## Task 3 — Worktree strategy: `:symlink` branch in `lifecycle/up!`

**Files:**
- Modify: `src/nido/session/lifecycle.clj` (the worktree creation branch around lines 265-271)
- Test: `test/nido/session/lifecycle_test.clj` (create if missing)

Today `up!` calls `create-git-worktree!` (line 135) or `create-jj-workspace!` (line 203) for new worktrees, depending on VCS. Add a third branch: if `(-> profile :worktree :strategy) = :symlink`, create a symlink from `wt-path` to the profile's `:target` instead.

- [ ] **Step 1: jj hygiene check.**

- [ ] **Step 2: Write a failing test**

Create `test/nido/session/lifecycle_test.clj`:

```clojure
(ns nido.session.lifecycle-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.session.lifecycle :as lifecycle]))

(deftest symlink-worktree-creates-symlink-to-target
  (let [tmp    (fs/create-temp-dir)
        target (fs/create-dirs (str (fs/path tmp "fake-checkout")))
        wt     (str (fs/path tmp "wt-link"))]
    (try
      (lifecycle/create-symlink-worktree! wt (str target))
      (is (fs/sym-link? wt) "wt-link should be a symlink")
      (is (= (str (fs/real-path target))
             (str (fs/real-path wt)))
          "symlink should resolve to the target dir")
      (finally (fs/delete-tree tmp)))))

(deftest symlink-worktree-refuses-if-target-missing
  (let [tmp (fs/create-temp-dir)
        wt  (str (fs/path tmp "wt-link"))]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (lifecycle/create-symlink-worktree! wt "/no/such/path")))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 3: Run, verify fail.**

- [ ] **Step 4: Implement `create-symlink-worktree!`**

In `src/nido/session/lifecycle.clj`, near `create-git-worktree!` (line 135):

```clojure
(defn create-symlink-worktree!
  "Symlink wt-path to an existing checkout at target. Refuses if target
   is missing — we never silently point at non-existent code."
  [wt-path target]
  (when-not (fs/exists? target)
    (throw (ex-info (str "Symlink worktree target does not exist: " target)
                    {:wt-path wt-path :target target})))
  (fs/create-dirs (str (fs/parent wt-path)))
  (when (fs/exists? wt-path)
    (fs/delete wt-path))               ; replace stale symlink if present
  (fs/create-sym-link wt-path target)
  wt-path)
```

- [ ] **Step 5: Run unit tests, verify pass.**

- [ ] **Step 6: Wire the branch into `up!`**

Edit the worktree creation block in `lifecycle/up!`. Today (roughly):

```clojure
(when-not (fs/exists? wt-path)
  (cond
    (jj-project? project-dir) (create-jj-workspace! ...)
    :else                     (create-git-worktree! ...)))
```

Add a third branch BEFORE the VCS dispatch (since the symlink strategy overrides VCS detection):

```clojure
(when-not (fs/exists? wt-path)
  (cond
    (= :symlink (-> profile :worktree :strategy))
    (create-symlink-worktree! wt-path (-> profile :worktree :target))

    (jj-project? project-dir) (create-jj-workspace! ...)
    :else                     (create-git-worktree! ...)))
```

`profile` is the resolved profile from Task 2; if you haven't bound it yet at this point in `up!`, do so via `(profiles/resolve-profile project (or (:session-profile opts) :full))`. (It's fine to resolve once at the top of `up!` and reuse — that's what Task 2 already arranged.)

- [ ] **Step 7: Run full suite** — `bb test`.

- [ ] **Step 8: jj hygiene + commit**

```
jj describe @ -m "feat(session/lifecycle): :symlink worktree strategy

When the resolved profile's :worktree.strategy is :symlink, up!
creates ln -s wt-path → target instead of git-worktree-add or
jj-workspace. Refuses if target is missing (no silent dangling
links). Used by the :lite profile to point at a shared mainline
checkout for read-only triage Runs."
```

---

## Task 4 — `Trigger` schema accepts `:session-profile`

**Files:**
- Modify: `src/nido/coordinator/triggers.clj` (the `Trigger` schema)
- Test: `test/nido/coordinator/triggers_test.clj` (append)

Same shape as the Plan A `:priority` addition.

- [ ] **Step 1: jj hygiene check.**

- [ ] **Step 2: Failing test**

Append:

```clojure
(deftest schema-accepts-session-profile
  (is (m/validate triggers/Trigger
                  (assoc minimal-trigger :session-profile :lite))))

(deftest schema-rejects-non-keyword-session-profile
  (is (not (m/validate triggers/Trigger
                       (assoc minimal-trigger :session-profile "lite")))))
```

- [ ] **Step 3: Run, verify fail.**

- [ ] **Step 4: Add field to schema**

In `src/nido/coordinator/triggers.clj`, in `Trigger`:

```clojure
[:session-profile {:optional true} keyword?]
```

Place adjacent to `:priority` (also optional, also coordinator-side runtime hint).

- [ ] **Step 5: Run, verify pass.**

- [ ] **Step 6: jj hygiene + commit**

```
jj describe @ -m "feat(coordinator/triggers): accept optional :session-profile

Triggers can declare which session profile (e.g. :lite) to spawn for
each Run. Defaults to :full at the spawn site if absent."
```

---

## Task 5 — Run record carries `:session-profile`; coordinator spawns with it

**Files:**
- Modify: `src/nido/coordinator/runs.clj` (`Run` schema + `create-run!` + `spawn-session-for-run!`)
- Test: `test/nido/coordinator/runs_test.clj` (append)

Like Plan A Task 3 did for `:priority`: schema gains the field, `create-run!` persists it from the fire-request, `spawn-session-for-run!` reads it from the Run and passes to `up!`.

- [ ] **Step 1: jj hygiene check.**

- [ ] **Step 2: Failing test**

Append to `test/nido/coordinator/runs_test.clj`:

```clojure
(deftest create-run-carries-session-profile
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [run (runs/create-run!
                    {:project :p
                     :trigger {:name :t :skill :triage-bug
                               :payload "x" :source {:type :test}
                               :session-profile :lite}
                     :payload {}
                     :priority 0
                     :session-profile :lite}
                    {})]
          (is (= :lite (:session-profile run)))))
      (finally (fs/delete-tree tmp)))))

(deftest create-run-defaults-session-profile-to-full
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [run (runs/create-run!
                    {:project :p
                     :trigger {:name :t :skill :noop
                               :payload "x" :source {:type :test}}
                     :payload {}
                     :priority 0}
                    {})]
          (is (= :full (:session-profile run))
              "Runs without an explicit :session-profile should default to :full")))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 3: Run, verify fail.**

- [ ] **Step 4: Add to Run schema**

In `src/nido/coordinator/runs.clj`, in the `Run` schema, after `:priority`:

```clojure
[:session-profile keyword?]
```

Required, like `:priority`. Default 0 → default `:full` is the parallel pattern.

- [ ] **Step 5: Persist in `create-run!`**

Add to the destructuring and to the `run` map:

```clojure
:session-profile (or session-profile :full)
```

Where `session-profile` comes from the destructured input (Task 4 already put it on the fire-request via routing).

- [ ] **Step 6: Update `events/route` to propagate `:session-profile`**

(Same pattern as Plan A Task 3 for `:priority`.) In each fire-request constructor in `events.clj`, add:

```clojure
:session-profile (:session-profile t)
```

(No default at this layer — let `create-run!` apply `:full`.)

- [ ] **Step 7: Update `spawn-session-for-run!` to pass the profile to `up!`**

In `src/nido/coordinator/runs.clj` (lines 140-156):

```clojure
(defn spawn-session-for-run! [run]
  (let [{:keys [project session-name id session-profile]} run
        result       (session-lifecycle/up!
                       session-name
                       {:project         project
                        :owned-by-run    id
                        :session-profile session-profile})
        ...]))
```

- [ ] **Step 8: Update the migration backfill** in `runs/read-run` from Plan A. Pre-Plan-B Runs lack `:session-profile`. Extend the backfill:

```clojure
(some-> (io/read-edn path)
        (update :priority         #(if (int? %) % 0))
        (update :session-profile  #(if (keyword? %) % :full)))
```

- [ ] **Step 9: Run full suite** — `bb test`. Watch for fixture updates needed in `runs_test.clj`, `reconcile_test.clj`, `runs_view_test.clj` — same closed-schema pattern as Plan A.

- [ ] **Step 10: jj hygiene + commit**

```
jj describe @ -m "feat(coordinator): propagate :session-profile to Run and session spawn

events/route stamps each fire-request with the trigger's
:session-profile; create-run! persists it on the Run (default :full);
spawn-session-for-run! passes it to lifecycle/up!. read-run backfills
the field for legacy Runs (same migration pattern as :priority)."
```

---

## Task 6 — CLI `bb nido:session:up` accepts `:session-profile`

**Files:**
- Modify: `src/tasks/nido_session.clj` (the `up` task)

Manual CLI parity with the coordinator-spawn path. Accept `:session-profile <kw>` from CLI args; default `:full` (no flag = current behavior).

- [ ] **Step 1: jj hygiene check.**

- [ ] **Step 2: Find the `up` task arg parser**

```
grep -n "defn up\|task-args" src/tasks/nido_session.clj
```

Identify how `:project`, `:jvm-heap-max`, and friends are parsed today. Add `:session-profile` to the recognized keys.

- [ ] **Step 3: Failing test (optional — if `nido_session.clj` has no tests yet, skip the test and rely on manual smoke)**

If there's a test ns for tasks/nido_session, add an arg-parsing test. Otherwise, document the new flag in the function's docstring and verify by hand.

- [ ] **Step 4: Wire the flag**

The task ultimately calls `lifecycle/up! session opts` — make sure `opts` includes `:session-profile (or (:session-profile parsed-args) :full)`.

- [ ] **Step 5: Run full suite.**

- [ ] **Step 6: Manual smoke — accept the CLI flag**

```
bb nido:session:up :project brian smoke-lite :session-profile :lite 2>&1 | head -20
```

If the profile registry doesn't yet declare `:lite` for the brian project (it doesn't until Task 7), expect an "Unknown session profile :lite" error from `profiles/resolve-profile`. That's the right failure mode — confirms the flag reached the resolver.

- [ ] **Step 7: jj hygiene + commit**

```
jj describe @ -m "feat(tasks/session): :session-profile flag on bb nido:session:up

Manual CLI parity with coordinator-spawned Runs. Defaults to :full
when absent. Unknown profile keywords surface as a clear error from
profiles/resolve-profile."
```

---

## Task 7 — Register the brian `:lite` profile

**Files:**
- New: `~/.nido/projects/brian/session-profiles.edn`

This isn't a code change — it's a runtime config file. Document its contents here so the engineer can create it during plan execution.

- [ ] **Step 1: Create the file**

```clojure
{:profiles
 {:full
  {:services :all
   :worktree {:strategy :git-worktree}}

  :lite
  {:services []
   :worktree {:strategy :symlink
              :target   "~/Code/brian"}}}}
```

Path: `~/.nido/projects/brian/session-profiles.edn`.

- [ ] **Step 2: Verify the resolver loads it**

```
bb -e "(require '[nido.session.profiles :as p]) (prn (p/resolve-profile :brian :lite))"
```

Expected: a printed map with `:services []` and the expanded `:worktree.target` (absolute path under `/Users/<you>/Code/brian`).

- [ ] **Step 3: Not a commit step.** This file lives outside the repo (it's per-machine runtime config). No `jj describe` for this task — just verify it exists and works.

---

## Task 8 — `reset!` and `destroy!` lite-aware cleanup

**Files:**
- Modify: `src/nido/session/lifecycle.clj` (`reset!` line 290-308, `destroy!` line 310-339)

For lite sessions:
- `reset!` should NOT drop PGDATA (no PG to drop) — but currently the code is unconditional. Make the drop conditional on the PG service having actually been started.
- `destroy!` should remove the symlink (not the target!) and skip PG state-dir drop.

The trick: at `reset!`/`destroy!` time, we don't directly know the profile. We know the session-name. Two options:

(a) Re-resolve the profile via the project (read `session-profiles.edn` again).
(b) Persist the profile in `~/.nido/state/<instance-id>/session.edn` at session-up time so cleanup can read it without re-resolving.

(b) is more robust (the registry might have been edited since session-up). Implement (b).

- [ ] **Step 1: jj hygiene check.**

- [ ] **Step 2: Persist the profile alongside session state**

At the end of `engine/start-session!`, write the resolved profile to `~/.nido/state/<instance-id>/profile.edn`. Add two helpers in `engine.clj`:

```clojure
(defn- profile-path [instance-id]
  (str (fs/path (state/instance-state-dir instance-id) "profile.edn")))

(defn write-profile-for-session! [wt-path profile]
  (let [path (profile-path (resolve-instance-id wt-path))]
    (fs/create-dirs (fs/parent path))
    (io/write-edn! path profile)))

(defn read-profile-for-session
  "Return the resolved profile persisted at session-up time. nil if absent
   (e.g. legacy sessions predating this feature)."
  [wt-path]
  (let [path (profile-path (resolve-instance-id wt-path))]
    (when (fs/exists? path)
      (io/read-edn path))))
```

Call `write-profile-for-session!` once `start-services!` succeeds (so we only persist for sessions that actually came up).

Add a tiny test that round-trips:

```clojure
(deftest profile-persists-across-read
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [wt (str (fs/path tmp "wt"))
              p  {:services [] :worktree {:strategy :symlink :target "/tmp/x"}}]
          (fs/create-dirs wt)
          (engine/write-profile-for-session! wt p)
          (is (= p (engine/read-profile-for-session wt)))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 3: Failing test**

In `test/nido/session/lifecycle_test.clj`:

```clojure
(deftest destroy-lite-removes-symlink-not-target
  (let [tmp    (fs/create-temp-dir)
        target (fs/create-dirs (str (fs/path tmp "shared-checkout")))
        wt     (str (fs/path tmp "wt-link"))]
    (try
      (lifecycle/create-symlink-worktree! wt (str target))
      ;; simulate destroy on a symlink-style worktree:
      (lifecycle/remove-symlink-worktree! wt)
      (is (not (fs/exists? wt)) "symlink removed")
      (is (fs/exists? target) "target NOT removed")
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 4: Implement `remove-symlink-worktree!`**

```clojure
(defn remove-symlink-worktree!
  "Delete the symlink at wt-path. Refuses to recurse — the symlink
   target is shared state we never own."
  [wt-path]
  (when (and (fs/exists? wt-path) (fs/sym-link? wt-path))
    (fs/delete wt-path)))
```

- [ ] **Step 5: Branch in `destroy!`**

```clojure
(let [profile (engine/read-profile-for-session wt-path)]
  (cond
    (= :symlink (-> profile :worktree :strategy))
    (lifecycle/remove-symlink-worktree! wt-path)

    (jj-workspace? wt-path)
    (remove-jj-workspace! ...)

    :else
    (remove-git-worktree! ...)))
```

- [ ] **Step 6: Make PGDATA drop conditional in `reset!`**

The current code does:

```clojure
(let [pg-data (state/pg-data-dir ...)]
  (when (fs/exists? pg-data) ...drop...))
```

The `when (fs/exists? pg-data)` already gates correctly for lite (no PGDATA → no drop). Verify by reading the actual code; if the gate is missing, add it.

- [ ] **Step 7: Run full suite.**

- [ ] **Step 8: jj hygiene + commit**

```
jj describe @ -m "feat(session/lifecycle): lite-aware reset! and destroy!

destroy! reads the persisted profile (state/<instance-id>/profile.edn)
and removes the symlink (not the target!) for :symlink worktrees.
reset! already gated PGDATA drop on existence — lite sessions never
create PGDATA, so the gate handles them implicitly."
```

---

## Task 9 — End-to-end smoke for a lite session

**Files:** none (verification only)

Bring up a lite session via the CLI, verify no PG/JVM starts, verify the worktree is a symlink. No code changes — if a step fails, report BLOCKED.

- [ ] **Step 1: Confirm Task 7's registry file is in place**

```
cat ~/.nido/projects/brian/session-profiles.edn
```

Should show the two profiles.

- [ ] **Step 2: Tear down any stale session named smoke-lite**

```
bb nido:session:destroy :project brian smoke-lite 2>/dev/null || true
```

- [ ] **Step 3: Bring up the lite session**

```
bb nido:session:up :project brian smoke-lite :session-profile :lite 2>&1 | tail -20
```

Expected output: log lines about creating the symlink, NO `pg_ctl` lines, NO JVM/REPL startup lines. Final line should print the session-home path.

- [ ] **Step 4: Verify the session-home shape**

```
ls -la ~/.nido/sessions/brian/smoke-lite/
readlink ~/.nido/sessions/brian/smoke-lite/worktree
cat ~/.nido/sessions/brian/smoke-lite/.mcp.json 2>&1
```

Expected:
- `worktree` is a symlink pointing at `/Users/<you>/Code/brian` (or wherever `:target` resolves).
- `.mcp.json` exists but does NOT contain a `postgres` MCP entry (it should be either empty `{:mcpServers {}}` or absent if the launcher decides not to write empty files).
- `CLAUDE.md` exists and does NOT mention a pg-port.

- [ ] **Step 5: Verify no PG / JVM process exists for this session**

```
ps aux | grep -E "smoke-lite|nido-brian-smoke-lite" | grep -v grep
```

Expected: empty.

- [ ] **Step 6: Tear down**

```
bb nido:session:destroy :project brian smoke-lite 2>&1 | tail -5
ls -la ~/Code/brian | head -3       # confirm the target is still there
```

Expected: the session-home and the symlink are gone; `~/Code/brian` is untouched.

If any expectation fails, report BLOCKED with the failing step + observed output.

---

## Self-review — spec coverage check

| Spec requirement | Task |
|---|---|
| Profile registry file `~/.nido/projects/<project>/session-profiles.edn` | Task 1 (loader) + Task 7 (file) |
| `:full` and `:lite` profiles | Task 7 (registry contents) |
| Resolver throws on unknown profile | Task 1 |
| `:lite` skips `:postgresql` and `:process` services | Task 2 (filter-services) + Task 7 (`:services []`) |
| `:lite` worktree is a symlink to a target dir | Task 3 |
| Trigger schema accepts `:session-profile` | Task 4 |
| Coordinator spawn path passes profile to `lifecycle/up!` | Task 5 |
| CLI `bb nido:session:up :session-profile :lite` works | Task 6 |
| `reset!` skips PGDATA drop for lite | Task 8 (existence gate) |
| `destroy!` removes symlink, preserves target | Task 8 |
| `.mcp.json` skips postgres entry for lite | **already true** — `launcher/write-artifacts!` gates on `pg-svc` (no change needed) |
| `CLAUDE.md` omits pg-port for lite | **already true** — `render-context` interpolates nil → blank |
| Migration: pre-Plan-B Runs lack `:session-profile` | Task 5 (read-run backfill extended) |

No placeholders. No TBDs. All field names referenced in later tasks (`:session-profile` on the Trigger, on the Run, in opts to `up!`) are defined in the tasks that introduce them.
