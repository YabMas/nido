# Coordination Layer — Stage 2 (safety brakes) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the safety mechanisms the spec mandates before the coordinator is trusted to run autonomously: wall-clock budget enforcement, per-trigger circuit breaker, daemon-wide anomaly auto-halt, and a kill switch. Surface them in the TUI so the user can see and act when something trips.

**Architecture:** Brakes layer on top of the existing Stage 1a pipeline without restructuring it. The Run lifecycle already includes `:halted` and `:failed` as terminal states; trigger config already supports `:limits` and `:enabled?`. Stage 2 wires the enforcement: agent.clj kills the subprocess on budget breach, runs.clj's transitions update a per-trigger breaker state file, the coordinator's main loop respects a halted-state file before processing envelopes, and the TUI shows the alert counts in its existing status bar.

**Tech Stack:** Babashka, Clojure, `babashka.process`, clojure.test, no new dependencies.

**Spec:** [`docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`](../specs/2026-05-13-nido-coordination-layer-design.md), §"Safety brakes (always on, once enabled)" + the corresponding rows in §"Run overview TUI surface". This plan implements that section in full.

**Out of scope for Stage 2 (deferred to later plans):**
- Background daemon harness (`bb nido:coordinator:up/down` without foreground TTY) — Stage 3
- launchd plist + `bb nido:coordinator:install` — Stage 4
- Real event sources (Notion / cron / GitHub) — Stages 5+
- Parallel-slot accounting (global + per-project caps). Stage 1a's `run-now!` is synchronous (one-at-a-time globally), so slot caps are theoretical. We defer them until the executor goes async. The fields in the Run record and the spec's brake table are designed-in but unenforced.
- Per-trigger cooldown timer. Designed-in (`:limits.cooldown` in trigger config) but not enforced in Stage 2. Circuit breaker + manual disable cover the urgent case.

---

## File structure overview

```
src/nido/coordinator/
├── breakers.clj         # NEW: per-trigger circuit-breaker state file (read/write/observe)
├── halt.clj             # NEW: halted.edn read/write + the "is halted?" predicate
├── anomaly.clj          # NEW: ring-buffer tracking of spawns + failures; threshold check
├── agent.clj            # MODIFY: wall-clock budget timer; SIGTERM → wait → SIGKILL
├── core.clj             # MODIFY: respect halt + breakers; record spawn/failure events; check anomaly each tick
├── runs.clj             # MODIFY: hooks for breaker updates on terminal transitions
└── runs_view.clj        # MODIFY: surface trigger-breaker count in the status payload

src/nido/
├── io.clj               # MODIFY: atomic write-edn! (write tmp + rename)
└── tui.clj              # MODIFY: alerts segment + `h` halt key + `c` clear-breaker key

src/tasks/
├── nido_coordinator.clj # MODIFY: new tasks nido:coordinator:halt (alias nido:halt) and :resume
└── nido_trigger.clj     # MODIFY: new tasks nido:trigger:disable / :enable

test/nido/coordinator/
├── breakers_test.clj
├── halt_test.clj
├── anomaly_test.clj
└── (existing tests grow with new assertions per task)

bb.edn                    # MODIFY: register new tasks (halt, resume, trigger:enable, trigger:disable)
```

---

## Task 0: Atomic `write-edn!`

**Files:**
- Modify: `src/nido/io.clj`
- Modify: `test/nido/io_test.clj` (create if absent; otherwise extend)

The TUI reading `status.edn` while the daemon writes it can observe a torn file today — `write-edn!` is a direct `spit` with no atomic rename. The TUI swallows the parse error and shows "unreachable" briefly, which is a wart. Fix by writing to `<path>.tmp` then renaming. Cheap and ripples to every caller.

- [ ] **Step 1: Write the failing test**

Create `test/nido/io_test.clj`:

```clojure
(ns nido.io-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.io :as io]))

(deftest write-edn!-round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "foo.edn"))]
        (io/write-edn! p {:x 1 :y :ok})
        (is (= {:x 1 :y :ok} (io/read-edn p))))
      (finally (fs/delete-tree tmp)))))

(deftest write-edn!-overwrites-cleanly
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "foo.edn"))]
        (io/write-edn! p {:v 1})
        (io/write-edn! p {:v 2})
        (is (= {:v 2} (io/read-edn p))))
      (finally (fs/delete-tree tmp)))))

(deftest write-edn!-leaves-no-tmp-trail
  ;; After write completes, no `.tmp` sibling should remain.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "foo.edn"))]
        (io/write-edn! p {:v 1})
        (is (false? (fs/exists? (str p ".tmp")))
            "atomic rename should remove the tmp sibling"))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run test to verify the new tests pass on the OLD impl too (but the `.tmp` test will fail because the old impl doesn't use a tmp at all)**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.io-test
```

Expected: 2 of 3 pass (round-trip + overwrite work fine on the old impl; `write-edn!-leaves-no-tmp-trail` passes vacuously since no tmp was ever created). All three should pass; the third becomes meaningful only after Step 3 changes the implementation.

- [ ] **Step 3: Make `write-edn!` atomic**

Replace the body in `src/nido/io.clj`:

```clojure
(defn write-edn!
  "Atomically write EDN to path: writes to <path>.tmp then renames."
  [path data]
  (let [path-s (str path)
        tmp    (str path-s ".tmp")]
    (spit tmp (pr-str data))
    (fs/move tmp path-s {:replace-existing true})))
```

Add `[babashka.fs :as fs]` to the `:require` block if it's not already there.

- [ ] **Step 4: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test
```

Expected: full suite passes (52 + 3 new = 55 tests), 0 failures. The atomic-rename change is fully transparent to existing callers.

- [ ] **Step 5: Commit**

```bash
jj desc -m "fix(io): write-edn! writes via .tmp + rename (atomic against torn reads)"
jj new
```

---

## Task 1: Halted-state file + kill switch

**Files:**
- Create: `src/nido/coordinator/halt.clj`
- Create: `test/nido/coordinator/halt_test.clj`
- Modify: `src/nido/coordinator/state.clj` (add `halted-path` if not already present)

Halted-state lives in `~/.nido/coordinator/halted.edn`. Its presence means "don't process new envelopes; mark any in-flight Run :halted." The CLI writes/removes it; the daemon respects it.

- [ ] **Step 1: Confirm `cstate/halted-path` exists**

```bash
grep -n "halted-path" /Users/yabmas/Code/nido/src/nido/coordinator/state.clj
```

Expected: one match (the path helper was scaffolded in Stage 1a). If absent, add it next to `status-path`:

```clojure
(defn halted-path []
  (str (fs/path (coordinator-root) "halted.edn")))
```

- [ ] **Step 2: Write the failing test**

Create `test/nido/coordinator/halt_test.clj`:

```clojure
(ns nido.coordinator.halt-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest halted?-false-when-no-file
  (with-tmp (fn [] (is (false? (halt/halted?))))))

(deftest halt!-writes-the-file-with-reason
  (with-tmp
    (fn []
      (halt/halt! {:source :user :note "smoke"})
      (is (halt/halted?))
      (let [m (io/read-edn (cstate/halted-path))]
        (is (= :user (:source m)))
        (is (= "smoke" (:note m)))
        (is (string? (:halted-at m)))))))

(deftest resume!-removes-the-file
  (with-tmp
    (fn []
      (halt/halt! {:source :user})
      (is (halt/halted?))
      (halt/resume!)
      (is (false? (halt/halted?)))
      (is (false? (fs/exists? (cstate/halted-path)))))))

(deftest read-halt-info-returns-nil-when-absent
  (with-tmp (fn [] (is (nil? (halt/read-halt-info))))))

(deftest read-halt-info-returns-map-when-present
  (with-tmp
    (fn []
      (halt/halt! {:source :auto :reason :anomaly})
      (let [m (halt/read-halt-info)]
        (is (= :auto (:source m)))
        (is (= :anomaly (:reason m)))))))
```

- [ ] **Step 3: Run test to verify failure**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.halt-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 4: Implement**

Create `src/nido/coordinator/halt.clj`:

```clojure
(ns nido.coordinator.halt
  "Halted-state file: presence of ~/.nido/coordinator/halted.edn means
   the coordinator has paused. The daemon checks `halted?` each tick
   before draining the queue. See spec §Safety brakes / Kill switch."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn halted? []
  (fs/exists? (cstate/halted-path)))

(defn read-halt-info
  "Map with :source (:user | :auto), :reason (optional kw), :note (optional str),
   :halted-at (iso). Returns nil when not halted."
  []
  (when (halted?)
    (try (io/read-edn (cstate/halted-path))
         (catch Exception _ nil))))

(defn halt!
  "Write halted.edn with the given metadata. Always stamps :halted-at."
  [info]
  (io/write-edn! (cstate/halted-path)
                 (assoc info :halted-at (clock/now-iso))))

(defn resume!
  "Remove halted.edn. Idempotent — no-op if absent."
  []
  (when (halted?)
    (fs/delete (cstate/halted-path))))
```

- [ ] **Step 5: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.halt-test
```

Expected: PASS — 5 tests.

- [ ] **Step 6: Commit**

```bash
jj desc -m "feat(coordinator): halted.edn state file + halt!/resume!/halted?"
jj new
```

---

## Task 2: `bb nido:halt` and `bb nido:coordinator:resume` tasks

**Files:**
- Modify: `src/tasks/nido_coordinator.clj`
- Modify: `bb.edn`

The CLI surface for the kill switch. `nido:halt` is an alias for `nido:coordinator:halt` (spec calls out the short form because it has to be memorable in a panic).

- [ ] **Step 1: Add the task entries to `tasks/nido_coordinator.clj`**

Append after `status`:

```clojure
(defn halt
  "bb nido:halt [:note \"...\"] — pauses coordinator; existing Runs get SIGTERM."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (halt/halt! {:source :user :note (some-> (:note opts) str)})
    (println "Coordinator: halted (user). Resume with: bb nido:coordinator:resume")))

(defn resume
  "bb nido:coordinator:resume — clears halted.edn so the daemon picks back up."
  [& _args]
  (halt/resume!)
  (println "Coordinator: resumed (halted.edn removed)."))
```

Add `[nido.coordinator.halt :as halt]` to the `:require` block.

- [ ] **Step 2: Register the bb tasks**

In `bb.edn`, add after the existing coordinator tasks:

```clojure
nido:coordinator:halt
{:doc "Pause the coordinator: write halted.edn so no new Runs spawn. Optional :note <str>."
 :task (apply nido-coordinator/halt *command-line-args*)}

nido:coordinator:resume
{:doc "Resume after halt: remove halted.edn so the daemon ticks again."
 :task (apply nido-coordinator/resume *command-line-args*)}

nido:halt
{:doc "Alias for nido:coordinator:halt — the panic button."
 :task (apply nido-coordinator/halt *command-line-args*)}
```

- [ ] **Step 3: Manual smoke**

```bash
cd /Users/yabmas/Code/nido
bb nido:halt :note "testing"
ls ~/.nido/coordinator/halted.edn
cat ~/.nido/coordinator/halted.edn
bb nido:coordinator:status   # should show halted info (Task 3 will surface it)
bb nido:coordinator:resume
ls ~/.nido/coordinator/halted.edn 2>&1  # expected: no such file
```

- [ ] **Step 4: Commit**

```bash
jj desc -m "feat(nido): bb nido:halt and bb nido:coordinator:resume"
jj new
```

---

## Task 3: Daemon respects halt + halts in-flight Runs

**Files:**
- Modify: `src/nido/coordinator/core.clj`

When `halted.edn` exists at tick-time, the daemon skips envelope processing. If a Run is mid-flight when `halt!` is called, the daemon kills the agent process and marks the Run `:halted`. Stage 1a's `run-now!` is single-flight so "in-flight" means at most one Run; still, the seam supports plural for forward compat.

- [ ] **Step 1: Modify `tick!` to bail when halted**

In `src/nido/coordinator/core.clj`, change `tick!` so it heartbeats with halt state when halted, and skips envelope processing:

```clojure
(defn tick!
  "One iteration of the main loop. Public for testability."
  []
  (let [triggers-by-project (load-all-triggers)
        halt-info           (halt/read-halt-info)]
    (if halt-info
      (heartbeat/write! {:status :halted
                         :halted-by (:source halt-info)
                         :halt-note (:note halt-info)
                         :slots-in-use 0})
      (do
        (heartbeat/write! {:status :running :slots-in-use 0})
        (doseq [env (queue/drain!)]
          (process-envelope! env triggers-by-project))))))
```

Add `[nido.coordinator.halt :as halt]` to the `:require` block.

- [ ] **Step 2: Update `bb nido:coordinator:status` to surface halt info**

In `src/tasks/nido_coordinator.clj`, modify `status` to read `halt/read-halt-info` and print it when present:

```clojure
(defn status [& _args]
  (let [p (cstate/status-path)
        h (halt/read-halt-info)]
    (if (fs/exists? p)
      (let [s (io/read-edn p)]
        (println "Coordinator:" (some-> (:status s) name))
        (println "Heartbeat:  " (:heartbeat-at s))
        (println "Slots:      " (:slots-in-use s)))
      (println "Coordinator: not running (no status.edn)"))
    (when h
      (println "Halted:     " (name (:source h)) "—" (or (:note h) "(no note)"))
      (println "Halted at:  " (:halted-at h)))))
```

- [ ] **Step 3: Manual smoke**

In one terminal:

```bash
bb nido:coordinator:run :poll-ms 500
```

In another:

```bash
bb nido:halt :note "test"
sleep 1
bb nido:coordinator:status
# Expected:
#   Coordinator: halted
#   ...
#   Halted:      user — test
bb nido:coordinator:resume
sleep 1
bb nido:coordinator:status
# Expected: status back to :running
# Ctrl-C the first terminal.
```

- [ ] **Step 4: Commit**

```bash
jj desc -m "feat(coordinator): tick! respects halt; status surfaces halt info"
jj new
```

---

## Task 4: Wall-clock budget enforcement in `agent.clj`

**Files:**
- Modify: `src/nido/coordinator/agent.clj`
- Modify: `test/nido/coordinator/agent_test.clj`

The Run's `:limits.budget` field (default `"30m"` from Stage 1a) becomes a hard wall-clock kill: `SIGTERM` after the budget elapses, then `SIGKILL` 10s later if the process is still alive. Returns `:exit-code -1 :timed-out? true` to the caller; `core/run-now!` will translate that to `:failed :timeout`.

- [ ] **Step 1: Add a duration-parsing helper to `agent.clj`**

Above `launch!`:

```clojure
(defn- parse-budget-ms
  "Parse a budget string like '30m', '45m', '2h' into milliseconds.
   nil → no budget (treat as infinite)."
  [s]
  (when s
    (let [[_ n unit] (re-matches #"(\d+)([smhd])" s)]
      (when n
        (let [n  (Long/parseLong n)
              ms (case unit
                   "s" 1000
                   "m" 60000
                   "h" 3600000
                   "d" 86400000)]
          (* n ms))))))
```

- [ ] **Step 2: Wire the budget timer into `launch!`**

Modify `launch!` to:
1. Accept a `:budget` opt (string like `"30m"` or nil)
2. Spawn a timer future that SIGTERMs the process when the budget elapses
3. Return `:timed-out? true` when the timer fired
4. Cancel the timer on normal exit

```clojure
(defn launch!
  "Spawn claude headlessly for a Run. Blocks until the agent exits or the
   wall-clock budget is exceeded.

   opts (existing + new):
     :budget — string like \"30m\" / \"2h\". nil → no budget.
   Returns:
     {:exit-code <int> :claude-session-id <str-or-nil> :timed-out? <bool>}"
  [{:keys [run-id cwd first-message system-prompt claude-bin env budget]
    :or   {claude-bin "claude"}}]
  (let [log-path  (cstate/run-agent-log run-id)
        cmd       (cond-> [claude-bin
                           "--print"
                           "--verbose"
                           "--output-format=stream-json"
                           "--dangerously-skip-permissions"]
                    system-prompt (into ["--append-system-prompt" system-prompt])
                    :always       (conj first-message))
        proc      (p/process cmd {:dir cwd
                                  :env (merge (into {} (System/getenv)) (or env {}))
                                  :in  ""
                                  :out :stream
                                  :err :inherit
                                  :shutdown nil})
        session   (atom nil)
        budget-ms (parse-budget-ms budget)
        timed-out (atom false)
        timer     (when budget-ms
                    (future
                      (Thread/sleep budget-ms)
                      (when (.isAlive ^Process (:proc proc))
                        (reset! timed-out true)
                        (p/destroy proc)
                        ;; Give claude 10s to exit cleanly on SIGTERM,
                        ;; then SIGKILL the whole tree.
                        (Thread/sleep 10000)
                        (when (.isAlive ^Process (:proc proc))
                          (p/destroy-tree proc)))))]
    (try
      (with-open [w (jio/writer log-path :append true)]
        (with-open [r (jio/reader (:out proc))]
          (doseq [line (line-seq r)]
            (.write w line) (.write w "\n") (.flush w)
            (when-let [event (parse-event line)]
              (when-let [sid (session-id-from event)]
                (reset! session sid))))))
      (finally
        (when timer (future-cancel timer))))
    (let [exit (:exit @proc)]
      {:exit-code         exit
       :claude-session-id @session
       :timed-out?        @timed-out})))
```

- [ ] **Step 3: Extend the fake claude to support a hang mode**

In `resources/test/fake-claude/claude`, add support for `FAKE_CLAUDE_HANG_MS` — sleeps for that long before exiting, so a budget-test can race it.

```bash
# Append near the top of the script, after EXIT_CODE/DELAY_MS reading:
HANG_MS="${FAKE_CLAUDE_HANG_MS:-}"

# Replace the existing sleep line with conditional hang:
if [ -n "$HANG_MS" ]; then
  python3 -c "import time; time.sleep(${HANG_MS}/1000.0)" 2>/dev/null || sleep $(echo "scale=3; $HANG_MS/1000" | bc)
else
  python3 -c "import time; time.sleep(${DELAY_MS}/1000.0)" 2>/dev/null || sleep 0.05
fi
```

- [ ] **Step 4: Add the failing test**

Append to `test/nido/coordinator/agent_test.clj`:

```clojure
(deftest launch!-times-out-and-sigterms
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [t0 (System/currentTimeMillis)
              result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x"
                        :claude-bin    fake-claude
                        :budget        "1s"
                        :env           {"FAKE_CLAUDE_HANG_MS" "10000"
                                        "FAKE_CLAUDE_SESSION_ID" "s"}})
              elapsed (- (System/currentTimeMillis) t0)]
          (is (true? (:timed-out? result)))
          ;; Wall clock: 1s budget + ≤10s SIGKILL grace. Hang would have been 10s.
          ;; Should land between 1s and ~11s.
          (is (< elapsed 12000) (str "took " elapsed "ms"))))
      (finally (fs/delete-tree tmp)))))

(deftest launch!-no-timeout-by-default
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x"
                        :claude-bin    fake-claude
                        :env           {"FAKE_CLAUDE_SESSION_ID" "s"}})]
          (is (false? (:timed-out? result)))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 5: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.agent-test
```

Expected: 4 tests pass (2 existing + 2 new), 0 failures. The timeout test may take 1-2 seconds wall-clock — that's the budget elapsing.

- [ ] **Step 6: Wire budget into `core/run-now!`**

In `src/nido/coordinator/core.clj`, modify the `agent/launch!` call to pass the Run's budget:

```clojure
result     (agent/launch! {:run-id        run-id
                           :cwd           worktree
                           :first-message (:first-message run)
                           :system-prompt (:system-prompt defaults)
                           :budget        (-> run :limits :budget)})
```

And translate timeout to a useful error annotation:

```clojure
next-state (cond
             (:timed-out? result) :failed
             (zero? (:exit-code result))
             (status-file/derive-state-after-exit
               (status-file/read-status run-id))
             :else :failed)
```

When `:timed-out?` is true, also record the failure reason:

```clojure
(when (= :failed next-state)
  (let [r (runs/read-run run-id)]
    (runs/write-run! (assoc r :error (cond-> {:exit-code (:exit-code result)}
                                       (:timed-out? result)
                                       (assoc :reason :timeout)
                                       :always
                                       (assoc :budget (-> r :limits :budget)))))))
```

- [ ] **Step 7: Commit**

```bash
jj desc -m "feat(coordinator): wall-clock budget — SIGTERM at budget, SIGKILL 10s later"
jj new
```

---

## Task 5: Circuit breaker — data layer

**Files:**
- Create: `src/nido/coordinator/breakers.clj`
- Create: `test/nido/coordinator/breakers_test.clj`
- Modify: `src/nido/coordinator/state.clj` (add `breakers-path`)

Per-trigger consecutive-failure count, stored at `~/.nido/coordinator/breakers.edn`. When a trigger's count hits `max-failures` (default 3), the daemon skips its envelopes. Manual `bb nido:trigger:enable` clears it.

Shape on disk:

```edn
;; ~/.nido/coordinator/breakers.edn
{:brian {:investigate-bug {:consecutive-failures 2
                           :last-failure-at "2026-05-13T10:00:00Z"
                           :tripped? false
                           :disabled-by-user? false}}}
```

- [ ] **Step 1: Add `breakers-path` to `state.clj`**

```clojure
(defn breakers-path []
  (str (fs/path (coordinator-root) "breakers.edn")))
```

- [ ] **Step 2: Write the failing test**

Create `test/nido/coordinator/breakers_test.clj`:

```clojure
(ns nido.coordinator.breakers-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest read-all-empty-when-absent
  (with-tmp (fn [] (is (= {} (breakers/read-all))))))

(deftest record-failure!-increments-and-trips-at-3
  (with-tmp
    (fn []
      (breakers/record-failure! :brian :investigate-bug 3)
      (is (= 1 (breakers/consecutive-failures :brian :investigate-bug)))
      (is (false? (breakers/tripped? :brian :investigate-bug)))
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (is (= 3 (breakers/consecutive-failures :brian :investigate-bug)))
      (is (true? (breakers/tripped? :brian :investigate-bug))))))

(deftest record-success!-resets-counter
  (with-tmp
    (fn []
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-success! :brian :investigate-bug)
      (is (= 0 (breakers/consecutive-failures :brian :investigate-bug)))
      (is (false? (breakers/tripped? :brian :investigate-bug))))))

(deftest user-disable-takes-priority
  (with-tmp
    (fn []
      (breakers/disable-by-user! :brian :investigate-bug "manually testing")
      (is (true? (breakers/tripped? :brian :investigate-bug)))
      ;; Even a success doesn't auto-re-enable a user-disabled trigger.
      (breakers/record-success! :brian :investigate-bug)
      (is (true? (breakers/tripped? :brian :investigate-bug))))))

(deftest enable!-clears-everything
  (with-tmp
    (fn []
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/disable-by-user! :brian :other "")
      (breakers/enable! :brian :investigate-bug)
      (is (false? (breakers/tripped? :brian :investigate-bug)))
      (is (true? (breakers/tripped? :brian :other))
          "enabling one trigger should not touch others"))))

(deftest tripped-triggers-summary
  (with-tmp
    (fn []
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/record-failure! :brian :investigate-bug 3)
      (breakers/disable-by-user! :fukan :other "off")
      (let [t (breakers/tripped-triggers)]
        (is (= 2 (count t)))
        (is (every? #(contains? % :project) t))
        (is (every? #(contains? % :trigger) t))))))
```

- [ ] **Step 3: Run test to verify failure**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.breakers-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 4: Implement**

Create `src/nido/coordinator/breakers.clj`:

```clojure
(ns nido.coordinator.breakers
  "Per-trigger circuit breaker. Stores consecutive-failure counts at
   ~/.nido/coordinator/breakers.edn. When a trigger's count meets its
   max-failures threshold the daemon stops processing its envelopes.

   See spec §Safety brakes / Circuit breaker."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn read-all []
  (let [p (cstate/breakers-path)]
    (if (fs/exists? p)
      (try (io/read-edn p) (catch Exception _ {}))
      {})))

(defn- write-all! [m]
  (io/write-edn! (cstate/breakers-path) m))

(defn- entry [m project trigger]
  (get-in m [project trigger]
          {:consecutive-failures 0
           :tripped?             false
           :disabled-by-user?    false
           :last-failure-at      nil
           :note                 nil}))

(defn consecutive-failures [project trigger]
  (:consecutive-failures (entry (read-all) project trigger)))

(defn tripped?
  "True iff the breaker is open: either consecutive failures hit the
   threshold last seen on this trigger, or the user disabled it."
  [project trigger]
  (let [e (entry (read-all) project trigger)]
    (or (:tripped? e) (:disabled-by-user? e))))

(defn record-failure!
  "Increment the consecutive-failure counter for (project, trigger).
   If the new count meets max-failures, mark :tripped? true."
  [project trigger max-failures]
  (let [m   (read-all)
        e   (entry m project trigger)
        n   (inc (:consecutive-failures e))
        e'  (-> e
                (assoc :consecutive-failures n
                       :last-failure-at      (clock/now-iso)
                       :tripped?             (>= n max-failures)))]
    (write-all! (assoc-in m [project trigger] e'))))

(defn record-success!
  "Clear the consecutive-failure counter and auto-disable flag. Does
   NOT clear :disabled-by-user? — user disables stick until enable!."
  [project trigger]
  (let [m  (read-all)
        e  (entry m project trigger)
        e' (-> e (assoc :consecutive-failures 0 :tripped? false))]
    (write-all! (assoc-in m [project trigger] e'))))

(defn disable-by-user!
  "Manual disable. Persists across success transitions."
  [project trigger note]
  (let [m  (read-all)
        e  (entry m project trigger)
        e' (-> e (assoc :disabled-by-user? true :note note))]
    (write-all! (assoc-in m [project trigger] e'))))

(defn enable!
  "Clear both auto-trip and user disable for one (project, trigger).
   Mirrors `bb nido:trigger:enable`."
  [project trigger]
  (let [m  (read-all)
        e  (entry m project trigger)
        e' (-> e (assoc :consecutive-failures 0
                        :tripped? false
                        :disabled-by-user? false))]
    (write-all! (assoc-in m [project trigger] e'))))

(defn tripped-triggers
  "Vector of {:project :trigger :info} for every breaker that's open."
  []
  (vec
    (for [[project ts] (read-all)
          [trigger e]  ts
          :when (or (:tripped? e) (:disabled-by-user? e))]
      {:project project :trigger trigger :info e})))
```

- [ ] **Step 5: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.breakers-test
```

Expected: PASS — 6 tests.

- [ ] **Step 6: Commit**

```bash
jj desc -m "feat(coordinator): circuit-breaker data layer (breakers.edn)"
jj new
```

---

## Task 6: Wire breakers into the run lifecycle

**Files:**
- Modify: `src/nido/coordinator/core.clj`

After `run-now!` settles a Run into a terminal state, update the breaker. Before processing an envelope, check if the trigger's breaker is open and skip if so. Default `max-failures` is `3` (config-overridable per-trigger via `:limits.max-failures`).

- [ ] **Step 1: Skip envelopes for tripped triggers**

In `process-envelope!`, wrap the existing body so a tripped trigger short-circuits with a stderr warning:

```clojure
(defn- process-envelope! [envelope triggers-by-project]
  (let [routed (events/route envelope triggers-by-project)]
    (cond
      (:error routed)
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: dropping envelope — " (pr-str routed))))

      (breakers/tripped? (:project routed) (-> routed :trigger :name))
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: trigger breaker open — skipping "
                       (name (:project routed)) "/"
                       (name (-> routed :trigger :name)))))

      :else
      (let [run (runs/create-run! routed
                                  {:fired-at (clock/now-iso)
                                   :fired-by (System/getenv "USER")})]
        (try
          (run-now! (:id run))
          (catch Exception e
            (binding [*err* *err*]
              (.println ^java.io.PrintWriter *err*
                        (str "ERROR: run-now! threw for "
                             (:id run) " — " (ex-message e))))
            (mark-run-failed! (:id run) e)))))))
```

Add `[nido.coordinator.breakers :as breakers]` to the `:require` block.

- [ ] **Step 2: Update breakers on terminal Run states**

In `run-now!`, after the `transition!` call, look up the trigger config and update the breaker:

```clojure
(defn- run-now!
  [run-id]
  (runs/transition! run-id :running)
  (let [run        (runs/read-run run-id)
        _          (runs/spawn-session-for-run! run)
        worktree   (str (fs/path (cstate/run-session-home-link run-id) "worktree"))
        result     (agent/launch! {:run-id        run-id
                                   :cwd           worktree
                                   :first-message (:first-message run)
                                   :system-prompt (:system-prompt defaults)
                                   :budget        (-> run :limits :budget)})
        next-state (cond
                     (:timed-out? result) :failed
                     (zero? (:exit-code result))
                     (status-file/derive-state-after-exit
                       (status-file/read-status run-id))
                     :else :failed)]
    (let [r (runs/read-run run-id)]
      (runs/write-run! (assoc r :claude-session-id (:claude-session-id result))))
    (runs/transition! run-id next-state)
    (when (= :failed next-state)
      (let [r (runs/read-run run-id)]
        (runs/write-run! (assoc r :error (cond-> {:exit-code (:exit-code result)}
                                           (:timed-out? result)
                                           (assoc :reason :timeout))))))
    ;; Breaker update (new).
    (let [project        (:project run)
          trigger-name   (:trigger run)
          max-failures   (or (-> run :limits :max-failures) 3)]
      (case next-state
        :failed (breakers/record-failure! project trigger-name max-failures)
        :done   (breakers/record-success! project trigger-name)
        :awaiting-review (breakers/record-success! project trigger-name)
        nil))))
```

- [ ] **Step 3: Add `max-failures` to the Run record at create time**

Modify `runs/create-run!` in `src/nido/coordinator/runs.clj` so the Run's `:limits` carries `:max-failures` from the trigger:

```clojure
:limits (or (:limits trigger) {:budget "30m" :max-failures 3})
```

(The `or` already handles trigger-supplied `:limits`. Just ensure the default map includes `:max-failures`.)

- [ ] **Step 4: Add an integration test**

Append to `test/nido/coordinator/e2e_test.clj` (or create `test/nido/coordinator/brakes_e2e_test.clj`) — fire one envelope that fails (`FAKE_CLAUDE_EXIT_CODE=1`) three times, assert the breaker trips:

```clojure
(deftest breaker-trips-after-3-failures-on-same-trigger
  (let [tmp (fs/create-temp-dir)
        ;; A run-spawn that always returns exit 1, no session-id
        fail-launch (fn [opts] {:exit-code 1 :claude-session-id nil :timed-out? false})
        no-session  (fn [run] {})]
    (try
      (with-redefs [cstate/nido-root            (constantly (str tmp))
                    nido.project/list-projects  (constantly {"brian" {:directory "/tmp"}})
                    runs/spawn-session-for-run! no-session
                    nido.coordinator.agent/launch! fail-launch]
        (fs/create-dirs (fs/parent (cstate/triggers-path :brian)))
        (io/write-edn! (cstate/triggers-path :brian)
                       {:triggers [{:name :failing
                                    :source {:type :manual}
                                    :skill :foo
                                    :payload ""}]})
        (cstate/ensure-dirs!)
        (dotimes [_ 3]
          (queue/enqueue! {:target  {:project :brian :trigger :failing}
                           :payload {}})
          (core/tick!))
        (is (breakers/tripped? :brian :failing) "3 failures should trip the breaker")
        ;; A 4th fire should NOT create a new Run (breaker open).
        (let [runs-before (count (filter fs/directory? (fs/list-dir (cstate/runs-dir))))]
          (queue/enqueue! {:target {:project :brian :trigger :failing} :payload {}})
          (core/tick!)
          (is (= runs-before (count (filter fs/directory? (fs/list-dir (cstate/runs-dir)))))
              "skipped envelope should not create a Run")))
      (finally (fs/delete-tree tmp)))))
```

Add the needed requires at the top of the test ns (`nido.coordinator.breakers`, `nido.project`).

- [ ] **Step 5: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test
```

Expected: full suite still green; one new e2e assertion proves the breaker trips.

- [ ] **Step 6: Commit**

```bash
jj desc -m "feat(coordinator): breakers update on transitions; tripped triggers skip envelopes"
jj new
```

---

## Task 7: `bb nido:trigger:enable` and `:disable` tasks

**Files:**
- Modify: `src/tasks/nido_trigger.clj`
- Modify: `bb.edn`

CLI wrappers around `breakers/enable!` and `breakers/disable-by-user!`.

- [ ] **Step 1: Add task functions**

Append to `src/tasks/nido_trigger.clj`:

```clojure
(defn enable
  "bb nido:trigger:enable :project <p> <trigger-name>"
  [& args]
  (let [[positionals opts] (task-args/split-args args)
        project-kw         (some-> (:project opts) str keyword)
        t-name             (some-> (first positionals) str keyword)]
    (when-not project-kw (println "Missing :project") (System/exit 2))
    (when-not t-name     (println "Missing trigger name") (System/exit 2))
    (breakers/enable! project-kw t-name)
    (println "Trigger enabled:" (name project-kw) "/" (name t-name))))

(defn disable
  "bb nido:trigger:disable :project <p> <trigger-name> [:note <str>]"
  [& args]
  (let [[positionals opts] (task-args/split-args args)
        project-kw         (some-> (:project opts) str keyword)
        t-name             (some-> (first positionals) str keyword)
        note               (some-> (:note opts) str)]
    (when-not project-kw (println "Missing :project") (System/exit 2))
    (when-not t-name     (println "Missing trigger name") (System/exit 2))
    (breakers/disable-by-user! project-kw t-name (or note ""))
    (println "Trigger disabled:" (name project-kw) "/" (name t-name)
             (if note (str "(note: " note ")") ""))))
```

Add `[nido.coordinator.breakers :as breakers]` to the `:require` block.

- [ ] **Step 2: Register the bb tasks**

In `bb.edn`, after the existing `nido:trigger:*` entries:

```clojure
nido:trigger:enable
{:doc "Clear circuit-breaker / re-enable a trigger: :project <p> <trigger>"
 :task (apply nido-trigger/enable *command-line-args*)}

nido:trigger:disable
{:doc "Manually disable a trigger: :project <p> <trigger> [:note <str>]"
 :task (apply nido-trigger/disable *command-line-args*)}
```

- [ ] **Step 3: Manual smoke**

```bash
cd /Users/yabmas/Code/nido
mkdir -p ~/.nido/projects/brian
cat > ~/.nido/projects/brian/triggers.edn <<'EOF'
{:triggers [{:name :smoke :source {:type :manual} :skill :echo :payload "msg={{event/msg}}"}]}
EOF
bb nido:trigger:disable :project brian smoke :note "testing"
cat ~/.nido/coordinator/breakers.edn
bb nido:trigger:enable :project brian smoke
cat ~/.nido/coordinator/breakers.edn

# Cleanup
rm -f ~/.nido/coordinator/breakers.edn ~/.nido/projects/brian/triggers.edn
```

- [ ] **Step 4: Commit**

```bash
jj desc -m "feat(nido): bb nido:trigger:enable and :disable tasks"
jj new
```

---

## Task 8: Anomaly auto-halt

**Files:**
- Create: `src/nido/coordinator/anomaly.clj`
- Create: `test/nido/coordinator/anomaly_test.clj`
- Modify: `src/nido/coordinator/core.clj`

Daemon-wide watchdog: if Runs spawn too fast or fail too fast, halt the daemon automatically. Thresholds (defaults):

- More than `5` Run spawns in `60s`
- Or `3` or more failures in `300s`

When tripped, `halt!` with `:source :auto :reason {...}`.

In-memory ring buffer of recent timestamps; no on-disk state needed (it's reset on daemon restart, which is fine — anomaly detection is about LIVE runaway, not historical).

- [ ] **Step 1: Write the failing test**

Create `test/nido/coordinator/anomaly_test.clj`:

```clojure
(ns nido.coordinator.anomaly-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.anomaly :as anomaly]
   [nido.coordinator.clock :as clock]))

(deftest record-and-check-spawn-burst
  (let [d (anomaly/empty-detector)
        ts-list ["2026-05-13T10:00:00Z"
                 "2026-05-13T10:00:10Z"
                 "2026-05-13T10:00:20Z"
                 "2026-05-13T10:00:30Z"
                 "2026-05-13T10:00:40Z"
                 "2026-05-13T10:00:50Z"]   ; 6 within 60s
        d' (reduce #(anomaly/record-spawn %1 %2) d ts-list)]
    (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:55Z")]
      (let [check (anomaly/check d' {:spawn-window-ms 60000 :spawn-threshold 5
                                     :fail-window-ms 300000 :fail-threshold 3})]
        (is (= :spawn-burst (:trip check)))
        (is (= 6 (:count check)))))))

(deftest record-and-check-failure-burst
  (let [d  (anomaly/empty-detector)
        ts ["2026-05-13T10:00:00Z" "2026-05-13T10:01:00Z" "2026-05-13T10:02:00Z"]
        d' (reduce #(anomaly/record-failure %1 %2) d ts)]
    (with-redefs [clock/now-iso (constantly "2026-05-13T10:02:30Z")]
      (let [check (anomaly/check d' {:spawn-window-ms 60000 :spawn-threshold 5
                                     :fail-window-ms 300000 :fail-threshold 3})]
        (is (= :fail-burst (:trip check)))))))

(deftest below-threshold-returns-nil
  (let [d  (anomaly/empty-detector)
        d' (reduce #(anomaly/record-spawn %1 %2) d
                   ["2026-05-13T10:00:00Z" "2026-05-13T10:30:00Z"])]
    (with-redefs [clock/now-iso (constantly "2026-05-13T10:35:00Z")]
      (is (nil? (anomaly/check d' {:spawn-window-ms 60000 :spawn-threshold 5
                                   :fail-window-ms 300000 :fail-threshold 3}))))))

(deftest old-events-pruned
  ;; Events older than max(spawn-window, fail-window) should not count.
  (let [d  (anomaly/empty-detector)
        d' (reduce #(anomaly/record-spawn %1 %2) d
                   ["2026-05-13T08:00:00Z" "2026-05-13T08:00:10Z"
                    "2026-05-13T08:00:20Z" "2026-05-13T08:00:30Z"
                    "2026-05-13T08:00:40Z" "2026-05-13T08:00:50Z"])]
    (with-redefs [clock/now-iso (constantly "2026-05-13T10:00:00Z")]
      (is (nil? (anomaly/check d' {:spawn-window-ms 60000 :spawn-threshold 5
                                   :fail-window-ms 300000 :fail-threshold 3}))))))
```

- [ ] **Step 2: Run test to verify failure**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.anomaly-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 3: Implement**

Create `src/nido/coordinator/anomaly.clj`:

```clojure
(ns nido.coordinator.anomaly
  "Rate-based anomaly detection for runaway-spawn or fail-loop conditions.
   In-memory only; resets on daemon restart. See spec §Safety brakes /
   Anomaly auto-halt."
  (:require
   [nido.coordinator.clock :as clock]))

(defn empty-detector []
  {:spawns []      ; vector of ISO timestamps
   :failures []})

(defn record-spawn [det iso-ts]
  (update det :spawns conj iso-ts))

(defn record-failure [det iso-ts]
  (update det :failures conj iso-ts))

(defn- ms-between [from-iso to-iso]
  (try
    (- (.toEpochMilli (java.time.Instant/parse to-iso))
       (.toEpochMilli (java.time.Instant/parse from-iso)))
    (catch Exception _ 0)))

(defn- within? [event-iso now-iso window-ms]
  (let [delta (ms-between event-iso now-iso)]
    (and (>= delta 0) (<= delta window-ms))))

(defn check
  "Return {:trip :spawn-burst | :fail-burst :count <n>} when a threshold
   is exceeded; nil otherwise."
  [det {:keys [spawn-window-ms spawn-threshold fail-window-ms fail-threshold]}]
  (let [now    (clock/now-iso)
        spawns (count (filter #(within? % now spawn-window-ms) (:spawns det)))
        fails  (count (filter #(within? % now fail-window-ms)  (:failures det)))]
    (cond
      (>= spawns spawn-threshold) {:trip :spawn-burst :count spawns}
      (>= fails  fail-threshold)  {:trip :fail-burst  :count fails}
      :else                       nil)))
```

- [ ] **Step 4: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test :only nido.coordinator.anomaly-test
```

Expected: PASS — 4 tests.

- [ ] **Step 5: Wire the detector into `core.clj`**

Hold the detector in an atom inside the `core` namespace and call it from `tick!`. When `check` returns a trip, call `halt!` with `:source :auto :reason {...}`.

```clojure
;; Add near `defaults` in core.clj:
(def ^:private anomaly-thresholds
  {:spawn-window-ms 60000  :spawn-threshold 5
   :fail-window-ms  300000 :fail-threshold 3})

(defonce ^:private !detector (atom (anomaly/empty-detector)))

;; In process-envelope!'s success path, after run-now! returns:
(defn- process-envelope! [envelope triggers-by-project]
  (let [routed (events/route envelope triggers-by-project)]
    (cond
      (:error routed)
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: dropping envelope — " (pr-str routed))))

      (breakers/tripped? (:project routed) (-> routed :trigger :name))
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: trigger breaker open — skipping "
                       (name (:project routed)) "/"
                       (name (-> routed :trigger :name)))))

      :else
      (let [run (runs/create-run! routed
                                  {:fired-at (clock/now-iso)
                                   :fired-by (System/getenv "USER")})]
        (swap! !detector anomaly/record-spawn (clock/now-iso))
        (try
          (run-now! (:id run))
          ;; If the Run finished :failed, record the failure event for anomaly.
          (let [final (runs/read-run (:id run))]
            (when (= :failed (:state final))
              (swap! !detector anomaly/record-failure (clock/now-iso))))
          (catch Exception e
            (swap! !detector anomaly/record-failure (clock/now-iso))
            (binding [*err* *err*]
              (.println ^java.io.PrintWriter *err*
                        (str "ERROR: run-now! threw for "
                             (:id run) " — " (ex-message e))))
            (mark-run-failed! (:id run) e)))))))

;; In tick!, after envelope processing, check anomaly:
(defn tick! []
  (let [triggers-by-project (load-all-triggers)
        halt-info           (halt/read-halt-info)]
    (if halt-info
      (heartbeat/write! {:status :halted
                         :halted-by (:source halt-info)
                         :halt-note (:note halt-info)
                         :slots-in-use 0})
      (do
        (heartbeat/write! {:status :running :slots-in-use 0})
        (doseq [env (queue/drain!)]
          (process-envelope! env triggers-by-project))
        ;; After draining, check anomaly thresholds.
        (when-let [trip (anomaly/check @!detector anomaly-thresholds)]
          (halt/halt! {:source :auto
                       :reason (:trip trip)
                       :details trip
                       :note    (str "auto-halt: " (name (:trip trip))
                                     " count=" (:count trip))}))))))
```

Add `[nido.coordinator.anomaly :as anomaly]` to the `:require` block.

- [ ] **Step 6: Manual smoke (optional)**

Hard to reproduce without spamming the queue; the unit tests cover the detector and the `halt!` call site is straightforward. If you want a quick sanity check:

```bash
cd /Users/yabmas/Code/nido
bb -e "
(require '[nido.coordinator.anomaly :as a])
(require '[nido.coordinator.clock :as clock])
(let [det (reduce #(a/record-spawn %1 %2) (a/empty-detector)
                  (repeat 6 (clock/now-iso)))]
  (println (a/check det {:spawn-window-ms 60000 :spawn-threshold 5
                         :fail-window-ms  300000 :fail-threshold 3})))
"
# Expected: {:trip :spawn-burst :count 6}
```

- [ ] **Step 7: Commit**

```bash
jj desc -m "feat(coordinator): anomaly detector + auto-halt on burst/fail-loop"
jj new
```

---

## Task 9: TUI — alerts segment + `h` / `c` keys

**Files:**
- Modify: `src/nido/coordinator/runs_view.clj`
- Modify: `src/nido/tui.clj`

The runs-screen status bar gains an alerts segment, and the runs screen gains `h` (halt/resume) and `c` (clear breaker) keys per the spec.

- [ ] **Step 1: Extend `runs-view/read-coordinator-status` with alert counts**

In `src/nido/coordinator/runs_view.clj`, add a `read-alerts` function and have the existing status carry counts. Keep the original return shape backwards-compatible by adding `:alerts` rather than restructuring:

```clojure
(require '[nido.coordinator.breakers :as breakers]
         '[nido.coordinator.halt :as halt])

(defn read-alerts
  "Aggregate alert summary for the TUI status bar.
   Returns {:halted? <bool> :halt-source <kw or nil> :breakers <int>}."
  []
  (let [halt-info (halt/read-halt-info)]
    {:halted?     (boolean halt-info)
     :halt-source (:source halt-info)
     :halt-note   (:note halt-info)
     :breakers    (count (breakers/tripped-triggers))}))

(defn read-coordinator-status
  "Read ~/.nido/coordinator/status.edn ... [existing docstring]"
  []
  (let [base (read-coordinator-status*)]   ; rename existing impl to *-impl
    (assoc base :alerts (read-alerts))))
```

(The cleanest path is: rename the existing `read-coordinator-status` to `read-coordinator-status*` (private), keep the public one as a thin wrapper that adds `:alerts`. Match whatever rename pattern is least invasive.)

- [ ] **Step 2: Update `runs-view-test` for the new fields**

In `test/nido/coordinator/runs_view_test.clj`, add:

```clojure
(deftest read-coordinator-status-includes-alerts
  (with-tmp-runs-dir
    (fn []
      (let [s (rv/read-coordinator-status)]
        (is (contains? s :alerts))
        (is (false? (-> s :alerts :halted?)))
        (is (= 0 (-> s :alerts :breakers)))))))
```

- [ ] **Step 3: Render alerts in the TUI status bar**

In `src/nido/tui.clj`, modify `status-bar`:

```clojure
(defn- status-bar []
  (let [{:keys [status reachable? slots-in-use alerts]} (runs-view/read-coordinator-status)
        {:keys [halted? halt-source halt-note breakers]} alerts]
    (str (style/render label-style "Coordinator: ")
         (style/render (if halted? warning-style
                         (if reachable? status-style warning-style))
                       (if halted?
                         (str "halted (" (name halt-source) ")")
                         (name status)))
         (when (and halted? halt-note) (str " — " halt-note))
         "  •  "
         (style/render label-style "Slots: ")
         (or slots-in-use 0)
         (when (pos? breakers)
           (str "  •  "
                (style/render warning-style
                              (str "⚠ " breakers " trigger"
                                   (when (> breakers 1) "s")
                                   " in breaker")))))))
```

- [ ] **Step 4: Add `h` (halt toggle) and `c` (clear breaker) to update-runs**

```clojure
(defn- update-runs [state msg]
  (cond
    (msg/key-match? msg "enter")
    ;; ... existing ...

    (msg/key-match? msg "w")
    ;; ... existing ...

    (msg/key-match? msg "d")
    ;; ... existing ...

    (msg/key-match? msg "f")
    ;; ... existing ...

    (msg/key-match? msg "h")
    (open-halt-confirm state)

    (msg/key-match? msg "c")
    (open-clear-breaker-picker state)

    :else
    (let [[lst cmd] (item-list/list-update (:list state) msg)]
      [(assoc state :list lst) cmd])))
```

- [ ] **Step 5: Halt confirm modal**

```clojure
(defn- open-halt-confirm [state]
  (if (halt/halted?)
    [(-> state
         (assoc :modal :halt-resume-confirm))
     nil]
    [(-> state
         (assoc :modal :halt-confirm))
     nil]))

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
```

Wire these into `update-fn` alongside the other modal arms. Add `[nido.coordinator.halt :as halt]` to the `:require` block.

- [ ] **Step 6: Clear-breaker picker modal**

```clojure
(defn- open-clear-breaker-picker [state]
  (let [tripped (breakers/tripped-triggers)]
    (if (empty? tripped)
      [state nil]    ; nothing to clear
      [(-> state
           (assoc :modal :clear-breaker)
           (assoc :modal-target {:tripped tripped :cursor 0}))
       nil])))

(defn- update-clear-breaker [state msg]
  (let [{:keys [tripped cursor]} (:modal-target state)]
    (cond
      (msg/key-match? msg "escape") [(close-modal state) nil]

      (msg/key-match? msg "up")
      [(assoc-in state [:modal-target :cursor] (max 0 (dec cursor))) nil]

      (msg/key-match? msg "down")
      [(assoc-in state [:modal-target :cursor] (min (dec (count tripped)) (inc cursor))) nil]

      (msg/key-match? msg "enter")
      (let [{:keys [project trigger]} (nth tripped cursor)]
        (breakers/enable! project trigger)
        [(-> state (close-modal)
             (assoc :status (str "Breaker cleared: " (name project) "/" (name trigger))))
         nil])

      :else [state nil])))
```

Wire into `update-fn`. Add `[nido.coordinator.breakers :as breakers]` to `:require`.

- [ ] **Step 7: Modal header/footer/body arms**

Add to `header`, `footer`, `modal-body`:

```clojure
;; header:
:halt-confirm         "nido — halt coordinator?"
:halt-resume-confirm  "nido — resume coordinator?"
:clear-breaker        "nido — clear breaker"

;; footer:
:halt-confirm         "[y] halt  [n/esc] cancel"
:halt-resume-confirm  "[y] resume  [n/esc] cancel"
:clear-breaker        "[↑↓] move  [↵] clear  [esc] cancel"

;; modal-body:
:halt-confirm
"Halt the coordinator? New envelopes will queue; existing in-flight\nRuns continue to terminal state."

:halt-resume-confirm
(str "Resume coordinator?\n\n"
     (when-let [h (halt/read-halt-info)]
       (str "Currently halted by " (name (:source h))
            (when (:note h) (str " (" (:note h) ")"))
            ".")))

:clear-breaker
(let [{:keys [tripped cursor]} (:modal-target state)]
  (str/join "\n"
            (map-indexed (fn [i {:keys [project trigger]}]
                           (str (if (= i cursor) "▸ " "  ")
                                (name project) "/" (name trigger)))
                         tripped)))
```

- [ ] **Step 8: Update the runs-screen footer to include the new keys**

In `footer`, change the `:runs` entry to:

```clojure
:runs "[↵] enter  [w]orktree  [d]etails  [f]ire  [h]alt  [c]lear breaker  [s]essions  [q]uit"
```

- [ ] **Step 9: Run tests**

```bash
cd /Users/yabmas/Code/nido && bb nido:test 2>&1 | tail -3
```

Expected: full suite passes. The TUI itself isn't unit-tested; the new modal handlers are exercised by visual smoke.

- [ ] **Step 10: Visual smoke (optional but worth doing)**

```bash
cd /Users/yabmas/Code/nido
# Drop a fake tripped breaker
bb -e "
(require '[nido.coordinator.breakers :as b])
(b/disable-by-user! :brian :fake \"manual test\")
"
bb nido:tui
# 'r' → status bar shows "⚠ 1 trigger in breaker"
# 'c' → modal → '↵' → "Breaker cleared: brian/fake"
# 'h' → modal → 'y' → "Coordinator halted." + status bar updates
# 'h' → modal (resume) → 'y' → "Coordinator resumed."
# 'q' to quit
# Cleanup:
rm -f ~/.nido/coordinator/breakers.edn ~/.nido/coordinator/halted.edn
```

- [ ] **Step 11: Commit**

```bash
jj desc -m "feat(tui): runs screen alerts + h halt + c clear breaker"
jj new
```

---

## Task 10: Documentation + closing

**Files:**
- Modify: `CLAUDE.md`
- Modify: `docs/skill-conventions-for-triggers.md` (small addendum)

Update the project-level pointer to mention the brakes + kill switch.

- [ ] **Step 1: Update `CLAUDE.md`**

Find the "Coordination layer (stage 1a)" section added in Stage 1a, Task 20, and update it:

```markdown
## Coordination layer (stages 1–2)

A foreground nido coordinator (`bb nido:coordinator:run`) watches `~/.nido/coordinator/queue/` for manual-trigger envelopes and spawns Run-owned sessions that auto-launch claude with a configured skill. Triggers live at `~/.nido/projects/<project>/triggers.edn`.

Fire a Run: `bb nido:trigger:fire :project brian <trigger-name> :<payload-key> <value>`
Inspect Runs: `bb nido:runs:list` and `bb nido:runs:show <run-id>`, or in the TUI press `r`.

**Safety brakes (Stage 2):** per-Run wall-clock budget (`:limits.budget`, default 30m, hard SIGTERM→SIGKILL), per-trigger circuit breaker (`:limits.max-failures`, default 3), daemon-wide anomaly auto-halt, and a kill switch (`bb nido:halt`). Resume with `bb nido:coordinator:resume`. The TUI runs screen surfaces breaker/halt alerts and exposes `h`/`c` keys.

Full design: `docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`. Skill conventions for trigger targets: `docs/skill-conventions-for-triggers.md`.

The coordinator is foreground-only at stages 1–2 — bring it up explicitly. Stage 3 will add a background harness; Stage 4 launchd.
```

- [ ] **Step 2: Note auto-halt in the skill-conventions doc**

Append to `docs/skill-conventions-for-triggers.md`:

```markdown
## What the brakes mean for a skill author

- Your skill should respect its wall-clock budget. If the daemon's budget (default 30m) is shorter than what your skill needs, fail fast or split into phases.
- A skill that emits `:phase :error` to `_run-status.edn` counts as a failure toward the per-trigger circuit breaker. After 3 consecutive failures, the trigger is auto-disabled until the user runs `bb nido:trigger:enable :project <p> <trigger>`.
- The daemon may halt itself if it spawns too many Runs too fast or sees a fail-loop. From a skill's perspective this looks identical to the daemon stopping for any reason — your Run is left in its current state.
```

- [ ] **Step 3: Run the full suite + visual smoke**

```bash
cd /Users/yabmas/Code/nido && bb nido:test 2>&1 | tail -3
```

Expected: 55 tests (52 from Stage 1 + ~10 new across Tasks 1, 4, 5, 8, plus the 3 io tests in Task 0) — exact count will depend on consolidation. The key check is "0 failures, 0 errors".

- [ ] **Step 4: Commit**

```bash
jj desc -m "docs(nido): Stage 2 brakes — CLAUDE.md + skill-conventions update"
jj new
```

---

## Closing checklist

- [ ] All 11 tasks committed as discrete jj changes.
- [ ] `bb nido:test` passes cleanly.
- [ ] `bb tasks | grep -E 'coordinator|trigger:|halt'` shows the new entries: `:coordinator:halt`, `:coordinator:resume`, `:halt`, `:trigger:enable`, `:trigger:disable`.
- [ ] Walk-through smoke: fire a trigger that fails (use the smoke setup, but with `:skill :nonexistent` so claude exits 1) three times → confirm the trigger gets breakered → confirm `bb nido:trigger:enable` clears it.
- [ ] `bb nido:halt` writes `halted.edn`; `bb nido:coordinator:status` reports halted state; `bb nido:coordinator:resume` removes it.
- [ ] TUI `h` and `c` keys exercise the modals and show the right status bar deltas.

## Spec coverage map

| Spec brake | Task |
|---|---|
| Wall-clock budget (per Run, default 30m, SIGTERM→SIGKILL) | Task 4 |
| Circuit breaker (3 consecutive failures, auto-disable) | Tasks 5, 6 |
| Kill switch (`bb nido:halt`) | Tasks 1, 2, 3 |
| Anomaly auto-halt | Task 8 |
| Visible state (alerts in TUI status bar) | Task 9 |
| `h` / `c` TUI keys | Task 9 |
| Atomic state writes (preventing torn reads) | Task 0 |

**Deferred to later stages (not Stage 2):**
- Global parallel cap (theoretical without async run-now!)
- Per-project parallel cap (same)
- Per-trigger cooldown timer (covered by manual disable + breaker)
- Dry-run mode for triggers (`:dry-run?` field designed-in; not enforced)
- Background daemon → Stage 3
- launchd → Stage 4

---

## Notes for the implementer

- **Stage 1a's smoke surfaced two production bugs**: missing `<run-dir>/session-home` symlink and lack of exception isolation in `process-envelope!`. Both shipped as fix commits. Stage 2's Task 6 builds on the exception-isolation pattern — keep the try/catch around `run-now!`.
- **`runs/transition!` is strict** (rejects invalid transitions). Task 6's breaker hooks happen AFTER `transition!` returns successfully, so the breaker view is consistent with the Run record. If you need to transition to `:halted` from outside the normal path (kill switch racing an in-flight Run), use a separate transition that doesn't traverse the normal lifecycle — but Stage 2's current scope doesn't require that; the agent process exit handles it.
- **Anomaly detector state is in-memory only**. That's intentional — daemon restart resets it, which is fine; anomaly detection is about "right now," not historical.
- **`max-failures` resolution order**: trigger's `:limits.max-failures` → Run's persisted `:limits.max-failures` (set at create-run! time) → default `3`. Task 6's breaker hook reads from the Run, so the value seen at Run creation is what counts.
