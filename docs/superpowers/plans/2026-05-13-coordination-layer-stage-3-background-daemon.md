# Coordination Layer — Stage 3 (background daemon) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the coordinator from foreground-only (Stages 1–2) to a manually-managed background daemon: `bb nido:coordinator:up` spawns it detached, `bb nido:coordinator:down` stops it gracefully, `bb nido:coordinator:logs` tails its output. Add startup reconciliation so crashes/restarts don't leave Runs stuck in non-terminal states.

**Architecture:** A thin daemon-management layer on top of Stage 2. `up` shells out to `bb nido:coordinator:run` with stdio redirected to a log file and the child's lifecycle decoupled from the spawning bb process (via `:shutdown nil`). A PID file at `~/.nido/coordinator/coordinator.pid` tracks the daemon; `:down` reads it and signals. The daemon installs a JVM shutdown hook that drops the PID file and writes a `:stopped` heartbeat so the TUI immediately reflects "not running." On startup, the daemon scans `~/.nido/runs/` for non-terminal Runs and reconciles them — derives terminal state from artifacts + agent.log, or marks `:failed :reason :orphaned-from-restart`.

**Tech Stack:** Babashka, Clojure, `babashka.process`, `babashka.fs`, `clojure.test`. No new deps.

**Spec:** [`docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`](../specs/2026-05-13-nido-coordination-layer-design.md), §"The coordinator daemon" (process model + crash recovery) and §"Staged rollout / Stage 3". This plan implements Stage 3.

**Out of scope for Stage 3 (deferred to later plans):**
- launchd plist + login auto-start → Stage 4
- Async `run-now!` + slot caps → Stage 4 or 5 (when concurrency actually pays off)
- Real event sources (Notion, cron, GitHub) → Stages 5+
- Killing in-flight agents on daemon shutdown. The daemon's shutdown hook drops the PID file + writes a `:stopped` heartbeat, but doesn't try to SIGTERM the agent subprocess. The agent (spawned with `:shutdown nil` per Stage 1a) keeps running with `PPID=1` and finishes (or hits its wall-clock budget). When the daemon restarts, reconciliation observes the Run's terminal evidence and marks it `:done`/`:awaiting-review` if the agent finished, `:failed :reason :orphaned-from-restart` if not. Stage 2's halt-doesn't-preempt limitation is unchanged here.

---

## File structure overview

```
src/nido/coordinator/
├── pid.clj              # NEW: write/read/delete/alive? for coordinator.pid
├── reconcile.clj        # NEW: scan non-terminal Runs on startup, derive terminal state
├── state.clj            # MODIFY: add `log-path` and `pid-path` helpers
└── core.clj             # MODIFY: shutdown hook + reconcile-on-startup; `:stopped` heartbeat

src/tasks/
└── nido_coordinator.clj # MODIFY: new tasks up, down, logs; existing status augmented with PID liveness

test/nido/coordinator/
├── pid_test.clj         # NEW
└── reconcile_test.clj   # NEW

bb.edn                    # MODIFY: register up, down, logs

scripts/
└── (no new scripts — daemon spawn is via babashka.process; no shell wrappers needed)
```

---

## Task 0: PID file utility

**Files:**
- Modify: `src/nido/coordinator/state.clj` (add `pid-path`)
- Create: `src/nido/coordinator/pid.clj`
- Create: `test/nido/coordinator/pid_test.clj`

Small namespace that owns reading/writing `~/.nido/coordinator/coordinator.pid` and checking whether the PID is alive. The daemon writes this on startup; `up`/`down`/`status` read it.

- [ ] **Step 1: Add `pid-path` to `state.clj`**

```clojure
(defn pid-path []
  (str (fs/path (coordinator-root) "coordinator.pid")))
```

Place next to `halted-path`.

- [ ] **Step 2: Write the failing test**

Create `test/nido/coordinator/pid_test.clj`:

```clojure
(ns nido.coordinator.pid-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.pid :as pid]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest read-nil-when-absent
  (with-tmp (fn [] (is (nil? (pid/read))))))

(deftest write-then-read-round-trips
  (with-tmp
    (fn []
      (pid/write! 12345)
      (is (= 12345 (pid/read))))))

(deftest delete!-is-idempotent
  (with-tmp
    (fn []
      (pid/write! 999)
      (pid/delete!)
      (is (nil? (pid/read)))
      ;; Second delete should be a no-op, not an error.
      (pid/delete!)
      (is (nil? (pid/read))))))

(deftest alive?-false-when-no-pid-file
  (with-tmp (fn [] (is (false? (pid/alive?))))))

(deftest alive?-true-for-this-process
  (with-tmp
    (fn []
      ;; Our own JVM process is definitely alive.
      (pid/write! (long (.pid (java.lang.ProcessHandle/current))))
      (is (true? (pid/alive?))))))

(deftest alive?-false-for-stale-pid
  (with-tmp
    (fn []
      ;; PID 1 is init/launchd — exists but isn't *this* coordinator.
      ;; Use a definitely-dead PID instead: 2^31-1 is well past any real pid.
      (pid/write! 2147483647)
      (is (false? (pid/alive?))))))
```

- [ ] **Step 3: Run test to verify failure**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.pid-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 4: Implement**

Create `src/nido/coordinator/pid.clj`:

```clojure
(ns nido.coordinator.pid
  "PID file lifecycle for the background coordinator daemon. See spec
   §The coordinator daemon / Crash recovery."
  (:refer-clojure :exclude [read])
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.state :as cstate]))

(defn read
  "Return the PID stored in coordinator.pid, or nil if absent / malformed."
  []
  (let [p (cstate/pid-path)]
    (when (fs/exists? p)
      (try
        (-> p slurp clojure.string/trim Long/parseLong)
        (catch Exception _ nil)))))

(defn write!
  "Write the given PID to coordinator.pid. Caller's responsibility to ensure
   the coordinator dir exists (cstate/ensure-dirs!)."
  [pid]
  (spit (cstate/pid-path) (str pid "\n")))

(defn delete!
  "Remove coordinator.pid. Idempotent."
  []
  (let [p (cstate/pid-path)]
    (when (fs/exists? p) (fs/delete p))))

(defn alive?
  "True iff the PID in coordinator.pid corresponds to a live OS process.
   Uses java.lang.ProcessHandle, which is available in babashka."
  []
  (boolean
    (when-let [pid (read)]
      (when-let [^java.util.Optional opt (java.lang.ProcessHandle/of (long pid))]
        (and (.isPresent opt)
             (.isAlive ^java.lang.ProcessHandle (.get opt)))))))
```

Note the `(:refer-clojure :exclude [read])` — `read` is a core fn, but we want `pid/read` as the public API. Stage 1a did the same for `core/run!`.

- [ ] **Step 5: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.pid-test
```

Expected: PASS — 6 tests.

- [ ] **Step 6: Commit**

```bash
jj desc -m "feat(coordinator): PID file utility (pid/read/write!/delete!/alive?)"
jj new
```

---

## Task 1: Log path helper

**File:**
- Modify: `src/nido/coordinator/state.clj`

Add a `log-path` helper for the background daemon's stdout/stderr redirect target.

- [ ] **Step 1: Add `log-path`**

In `src/nido/coordinator/state.clj`, alongside `pid-path`:

```clojure
(defn log-path []
  (str (fs/path (coordinator-root) "coordinator.log")))
```

- [ ] **Step 2: Verify it loads**

```bash
cd /Users/yabmas/Code/nido && bb -e "(require 'nido.coordinator.state) (println (nido.coordinator.state/log-path))"
```

Expected: prints `/Users/yabmas/.nido/coordinator/coordinator.log` (or whatever `$NIDO_HOME` resolves to).

- [ ] **Step 3: Run full suite**

```bash
cd /Users/yabmas/Code/nido && bb nido:test 2>&1 | tail -3
```

Expected: 74 + 6 = 80 tests, 0 failures (Stage 2's 74 + Task 0's 6).

- [ ] **Step 4: Commit**

```bash
jj desc -m "feat(coordinator): log-path helper for background daemon"
jj new
```

---

## Task 2: Reconcile non-terminal Runs on startup

**Files:**
- Create: `src/nido/coordinator/reconcile.clj`
- Create: `test/nido/coordinator/reconcile_test.clj`

When the daemon starts, it scans every Run directory and forces any Run still in a non-terminal state to a terminal one. The decision matrix:

1. `_run-status.edn` says `:complete` → Run becomes `:done`
2. `_run-status.edn` says `:awaiting-input` → Run becomes `:awaiting-review` (the agent finished cleanly and was waiting for us)
3. `_run-status.edn` says `:error` → Run becomes `:failed` with `:reason :skill-reported-error`
4. `agent.log` contains a `{"type":"result"}` event (last line) → Run becomes `:done` (agent exited cleanly without a status file — same default as in-process derivation)
5. Otherwise → Run becomes `:failed` with `:reason :orphaned-from-restart`

This mirrors the spec's §"Crash recovery" behavior — derive terminal state from observable evidence.

- [ ] **Step 1: Write the failing test**

Create `test/nido/coordinator/reconcile_test.clj`:

```clojure
(ns nido.coordinator.reconcile-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.reconcile :as reconcile]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def base-run
  {:id "2026-05-13-test-foo-zzzzzzzz"
   :project :test :trigger :foo
   :source {:type :manual :fired-at "T" :fired-by "u"}
   :event-payload {} :skill :foo :first-message "/foo"
   :agent :claude :session-name "run-test-foo-zzzzzzzz"
   :claude-session-id nil :limits {} :state :running
   :state-history [{:at "T1" :state :queued} {:at "T2" :state :running}]
   :artifacts [] :error nil})

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(defn- seed-run! [run]
  (fs/create-dirs (cstate/run-dir (:id run)))
  (runs/write-run! run))

(deftest reconcile!-leaves-terminal-runs-alone
  (with-tmp
    (fn []
      (seed-run! (assoc base-run :state :done :state-history
                        [{:at "T" :state :queued} {:at "T" :state :done}]))
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-promotes-to-done-when-status-says-complete
  (with-tmp
    (fn []
      (seed-run! base-run)
      (io/write-edn! (cstate/run-status-path (:id base-run))
                     {:phase :complete :note "done"})
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-promotes-to-awaiting-review-when-status-says-awaiting
  (with-tmp
    (fn []
      (seed-run! base-run)
      (io/write-edn! (cstate/run-status-path (:id base-run))
                     {:phase :awaiting-input :note "?"})
      (reconcile/reconcile!)
      (is (= :awaiting-review (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-marks-failed-when-status-says-error
  (with-tmp
    (fn []
      (seed-run! base-run)
      (io/write-edn! (cstate/run-status-path (:id base-run))
                     {:phase :error :note "boom"})
      (reconcile/reconcile!)
      (let [r (runs/read-run (:id base-run))]
        (is (= :failed (:state r)))
        (is (= :skill-reported-error (-> r :error :reason)))))))

(deftest reconcile!-promotes-to-done-when-agent-log-has-result-event
  (with-tmp
    (fn []
      (seed-run! base-run)
      (spit (cstate/run-agent-log (:id base-run))
            "{\"type\":\"system\",\"subtype\":\"init\"}\n{\"type\":\"result\",\"subtype\":\"success\"}\n")
      (reconcile/reconcile!)
      (is (= :done (:state (runs/read-run (:id base-run))))))))

(deftest reconcile!-marks-orphan-when-no-evidence
  (with-tmp
    (fn []
      (seed-run! base-run)
      (reconcile/reconcile!)
      (let [r (runs/read-run (:id base-run))]
        (is (= :failed (:state r)))
        (is (= :orphaned-from-restart (-> r :error :reason)))))))

(deftest reconcile!-handles-queued-runs-too
  (with-tmp
    (fn []
      (seed-run! (assoc base-run :state :queued
                        :state-history [{:at "T" :state :queued}]))
      (reconcile/reconcile!)
      (let [r (runs/read-run (:id base-run))]
        ;; Queued Runs that didn't start yet are also orphaned — they never
        ;; got a session or agent.
        (is (= :failed (:state r)))
        (is (= :orphaned-from-restart (-> r :error :reason)))))))
```

- [ ] **Step 2: Run test to verify failure**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.reconcile-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 3: Implement**

Create `src/nido/coordinator/reconcile.clj`:

```clojure
(ns nido.coordinator.reconcile
  "On daemon startup, force any non-terminal Run to a terminal state by
   reading observable evidence (artifacts, _run-status.edn, agent.log).

   See spec §The coordinator daemon / Crash recovery."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.status-file :as status-file]))

(def non-terminal-states
  #{:queued :running :awaiting-review})

(defn- agent-log-reached-result?
  "True iff the last non-blank line of agent.log starts a `result` event.
   That's claude-code's terminal stream-json event; its presence means
   the agent exited cleanly without writing a status file."
  [run-id]
  (let [p (cstate/run-agent-log run-id)]
    (when (fs/exists? p)
      (let [lines (->> (str/split-lines (slurp p))
                       (remove str/blank?))]
        (when-let [last-line (last lines)]
          (str/includes? last-line "\"type\":\"result\""))))))

(defn- derive-terminal-state
  "Decide the terminal state for one non-terminal Run."
  [run-id]
  (let [status (status-file/read-status run-id)]
    (cond
      (= :complete       (:phase status)) {:state :done :error nil}
      (= :awaiting-input (:phase status)) {:state :awaiting-review :error nil}
      (= :error          (:phase status)) {:state :failed
                                           :error {:reason :skill-reported-error
                                                   :note   (:note status)}}
      (agent-log-reached-result? run-id) {:state :done :error nil}
      :else                               {:state :failed
                                           :error {:reason :orphaned-from-restart}})))

(defn- reconcile-one!
  "Read run.edn, decide a terminal state if non-terminal, write it back."
  [run-id]
  (when-let [run (runs/read-run run-id)]
    (when (contains? non-terminal-states (:state run))
      (let [{:keys [state error]} (derive-terminal-state run-id)
            history-entry         {:at (str (java.time.Instant/now)) :state state}
            updated               (-> run
                                      (assoc :state state
                                             :error error)
                                      (update :state-history conj history-entry))]
        (runs/write-run! updated)))))

(defn reconcile!
  "Scan every Run directory under ~/.nido/runs/ and force any non-terminal
   Run to a terminal state. Idempotent — already-terminal Runs are left alone."
  []
  (let [d (cstate/runs-dir)]
    (when (fs/exists? d)
      (doseq [child (fs/list-dir d)
              :when (fs/directory? child)]
        (reconcile-one! (str (fs/file-name child)))))))
```

Note: `(str (java.time.Instant/now))` rather than `clock/now-iso` — `clock/now-iso` is in `nido.coordinator.clock` and is fine to use here too. Match the time-seam convention by using `clock/now-iso`:

```clojure
(:require ... [nido.coordinator.clock :as clock] ...)
;; ...
history-entry {:at (clock/now-iso) :state state}
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.reconcile-test
```

Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): startup reconcile of non-terminal Runs"
jj new
```

---

## Task 3: Shutdown hook + reconcile-on-startup in `core.clj`

**Files:**
- Modify: `src/nido/coordinator/core.clj`

The daemon's `run!` needs to:
1. Reconcile non-terminal Runs (Task 2) before entering the loop.
2. Write its own PID (Task 0) so `:down`/`:status` can find it.
3. Install a JVM shutdown hook that drops the PID file and writes a `:stopped` heartbeat.

The shutdown hook runs on `SIGTERM` (caught by the JVM and translated to a clean exit), `Ctrl-C` (`SIGINT`, also caught), and normal exit. It does NOT run on `SIGKILL` — that's intentional; the daemon should be `:down`'d gracefully, with `:force?` as the escape hatch.

- [ ] **Step 1: Modify `run!` to set up daemon lifecycle**

Replace the existing `run!` in `src/nido/coordinator/core.clj`:

```clojure
(defn- install-shutdown-hook! []
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread.
      (fn []
        ;; Best-effort: write a final :stopped heartbeat, then drop the PID file.
        (try (heartbeat/write! {:status :stopped :slots-in-use 0})
             (catch Exception _ nil))
        (try (pid/delete!)
             (catch Exception _ nil))))))

(defn run!
  "Start the foreground loop. Blocks until interrupted.
   Also installs the daemon lifecycle: writes coordinator.pid, runs the
   crash-recovery reconcile pass, and registers a JVM shutdown hook."
  [& {:keys [poll-ms] :or {poll-ms (:poll-ms defaults)}}]
  (cstate/ensure-dirs!)
  (println "nido coordinator: starting (poll" poll-ms "ms)")
  (reconcile/reconcile!)
  (pid/write! (long (.pid (java.lang.ProcessHandle/current))))
  (install-shutdown-hook!)
  (heartbeat/write! {:status :running :slots-in-use 0})
  (loop []
    (tick!)
    (Thread/sleep poll-ms)
    (recur)))
```

Add `[nido.coordinator.pid :as pid]` and `[nido.coordinator.reconcile :as reconcile]` to the `:require` block.

- [ ] **Step 2: Manual smoke**

```bash
cd /Users/yabmas/Code/nido
# Start a foreground daemon
bb nido:coordinator:run :poll-ms 500 > /tmp/coord-smoke.log 2>&1 &
COORD=$!
sleep 1
# PID file should exist with this exact PID
cat ~/.nido/coordinator/coordinator.pid    # expect: $COORD-ish (not exactly — there's a bb wrapper too)
ls -la ~/.nido/coordinator/coordinator.pid

# Kill (SIGTERM) and verify cleanup
kill $COORD
sleep 2
ls ~/.nido/coordinator/coordinator.pid 2>&1   # expect: No such file
cat ~/.nido/coordinator/status.edn            # expect :status :stopped (the final heartbeat)

# Cleanup
rm -f /tmp/coord-smoke.log
```

The PID written into coordinator.pid is the bb JVM process's own PID — that's what we want, because `:down` will SIGTERM it. (When `bb nido:coordinator:run` runs, the foreground process is one JVM; its `(.pid (ProcessHandle/current))` is the PID we write.)

Note on the smoke: the `$COORD` variable holds the bash backgrounded PID, which is the bb invocation. That may differ slightly from the JVM's reported PID if bb has a small launcher; in practice on macOS bb-task is a single JVM and they match.

- [ ] **Step 3: Run full suite**

```bash
cd /Users/yabmas/Code/nido && bb nido:test 2>&1 | tail -3
```

Expected: 87 tests, 0 failures (80 + 7 from reconcile-test).

- [ ] **Step 4: Commit**

```bash
jj desc -m "feat(coordinator): run! installs shutdown hook + reconciles on startup + writes PID"
jj new
```

---

## Task 4: `bb nido:coordinator:up`

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`
- Modify: `bb.edn`

Spawn the daemon detached: stdout/stderr go to `coordinator.log`, stdin is closed, the child's lifecycle is decoupled from the spawning bb process. Refuse if the daemon is already running.

- [ ] **Step 1: Add the `up` task**

Append to `src/tasks/nido_coordinator.clj`:

```clojure
(defn up
  "bb nido:coordinator:up [:poll-ms <int>] — spawn the daemon in background.
   Refuses if coordinator.pid points at a live process.

   Output goes to ~/.nido/coordinator/coordinator.log (append).
   The daemon writes its own PID; this task exits as soon as the spawn is set up."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (if (pid/alive?)
      (do (println "Coordinator: already running (pid" (pid/read) "). Use `bb nido:coordinator:down` to stop.")
          (System/exit 1))
      (do
        (cstate/ensure-dirs!)
        (let [log-out (java.io.FileOutputStream. (cstate/log-path) true)
              cmd     (cond-> ["bb" "nido:coordinator:run"]
                        (:poll-ms opts) (into [":poll-ms" (str (:poll-ms opts))]))
              proc    (p/process cmd {:in       ""        ; close stdin
                                      :out      log-out   ; append log
                                      :err      log-out
                                      :shutdown nil})     ; survive bb exit
              child-pid (.pid (:proc proc))]
          ;; Don't wait for the child — leave it detached.
          (println "Coordinator: starting in background (pid" child-pid ")")
          (println "Logs: " (cstate/log-path))
          (println "Stop: bb nido:coordinator:down"))))))
```

Add `[babashka.process :as p]` and `[nido.coordinator.pid :as pid]` to the `:require` block.

- [ ] **Step 2: Register in `bb.edn`**

Add after `nido:coordinator:resume`:

```clojure
nido:coordinator:up
{:doc "Spawn coordinator daemon in background. Optional :poll-ms <int>. Logs to coordinator.log."
 :task (apply nido-coordinator/up *command-line-args*)}
```

- [ ] **Step 3: Manual smoke**

```bash
cd /Users/yabmas/Code/nido

# Clean state
rm -f ~/.nido/coordinator/coordinator.pid ~/.nido/coordinator/halted.edn

# Start
bb nido:coordinator:up :poll-ms 500
# Expected:
#   Coordinator: starting in background (pid <N>)
#   Logs:  /Users/yabmas/.nido/coordinator/coordinator.log
#   Stop: bb nido:coordinator:down

sleep 1

# Verify PID file + heartbeat
cat ~/.nido/coordinator/coordinator.pid
bb nido:coordinator:status
# Expected: Coordinator: running, slots 0

# Verify log is being written
ls -la ~/.nido/coordinator/coordinator.log
head -3 ~/.nido/coordinator/coordinator.log
# Expected: contains "nido coordinator: starting (poll 500 ms)"

# Second :up should refuse
bb nido:coordinator:up
# Expected: "Coordinator: already running (pid <N>)..."
# Exit code 1.

# Manual cleanup (Task 5 adds the proper :down)
kill $(cat ~/.nido/coordinator/coordinator.pid)
sleep 2
ls ~/.nido/coordinator/coordinator.pid 2>&1
# Expected: No such file (shutdown hook cleaned it)
```

- [ ] **Step 4: Run full suite**

```bash
cd /Users/yabmas/Code/nido && bb nido:test 2>&1 | tail -3
```

Expected: 87 tests, 0 failures (no new tests; spawn is exercised by smoke).

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(nido): bb nido:coordinator:up — spawn daemon in background"
jj new
```

---

## Task 5: `bb nido:coordinator:down`

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`
- Modify: `bb.edn`

Read `coordinator.pid`, send SIGTERM, wait up to 30s for the daemon to exit (its shutdown hook should fire), and clean up. `:force? true` sends SIGKILL instead.

- [ ] **Step 1: Add the `down` task**

Append to `src/tasks/nido_coordinator.clj`:

```clojure
(defn down
  "bb nido:coordinator:down [:force? true] — stop the background daemon.
   Default SIGTERM, waits up to 30s for graceful shutdown.
   With :force? true, sends SIGKILL immediately (orphans any in-flight agent)."
  [& args]
  (let [[_ opts]  (task-args/split-args args)
        force?    (= true (:force? opts))
        pid       (pid/read)]
    (cond
      (nil? pid)
      (do (println "Coordinator: not running (no PID file).") (System/exit 0))

      (not (pid/alive?))
      (do (println "Coordinator: stale PID file (pid" pid "is not alive). Cleaning up.")
          (pid/delete!)
          (System/exit 0))

      :else
      (let [proc-handle (.get ^java.util.Optional (java.lang.ProcessHandle/of (long pid)))
            signal-name (if force? "SIGKILL" "SIGTERM")]
        (println "Coordinator: sending" signal-name "to pid" pid)
        (if force?
          (.destroyForcibly ^java.lang.ProcessHandle proc-handle)
          (.destroy ^java.lang.ProcessHandle proc-handle))
        ;; Wait up to 30s for the process to exit. Poll every 200ms.
        (let [deadline (+ (System/currentTimeMillis) 30000)]
          (loop []
            (cond
              (not (.isAlive ^java.lang.ProcessHandle proc-handle))
              (do (pid/delete!)   ; defensive — shutdown hook usually does this
                  (println "Coordinator: stopped."))

              (> (System/currentTimeMillis) deadline)
              (do (println "Coordinator: did not exit within 30s. Use :force? true to SIGKILL.")
                  (System/exit 2))

              :else
              (do (Thread/sleep 200) (recur)))))))))
```

- [ ] **Step 2: Register in `bb.edn`**

Add after `nido:coordinator:up`:

```clojure
nido:coordinator:down
{:doc "Stop the background daemon. SIGTERM by default. :force? true → SIGKILL."
 :task (apply nido-coordinator/down *command-line-args*)}
```

- [ ] **Step 3: Manual smoke**

```bash
cd /Users/yabmas/Code/nido

# Start
bb nido:coordinator:up :poll-ms 500
sleep 1
bb nido:coordinator:status   # running

# Graceful down
bb nido:coordinator:down
# Expected:
#   Coordinator: sending SIGTERM to pid <N>
#   Coordinator: stopped.

# Verify cleanup
ls ~/.nido/coordinator/coordinator.pid 2>&1   # No such file
bb nido:coordinator:status                    # status.edn says :stopped

# Second :down on a stopped coord should be a no-op
bb nido:coordinator:down
# Expected: "Coordinator: not running (no PID file)."

# Force kill path
bb nido:coordinator:up :poll-ms 500
sleep 1
bb nido:coordinator:down :force? true
# Expected: SIGKILL message + Coordinator: stopped.
# (NOTE: with SIGKILL, the shutdown hook does NOT run — verify PID file is still cleaned by the :down task itself.)
ls ~/.nido/coordinator/coordinator.pid 2>&1   # No such file
```

- [ ] **Step 4: Stale-PID smoke**

```bash
# Synthesize a stale PID file (use a definitely-dead PID)
echo "2147483647" > ~/.nido/coordinator/coordinator.pid
bb nido:coordinator:down
# Expected: "Coordinator: stale PID file (pid 2147483647 is not alive). Cleaning up."
ls ~/.nido/coordinator/coordinator.pid 2>&1   # No such file
```

- [ ] **Step 5: Run full suite**

```bash
cd /Users/yabmas/Code/nido && bb nido:test 2>&1 | tail -3
```

Expected: 87 tests, 0 failures.

- [ ] **Step 6: Commit**

```bash
jj desc -m "feat(nido): bb nido:coordinator:down — graceful stop with :force? escape hatch"
jj new
```

---

## Task 6: `bb nido:coordinator:logs`

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`
- Modify: `bb.edn`

Tail the daemon's log file. Default behavior: print last 50 lines and exit. `:follow? true`: tail -f (blocks until Ctrl-C).

- [ ] **Step 1: Add the `logs` task**

Append to `src/tasks/nido_coordinator.clj`:

```clojure
(defn logs
  "bb nido:coordinator:logs [:follow? true] [:lines <n>]
   Default: print last 50 lines of coordinator.log and exit.
   :follow? true → tail -f (blocks until Ctrl-C)."
  [& args]
  (let [[_ opts] (task-args/split-args args)
        follow?  (= true (:follow? opts))
        lines    (or (some-> (:lines opts) str parse-long) 50)
        log      (cstate/log-path)]
    (if-not (fs/exists? log)
      (println "Coordinator log not found:" log)
      (if follow?
        ;; tail -f, blocks until Ctrl-C
        (apply p/exec ["tail" "-f" "-n" (str lines) log])
        ;; one-shot tail -n N
        (apply p/exec ["tail" "-n" (str lines) log])))))
```

Add `[babashka.process :as p]` to the `:require` block (likely already there from Task 4).

- [ ] **Step 2: Register in `bb.edn`**

Add after `nido:coordinator:down`:

```clojure
nido:coordinator:logs
{:doc "Tail the coordinator log. Default last 50 lines. :follow? true to follow."
 :task (apply nido-coordinator/logs *command-line-args*)}
```

- [ ] **Step 3: Manual smoke**

```bash
cd /Users/yabmas/Code/nido

# With a running coordinator
bb nido:coordinator:up :poll-ms 500
sleep 1

bb nido:coordinator:logs
# Expected: prints the last 50 (or fewer) lines of coordinator.log, exits.

bb nido:coordinator:logs :lines 3
# Expected: prints last 3 lines.

# Follow mode (manual interactive check)
bb nido:coordinator:logs :follow? true &
TAIL_PID=$!
sleep 3
kill $TAIL_PID 2>/dev/null   # interrupt the follow
wait 2>/dev/null

# With no log present
bb nido:coordinator:down
rm -f ~/.nido/coordinator/coordinator.log
bb nido:coordinator:logs
# Expected: "Coordinator log not found: /Users/yabmas/.nido/coordinator/coordinator.log"
```

- [ ] **Step 4: Run full suite**

```bash
cd /Users/yabmas/Code/nido && bb nido:test 2>&1 | tail -3
```

Expected: 87 tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(nido): bb nido:coordinator:logs — tail / follow coordinator.log"
jj new
```

---

## Task 7: Update `bb nido:coordinator:status` to show PID liveness

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`

The existing `status` task reads `status.edn`. Augment it to also show whether the daemon process is alive per the PID file. This distinguishes "daemon is running and reachable" from "stale status.edn but daemon is gone."

- [ ] **Step 1: Modify `status`**

In `src/tasks/nido_coordinator.clj`, replace the existing `status` body:

```clojure
(defn status [& _args]
  (let [p          (cstate/status-path)
        h          (halt/read-halt-info)
        pid        (pid/read)
        proc-alive (pid/alive?)]
    (println "Process:     "
             (cond
               (and pid proc-alive) (str "alive (pid " pid ")")
               pid                  (str "stale PID file (pid " pid " is not alive)")
               :else                "not running (no PID file)"))
    (if (fs/exists? p)
      (let [s (io/read-edn p)]
        (println "Coordinator:" (some-> (:status s) name))
        (println "Heartbeat:  " (:heartbeat-at s))
        (println "Slots:      " (:slots-in-use s)))
      (println "Coordinator: no status.edn (never started or already cleaned up)"))
    (when h
      (println "Halted:     " (name (:source h)) "—" (or (:note h) "(no note)"))
      (println "Halted at:  " (:halted-at h)))))
```

- [ ] **Step 2: Manual smoke**

```bash
cd /Users/yabmas/Code/nido

# No daemon
bb nido:coordinator:down 2>/dev/null
rm -f ~/.nido/coordinator/coordinator.pid ~/.nido/coordinator/status.edn

bb nido:coordinator:status
# Expected:
#   Process:      not running (no PID file)
#   Coordinator: no status.edn (never started or already cleaned up)

# Running daemon
bb nido:coordinator:up :poll-ms 500
sleep 1
bb nido:coordinator:status
# Expected:
#   Process:      alive (pid <N>)
#   Coordinator: running
#   Heartbeat:  ...
#   Slots:      0

# Stop and check
bb nido:coordinator:down
bb nido:coordinator:status
# Expected:
#   Process:      not running (no PID file)
#   Coordinator: stopped     ← the final shutdown-hook heartbeat
#   Heartbeat:  ...

# Synthesize a stale PID file
echo "2147483647" > ~/.nido/coordinator/coordinator.pid
bb nido:coordinator:status
# Expected:
#   Process:      stale PID file (pid 2147483647 is not alive)
#   Coordinator: stopped
# Cleanup
rm ~/.nido/coordinator/coordinator.pid
```

- [ ] **Step 3: Commit**

```bash
jj desc -m "feat(coordinator): status surfaces PID liveness (alive / stale / not running)"
jj new
```

---

## Task 8: Documentation

**Files:**
- Modify: `CLAUDE.md`

Update the project-level pointer to mention the background daemon workflow.

- [ ] **Step 1: Update `CLAUDE.md`**

Replace the "Coordination layer (stages 1–2)" section with:

```markdown
## Coordination layer (stages 1–3)

The coordinator daemon spawns Run-owned sessions that auto-launch claude with a configured skill. Triggers live at `~/.nido/projects/<project>/triggers.edn`; envelopes hit `~/.nido/coordinator/queue/`.

**Running the daemon (Stage 3):**

```
bb nido:coordinator:up          # background daemon; logs to coordinator.log
bb nido:coordinator:status      # process alive? + heartbeat + halt/breaker info
bb nido:coordinator:logs        # last 50 lines (or :follow? true to tail -f)
bb nido:coordinator:down        # graceful stop (SIGTERM); :force? true → SIGKILL
```

`up` refuses if a live daemon already holds the PID file. `down` cleans the PID file even when the daemon was already gone (stale PID).

**Foreground (still supported for development):**

```
bb nido:coordinator:run :poll-ms 500
```

**Working with triggers/runs:**

```
bb nido:trigger:fire :project brian <name> :<key> <value>
bb nido:runs:list / bb nido:runs:show <id>
```

Or in the TUI press `r` for the runs surface.

**Safety brakes (Stage 2):** per-Run wall-clock budget (`:limits.budget`, default 30m, SIGTERM→SIGKILL), per-trigger circuit breaker (`:limits.max-failures`, default 3), daemon-wide anomaly auto-halt, kill switch (`bb nido:halt` + `bb nido:coordinator:resume`). TUI `h` halts, `c` clears a breaker.

**Startup reconciliation (Stage 3):** when the daemon starts, any non-terminal Run on disk is forced to a terminal state from observable evidence (artifacts, `_run-status.edn`, agent.log). Crashed/orphaned Runs get marked `:failed :reason :orphaned-from-restart` so the dashboard stays honest.

Full design: `docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`. Skill conventions: `docs/skill-conventions-for-triggers.md`.

Stage 4 will add launchd auto-start at login; stages 5+ add Notion / cron / GitHub event sources.
```

- [ ] **Step 2: Commit**

```bash
jj desc -m "docs(nido): Stage 3 background-daemon workflow in CLAUDE.md"
jj new
```

---

## Closing checklist

- [ ] All 9 tasks committed as discrete jj changes.
- [ ] `bb nido:test` passes cleanly (87 tests, 0 failures).
- [ ] `bb tasks | grep nido:coordinator:` shows: `:run`, `:up`, `:down`, `:status`, `:logs`, `:halt`, `:resume`.
- [ ] Walk-through smoke:
  - `bb nido:coordinator:up` → process alive, heartbeat ticking, log file growing.
  - `bb nido:coordinator:status` → "Process: alive (pid N)" + "Coordinator: running".
  - `bb nido:coordinator:logs :lines 5` → prints recent lines.
  - `bb nido:coordinator:down` → "Coordinator: stopped." + PID file gone.
  - `bb nido:coordinator:up; pkill -9 bb; bb nido:coordinator:status` → "stale PID file" message.
  - Restart after a real-claude smoke that orphans a Run: previous Run flips to `:failed :reason :orphaned-from-restart`.

## Spec coverage map

| Spec section | Task(s) |
|---|---|
| Auto-started by launchd at login | **Out of scope — Stage 4** |
| `KeepAlive=true` on crash | **Out of scope — Stage 4** |
| Manual control: `:status / :restart / :logs` | Tasks 6, 7 (`:restart` is Stage 1a's `:down`+`:up` combo per spec) |
| Crash recovery (reconcile non-terminal on boot) | Task 2 (+ Task 3 wires it in) |
| PID + log artifacts under `~/.nido/coordinator/` | Tasks 0, 1 |

**Deferred to later stages (not Stage 3):**
- launchd plist + `:install` task → Stage 4
- Async `run-now!` (so halt actually preempts in-flight Runs) → Stage 4 or 5
- Real event sources (Notion / cron / GitHub) → Stage 5+

---

## Notes for the implementer

- **Babashka's shutdown-hook support is reliable for SIGTERM and SIGINT.** It does not run on SIGKILL — that's intentional; the kill-switch design assumes `:force? true` is an explicit user choice with known consequences (orphaned agents).
- **`java.lang.ProcessHandle/of` is babashka-supported.** Verified in the test for `pid/alive?`. If it's missing on a future bb version, fall back to spawning `ps -p <pid>` and checking exit code.
- **The background spawn does NOT use `nohup`/`setsid`.** It relies on (1) `:shutdown nil` so the spawning bb-task's exit doesn't drop the child, (2) `:in ""` so the child isn't tied to a terminal, and (3) `:out`/`:err` redirected to the log file. On macOS this gives a fully detached child. If the user runs `up` from a terminal that's then closed via SIGHUP, the child should ignore it because its stdio is no longer a terminal. If you hit issues, prepend `["setsid"]` (Linux/macOS coreutils) — but verify availability first.
- **Reconciliation is idempotent.** Running `reconcile!` on a Run that's already terminal is a no-op (the function checks `non-terminal-states`). So back-to-back daemon restarts don't re-write the same Runs.
- **PID file race.** Two `up` invocations within a few milliseconds of each other could both pass the `alive?` check and both spawn a daemon. Acceptable for v1 — the second daemon will fail because the heartbeat write collision is benign and the queue draining is idempotent. If this turns out to actually happen, add a file lock in a later pass.
