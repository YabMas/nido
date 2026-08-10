# Real Liveness Oracle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `nido.work/live-session-names` report sessions that are *actually* holding a port, and make the session registry self-heal, so the TUI's "Winding down" band stops filling with months-dead phantoms.

**Architecture:** Three independent changes, in dependency order. (1) The oracle stops trusting `~/.nido/state/sessions.edn` and probes the recorded app/nREPL ports for real — the same technique `work/machine-rows` already uses. (2) `state/remove-from-registry!` learns to prune the legacy `.codex` registries it merges on read, so a deletion actually sticks. (3) The coordinator's throttled adoption sweep drops registry entries that fail the probe, so the registry stops accumulating dead rows. Task 3 depends on Task 1 (the probe predicate) and Task 2 (deletion that sticks).

**Tech Stack:** Babashka (Clojure), `clojure.test`, jj (colocated git), `bb nido:test`.

## Background — why this exists

The registry at `~/.nido/state/sessions.edn` is only ever cleaned by a *graceful* teardown: `engine/stop-session!` → `state/remove-from-registry!` (`src/nido/session/engine.clj:511`). Any other death — reboot, JVM crash, `kill`, OrbStack restart — leaves the entry with its port numbers intact, permanently.

`live-session-names` (`src/nido/work.clj:771`) defines liveness as *"the registry row has a port number in it"*, so those entries read as live forever. Downstream, `work/winding-down` renders a closed workstream still "holding" them as a Winding-down row that never clears.

Measured on this machine before the change: **19 of 23** sessions the oracle called live were dead processes with no listening ports, and **all 12** Winding-down rows were phantoms — some dating to May 2026. Those entries have since been hand-pruned; this plan stops them coming back.

The fix direction is already precedented in the same namespace: `work/machine-rows` (`src/nido/work.clj:864`) computes `live?` as `(and (pos-int? port) (proc/tcp-open? port))`. This plan brings the oracle in line with it.

## Global Constraints

- **Probe signal:** a session is live iff its recorded `:app-port` **or** `:nrepl-port` currently accepts a TCP connection.
- **`:pg-port` is never a liveness signal.** Most sessions point at the project's *shared* cluster (port 6145 here), which is up whenever any one session is — it cannot distinguish one session from another.
- **`:repl-pid` is never a liveness signal.** PIDs are recycled; a months-old entry whose PID got reused would read as live forever, resurrecting exactly the phantoms this work removes.
- Probing is cheap and needs no caching: closed localhost ports refuse instantly (measured: 5 probes of a dead port ≈ 0 ms; `proc/tcp-open?`'s 500 ms timeout only applies to filtered/unreachable hosts, not `127.0.0.1`).
- Tests run with `bb nido:test`, filterable via `:only <ns-prefix>`.
- Commits use **jj**, one logical change each. Start each task with `jj new` on a clean `@`.
- **This plan file is not committed.** It drives the work; it is not the work.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `src/nido/work.clj` | The work-plane core; home of the liveness oracle | Add `session-live?`; rewrite `live-session-names`; add `prune-dead-registry!` |
| `src/nido/session/state.clj` | Session state + the global registry | Make legacy-registry deletion effective |
| `src/nido/coordinator/core.clj` | Coordinator tick loop | Prune dead registry entries on the adoption sweep |
| `test/nido/work_test.clj` | Work-plane tests | Replace the oracle test; add prune tests |
| `test/nido/session/state_test.clj` | Registry tests | Add legacy-deletion test |
| `test/nido/coordinator/core_test.clj` | Tick-loop tests | Assert the sweep prunes |

### Explicit non-goal

`work/machine-rows` keeps its own `live?` (app-port only). It answers a different question — "should I render a URL and RSS for this row?" — and unifying it is a separate change with its own UI blast radius. Leave it alone.

---

### Task 1: The oracle probes ports instead of trusting the registry

**Files:**
- Modify: `src/nido/work.clj:771-779` (`live-session-names`), plus a new `session-live?` immediately above it
- Test: `test/nido/work_test.clj:1245-1251` (replaces `live-session-names-are-the-ones-with-ports`)

**Interfaces:**
- Produces: `(work/session-live? {:app-port int-or-nil :nrepl-port int-or-nil ...}) → boolean` — public, reused by Task 3.
- Produces: `(work/live-session-names project) → #{session-name}` — signature unchanged; semantics now real.

**Note on the existing test:** `live-session-names-are-the-ones-with-ports` (line 1245) *encodes the bug* — it asserts `"up1"` is live purely because it carries `:pg-port 5501`. It must be replaced, not extended. `test/nido/tui_test.clj` stubs `live-session-names` wholesale, so it is unaffected.

- [ ] **Step 1: Start a clean changeset**

```bash
cd ~/Code/nido
jj st                    # if @ has unrelated changes, run: jj new
jj new
jj desc -m "fix(work): make the liveness oracle probe ports instead of trusting the registry"
```

- [ ] **Step 2: Add `nido.process` to the test namespace requires**

In `test/nido/work_test.clj`, the `:require` vector (ends around line 22 with `[nido.work :as work]))`) gains one entry, keeping alphabetical order:

```clojure
   [nido.project]
   [nido.process]
   [nido.session.lifecycle]
   [nido.work :as work]))
```

- [ ] **Step 3: Write the failing test**

Replace the whole of `live-session-names-are-the-ones-with-ports` (`test/nido/work_test.clj:1245-1251`) with:

```clojure
(deftest live-session-names-probe-ports-rather-than-trusting-the-registry
  ;; The registry is only cleaned by a graceful down!; a reboot or a crash leaves
  ;; an entry with its port numbers intact forever. Liveness therefore has to be
  ;; measured, not read.
  (with-redefs [nido.session.lifecycle/list-all-data
                (fn [_] {:sessions [{:name "app-up"    :app-port 3101 :nrepl-port nil  :pg-port nil}
                                    {:name "repl-up"   :app-port nil  :nrepl-port 5601 :pg-port nil}
                                    {:name "crashed"   :app-port 3102 :nrepl-port 5602 :pg-port nil}
                                    {:name "shared-pg" :app-port nil  :nrepl-port nil  :pg-port 6145}
                                    {:name "down"      :app-port nil  :nrepl-port nil  :pg-port nil}]})
                nido.process/tcp-open? (fn [port] (contains? #{3101 5601 6145} port))]
    (is (= #{"app-up" "repl-up"} (work/live-session-names "p")))
    (is (not (contains? (work/live-session-names "p") "crashed"))
        "recorded ports that no longer answer are not liveness")
    (is (not (contains? (work/live-session-names "p") "shared-pg"))
        "pg is never a signal — the shared cluster answers for every session at once")))

(deftest session-live?-reads-app-or-nrepl-only
  (with-redefs [nido.process/tcp-open? (fn [port] (= 4000 port))]
    (is (true?  (work/session-live? {:app-port 4000})))
    (is (true?  (work/session-live? {:nrepl-port 4000})))
    (is (false? (work/session-live? {:app-port 4001 :nrepl-port 4002})))
    (is (false? (work/session-live? {:pg-port 4000}))  "pg excluded")
    (is (false? (work/session-live? {:repl-pid 12345})) "pid excluded — PIDs get recycled")
    (is (false? (work/session-live? {})))))
```

- [ ] **Step 4: Run the tests to verify they fail**

```bash
cd ~/Code/nido && bb nido:test :only nido.work
```

Expected: FAIL — `session-live?` is unresolvable (`No such var: work/session-live?`), and the oracle test fails because the current implementation ignores `tcp-open?` and returns `#{"app-up" "repl-up" "crashed" "shared-pg"}`.

- [ ] **Step 5: Implement**

In `src/nido/work.clj`, replace the existing `live-session-names` (lines 771-779) with:

```clojure
(defn session-live?
  "Does this registry-shaped session map hold a port RIGHT NOW? Probes the
   recorded app/nREPL ports rather than trusting that they were recorded — the
   registry is only cleaned on a graceful `down!` (engine/stop-session! →
   state/remove-from-registry!), so a reboot, a JVM crash or a `kill` leaves an
   entry with its port numbers intact indefinitely.

   `:pg-port` is deliberately not a signal: most sessions point at the project's
   SHARED cluster, which answers whenever any one session is up. `:repl-pid` is
   not a signal either: PIDs are recycled, so a months-old entry whose PID was
   reused would read as live forever."
  [s]
  (boolean (or (and (pos-int? (:app-port s))   (proc/tcp-open? (:app-port s)))
               (and (pos-int? (:nrepl-port s)) (proc/tcp-open? (:nrepl-port s))))))

(defn live-session-names
  "Set of session names for `project` that are actually up — i.e. hold an open
   app/nREPL port right now. THE liveness oracle: the TUI board, the web
   grouping, the adopter, and the winding-down band all read this one fn."
  [project]
  (->> (lifecycle/list-all-data {:project (name project)})
       :sessions
       (filter session-live?)
       (map :name)
       set))
```

`nido.process` is already required as `proc` in this namespace (`src/nido/work.clj:32`) — no require change needed.

- [ ] **Step 6: Run the tests to verify they pass**

```bash
cd ~/Code/nido && bb nido:test :only nido.work
```

Expected: PASS.

- [ ] **Step 7: Run the full suite — the oracle has four call sites**

```bash
cd ~/Code/nido && bb nido:test
```

Expected: PASS. Call sites that must stay green: `tui.clj:227` (board), `work.clj:786` (`bring-down!`), `work.clj:837` (`adopt-orphans!`), `work.clj:912` (web `screen`).

- [ ] **Step 8: Commit**

```bash
cd ~/Code/nido && jj commit -m "fix(work): make the liveness oracle probe ports instead of trusting the registry

The registry is only cleaned by a graceful down!, so a reboot or a crash
left entries with their ports intact forever and the winding-down band
filled with sessions that had been dead for months. Measure liveness
instead: an open app or nREPL port. pg-port is excluded (the shared
cluster answers for everyone) and repl-pid is excluded (PIDs recycle)."
```

---

### Task 2: Deleting a legacy registry entry actually sticks

**Files:**
- Modify: `src/nido/session/state.clj:195-215` (`legacy-registry-paths`, `remove-from-registry!`)
- Test: `test/nido/session/state_test.clj` (append)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `(state/remove-from-registry! project-dir)` — signature unchanged; now effective for legacy-only keys. Task 3 relies on this.
- No visibility changes. `legacy-registry-paths` and `registry-file-path` stay `^:private`: babashka's `with-redefs` reaches private vars in other namespaces (verified in this runtime), so the tests need no seam widening.

**The bug:** `read-registry` (line 201) merges `~/.codex/nido/sessions.edn` and `~/.codex/agent-cockpit/sessions.edn` *under* the canonical file, but `write-registry!` (line 208) only ever writes the canonical file. So `remove-from-registry!` drops the key from the merged map, writes the canonical file without it, and the very next `read-registry` merges it straight back in from the legacy file. A legacy-only entry is immortal. Confirmed on this machine: `/Users/yabmas/.codex/worktrees/6aa4/brian-next` (from Feb 2026, worktree long gone) lived only in `agent-cockpit/sessions.edn`.

- [ ] **Step 1: Start a clean changeset**

```bash
cd ~/Code/nido && jj new
jj desc -m "fix(session): make legacy registry entries deletable"
```

- [ ] **Step 2: Write the failing test**

Append to `test/nido/session/state_test.clj`:

```clojure
(deftest remove-from-registry-also-prunes-legacy-files
  ;; read-registry merges the .codex registries UNDER the canonical file, but
  ;; write-registry! only rewrites the canonical one — so without pruning the
  ;; legacy file too, a legacy-only key comes straight back on the next read.
  (let [tmp    (fs/create-temp-dir)
        legacy (str (fs/path tmp "legacy.edn"))
        canon  (str (fs/path tmp "sessions.edn"))
        k      "/gone/worktree"]
    (try
      (io/write-edn! legacy {k {:instance-id "ghost" :app-port 4938}})
      (io/write-edn! canon {"/live/worktree" {:instance-id "real" :app-port 3000}})
      ;; Fully-qualified symbols, not the `state` alias and not #'— with-redefs
      ;; wraps each name in (var …), which reaches these private vars fine.
      (with-redefs [nido.session.state/legacy-registry-paths (constantly [legacy])
                    nido.session.state/registry-file-path    (delay canon)]
        (is (contains? (state/read-registry) k) "precondition: the legacy key reads back")
        (state/remove-from-registry! k)
        (is (not (contains? (state/read-registry) k))
            "the legacy key is gone and stays gone across a re-read")
        (is (contains? (state/read-registry) "/live/worktree")
            "unrelated canonical entries survive"))
      (finally (fs/delete-tree tmp)))))
```

Add `[babashka.fs :as fs]` and `[nido.io :as io]` to that file's `:require` vector (it currently has only `clojure.string`, `clojure.test`, `nido.session.state`), keeping alphabetical order:

```clojure
(ns nido.session.state-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.io :as io]
   [nido.session.state :as state]))
```

- [ ] **Step 3: Run the test to verify it fails**

```bash
cd ~/Code/nido && bb nido:test :only nido.session.state
```

Expected: FAIL — the legacy key reappears after `remove-from-registry!`, so the second assertion fails.

- [ ] **Step 4: Implement**

In `src/nido/session/state.clj`, leave lines 192-212 (`registry-file-path` through `upsert-registry!`) exactly as they are, and replace only `remove-from-registry!` (lines 214-215) with:

```clojure
(defn- prune-legacy-registry!
  "Drop `k` from any legacy registry that still carries it. read-registry merges
   those files UNDER the canonical one but write-registry! only rewrites the
   canonical one — so without this, removing a legacy-only key is a no-op: the
   next read merges it back in and the entry is immortal."
  [k]
  (doseq [path (legacy-registry-paths)]
    (when-let [m (io/read-edn path)]
      (when (contains? m k)
        (io/write-edn! path (dissoc m k))))))

(defn remove-from-registry! [project-dir]
  (prune-legacy-registry! project-dir)
  (write-registry! (dissoc (read-registry) project-dir)))
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
cd ~/Code/nido && bb nido:test :only nido.session.state
```

Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
cd ~/Code/nido && bb nido:test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
cd ~/Code/nido && jj commit -m "fix(session): make legacy registry entries deletable

read-registry merges the .codex registries under the canonical file but
write-registry! only rewrites the canonical one, so removing a
legacy-only key was a no-op — the next read merged it back in. Prune the
legacy file on removal too."
```

---

### Task 3: The coordinator prunes dead registry entries

**Files:**
- Modify: `src/nido/work.clj` (new `prune-dead-registry!`, placed directly after `live-session-names`)
- Modify: `src/nido/coordinator/core.clj:164-185` (`maybe-adopt!`)
- Test: `test/nido/work_test.clj` (append), `test/nido/coordinator/core_test.clj` (append)

**Interfaces:**
- Consumes: `(work/session-live? entry) → boolean` from Task 1; `(sstate/remove-from-registry! k)` from Task 2.
- Produces: `(work/prune-dead-registry!) → [instance-id-or-key]` (0-arity, uses wall clock) and `(work/prune-dead-registry! now-ms) → [instance-id-or-key]`.

**Grace window:** `engine/start-session!` writes the registry entry *after* its services are up (`src/nido/session/engine.clj:396-407`) and stamps `:created-at` fresh on every `up!`, so a starting session already has its ports open by the time it is prunable. The 10-minute grace is belt-and-braces for a session whose app is momentarily cycling. The coordinator calls the 0-arity so the sweep's own tick clock is never conflated with wall-clock `:created-at`.

- [ ] **Step 1: Start a clean changeset**

```bash
cd ~/Code/nido && jj new
jj desc -m "feat(coordinator): prune dead session registry entries on the adoption sweep"
```

- [ ] **Step 2: Write the failing work-level test**

`test/nido/work_test.clj` requires `[nido.session.lifecycle]` but not `[nido.session.state]`; the test below redefines vars in the latter, so add it to the `:require` vector in alphabetical order:

```clojure
   [nido.project]
   [nido.process]
   [nido.session.lifecycle]
   [nido.session.state]
   [nido.work :as work]))
```

Note: `with-redefs` binding names are plain fully-qualified symbols — it wraps each in `(var …)` itself, which reaches private vars fine. Writing `#'ns/name` there expands to `(var (var ns/name))` and fails to compile.

Then append to `test/nido/work_test.clj`:

```clojure
(deftest prune-dead-registry-drops-only-dead-and-old-entries
  (let [now     1000000000000
        recent  (java.time.Instant/ofEpochMilli (- now 60000))       ; 1 min old
        ancient (java.time.Instant/ofEpochMilli (- now 86400000))    ; 1 day old
        removed (atom [])]
    (with-redefs [nido.session.state/read-registry
                  (constantly {"/wt/live"   {:instance-id "p--live"   :app-port 3000
                                             :created-at (str ancient)}
                               "/wt/dead"   {:instance-id "p--dead"   :app-port 3001
                                             :created-at (str ancient)}
                               "/wt/young"  {:instance-id "p--young"  :app-port 3002
                                             :created-at (str recent)}
                               "/wt/no-ts"  {:instance-id "p--no-ts"  :app-port 3003}})
                  nido.session.state/remove-from-registry!
                  (fn [k] (swap! removed conj k))
                  nido.process/tcp-open? (fn [port] (= 3000 port))]
      (let [pruned (work/prune-dead-registry! now)]
        (is (= #{"/wt/dead" "/wt/no-ts"} (set @removed))
            "dead + old is pruned; a dead entry with no timestamp is pruned")
        (is (not (contains? (set @removed) "/wt/live"))  "a listening session survives")
        (is (not (contains? (set @removed) "/wt/young")) "inside the grace window it survives")
        (is (= #{"p--dead" "p--no-ts"} (set pruned))
            "returns instance-ids for the coordinator's log line")))))
```

- [ ] **Step 3: Run it to verify it fails**

```bash
cd ~/Code/nido && bb nido:test :only nido.work
```

Expected: FAIL — `No such var: work/prune-dead-registry!`.

- [ ] **Step 4: Implement `prune-dead-registry!`**

In `src/nido/work.clj`, directly after `live-session-names`, add:

```clojure
(def ^:private registry-prune-grace-ms
  "Never prune an entry younger than this. `:created-at` is restamped on every
   `up!` (engine/start-session!), so this only ever shields a session that just
   started — belt and braces on top of the fact that the entry is written AFTER
   its services are listening."
  (* 10 60 1000))

(defn- entry-age-ms
  "Milliseconds since the entry was (re)registered, or nil when it carries no
   parseable `:created-at` — a pre-timestamp entry is by definition old."
  [entry now-ms]
  (when-let [ts (:created-at entry)]
    (try (- now-ms (.toEpochMilli (java.time.Instant/parse ts)))
         (catch Exception _ nil))))

(defn prune-dead-registry!
  "Drop every registry entry whose session no longer holds a port, and return
   their instance-ids. The registry is otherwise only cleaned by a graceful
   `down!` (engine/stop-session! → state/remove-from-registry!), so a reboot, a
   JVM crash or a `kill` leaves an entry — and its phantom Winding-down row —
   behind forever. Entries inside the grace window are left alone.

   Registry-global, not per-project: one call covers every project."
  ([] (prune-dead-registry! (System/currentTimeMillis)))
  ([now-ms]
   (let [dead (->> (sstate/read-registry)
                   (remove (fn [[_ entry]]
                             (or (session-live? entry)
                                 (when-let [age (entry-age-ms entry now-ms)]
                                   (< age registry-prune-grace-ms)))))
                   vec)]
     (doseq [[k _] dead]
       (sstate/remove-from-registry! k))
     (mapv (fn [[k entry]] (or (:instance-id entry) k)) dead))))
```

- [ ] **Step 5: Run the work test to verify it passes**

```bash
cd ~/Code/nido && bb nido:test :only nido.work
```

Expected: PASS.

- [ ] **Step 6: Write the failing coordinator test**

Append to `test/nido/coordinator/core_test.clj`:

```clojure
(deftest adoption-sweep-prunes-the-dead-registry-first
  (let [calls (atom [])]
    (with-redefs [nido.work/prune-dead-registry! (fn [] (swap! calls conj :pruned) ["p--ghost"])
                  nido.work/adopt-orphans!       (fn [_] (swap! calls conj :adopted) {})
                  nido.coordinator.core/registered-projects (constantly [:brian])]
      (reset! @#'core/!last-adopt-ms 0)
      (#'core/maybe-adopt! (* 10 60 1000))
      (is (= [:pruned :adopted] @calls)
          "the registry is cleaned before adoption reads it, in the same sweep"))))
```

Add `[nido.work]` to that file's `:require` vector if it is not already present.

- [ ] **Step 7: Run it to verify it fails**

```bash
cd ~/Code/nido && bb nido:test :only nido.coordinator.core
```

Expected: FAIL — `@calls` is `[:adopted]`; the sweep does not prune.

- [ ] **Step 8: Wire the prune into the sweep**

In `src/nido/coordinator/core.clj`, replace `maybe-adopt!` (lines 164-185) with:

```clojure
(defn- maybe-adopt!
  "Throttled invariant sweep: at most once per :adopt-interval-ms, prune registry
   entries for sessions that are no longer up, then adopt live orphan sessions
   into scratch workstreams and yield claimed duplicates (work/adopt-orphans!).
   The prune runs first so adoption reads a registry that matches reality.
   Never throws into the tick loop."
  [now-ms]
  (when (>= (- now-ms @!last-adopt-ms) (:adopt-interval-ms defaults))
    (reset! !last-adopt-ms now-ms)
    (try
      (let [pruned (work/prune-dead-registry!)]
        (when (seq pruned)
          (println (str "nido coordinator: pruned " (count pruned)
                        " dead session registry entr"
                        (if (= 1 (count pruned)) "y" "ies") ": "
                        (str/join ", " pruned)))))
      (catch Throwable t
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "WARN: registry prune threw — " (ex-message t))))))
    (doseq [project (registered-projects)]
      (try
        (let [{:keys [adopted yielded]} (work/adopt-orphans! project)]
          (when (seq adopted)
            (println (str "nido coordinator: adopted " (count adopted)
                          " orphan session(s) in " (name project) ": "
                          (str/join ", " adopted))))
          (when (seq yielded)
            (println (str "nido coordinator: yielded " (count yielded)
                          " scratch workstream(s) in " (name project)))))
        (catch Throwable t
          (binding [*err* *err*]
            (.println ^java.io.PrintWriter *err*
                      (str "WARN: adoption sweep threw for " (name project)
                           " — " (ex-message t)))))))))
```

- [ ] **Step 9: Run the coordinator test to verify it passes**

```bash
cd ~/Code/nido && bb nido:test :only nido.coordinator.core
```

Expected: PASS.

- [ ] **Step 10: Run the full suite**

```bash
cd ~/Code/nido && bb nido:test
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
cd ~/Code/nido && jj commit -m "feat(coordinator): prune dead session registry entries on the adoption sweep

The registry only shed entries on a graceful down!, so it grew to 33
rows of which 29 were dead. Drop entries whose ports no longer answer on
the same throttled sweep that adopts orphans, behind a 10-minute grace
window so a just-started session is never touched."
```

---

## Verification

After all three tasks, confirm against the live machine — this is the check that would have caught the original bug:

```bash
cd ~/Code/nido && bb -e '
(require (quote [nido.work :as work]) (quote [nido.session.state :as state]))
(println "registry entries:" (count (state/read-registry)))
(doseq [p ["brian" "nido" "babel" "fukan"]]
  (let [live (work/live-session-names p)]
    (println (format "%-8s live=%-2d winding-down-rows=%d"
                     p (count live) (count (work/winding-down p live))))))'
```

Expected: every name in `live` corresponds to a process you can see in `ps`, and no Winding-down row names a session whose ports are closed. Cross-check any row with `lsof -nP -iTCP:<app-port> -sTCP:LISTEN`.

Then open the TUI (`nido`) and confirm the Winding-down band shows only workstreams whose sessions are genuinely up.

## Notes for the implementer

- **Do not commit this plan file.** It is input, not work.
- Task 1 alone fixes the user-visible symptom; Tasks 2 and 3 stop it recurring. They are separately reviewable and separately revertable — keep them in separate commits.
- If the full suite surfaces a failure in `nido.tui` tests, check whether the test stubs `live-session-names` (most do) or reaches the real one — the latter now needs a `nido.process/tcp-open?` stub too.
