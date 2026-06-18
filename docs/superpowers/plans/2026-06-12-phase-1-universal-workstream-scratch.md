# Phase 1: Universal Workstream + Scratch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **VCS:** This is a **jj** (Jujutsu) repo — `.jj/` exists. ALWAYS activate the `jujutsu` skill before any VCS operation; git commands corrupt jj data. Commit steps below use `jj`. Each task = one logical commit. Before each task run `jj st`; if `@` already has unrelated changes, `jj new` first. Subagents: script `jj new` + a before/after `jj log` check so you amend a fresh change, not the previous commit.

**Goal:** Make *every* manually-launched session belong to a workstream — a `bb nido:session:up` births a loose ("scratch") workstream + a human session; `destroy` reaps it when it never grew a ref or a ledger entry — so the universal-workstream model holds before any TUI work.

**Architecture:** Loose-workstream policy lives in a new coordinator-side namespace `nido.coordinator.scratch` (it can't live in `nido.session.*` — the coordinator already requires `nido.session.lifecycle`, so a back-edge would cycle). The manual-only seam is the **task layer** (`tasks.nido-session`), the one place that legitimately sees both the session lifecycle and the coordinator workstream model. Coordinator-spawned sessions are unaffected — they never go through the task; they already mint their own workstream in `spawn/spawn-records!`. The TUI is **not touched** in this phase: the `:sessions` screen keeps reading the registry exactly as today, so nothing visible changes; loose workstreams accrete underneath for Phase 2 to render.

**Tech Stack:** Babashka, Clojure, `clojure.test`, Malli (existing `Workstream`/`Session` schemas), `babashka.fs`.

---

## File Structure

- **Create** `src/nido/coordinator/scratch.clj` — the loose-workstream policy: `scratch?`, `find-ws-for-session`, `birth!`, `reap!`. One responsibility: deciding when a one-off session gets/loses a workstream. Depends only on `nido.coordinator.workstream` + `nido.coordinator.session`.
- **Create** `test/nido/coordinator/scratch_test.clj` — TDD for the above.
- **Modify** `src/tasks/nido_session.clj` — `up` births, `destroy` reaps.
- **Create** `test/tasks/nido_session_test.clj` — wiring tests (stub lifecycle + scratch).
- **Create** `src/tasks/nido_scratch.clj` — the `backfill` task (one-time migration for pre-existing manual sessions).
- **Create** `test/tasks/nido_scratch_test.clj` — wiring test for backfill.
- **Modify** `bb.edn` — register `tasks.nido-scratch` + the `nido:scratch:backfill` task.

---

## Task 1: `nido.coordinator.scratch` — loose-workstream policy

**Files:**
- Create: `src/nido/coordinator/scratch.clj`
- Test: `test/nido/coordinator/scratch_test.clj`

- [ ] **Step 1: Write the failing tests**

Create `test/nido/coordinator/scratch_test.clj`:

```clojure
(ns nido.coordinator.scratch-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as workstream]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest birth-creates-loose-workstream-with-human-session
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot")]
        (is (some? ws-id))
        (let [w (workstream/read-ws :brian ws-id)]
          (is (scratch/scratch? w) "no external refs")
          (is (= :scratch (:stage w))))
        (let [ss (session/list-sessions :brian ws-id)]
          (is (= ["refshot"] (mapv :name ss)))
          (is (nil? (:autonomy (first ss))) "human session"))))))

(deftest birth-is-idempotent
  (with-tmp
    (fn [_]
      (let [a (scratch/birth! :brian "refshot")
            b (scratch/birth! :brian "refshot")]
        (is (= a b) "same ws-id, no second workstream")
        (is (= 1 (count (workstream/list-ids :brian))))
        (is (= 1 (count (session/list-sessions :brian a))))))))

(deftest find-ws-for-session-locates-owner
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot")]
        (is (= ws-id (scratch/find-ws-for-session :brian "refshot")))
        (is (nil? (scratch/find-ws-for-session :brian "nope")))))))

(deftest reap-deletes-a-bare-loose-workstream
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot")]
        (scratch/reap! :brian "refshot")
        (is (nil? (workstream/read-ws :brian ws-id)))
        (is (empty? (workstream/list-ids :brian)))))))

(deftest reap-spares-a-workstream-with-a-ref
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot")]
        (workstream/add-ref! :brian ws-id {:adapter :notion :id "BR-1"})
        (scratch/reap! :brian "refshot")
        (is (some? (workstream/read-ws :brian ws-id)) "ref ⇒ not reaped")))))

(deftest reap-spares-a-workstream-with-entries
  (with-tmp
    (fn [_]
      (let [ws-id (scratch/birth! :brian "refshot")]
        (workstream/append-entry! :brian ws-id {:kind :note} "hi")
        (scratch/reap! :brian "refshot")
        (is (some? (workstream/read-ws :brian ws-id)) "entry ⇒ not reaped")))))

(deftest reap-is-a-noop-when-absent
  (with-tmp
    (fn [_]
      (is (nil? (scratch/reap! :brian "ghost"))))))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bb nido:test :only nido.coordinator.scratch-test` (or `clojure -M:test -n nido.coordinator.scratch-test` if that's the project runner — check `bb nido:test` first)
Expected: FAIL — `No namespace: nido.coordinator.scratch` (the ns doesn't exist yet).

- [ ] **Step 3: Write the implementation**

Create `src/nido/coordinator/scratch.clj`:

```clojure
(ns nido.coordinator.scratch
  "Loose ('scratch') workstreams: the home for one-off, manually-launched
   sessions that carry no external ref. A manual `bb nido:session:up` births a
   loose workstream + a human session here so that *every* session belongs to a
   workstream (the universal-workstream model — see spec
   docs/superpowers/specs/2026-06-12-intent-organized-tui-and-github-intake-design.md).
   `destroy` reaps it when it never grew a ref or a ledger entry, keeping
   one-offs zero-ceremony.

   Lives coordinator-side (not in nido.session.*) to avoid a namespace cycle:
   the coordinator already depends on nido.session.lifecycle, so the wiring is
   done at the task layer (tasks.nido-session) — the only place that sees both."
  (:require
   [nido.coordinator.session :as session]
   [nido.coordinator.workstream :as workstream]))

(defn scratch?
  "A loose workstream: one carrying no external refs. Source-agnostic — Notion
   and GitHub workstreams both carry refs, so 'no refs' uniquely marks a one-off."
  [w]
  (empty? (:external-refs w)))

(defn find-ws-for-session
  "ws-id of the workstream whose sessions include `session-name`, or nil. Scans
   the project's workstreams (same shape as workstream/find-by-ref)."
  [project session-name]
  (->> (workstream/list-ids project)
       (some (fn [ws-id]
               (when (some #(= session-name (:name %))
                           (session/list-sessions project ws-id))
                 ws-id)))))

(defn birth!
  "Ensure a loose workstream owns a human session named `session-name`.
   Idempotent: if any workstream already owns that session, returns its ws-id
   unchanged. Otherwise mints a ref-less :scratch-stage workstream and a live
   human session (autonomy nil). Returns the ws-id."
  [project session-name]
  (or (find-ws-for-session project session-name)
      (let [w (workstream/create! project {:stage :scratch :external-refs []})]
        (session/create! project (:id w)
                         {:name session-name :weight :light :autonomy nil})
        (:id w))))

(defn reap!
  "Delete the loose workstream owning `session-name` when it is safe to discard:
   it is scratch (no refs), carries no ledger entries, and owns no session other
   than this one. No-op when absent or not reapable (it grew a ref/entry, or
   another session shares it). Idempotent. Returns nil."
  [project session-name]
  (when-let [ws-id (find-ws-for-session project session-name)]
    (let [w      (workstream/read-ws project ws-id)
          others (->> (session/list-sessions project ws-id)
                      (remove #(= session-name (:name %))))]
      (when (and (scratch? w)
                 (empty? (:entries w))
                 (empty? others))
        (workstream/delete! project ws-id))))
  nil)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `bb nido:test :only nido.coordinator.scratch-test`
Expected: PASS — all 7 tests green.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(scratch): loose-workstream birth/reap policy

A one-off session now gets a ref-less :scratch workstream + human session;
reaped on destroy only when it never grew a ref or ledger entry."
```

---

## Task 2: Wire `up` to birth, `destroy` to reap

**Files:**
- Modify: `src/tasks/nido_session.clj:31-34` (require), `:54-70` (up), `:91-98` (destroy)
- Test: `test/tasks/nido_session_test.clj`

- [ ] **Step 1: Write the failing wiring tests**

Create `test/tasks/nido_session_test.clj`:

```clojure
(ns tasks.nido-session-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [tasks.nido-session :as task]))

(deftest up-births-a-loose-workstream-for-the-session
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!          (fn [s o] (swap! calls conj [:up s o]))
                  state/session-home-dir (fn [_ _] "/tmp/home")
                  scratch/birth!         (fn [p s] (swap! calls conj [:birth p s]))]
      (task/up :project "brian" "refshot")
      (is (some #(= [:up "refshot" {}] %) @calls) "lifecycle up still runs")
      (is (some #(= [:birth :brian "refshot"] %) @calls) "loose workstream born"))))

(deftest destroy-reaps-the-loose-workstream
  (let [calls (atom [])]
    (with-redefs [lifecycle/destroy! (fn [s o] (swap! calls conj [:destroy s o]))
                  scratch/reap!      (fn [p s] (swap! calls conj [:reap p s]))]
      (task/destroy :project "brian" "refshot")
      (is (some #(= [:destroy "refshot" {}] %) @calls) "lifecycle destroy still runs")
      (is (some #(= [:reap :brian "refshot"] %) @calls) "loose workstream reaped"))))
```

- [ ] **Step 2: Run to verify they fail**

Run: `bb nido:test :only tasks.nido-session-test`
Expected: FAIL — `up` doesn't call `scratch/birth!` and `destroy` doesn't call `scratch/reap!` yet, so the `:birth`/`:reap` assertions fail (no var error if `scratch` already exists from Task 1).

- [ ] **Step 3: Add the require**

In `src/tasks/nido_session.clj`, change the `:require` (lines 31-34) from:

```clojure
  (:require
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [nido.task-args :as task-args]))
```

to:

```clojure
  (:require
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [nido.task-args :as task-args]))
```

- [ ] **Step 4: Birth in `up`**

In `up` (lines 61-70), replace the body after `session` is bound:

```clojure
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/up! session opts)
    (scratch/birth! (keyword project) session)
    (let [home (state/session-home-dir project session)]
      (println)
      (println (str "Session ready: " project "/" session))
      (println (str "  cd " home))
      (println (str "  bb nido:session:enter :project " project " " session)))))
```

- [ ] **Step 5: Reap in `destroy`**

In `destroy` (lines 91-98), bind `project` (not `_project`) and reap after `destroy!`:

```clojure
(defn destroy
  "Bring the named session down and remove its worktree.
   Pass :delete-branch? true to also drop the git branch.
   Reaps the session's loose (scratch) workstream when it never grew a ref or
   ledger entry; a Notion/GitHub workstream (carrying a ref) is left intact."
  [& args]
  (let [[pos opts] (task-args/split-args args)
        project (require-project opts)
        session (require-session-name pos)]
    (lifecycle/destroy! session opts)
    (scratch/reap! (keyword project) session)))
```

- [ ] **Step 6: Run the wiring tests to verify they pass**

Run: `bb nido:test :only tasks.nido-session-test`
Expected: PASS — both tests green.

- [ ] **Step 7: Live verification (one real session)**

Run against a real project (services start — this is the genuine end-to-end check):

```bash
bb nido:session:up :project brian scratch-smoke
bb -e '(require (quote [nido.coordinator.scratch :as s])) (println (s/find-ws-for-session :brian "scratch-smoke"))'
# Expected: prints a ws-YYYYMMDD-xxxxxx id (loose workstream was born)
bb nido:session:destroy :project brian scratch-smoke
bb -e '(require (quote [nido.coordinator.scratch :as s])) (println (s/find-ws-for-session :brian "scratch-smoke"))'
# Expected: prints nil (loose workstream was reaped)
```

- [ ] **Step 8: Commit**

```bash
jj commit -m "feat(session): manual up births a scratch workstream, destroy reaps it

Every manual session now belongs to a workstream; ref-less one-offs stay
zero-ceremony (reaped on destroy). Coordinator-spawned sessions are unaffected
— they never go through the task layer."
```

---

## Task 3: `nido:scratch:backfill` — migrate pre-existing manual sessions

**Why:** sessions already in the registry (the `feat/*`, `refshot`, … in the current TUI) predate this model and own no workstream. Backfill mints a loose workstream for each so the Phase 2 Scratch view shows them — "migrate the existing flat-list semantics so nothing is lost."

**Files:**
- Create: `src/tasks/nido_scratch.clj`
- Test: `test/tasks/nido_scratch_test.clj`
- Modify: `bb.edn:8-33` (require), and add the `nido:scratch:backfill` task entry

- [ ] **Step 1: Write the failing wiring test**

Create `test/tasks/nido_scratch_test.clj`:

```clojure
(ns tasks.nido-scratch-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-scratch :as task]))

(deftest backfill-births-one-loose-workstream-per-session
  (let [births (atom [])]
    (with-redefs [lifecycle/list-all-data (fn [_] {:sessions [{:name "a"} {:name "b"}]})
                  scratch/birth!          (fn [p n] (swap! births conj [p n]) "ws-x")]
      (task/backfill :project "brian")
      (is (= [[:brian "a"] [:brian "b"]] @births)
          "one idempotent birth! per existing session, keyword project"))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only tasks.nido-scratch-test`
Expected: FAIL — `No namespace: tasks.nido-scratch`.

- [ ] **Step 3: Write the task namespace**

Create `src/tasks/nido_scratch.clj`:

```clojure
(ns tasks.nido-scratch
  "One-time migration: give every pre-existing manual session a loose (scratch)
   workstream, so the universal-workstream model holds for sessions that predate
   it. Idempotent — `scratch/birth!` no-ops on sessions already owned by a
   workstream (Notion, GitHub, or an earlier backfill)."
  (:require
   [nido.coordinator.scratch :as scratch]
   [nido.session.lifecycle :as lifecycle]
   [nido.task-args :as task-args]))

(defn backfill
  "Birth a loose workstream for every existing session of `:project` that does
   not yet belong to one. Safe to re-run."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        project  (or (some-> (:project opts) name)
                     (throw (ex-info "Missing :project <name>"
                                     {:hint "Pass :project <project-name>."})))
        {:keys [sessions]} (lifecycle/list-all-data {:project project})]
    (doseq [{:keys [name]} sessions]
      (let [ws-id (scratch/birth! (keyword project) name)]
        (println (format "  %-40s → %s" name ws-id))))
    (println (format "Backfilled %d session(s) for %s" (count sessions) project))))
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `bb nido:test :only tasks.nido-scratch-test`
Expected: PASS.

- [ ] **Step 5: Register the task in `bb.edn`**

Add to the `:requires` vector (after line 32, `tasks.nido-workstream`):

```clojure
             [tasks.nido-scratch :as nido-scratch]
```

Add a task entry near `nido:reclaim` (after line 98, the `nido:session:destroy` block is fine too — keep it next to session verbs):

```clojure
  nido:scratch:backfill
  {:doc "Backfill a loose workstream for every existing session: :project <p> (idempotent)"
   :task (apply nido-scratch/backfill *command-line-args*)}
```

- [ ] **Step 6: Verify the task loads**

Run: `bb tasks | grep scratch`
Expected: lists `nido:scratch:backfill`.

- [ ] **Step 7: Live verification + actual migration**

```bash
bb nido:scratch:backfill :project brian
# Expected: one "<session> → ws-..." line per existing session, then a count.
bb nido:scratch:backfill :project brian
# Expected: identical output — re-run births nothing new (idempotent).
```

- [ ] **Step 8: Commit**

```bash
jj commit -m "feat(scratch): nido:scratch:backfill migration task

Mints a loose workstream for every pre-existing manual session so nothing is
lost when the Scratch view lands in Phase 2. Idempotent."
```

---

## Self-Review

**Spec coverage (Phase 1 slice):**
- "Loose-workstream birth/reaping" → Tasks 1 + 2. ✓
- "Make every session belong to a workstream" → Task 2 (`up` births) + Task 3 (backfill existing). ✓
- "Migrate the existing flat-list semantics so nothing is lost" → Task 3 backfill; TUI untouched so the `:sessions` screen is unchanged. ✓
- "Loose workstreams group by engagement only, not the stage lifecycle" → carried by `:scratch` stage (not in `session/lifecycle-stages`, so it never overrides the engagement-based projection). The grouping itself is rendered in Phase 2; the data is correct now. ✓
- `scratch?` predicate (the Phase 2 view filter) → defined + tested in Task 1. ✓

**Type/name consistency:** `scratch/birth!`, `scratch/reap!`, `scratch/find-ws-for-session`, `scratch/scratch?` are used identically across Tasks 1–3. `birth!` returns a ws-id string; `reap!` returns nil; both take `[project session-name]` with a **keyword** project (call sites pass `(keyword project)`). `workstream/create!`/`read-ws`/`add-ref!`/`append-entry!`/`delete!`/`list-ids` and `session/create!`/`list-sessions` signatures match the existing namespaces verified in source.

**Placeholder scan:** none — every step carries real code/commands.

**Deferred to later phases (flagged, intentionally NOT in Phase 1):**
- **Registry reverse-link** (`:workstream-id` on the registry entry): not needed yet — reap finds the owner by scanning (`find-ws-for-session`). Phase 2 adds the link when the TUI must correlate substrate → workstream.
- **Substrate sync on session down/up:** a loose workstream's human session stays `:substrate :live` even when services are down, so engagement would read `:active`. Harmless in Phase 1 (Scratch view isn't rendered). Phase 2 syncs it.
- **Manual destroy of a *coordinator* session:** `reap!` correctly spares the ref-carrying workstream but leaves a stale coordinator-session record. Pre-existing edge (manually destroying a Run-owned session is already unusual); out of scope here.

---

## Execution Handoff

After this plan is approved, Phases 2–4 each get their own plan (they depend on Phase 1's realities). Phase 2 (multi-view TUI) is where the registry reverse-link and substrate-sync land.
