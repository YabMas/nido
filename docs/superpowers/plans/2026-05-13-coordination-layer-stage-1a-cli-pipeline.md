# Coordination Layer — Stage 1a (CLI pipeline) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a foreground-only nido coordinator that watches a manual-trigger filesystem queue, spawns sessions running headless claude with a configured skill, tracks Run lifecycle in `~/.nido/runs/`, and lets the user fire and inspect Runs via three new bb tasks. No TUI yet — that's Stage 1b.

**Architecture:** Filesystem-canonical: TUI/CLI write request files; daemon watches; daemon writes Run state files. Coordinator is one foreground bb process. Sessions remain a primitive — Run-owned sessions carry `:owned-by-run` in `session.edn` and gain a `bin/claude` shim + `run-link/` symlink so typing `claude` after the autonomous phase resumes the conversation.

**Tech Stack:** Babashka, Clojure, [Malli](https://github.com/metosin/malli) for schemas, `babashka.fs`, `babashka.process`, `clojure.test`.

**Spec:** [`docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`](../specs/2026-05-13-nido-coordination-layer-design.md). This plan implements **Stage 1** of that spec, minus the TUI runs screen. Stage 1b (TUI) and stages 2–6 each get their own plans later.

**Out of scope for Stage 1a (deferred to later plans):**
- TUI runs screen → Stage 1b
- Wall-clock budget, circuit breaker, anomaly auto-halt, kill switch → Stage 2
- Background / launchd daemon → Stages 3–4
- Notion / cron / GitHub event sources → Stages 5+
- Multi-agent vendor dispatch (`:codex` etc.) — only `:claude` in Stage 1a

---

## File structure overview

```
src/nido/coordinator/
├── state.clj          # paths under ~/.nido/coordinator and ~/.nido/runs; mkdir
├── runs.clj           # run.edn schema, read/write, state machine
├── triggers.clj       # trigger schema, per-project load + validate, payload interp
├── events.clj         # envelope routing (direct-target only in 1a)
├── queue.clj          # :manual source — poll ~/.nido/coordinator/queue/
├── status_file.clj    # read session-home/_run-status.edn; map to Run state
├── shim.clj           # write session-home/bin/claude + run-link/ symlink
├── agent.clj          # headless claude launch, stream-json parsing
├── heartbeat.clj      # rewrite ~/.nido/coordinator/status.edn every 2s
└── core.clj           # main loop: wires all the above

src/tasks/
├── nido_coordinator.clj   # coordinator:run, coordinator:status
├── nido_trigger.clj       # trigger:fire, trigger:list
└── nido_runs.clj          # runs:list, runs:show

test/nido/coordinator/
├── state_test.clj
├── runs_test.clj
├── triggers_test.clj
├── events_test.clj
├── queue_test.clj
├── status_file_test.clj
├── shim_test.clj
├── agent_test.clj
└── e2e_test.clj        # spawns coordinator, fires fake trigger, asserts lifecycle

resources/test/
└── fake-claude/
    └── claude            # bash script that emulates `claude --print --output-format=stream-json`

bb.edn                     # add :tasks/requires entries + test task; add malli dep
```

Each `nido/coordinator/<name>.clj` namespace is self-contained: ≤200 LOC, one responsibility, public API in the namespace docstring. The test files mirror 1:1.

---

## Task 0: Bootstrap test infrastructure

**Files:**
- Modify: `bb.edn` (add `test` path + a `nido:test` task + `metosin/malli` dep)
- Create: `test/nido/smoke_test.clj`
- Create: `src/tasks/nido_test.clj`

There are no tests in nido today. This task adds a minimal runner so subsequent tasks can be TDD'd.

- [ ] **Step 1: Add the dep and test path to `bb.edn`**

```clojure
;; bb.edn — top of file
{:min-bb-version "1.12.215"
 :paths ["src" "resources" "test"]

 :deps {de.timokramer/charm.clj {:mvn/version "0.2.71"}
        metosin/malli           {:mvn/version "0.16.4"}}
 ...}
```

Add `tasks.nido-test :as nido-test` to the `:requires` vector and a task entry:

```clojure
nido:test
{:doc "Run unit tests. Optional :only <ns-prefix> to filter."
 :task (apply nido-test/run *command-line-args*)}
```

- [ ] **Step 2: Write the test runner task**

Create `src/tasks/nido_test.clj`:

```clojure
(ns tasks.nido-test
  "Run unit tests under test/. Optional :only <ns-prefix> filter."
  (:require
   [babashka.classpath :as cp]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :as t]
   [nido.task-args :as task-args]))

(defn- discover-test-namespaces
  "Find test namespaces under test/, optionally filtered by prefix."
  [prefix]
  (->> (fs/glob "test" "**/*_test.clj")
       (map str)
       (map #(-> %
                 (str/replace #"^test/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "_" "-")
                 (str/replace "/" ".")
                 symbol))
       (filter (fn [ns-sym]
                 (or (str/blank? prefix)
                     (str/starts-with? (str ns-sym) prefix))))
       sort))

(defn run [& args]
  (let [{:keys [only]} (task-args/parse args)
        nses (discover-test-namespaces only)]
    (when (empty? nses)
      (println "No test namespaces found." (when only (str "filter: " only)))
      (System/exit 0))
    (doseq [ns-sym nses]
      (require ns-sym))
    (let [{:keys [fail error] :as summary} (apply t/run-tests nses)]
      (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))))
```

- [ ] **Step 3: Write the smoke test**

Create `test/nido/smoke_test.clj`:

```clojure
(ns nido.smoke-test
  (:require [clojure.test :refer [deftest is]]))

(deftest smoke
  (is (= 4 (+ 2 2)) "test runner is wired up"))
```

- [ ] **Step 4: Run the runner**

```bash
bb nido:test
```

Expected: one passing test, exit 0.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(nido/test): bootstrap clojure.test runner + malli dep"
jj new
```

The `jj new` starts the next changeset clean (per `CLAUDE.md` "fresh changeset before coding").

---

## Task 1: State paths + directory init

**Files:**
- Create: `src/nido/coordinator/state.clj`
- Create: `test/nido/coordinator/state_test.clj`

This namespace centralizes every path the coordinator reads or writes. Everything else depends on these — getting them right once keeps the rest small.

- [ ] **Step 1: Write the failing test**

Create `test/nido/coordinator/state_test.clj`:

```clojure
(ns nido.coordinator.state-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.state :as cstate]))

(deftest paths
  (testing "coordinator-dir under ~/.nido/coordinator"
    (is (= (str (fs/path (fs/home) ".nido" "coordinator"))
           (str (cstate/coordinator-dir)))))
  (testing "queue-dir is a child of coordinator-dir"
    (is (= (str (fs/path (cstate/coordinator-dir) "queue"))
           (str (cstate/queue-dir)))))
  (testing "status-path is status.edn under coordinator-dir"
    (is (= (str (fs/path (cstate/coordinator-dir) "status.edn"))
           (str (cstate/status-path)))))
  (testing "runs-dir under ~/.nido/runs"
    (is (= (str (fs/path (fs/home) ".nido" "runs"))
           (str (cstate/runs-dir)))))
  (testing "run-dir is a child of runs-dir, named by run-id"
    (is (= (str (fs/path (cstate/runs-dir) "abc-123"))
           (str (cstate/run-dir "abc-123")))))
  (testing "run.edn is named correctly inside run-dir"
    (is (= (str (fs/path (cstate/run-dir "abc-123") "run.edn"))
           (str (cstate/run-edn-path "abc-123")))))
  (testing "triggers.edn path is per-project"
    (is (= (str (fs/path (fs/home) ".nido" "projects" "brian" "triggers.edn"))
           (str (cstate/triggers-path :brian))))))

(deftest ensure-dirs!-creates-coordinator-tree
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/coordinator-root (constantly tmp)]
        (cstate/ensure-dirs!)
        (is (fs/directory? (fs/path tmp "queue")))
        (is (fs/directory? (fs/path tmp "..")))) ; sanity
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.state-test
```

Expected: FAIL with "Could not locate nido/coordinator/state".

- [ ] **Step 3: Write minimal implementation**

Create `src/nido/coordinator/state.clj`:

```clojure
(ns nido.coordinator.state
  "Filesystem paths the coordinator owns.

   Layout (per spec §Directory layout summary):
     ~/.nido/coordinator/
       config.edn
       status.edn
       halted.edn   (only when auto-halted — stage 2)
       queue/<uuid>.edn
     ~/.nido/projects/<project>/triggers.edn
     ~/.nido/runs/<run-id>/{run.edn, _run-status.edn, artifacts/, agent.log, session-home}"
  (:require [babashka.fs :as fs]))

(defn nido-root []
  (fs/path (fs/home) ".nido"))

(defn coordinator-root []
  (fs/path (nido-root) "coordinator"))

(defn coordinator-dir [] (coordinator-root))

(defn queue-dir []
  (fs/path (coordinator-root) "queue"))

(defn status-path []
  (fs/path (coordinator-root) "status.edn"))

(defn config-path []
  (fs/path (coordinator-root) "config.edn"))

(defn runs-dir []
  (fs/path (nido-root) "runs"))

(defn run-dir [run-id]
  (fs/path (runs-dir) run-id))

(defn run-edn-path [run-id]
  (fs/path (run-dir run-id) "run.edn"))

(defn run-status-path [run-id]
  (fs/path (run-dir run-id) "_run-status.edn"))

(defn run-artifacts-dir [run-id]
  (fs/path (run-dir run-id) "artifacts"))

(defn run-agent-log [run-id]
  (fs/path (run-dir run-id) "agent.log"))

(defn run-session-home-link [run-id]
  (fs/path (run-dir run-id) "session-home"))

(defn triggers-path [project]
  (fs/path (nido-root) "projects" (name project) "triggers.edn"))

(defn ensure-dirs!
  "Create the coordinator + runs directories if absent. Idempotent."
  []
  (fs/create-dirs (coordinator-root))
  (fs/create-dirs (queue-dir))
  (fs/create-dirs (runs-dir)))
```

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.state-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): state paths + dir init"
jj new
```

---

## Task 2: Run schema + read/write

**Files:**
- Create: `src/nido/coordinator/runs.clj`
- Create: `test/nido/coordinator/runs_test.clj`

Defines the canonical Run record and round-trips it through disk.

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.runs-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]))

(def example-run
  {:id              "2026-05-13-brian-investigate-bug-a1b2c3"
   :project         :brian
   :trigger         :investigate-bug
   :source          {:type :manual :fired-at "2026-05-13T09:14:22Z" :fired-by "yabmas"}
   :event-payload   {:url "https://notion.so/page/abc"}
   :skill           :investigate-bug
   :first-message   "/investigate-bug https://notion.so/page/abc"
   :agent           :claude
   :session-name    "run-2026-05-13-investigate-bug-a1b2c3"
   :claude-session-id nil
   :limits          {:budget "30m"}
   :state           :queued
   :state-history   [{:at "2026-05-13T09:14:22Z" :state :queued}]
   :artifacts       []
   :error           nil})

(deftest schema-accepts-valid-run
  (is (m/validate runs/Run example-run)))

(deftest schema-rejects-bad-state
  (is (not (m/validate runs/Run (assoc example-run :state :nonsense)))))

(deftest schema-rejects-missing-required-fields
  (is (not (m/validate runs/Run (dissoc example-run :id))))
  (is (not (m/validate runs/Run (dissoc example-run :state)))))

(deftest round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly tmp)]
        (fs/create-dirs (cstate/run-dir (:id example-run)))
        (runs/write-run! example-run)
        (is (= example-run (runs/read-run (:id example-run)))))
      (finally (fs/delete-tree tmp)))))

(deftest read-run-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly tmp)]
        (is (nil? (runs/read-run "does-not-exist"))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.runs-test
```

Expected: FAIL with "Could not locate nido/coordinator/runs".

- [ ] **Step 3: Implement schema + I/O**

```clojure
(ns nido.coordinator.runs
  "Canonical Run record: schema, read/write, state machine.

   See spec §Runs."
  (:require
   [babashka.fs :as fs]
   [malli.core :as m]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def states
  "Permitted Run states. See spec §Runs / Lifecycle."
  #{:queued :running :awaiting-review :done :failed :halted :dry-run-would-fire})

(def Run
  [:map {:closed true}
   [:id                string?]
   [:project           keyword?]
   [:trigger           keyword?]
   [:source            [:map [:type keyword?]]]
   [:event-payload     [:map-of keyword? any?]]
   [:skill             keyword?]
   [:first-message     string?]
   [:agent             keyword?]
   [:session-name      string?]
   [:claude-session-id [:maybe string?]]
   [:limits            [:map-of keyword? any?]]
   [:state             (into [:enum] states)]
   [:state-history     [:vector [:map
                                 [:at    string?]
                                 [:state (into [:enum] states)]]]]
   [:artifacts         [:vector [:map
                                 [:path string?]
                                 [:written-at string?]]]]
   [:error             [:maybe [:map-of keyword? any?]]]])

(defn validate
  "Returns the run or throws ex-info with humanized errors."
  [run]
  (if (m/validate Run run)
    run
    (throw (ex-info "Invalid Run record"
                    {:errors (m/explain Run run)
                     :run    run}))))

(defn read-run
  "Read a run.edn by id. Returns nil if absent."
  [run-id]
  (let [path (cstate/run-edn-path run-id)]
    (when (fs/exists? path)
      (io/read-edn (str path)))))

(defn write-run!
  "Validate then write a Run record. Parent dir must already exist."
  [run]
  (validate run)
  (io/write-edn! (str (cstate/run-edn-path (:id run))) run)
  run)
```

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.runs-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): Run schema + read/write"
jj new
```

---

## Task 3: Run state machine

**Files:**
- Modify: `src/nido/coordinator/runs.clj` (add `transition!` and `valid-transition?`)
- Modify: `test/nido/coordinator/runs_test.clj` (add transition tests)

- [ ] **Step 1: Add failing tests to `runs_test.clj`**

Append:

```clojure
(deftest valid-transitions
  (is (runs/valid-transition? :queued :running))
  (is (runs/valid-transition? :running :awaiting-review))
  (is (runs/valid-transition? :running :done))
  (is (runs/valid-transition? :running :failed))
  (is (runs/valid-transition? :awaiting-review :running))
  (is (runs/valid-transition? :awaiting-review :done))
  (is (not (runs/valid-transition? :queued :awaiting-review)))
  (is (not (runs/valid-transition? :done :running)))
  (is (not (runs/valid-transition? :failed :running))))

(deftest transition!-updates-state-and-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly tmp)]
        (fs/create-dirs (cstate/run-dir (:id example-run)))
        (runs/write-run! example-run)
        (let [updated (runs/transition! (:id example-run) :running)]
          (is (= :running (:state updated)))
          (is (= 2 (count (:state-history updated))))
          (is (= :running (-> updated :state-history last :state)))
          (is (string? (-> updated :state-history last :at)))))
      (finally (fs/delete-tree tmp)))))

(deftest transition!-rejects-invalid-transition
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly tmp)]
        (fs/create-dirs (cstate/run-dir (:id example-run)))
        (runs/write-run! (assoc example-run :state :done))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid transition"
                              (runs/transition! (:id example-run) :running))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
bb nido:test :only nido.coordinator.runs-test
```

Expected: FAIL with undefined-var on `valid-transition?` and `transition!`.

- [ ] **Step 3: Implement**

Append to `src/nido/coordinator/runs.clj`:

```clojure
(def allowed-transitions
  "Map of from-state → set of to-states.
   See spec §Runs / Lifecycle. Terminal states have no entries."
  {:queued          #{:running :failed :halted}
   :running         #{:awaiting-review :done :failed :halted}
   :awaiting-review #{:running :done :failed :halted}})

(defn valid-transition? [from to]
  (boolean (contains? (get allowed-transitions from #{}) to)))

(defn- now-iso []
  (str (java.time.Instant/now)))

(defn transition!
  "Atomically update a Run's state with history. Throws if the transition
   is invalid. Returns the updated Run."
  [run-id new-state]
  (let [run     (read-run run-id)
        from    (:state run)]
    (when-not (valid-transition? from new-state)
      (throw (ex-info "Invalid transition"
                      {:run-id run-id :from from :to new-state})))
    (let [updated (-> run
                      (assoc :state new-state)
                      (update :state-history conj {:at (now-iso) :state new-state}))]
      (write-run! updated)
      updated)))
```

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.runs-test
```

Expected: PASS (all tests, old and new).

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): Run state machine + transition!"
jj new
```

---

## Task 4: Trigger schema + per-project loader

**Files:**
- Create: `src/nido/coordinator/triggers.clj`
- Create: `test/nido/coordinator/triggers_test.clj`

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.triggers-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [nido.coordinator.triggers :as triggers]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def minimal-trigger
  {:name    :investigate-bug
   :source  {:type :manual}
   :skill   :investigate-bug
   :payload "{{event/url}}"})

(deftest schema-accepts-minimal-trigger
  (is (m/validate triggers/Trigger minimal-trigger)))

(deftest schema-rejects-missing-name
  (is (not (m/validate triggers/Trigger (dissoc minimal-trigger :name)))))

(deftest schema-accepts-optional-fields
  (let [t (merge minimal-trigger
                 {:filter      {:priority ["P0"]}
                  :payload-key :ticket-id
                  :agent       :claude
                  :limits      {:budget "45m" :max-failures 3}
                  :dry-run?    true
                  :enabled?    false})]
    (is (m/validate triggers/Trigger t))))

(deftest load-triggers-reads-file
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly tmp)]
        (fs/create-dirs (fs/parent (cstate/triggers-path :brian)))
        (io/write-edn! (str (cstate/triggers-path :brian))
                       {:triggers [minimal-trigger]})
        (let [loaded (triggers/load-for-project :brian)]
          (is (= 1 (count loaded)))
          (is (= :investigate-bug (-> loaded first :name)))))
      (finally (fs/delete-tree tmp)))))

(deftest load-triggers-returns-empty-when-file-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly tmp)]
        (is (= [] (triggers/load-for-project :nonexistent))))
      (finally (fs/delete-tree tmp)))))

(deftest find-trigger-by-name
  (let [ts [minimal-trigger
            (assoc minimal-trigger :name :other)]]
    (is (= :investigate-bug (:name (triggers/find-by-name ts :investigate-bug))))
    (is (nil? (triggers/find-by-name ts :missing)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.triggers-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 3: Implement**

Create `src/nido/coordinator/triggers.clj`:

```clojure
(ns nido.coordinator.triggers
  "Per-project trigger config: schema, load, validate, find.

   See spec §Triggers."
  (:require
   [babashka.fs :as fs]
   [malli.core :as m]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

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
   [:dry-run?    {:optional true} boolean?]
   [:enabled?    {:optional true} boolean?]])

(def TriggersFile
  [:map {:closed true}
   [:triggers [:vector Trigger]]])

(defn load-for-project
  "Read triggers.edn for a project. Returns a vector of trigger maps
   (possibly empty). Invalid entries are skipped with a stderr warning."
  [project]
  (let [path (cstate/triggers-path project)]
    (if (fs/exists? path)
      (let [raw (io/read-edn (str path))]
        (if (m/validate TriggersFile raw)
          (:triggers raw)
          (do
            (binding [*err* *err*]
              (println "WARN: invalid triggers.edn for project" project
                       "—" (pr-str (m/explain TriggersFile raw))))
            (->> (:triggers raw)
                 (filter #(m/validate Trigger %))
                 vec))))
      [])))

(defn find-by-name
  "Find a trigger in a loaded vector by :name. Returns nil if absent."
  [triggers name]
  (some #(when (= name (:name %)) %) triggers))
```

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.triggers-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): trigger schema + per-project loader"
jj new
```

---

## Task 5: Payload interpolation

**Files:**
- Modify: `src/nido/coordinator/triggers.clj` (add `render-payload`)
- Modify: `test/nido/coordinator/triggers_test.clj` (add interpolation tests)

- [ ] **Step 1: Add failing tests**

Append to `triggers_test.clj`:

```clojure
(deftest render-payload-substitutes-top-level
  (is (= "/investigate-bug url=https://example.com"
         (triggers/render-payload "/investigate-bug url={{event/url}}"
                                  {:url "https://example.com"}))))

(deftest render-payload-substitutes-nested
  (is (= "ticket=ABC priority=P0"
         (triggers/render-payload "ticket={{event/ticket/id}} priority={{event/ticket/priority}}"
                                  {:ticket {:id "ABC" :priority "P0"}}))))

(deftest render-payload-leaves-literal-text-alone
  (is (= "no placeholders here"
         (triggers/render-payload "no placeholders here" {:url "x"}))))

(deftest render-payload-missing-key-renders-empty
  (is (= "url=" (triggers/render-payload "url={{event/missing}}" {:url "x"}))))
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
bb nido:test :only nido.coordinator.triggers-test
```

Expected: FAIL with undefined-var on `render-payload`.

- [ ] **Step 3: Implement**

Append to `src/nido/coordinator/triggers.clj`:

```clojure
(require '[clojure.string :as str])

(def ^:private placeholder-re
  #"\{\{event/([^}]+)\}\}")

(defn- lookup-path
  "Resolve a slash-delimited path like 'ticket/id' against an event map."
  [event path]
  (let [ks (mapv keyword (str/split path #"/"))]
    (get-in event ks)))

(defn render-payload
  "Replace {{event/path}} placeholders in template with values from event.
   Missing values render as empty string."
  [template event]
  (str/replace template placeholder-re
               (fn [[_ path]] (str (lookup-path event path) ))))
```

(Move the `(require ...)` to the namespace `:require` form in practice; shown inline here for clarity.)

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.triggers-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): payload interpolation"
jj new
```

---

## Task 6: Envelope routing

**Files:**
- Create: `src/nido/coordinator/events.clj`
- Create: `test/nido/coordinator/events_test.clj`

Resolves a `{:target {:project :trigger} :payload}` envelope to a "fire request" containing the matched trigger + the event payload. Broadcast routing is designed-in but stubbed (not used until Stage 5).

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.events-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.events :as events]))

(def triggers-fixture
  [{:name    :investigate-bug
    :source  {:type :manual}
    :skill   :investigate-bug
    :payload "{{event/url}}"}
   {:name    :other
    :source  {:type :manual}
    :skill   :other
    :payload "x"}])

(deftest direct-target-resolves-trigger
  (let [envelope {:target  {:project :brian :trigger :investigate-bug}
                  :payload {:url "https://x"}}
        request  (events/route envelope {:brian triggers-fixture})]
    (is (= :investigate-bug (-> request :trigger :name)))
    (is (= :brian (:project request)))
    (is (= {:url "https://x"} (:payload request)))))

(deftest direct-target-unknown-trigger-returns-error
  (let [envelope {:target {:project :brian :trigger :missing} :payload {}}
        result   (events/route envelope {:brian triggers-fixture})]
    (is (= :unknown-trigger (:error result)))))

(deftest direct-target-unknown-project-returns-error
  (let [envelope {:target {:project :nope :trigger :x} :payload {}}
        result   (events/route envelope {:brian triggers-fixture})]
    (is (= :unknown-project (:error result)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.events-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 3: Implement**

Create `src/nido/coordinator/events.clj`:

```clojure
(ns nido.coordinator.events
  "Envelope routing: turn an incoming envelope into a fire request.

   Two envelope shapes (spec §Event sources / Two event-flow patterns):
     {:target {:project :p :trigger :t} :payload <m>}   — direct-target
     {:broadcast <event>}                              — broadcast (stage 5+)

   In Stage 1a only :target envelopes occur (from the :manual source).
   Broadcast routing is stubbed so the contract is in place."
  (:require [nido.coordinator.triggers :as triggers]))

(defn- route-direct
  [{:keys [target payload]} triggers-by-project]
  (let [{:keys [project trigger]} target
        ts (get triggers-by-project project)]
    (cond
      (nil? ts)
      {:error :unknown-project :project project}

      :else
      (if-let [t (triggers/find-by-name ts trigger)]
        {:project project :trigger t :payload payload}
        {:error :unknown-trigger :project project :trigger trigger}))))

(defn route
  "Resolve an envelope to a fire request map:
     {:project <kw> :trigger <trigger-map> :payload <m>}
   or an error map:
     {:error <kw> :project ... :trigger ...}

   `triggers-by-project` is `{:project [<trigger>, ...]}`."
  [envelope triggers-by-project]
  (cond
    (:target envelope)    (route-direct envelope triggers-by-project)
    (:broadcast envelope) {:error :broadcast-not-implemented}
    :else                 {:error :unknown-envelope}))
```

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.events-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): envelope routing (direct-target)"
jj new
```

---

## Task 7: Manual queue source

**Files:**
- Create: `src/nido/coordinator/queue.clj`
- Create: `test/nido/coordinator/queue_test.clj`

Reads (and removes) files from `~/.nido/coordinator/queue/`, returning a vector of parsed envelopes. The coordinator's main loop calls this on a poll interval.

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.queue-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(deftest drain-reads-and-removes-files
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/coordinator-root (constantly tmp)]
        (cstate/ensure-dirs!)
        (let [f1 (str (fs/path (cstate/queue-dir) "a.edn"))
              f2 (str (fs/path (cstate/queue-dir) "b.edn"))]
          (io/write-edn! f1 {:target {:project :brian :trigger :x} :payload {:url "1"}})
          (io/write-edn! f2 {:target {:project :brian :trigger :x} :payload {:url "2"}})
          (let [envelopes (queue/drain!)]
            (is (= 2 (count envelopes)))
            (is (every? #(= :brian (-> % :target :project)) envelopes))
            (is (not (fs/exists? f1)))
            (is (not (fs/exists? f2))))))
      (finally (fs/delete-tree tmp)))))

(deftest drain-empty-queue
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/coordinator-root (constantly tmp)]
        (cstate/ensure-dirs!)
        (is (= [] (queue/drain!))))
      (finally (fs/delete-tree tmp)))))

(deftest drain-skips-and-quarantines-malformed-files
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/coordinator-root (constantly tmp)]
        (cstate/ensure-dirs!)
        (let [good (str (fs/path (cstate/queue-dir) "good.edn"))
              bad  (str (fs/path (cstate/queue-dir) "bad.edn"))]
          (io/write-edn! good {:target {:project :p :trigger :t} :payload {}})
          (io/write-text! bad "not-edn-at-all{{{")
          (let [envelopes (queue/drain!)]
            (is (= 1 (count envelopes)))
            (is (not (fs/exists? good)))
            (is (fs/exists? (str (fs/path (cstate/queue-dir) "bad.edn.malformed"))) "bad file renamed for inspection"))))
      (finally (fs/delete-tree tmp)))))

(deftest enqueue!-writes-an-envelope-file
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/coordinator-root (constantly tmp)]
        (cstate/ensure-dirs!)
        (queue/enqueue! {:target {:project :brian :trigger :x} :payload {:url "1"}})
        (is (= 1 (count (fs/list-dir (cstate/queue-dir))))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.queue-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 3: Implement**

Create `src/nido/coordinator/queue.clj`:

```clojure
(ns nido.coordinator.queue
  "The :manual event source — a filesystem queue of envelopes.

   - `enqueue!` writes an envelope file under ~/.nido/coordinator/queue/<uuid>.edn
   - `drain!` reads, deletes, and returns all pending envelopes (skipping
     malformed files, which are renamed `<file>.malformed` for inspection)."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn- read-envelope-file [path]
  (try
    [(edn/read-string (slurp (str path))) nil]
    (catch Exception e [nil e])))

(defn drain!
  "Read and remove all envelope files. Returns a vector of envelopes.
   Malformed files are renamed `<file>.malformed` and skipped."
  []
  (let [files (->> (fs/list-dir (cstate/queue-dir))
                   (filter #(re-matches #".*\.edn$" (str (fs/file-name %))))
                   (sort-by str))]
    (reduce
      (fn [acc f]
        (let [[envelope err] (read-envelope-file f)]
          (if err
            (do
              (fs/move f (str f ".malformed"))
              (binding [*err* *err*]
                (println "WARN: malformed queue file" (str f) "—" (ex-message err)))
              acc)
            (do
              (fs/delete f)
              (conj acc envelope)))))
      []
      files)))

(defn enqueue!
  "Write an envelope to the queue with a fresh UUID filename."
  [envelope]
  (let [uuid (str (java.util.UUID/randomUUID))
        path (str (fs/path (cstate/queue-dir) (str uuid ".edn")))]
    (io/write-edn! path (assoc envelope :created-at (str (java.time.Instant/now))))
    path))
```

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.queue-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): manual queue source (drain/enqueue)"
jj new
```

---

## Task 8: Run creation from fire request

**Files:**
- Modify: `src/nido/coordinator/runs.clj` (add `create-run!`)
- Modify: `test/nido/coordinator/runs_test.clj`

Given a fire request from the events router, produce a new `:queued` Run, write its `run.edn`, and return it. Session spawning is the next task — this task only produces the Run record.

- [ ] **Step 1: Add failing test**

Append to `runs_test.clj`:

```clojure
(deftest create-run!-builds-a-queued-run
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly tmp)]
        (let [fire-req {:project :brian
                        :trigger {:name    :investigate-bug
                                  :source  {:type :manual}
                                  :skill   :investigate-bug
                                  :payload "/investigate-bug url={{event/url}}"
                                  :agent   :claude}
                        :payload {:url "https://x"}}
              run      (runs/create-run! fire-req {:fired-at "T" :fired-by "u"})]
          (is (= :queued (:state run)))
          (is (= :brian (:project run)))
          (is (= :investigate-bug (:trigger run)))
          (is (= :investigate-bug (:skill run)))
          (is (= "/investigate-bug url=https://x" (:first-message run)))
          (is (re-matches #"\d{4}-\d{2}-\d{2}-brian-investigate-bug-[a-f0-9]{8}" (:id run)))
          (is (= (:id run) (-> run :id))) ; trivial
          (is (= 1 (count (:state-history run))))
          (is (fs/exists? (cstate/run-edn-path (:id run))))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.runs-test
```

Expected: FAIL with undefined-var `create-run!`.

- [ ] **Step 3: Implement**

Append to `src/nido/coordinator/runs.clj`:

```clojure
(require '[nido.coordinator.triggers :as triggers])

(defn- new-run-id [project trigger-name]
  (let [date (subs (now-iso) 0 10)
        suf  (subs (str (java.util.UUID/randomUUID)) 0 8)]
    (str date "-" (name project) "-" (name trigger-name) "-" suf)))

(defn create-run!
  "Build a :queued Run record from a fire request and persist run.edn.
   `meta` carries source-call metadata: {:fired-at <iso> :fired-by <str>}."
  [{:keys [project trigger payload]} meta]
  (let [run-id  (new-run-id project (:name trigger))
        sess    (str "run-" (subs run-id 11))   ; drop date prefix in session name
        message (str "/" (name (:skill trigger)) " "
                     (triggers/render-payload (:payload trigger) payload))
        run     {:id              run-id
                 :project         project
                 :trigger         (:name trigger)
                 :source          (merge {:type (-> trigger :source :type)} meta)
                 :event-payload   payload
                 :skill           (:skill trigger)
                 :first-message   message
                 :agent           (or (:agent trigger) :claude)
                 :session-name    sess
                 :claude-session-id nil
                 :limits          (or (:limits trigger) {:budget "30m"})
                 :state           :queued
                 :state-history   [{:at (now-iso) :state :queued}]
                 :artifacts       []
                 :error           nil}]
    (fs/create-dirs (cstate/run-dir run-id))
    (fs/create-dirs (cstate/run-artifacts-dir run-id))
    (write-run! run)))
```

(Hoist the `nido.coordinator.triggers` require to the namespace `:require` block in practice.)

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.runs-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): create-run! builds queued Runs from fire requests"
jj new
```

---

## Task 9: Resume shim + run-link symlink

**Files:**
- Create: `src/nido/coordinator/shim.clj`
- Create: `test/nido/coordinator/shim_test.clj`

The shim must work without requiring an env var — it reads run.edn directly at invocation. The `run-link/` symlink in the session home makes the run.edn discoverable by relative path from the shim.

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.shim-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.shim :as shim]))

(deftest write-shim!-creates-executable-script-and-symlink
  (let [tmp           (fs/create-temp-dir)
        session-home  (str (fs/path tmp "sess"))
        run-dir       (str (fs/path tmp "run"))]
    (try
      (fs/create-dirs session-home)
      (fs/create-dirs run-dir)
      (shim/write! session-home run-dir)
      (let [shim-path (str (fs/path session-home "bin" "claude"))]
        (is (fs/exists? shim-path))
        (is (fs/executable? shim-path))
        (let [content (slurp shim-path)]
          (is (str/includes? content "claude --resume"))
          (is (str/includes? content "run-link/run.edn")))
        (let [link (str (fs/path session-home "run-link"))]
          (is (fs/sym-link? link))
          (is (= (str (fs/canonicalize run-dir))
                 (str (fs/canonicalize link))))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.shim-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 3: Implement**

Create `src/nido/coordinator/shim.clj`:

```clojure
(ns nido.coordinator.shim
  "Resume shim + run-link symlink for Run-owned sessions.

   Writes <session-home>/bin/claude (executable) and
   <session-home>/run-link symlink → <run-dir>.

   The shim reads run-link/run.edn at invocation time to discover the
   claude session-id, so typing `claude` from the session-home resumes
   the autonomous conversation. Falls through to a normal `claude` if
   the id is absent. See spec §Agent launch / Resume from the
   session-home."
  (:require
   [babashka.fs :as fs]))

(def ^:private shim-script
  "#!/usr/bin/env bash
set -euo pipefail
RUN_EDN=\"$(dirname \"$0\")/../run-link/run.edn\"
SESSION_ID=\"\"
if [ -f \"$RUN_EDN\" ]; then
  SESSION_ID=$(bb -e \"(-> (slurp \\\"$RUN_EDN\\\") clojure.edn/read-string :claude-session-id)\" 2>/dev/null || true)
fi
if [ -n \"$SESSION_ID\" ] && [ \"$SESSION_ID\" != \"nil\" ]; then
  exec command claude --resume \"$SESSION_ID\" \"$@\"
fi
exec command claude \"$@\"
")

(defn write!
  "Write the shim + run-link in the given session-home pointing at run-dir."
  [session-home run-dir]
  (let [bin-dir   (fs/path session-home "bin")
        shim-path (fs/path bin-dir "claude")
        link-path (fs/path session-home "run-link")]
    (fs/create-dirs bin-dir)
    (spit (str shim-path) shim-script)
    (fs/set-posix-file-permissions shim-path "rwxr-xr-x")
    (when (fs/exists? link-path) (fs/delete link-path))
    (fs/create-sym-link link-path run-dir)))
```

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.shim-test
```

Expected: PASS.

- [ ] **Step 5: Manually verify the shim is readable**

```bash
mkdir -p /tmp/shim-check/{sess,run}
echo '{:claude-session-id "abc-123"}' > /tmp/shim-check/run/run.edn
bb -e "(require 'nido.coordinator.shim) (nido.coordinator.shim/write! \"/tmp/shim-check/sess\" \"/tmp/shim-check/run\")"
cat /tmp/shim-check/sess/bin/claude
ls -la /tmp/shim-check/sess/run-link
rm -rf /tmp/shim-check
```

Expected: shim contains `claude --resume` with a relative `run-link/run.edn` path; symlink points at run dir.

- [ ] **Step 6: Commit**

```bash
jj desc -m "feat(coordinator): claude resume shim + run-link symlink"
jj new
```

---

## Task 10: `_run-status.edn` reader

**Files:**
- Create: `src/nido/coordinator/status_file.clj`
- Create: `test/nido/coordinator/status_file_test.clj`

Reads the skill-written status file from `<run-dir>/_run-status.edn` and maps its `:phase` to a Run lifecycle state.

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.status-file-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.status-file :as sf]
   [nido.io :as io]))

(deftest read-returns-nil-when-absent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly tmp)]
        (fs/create-dirs (cstate/run-dir "r1"))
        (is (nil? (sf/read-status "r1"))))
      (finally (fs/delete-tree tmp)))))

(deftest read-returns-map-when-present
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly tmp)]
        (fs/create-dirs (cstate/run-dir "r1"))
        (io/write-edn! (str (cstate/run-status-path "r1"))
                       {:phase :awaiting-input :note "look"})
        (is (= {:phase :awaiting-input :note "look"}
               (sf/read-status "r1"))))
      (finally (fs/delete-tree tmp)))))

(deftest phase->state
  (is (= :awaiting-review (sf/phase->state :awaiting-input)))
  (is (= :done            (sf/phase->state :complete)))
  (is (= :failed          (sf/phase->state :error)))
  (is (nil?               (sf/phase->state :investigating)))   ; ongoing, no transition
  (is (nil?               (sf/phase->state :working))))         ; ongoing, no transition

(deftest derived-state-after-clean-exit
  (testing "status says awaiting-input"
    (is (= :awaiting-review
           (sf/derive-state-after-exit {:phase :awaiting-input}))))
  (testing "status says complete"
    (is (= :done
           (sf/derive-state-after-exit {:phase :complete}))))
  (testing "status absent — treat as done"
    (is (= :done (sf/derive-state-after-exit nil)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.status-file-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 3: Implement**

Create `src/nido/coordinator/status_file.clj`:

```clojure
(ns nido.coordinator.status-file
  "Read the skill-written `<run-dir>/_run-status.edn` and map phases to
   Run lifecycle states. See spec §Skills as auto-trigger targets and
   §Agent launch."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn read-status
  "Returns the status map or nil if absent / malformed."
  [run-id]
  (let [p (cstate/run-status-path run-id)]
    (when (fs/exists? p)
      (try (io/read-edn (str p))
           (catch Exception _ nil)))))

(defn phase->state
  "Map a skill-reported phase to a Run state. nil for ongoing phases
   (the daemon should not transition the Run for those)."
  [phase]
  (case phase
    :awaiting-input :awaiting-review
    :complete       :done
    :error          :failed
    nil))

(defn derive-state-after-exit
  "Given a status map (or nil), what state should the Run move to
   after a clean agent exit? Absent status → :done."
  [status]
  (or (phase->state (:phase status)) :done))
```

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.status-file-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): _run-status.edn reader + phase mapping"
jj new
```

---

## Task 11: Fake claude for testing the agent launcher

**Files:**
- Create: `resources/test/fake-claude/claude` (bash script)

The headless claude launcher (Task 12) is hard to test against the real binary. A fake script that emulates the relevant slice of `--print --output-format=stream-json` lets us assert end-to-end behavior in tests.

- [ ] **Step 1: Write the fake script**

Create `resources/test/fake-claude/claude` with content:

```bash
#!/usr/bin/env bash
# Fake claude binary for tests. Emits stream-json events the way
# `claude --print --output-format=stream-json` would. Reads:
#   FAKE_CLAUDE_SESSION_ID — session id to emit in the init event
#   FAKE_CLAUDE_EXIT_CODE  — exit code to return (default 0)
#   FAKE_CLAUDE_STATUS_FILE — if set, path to write a _run-status.edn
#   FAKE_CLAUDE_DELAY_MS   — sleep before exiting (default 50)

set -euo pipefail
SESSION_ID="${FAKE_CLAUDE_SESSION_ID:-fake-session-001}"
EXIT_CODE="${FAKE_CLAUDE_EXIT_CODE:-0}"
DELAY_MS="${FAKE_CLAUDE_DELAY_MS:-50}"

# init event
printf '{"type":"system","subtype":"init","session_id":"%s"}\n' "$SESSION_ID"
# a fake tool call
printf '{"type":"assistant","tool_use":{"name":"Read"}}\n'
# sleep to let the parent observe at least one event
python3 -c "import time; time.sleep(${DELAY_MS}/1000.0)" 2>/dev/null || sleep 0.05

# optionally write a status file
if [ -n "${FAKE_CLAUDE_STATUS_FILE:-}" ]; then
  mkdir -p "$(dirname "$FAKE_CLAUDE_STATUS_FILE")"
  echo '{:phase :awaiting-input :note "test"}' > "$FAKE_CLAUDE_STATUS_FILE"
fi

# result event
printf '{"type":"result","subtype":"success"}\n'
exit $EXIT_CODE
```

- [ ] **Step 2: Make it executable**

```bash
chmod +x resources/test/fake-claude/claude
```

- [ ] **Step 3: Verify it runs**

```bash
FAKE_CLAUDE_SESSION_ID=abc resources/test/fake-claude/claude --print --output-format=stream-json "/skill x"
```

Expected: three JSON lines on stdout, ending in a `"result"` event with subtype `"success"`. Exit 0.

- [ ] **Step 4: Commit**

```bash
jj desc -m "test(coordinator): fake claude binary for agent-launch tests"
jj new
```

---

## Task 12: Headless claude launcher + stream-json parsing

**Files:**
- Create: `src/nido/coordinator/agent.clj`
- Create: `test/nido/coordinator/agent_test.clj`

Spawns claude, tees stdout to `agent.log`, parses events to capture the session-id, returns a result map. Uses `:claude-bin` indirection so tests can swap in the fake script.

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.agent-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.state :as cstate]))

(def fake-claude
  (str (fs/canonicalize "resources/test/fake-claude/claude")))

(deftest launch!-captures-session-id-and-exits-clean
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly tmp)]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x hi"
                        :claude-bin    fake-claude
                        :env           {"FAKE_CLAUDE_SESSION_ID" "session-xyz"}})]
          (is (= 0 (:exit-code result)))
          (is (= "session-xyz" (:claude-session-id result)))
          (is (fs/exists? (cstate/run-agent-log "r1"))
              "agent.log captured stream-json output")))
      (finally (fs/delete-tree tmp)))))

(deftest launch!-records-non-zero-exit
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/runs-dir (constantly tmp)]
        (fs/create-dirs (cstate/run-dir "r1"))
        (let [result (agent/launch!
                       {:run-id        "r1"
                        :cwd           (str tmp)
                        :first-message "/x"
                        :claude-bin    fake-claude
                        :env           {"FAKE_CLAUDE_EXIT_CODE" "7"}})]
          (is (= 7 (:exit-code result)))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.agent-test
```

Expected: FAIL with namespace-not-found.

- [ ] **Step 3: Implement**

Create `src/nido/coordinator/agent.clj`:

```clojure
(ns nido.coordinator.agent
  "Headless claude launcher (autonomous phase of a Run).

   See spec §Agent launch."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [nido.coordinator.state :as cstate]))

(defn- parse-event [^String line]
  (try (json/parse-string line keyword)
       (catch Exception _ nil)))

(defn- session-id-from [event]
  (when (and (= "system" (:type event))
             (= "init"   (:subtype event)))
    (:session_id event)))

(defn launch!
  "Spawn claude headlessly for a Run. Blocks until the agent exits.

   opts:
     :run-id        — run id (used to locate run-dir for agent.log path)
     :cwd           — working directory the agent runs in (worktree)
     :first-message — message passed as the positional argument
     :system-prompt — optional --append-system-prompt content
     :claude-bin    — path/name of the claude binary (override for tests)
     :env           — extra env vars to merge into the child's environment

   Returns:
     {:exit-code <int> :claude-session-id <str-or-nil>}"
  [{:keys [run-id cwd first-message system-prompt claude-bin env]
    :or   {claude-bin "claude"}}]
  (let [log-path (str (cstate/run-agent-log run-id))
        cmd      (cond-> [claude-bin
                          "--print"
                          "--output-format=stream-json"
                          "--dangerously-skip-permissions"]
                   system-prompt (into ["--append-system-prompt" system-prompt])
                   :always       (conj first-message))
        proc     (p/process cmd {:dir cwd
                                 :env (merge (into {} (System/getenv)) (or env {}))
                                 :out :stream
                                 :err :inherit
                                 :shutdown nil})
        session  (atom nil)]
    (with-open [w (clojure.java.io/writer log-path :append true)]
      (with-open [r (clojure.java.io/reader (:out proc))]
        (doseq [line (line-seq r)]
          (.write w line) (.write w "\n") (.flush w)
          (when-let [event (parse-event line)]
            (when-let [sid (session-id-from event)]
              (reset! session sid))))))
    (let [exit (:exit @proc)]
      {:exit-code         exit
       :claude-session-id @session})))
```

NOTE: babashka bundles `cheshire.core` for JSON. No extra dep needed.

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.agent-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): headless claude launcher + stream-json session-id capture"
jj new
```

---

## Task 13: Session spawning for Runs (integration with existing session layer)

**Files:**
- Modify: `src/nido/session/launcher.clj` (write `bin/claude` shim + `run-link` for Run-owned sessions)
- Modify: `src/nido/coordinator/runs.clj` (add `spawn-session-for-run!`)

Tested via the E2E test in Task 16. This task plumbs the layers together; behavior is observable end-to-end.

- [ ] **Step 1: Add a session-home decoration hook**

In `src/nido/session/launcher.clj`, after the existing artifacts are written (`.mcp.json`, `CLAUDE.md`, `worktree`, `.claude` symlinks), check for `:owned-by-run` in the session.edn and, if present, call into the shim namespace:

```clojure
;; In nido.session.launcher, find the function that writes the artifacts
;; (likely the entry point called from lifecycle/up!). After existing
;; writes, append:

(require '[nido.coordinator.shim :as coord-shim]
         '[nido.coordinator.state :as cstate])

;; ... at the end of the session-home decoration:
(when-let [run-id (:owned-by-run session-edn)]
  (coord-shim/write! session-home
                     (str (cstate/run-dir run-id))))
```

(The exact insertion site is at the end of the public `write-launcher-artifacts!`-style function. Locate it by searching for the place that writes `CLAUDE.md`. Add the require to the namespace's `:require` block.)

- [ ] **Step 2: Add `spawn-session-for-run!` in coordinator/runs.clj**

Append:

```clojure
(require '[nido.session.lifecycle :as session-lifecycle]
         '[nido.session.state :as session-state])

(defn spawn-session-for-run!
  "Create + bring up a session for a Run, marked :owned-by-run.
   Returns the session.edn data."
  [run]
  (let [{:keys [project session-name id]} run]
    ;; The exact session-up entry point in nido is
    ;; nido.session.lifecycle/up!. It accepts a session.edn-shaped map
    ;; that includes :project, :session, :services, and (new) :owned-by-run.
    ;; The coordinator does not invent a new services list — it reuses
    ;; the project's standard session template. We seed :owned-by-run
    ;; so the launcher hook from Step 1 picks it up.
    (session-lifecycle/up! {:project       project
                            :session       session-name
                            :owned-by-run  id})))
```

- [ ] **Step 3: Note for the engineer**

The actual signature of `nido.session.lifecycle/up!` may take positional args (e.g. `(up! project session opts)`) rather than a single map. **Verify by reading `src/nido/session/lifecycle.clj:257`** before writing this. Match the existing convention; the design contract is just "bring a session up with `:owned-by-run` recorded in its session.edn." The Step 2 snippet above is illustrative — adjust signature to the actual API.

- [ ] **Step 4: Manual smoke**

```bash
# Verify nido still works for normal sessions (no regressions):
bb nido:session:up :project brian smoke-test
bb nido:session:status :project brian smoke-test
bb nido:session:destroy :project brian smoke-test
```

Expected: existing session workflow unchanged. The launcher hook is a no-op when `:owned-by-run` is absent.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): spawn Run-owned sessions; launcher writes shim + run-link"
jj new
```

---

## Task 14: Heartbeat writer

**Files:**
- Create: `src/nido/coordinator/heartbeat.clj`
- Create: `test/nido/coordinator/heartbeat_test.clj`

A trivial namespace that rewrites `~/.nido/coordinator/status.edn` on demand. The main loop ticks it every iteration.

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.heartbeat-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.heartbeat :as hb]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(deftest write!-records-state-with-timestamp
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/coordinator-root (constantly tmp)]
        (cstate/ensure-dirs!)
        (hb/write! {:status :running :slots-in-use 0})
        (let [m (io/read-edn (str (cstate/status-path)))]
          (is (= :running (:status m)))
          (is (= 0 (:slots-in-use m)))
          (is (string? (:heartbeat-at m)))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run test to verify it fails**

```bash
bb nido:test :only nido.coordinator.heartbeat-test
```

Expected: FAIL.

- [ ] **Step 3: Implement**

```clojure
(ns nido.coordinator.heartbeat
  "Write ~/.nido/coordinator/status.edn with a fresh timestamp."
  (:require
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn write! [state]
  (io/write-edn! (str (cstate/status-path))
                 (assoc state :heartbeat-at (str (java.time.Instant/now)))))
```

- [ ] **Step 4: Run tests**

```bash
bb nido:test :only nido.coordinator.heartbeat-test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj desc -m "feat(coordinator): heartbeat status.edn writer"
jj new
```

---

## Task 15: Coordinator main loop

**Files:**
- Create: `src/nido/coordinator/core.clj`

This wires everything together. The loop:
1. Load triggers for every registered project.
2. Drain the queue → list of envelopes.
3. For each envelope, route to a fire request, create a Run.
4. Promote queued Runs to running (within the global cap).
5. For each :running Run: spawn the session, launch the agent, await exit, derive state.
6. Write heartbeat.
7. Sleep `:poll-ms` and repeat.

No unit tests for this namespace — it's wiring. End-to-end coverage lives in Task 16.

- [ ] **Step 1: Implement**

```clojure
(ns nido.coordinator.core
  "Coordinator main loop. Foreground only in Stage 1a.

   See spec §The coordinator daemon."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.events :as events]
   [nido.coordinator.heartbeat :as heartbeat]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.status-file :as status-file]
   [nido.coordinator.triggers :as triggers]
   [nido.project :as project]))

(def ^:private defaults
  {:poll-ms             1000
   :global-parallel-cap 2
   :system-prompt       "You are running inside a nido auto-triggered session. The user is not present yet. Write artifacts under <session-home>/artifacts/ with stable filenames. Update <session-home>/_run-status.edn at phase transitions with {:phase :awaiting-input | :working | :complete | :error :note <str>}."})

(defn- registered-projects []
  ;; nido.project/list-projects returns a vector of {:name :dir} maps.
  (mapv :name (project/list-projects)))

(defn- load-all-triggers
  "Returns {:brian [triggers] :foo [triggers]}."
  []
  (->> (registered-projects)
       (into {} (map (fn [p] [p (triggers/load-for-project p)])))))

(defn- run-now!
  "Drive a single :queued Run to terminal/awaiting-review state.
   Synchronous in Stage 1a — concurrency is added in Stage 2."
  [run-id]
  (runs/transition! run-id :running)
  (let [run     (runs/read-run run-id)
        sess    (runs/spawn-session-for-run! run)
        worktree (str (fs/path (cstate/run-session-home-link run-id) "worktree"))
        result  (agent/launch! {:run-id        run-id
                                :cwd           worktree
                                :first-message (:first-message run)
                                :system-prompt (:system-prompt defaults)})
        next-state (if (zero? (:exit-code result))
                     (status-file/derive-state-after-exit
                       (status-file/read-status run-id))
                     :failed)]
    ;; persist captured claude session-id
    (let [r (runs/read-run run-id)]
      (runs/write-run! (assoc r :claude-session-id (:claude-session-id result))))
    (runs/transition! run-id next-state)
    (when (= :failed next-state)
      (let [r (runs/read-run run-id)]
        (runs/write-run! (assoc r :error {:exit-code (:exit-code result)}))))))

(defn- process-envelope! [envelope triggers-by-project]
  (let [routed (events/route envelope triggers-by-project)]
    (if (:error routed)
      (binding [*err* *err*]
        (println "WARN: dropping envelope —" (pr-str routed)))
      (let [run (runs/create-run! routed
                                  {:fired-at (str (java.time.Instant/now))
                                   :fired-by (System/getenv "USER")})]
        (run-now! (:id run))))))

(defn tick!
  "One iteration of the main loop. Public for testability."
  []
  (let [triggers-by-project (load-all-triggers)
        envelopes           (queue/drain!)]
    (heartbeat/write! {:status :running :slots-in-use 0})
    (doseq [env envelopes]
      (process-envelope! env triggers-by-project))))

(defn run!
  "Start the foreground loop. Blocks until interrupted."
  [& {:keys [poll-ms] :or {poll-ms (:poll-ms defaults)}}]
  (cstate/ensure-dirs!)
  (println "nido coordinator: starting (foreground, poll" poll-ms "ms)")
  (heartbeat/write! {:status :running :slots-in-use 0})
  (loop []
    (tick!)
    (Thread/sleep poll-ms)
    (recur)))
```

- [ ] **Step 2: Manual smoke (no agent yet)**

```bash
# In one terminal:
bb -e "(require 'nido.coordinator.core) (nido.coordinator.core/run! :poll-ms 500)" &
COORD_PID=$!
sleep 1
# In another terminal:
cat ~/.nido/coordinator/status.edn
# Expected: a map with :status :running and a recent :heartbeat-at.
kill $COORD_PID
```

- [ ] **Step 3: Commit**

```bash
jj desc -m "feat(coordinator): main loop wiring (foreground, single-flight Runs)"
jj new
```

---

## Task 16: `bb nido:coordinator:run` and `:status` tasks

**Files:**
- Create: `src/tasks/nido_coordinator.clj`
- Modify: `bb.edn` (register tasks)

- [ ] **Step 1: Write the task entry**

Create `src/tasks/nido_coordinator.clj`:

```clojure
(ns tasks.nido-coordinator
  "Bb task entry points for the coordinator daemon."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.core :as core]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]
   [nido.task-args :as task-args]))

(defn run [& args]
  (let [{:keys [poll-ms]} (task-args/parse args)
        ms (some-> poll-ms parse-long)]
    (if ms
      (core/run! :poll-ms ms)
      (core/run!))))

(defn status [& _args]
  (let [p (cstate/status-path)]
    (if (fs/exists? p)
      (let [s (io/read-edn (str p))]
        (println "Coordinator:" (name (:status s)))
        (println "Heartbeat:  " (:heartbeat-at s))
        (println "Slots:      " (:slots-in-use s))
        (when (:halted-reason s)
          (println "Halted:     " (:halted-reason s))))
      (println "Coordinator: not running (no status.edn)"))))
```

- [ ] **Step 2: Register in bb.edn**

Add to `:requires`:

```clojure
[tasks.nido-coordinator :as nido-coordinator]
```

Add task entries:

```clojure
nido:coordinator:run
{:doc "Run coordinator in foreground (stage 1a). Optional :poll-ms <int>."
 :task (apply nido-coordinator/run *command-line-args*)}

nido:coordinator:status
{:doc "Show coordinator heartbeat + state from status.edn"
 :task (apply nido-coordinator/status *command-line-args*)}
```

- [ ] **Step 3: Manual smoke**

```bash
bb nido:coordinator:run :poll-ms 500 &
sleep 1
bb nido:coordinator:status
# Expected: prints "Coordinator: running" and a recent heartbeat.
kill %1
```

- [ ] **Step 4: Commit**

```bash
jj desc -m "feat(nido): bb nido:coordinator:run and :status tasks"
jj new
```

---

## Task 17: `bb nido:trigger:fire` and `:list` tasks

**Files:**
- Create: `src/tasks/nido_trigger.clj`
- Modify: `bb.edn`

- [ ] **Step 1: Write the task**

Create `src/tasks/nido_trigger.clj`:

```clojure
(ns tasks.nido-trigger
  "Bb task entry points for firing manual triggers and listing trigger
   configs."
  (:require
   [clojure.string :as str]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.triggers :as triggers]
   [nido.task-args :as task-args]))

(defn- positional-trigger-name [args]
  (->> args
       (remove keyword?)
       (remove #(str/starts-with? (str %) "--"))
       first))

(defn- payload-flags->map
  "Convert --key value --key2 value2 into {:key 'value' :key2 'value2'}."
  [args]
  (loop [m {} xs args]
    (let [[a b & rest] xs]
      (cond
        (nil? a) m
        (and (string? a) (str/starts-with? a "--"))
        (recur (assoc m (keyword (subs a 2)) (str b)) rest)
        :else (recur m (rest xs))))))

(defn fire
  "bb nido:trigger:fire :project <p> <trigger-name> --key val --key2 val2"
  [& args]
  (let [{:keys [project]} (task-args/parse args)
        project-kw   (when project (keyword (str project)))
        t-name       (some-> (positional-trigger-name args) str keyword)
        payload      (payload-flags->map (map str args))
        ;; remove non-payload positional args from payload
        payload      (dissoc payload :project)]
    (when-not project-kw (println "Missing :project") (System/exit 2))
    (when-not t-name     (println "Missing trigger name") (System/exit 2))
    (let [ts (triggers/load-for-project project-kw)]
      (when-not (triggers/find-by-name ts t-name)
        (println "No trigger" t-name "for project" project-kw) (System/exit 3))
      (cstate/ensure-dirs!)
      (let [path (queue/enqueue!
                   {:target  {:project project-kw :trigger t-name}
                    :payload payload})]
        (println "queued" path)))))

(defn list-triggers
  "bb nido:trigger:list :project <p>"
  [& args]
  (let [{:keys [project]} (task-args/parse args)
        project-kw (when project (keyword (str project)))]
    (when-not project-kw (println "Missing :project") (System/exit 2))
    (let [ts (triggers/load-for-project project-kw)]
      (if (empty? ts)
        (println "No triggers for project" project-kw)
        (doseq [t ts]
          (println (format "%-30s source=%s skill=%s%s"
                           (name (:name t))
                           (name (-> t :source :type))
                           (name (:skill t))
                           (if (:dry-run? t) " (dry-run)" ""))))))))
```

- [ ] **Step 2: Register in bb.edn**

Add to `:requires`: `[tasks.nido-trigger :as nido-trigger]`

Add tasks:

```clojure
nido:trigger:fire
{:doc "Fire a manual trigger: :project <p> <trigger> --<key> <value> ..."
 :task (apply nido-trigger/fire *command-line-args*)}

nido:trigger:list
{:doc "List configured triggers: :project <p>"
 :task (apply nido-trigger/list-triggers *command-line-args*)}
```

- [ ] **Step 3: Manual smoke**

```bash
mkdir -p ~/.nido/projects/brian
cat > ~/.nido/projects/brian/triggers.edn <<'EOF'
{:triggers [{:name :smoke :source {:type :manual} :skill :echo :payload "msg={{event/msg}}"}]}
EOF
bb nido:trigger:list :project brian
bb nido:trigger:fire :project brian smoke --msg hello
ls ~/.nido/coordinator/queue/
```

Expected: list shows one trigger; fire prints `queued /path/...`; queue dir has one .edn file.

- [ ] **Step 4: Commit**

```bash
jj desc -m "feat(nido): bb nido:trigger:fire and :list tasks"
jj new
```

---

## Task 18: `bb nido:runs:list` and `:show` tasks

**Files:**
- Create: `src/tasks/nido_runs.clj`
- Modify: `bb.edn`

- [ ] **Step 1: Write the task**

Create `src/tasks/nido_runs.clj`:

```clojure
(ns tasks.nido-runs
  "Bb task entry points for inspecting Run records."
  (:require
   [babashka.fs :as fs]
   [clojure.pprint :as pp]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.task-args :as task-args]))

(defn- all-runs []
  (->> (fs/list-dir (cstate/runs-dir))
       (filter fs/directory?)
       (map (comp str fs/file-name))
       sort
       (keep runs/read-run)))

(defn list-runs
  "bb nido:runs:list [:state <kw>] [:trigger <kw>] [:project <kw>]"
  [& args]
  (let [{:keys [state trigger project]} (task-args/parse args)
        filter-fn (every-pred
                    (if state   #(= (keyword (str state))   (:state %))   (constantly true))
                    (if trigger #(= (keyword (str trigger)) (:trigger %)) (constantly true))
                    (if project #(= (keyword (str project)) (:project %)) (constantly true)))]
    (doseq [r (filter filter-fn (all-runs))]
      (println (format "[%-15s] %s · %s · %s"
                       (name (:state r))
                       (name (:project r))
                       (name (:trigger r))
                       (:id r))))))

(defn show
  "bb nido:runs:show <run-id>"
  [& args]
  (let [run-id (->> args (remove keyword?) first str)]
    (if-let [r (runs/read-run run-id)]
      (do
        (pp/pprint r)
        (let [log (cstate/run-agent-log run-id)]
          (when (fs/exists? log)
            (println "\n--- last 50 lines of agent.log ---")
            (->> (slurp (str log)) clojure.string/split-lines (take-last 50)
                 (run! println)))))
      (do (println "No run" run-id) (System/exit 4)))))
```

- [ ] **Step 2: Register in bb.edn**

Add to `:requires`: `[tasks.nido-runs :as nido-runs]`

Add tasks:

```clojure
nido:runs:list
{:doc "List runs (filters: :state, :trigger, :project)"
 :task (apply nido-runs/list-runs *command-line-args*)}

nido:runs:show
{:doc "Show full run.edn + last 50 lines of agent.log: <run-id>"
 :task (apply nido-runs/show *command-line-args*)}
```

- [ ] **Step 3: Manual smoke (deferred to E2E in Task 19)**

This task is exercised by the end-to-end test.

- [ ] **Step 4: Commit**

```bash
jj desc -m "feat(nido): bb nido:runs:list and :show tasks"
jj new
```

---

## Task 19: End-to-end smoke test

**Files:**
- Create: `test/nido/coordinator/e2e_test.clj`

Drives the entire pipeline without a real claude or a real session:
1. Stub `runs/spawn-session-for-run!` to create a fake session-home directory tree.
2. Drop a queue file referencing a configured trigger.
3. Call `core/tick!` once.
4. Assert: a Run was created, its state is `:awaiting-review`, its `claude-session-id` is captured, `agent.log` exists.

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.coordinator.e2e-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.core :as core]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def fake-claude
  (str (fs/canonicalize "resources/test/fake-claude/claude")))

(deftest manual-trigger-end-to-end
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root        (constantly tmp)
                    cstate/coordinator-root (constantly (fs/path tmp "coordinator"))
                    cstate/runs-dir         (constantly (fs/path tmp "runs"))
                    ;; stub project listing
                    nido.project/list-projects (constantly [{:name :brian :dir "/tmp"}])
                    ;; stub session spawn — write a minimal session-home tree so
                    ;; the agent has a worktree to cd into
                    runs/spawn-session-for-run!
                    (fn [run]
                      (let [home    (fs/path tmp "sessions" "brian" (:session-name run))
                            wt      (fs/path home "worktree")
                            link    (cstate/run-session-home-link (:id run))]
                        (fs/create-dirs wt)
                        (fs/create-sym-link link home)
                        {}))
                    ;; force the agent to use the fake claude
                    nido.coordinator.agent/launch!
                    (fn [opts]
                      ;; write a _run-status.edn so derived state is :awaiting-review
                      (let [run-dir (cstate/run-dir (:run-id opts))]
                        (io/write-edn! (str (cstate/run-status-path (:run-id opts)))
                                       {:phase :awaiting-input :note "from fake"})
                        (spit (str (cstate/run-agent-log (:run-id opts)))
                              "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"abc-xyz\"}\n"))
                      {:exit-code 0 :claude-session-id "abc-xyz"})]
        ;; 1. configure a trigger
        (fs/create-dirs (fs/parent (cstate/triggers-path :brian)))
        (io/write-edn! (str (cstate/triggers-path :brian))
                       {:triggers [{:name    :investigate-bug
                                    :source  {:type :manual}
                                    :skill   :investigate-bug
                                    :payload "url={{event/url}}"}]})
        ;; 2. enqueue an envelope
        (cstate/ensure-dirs!)
        (queue/enqueue! {:target  {:project :brian :trigger :investigate-bug}
                         :payload {:url "https://x"}})
        ;; 3. one tick
        (core/tick!)
        ;; 4. assert
        (let [runs-dir (cstate/runs-dir)
              run-dirs (->> (fs/list-dir runs-dir) (filter fs/directory?))]
          (is (= 1 (count run-dirs)) "exactly one Run was created")
          (let [run-id (str (fs/file-name (first run-dirs)))
                run    (runs/read-run run-id)]
            (is (= :awaiting-review (:state run)))
            (is (= "abc-xyz"        (:claude-session-id run)))
            (is (= "/investigate-bug url=https://x" (:first-message run)))
            (is (fs/exists? (cstate/run-agent-log run-id))))))
      (finally (fs/delete-tree tmp)))))
```

- [ ] **Step 2: Run the test**

```bash
bb nido:test :only nido.coordinator.e2e-test
```

Expected: PASS.

- [ ] **Step 3: Real-binary manual smoke (optional, requires actual claude)**

Skip if claude isn't installed locally. Otherwise:

```bash
# Configure a real trigger pointing at a tiny test skill (or use a stock one).
mkdir -p ~/.nido/projects/brian
cat > ~/.nido/projects/brian/triggers.edn <<'EOF'
{:triggers [{:name :echo-smoke
             :source {:type :manual}
             :skill :echo
             :payload "hello {{event/who}}"}]}
EOF

# Start coordinator
bb nido:coordinator:run :poll-ms 500 &
COORD_PID=$!

# Fire
bb nido:trigger:fire :project brian echo-smoke --who world

# Wait for it to finish, then inspect
sleep 5
bb nido:runs:list
bb nido:runs:show <run-id-from-list>

kill $COORD_PID
```

Expected: a Run appears in `bb nido:runs:list` with state `:awaiting-review` (or `:done`); `bb nido:runs:show` displays a populated `run.edn` and the tail of `agent.log`.

- [ ] **Step 4: Commit**

```bash
jj desc -m "test(coordinator): end-to-end pipeline smoke test"
jj new
```

---

## Task 20: Documentation

**Files:**
- Create: `docs/skill-conventions-for-triggers.md`
- Modify: `CLAUDE.md` (add a short pointer to the coordination layer)

- [ ] **Step 1: Write the skill-author guide**

Create `docs/skill-conventions-for-triggers.md`:

```markdown
# Skill conventions for use as auto-trigger targets

When a nido trigger fires, it launches a claude session with `/<skill> <payload>` as the first message. Most skills work as-is. Skills written with auto-triggering in mind follow three light conventions so the Run lifecycle reflects what the skill is doing.

These are conventions, not enforced contracts. See the [coordination layer design](superpowers/specs/2026-05-13-nido-coordination-layer-design.md) for the full picture.

## 1. Artifacts go in `<session-home>/artifacts/`

Write meaningful intermediate outputs as files under `artifacts/` with stable names:

- `artifacts/analysis.md`
- `artifacts/proposal.md`
- `artifacts/plan.md`

Stable filenames let the Run overview surface them and let the user `cat artifacts/proposal.md` without searching.

## 2. Status file at `<session-home>/_run-status.edn`

Update this file at phase transitions:

```edn
{:phase :awaiting-input
 :note "Analysis written; does this match your read of the ticket?"
 :artifact "artifacts/analysis.md"}
```

Phases:
- `:investigating` — gathering context (ongoing — does not change Run state)
- `:working` — making changes (ongoing)
- `:awaiting-input` — yield to user → Run state becomes `:awaiting-review`
- `:complete` — done → Run state becomes `:done`
- `:error` — gave up → Run state becomes `:failed`

The daemon reads this file when the agent exits to decide the Run's terminal state.

## 3. Idempotency

If the skill is re-invoked in a session-home that already has artifacts (e.g., the user re-fired the trigger, or the daemon restarted), read existing artifacts and resume — don't blindly overwrite.
```

- [ ] **Step 2: Add a pointer in `CLAUDE.md`**

Find the existing "Delegation" section near the bottom of `CLAUDE.md` and add a new section just before it:

```markdown
## Coordination layer (stage 1a)

A foreground nido coordinator (`bb nido:coordinator:run`) watches `~/.nido/coordinator/queue/` for manual-trigger envelopes and spawns Run-owned sessions that auto-launch claude with a configured skill. Triggers live at `~/.nido/projects/<project>/triggers.edn`.

Fire a Run: `bb nido:trigger:fire :project brian <trigger-name> --<key> <value>`
Inspect Runs: `bb nido:runs:list` and `bb nido:runs:show <run-id>`.

Full design: `docs/superpowers/specs/2026-05-13-nido-coordination-layer-design.md`. Skill conventions for trigger targets: `docs/skill-conventions-for-triggers.md`.

The coordinator is foreground-only at stage 1a — bring it up explicitly when you want autonomous behavior, kill it with `Ctrl-C` when you don't.
```

- [ ] **Step 3: Commit**

```bash
jj desc -m "docs(nido): skill-trigger conventions + CLAUDE.md coordination pointer"
jj new
```

---

## Closing checklist

- [ ] All 20 tasks committed as discrete jj changes.
- [ ] `bb nido:test` passes from a clean working copy.
- [ ] `bb nido:help` shows the new tasks.
- [ ] Manual smoke from Task 19 Step 3 succeeds end-to-end (if claude is installed).
- [ ] The spec's open question on `bb nido:trigger:validate` is captured as a follow-up issue (or deferred to Stage 2).

---

## Spec coverage map

| Spec section | Task(s) |
|---|---|
| Architecture / layered model | covered by entire plan |
| Glossary | enforced via Malli schemas in Tasks 2, 4 |
| Staged rollout | this plan = Stage 1a; later stages are separate plans |
| Safety brakes | deferred to Stage 2 (out of scope here, called out at top) |
| Coordinator daemon — filesystem-canonical | Tasks 7, 14, 15 |
| Triggers — config, validation, payload templating | Tasks 4, 5 |
| Event sources — plugin contract + :manual | Tasks 6, 7 |
| Future-source sketches | designed-in via `events/route` `:broadcast` stub (Task 6) |
| Skills as auto-trigger targets — conventions | Task 20 |
| Runs — schema, lifecycle, retention | Tasks 2, 3, 8 (retention deferred — separate plan) |
| Run storage layout | Tasks 1, 8 |
| Agent launch — headless claude, stream-json | Task 12 |
| Resume from session-home — shim + run-link | Tasks 9, 13 |
| Crash handling — daemon reconciles non-terminal Runs on boot | deferred (Stage 2/3 — needs background daemon first) |
| Multi-agent vendor support | designed-in via `:agent` field; only `:claude` implemented |
| Run overview TUI surface | Stage 1b (next plan) |
| Directory layout summary | Task 1 |
| CLI surface (additions) | Tasks 16, 17, 18 (subset; halt/resume/install in Stage 2/4) |
