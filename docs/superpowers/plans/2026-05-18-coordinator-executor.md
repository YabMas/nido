# Coordinator Executor + Envelope Priority — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the coordinator's synchronous `run-now!` with a slot-based scheduler that runs up to N Runs in parallel, ordered by envelope `:priority`. Lift `:global-parallel-cap` from a cosmetic config to an enforced limit. No behavior change for any existing trigger (envelopes default to priority 0 → FIFO).

**Architecture:** A single `nido.coordinator.executor` namespace holding an atom: `{:queue (sorted-set-by …) :in-flight {run-id → future} :cap N}`. The daemon's outer loop drains the on-disk envelope queue into `executor/submit!`, then calls `executor/tick!` which (1) reaps finished futures, (2) promotes up to `cap` queued Runs into new futures. Each future does what `run-now!` does today (transition → spawn session → `agent/launch!` → transition). babashka-compatible — no `java.util.concurrent.PriorityBlockingQueue` (not available in bb), uses Clojure `sorted-set-by` + `future` + `locking` instead.

**Tech Stack:** Babashka (bb), `clojure.test`, Malli for schemas. Existing nido namespaces: `coordinator.core`, `coordinator.queue`, `coordinator.runs`, `coordinator.agent`, `coordinator.triggers`, `coordinator.events`, `tui`.

**Spec reference:** [2026-05-18-notion-triage-agent-design.md §Coordinator changes](../specs/2026-05-18-notion-triage-agent-design.md). This plan delivers Stage 1 of the six-stage rollout.

---

## File Structure

**New:**
- `src/nido/coordinator/executor.clj` — slot scheduler (atom + sorted-set + futures).
- `test/nido/coordinator/executor_test.clj` — unit tests for the scheduler.

**Modified:**
- `src/nido/coordinator/queue.clj` — `enqueue!` sets `:received-at`; default `:priority 0`.
- `src/nido/coordinator/triggers.clj` — `Trigger` schema gains optional `:priority int?`.
- `src/nido/coordinator/events.clj` — `route` stamps trigger `:priority` onto envelopes.
- `src/nido/coordinator/core.clj` — daemon loop calls `executor/submit!` + `executor/tick!` instead of synchronous `run-now!`. `run-now!` becomes the body of the future the executor spawns.
- `src/nido/coordinator/runs_view.clj` — exposes executor snapshot for the TUI header.
- `src/nido/tui.clj` — header line shows `in-flight: N/cap · queued: M`.
- `test/nido/coordinator/queue_test.clj` — assertions for `:received-at` + `:priority` defaults (create if missing — there isn't one today).
- `test/nido/coordinator/triggers_test.clj` — assertion that `:priority` is accepted.
- `test/nido/coordinator/events_test.clj` — assertion that routing carries `:priority` through (create if missing).

**Untouched:** `coordinator.runs` (state machine, transitions), `coordinator.agent` (`launch!`), sources, the TUI's keystroke handlers. The triage skill is a later plan.

---

## Task 1 — Envelope schema: `:priority` + `:received-at`

**Files:**
- Modify: `src/nido/coordinator/queue.clj:45-51`
- Test: `test/nido/coordinator/queue_test.clj` (create if missing)

The envelope today is `{:target|:broadcast … :payload … :created-at <iso>}`. After this task it's `{… :payload … :created-at <iso> :received-at <iso> :priority <int, default 0>}`. `:received-at` is set by `enqueue!` (the queue is the system-of-record for "when we observed it"); `:priority` is set by the caller (trigger routing — Task 3).

- [ ] **Step 1: Write the failing test** for `enqueue!` setting `:received-at`.

Create `test/nido/coordinator/queue_test.clj` if it doesn't exist:

```clojure
(ns nido.coordinator.queue-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn- with-tmp-nido [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (fs/create-dirs (cstate/queue-dir))
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest enqueue-sets-received-at
  (with-tmp-nido
    (fn []
      (let [path (queue/enqueue! {:target {:project :p :trigger :t} :payload {}})
            env  (io/read-edn path)]
        (is (string? (:received-at env))
            ":received-at should be set as an ISO timestamp"))
      ;; clean up the file enqueue! wrote
      (queue/drain!))))

(deftest enqueue-defaults-priority-to-zero
  (with-tmp-nido
    (fn []
      (let [path (queue/enqueue! {:target {:project :p :trigger :t} :payload {}})
            env  (io/read-edn path)]
        (is (= 0 (:priority env))
            ":priority should default to 0 when not provided"))
      (queue/drain!))))

(deftest enqueue-preserves-explicit-priority
  (with-tmp-nido
    (fn []
      (let [path (queue/enqueue! {:target {:project :p :trigger :t}
                                   :payload {} :priority 42})
            env  (io/read-edn path)]
        (is (= 42 (:priority env))))
      (queue/drain!))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/yabmas/Code/nido && bb test test/nido/coordinator/queue_test.clj
```

Expected: 3 failures — `:received-at` is nil, `:priority` is nil.

- [ ] **Step 3: Modify `enqueue!` to set both fields**

Edit `src/nido/coordinator/queue.clj` lines 45-51:

```clojure
(defn enqueue!
  "Write an envelope to the queue with a fresh UUID filename.
   Stamps :created-at (when the caller produced it), :received-at (when
   we observed it), and defaults :priority to 0 if absent."
  [envelope]
  (let [uuid (str (java.util.UUID/randomUUID))
        path (str (fs/path (cstate/queue-dir) (str uuid ".edn")))
        now  (clock/now-iso)
        env  (cond-> envelope
               true (assoc :received-at now)
               (not (contains? envelope :created-at)) (assoc :created-at now)
               (not (contains? envelope :priority))   (assoc :priority 0))]
    (io/write-edn! path env)
    path))
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/yabmas/Code/nido && bb test test/nido/coordinator/queue_test.clj
```

Expected: 3 passes.

- [ ] **Step 5: Run the full test suite to make sure nothing else regressed**

```bash
cd /Users/yabmas/Code/nido && bb test
```

Expected: all green.

- [ ] **Step 6: Commit**

```bash
jj new && jj desc -m "feat(coordinator/queue): add :priority + :received-at to envelope

Envelopes now carry :received-at (set by enqueue!) and :priority
(default 0). Backwards-compatible with all existing triggers — every
envelope written today gets priority 0, preserving FIFO order until
triggers start setting :priority explicitly."
```

---

## Task 2 — Trigger schema: accept optional `:priority`

**Files:**
- Modify: `src/nido/coordinator/triggers.clj:12-23`
- Test: `test/nido/coordinator/triggers_test.clj` (existing)

Trigger config gains `[:priority {:optional true} int?]`. (`:priority-from` is part of Plan C — the Notion source upgrade — since it requires source-level resolution.)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/coordinator/triggers_test.clj`:

```clojure
(deftest schema-accepts-priority
  (is (m/validate triggers/Trigger
                  (assoc minimal-trigger :priority 10))))

(deftest schema-rejects-non-int-priority
  (is (not (m/validate triggers/Trigger
                       (assoc minimal-trigger :priority "high")))))
```

- [ ] **Step 2: Run to verify they fail**

```bash
bb test test/nido/coordinator/triggers_test.clj
```

Expected: `schema-accepts-priority` fails — `:priority` isn't a known key on the closed map.

- [ ] **Step 3: Add `:priority` to the schema**

Edit `src/nido/coordinator/triggers.clj` lines 12-23:

```clojure
(def Trigger
  [:map {:closed true}
   [:name           keyword?]
   [:source         [:map [:type keyword?]]]
   [:skill          keyword?]
   [:payload        string?]
   [:filter      {:optional true} [:map-of keyword? any?]]
   [:payload-key {:optional true} keyword?]
   [:agent       {:optional true} keyword?]
   [:limits      {:optional true} [:map-of keyword? any?]]
   [:priority    {:optional true} int?]
   [:dry-run?    {:optional true} boolean?]
   [:enabled?    {:optional true} boolean?]])
```

- [ ] **Step 4: Run to verify passing**

```bash
bb test test/nido/coordinator/triggers_test.clj
```

Expected: both new tests pass.

- [ ] **Step 5: Commit**

```bash
jj new && jj desc -m "feat(coordinator/triggers): accept optional :priority int

Triggers can declare a constant envelope priority. Higher = pops
sooner from the executor queue. :priority-from (source-resolved
per-event override) lands later with the Notion source upgrade."
```

---

## Task 3 — Routing carries trigger `:priority` onto the envelope

**Files:**
- Modify: `src/nido/coordinator/events.clj` (the `route` function — find via grep, look for the call that builds the fire-request)
- Test: `test/nido/coordinator/events_test.clj` (create if missing)

When a broadcast envelope matches a trigger, the routing step today builds a fire-request from `(trigger, payload)`. We need the resulting Run (created in core.clj from that fire-request) to know its envelope priority. Cleanest path: routing returns fire-requests that carry `:priority`, the caller propagates it into the Run record so the executor can sort.

First, survey the actual route function shape — the explore output didn't show it. Read `src/nido/coordinator/events.clj` and `src/nido/coordinator/core.clj` around `process-envelope!` (line 156) to confirm the data flow before writing code.

- [ ] **Step 1: Read the actual code** to confirm the route → fire-request → create-run! data flow.

```bash
cd /Users/yabmas/Code/nido && grep -n "fire-request\|route\|create-run!" src/nido/coordinator/events.clj src/nido/coordinator/core.clj
```

Identify (a) where `route` returns its vector of requests, (b) where each request reaches `create-run!`, (c) what the request map looks like.

- [ ] **Step 2: Write the failing test**

Create `test/nido/coordinator/events_test.clj` (skeleton — adapt selector + arg shape to match the real `route` signature you found in Step 1):

```clojure
(ns nido.coordinator.events-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.events :as events]))

(def trigger-with-priority
  {:name :t1 :source {:type :notion-view} :skill :triage-bug
   :payload "x" :priority 10})

(def trigger-without-priority
  {:name :t2 :source {:type :notion-view} :skill :triage-bug
   :payload "x"})

(def envelope
  {:broadcast {:type :notion-view :source-config {} :payload {:page-id "p1"}}
   :received-at "2026-05-18T12:00:00Z" :priority 0})

(deftest route-uses-trigger-priority-when-set
  (let [requests (events/route envelope [trigger-with-priority])]
    (is (= 10 (-> requests first :priority)))))

(deftest route-defaults-priority-to-zero
  (let [requests (events/route envelope [trigger-without-priority])]
    (is (= 0 (-> requests first :priority)))))
```

- [ ] **Step 3: Run to verify it fails**

```bash
bb test test/nido/coordinator/events_test.clj
```

Expected: failures — `:priority` key missing on the fire-request.

- [ ] **Step 4: Update `route` to stamp `:priority`**

In the function that constructs each fire-request, add:

```clojure
;; inside the per-trigger map construction:
:priority (or (:priority trigger) 0)
```

(Use the exact local-var names you found in Step 1 — show the diff in the actual file, don't paste verbatim.)

- [ ] **Step 5: Run to verify passing**

```bash
bb test test/nido/coordinator/events_test.clj
```

- [ ] **Step 6: Propagate `:priority` from fire-request into the Run record**

Edit `src/nido/coordinator/runs.clj:105-133` (`create-run!`). The `run` map already destructures from the fire-request. Add a `:priority` field to the persisted Run:

```clojure
;; in the let-bound `run` map (around line 116):
:priority        (or (:priority fire-request) 0)
```

(Where `fire-request` is whatever local name the function uses for the input map — it's the destructured first arg.)

Also update the Run schema (lines 19-39 — find it via grep `def Run`) to include:

```clojure
[:priority int?]
```

- [ ] **Step 7: Write a test for `create-run!` carrying `:priority`**

Append to `test/nido/coordinator/runs_test.clj` (find/create it):

```clojure
(deftest create-run-carries-priority
  (with-tmp-nido
    (fn []
      (let [run (runs/create-run!
                  {:project :p
                   :trigger {:name :t :skill :triage-bug
                             :payload "x" :source {:type :test}}
                   :payload {}
                   :priority 7}
                  {})]
        (is (= 7 (:priority run)))))))
```

- [ ] **Step 8: Run full suite**

```bash
bb test
```

Expected: green.

- [ ] **Step 9: Commit**

```bash
jj new && jj desc -m "feat(coordinator/events): propagate trigger :priority to Run

route() stamps each fire-request with the trigger's :priority (default
0). create-run! persists it on the Run record so the executor can sort
queued Runs without re-reading triggers."
```

---

## Task 4 — Executor namespace (the scheduler)

**Files:**
- Create: `src/nido/coordinator/executor.clj`
- Test: `test/nido/coordinator/executor_test.clj`

A single namespace with three public functions: `configure!`, `submit!`, `tick!`, and a read-only `snapshot`. State lives in an atom keyed by `:queue` (sorted set of slot-items), `:in-flight` (map run-id → future), and `:cap` (int). All mutation goes through a `locking` block on a dedicated monitor so the daemon's reap+promote-per-tick is atomic.

Babashka-compatible — no `PriorityBlockingQueue`. Worker model is "one `future` per slot-item promotion" rather than "long-lived worker thread per slot." Equivalent behavior, simpler code.

- [ ] **Step 1: Write the failing tests**

```clojure
(ns nido.coordinator.executor-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [nido.coordinator.executor :as ex]))

(defn- reset-executor! [_]
  (ex/configure! {:global-cap 2})
  (ex/clear!))                          ;; helper we'll add for tests

(use-fixtures :each reset-executor!)

(deftest submit-then-tick-spawns-future
  (let [spawned (atom [])]
    (ex/submit! "run-1" 0)
    (ex/tick! (fn [rid] (swap! spawned conj rid)))
    (Thread/sleep 50)                    ;; let the future run
    (is (= ["run-1"] @spawned))))

(deftest higher-priority-pops-first
  (let [spawned (atom [])
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 100))]
    (ex/configure! {:global-cap 1})
    (ex/submit! "low"  1)
    (ex/submit! "high" 10)
    (ex/tick! slow)
    (Thread/sleep 30)                    ;; first promotion done
    (ex/tick! slow)                      ;; reap nothing, slot full
    (Thread/sleep 200)                   ;; "high" finishes
    (ex/tick! slow)                      ;; reap high, promote low
    (Thread/sleep 200)
    (is (= ["high" "low"] @spawned))))

(deftest fifo-tie-breaks-priority-ties
  (let [spawned (atom [])
        slow    (fn [rid] (swap! spawned conj rid) (Thread/sleep 100))]
    (ex/configure! {:global-cap 1})
    (ex/submit! "first"  5)
    (Thread/sleep 5)                     ;; ensure distinct received-at
    (ex/submit! "second" 5)
    (ex/tick! slow) (Thread/sleep 30)
    (ex/tick! slow) (Thread/sleep 200)
    (ex/tick! slow) (Thread/sleep 200)
    (is (= ["first" "second"] @spawned))))

(deftest cap-limits-concurrent-futures
  (let [running (atom 0)
        max-r   (atom 0)
        slow    (fn [_] (swap! running inc)
                        (swap! max-r max @running)
                        (Thread/sleep 100)
                        (swap! running dec))]
    (ex/configure! {:global-cap 2})
    (dotimes [i 5] (ex/submit! (str "r" i) 0))
    ;; Drive several ticks while futures run.
    (dotimes [_ 10] (ex/tick! slow) (Thread/sleep 50))
    (is (<= @max-r 2)
        "should never exceed cap of 2 in-flight")))

(deftest snapshot-reports-counts
  (ex/configure! {:global-cap 1})
  (ex/submit! "r1" 0)
  (ex/submit! "r2" 0)
  (let [s (ex/snapshot)]
    (is (= 1 (:cap s)))
    (is (= 0 (:in-flight s)))
    (is (= 2 (:queued s)))))

(deftest tick-reaps-completed-futures
  (let [spawned (atom #{})]
    (ex/configure! {:global-cap 1})
    (ex/submit! "r1" 0)
    (ex/tick! (fn [rid] (swap! spawned conj rid)))
    (Thread/sleep 100)
    (ex/tick! (fn [_] nil))              ;; reap r1
    (is (= 0 (:in-flight (ex/snapshot))))))

(deftest tick-swallows-future-exceptions-and-frees-slot
  (let [thrown (atom 0)]
    (ex/configure! {:global-cap 1})
    (ex/submit! "boom" 0)
    (ex/tick! (fn [_] (swap! thrown inc) (throw (ex-info "boom" {}))))
    (Thread/sleep 100)
    (ex/tick! (fn [_] nil))
    (is (= 1 @thrown))
    (is (= 0 (:in-flight (ex/snapshot))))
    (is (= 0 (:queued    (ex/snapshot))))))
```

- [ ] **Step 2: Run to verify failure**

```bash
bb test test/nido/coordinator/executor_test.clj
```

Expected: namespace doesn't exist.

- [ ] **Step 3: Write the executor**

Create `src/nido/coordinator/executor.clj`:

```clojure
(ns nido.coordinator.executor
  "Slot-based scheduler with priority queue. Babashka-compatible:
   uses a clojure sorted-set keyed by [(- priority) received-at] for the
   wait queue (PriorityBlockingQueue isn't available in bb), and `future`
   per in-flight Run. All mutation goes through `locking` on `lock` so the
   daemon's reap+promote tick is atomic."
  (:require
   [nido.coordinator.clock :as clock]))

(defn- slot-cmp
  "Sort by descending priority, then by received-at ascending (FIFO ties)."
  [a b]
  (compare [(- (:priority a)) (:received-at a) (:run-id a)]
           [(- (:priority b)) (:received-at b) (:run-id b)]))

(defonce ^:private !state
  (atom {:queue     (sorted-set-by slot-cmp)
         :in-flight {}
         :cap       1}))

(defonce ^:private lock (Object.))

(defn configure!
  "Set the global concurrency cap. Pure config — does not start threads."
  [{:keys [global-cap]}]
  (swap! !state assoc :cap global-cap))

(defn clear!
  "Test-only: reset queue + in-flight. Does not cancel in-flight futures."
  []
  (swap! !state assoc :queue (sorted-set-by slot-cmp) :in-flight {}))

(defn submit!
  "Add a Run to the wait queue. run-id is opaque to the executor; priority
   is an int (higher pops first). Idempotent: re-submitting the same run-id
   is a no-op (prevents duplicate work if the daemon re-processes a queue
   file)."
  [run-id priority]
  (locking lock
    (swap! !state update :queue
           (fn [q]
             (if (or (some #(= run-id (:run-id %)) q)
                     (contains? (:in-flight @!state) run-id))
               q
               (conj q {:run-id      run-id
                        :priority    priority
                        :received-at (clock/now-iso)}))))))

(defn- reap-done [in-flight]
  (reduce-kv
    (fn [acc rid f]
      (if (future-done? f)
        (do (try @f (catch Throwable _ nil)) acc) ; swallow; logged elsewhere
        (assoc acc rid f)))
    {} in-flight))

(defn tick!
  "Called by the daemon every poll. Reaps finished futures, then promotes
   up to (cap - in-flight) queued Runs into new futures. on-spawn is
   `(fn [run-id])` — typically a wrapper around the legacy run-now! body."
  [on-spawn]
  (locking lock
    (swap! !state update :in-flight reap-done)
    (let [{:keys [queue in-flight cap]} @!state
          free  (max 0 (- cap (count in-flight)))
          picks (->> queue (take free) vec)]
      (when (seq picks)
        (let [new-q (reduce disj queue picks)
              new-f (into in-flight
                          (for [{:keys [run-id]} picks]
                            [run-id (future (try (on-spawn run-id)
                                                 (catch Throwable t t)))]))]
          (swap! !state assoc :queue new-q :in-flight new-f))))))

(defn snapshot
  "Read-only view for the TUI. No locking — reads a consistent atom value."
  []
  (let [{:keys [queue in-flight cap]} @!state]
    {:cap       cap
     :in-flight (count in-flight)
     :queued    (count queue)
     :queue     (mapv :run-id queue)}))
```

- [ ] **Step 4: Run tests, fix until green**

```bash
bb test test/nido/coordinator/executor_test.clj
```

Expected: 7 passes. If timing-sensitive tests flake, raise the sleeps (but keep them as small as the tests reliably pass at).

- [ ] **Step 5: Commit**

```bash
jj new && jj desc -m "feat(coordinator/executor): slot-based scheduler with priority queue

New nido.coordinator.executor namespace. Atom-backed wait queue ordered
by (priority desc, received-at asc, run-id), futures per in-flight Run,
single `locking` monitor so the daemon's reap+promote tick is atomic.
Babashka-compatible (no java.util.concurrent.PriorityBlockingQueue).
Not wired into the daemon yet — see next task."
```

---

## Task 5 — Wire the executor into the daemon loop

**Files:**
- Modify: `src/nido/coordinator/core.clj:98-200` (the daemon loop, `run-now!`, `process-envelope!`, `tick!`)
- Test: `test/nido/coordinator/core_test.clj` (find or create — integration smoke)

The daemon loop's last two steps today are:

```
process-envelope! ─→ run-now! (blocking)
```

After this task:

```
process-envelope! ─→ executor/submit!     (non-blocking)
                ⤷ at end of tick: executor/tick! reaps + promotes
                                  ⤷ future runs run-blocking! (was run-now!)
```

- [ ] **Step 1: Rename `run-now!` → `run-blocking!`** (no behavior change yet)

This is the function the executor's future will call. Renaming makes intent explicit and the diff against the executor wiring readable.

Edit `src/nido/coordinator/core.clj:98-143`:

```clojure
(defn- run-blocking!
  "Drive a single :queued Run to terminal/awaiting-review state.
   Blocking — called from inside an executor-spawned future."
  [run-id]
  …existing body unchanged…)
```

Also update its caller (line 188-ish) to use the new name. Don't change behavior.

- [ ] **Step 2: Verify nothing broke**

```bash
bb test
```

Expected: green (pure rename).

- [ ] **Step 3: Update `process-envelope!` to submit instead of run synchronously**

Edit `src/nido/coordinator/core.clj:156-...` (`process-envelope!`). Where it currently calls `(run-blocking! run-id)`, replace with:

```clojure
(executor/submit! (:id run) (or (:priority run) 0))
```

(Keep the existing pre-submit logic — Run creation, dry-run shortcut, etc.)

Add the require at the top of the ns:

```clojure
[nido.coordinator.executor :as executor]
```

- [ ] **Step 4: Add `executor/tick!` to the daemon loop**

Find the outer `tick!` (line 200-ish — the one called from the `while` loop). After it processes envelopes, call:

```clojure
(executor/tick! run-blocking!)
```

- [ ] **Step 5: Initialize the executor with the config'd cap on daemon start**

Find where the daemon reads its config (look for the `defaults` def — should be near top of core.clj). At the place where the daemon starts, add:

```clojure
(executor/configure! {:global-cap (:global-parallel-cap defaults)})
```

- [ ] **Step 6: Integration smoke test — envelope → terminal**

Add to `test/nido/coordinator/core_test.clj` (create if needed):

```clojure
(deftest envelope-drives-run-to-terminal-via-executor
  (with-tmp-nido
    (fn []
      ;; Stub agent/launch! so we don't actually spawn claude.
      (with-redefs [agent/launch!
                    (fn [_] {:exit-code 0 :claude-session-id "sess-x" :timed-out? false})
                    runs/spawn-session-for-run!
                    (fn [_] nil)]
        (let [trigger {:name :t :source {:type :test} :skill :noop :payload "x"}
              run    (runs/create-run! {:project :p :trigger trigger :payload {} :priority 0} {})]
          (executor/configure! {:global-cap 1})
          (executor/submit! (:id run) 0)
          (executor/tick! #'core/run-blocking!)
          (Thread/sleep 200)
          (executor/tick! #'core/run-blocking!) ;; reap
          (is (= :done (:state (runs/read-run (:id run))))))))))
```

- [ ] **Step 7: Run full suite**

```bash
bb test
```

Expected: green. Any test that depended on synchronous behavior (e.g., asserting state immediately after `process-envelope!`) needs a small `Thread/sleep` + extra `tick!` for the reap. Fix as needed.

- [ ] **Step 8: Manual smoke — fire the existing smoke trigger end-to-end**

```bash
cd /Users/yabmas/Code/nido
bb nido:coordinator:down  2>/dev/null
bb nido:coordinator:up
sleep 2
bb nido:trigger:fire :project brian smoke-notion :title "smoke-from-executor"
sleep 5
bb nido:runs:list | head -5
bb nido:coordinator:logs | tail -20
bb nido:coordinator:down
```

Expected: a new Run reaches a terminal state (`:done`, `:failed`, or `:awaiting-review`); coordinator logs show executor activity.

- [ ] **Step 9: Commit**

```bash
jj new && jj desc -m "feat(coordinator): replace synchronous run-now! with executor

Daemon's process-envelope! now submits to the executor instead of
blocking on run-now!. Executor reaps and promotes once per daemon
tick. Rename run-now! → run-blocking! (clearer intent: it's now the
body of an executor-spawned future). Behavior unchanged for the
smoke trigger (default priority 0, FIFO order, cap defaults to 1
until config is bumped)."
```

---

## Task 6 — Coordinator config `:executor` section

**Files:**
- Modify: `src/nido/coordinator/core.clj` (the `defaults` def)
- Modify: whichever ns reads `~/.nido/coordinator/config.edn` (find via grep `config.edn`)
- Test: `test/nido/coordinator/core_test.clj`

Spec calls for:

```clojure
{:global-parallel-cap 5
 :executor {:idle-poll-ms 250
            :shutdown-grace-ms 5000}
 …existing keys…}
```

`:global-parallel-cap` already exists as 2. We're not raising the live default in this plan (training-wheels stay at 2 until the triage triggers ship and you flip it). We *do* expose `:executor` so later plans don't need to backport schema.

- [ ] **Step 1: Failing test for defaults**

```clojure
(deftest defaults-include-executor-section
  (is (= 250  (-> core/defaults :executor :idle-poll-ms)))
  (is (= 5000 (-> core/defaults :executor :shutdown-grace-ms))))
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Add `:executor` defaults**

Edit `src/nido/coordinator/core.clj`, find the `defaults` def, add:

```clojure
:executor {:idle-poll-ms       250
           :shutdown-grace-ms  5000}
```

- [ ] **Step 4: Plumb `:idle-poll-ms` and `:shutdown-grace-ms` through**

For now, these are values the daemon reads but doesn't change behavior on (cap-aware spawn is already in Task 4-5). Leave a TODO comment? **No** — TODOs are plan-failures per writing-plans. Instead: actually use them.

- `idle-poll-ms`: the daemon already has a `poll-ms` for envelope-drain cadence. `idle-poll-ms` is the smaller cadence the daemon uses *between drains* when the executor signaled "nothing's happening, slot's free, but queue is empty." For Plan A scope, just store the value — no behavior change yet. Plan D will use it.

Wait — that's a TODO-in-disguise. Cleaner: omit `:idle-poll-ms` from this plan. Add it in the plan that actually uses it.

Revised: defaults add only `:shutdown-grace-ms`. `:idle-poll-ms` is part of a later plan that introduces the busy/idle distinction.

- [ ] **Step 4 (revised): Add only `:shutdown-grace-ms`**

```clojure
:executor {:shutdown-grace-ms 5000}
```

Update the failing test accordingly (remove the `:idle-poll-ms` assertion).

- [ ] **Step 5: Use `:shutdown-grace-ms` in the daemon stop path**

Find the daemon stop code (the `down` handler in `tasks/nido_coordinator.clj` or `nido.coordinator.core/stop!`). Today it sends SIGTERM and waits a hardcoded interval before SIGKILL. Replace with:

```clojure
(let [grace-ms (-> defaults :executor :shutdown-grace-ms)]
  (Thread/sleep grace-ms))
```

Surface a single integration test:

```clojure
(deftest stop-uses-configured-grace
  (with-redefs [core/defaults (assoc-in core/defaults [:executor :shutdown-grace-ms] 1)]
    ;; assert that stop! returns quickly when grace is 1ms
    (let [t0 (System/currentTimeMillis)]
      (core/stop!)
      (is (< (- (System/currentTimeMillis) t0) 500)
          "stop! honors :shutdown-grace-ms"))))
```

- [ ] **Step 6: Run tests, full suite**

```bash
bb test
```

- [ ] **Step 7: Commit**

```bash
jj new && jj desc -m "feat(coordinator): expose :executor config section

defaults gain {:executor {:shutdown-grace-ms 5000}}. Daemon stop path
honors the grace value (was hardcoded). :idle-poll-ms and per-trigger
caps are deliberately omitted — they'll land alongside the plans that
actually use them, instead of as dead config keys."
```

---

## Task 7 — TUI header: in-flight / cap · queued

**Files:**
- Modify: `src/nido/coordinator/runs_view.clj:100-108` (the `read-coordinator-status` function — extend it to include the executor snapshot)
- Modify: `src/nido/tui.clj:126-136` (`run-rows` — prepend a header row)
- Test: `test/nido/tui_test.clj` if it exists; otherwise unit-test `runs_view.clj`'s new shape

This is the single observability change in Plan A — operators need to see whether the executor is doing anything. The triage-specific keystrokes (`t`, `g`) and per-trigger breakdown live in Plan D.

- [ ] **Step 1: Failing test**

In `test/nido/coordinator/runs_view_test.clj` (create if needed):

```clojure
(deftest read-coordinator-status-includes-executor-snapshot
  (with-redefs [executor/snapshot (constantly {:cap 5 :in-flight 2 :queued 3 :queue ["a" "b" "c"]})]
    (let [s (runs-view/read-coordinator-status)]
      (is (= 5 (-> s :executor :cap)))
      (is (= 2 (-> s :executor :in-flight)))
      (is (= 3 (-> s :executor :queued))))))
```

- [ ] **Step 2: Run, verify fail.**

- [ ] **Step 3: Extend `read-coordinator-status`**

Edit `src/nido/coordinator/runs_view.clj:100-108` to merge executor snapshot:

```clojure
(defn read-coordinator-status
  "Read coordinator heartbeat + alerts + executor snapshot."
  []
  (-> {:status …existing…
       :slots-in-use …existing…
       :heartbeat-at …existing…
       :reachable? …existing…
       :alerts …existing…}
      (assoc :executor (executor/snapshot))))
```

Add `[nido.coordinator.executor :as executor]` to the requires.

- [ ] **Step 4: Header row in TUI**

Edit `src/nido/tui.clj:126` (`run-rows`). Prepend a single-line header:

```clojure
(defn run-rows []
  (let [{:keys [executor]} (runs-view/read-coordinator-status)
        header (format "in-flight: %d/%d · queued: %d"
                       (:in-flight executor) (:cap executor) (:queued executor))
        groups …existing grouping logic…]
    (into [header] groups)))
```

(Adapt to whatever shape `run-rows` returns today — string vector, or some richer record. The existing code uses a vector-of-strings idiom; match it.)

- [ ] **Step 5: Manual smoke**

```bash
bb nido:coordinator:up
bb nido:tui &
# press `r` for the runs surface — header should read "in-flight: 0/2 · queued: 0"
bb nido:trigger:fire :project brian smoke-notion :title "smoke-tui"
# header briefly shows in-flight: 1/2 · queued: 0
```

- [ ] **Step 6: Run full suite**

- [ ] **Step 7: Commit**

```bash
jj new && jj desc -m "feat(tui): runs surface header shows executor in-flight / cap / queued

Three-number header so the operator can see the executor doing work.
read-coordinator-status grows an :executor snapshot from
executor/snapshot. Triage-specific surfaces (per-trigger breakdown,
keystrokes t/g) ship with the triage plan."
```

---

## Task 8 — Final verification

- [ ] **Step 1: Full test suite, no skips**

```bash
cd /Users/yabmas/Code/nido && bb test
```

Expected: all green.

- [ ] **Step 2: End-to-end smoke against the existing smoke trigger**

```bash
bb nido:coordinator:down 2>/dev/null
bb nido:coordinator:up
sleep 2
bb nido:trigger:fire :project brian smoke-notion :title "executor-e2e"
sleep 10
bb nido:runs:list | head
bb nido:coordinator:logs | tail -30
```

Expected: a new Run in a terminal state; coordinator log mentions executor activity; TUI header has shown non-zero values.

- [ ] **Step 3: Parallelism sanity-check**

Temporarily bump `:global-parallel-cap` to 3 in `~/.nido/coordinator/config.edn`, restart the daemon, fire three smoke envelopes back-to-back:

```bash
bb nido:coordinator:down && bb nido:coordinator:up
sleep 2
for i in 1 2 3; do bb nido:trigger:fire :project brian smoke-notion :title "parallel-$i"; done
sleep 2
bb nido:runs:list | head        # expect three :running simultaneously
```

Restore `:global-parallel-cap` to its previous value when done.

- [ ] **Step 4: Clean up the daemon**

```bash
bb nido:coordinator:down
```

- [ ] **Step 5: Final commit if anything moved in verification**

If you tweaked anything during smoke (timing, log format, etc.), commit it as a small fix-up:

```bash
jj new && jj desc -m "chore(coordinator/executor): post-smoke adjustments"
```

Otherwise: nothing to commit. Plan A is done.

---

## Self-review — spec coverage check

| Spec requirement | Task |
|---|---|
| `nido.coordinator.executor` namespace, slot-based scheduler | Task 4 |
| Priority queue with FIFO ties | Task 4 (tests) |
| `submit!`, `free-slots` / `snapshot`, no per-trigger caps | Task 4 |
| Envelope `:priority` (int, default 0) and `:received-at` | Task 1 |
| Trigger schema `:priority` (constant) | Task 2 |
| Routing propagates trigger `:priority` onto envelope/Run | Task 3 |
| Daemon's `run-now!` replaced by executor submission | Task 5 |
| `:global-parallel-cap` now actually enforced | Task 5 + Task 6 |
| Coordinator config gains `:executor` section | Task 6 |
| TUI in-flight/cap/queued header | Task 7 |
| `:priority-from` source-resolved override | **Plan C** (Notion source upgrade) |
| Lite session profile, worktree symlink | **Plan B** |
| Triage skill, triggers, triage-specific TUI keystrokes | **Plan D** |

Deferred-but-named: `:idle-poll-ms` config key. Omitted from Plan A because it has no behavior to attach to yet; lands with the plan that uses it.

No placeholders. No TBDs. All types referenced in later tasks (the `:priority` field on `Trigger`, on the envelope, on the Run record, on the executor's slot-item) are defined in earlier tasks.
