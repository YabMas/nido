# Re-hydratable Resume + Visible Outcomes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a parked review resumable from the gate inbox even after its runtime was reclaimed (re-provision the session-home, then `--resume`), and make every resume outcome durable + visible (no more silent hang).

**Architecture:** Concentrated change. `nido.coordinator.resume/run-turn!` probes the session-home and re-provisions via the existing idempotent `runs/spawn-session-for-run!` if it's gone, then records its outcome (`:error` cleared on success / set on failure) on the session record via a new `session/set-error!`. The gate facet (`nido.work`) surfaces `:resume-error`; `nido.ui` shows an immediate "Resuming…" pane fragment on a reply and a persistent error badge on the gate. The pure liveness predicates are untouched (that's Sub-project 2).

**Tech Stack:** Babashka/Clojure, `clojure.test`, `babashka.fs`, jujutsu (jj). Tests: `bb nido:test :only <ns-prefix>`.

**Spec:** `docs/superpowers/specs/2026-06-18-rehydratable-resume-design.md`.

---

## Commit hygiene (read before Task 1)

The working copy `@` holds **uncommitted planning docs** (`docs/superpowers/**`) + untracked/uncommitted stray files (`resources/nido-icon.png`, `resources/Badger - Wikipedia`) that must NOT enter any code commit. Before starting:

```bash
jj log -r '@' --no-graph -T 'change_id.shortest(8) ++ "\n"'   # note the docs-bearing change
jj new                                                         # clean code changeset on top
```

Each task ends with its own `jj commit -m "…"`. After each, verify nothing extra was swept in:

```bash
jj show -s @-   # must list ONLY the task's src/test files — never docs/** or resources/**
```

If a commit ever shows `docs/**` or `resources/**`, `jj squash`/`jj restore` it back out before continuing.

## File Structure

- **`src/nido/coordinator/session.clj`** (modify) — add `set-error!` (sibling of `set-phase!`).
- **`src/nido/coordinator/resume.clj`** (modify) — `run-turn!` gains `home-present?` probe + re-provision + outcome recording; `resume!`'s future simplifies (run-turn! now never throws).
- **`src/nido/work.clj`** (modify) — `->gate` surfaces `:resume-error`; `session-facet` adds `:error`.
- **`src/nido/ui/views.clj`** (modify) — `gate-resuming-fragment` + `gate-card` error badge + `.gate-err` CSS.
- **`src/nido/ui/server.clj`** (modify) — the `:reply` POST returns the resuming pane fragment.
- **Tests:** `test/nido/coordinator/session_test.clj`, `test/nido/coordinator/resume_test.clj`, `test/nido/work_test.clj`, `test/nido/ui/views_test.clj`, `test/nido/ui/server_test.clj`.

---

### Task 1: `session/set-error!`

**Files:**
- Modify: `src/nido/coordinator/session.clj` (add `set-error!` right after `set-phase!`)
- Test: `test/nido/coordinator/session_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/coordinator/session_test.clj` (it already has a `with-tmp`-style fixture and creates sessions; reuse the file's existing helpers — if it defines a fixture under a different name, adapt the two tests to it):

```clojure
(deftest set-error-sets-and-clears-autonomy-error
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy {:skill :triage-bug :first-message "x" :agent :claude
                                     :claude-session-id nil :trigger :triage-bug :limits {}
                                     :priority 0 :uncapped? false :on-promote nil :phase :parked
                                     :phase-history [] :error nil}})
        (session/set-error! :brian (:id w) "auto" {:reason :resume-failed :message "boom"})
        (is (= :resume-failed (-> (first (session/list-sessions :brian (:id w))) :autonomy :error :reason)))
        (session/set-error! :brian (:id w) "auto" nil)
        (is (nil? (-> (first (session/list-sessions :brian (:id w))) :autonomy :error)))))))

(deftest set-error-throws-on-human-session
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "me" :weight :light :autonomy nil})
        (is (thrown? clojure.lang.ExceptionInfo
                     (session/set-error! :brian (:id w) "me" {:reason :x})))))))
```

> If `session_test.clj` doesn't already require `nido.coordinator.workstream`, add `[nido.coordinator.workstream :as workstream]` to its ns. Match the file's existing fixture (it almost certainly redefs `cstate/nido-root` + calls `cstate/ensure-dirs!` like the other coordinator tests).

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.coordinator.session`
Expected: FAIL — `set-error!` is undefined.

- [ ] **Step 3: Implement `set-error!`**

Add to `src/nido/coordinator/session.clj` immediately after `set-phase!` (it reuses the same private `load!`/`write!`/`autonomous?` already in the file):

```clojure
(defn set-error!
  "Set (nil clears) the last-resume error on an autonomous session's autonomy
   facet. Throws if the session has no autonomy facet (a human session has none).
   Returns the updated record."
  [project ws-id session-name err]
  (let [s (load! project ws-id session-name)]
    (when-not (autonomous? s)
      (throw (ex-info "Cannot set error on a human session"
                      {:project project :ws-id ws-id :session session-name})))
    (write! (assoc-in s [:autonomy :error] err))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.coordinator.session`
Expected: PASS (existing + 2 new).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(session): set-error! records a last-resume error on the autonomy facet"
jj show -s @-
```

---

### Task 2: `run-turn!` re-hydrates a reclaimed home + records the outcome

**Files:**
- Modify: `src/nido/coordinator/resume.clj`
- Test: `test/nido/coordinator/resume_test.clj` (append)

- [ ] **Step 1: Write the failing tests**

Append to `test/nido/coordinator/resume_test.clj` (it already defines `with-tmp`, `autonomy-parked`, `write-run!` and requires `agent`, `resume`, `runs`, `session`, `cstate`, `ws`):

```clojure
(deftest run-turn-skips-rehydrate-when-home-present
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})
            spawned (atom 0) launched (atom nil)]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [resume/home-present? (fn [_] true)
                      runs/spawn-session-for-run! (fn [_] (swap! spawned inc))
                      agent/launch! (fn [opts] (reset! launched opts) {:exit-code 0 :num-turns 1})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (is (zero? @spawned) "home present → no re-provision")
        (is (= "sid-9" (:claude-session-id @launched)) "launches --resume")
        (is (= :parked (get-in (first (session/list-sessions :brian (:id w))) [:autonomy :phase])))))))

(deftest run-turn-rehydrates-when-home-absent
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})
            spawned (atom 0) launched (atom nil)]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [resume/home-present? (fn [_] false)
                      runs/spawn-session-for-run! (fn [_] (swap! spawned inc))
                      agent/launch! (fn [opts] (reset! launched opts) {:exit-code 0})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (is (= 1 @spawned) "home absent → re-provision once")
        (is (some? @launched) "then launches --resume")
        (is (= :parked (get-in (first (session/list-sessions :brian (:id w))) [:autonomy :phase])))))))

(deftest run-turn-records-error-on-failure
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [resume/home-present? (fn [_] true)
                      agent/launch! (fn [_] (throw (ex-info "boom" {})))]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (let [auto (:autonomy (first (session/list-sessions :brian (:id w))))]
          (is (= :resume-failed (-> auto :error :reason)) "failure recorded on the session")
          (is (= "boom" (-> auto :error :message)))
          (is (= :parked (:phase auto)) "still re-parks")))))) 

(deftest run-turn-rehydrate-failure-tagged
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [resume/home-present? (fn [_] false)
                      runs/spawn-session-for-run! (fn [_] (throw (ex-info "no branch" {})))
                      agent/launch! (fn [_] {:exit-code 0})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (is (= :rehydrate-failed
               (-> (first (session/list-sessions :brian (:id w))) :autonomy :error :reason))
            "a re-provision failure is tagged :rehydrate-failed")))))

(deftest run-turn-clears-error-on-success
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-parked :error {:reason :resume-failed})})
        (session/set-phase! :brian (:id w) "auto" :running)
        (write-run! "r1" (:id w) "auto" "sid-9")
        (with-redefs [resume/home-present? (fn [_] true)
                      agent/launch! (fn [_] {:exit-code 0})]
          (#'resume/run-turn! :brian (:id w) "auto" (runs/read-run "r1") "apply"))
        (is (nil? (-> (first (session/list-sessions :brian (:id w))) :autonomy :error))
            "a clean turn clears the prior error")))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.coordinator.resume`
Expected: FAIL — `home-present?` undefined; `run-turn!` doesn't re-provision or record errors.

- [ ] **Step 3: Implement**

In `src/nido/coordinator/resume.clj`, add `[babashka.fs :as fs]` and `[nido.coordinator.clock :as clock]` to the `:require`. Replace `run-turn!` and simplify `resume!`'s future:

```clojure
(defn- home-present?
  "Does the run's session-home runtime still exist? (`fs/exists?` follows the
   symlink, so a dangling/reclaimed home reads absent.)"
  [run]
  (fs/exists? (cstate/run-session-home-link (:id run))))

(defn- run-turn!
  "Synchronous body for one resume turn. Re-provisions the session-home first if it
   was reclaimed — the transcript survives, keyed by the home path, so re-provision
   at the same path re-anchors it (runs/spawn-session-for-run! is idempotent). Then
   launches one bounded `claude --resume` turn, records the outcome on the session
   (`:error` cleared on success / set on failure, logged to *err* for operators),
   and re-parks for re-review regardless."
  [project ws-id session-name run input]
  (try
    (when-not (home-present? run)
      (try (runs/spawn-session-for-run! run)
           (catch Throwable t
             (throw (ex-info "Re-hydration failed" {:reason :rehydrate-failed} t)))))
    (agent/launch! {:run-id            (:id run)
                    :cwd               (cstate/run-session-home-link (:id run))
                    :first-message     input
                    :claude-session-id (:claude-session-id run)
                    :resume?           true
                    :budget            (-> run :limits :budget)})
    (session/set-error! project ws-id session-name nil)
    (catch Throwable t
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "nido coordinator: resume turn failed for " session-name
                       " — " (ex-message t))))
      (session/set-error! project ws-id session-name
                          {:at      (clock/now-iso)
                           :reason  (or (:reason (ex-data t)) :resume-failed)
                           :message (ex-message t)}))
    (finally
      (session/set-phase! project ws-id session-name :parked))))
```

And simplify `resume!`'s future (run-turn! now catches internally, so the outer try/catch is dead — drop it):

```clojure
      (session/set-phase! project ws-id (:name s) :running)
      (future (run-turn! project ws-id (:name s) run input))
      {:resumed (:name s)})))
```

(Keep the rest of `resume!` — the `parked-session`/`find-for-session` preconditions — unchanged.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.coordinator.resume`
Expected: PASS (existing Plan-1 tests still green + 5 new).

**IMPORTANT — fix the pre-existing tests that now hit the real re-provision path.** Any Plan-1 `run-turn!` test that calls `#'resume/run-turn!` directly while redefing `agent/launch!` but NOT `home-present?` will, in `with-tmp` (where the run-session-home-link target doesn't exist), now read `home-present?` false and call the **real** `runs/spawn-session-for-run!` (real worktree creation → fails/hangs in the test env). There are two: **`run-turn-launches-resume-and-reparks`** and **`run-turn-reparks-when-launch-throws`**. Add `resume/home-present? (fn [_] true)` to each one's `with-redefs` so they stay on the pure launch path. (Tests that redef `resume/run-turn!` itself — e.g. `resume!-flips-running-and-spawns-turn` — are unaffected.) Make these minimal edits and note them in your report.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(resume): re-hydrate a reclaimed home + record the turn outcome on the session"
jj show -s @-
```

---

### Task 3: facet surfaces `:resume-error`

**Files:**
- Modify: `src/nido/work.clj` (`->gate` + `session-facet`)
- Test: `test/nido/work_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/work_test.clj`:

```clojure
(deftest gates-surface-the-parked-session-resume-error
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-2" :title "t"}]})]
        (tickets/open! :brian "BR-2" {:title "t"})
        (tickets/set-status! :brian "BR-2" :investigating)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked
                                           :error {:reason :resume-failed :message "boom"})})
        (let [g (first (work/gates :brian))]
          (is (= :resume-failed (-> g :resume-error :reason)))
          (is (= "boom" (-> g :resume-error :message))))))))

(deftest gates-resume-error-nil-when-clean
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-3" :title "t"}]})]
        (tickets/open! :brian "BR-3" {:title "t"})
        (tickets/set-status! :brian "BR-3" :investigating)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy :autonomy (assoc autonomy-running :phase :parked)})
        (is (nil? (:resume-error (first (work/gates :brian)))))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.work`
Expected: FAIL — `:resume-error` is absent on the gate.

- [ ] **Step 3: Implement**

In `src/nido/work.clj`, update `->gate` to read the parked session once and surface its error:

```clojure
(defn- ->gate
  "Hydrate one needs-you spine row into a gate."
  [project row]
  (let [parked? (= :parked-at-gate (:engagement row))
        psess   (parked-session project (:ws-id row))]
    {:ws-id        (:ws-id row)
     :project      (name project)
     :origin       (:origin row)
     :stage        (:stage row)
     :label        (:label row)
     :report       (latest-report project (:ws-id row))
     :actions      (gate-actions (:stage row) parked?)
     :session      (:name psess)
     :resume-error (get-in psess [:autonomy :error])}))
```

> Preserve whatever `->gate` currently does for `:project` (it normalizes to a string via `(name project)` — keep that). The only additions are binding `psess` once and the `:resume-error` key; `:session` now reads `(:name psess)` instead of calling `parked-session` again.

Also add `:error` to `session-facet` (the detail view) for parity:

```clojure
(defn- session-facet
  "One session on the autonomy axis."
  [s]
  (let [auto (:autonomy s)]
    {:name           (:name s)
     :autonomy-level (if auto :autonomous :interactive)
     :parked?        (csession/parked? s)
     :status         (session-status s)
     :brakes         (when auto (:limits auto))
     :error          (when auto (:error auto))}))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.work`
Expected: PASS (existing + 2 new). Existing gate tests still pass (`:resume-error` is just an added key; sessions without an error read nil).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(work): gates surface the parked session's resume error"
jj show -s @-
```

---

### Task 4: web — "Resuming…" pane + the error badge

**Files:**
- Modify: `src/nido/ui/views.clj` (`gate-resuming-fragment`, `gate-card` badge, `.gate-err` CSS)
- Modify: `src/nido/ui/server.clj` (the `:reply` POST returns the resuming pane)
- Test: `test/nido/ui/views_test.clj`, `test/nido/ui/server_test.clj` (append)

- [ ] **Step 1: Write the failing tests**

Append to `test/nido/ui/views_test.clj`:

```clojure
(deftest gate-resuming-fragment-targets-the-pane
  (let [html (views/gate-resuming-fragment "brian" "ws-1")]
    (is (str/includes? html "id=\"gate-pane\""))
    (is (str/includes? html "Resuming"))))

(deftest gate-card-shows-resume-error-badge
  (let [g (assoc sample-gate :resume-error {:reason :resume-failed :message "exec failed"})
        html (views/gate-inbox-fragment [g] nil)]
    (is (str/includes? html "resume failed"))
    (is (str/includes? html "exec failed"))))
```

Append to `test/nido/ui/server_test.clj`:

```clojure
(deftest post-gate-reply-returns-resuming-pane
  (with-redefs [nido.work/resolve-gate! (fn [& _] {:resumed "auto"})]
    (let [body (java.io.ByteArrayInputStream. (.getBytes "{\"reply\":\"apply\"}"))
          resp (server/handle-request {:request-method :post :uri "/gate/brian/ws-1/reply" :body body})]
      (Thread/sleep 50)
      (is (str/includes? (:body resp) "Resuming"))
      (is (str/includes? (:body resp) "gate-pane")))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `bb nido:test :only nido.ui`
Expected: FAIL — `gate-resuming-fragment` undefined; the reply POST returns the inbox fragment (no "Resuming"); `gate-card` doesn't render the badge.

- [ ] **Step 3: Implement**

(a) In `src/nido/ui/views.clj`, append to the `layout` `<style>`:

```
        .gate-err { color:#f87171; font-size:11px; margin:4px 0 0 26px; }
```

(b) Add `gate-resuming-fragment` (near the other gate fragments):

```clojure
(defn gate-resuming-fragment
  "Immediate pane feedback after a reply: the resume runs in the background
   (re-hydrating the session if its runtime was reclaimed). Patches the pane."
  [project ws-id]
  (str
   (h/html
    [:div {:id "gate-pane"}
     [:div.breadcrumb project " / gate"]
     [:h1 "Resuming…"]
     [:p.meta "ws " ws-id]
     [:p "Re-hydrating the session if its runtime was reclaimed, then resuming the "
      "conversation. Watch the inbox — this gate will advance, or re-appear with an "
      "error, when the turn finishes."]])))
```

(c) In `gate-card`, destructure `resume-error` and render the badge after the `gate-sub` line:

```clojure
(defn- gate-card
  "One inbox row; links to the gate pane. `sel?` highlights the open gate."
  [{:keys [ws-id project origin stage label report session resume-error]} sel?]
  [:a {:class (str "gate-card" (when sel? " sel"))
       :href  (str "/gate/" project "/" ws-id)}
   [:div.gate-top (origin-badge origin) [:span.lbl label] [:span.needs {:title "needs you"}]]
   [:div.gate-sub [:span project] (chip stage)
    [:span (if session (str "parked · " session) "decide")]]
   [:div.gate-prev (or (some-> report :markdown
                               (str/replace #"^#.*\n+" "")
                               str/split-lines first)
                       "—")]
   (when resume-error
     [:div.gate-err "⚠ resume failed: " (or (:message resume-error)
                                            (name (:reason resume-error)))])])
```

(d) In `src/nido/ui/server.clj`, the gate POST branch: for `:reply`, return the resuming pane (mutations keep returning the inbox fragment):

```clojure
      (and (= 4 (count segs)) (= "gate" (first segs)))
      (let [project   (nth segs 1)
            ws-id     (nth segs 2)
            action-id (keyword (nth segs 3))
            input     (when (= :reply action-id) (:reply (parse-json-body body)))]
        (gate-resolve! project ws-id action-id input)
        (if (= :reply action-id)
          (sse-response (sse-fragment (views/gate-resuming-fragment project ws-id)))
          (sse-response (sse-fragment (views/gate-inbox-fragment (work/all-gates) ws-id)))))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bb nido:test :only nido.ui`
Expected: PASS (existing + 3 new). The pre-existing `post-gate-reply-passes-input-from-body` test (Plan 2) asserts `@calls` only — it still passes since `gate-resolve!` is still called; it doesn't assert the response body, so the changed response doesn't break it.

- [ ] **Step 5: Full suite + commit**

```bash
bb nido:test          # whole suite green
jj commit -m "feat(ui): immediate Resuming pane on reply + resume-failed badge on gates"
jj show -s @-
```

---

## Self-check after all tasks

- [ ] `bb nido:test` — full suite green.
- [ ] `jj log` — 4 contiguous code commits on the `jj new` changeset; **no** `docs/**` or `resources/**` in any (`jj diff -r <first>::@- --name-only` lists only `src/**` + `test/**`).
- [ ] Behaviour: `resume!` re-provisions a reclaimed home before `--resume`; a failed turn records `:error` on the session and re-parks; a successful turn clears it; the gate inbox shows "Resuming…" on a reply and a "⚠ resume failed: …" badge when a session carries an error.

## What this plan deliberately does NOT do (Sub-project 2)

The `:dormant` substrate state, the reconciliation probe that flips stale `:live` records ahead of a click, clean transcript-lost detection, hiding truly-lost gates, and the inbox distinguishing live/dormant/lost before you act. No change to `live?`/`parked?`. No keep-warm pinning.
