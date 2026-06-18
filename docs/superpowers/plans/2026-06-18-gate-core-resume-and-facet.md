# Gate Core (Resume + `nido.work` Facet) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the surface-agnostic *gate* core to `nido.work` — needs-you workstreams hydrated with a report + follow-actions — plus the one new runtime capability it needs: re-engaging a parked agent with the human's input (`claude --resume`).

**Architecture:** Two phases. Phase 1 adds **resume-with-input** in the coordinator (`agent/build-cmd` learns `--resume`; `runs/find-for-session` recovers a parked session's run; new `nido.coordinator.resume/resume!` drives the session phase `:parked → :running → :parked` around one bounded headless turn). Phase 2 adds the **gate facet** to `nido.work` (`gate-actions`/`gates`/`all-gates`/`gate`/`resolve-gate!`) — a projection over today's storage, no migration. The web surface that renders all this is a separate follow-on plan (Plan 2), written against these shapes.

**Tech Stack:** Babashka/Clojure, `clojure.test`, `babashka.process`, jujutsu (jj). Tests run with `bb nido:test :only <ns-prefix>`.

**This plan is the core for** `docs/superpowers/specs/2026-06-18-gate-driven-web-companion-design.md`.

---

## Commit hygiene (read before Task 1)

The working copy `@` currently holds **uncommitted planning docs** (`docs/superpowers/**`) that must NOT enter any code commit. Before starting:

```bash
jj log -r '@' --no-graph -T 'change_id.shortest(8) ++ "\n"'   # note the docs-bearing change
jj new                                                         # start a clean code changeset on top
```

Then each task below ends with its own `jj commit -m "…"`. After each commit, verify no planning artifacts were swept in:

```bash
jj show -s @-   # the just-made commit: must list ONLY the task's src/test files, never docs/superpowers/**
```

If a commit ever shows a `docs/superpowers/` file, `jj squash`/`jj restore` it back out before continuing.

---

## File Structure

- **`src/nido/coordinator/agent.clj`** (modify) — `build-cmd` + `launch!` learn `:resume?` (use `--resume <id>` instead of `--session-id <id>`).
- **`src/nido/coordinator/runs.clj`** (modify) — add `find-for-session` to recover the Run owning a parked session.
- **`src/nido/coordinator/resume.clj`** (create) — `resume!`: orchestrate one bounded resume turn, driving session phase.
- **`src/nido/work.clj`** (modify) — add the gate facet: `gate-actions`, `gates`, `all-gates`, `gate`, `resolve-gate!` + private `latest-report`/`parked-session`/`first-heading`.
- **`test/nido/coordinator/agent_test.clj`** (create or append) — `build-cmd` resume/non-resume.
- **`test/nido/coordinator/runs_test.clj`** (create or append) — `find-for-session`.
- **`test/nido/coordinator/resume_test.clj`** (create) — `resume!` orchestration + preconditions.
- **`test/nido/work_test.clj`** (append) — gate-actions/gates/all-gates/resolve-gate!.

---

# Phase 1 — Resume capability

### Task 1: `build-cmd` / `launch!` learn `--resume`

**Files:**
- Modify: `src/nido/coordinator/agent.clj` (`build-cmd` ~38-53, `launch!` arg-map ~81-85)
- Test: `test/nido/coordinator/agent_test.clj` (create if absent)

- [ ] **Step 1: Write the failing test**

In `test/nido/coordinator/agent_test.clj`:

```clojure
(ns nido.coordinator.agent-test
  (:require [clojure.test :refer [deftest is]]
            [nido.coordinator.agent]))

(def ^:private build-cmd #'nido.coordinator.agent/build-cmd)

(deftest build-cmd-resume-uses-resume-flag
  (is (= ["claude" "--print" "--verbose" "--output-format=stream-json"
          "--dangerously-skip-permissions" "--resume" "sid-1" "hi"]
         (build-cmd {:claude-bin "claude" :first-message "hi"
                     :claude-session-id "sid-1" :resume? true}))
      "resume? routes the recorded id through --resume"))

(deftest build-cmd-without-resume-uses-session-id
  (is (= ["claude" "--print" "--verbose" "--output-format=stream-json"
          "--dangerously-skip-permissions" "--session-id" "sid-1" "hi"]
         (build-cmd {:claude-bin "claude" :first-message "hi"
                     :claude-session-id "sid-1"}))
      "the original burst still records under --session-id"))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bb nido:test :only nido.coordinator.agent`
Expected: FAIL — `build-cmd-resume-uses-resume-flag` gets `--session-id` (current code ignores `:resume?`).

- [ ] **Step 3: Implement — split the session-id branch on `:resume?`**

Replace `build-cmd` in `src/nido/coordinator/agent.clj`:

```clojure
(defn- build-cmd
  "Assemble the claude command vector. With :claude-session-id, the run is
   addressed by id: :resume? true CONTINUES that transcript (--resume) — a
   gate reply; otherwise it RECORDS under it (--session-id) — the first burst.
   first-message is the trailing positional argument."
  [{:keys [claude-bin first-message system-prompt claude-session-id resume?]}]
  (cond-> [claude-bin
           "--print"
           "--verbose"
           "--output-format=stream-json"
           "--dangerously-skip-permissions"]
    (and claude-session-id resume?)       (into ["--resume" claude-session-id])
    (and claude-session-id (not resume?)) (into ["--session-id" claude-session-id])
    system-prompt                          (into ["--append-system-prompt" system-prompt])
    :always                                (conj first-message)))
```

- [ ] **Step 4: Thread `:resume?` through `launch!`**

In `launch!`, add `resume?` to the destructure and pass it to `build-cmd`:

```clojure
  [{:keys [run-id cwd first-message system-prompt claude-bin env budget claude-session-id resume?]
    :or   {claude-bin "claude"}}]
  (let [log-path  (cstate/run-agent-log run-id)
        cmd       (build-cmd {:claude-bin claude-bin :first-message first-message
                              :system-prompt system-prompt :claude-session-id claude-session-id
                              :resume? resume?})
```

(Leave the rest of `launch!` unchanged — the budget timer, log streaming, and `:claude-session-id (or claude-session-id @session)` return all work as-is for a resume turn.)

- [ ] **Step 5: Run tests to verify they pass**

Run: `bb nido:test :only nido.coordinator.agent`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat(agent): build-cmd/launch! learn --resume for gate replies"
jj show -s @-   # verify: only src/nido/coordinator/agent.clj + test/nido/coordinator/agent_test.clj
```

---

### Task 2: `runs/find-for-session` recovers a parked session's Run

**Files:**
- Modify: `src/nido/coordinator/runs.clj` (add after `in-progress-count-by-trigger`, ~113)
- Test: `test/nido/coordinator/runs_test.clj` (create if absent)

- [ ] **Step 1: Write the failing test**

In `test/nido/coordinator/runs_test.clj`:

```clojure
(ns nido.coordinator.runs-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [nido.coordinator.runs :as runs]
            [nido.coordinator.state :as cstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(defn- write-run! [overrides]
  (let [r (merge {:id "r1" :project :brian :trigger :triage-bug
                  :source {:type :manual} :event-payload {} :skill :triage-bug
                  :first-message "/triage-bug" :agent :claude :session-name "s"
                  :workstream-id "ws-1" :claude-session-id "sid-1"
                  :limits {:budget "30m"} :priority 0 :session-profile :full
                  :uncapped? false :state :awaiting-review
                  :state-history [{:at "2026-06-18T00:00:00Z" :state :queued}]
                  :artifacts [] :error nil}
                 overrides)]
    (fs/create-dirs (cstate/run-dir (:id r)))
    (runs/write-run! r)))

(deftest find-for-session-returns-newest-matching
  (with-tmp
    (fn []
      (write-run! {:id "r-old" :workstream-id "ws-1" :session-name "s"
                   :claude-session-id "old"
                   :state-history [{:at "2026-06-18T00:00:00Z" :state :queued}]})
      (write-run! {:id "r-new" :workstream-id "ws-1" :session-name "s"
                   :claude-session-id "new"
                   :state-history [{:at "2026-06-18T01:00:00Z" :state :queued}]})
      (write-run! {:id "r-other" :workstream-id "ws-1" :session-name "other"
                   :claude-session-id "x"})
      (is (= "new" (:claude-session-id (runs/find-for-session :brian "ws-1" "s")))
          "newest run owning (ws-1, s) wins; the (ws-1, other) run is excluded"))))

(deftest find-for-session-nil-when-none
  (with-tmp
    (fn []
      (is (nil? (runs/find-for-session :brian "ws-missing" "s"))))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bb nido:test :only nido.coordinator.runs`
Expected: FAIL — `find-for-session` is undefined.

- [ ] **Step 3: Implement `find-for-session`**

Add to `src/nido/coordinator/runs.clj`:

```clojure
(defn find-for-session
  "The newest Run owning (`ws-id`, `session-name`) in `project`, or nil. Lets the
   resume path recover a parked session's resumable :claude-session-id + run-id
   (the real id lives in run.edn, not on the session autonomy)."
  [project ws-id session-name]
  (->> (list-run-ids)
       (keep read-run)
       (filter #(and (= (:project %) project)
                     (= (:workstream-id %) ws-id)
                     (= (:session-name %) session-name)))
       (sort-by #(-> % :state-history last :at))
       last))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.coordinator.runs`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(runs): find-for-session recovers the run owning a parked session"
jj show -s @-
```

---

### Task 3: `nido.coordinator.resume/resume!`

**Files:**
- Create: `src/nido/coordinator/resume.clj`
- Test: `test/nido/coordinator/resume_test.clj`

- [ ] **Step 1: Write the failing test**

In `test/nido/coordinator/resume_test.clj`:

```clojure
(ns nido.coordinator.resume-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [nido.coordinator.agent :as agent]
            [nido.coordinator.resume :as resume]
            [nido.coordinator.runs :as runs]
            [nido.coordinator.session :as session]
            [nido.coordinator.state :as cstate]
            [nido.coordinator.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(def ^:private autonomy-parked
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {:budget "30m"} :priority 4 :uncapped? false
   :on-promote nil :phase :parked
   :phase-history [{:at "2026-06-18T00:00:00Z" :phase :parked}] :error nil})

(defn- write-run! [id ws-id sname sid]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :triage-bug
                    :source {:type :manual} :event-payload {} :skill :triage-bug
                    :first-message "/triage-bug" :agent :claude :session-name sname
                    :workstream-id ws-id :claude-session-id sid
                    :limits {:budget "30m"} :priority 0 :session-profile :full
                    :uncapped? false :state :awaiting-review
                    :state-history [{:at "2026-06-18T00:00:00Z" :state :queued}]
                    :artifacts [] :error nil}))

;; run-turn! is synchronous → unit-test the actual launch + re-park here.
(deftest run-turn-launches-resume-and-reparks
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})
            calls (atom nil)]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [agent/launch! (fn [opts] (reset! calls opts) {:exit-code 0 :num-turns 1})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "do the fix"))
        (is (= "sid-9" (:claude-session-id @calls)))
        (is (true? (:resume? @calls)) "continues the recorded conversation")
        (is (= "do the fix" (:first-message @calls)))
        (is (= "30m" (:budget @calls)) "the run's budget bounds the turn")
        (is (= :parked (get-in (first (session/list-sessions :brian (:id w)))
                               [:autonomy :phase]))
            "the turn re-parks the session for re-review")))))

(deftest resume!-flips-running-and-spawns-turn
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})
            spawned (atom nil)]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [resume/run-turn! (fn [& args] (reset! spawned (vec args)))]
          (is (= {:resumed "auto"} (resume/resume! :brian (:id w) "go"))))
        ;; resume! sets :running synchronously before handing off to the turn.
        (is (= :running (get-in (first (session/list-sessions :brian (:id w)))
                                [:autonomy :phase])))))))

(deftest resume!-throws-when-not-parked
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (resume/resume! :brian (:id w) "go"))
            "no parked session → no resume target")))))

(deftest resume!-throws-when-no-claude-session-id
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy :autonomy autonomy-parked})
        ;; no run on disk → no recoverable claude-session-id
        (is (thrown? clojure.lang.ExceptionInfo
                     (resume/resume! :brian (:id w) "go")))))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bb nido:test :only nido.coordinator.resume`
Expected: FAIL — `nido.coordinator.resume` namespace does not exist.

- [ ] **Step 3: Implement `resume.clj`**

Create `src/nido/coordinator/resume.clj`:

```clojure
(ns nido.coordinator.resume
  "Re-engage a PARKED autonomous session with the human's input by relaunching
   its recorded claude conversation as one bounded headless turn
   (`claude --resume <id> -p \"<input>\"`). The session phase is driven
   :parked → :running → :parked directly (the gate inbox reads the session phase,
   not a Run state — so this works whether the owning Run is :awaiting-review or
   already terminal). This is the :reply resolver behind nido.work/resolve-gate!."
  (:require
   [nido.coordinator.agent :as agent]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]))

(defn- parked-session
  "The (first) parked autonomous session under a workstream, or nil."
  [project ws-id]
  (->> (session/list-sessions project ws-id)
       (filter session/parked?)
       first))

(defn- run-turn!
  "Synchronous body: launch one resume turn for `run`, then re-park `session-name`
   for re-review regardless of outcome (the agent itself writes the next report
   entry; a failure leaves the agent.log under the run-dir). Claude runs with the
   session-home as cwd so `--resume` resolves the transcript (per run-blocking!)."
  [project ws-id session-name run input]
  (try
    (agent/launch! {:run-id            (:id run)
                    :cwd               (cstate/run-session-home-link (:id run))
                    :first-message     input
                    :claude-session-id (:claude-session-id run)
                    :resume?           true
                    :budget            (-> run :limits :budget)})
    (finally
      (session/set-phase! project ws-id session-name :parked))))

(defn resume!
  "Re-engage a parked session under `ws-id` with `input`. Flips the session to
   :running synchronously, then runs one resume turn on a background thread.
   Returns {:resumed <session-name>}; throws ex-info (with :reason) when there is
   no parked session (:not-parked) or no recoverable conversation (:no-claude-session)."
  [project ws-id input]
  (let [s (parked-session project ws-id)]
    (when-not s
      (throw (ex-info "No parked session to resume"
                      {:reason :not-parked :project project :ws-id ws-id})))
    (let [run (runs/find-for-session project ws-id (:name s))]
      (when-not (and run (:claude-session-id run))
        (throw (ex-info "No resumable conversation — open the session in the terminal"
                        {:reason :no-claude-session :project project :ws-id ws-id})))
      (session/set-phase! project ws-id (:name s) :running)
      (future (run-turn! project ws-id (:name s) run input))
      {:resumed (:name s)})))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.coordinator.resume`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(resume): resume! re-engages a parked session with human input"
jj show -s @-
```

---

# Phase 2 — Gate facet in `nido.work`

### Task 4: `gate-actions` (pure, stage-derived)

**Files:**
- Modify: `src/nido/work.clj` (add after `stages`, before `classify-origin`)
- Test: `test/nido/work_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/work_test.clj`:

```clojure
(deftest gate-actions-are-stage-derived
  (is (= [{:id :promote :label "Promote" :kind :mutation}
          {:id :skip    :label "Skip"    :kind :mutation}
          {:id :reply   :label "Reply"   :kind :reply}]
         (work/gate-actions :triage true)))
  (is (= [] (work/gate-actions :triage false)) "an unparked triage offers nothing")
  (is (= [{:id :promote :label "Promote" :kind :mutation}
          {:id :drop    :label "Drop"    :kind :mutation}]
         (work/gate-actions :ready false)) "ready always decides, parked or not")
  (is (= [{:id :reply :label "Reply" :kind :reply}
          {:id :done  :label "Done"  :kind :mutation}]
         (work/gate-actions :in-progress true)))
  (is (= [] (work/gate-actions :intake true)))
  (is (= [] (work/gate-actions :done true))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — `gate-actions` is undefined.

- [ ] **Step 3: Implement `gate-actions`**

Add to `src/nido/work.clj` (after the `stages` def):

```clojure
(defn gate-actions
  "Follow-actions for a gate, derived from its spine `stage` (and whether a
   session is `parked?`). `:kind` is a render hint only — :mutation → one-click
   button, :reply → textarea. resolve-gate! dispatches on `:id`."
  [stage parked?]
  (case stage
    :triage      (if parked?
                   [{:id :promote :label "Promote" :kind :mutation}
                    {:id :skip    :label "Skip"    :kind :mutation}
                    {:id :reply   :label "Reply"   :kind :reply}]
                   [])
    :ready       [{:id :promote :label "Promote" :kind :mutation}
                  {:id :drop    :label "Drop"    :kind :mutation}]
    :in-progress (if parked?
                   [{:id :reply :label "Reply" :kind :reply}
                    {:id :done  :label "Done"  :kind :mutation}]
                   [])
    []))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.work`
Expected: PASS (existing + new gate-actions test).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): gate-actions — stage-derived follow-actions"
jj show -s @-
```

---

### Task 5: `gates` + `gate` hydrate needs-you rows with report + actions

**Files:**
- Modify: `src/nido/work.clj` (requires + private helpers + `gates`/`gate`)
- Test: `test/nido/work_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/work_test.clj`:

```clojure
(deftest gates-hydrates-a-parked-triage-workstream
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-7" :title "t"}]})]
        (tickets/open! :brian "BR-7" {:title "t"})
        (tickets/set-status! :brian "BR-7" :investigating)
        (workstream/append-entry! :brian (:id w) {:kind :triage}
                                  "# Verdict\n\nbug — reproduced.")
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (let [g (first (work/gates :brian))]
          (is (= (:id w) (:ws-id g)))
          (is (= :notion (:origin g)))
          (is (= :triage (:stage g)))
          (is (= "auto" (:session g)) "the parked session a :reply would resume")
          (is (= [:promote :skip :reply] (map :id (:actions g))))
          (is (= :triage (-> g :report :kind)))
          (is (= "Verdict" (-> g :report :title)))
          (is (= "# Verdict\n\nbug — reproduced." (-> g :report :markdown))))))))

(deftest gates-excludes-workstreams-that-do-not-need-you
  (with-tmp
    (fn [_]
      ;; triaged + a non-parked session → stage :ready, needs-you TRUE (ready always)
      (let [r (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-8" :title "t"}]})]
        (tickets/open! :brian "BR-8" {:title "t"})
        (tickets/set-status! :brian "BR-8" :triaged)
        (session/create! :brian (:id r) {:name "s" :weight :light :autonomy nil}))
      (let [g (first (work/gates :brian))]
        (is (= :ready (:stage g)))
        (is (= [:promote :drop] (map :id (:actions g))) "ready gate decides promote/drop")
        (is (nil? (:session g)) "no parked session → nothing to reply to")))))

(deftest gate-detail-nil-for-absent
  (with-tmp
    (fn [_]
      (is (nil? (work/gate :brian "ws-nope"))))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — `gates`/`gate` undefined.

- [ ] **Step 3: Add requires + private helpers + `gates`/`gate`**

In `src/nido/work.clj`, extend the `:require` with:

```clojure
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.state :as cstate]
```

Then add (place the helpers above `gates`, after the existing `workstream` detail fn):

```clojure
(defn- parked-session
  "The first parked autonomous session under a workstream, or nil — the session a
   :reply resolves against."
  [project ws-id]
  (->> (csession/list-sessions project ws-id)
       (filter csession/parked?)
       first))

(defn- first-heading
  "The text of the first markdown heading in `md` (e.g. '# Verdict' → \"Verdict\"),
   or nil."
  [md]
  (some->> md
           str/split-lines
           (some #(second (re-matches #"#+\s+(.*)" %)))))

(defn- latest-report
  "The workstream's most recent ledger entry as a gate report
   {:kind :at :title :markdown}, or nil when it has none. Origin-agnostic — reads
   the workstream-level :entries (works for ref-less scratch too)."
  [project ws-id]
  (when-let [w (cws/read-ws project ws-id)]
    (when-let [e (last (:entries w))]
      (let [f  (str (fs/path (cstate/workstream-dir project ws-id) (:file e)))
            md (when (fs/exists? f) (slurp f))]
        {:kind     (:kind e)
         :at       (:at e)
         :title    (first-heading md)
         :markdown md}))))

(defn- ->gate
  "Hydrate one needs-you spine row into a gate."
  [project row]
  (let [parked? (= :parked-at-gate (:engagement row))]
    {:ws-id   (:ws-id row)
     :project project
     :origin  (:origin row)
     :stage   (:stage row)
     :label   (:label row)
     :report  (latest-report project (:ws-id row))
     :actions (gate-actions (:stage row) parked?)
     :session (some-> (parked-session project (:ws-id row)) :name)}))

(defn gates
  "A project's gates: workstreams that want you now (needs-you), each hydrated
   with its report + follow-actions. `live-names` threads into the engagement
   projection (pass it so a downed one-off reads idle)."
  ([project] (gates project nil))
  ([project live-names]
   (->> (list-workstreams project live-names)
        (filter :needs-you)
        (mapv #(->gate project %)))))

(defn gate
  "Full gate detail for one workstream, or nil when it is absent or not a gate."
  [project ws-id]
  (->> (gates project)
       (filter #(= ws-id (:ws-id %)))
       first))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.work`
Expected: PASS (existing + 3 new tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): gates/gate hydrate needs-you rows with report + actions"
jj show -s @-
```

---

### Task 6: `all-gates` across every project

**Files:**
- Modify: `src/nido/work.clj` (add `all-gates`; require `nido.project`)
- Test: `test/nido/work_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/work_test.clj`:

```clojure
(deftest all-gates-merges-across-projects
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (workstream/append-entry! :brian (:id w) {:kind :triage} "# X\n\nrep.")
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)}))
      (with-redefs [nido.project/list-projects
                    (constantly {"brian" {:directory "/tmp/brian"}})]
        (let [gs (work/all-gates)]
          (is (= 1 (count gs)))
          (is (= :brian (:project (first gs))) "project name threads through to each gate"))))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — `all-gates` undefined.

- [ ] **Step 3: Implement `all-gates`**

Add `[nido.project :as project]` to the `nido.work` `:require`, then add:

```clojure
(defn all-gates
  "Gates across every registered project, needs-you/newest-first within each.
   Mirrors the dashboard's cross-project aggregation (see ui.server/all-session-rows).
   A project that can't be read contributes no gates rather than failing the board."
  []
  (->> (project/list-projects)
       (mapcat (fn [[pname _entry]]
                 (try (gates pname)
                      (catch Throwable _ []))))
       vec))
```

> Note: `gates` receives the project key verbatim from `list-projects` (a name string), consistent with how every `nido.work` fn is name-tolerant.

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.work`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): all-gates aggregates gates across projects"
jj show -s @-
```

---

### Task 7: `resolve-gate!` dispatches on action id

**Files:**
- Modify: `src/nido/work.clj` (require `nido.coordinator.resume`; add `resolve-gate!`)
- Test: `test/nido/work_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/work_test.clj` (add `[nido.coordinator.resume]` to the test ns `:require`):

```clojure
(deftest resolve-gate-promote-runs-the-promote-gesture
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom [])]
        (with-redefs [nido.coordinator.promote/promote-workstream!
                      (fn [p id] (swap! calls conj [p id]) {:decision :promote})]
          (is (= {:decision :promote} (work/resolve-gate! :brian (:id w) :promote)))
          (is (= [[:brian (:id w)]] @calls)))))))

(deftest resolve-gate-skip-and-drop-settle-dropped
  (with-tmp
    (fn [_]
      (let [a (workstream/create! :brian {:stage :triaging :external-refs []})
            b (workstream/create! :brian {:stage :triaging :external-refs []})]
        (work/resolve-gate! :brian (:id a) :skip)
        (work/resolve-gate! :brian (:id b) :drop)
        (is (= :dropped (:outcome (:closed (workstream/read-ws :brian (:id a))))))
        (is (= :dropped (:outcome (:closed (workstream/read-ws :brian (:id b))))))))))

(deftest resolve-gate-done-closes-done
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (work/resolve-gate! :brian (:id w) :done)
        (is (= :done (:outcome (:closed (workstream/read-ws :brian (:id w))))))))))

(deftest resolve-gate-reply-delegates-to-resume
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom nil)]
        (with-redefs [nido.coordinator.resume/resume!
                      (fn [p id input] (reset! calls [p id input]) {:resumed "auto"})]
          (is (= {:resumed "auto"} (work/resolve-gate! :brian (:id w) :reply "do it")))
          (is (= [:brian (:id w) "do it"] @calls)))))))
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — `resolve-gate!` undefined.

- [ ] **Step 3: Implement `resolve-gate!`**

Add `[nido.coordinator.resume :as resume]` to the `nido.work` `:require`, then add:

```clojure
(defn resolve-gate!
  "Apply a gate follow-action, dispatching on `action-id`:
     :promote      → set-stage! :in-progress (the promote gesture)
     :skip / :drop → close! :dropped (workstream settled; not pursued)
     :done         → set-stage! :done (close! :done)
     :reply        → resume! the parked agent with `input`
   Returns the resolver's result map."
  ([project ws-id action-id] (resolve-gate! project ws-id action-id nil))
  ([project ws-id action-id input]
   (case action-id
     :promote      (set-stage! project ws-id :in-progress)
     :done         (set-stage! project ws-id :done)
     (:skip :drop) (do (cws/close! project ws-id :dropped) {:decision :dropped})
     :reply        (resume/resume! project ws-id input)
     (throw (ex-info "Unknown gate action" {:action-id action-id :ws-id ws-id})))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.work`
Expected: PASS (all gate-facet tests).

- [ ] **Step 5: Full suite + commit**

```bash
bb nido:test          # whole suite green (no regressions)
jj commit -m "feat(work): resolve-gate! dispatches mutations + reply"
jj show -s @-
```

---

## Self-check after all tasks

- [ ] `bb nido:test` — full suite green.
- [ ] `jj log` — 7 contiguous code commits on the changeset started by the pre-Task-1 `jj new`; **no** `docs/superpowers/**` file in any of them (`jj diff -r <first>::@- --name-only` lists only `src/**` + `test/**`).
- [ ] Public `nido.work` gate API present: `gate-actions`, `gates`, `all-gates`, `gate`, `resolve-gate!`.
- [ ] `nido.coordinator.resume/resume!` + `runs/find-for-session` + `agent` `--resume` in place.

## What this plan deliberately does NOT do (Plan 2)

The **web surface** — the cross-project Gate Inbox, the gate pane (markdown report + action buttons + reply box), the spine Board, read-only detail, route-in, and the new routes/fragments in `nido.ui` — is a separate plan, written against the now-real facet shapes (exactly as Plan B followed Plan A). Markdown rendering library choice is deferred to that plan.
