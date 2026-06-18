# Live-path cutover to workstreams/sessions — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.
>
> **VCS:** jj (Jujutsu) repo. Activate the `jujutsu` skill. Per task: `jj new` FIRST, then `jj desc -m "…"`, THEN edit (this avoids editing the previous commit). Verify with `jj log` + `jj diff -r @ --summary`. Never commit `docs/superpowers/**`.
>
> **Test command:** `bb nido:test :only <ns-prefix>` (focused) or `bb nido:test` (full). Namespace-load check: `bb -e "(require 'nido.coordinator.<ns>)"`.

**Goal:** Make the live coordinator path create and drive workstreams + sessions so the running queue lights up the new Workstreams TUI — via the strangler approach: the session (with its `:autonomy` facet) is the authoritative model/surface record; the legacy `run` survives as the internal execution key and carries a pointer to its session. Triage dual-writes (legacy ticket + workstream ledger) during the transition.

**Architecture:** At spawn, find-or-create a workstream (dedup on the event's Notion ref) and create the autonomous session; `create-run!` carries a `:workstream-id` pointer. A single shared run-state→session-phase map (extracted from `migrate.clj`) is applied by a best-effort `mirror-run-phase!` helper called from both `runs/transition!` and `reconcile-one!`. Per-trigger gating swaps to a session-based count that preserves today's `:awaiting-review`/`:parked` backpressure. The proven execution machinery (executor, budget watchdog, agent.log, symlinks, artifacts, `runs-clean`) is untouched.

**Tech Stack:** Babashka, Clojure, Malli. Existing namespaces: `nido.coordinator.{runs,session,workstream,migrate,reconcile,core,executor,state,clock}`.

**Design defaults baked in (override in review if wrong):**
- Initial workstream stage = per-trigger `:workstream-stage`, default `:triaging`. (Note: `migrate.clj` uses `:investigating` as its fallback; the two vocabularies coexist until unified — out of scope here.)
- Dry-run: **no** workstream/session created (the dry-run arm stays run-only; `mirror-run-phase!` no-ops without a `:workstream-id`).
- Gating: **preserve** old backpressure — the session gating count includes `:parked` (= old `:awaiting-review`). One-line flip if the looser session-model semantics are preferred.

---

## File Structure

- **Create:** `src/nido/coordinator/spawn.clj` — pure ref derivation + workstream find-or-create + session creation + spawn orchestration. Test: `test/nido/coordinator/spawn_test.clj`.
- **Create:** `src/tasks/nido_workstream.clj` — bb-task entry points for the workstream ledger commands. Test: `test/tasks/nido_workstream_test.clj`.
- **Modify:** `src/nido/coordinator/runs.clj` — Run schema `:workstream-id`; `create-run!` threads it; shared `state->phase`; `mirror-run-phase!`; mirror in `transition!`.
- **Modify:** `src/nido/coordinator/migrate.clj` — use `runs/state->phase` instead of its private copy.
- **Modify:** `src/nido/coordinator/session.clj` — gating phase set + count fn (or parameterize `in-flight-by-trigger`).
- **Modify:** `src/nido/coordinator/reconcile.clj` — call `mirror-run-phase!` after writing the reconciled run.
- **Modify:** `src/nido/coordinator/core.clj` — `:else` spawn arm calls `spawn/spawn-records!`; gating source swap in `tick!`.
- **Modify:** `bb.edn` — register the `nido:workstream:*` tasks.
- **Modify (light, Task 9):** the session-home launcher (briefing carries `workstream-id`) and the brian `triage-bug` skill (dual-write the workstream ledger). These require reading those files first; specified at acceptance-criteria grain.

---

## Task 1: Shared run-state → session-phase map

**Files:**
- Modify: `src/nido/coordinator/runs.clj`
- Modify: `src/nido/coordinator/migrate.clj`
- Test: `test/nido/coordinator/runs_test.clj` (create if absent)

- [ ] **Step 1: Write the failing test**

Add to `test/nido/coordinator/runs_test.clj`:

```clojure
(ns nido.coordinator.runs-test
  (:require [clojure.test :refer [deftest is]]
            [nido.coordinator.runs :as runs]))

(deftest state->phase-maps-every-run-state
  ;; Every run state has a session-phase image; awaiting-review parks.
  (is (= :running (runs/state->phase :running)))
  (is (= :parked  (runs/state->phase :awaiting-review)))
  (is (= :done    (runs/state->phase :done)))
  (is (= :failed  (runs/state->phase :failed)))
  (is (= :halted  (runs/state->phase :halted)))
  (is (= :queued  (runs/state->phase :queued)))
  (is (= :preprocessing (runs/state->phase :preprocessing)))
  (is (= :failed  (runs/state->phase :dry-run-would-fire)))
  ;; total over the declared run states
  (is (every? runs/state->phase runs/states)))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.runs-test`
Expected: FAIL — `Unable to resolve symbol: runs/state->phase`.

- [ ] **Step 3: Add the map to `runs.clj`**

After the `states` def (line ~18), add:

```clojure
(def state->phase
  "Run state → session autonomy phase. The single source of truth for mirroring
   a run onto its authoritative session (used by transition!/reconcile and by
   the migrate one-shot). :awaiting-review parks (human gate)."
  {:queued             :queued
   :preprocessing      :preprocessing
   :running            :running
   :awaiting-review    :parked
   :done               :done
   :failed             :failed
   :halted             :halted
   :dry-run-would-fire :failed})
```

In `migrate.clj`, delete the private `run-state->phase` def (lines ~22-30) and replace its two uses (`run-state->substrate` is separate — keep it; the uses are in `run->session` at the `phases` mapv and `:phase`) with `runs/state->phase`:

```clojure
        phases    (mapv (fn [{:keys [at state]}]
                          {:at at :phase (runs/state->phase state)})
                        state-history)]
    ...
                          :phase             (runs/state->phase state)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.coordinator.runs-test` → PASS.
Run: `bb nido:test :only nido.coordinator.migrate` → PASS (migrate still green with the shared map).

- [ ] **Step 5: Commit**

```bash
jj new && jj desc -m "refactor(coordinator): share run-state→phase map between runs and migrate"
```

---

## Task 2: `:workstream-id` on the Run record

**Files:**
- Modify: `src/nido/coordinator/runs.clj`
- Test: `test/nido/coordinator/runs_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `runs_test.clj`:

```clojure
(deftest run-schema-accepts-optional-workstream-id
  (let [base {:id "r1" :project :p :trigger :t :source {:type :test}
              :event-payload {} :skill :noop :first-message "x" :agent :claude
              :session-name "s1" :claude-session-id nil :limits {} :priority 0
              :session-profile :full :uncapped? false :state :queued
              :state-history [{:at "2026-06-08T00:00:00Z" :state :queued}]
              :artifacts [] :error nil}]
    ;; valid without the key (legacy runs)
    (is (= base (runs/validate base)))
    ;; valid with it
    (is (= "ws-1" (:workstream-id (runs/validate (assoc base :workstream-id "ws-1")))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.coordinator.runs-test`
Expected: FAIL — closed schema rejects `:workstream-id`.

- [ ] **Step 3: Implement**

In the `Run` schema, add (after `:session-name`):

```clojure
   [:workstream-id     {:optional true} [:maybe string?]]
```

In `create-run!`, accept and persist it. Change the arg destructure to include `:workstream-id`:

```clojure
  [{:keys [project trigger payload priority session-profile uncapped? workstream-id]} meta]
```

and add to the run map (after `:session-name session-name`):

```clojure
                 :workstream-id   workstream-id
```

(When absent it's `nil`; the optional+maybe schema accepts that.)

- [ ] **Step 4: Run to verify pass**

Run: `bb nido:test :only nido.coordinator.runs-test` → PASS.
Run: `bb nido:test :only nido.coordinator.runs-clean` → PASS (existing run fixtures still valid).

- [ ] **Step 5: Commit**

```bash
jj new && jj desc -m "feat(coordinator/runs): optional :workstream-id linkage field"
```

---

## Task 3: `spawn` namespace — workstream find-or-create + session create

**Files:**
- Create: `src/nido/coordinator/spawn.clj`
- Test: `test/nido/coordinator/spawn_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/nido/coordinator/spawn_test.clj`:

```clojure
(ns nido.coordinator.spawn-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.session :as session]
   [nido.coordinator.spawn :as spawn]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest external-ref-from-notion-payload
  (is (= {:adapter :notion :id "BR-1" :title "T" :url "u" :page-id "pg"}
         (spawn/external-ref {:id "BR-1" :title "T" :url "u" :page-id "pg"})))
  (is (nil? (spawn/external-ref {})))
  (is (nil? (spawn/external-ref {:id "  "}))))

(deftest ensure-workstream-dedups-on-ref
  (with-tmp
    (fn [_]
      (let [a (spawn/ensure-workstream! :brian {:id "BR-9" :title "Nine"} :triaging)
            b (spawn/ensure-workstream! :brian {:id "BR-9" :title "Nine"} :triaging)]
        (is (= (:id a) (:id b)))                       ; same ref → same workstream
        (is (= 1 (count (ws/list-ids :brian))))
        (is (= :triaging (:stage a))))
      (let [c (spawn/ensure-workstream! :brian {} :intake)]  ; no ref → fresh
        (is (= 2 (count (ws/list-ids :brian))))
        (is (empty? (:external-refs c)))))))

(deftest spawn-records-creates-workstream-session-and-linked-run
  (with-tmp
    (fn [_]
      (let [routed {:project :brian
                    :trigger {:name :triage-bug :skill :triage-bug :agent :claude
                              :limits {:budget "30m"} :source {:type :notion-view}}
                    :payload {:id "BR-7" :title "Seven"}
                    :priority 0 :session-profile :lite :uncapped? false}
            run    (spawn/spawn-records! routed {:fired-at "2026-06-08T00:00:00Z" :fired-by "t"})
            ws-id  (:workstream-id run)
            sess   (session/read-session :brian ws-id (:session-name run))]
        (is (some? ws-id))
        (is (= "BR-7" (:id (ws/find-by-ref :brian :notion "BR-7"))) "ref points at the ws BR")
        (is (= :light (:weight sess)))                 ; :lite profile → :light weight
        (is (= :queued (get-in sess [:autonomy :phase])))
        (is (= :triage-bug (get-in sess [:autonomy :trigger])))
        (is (= :live (:substrate sess)))))))
```

Note: `(ws/find-by-ref :brian :notion "BR-7")` returns the workstream; its `:id` is the minted `ws-…`, not "BR-7" — fix that assertion to check the ref id instead:

```clojure
        (is (= "BR-7" (-> (ws/find-by-ref :brian :notion "BR-7") :external-refs first :id)))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.coordinator.spawn`
Expected: FAIL — `No namespace: nido.coordinator.spawn`.

- [ ] **Step 3: Implement `src/nido/coordinator/spawn.clj`**

```clojure
(ns nido.coordinator.spawn
  "Live-path spawn orchestration: turn a routed fire request into a workstream
   (found-or-created, deduped on external ref) + an authoritative autonomous
   session, then the legacy run that the execution machinery drives. The run
   carries :workstream-id back to its session (strangler boundary — see
   docs/superpowers/specs/2026-06-08-live-path-cutover-design.md)."
  (:require
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.workstream :as ws]))

(defn external-ref
  "Notion external ref derived from an event payload, or nil when the payload
   carries no usable id. Optional fields (title/url/page-id) included when present."
  [payload]
  (let [id (:id payload)]
    (when (and id (not (str/blank? id)))
      (cond-> {:adapter :notion :id id}
        (:title payload)   (assoc :title (:title payload))
        (:url payload)     (assoc :url (:url payload))
        (:page-id payload) (assoc :page-id (:page-id payload))))))

(defn ensure-workstream!
  "Find-or-create the workstream for this fire. With a derivable Notion ref,
   dedups via find-by-ref; otherwise mints a fresh ref-less workstream. Returns
   the workstream record."
  [project payload stage]
  (if-let [ref (external-ref payload)]
    (or (ws/find-by-ref project :adapter (:id ref))  ; placeholder — see note
        (ws/create! project {:stage stage :external-refs [ref]}))
    (ws/create! project {:stage stage :external-refs []})))
```

CORRECTION — `find-by-ref` takes `[project adapter external-id]` (see `workstream.clj:132`). Use:

```clojure
    (or (ws/find-by-ref project :notion (:id ref))
        (ws/create! project {:stage stage :external-refs [ref]}))
```

Continue:

```clojure
(defn- weight-of [session-profile]
  (if (= :lite session-profile) :light :heavy))

(defn autonomy-from
  "Build a session :autonomy map from a freshly-created run, seeded at :queued."
  [run]
  (let [now (clock/now-iso)]
    {:skill             (:skill run)
     :first-message     (:first-message run)
     :agent             (or (:agent run) :claude)
     :claude-session-id (:claude-session-id run)
     :trigger           (:trigger run)
     :limits            (or (:limits run) {})
     :priority          (or (:priority run) 0)
     :uncapped?         (boolean (:uncapped? run))
     :on-promote        (:on-promote run)
     :phase             :queued
     :phase-history     [{:at now :phase :queued}]
     :error             nil}))

(defn create-session-for-run!
  "Persist the authoritative autonomous session for a run under `ws-id`."
  [run ws-id]
  (session/create! (:project run) ws-id
                   {:name     (:session-name run)
                    :weight   (weight-of (:session-profile run))
                    :autonomy (autonomy-from run)}))

(defn- initial-stage
  "Per-trigger :workstream-stage, defaulting to :triaging."
  [routed]
  (or (-> routed :trigger :workstream-stage) :triaging))

(defn spawn-records!
  "Orchestrate the live spawn: ensure workstream → create run (linked) → create
   session. Returns the run (carrying :workstream-id) for the executor to submit."
  [routed meta]
  (let [w   (ensure-workstream! (:project routed) (:payload routed) (initial-stage routed))
        run (runs/create-run! (assoc routed :workstream-id (:id w)) meta)]
    (create-session-for-run! run (:id w))
    run))
```

- [ ] **Step 4: Run to verify pass**

Run: `bb nido:test :only nido.coordinator.spawn` → PASS.

- [ ] **Step 5: Commit**

```bash
jj new && jj desc -m "feat(coordinator/spawn): workstream find-or-create + session per fire"
```

---

## Task 4: `mirror-run-phase!` — sync run state onto its session

**Files:**
- Modify: `src/nido/coordinator/runs.clj`
- Modify: `src/nido/coordinator/reconcile.clj`
- Test: `test/nido/coordinator/runs_test.clj`

- [ ] **Step 1: Write the failing test**

Append to `runs_test.clj` (add requires for `babashka.fs`, `nido.coordinator.state`, `nido.coordinator.session`, `nido.coordinator.workstream` at the top):

```clojure
(deftest mirror-run-phase-syncs-session-and-noops-without-ws
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        ;; no :workstream-id → no-op, no throw
        (is (nil? (runs/mirror-run-phase! {:project :p :session-name "s" :state :running})))
        ;; with a real session, mirrors state→phase
        (let [w (workstream/create! :p {:stage :triaging})]
          (session/create! :p (:id w) {:name "s1" :weight :light
                                       :autonomy {:skill :k :first-message "m" :agent :claude
                                                  :claude-session-id nil :trigger :t :limits {}
                                                  :priority 0 :uncapped? false :on-promote nil
                                                  :phase :queued :phase-history [] :error nil}})
          (runs/mirror-run-phase! {:project :p :workstream-id (:id w)
                                   :session-name "s1" :state :awaiting-review})
          (is (= :parked (get-in (session/read-session :p (:id w) "s1") [:autonomy :phase])))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.coordinator.runs-test`
Expected: FAIL — `Unable to resolve symbol: runs/mirror-run-phase!`.

- [ ] **Step 3: Implement**

In `runs.clj` add the `session` require (`[nido.coordinator.session :as session]` — no cycle: session requires only clock/state/io). Add after `transition!`:

```clojure
(defn mirror-run-phase!
  "Best-effort mirror of a run's state onto its authoritative session's autonomy
   phase. No-op when the run has no :workstream-id (legacy / dry-run / test runs).
   Never throws — a missing or human session is logged to stderr and swallowed so
   it can't re-fail a transition or reconcile pass."
  [run]
  (when-let [ws-id (:workstream-id run)]
    (when-let [phase (state->phase (:state run))]
      (try
        (session/set-phase! (:project run) ws-id (:session-name run) phase)
        (catch Exception e
          (binding [*err* *err*]
            (.println ^java.io.PrintWriter *err*
                      (str "nido coordinator: phase mirror failed for "
                           (:session-name run) " → " phase " — " (ex-message e)))))))))
```

Wire it into `transition!`: after `(write-run! updated)` and before `updated` is returned, call `(mirror-run-phase! updated)`:

```clojure
      (write-run! updated)
      (mirror-run-phase! updated)
      updated)))
```

In `reconcile.clj`, in `reconcile-one!`, after `(runs/write-run! updated)` (and alongside `tickets/on-run-terminal!`), add `(runs/mirror-run-phase! updated)`:

```clojure
            (runs/write-run! updated)
            (runs/mirror-run-phase! updated)
            (tickets/on-run-terminal! updated state)))))))
```

- [ ] **Step 4: Run to verify pass**

Run: `bb nido:test :only nido.coordinator.runs-test` → PASS.
Run: `bb nido:test` → full suite green (transition!/reconcile callers unaffected since legacy runs have no `:workstream-id`).

- [ ] **Step 5: Commit**

```bash
jj new && jj desc -m "feat(coordinator/runs): mirror run state onto session phase (transition + reconcile)"
```

---

## Task 5: Wire `spawn-records!` into the live spawn arm

**Files:**
- Modify: `src/nido/coordinator/core.clj`
- Test: `test/nido/coordinator/core_test.clj` (extend if present; else add a focused test)

- [ ] **Step 1: Write the failing test**

Add a test that exercises `process-envelope!`'s live arm and asserts a workstream + session were created and the run is linked. (Find the existing `core_test.clj` pattern for building envelopes/triggers and a tmp nido-root; mirror it. If none exists, add `test/nido/coordinator/core_test.clj` with a `with-tmp` like the other coordinator tests and a minimal envelope routed to a non-dry-run, non-gated trigger.) Assertion core:

```clojure
;; after one process-envelope! tick on a live triage envelope for BR-5:
(let [run-id (first (runs/list-run-ids))
      run    (runs/read-run run-id)]
  (is (some? (:workstream-id run)))
  (is (some? (session/read-session (:project run) (:workstream-id run) (:session-name run))))
  (is (= "BR-5" (-> (ws/find-by-ref (:project run) :notion "BR-5") :external-refs first :id))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.coordinator.core`
Expected: FAIL — no workstream/session created (live arm still calls bare `create-run!`).

- [ ] **Step 3: Implement**

In `core.clj`, add the require `[nido.coordinator.spawn :as spawn]`. Replace the `:else` arm body (lines ~331-337):

```clojure
      :else
      (let [run (spawn/spawn-records! routed
                                      {:fired-at (clock/now-iso)
                                       :fired-by (System/getenv "USER")})]
        (swap! !detector anomaly/record-spawn (clock/now-iso))
        (executor/submit! (:id run) (:priority run) (:uncapped? run)
                          (:trigger run) (-> routed :trigger :max-in-flight))))))
```

Leave the **dry-run arm unchanged** (it keeps calling bare `runs/create-run!` with no `:workstream-id`, so no workstream/session is created and `mirror-run-phase!` no-ops on its `:dry-run-would-fire` transition — exactly the chosen default).

- [ ] **Step 4: Run to verify pass**

Run: `bb nido:test :only nido.coordinator.core` → PASS.
Run: `bb nido:test` → full suite green.

- [ ] **Step 5: Commit**

```bash
jj new && jj desc -m "feat(coordinator/core): live spawn creates workstream+session via spawn-records!"
```

---

## Task 6: Per-trigger gating on session phases (backpressure-preserving)

> **DECISION FLAGGED:** This counts `:parked` toward in-flight to preserve today's `:awaiting-review` backpressure. To adopt the looser session-model semantics (parked not in-flight), set `gating-phases` to `in-progress-phases` instead — a one-line change here.

**Files:**
- Modify: `src/nido/coordinator/session.clj`
- Modify: `src/nido/coordinator/core.clj`
- Test: `test/nido/coordinator/session_test.clj` (extend if present)

- [ ] **Step 1: Write the failing test**

Add (using the existing session-test fixture pattern, or a `with-tmp` redefining `cstate/nido-root`):

```clojure
(deftest gating-count-includes-parked
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging})
            mk (fn [nm phase]
                 (session/create! :brian (:id w)
                                  {:name nm :weight :light
                                   :autonomy {:skill :triage-bug :first-message "m" :agent :claude
                                              :claude-session-id nil :trigger :triage-bug :limits {}
                                              :priority 0 :uncapped? false :on-promote nil
                                              :phase phase :phase-history [] :error nil}}))]
        (mk "r-run" :running)
        (mk "r-park" :parked)
        (mk "r-queued" :queued)
        ;; gating counts running + parked (backpressure), NOT queued
        (is (= {:triage-bug 2} (session/gating-count-by-trigger :brian)))
        ;; in-flight-by-trigger (active work) still excludes parked
        (is (= {:triage-bug 1} (session/in-flight-by-trigger :brian)))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.coordinator.session`
Expected: FAIL — `Unable to resolve symbol: session/gating-count-by-trigger`.

- [ ] **Step 3: Implement**

In `session.clj`, refactor `in-flight-by-trigger` to take an optional phase set, and add the gating set + fn. Replace the existing `in-flight-by-trigger` with:

```clojure
(def gating-phases
  "Autonomy phases that occupy a trigger's in-flight budget FOR SCHEDULING.
   Includes :parked to preserve the legacy run-based backpressure (a session
   awaiting human review still holds the trigger's slot). Distinct from
   in-progress-phases, which is 'actively executing' work."
  #{:preprocessing :running :parked})

(defn count-by-trigger
  "Map {trigger-kw → count} of LIVE autonomous sessions whose phase is in
   `phase-set`, grouped by autonomy :trigger, across all of `project`'s
   workstreams. Scans disk; restart-safe."
  [project phase-set]
  (->> (list-ws-ids project)
       (mapcat #(list-sessions project %))
       (filter live?)
       (filter autonomous?)
       (filter #(contains? phase-set (get-in % [:autonomy :phase])))
       (reduce (fn [m s] (update m (get-in s [:autonomy :trigger]) (fnil inc 0))) {})))

(defn in-flight-by-trigger
  "Active-work count per trigger (preprocessing+running). Kept for callers that
   want 'executing now', distinct from scheduling backpressure."
  [project]
  (count-by-trigger project in-progress-phases))

(defn gating-count-by-trigger
  "Scheduling backpressure count per trigger (preprocessing+running+parked).
   The scheduler reads this to enforce per-trigger :max-in-flight — the session
   analogue of runs/in-progress-count-by-trigger."
  [project]
  (count-by-trigger project gating-phases))
```

In `core.clj`'s `tick!` (below line 345 — find where `runs/in-progress-count-by-trigger` is passed to `executor/tick!`), swap the gating source. The executor gates per project's triggers; if `executor/tick!` currently receives a single `{trigger → n}` map from `runs/in-progress-count-by-trigger`, replace it with the union across registered projects of `session/gating-count-by-trigger`. Concretely, where the code computes the in-flight map, replace:

```clojure
(runs/in-progress-count-by-trigger)
```

with a merge over registered projects:

```clojure
(reduce (fn [m p] (merge-with + m (session/gating-count-by-trigger p)))
        {} (registered-projects))
```

(Use the existing `registered-projects` helper in `core.clj` — confirm its name/arity while implementing. If `executor/tick!` is called per-project rather than globally, pass the single-project `gating-count-by-trigger` instead.)

- [ ] **Step 4: Run to verify pass**

Run: `bb nido:test :only nido.coordinator.session` → PASS.
Run: `bb nido:test` → full suite green.

- [ ] **Step 5: Commit**

```bash
jj new && jj desc -m "feat(coordinator): per-trigger gating on session phases (parked-inclusive backpressure)"
```

---

## Task 7: `nido:workstream:*` ledger commands

**Files:**
- Create: `src/tasks/nido_workstream.clj`
- Modify: `bb.edn`
- Test: `test/tasks/nido_workstream_test.clj`

- [ ] **Step 1: Write the failing test**

Create `test/tasks/nido_workstream_test.clj`:

```clojure
(ns tasks.nido-workstream-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [tasks.nido-workstream :as task]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest entry-add-stage-advance-close
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-3"}]})]
        ;; resolve by BR-#### ref, append an entry
        (task/entry-add* {:project "brian" :ref "BR-3" :kind "triage" :content "found a bug"})
        (let [w2 (ws/read-ws :brian (:id w))]
          (is (= 1 (count (:entries w2))))
          (is (= :triage (-> w2 :entries first :kind))))
        (task/stage-advance* {:project "brian" :ref "BR-3" :stage "investigating"})
        (is (= :investigating (:stage (ws/read-ws :brian (:id w)))))
        (task/close* {:project "brian" :ref "BR-3" :outcome "done"})
        (is (= :done (-> (ws/read-ws :brian (:id w)) :closed :outcome)))))))
```

(Note: `entry-add*`/`stage-advance*`/`close*` are the pure-ish testable inner fns; the bb task wrappers `entry-add`/… parse `*command-line-args*` and delegate. This mirrors `tasks.nido-test`'s split.)

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only tasks.nido-workstream`
Expected: FAIL — `No namespace: tasks.nido-workstream`.

- [ ] **Step 3: Implement `src/tasks/nido_workstream.clj`**

```clojure
(ns tasks.nido-workstream
  "bb-task entry points for the workstream ledger: append an entry, advance the
   stage, close a workstream. Resolves the target workstream by id or by Notion
   external ref (BR-####). Used by the triage skill's dual-write and by humans."
  (:require
   [nido.coordinator.workstream :as ws]
   [nido.task-args :as task-args]))

(defn- resolve-ws-id
  "Workstream id from opts: explicit :ws-id, or :ref resolved via find-by-ref
   (:notion adapter). Throws when neither resolves."
  [{:keys [project ws-id ref]}]
  (let [p (keyword project)]
    (or ws-id
        (some-> (and ref (ws/find-by-ref p :notion ref)) :id)
        (throw (ex-info "Cannot resolve workstream — pass :ws-id or a known :ref"
                        {:project project :ref ref})))))

(defn entry-add* [{:keys [project kind content] :as opts}]
  (ws/append-entry! (keyword project) (resolve-ws-id opts)
                    {:kind (keyword (or kind "note"))} (or content "")))

(defn stage-advance* [{:keys [project stage] :as opts}]
  (ws/advance-stage! (keyword project) (resolve-ws-id opts) (keyword stage)))

(defn close* [{:keys [project outcome] :as opts}]
  (ws/close! (keyword project) (resolve-ws-id opts) (keyword (or outcome "done"))))

(defn- run* [f args]
  (let [[_ opts] (task-args/split-args args)]
    (f opts)
    (println "ok")))

(defn entry-add     [& args] (run* entry-add* args))
(defn stage-advance [& args] (run* stage-advance* args))
(defn close-cmd     [& args] (run* close* args))
```

(Confirm `nido.task-args/split-args` shape against `tasks.nido-test` while implementing — it returns `[positional opts]`.)

In `bb.edn`, register the tasks (mirror the existing `nido:trigger:*` / `nido:runs:*` task entries):

```clojure
  nido:workstream:entry:add
  {:doc "Append an entry to a workstream ledger. :project <p> (:ws-id <id> | :ref <BR-####>) :kind <kw> :content <str>"
   :requires ([tasks.nido-workstream :as w])
   :task (apply w/entry-add *command-line-args*)}
  nido:workstream:stage:advance
  {:doc "Advance a workstream's stage. :project <p> (:ws-id|:ref) :stage <kw>"
   :requires ([tasks.nido-workstream :as w])
   :task (apply w/stage-advance *command-line-args*)}
  nido:workstream:close
  {:doc "Close a workstream. :project <p> (:ws-id|:ref) :outcome <done|dropped>"
   :requires ([tasks.nido-workstream :as w])
   :task (apply w/close-cmd *command-line-args*)}
```

(Match `bb.edn`'s existing task-entry style — `:requires`/`:task` form — exactly; copy from a neighboring `nido:*` task.)

- [ ] **Step 4: Run to verify pass**

Run: `bb nido:test :only tasks.nido-workstream` → PASS.
Run: `bb nido:workstream:entry:add` (no args) → prints the usage-ish error path without stacktrace noise is acceptable; the focused test is the gate.

- [ ] **Step 5: Commit**

```bash
jj new && jj desc -m "feat(tasks): nido:workstream entry/stage/close ledger commands"
```

---

## Task 8: Session-home briefing carries the workstream-id

**Files:**
- Modify: the session-home launcher (the code that writes `~/.nido/sessions/<p>/<s>/CLAUDE.md` for run-owned sessions). **First action: locate it** — grep for where the briefing/`CLAUDE.md` is written and where `:owned-by-run` is threaded (start: `src/nido/coordinator/shim.clj`, `src/nido/session/launcher*`, and `runs/spawn-session-for-run!`).

This task is specified at acceptance-criteria grain because the exact launcher file must be read first.

- [ ] **Step 1: Locate & read** the briefing writer. Identify where run context (skill, first-message, BR-id) already lands in the session-home so the agent can resume.

- [ ] **Step 2: Write/extend a test** asserting that, given a run with `:workstream-id`, the generated briefing (or the session-edn the launcher consumes) includes the workstream-id and BR-id. Use the launcher's existing test pattern.

- [ ] **Step 3: Implement** — thread `:workstream-id` from the run into the briefing/session-home. The run already carries it (Task 2); `spawn-session-for-run!` has the run. Add a `Workstream: <ws-id> (<BR-####>)` line to the briefing so the in-session agent, the triage dual-write, and `/continue-ticket` can target the ledger.

- [ ] **Step 4: Verify** focused test + `bb nido:test`.

- [ ] **Step 5: Commit** `jj new && jj desc -m "feat(coordinator): session-home briefing carries workstream-id"`.

**Acceptance:** a run-owned session's briefing names its workstream-id; no regression in launcher tests.

---

## Task 9: Triage skill dual-writes the workstream ledger

**Files:**
- Modify: the brian `triage-bug` skill (`SKILL.md`) — a harness/doc change, mirrored into nido per `harness.edn`. **First action: read** the current `triage-bug` SKILL.md to see exactly where it calls `bb nido:ticket:open` / append-entry / status-set.

This task is a skill-instruction edit, not Clojure; no unit test. Verification is a manual dry-run of the triage flow.

- [ ] **Step 1: Read** the `triage-bug` SKILL.md; note each `bb nido:ticket:*` call site and the HITL halt point.

- [ ] **Step 2: Edit** the skill so that, alongside (not instead of) each ticket write, it also calls the new `bb nido:workstream:*` command with the same content — resolving the workstream from the `Workstream:` line in the session-home briefing (Task 8) or via `:ref <BR-####>`:
  - on drafting findings → `bb nido:workstream:entry:add … :kind triage :content <report>`
  - on stage change → `bb nido:workstream:stage:advance … :stage <investigating|…>`
  - on terminal verdict → `bb nido:workstream:close … :outcome <done|dropped>` where the ticket flow would close.
  Keep the existing `bb nido:ticket:*` calls (dual-write) and the HITL halt unchanged ([[project_triage_safety_model]]).

- [ ] **Step 3: Annotate** the dual-write as transitional in the skill text (the legacy ticket leg is dropped once the ledger is trusted — [[feedback_dormant_extension_points]]).

- [ ] **Step 4: Verify** — `bb nido:harness:sync` reconciles the mirror; do a manual triage dry-run (or one live `teacher-bugs` cycle) and confirm both a ticket `meta.edn` AND a workstream entry/stage appear, and the new TUI shows the workstream as `:parked-at-gate` at the HITL halt.

- [ ] **Step 5: Commit** the skill change in the brian repo (per its own VCS rules), then `bb nido:harness:sync` in nido.

**Acceptance:** one triage cycle produces both legacy ticket and workstream-ledger records; the Workstreams TUI shows the live workstream and its parked session.

---

## Final step: re-enable triage and observe end-to-end

After Tasks 1–9 land and the suite is green:
- Confirm `:triage-teacher-bugs` (and optionally `:triage-new`) are active and breakers clear.
- Watch one poll cycle: `bb nido:coordinator:logs :follow true`.
- Open the TUI (`bb nido:tui`), enter the project, press `r` → the live triage workstream should appear under Active/Parked-at-gate with its session; drilling in shows phase tracking the run.

---

## Self-Review notes

- **Spec coverage:** spawn find-or-create (Task 3), session-as-source-of-truth + run linkage (Tasks 2–3), phase mirroring incl. reconcile (Tasks 1, 4), gating swap (Task 6), triage dual-write + ledger commands (Tasks 7, 9), briefing ws-id (Task 8), execution machinery untouched (no task modifies watchdog/agent.log/artifacts/runs-clean). Dry-run skip and `:workstream-stage` default honored in Tasks 5 and 3.
- **Gating decision** is flagged in Task 6 (parked-inclusive backpressure; one-line flip to the looser semantics).
- **Tasks 8–9** are intentionally at acceptance-criteria grain — they require reading the launcher and the brian triage skill first; the implementer reads those before writing. All other tasks carry complete code.
- **Concurrency:** `ensure-workstream!` find-or-create is racy under parallel spawns on the same ref. `process-envelope!` runs in the daemon tick (single-threaded routing); confirm spawns are serialized before relying on this. If parallel spawns are possible, add a per-project lock around `ensure-workstream!` (note, not yet a task — verify during Task 5).
