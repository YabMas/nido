# Workstream Core (`nido.work`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This is a **jj** repo — the `jujutsu` skill MUST be active before any VCS step.

**Goal:** Build `nido.work` — the single work-plane vocabulary that the TUI and (later) the web both wrap — presenting the unified workstream model over today's storage with no migration.

**Architecture:** `nido.work` sits *above* the coordinator record layer (`nido.coordinator.workstream` / `.session` / `.workstreams-view` / `.promote` / `.scratch` / `.tickets`). It reuses those proven readers/writers but presents the **one coherent model** from the design: a single stage spine (`intake → triage → ready → in-progress → done`), scratch folded in at `in-progress`, and "runs" presented as autonomous sessions on an autonomy axis. Surfaces render rows and route keypresses; all model logic lives here. This plan ships the core **as a projection** — `nido.coordinator.workstreams-view` is left untouched and still serves the current TUI until Plan B rewires it.

**Tech Stack:** Babashka / Clojure, `clojure.test`, malli (existing record schemas), the `with-redefs [cstate/nido-root …]` temp-dir fixture pattern already used in `test/nido/coordinator/workstreams_view_test.clj`.

**Scope note:** This is Plan A of sub-project 1. Plan B (rebuild the TUI as a thin `nido.work` client + system surface) is written after this lands, against the API this plan defines. Design: `docs/superpowers/specs/2026-06-16-coherent-workstream-core-and-thin-surfaces-design.md`.

---

## File Structure

- **Create `src/nido/work.clj`** — the work-plane core. One namespace, the entire work vocabulary. Public API (every signature locked here so later tasks stay consistent):

  ```clojure
  (def stages [:intake :triage :ready :in-progress :done])  ; canonical spine order

  (defn classify-origin [ws] …)                 ; raw ws record → :notion :github :slack :scratch
  (defn list-workstreams                          ; enriched rows on the single spine
    ([project] …) ([project live-names] …))       ; → [{:ws-id :project :origin :stage :needs-you
                                                  ;     :engagement :priority :session-count
                                                  ;     :last-activity :label :br-id :promote-id} …]
  (defn grouped                                   ; spine groups for the board
    ([project] …) ([project live-names] …))       ; → {:triage {:in-flight [..] :queued [..]}
                                                  ;     :ready [..] :in-progress [..]}
  (defn workstream [project ws-id] …)             ; full detail: {:id :project :origin :stage :label
                                                  ;   :ledger {:key :status :report-count}
                                                  ;   :sessions [{:name :autonomy-level :parked?
                                                  ;               :status :brakes} …]}
  (defn default-target [project action] …)        ; action ∈ #{:promote :new} → stage kw
  (defn set-stage! [project ws-id target] …)      ; the one mutation → {:decision <kw>}
  (defn new! [project session-name] …)            ; birth scratch ws + bring its session up → ws-id
  (defn open-target [project ws-id] …)            ; where `open` lands → {:project :session} | nil
  ```

- **Create `test/nido/work_test.clj`** — unit tests, temp-dir fixture, real `workstream.edn`/`session.edn` on disk.

No other files are modified in Plan A.

---

## Task 1: Namespace + spine vocabulary (`stages`, `classify-origin`, spine remap)

**Files:**
- Create: `src/nido/work.clj`
- Test: `test/nido/work_test.clj`

The spine remap is the heart of the unification: a wsv row's `:source` becomes `:origin`, and a **scratch** row's stage is forced to `:in-progress` (it entered the spine there) — unless it's settled, in which case `:done`.

- [ ] **Step 1: Write the failing test**

```clojure
(ns nido.work-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as workstream]
   [nido.work :as work]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest stages-is-the-canonical-spine
  (is (= [:intake :triage :ready :in-progress :done] work/stages)))

(deftest classify-origin-delegates-to-source-classifier
  (is (= :scratch (work/classify-origin {:stage :scratch :external-refs []})))
  (is (= :notion  (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :notion :id "BR-1"}]})))
  (is (= :github  (work/classify-origin {:stage :ready
                                         :external-refs [{:adapter :github-issue :id "o/r#1"}]})))
  (is (= :slack   (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :slack-message :id "slack-C1-1.0"}]}))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — `nido.work` namespace does not exist / unresolved `work/stages`.

- [ ] **Step 3: Write minimal implementation**

```clojure
(ns nido.work
  "The work-plane core: the single vocabulary every surface (TUI, web) wraps.

   Sits ABOVE the coordinator record layer (nido.coordinator.workstream/.session/
   .workstreams-view/.promote/.scratch/.tickets) and presents the ONE coherent
   model from docs/superpowers/specs/2026-06-16-coherent-workstream-core-and-thin-
   surfaces-design.md: a single stage spine (intake→triage→ready→in-progress→done),
   scratch folded in at :in-progress, and runs presented as autonomous sessions.
   Surfaces render + route; all model logic lives here. Ships as a projection over
   today's storage — no migration."
  (:require
   [nido.coordinator.workstreams-view :as wsv]))

(def stages
  "The canonical spine, in order. A PR merge is the event that advances
   in-progress→done; it is not a stage of its own."
  [:intake :triage :ready :in-progress :done])

(defn classify-origin
  "Origin of a workstream from its RAW record: :notion :github :slack :scratch.
   Delegates to the battle-tested source classifier (ref-less-but-autonomous
   workstreams are NOT scratch — scratch is keyed on the :scratch stage marker)."
  [ws]
  (wsv/ws-source ws))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.work`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): scaffold work-plane core ns with spine + origin classifier

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `list-workstreams` — enriched rows on the single spine

**Files:**
- Modify: `src/nido/work.clj`
- Test: `test/nido/work_test.clj`

Reuses `wsv/workstream-rows` (which already projects engagement, stage, priority, label, br-id, promote-id) and applies the spine remap: rename `:source → :origin`, force scratch → `:in-progress`, force settled → `:done`.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest list-workstreams-folds-scratch-into-in-progress
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "poke" :weight :light :autonomy nil}))
      (let [row (first (work/list-workstreams :brian #{"poke"}))]
        (is (= :scratch (:origin row)) "origin preserved")
        (is (= :in-progress (:stage row)) "scratch enters the spine at in-progress")
        (is (nil? (:source row)) ":source is renamed to :origin")))))

(deftest list-workstreams-settled-scratch-reads-done
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "poke" :weight :light :autonomy nil})
        (workstream/close! :brian (:id w) :done))
      (is (= :done (:stage (first (work/list-workstreams :brian))))
          "a closed scratch workstream is :done, not :in-progress"))))

(deftest list-workstreams-preserves-ref-stage
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9" :title "t"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (tickets/set-status! :brian "BR-9" :triaged)
        (session/create! :brian (:id w) {:name "s" :weight :light :autonomy nil}))
      (let [row (first (work/list-workstreams :brian))]
        (is (= :notion (:origin row)))
        (is (= :ready (:stage row)) "a triaged notion ticket projects to :ready, unchanged")))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — unresolved `work/list-workstreams`.

- [ ] **Step 3: Write minimal implementation**

Append to `src/nido/work.clj`:

```clojure
(defn- to-spine
  "Project one wsv row onto the single spine: rename :source→:origin, fold a
   scratch workstream to :in-progress, and a settled (closed) one to :done."
  [row]
  (let [origin (:source row)
        stage  (cond
                 (= :settled (:engagement row)) :done
                 (= :scratch origin)            :in-progress
                 :else                          (:stage row))]
    (-> row
        (assoc :origin origin :stage stage)
        (dissoc :source))))

(defn list-workstreams
  "All of a project's workstreams as enriched rows on the single spine. `live-names`
   (optional set of session names actually holding ports) is threaded into the
   engagement projection — pass it so a downed one-off reads idle."
  ([project] (list-workstreams project nil))
  ([project live-names]
   (mapv to-spine (wsv/workstream-rows project live-names))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.work`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): list-workstreams projects rows onto the single spine

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `grouped` — spine groups for the board

**Files:**
- Modify: `src/nido/work.clj`
- Test: `test/nido/work_test.clj`

The board groups along the spine. `wsv/grouped-by-stage` already groups by `:stage`, splits triage into `:in-flight`/`:queued`, and drops `:done`. Because `list-workstreams` has already remapped scratch→`:in-progress`, scratch rows simply land in the `:in-progress` group — no separate engagement grouping. This is the line that retires the two-state-machine fork.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest grouped-folds-scratch-into-in-progress-group
  (with-tmp
    (fn [_]
      ;; a scratch one-off
      (let [s (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id s) {:name "poke" :weight :light :autonomy nil}))
      ;; a triaged notion ticket → :ready
      (let [n (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-3" :title "t"}]})]
        (tickets/open! :brian "BR-3" {:title "t"})
        (tickets/set-status! :brian "BR-3" :triaged)
        (session/create! :brian (:id n) {:name "s" :weight :light :autonomy nil}))
      (let [g (work/grouped :brian #{"poke"})]
        (is (= 1 (count (:ready g))) "the triaged notion ticket is in :ready")
        (is (= 1 (count (:in-progress g))) "the scratch one-off folds into :in-progress")
        (is (= "BR-3 · t" (:label (first (:ready g)))))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — unresolved `work/grouped`.

- [ ] **Step 3: Write minimal implementation**

Add `nido.coordinator.workstreams-view` is already required. Append:

```clojure
(defn grouped
  "Workstreams grouped along the single spine for the board:
   {:triage {:in-flight [..] :queued [..]} :ready [..] :in-progress [..]}.
   Scratch one-offs fold into :in-progress (done via list-workstreams' remap);
   :done is omitted. The board renders these groups directly."
  ([project] (grouped project nil))
  ([project live-names]
   (wsv/grouped-by-stage (list-workstreams project live-names))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.work`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): grouped folds scratch into the in-progress spine band

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `workstream` — detail with the autonomy axis

**Files:**
- Modify: `src/nido/work.clj`
- Test: `test/nido/work_test.clj`

The detail view presents each session on the **autonomy axis**: `:interactive` (no autonomy facet) vs `:autonomous` (has one), a `:parked?` flag (the HITL gate), a unified `:status`, and `:brakes` (the autonomy `:limits` map) present only on autonomous sessions. This is where "run" finishes dissolving into "autonomous session."

- [ ] **Step 1: Write the failing test**

```clojure
(def ^:private autonomy-running
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {:budget "30m"} :priority 4 :uncapped? false :on-promote nil
   :phase :running :phase-history [{:at "2026-06-01T00:00:00Z" :phase :running}]
   :error nil})

(deftest workstream-detail-presents-sessions-on-the-autonomy-axis
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-5" :title "t"}]})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-running})
        (session/create! :brian (:id w) {:name "me"   :weight :light :autonomy nil})
        (let [d  (work/workstream :brian (:id w))
              by (into {} (map (juxt :name identity)) (:sessions d))]
          (is (= :notion (:origin d)))
          (is (= :autonomous (:autonomy-level (by "auto"))))
          (is (= {:budget "30m"} (:brakes (by "auto"))) "brakes = the autonomy :limits")
          (is (= :running (:status (by "auto"))))
          (is (= :interactive (:autonomy-level (by "me"))))
          (is (nil? (:brakes (by "me"))) "interactive sessions carry no brakes")
          (is (= :up (:status (by "me"))) "a live human session reads :up"))))))

(deftest workstream-detail-flags-the-hitl-gate
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "gate" :weight :heavy
                                         :autonomy (assoc autonomy-running :phase :parked)})
        (is (true? (:parked? (first (:sessions (work/workstream :brian (:id w))))))
            "a parked autonomous session is at the HITL gate")))))

(deftest workstream-detail-nil-for-absent
  (with-tmp
    (fn [_]
      (is (nil? (work/workstream :brian "ws-does-not-exist"))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — unresolved `work/workstream`.

- [ ] **Step 3: Write minimal implementation**

Ensure the `nido.work` ns `:require` includes (adding to what Task 1 wrote):

```clojure
   [nido.coordinator.session :as csession]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as cws]
```

Append:

```clojure
(defn- session-status
  "Unified status across the autonomy axis: an autonomous session reports its
   burst phase; an interactive (human) session reads :up when live, :down when
   archived."
  [s]
  (if (:autonomy s)
    (get-in s [:autonomy :phase])
    (if (csession/live? s) :up :down)))

(defn- session-facet
  "One session on the autonomy axis."
  [s]
  (let [auto (:autonomy s)]
    {:name           (:name s)
     :autonomy-level (if auto :autonomous :interactive)
     :parked?        (csession/parked? s)
     :status         (session-status s)
     :brakes         (when auto (:limits auto))}))

(defn- ledger-summary
  "Light ledger facet for the detail view: its key (BR-#### / slack id), status,
   and report count. nil when `k` is nil (the workstream carries no ledger ref)."
  [project k]
  (when k
    (let [m (tickets/read-meta project k)]
      {:key          k
       :status       (tickets/status project k)
       :report-count (count (:entries m))})))

(defn workstream
  "Full detail for one workstream: origin, spine stage, label, a light ledger
   facet, and its sessions on the autonomy axis. nil when absent. Computes the
   enriched row once (a single project scan) and reuses its :stage + :br-id."
  [project ws-id]
  (when-let [w (cws/read-ws project ws-id)]
    (let [sessions (csession/list-sessions project ws-id)
          row      (->> (list-workstreams project)
                        (some #(when (= ws-id (:ws-id %)) %)))]
      {:id       ws-id
       :project  project
       :origin   (classify-origin w)
       :stage    (:stage row)
       :label    (wsv/label w sessions)
       :ledger   (ledger-summary project (:br-id row))
       :sessions (mapv session-facet sessions)})))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.work`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): workstream detail presents sessions on the autonomy axis

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `default-target` — per-project default + canonical fallback

**Files:**
- Modify: `src/nido/work.clj`
- Test: `test/nido/work_test.clj`

`new`/`promote` use a per-project configured default stage (read from `projects.edn` via `nido.config/read-projects`), falling back to the canonical default `:in-progress`. A configured value is honored only when it names a real spine stage. Project keys in `projects.edn` are symbols, so the lookup normalizes by name.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest default-target-falls-back-to-in-progress
  (with-redefs [nido.config/read-projects (constantly {})]
    (is (= :in-progress (work/default-target :brian :promote)))
    (is (= :in-progress (work/default-target :brian :new)))))

(deftest default-target-honors-configured-stage
  (with-redefs [nido.config/read-projects
                (constantly {'brian {:workstream-defaults {:promote :ready}}})]
    (is (= :ready (work/default-target :brian :promote))
        "configured target wins, project key matched by name")
    (is (= :in-progress (work/default-target :brian :new))
        "unset action falls back to canonical")))

(deftest default-target-rejects-non-stage-config
  (with-redefs [nido.config/read-projects
                (constantly {'brian {:workstream-defaults {:promote :nonsense}}})]
    (is (= :in-progress (work/default-target :brian :promote))
        "a configured value that isn't a spine stage is ignored")))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — unresolved `work/default-target` (and unresolved `nido.config` in the test's with-redefs — add `[nido.config]` to the test ns `:require`).

- [ ] **Step 3: Write minimal implementation**

Add `[nido.config :as config]` to the `nido.work` ns `:require`. Append:

```clojure
(def ^:private canonical-default-target
  "Fallback when a project hasn't configured a default for the action."
  {:promote :in-progress :new :in-progress})

(defn- project-entry
  "projects.edn entry for `project`, tolerating symbol / keyword / string keys."
  [projects project]
  (or (get projects project)
      (get projects (symbol (name project)))
      (get projects (keyword (name project)))
      (get projects (name project))))

(defn default-target
  "Default target stage for a `new`/`promote` gesture in `project`. `action` is
   :promote or :new. A value configured under the project's :workstream-defaults
   is honored only when it names a spine stage; otherwise the canonical default."
  [project action]
  (let [configured (get-in (project-entry (config/read-projects) project)
                           [:workstream-defaults action])]
    (if (some #{configured} stages)
      configured
      (canonical-default-target action))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.work`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): default-target reads per-project config with canonical fallback

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `set-stage!` — the one mutation behind promote / done / advance

**Files:**
- Modify: `src/nido/work.clj`
- Test: `test/nido/work_test.clj`

`new`/`promote`/`done` are all *set-stage* at the surface, but the implementations differ by target: `:in-progress` runs the full promote gesture (gate + provision the planning leg), `:done` closes the workstream, any other spine stage advances the stored stage only (no leg). `set-stage!` is that dispatch.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest set-stage-done-closes-the-workstream
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (= {:decision :done} (work/set-stage! :brian (:id w) :done)))
        (is (some? (:closed (workstream/read-ws :brian (:id w)))))))))

(deftest set-stage-advance-moves-stage-without-a-leg
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (= {:decision :advanced} (work/set-stage! :brian (:id w) :ready)))
        (is (= :ready (:stage (workstream/read-ws :brian (:id w)))))))))

(deftest set-stage-in-progress-runs-the-promote-gesture
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom [])]
        (with-redefs [nido.coordinator.promote/promote-workstream!
                      (fn [p id] (swap! calls conj [p id]) {:decision :promote})]
          (is (= {:decision :promote} (work/set-stage! :brian (:id w) :in-progress)))
          (is (= [[:brian (:id w)]] @calls) "delegates to the shared promote gesture"))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — unresolved `work/set-stage!` (and add `[nido.coordinator.promote]` to the test `:require`).

- [ ] **Step 3: Write minimal implementation**

Add `[nido.coordinator.promote :as promote]` to the `nido.work` ns `:require`. Append:

```clojure
(defn set-stage!
  "Move a workstream to `target` stage — the single mutation behind the
   new/promote/done surface verbs. Dispatch:
     :in-progress → the full promote gesture (gate + provision the planning leg)
     :done        → close the workstream (:done outcome)
     other        → advance the stored stage only (no autonomous leg)
   Returns {:decision <kw>}: promote's decision verbatim, else :done / :advanced."
  [project ws-id target]
  (case target
    :in-progress (promote/promote-workstream! project ws-id)
    :done        (do (cws/close! project ws-id :done) {:decision :done})
    (do (cws/advance-stage! project ws-id target) {:decision :advanced})))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.work`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): set-stage! dispatches promote/done/advance by target

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `new!` + `open-target`

**Files:**
- Modify: `src/nido/work.clj`
- Test: `test/nido/work_test.clj`

`new!` births a scratch workstream and brings its session up — mirroring the proven add path (`lifecycle/up!` then `scratch/birth!`). `open-target` resolves which session `open` lands in: the most-recently-active **live** session, else the most-recently-active session, else nil. `lifecycle/up!` is heavy and is stubbed in the test.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest new!-births-a-scratch-workstream-with-its-session
  (with-tmp
    (fn [_]
      (let [ups (atom [])]
        (with-redefs [nido.session.lifecycle/up! (fn [n opts] (swap! ups conj [n opts]) nil)]
          (let [ws-id (work/new! :brian "spike-thing")]
            (is (string? ws-id))
            (is (= [["spike-thing" {:project "brian"}]] @ups) "session brought up via lifecycle")
            (let [w (workstream/read-ws :brian ws-id)]
              (is (= :scratch (work/classify-origin w)) "ref-less scratch workstream")
              (is (= ["spike-thing"]
                     (map :name (session/list-sessions :brian ws-id))))))))))

(deftest open-target-prefers-the-live-session
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "old"  :weight :light :autonomy nil})
        (session/archive! :brian (:id w) "old")
        (session/create! :brian (:id w) {:name "live" :weight :light :autonomy nil})
        (is (= {:project :brian :session "live"} (work/open-target :brian (:id w)))
            "open lands in the live session, not the archived one")))))

(deftest open-target-nil-when-no-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (nil? (work/open-target :brian (:id w))))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — unresolved `work/new!` / `work/open-target` (add `[nido.session.lifecycle]` to the test `:require`).

- [ ] **Step 3: Write minimal implementation**

Add `[nido.session.lifecycle :as lifecycle]` and `[nido.coordinator.scratch :as scratch]` to the `nido.work` ns `:require`. Append:

```clojure
(defn new!
  "Birth a scratch workstream and bring its session up. Mirrors the proven add
   path: lifecycle/up! creates the worktree + services (it is heavy and slow —
   surfaces wrap this in their own progress UI); scratch/birth! births the
   ref-less workstream + human session. Idempotent on an existing session name.
   Returns the ws-id. `project` may be a keyword or string."
  [project session-name]
  (lifecycle/up! session-name {:project (name project)})
  (scratch/birth! (keyword (name project)) session-name))

(defn open-target
  "Where `open` lands for a workstream: the most-recently-active LIVE session,
   else the most-recently-active session, else nil. Returns {:project :session}.
   Ordering reuses wsv/session-rows (newest-active first)."
  [project ws-id]
  (let [rows       (wsv/session-rows project ws-id)
        live-names (->> (csession/list-sessions project ws-id)
                        (filter csession/live?)
                        (map :name)
                        set)
        pick       (or (first (filter #(live-names (:name %)) rows))
                       (first rows))]
    (when pick {:project project :session (:name pick)})))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.work`
Expected: PASS — full `nido.work` suite green.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): new! births a scratch workstream; open-target resolves entry session

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification

- [ ] **Run the full core suite**

Run: `bb nido:test :only nido.work`
Expected: all tests PASS, 0 failures, 0 errors.

- [ ] **Run the whole test suite to confirm no regressions** (the core only adds a namespace; nothing else changes, but confirm)

Run: `bb nido:test`
Expected: 0 failures, 0 errors.

---

## Self-review notes (for the implementer)

- **Origin stays keyed on the `:scratch` stage marker** (via `wsv/ws-source`), NOT on ref-less-ness — `spawn/ensure-workstream!` mints ref-less *autonomous* workstreams that must classify as `:notion`, not `:scratch`. Do not "simplify" `classify-origin` to `(empty? external-refs)`.
- **The scratch→in-progress remap is display-only.** Storage still writes `:stage :scratch` (via `scratch/birth!`); the core never rewrites it. This is the "projection over storage, no migration" guarantee.
- **`set-stage!` to `:in-progress` is not a pure stage write** — it runs the promote gesture (provisions a session). That asymmetry is intentional and tested.
- **What Plan B will consume:** `list-workstreams`, `grouped`, `workstream`, `default-target`, `set-stage!`, `new!`, `open-target`, `stages`, `classify-origin`. The TUI rewrite calls only these for work. Do not widen the API in Plan A beyond what these tasks define.
- **Known limitation — `new!` always enters at `:in-progress` (not a silent cap).** The design's "new is configurable to jump to a stage" is only partially realized here: `default-target` exposes the `:new` seam, but `new!` itself births a scratch workstream at in-progress and does not honor a non-default target. Reason: origin is keyed on the stored `:stage :scratch` marker, so advancing a fresh scratch ws off `:scratch` would misclassify it as `:notion` (see the origin note above). Targeting an arbitrary stage on `new` is unblocked only once origin is decoupled from stage — a deliberate future storage change, out of scope for the projection-only Plan A. `promote`'s default+override is fully realized (Tasks 5–6).
