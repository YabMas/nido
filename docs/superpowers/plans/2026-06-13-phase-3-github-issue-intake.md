# Phase 3: GitHub Issue Intake + View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.
>
> **VCS:** jj (Jujutsu) repo. Activate the `jujutsu` skill; never git. One commit per task via `jj commit`. The working copy carries the planning docs as UNCOMMITTED changes — each implementer must `jj new` off the code tip so the docs stay out of code commits (the controller positions them).
>
> **DAEMON RESTART:** Tasks 1–3 change coordinator code. The running coordinator daemon reads `src/` once at startup — it will NOT pick up the new intake until restarted (`bb nido:coordinator:restart` under launchd, else `down` then `up`). This only matters for the live verification (Task 5); the unit tests load the code fresh each run.

**Goal:** Assigned GitHub issues surface as a manual-promote queue — a reconcile loop polls `gh issue list --assignee @me` and upserts one workstream per issue (born `:ready`, no triage, no session spawn, carrying a `:github-issue` ref), shown in a new GitHub view tab. Promoting them to autonomous work is Phase 4.

**Architecture:** A snapshot-free reconciler `coordinator/github-issue-intake`, modeled on the existing merge poller (`coordinator/github-merge`) and run from the same tick loop. Each poll: idempotent upsert by the `:github-issue` ref (so the first poll surfaces the whole backlog — nothing auto-fires, so the backlog *is* the queue), plus a reverse pass that DROPs a queue entry when its issue is no longer assigned AND the workstream is still unpromoted (stage `:ready`, no sessions). A half-open breaker mirrors the merge poller (auth / ≥3 failures open it; one probe per 30 m cooldown). The GitHub view is one `view-defs` entry — Phase 2's `ws-source` already classifies `:github-issue` refs as `:github`.

**Tech Stack:** Babashka, Clojure, the `gh` CLI (via `nido.github.client`), `clojure.test` (stub `gh/*`, temp nido-root).

**Out of scope (deferred to Phase 4):** the workstream-level promote gesture (pressing `p` on a github-issue workstream today no-ops with "no ticket on this workstream to promote" — acceptable). Fetching the issue *body* — done on-demand at promote time, not stored at intake (keeps the poll lightweight).

---

## File Structure
- **Modify** `src/nido/github/client.clj` — add `list-assigned-issues`.
- **Modify** `src/nido/github/config.clj` — add optional `:issues` to the config schema.
- **Create** `src/nido/coordinator/github_issue_intake.clj` — the reconciler.
- **Modify** `src/nido/coordinator/core.clj` — tick-loop wiring (`maybe-poll-github-issues!`).
- **Modify** `src/nido/tui.clj` — add the GitHub `view-defs` entry.
- **Tests:** `test/nido/github/client_test.clj`, `test/nido/coordinator/github_issue_intake_test.clj` (create), `test/nido/tui_test.clj` (update the view-order test).

---

## Task 1: GitHub client `list-assigned-issues` + config `:issues` schema

**Files:** Modify `src/nido/github/client.clj`, `src/nido/github/config.clj`; tests in `test/nido/github/client_test.clj` (+ `config_test.clj` if present).

- [ ] **Step 1: Write failing tests.** READ `test/nido/github/client_test.clj` first to match its `sh!`-stub style (it stubs `gh/sh!` to return `{:exit :out :err}`). Add:

```clojure
(deftest list-assigned-issues-parses-and-shapes
  (with-redefs [gh/sh! (fn [args]
                         (is (= ["gh" "issue" "list" "--repo" "o/r"
                                 "--assignee" "@me" "--state" "open"
                                 "--json" "number,url,title" "--limit" "100"] args))
                         {:exit 0
                          :out "[{\"number\":42,\"url\":\"u\",\"title\":\"t\"}]"
                          :err ""})]
    (is (= {:status :ok :issues [{:number 42 :url "u" :title "t"}]}
           (gh/list-assigned-issues "o/r" "@me")))))

(deftest list-assigned-issues-flags-auth-errors
  (with-redefs [gh/sh! (fn [_] {:exit 1 :out "" :err "gh auth login required"})]
    (is (= :auth (:error (gh/list-assigned-issues "o/r" "@me"))))))
```

- [ ] **Step 2: Run, verify FAIL.** `bb nido:test :only nido.github.client-test`.

- [ ] **Step 3: Implement.** In `src/nido/github/client.clj`, after `list-merged-prs`, add (reuses the existing private `auth-error?`):

```clojure
(defn list-assigned-issues
  "Open issues in `repo` assigned to `assignee` (e.g. \"@me\"), capped at `limit`
   (default 100). Returns {:status :ok :issues [{:number :url :title}]}
   or {:error :auth|:gh}."
  ([repo assignee] (list-assigned-issues repo assignee 100))
  ([repo assignee limit]
   (let [{:keys [exit out err]}
         (sh! ["gh" "issue" "list" "--repo" repo
               "--assignee" assignee
               "--state" "open"
               "--json" "number,url,title"
               "--limit" (str limit)])]
     (if (zero? exit)
       {:status :ok
        :issues (->> (json/parse-string out true)
                     (mapv (fn [m] {:number (:number m)
                                    :url    (:url m)
                                    :title  (:title m)})))}
       {:error (if (auth-error? err) :auth :gh) :detail (str/trim (or err ""))}))))
```

- [ ] **Step 4: Config schema.** In `src/nido/github/config.clj`, add an optional `:issues` key to the `Config` map (after `:on-merge`):

```clojure
   [:issues {:optional true}
    [:map
     [:assignee {:optional true} string?]
     [:enabled  {:optional true} boolean?]]]
```
Add a config test (or extend the existing one) asserting a config with `{:repo "o/r" :issues {:assignee "@me"}}` validates, and one with an unknown key under `:issues` does not (closed map).

- [ ] **Step 5: Run, verify PASS.** `bb nido:test :only nido.github` (client + config). Then full `bb nido:test`.

- [ ] **Step 6: Commit.**
```bash
jj commit -m "feat(github): list-assigned-issues client + :issues config schema"
```

---

## Task 2: The intake reconciler

**Files:** Create `src/nido/coordinator/github_issue_intake.clj` + `test/nido/coordinator/github_issue_intake_test.clj`.

- [ ] **Step 1: Write failing tests.** Mirror `test/nido/coordinator/github_merge_test.clj`'s `with-tmp` fixture (redef `cstate/nido-root` to a temp dir; `cstate/ensure-dirs!`). Stub `gh/list-assigned-issues`; use REAL workstream ops against the temp root. Create `test/nido/coordinator/github_issue_intake_test.clj`:

```clojure
(ns nido.coordinator.github-issue-intake-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.github-issue-intake :as intake]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.github.client :as gh]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(def ^:private cfg {:repo "o/r" :issues {:assignee "@me"}})

(defn- ids [project]
  (->> (ws/list-ids project) (map #(ws/read-ws project %))
       (keep (fn [w] (some #(when (= :github-issue (:adapter %)) (:id %)) (:external-refs w))))
       set))

(deftest cold-start-creates-a-ready-workstream-per-assigned-issue
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues
                    (fn [_ _] {:status :ok :issues [{:number 1 :url "u1" :title "a"}
                                                    {:number 2 :url "u2" :title "b"}]})]
        (intake/poll-and-reconcile! :brian cfg)
        (is (= #{"o/r#1" "o/r#2"} (ids :brian)))
        (let [w (ws/find-by-ref :brian :github-issue "o/r#1")]
          (is (= :ready (:stage w)))
          (is (= "a" (some #(when (= :github-issue (:adapter %)) (:title %)) (:external-refs w)))))))))

(deftest re-poll-is-idempotent
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues [{:number 1}]})]
        (intake/poll-and-reconcile! :brian cfg)
        (intake/poll-and-reconcile! :brian cfg)
        (is (= 1 (count (ws/list-ids :brian))))))))

(deftest unassigned-unpromoted-issue-is-dropped
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues [{:number 1}]})]
        (intake/poll-and-reconcile! :brian cfg))
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues []})]
        (intake/poll-and-reconcile! :brian cfg))
      (is (empty? (ws/list-ids :brian)) "no longer assigned + unpromoted ⇒ dropped"))))

(deftest unassigned-PROMOTED-issue-is-kept
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues [{:number 1}]})]
        (intake/poll-and-reconcile! :brian cfg))
      (let [ws-id (:id (ws/find-by-ref :brian :github-issue "o/r#1"))]
        (ws/advance-stage! :brian ws-id :in-progress)        ; promoted
        (with-redefs [gh/list-assigned-issues (fn [_ _] {:status :ok :issues []})]
          (intake/poll-and-reconcile! :brian cfg))
        (is (some? (ws/read-ws :brian ws-id)) "promoted ⇒ left alone")))))

(deftest auth-error-trips-the-breaker
  (with-tmp
    (fn [_]
      (with-redefs [gh/list-assigned-issues (fn [_ _] {:error :auth})]
        (intake/poll-and-reconcile! :brian cfg))
      (is (= :open (:breaker (#'intake/read-state-for :brian)))))))
```
(For the last test, expose a small private read helper OR read the state file directly via `sstate`; the implementer picks one and the test matches it. The key assertion: after an `:auth` error, the persisted state has `:breaker :open`.)

- [ ] **Step 2: Run, verify FAIL.** `bb nido:test :only nido.coordinator.github-issue-intake-test`.

- [ ] **Step 3: Implement.** Create `src/nido/coordinator/github_issue_intake.clj`:

```clojure
(ns nido.coordinator.github-issue-intake
  "Coordinator housekeeping: poll a project's GitHub repo for OPEN issues
   assigned to you and reconcile a workstream per issue — born at stage :ready,
   NO session spawn (you promote it manually — that's Phase 4's workstream-level
   promote). Snapshot-free: idempotent upsert by the :github-issue ref, plus a
   reverse pass that DROPs the queue entry when an issue is no longer assigned AND
   its workstream is still unpromoted (stage :ready, no sessions). Unlike the
   merge poller the FIRST poll surfaces the whole backlog — nothing auto-fires, so
   the backlog IS the queue.

   Half-open breaker mirrors github-merge: an :auth failure (or ≥3 consecutive
   failures) opens it; while open the poll is skipped until breaker-cooldown-s,
   then one probe runs (success clears, failure re-arms)."
  (:require
   [nido.coordinator.clock :as clock]
   [nido.coordinator.session :as session]
   [nido.coordinator.sources.state :as sstate]
   [nido.coordinator.workstream :as ws]
   [nido.github.client :as gh]))

(defn- warn [msg]
  (binding [*err* *err*]
    (.println ^java.io.PrintWriter *err* (str "WARN: " msg))))

(defn- state-key [project] (str "github-issues-" (name project)))

(def ^:private breaker-cooldown-s (* 30 60))

(defn- cooldown-elapsed? [state]
  (if-let [opened (:breaker-opened-at state)]
    (try
      (>= (.toSeconds (java.time.Duration/between
                        (java.time.Instant/parse opened)
                        (java.time.Instant/parse (clock/now-iso))))
          breaker-cooldown-s)
      (catch Exception _ true))
    true))

(defn- issue-id [repo number] (str repo "#" number))

(defn- issue-ref-id [w]
  (some #(when (= :github-issue (:adapter %)) (:id %)) (:external-refs w)))

(defn- unpromoted?
  "A github-issue workstream still sitting in the queue: stage :ready and no
   sessions. A promoted one has advanced stage or grown a session."
  [project w]
  (and (= :ready (:stage w))
       (empty? (session/list-sessions project (:id w)))))

(defn- upsert-issue!
  "Ensure a :ready workstream exists for one assigned issue. Idempotent via
   find-by-ref on the :github-issue adapter."
  [project repo {:keys [number url title]}]
  (let [id (issue-id repo number)]
    (when-not (ws/find-by-ref project :github-issue id)
      (ws/create! project {:stage :ready
                           :external-refs [(cond-> {:adapter :github-issue :id id}
                                             url   (assoc :url url)
                                             title (assoc :title title))]}))))

(defn- reverse-reconcile!
  "Delete queue entries whose issue is no longer in `assigned-ids` AND that are
   still unpromoted. delete! (not close!) so a re-assigned issue re-creates a
   fresh :ready entry next poll. Promoted workstreams are left untouched."
  [project assigned-ids]
  (doseq [ws-id (ws/list-ids project)
          :let  [w  (ws/read-ws project ws-id)
                 id (some-> w issue-ref-id)]
          :when (and id (not (contains? assigned-ids id)) (unpromoted? project w))]
    (ws/delete! project ws-id)))

(defn poll-and-reconcile!
  "One reconcile poll for a project. Reverse-reconcile runs only on a SUCCESSFUL
   poll (an error never drops the queue). Returns nil."
  [project {:keys [repo issues]}]
  (let [k        (state-key project)
        prior    (sstate/read-state k)
        assignee (or (:assignee issues) "@me")]
    (when-not (and (= :open (:breaker prior)) (not (cooldown-elapsed? prior)))
      (let [res (gh/list-assigned-issues repo assignee)]
        (if (:error res)
          (let [auth? (= :auth (:error res))
                fails (inc (or (:consecutive-failures prior) 0))
                open? (or auth? (>= fails 3) (= :open (:breaker prior)))]
            (sstate/write-state! k (merge (or prior {:type :github-issues :project project})
                                          (cond-> {:consecutive-failures fails
                                                   :breaker (if open? :open (:breaker prior))}
                                            open? (assoc :breaker-opened-at (clock/now-iso)))))
            (warn (str "github-issues: gh poll failed for " project " — " (:error res))))
          (let [assigned-ids (into #{} (map #(issue-id repo (:number %))) (:issues res))]
            (doseq [iss (:issues res)] (upsert-issue! project repo iss))
            (reverse-reconcile! project assigned-ids)
            (sstate/write-state! k {:type :github-issues :project project
                                    :consecutive-failures 0 :breaker nil})))))
    nil))
```

- [ ] **Step 4: Run, verify PASS.** `bb nido:test :only nido.coordinator.github-issue-intake-test`, then full `bb nido:test`.

- [ ] **Step 5: Commit.**
```bash
jj commit -m "feat(coordinator): github-issue-intake reconciler

Polls assigned open issues and upserts a :ready workstream per issue (no spawn);
reverse-reconciles unpromoted entries when an issue is unassigned. Half-open
breaker mirrors the merge poller."
```

---

## Task 3: Tick-loop wiring

**Files:** Modify `src/nido/coordinator/core.clj`. (No unit test — daemon-loop glue; mirror `maybe-poll-github-merges!` exactly and verify by reading + the full suite still compiling/passing.)

- [ ] **Step 1: Require the intake ns.** Add `[nido.coordinator.github-issue-intake :as github-issue-intake]` to `core.clj`'s `:require`.

- [ ] **Step 2: Throttle atom.** Next to `(defonce ^:private !last-github-poll-ms (atom {}))`, add:
```clojure
(defonce ^:private !last-github-issue-poll-ms (atom {}))
```

- [ ] **Step 3: Poll fn.** Add immediately after `maybe-poll-github-merges!`, mirroring it (same throttle/error-isolation), but gated on `:issues` being configured + not disabled:
```clojure
(defn- maybe-poll-github-issues!
  "Throttled GitHub-issue intake, per project whose github.edn carries an
   :issues block (and :enabled is not false). At most once per the project's
   :poll interval (default 5m)."
  [now-ms]
  (doseq [project (registered-projects)
          :let  [cfg (try (gh-config/load-config project) (catch Throwable _ nil))]
          :when (and cfg (:issues cfg) (not (false? (:enabled (:issues cfg)))))
          :let  [interval (or (parse-duration-ms (or (:poll cfg) "5m")) 300000)
                 last-ms  (get @!last-github-issue-poll-ms project)]
          :when (or (nil? last-ms) (>= (- now-ms last-ms) interval))]
    (swap! !last-github-issue-poll-ms assoc project now-ms)
    (try
      (github-issue-intake/poll-and-reconcile! project cfg)
      (catch Throwable t
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "WARN: github-issue poll threw for " project " — " (ex-message t))))))))
```
(Match the EXACT names used by `maybe-poll-github-merges!` for `registered-projects`, `gh-config`, `parse-duration-ms` — read it and reuse verbatim.)

- [ ] **Step 4: Call it in the tick.** In `tick!`, right after the `(maybe-poll-github-merges! now-ms)` call, add:
```clojure
    (maybe-poll-github-issues! now-ms)
```

- [ ] **Step 5: Verify it compiles + suite passes.** `bb nido:test` (loads `core` transitively). Confirm no compile error and totals unchanged.

- [ ] **Step 6: Commit.**
```bash
jj commit -m "feat(coordinator): run the github-issue intake from the tick loop"
```

---

## Task 4: The GitHub view tab

**Files:** Modify `src/nido/tui.clj` (`view-defs`); update `test/nido/tui_test.clj`.

- [ ] **Step 1: Update the failing test.** In `test/nido/tui_test.clj`, change `view-order-is-notion-scratch-sessions` to:
```clojure
(deftest view-order-is-notion-github-scratch-sessions
  (is (= [:notion :github :scratch :sessions] (mapv :id @#'tui/view-defs))))
```
And add:
```clojure
(deftest github-view-is-a-workstreams-view-on-the-github-source
  (let [v (#'tui/view-for-id :github)]
    (is (= :workstreams (:kind v)))
    (is (= :github (:source v)))))
```

- [ ] **Step 2: Run, verify FAIL.** `bb nido:test :only nido.tui-test`.

- [ ] **Step 3: Implement.** In `src/nido/tui.clj`, insert the GitHub entry into `view-defs` (after `:notion`):
```clojure
(def ^:private view-defs
  [{:id :notion   :label "Notion"   :kind :workstreams :source :notion}
   {:id :github   :label "GitHub"   :kind :workstreams :source :github}
   {:id :scratch  :label "Scratch"  :kind :workstreams :source :scratch}
   {:id :sessions :label "Sessions" :kind :ops}])
```
No other TUI change needed — `ws-source` (Phase 2) already returns `:github` for `:github-issue` refs, `current-rows`/`workstream-list-rows` dispatch on the view's `:source`, and a `:github`-source workstreams view uses `grouped-by-stage` (so `:ready` issues land under "Ready to pick up"). The tab bar, cycling, header/footer all consume `view-defs` and pick the new tab up automatically.

- [ ] **Step 4: Run, verify PASS.** `bb nido:test :only nido.tui-test`, then full `bb nido:test`.

- [ ] **Step 5: Commit.**
```bash
jj commit -m "feat(tui): add the GitHub view tab

One view-defs entry — Phase 2's source-dispatch renders github-issue workstreams
(stage :ready) under the GitHub tab. Proves adding a source is a one-line change."
```

---

## Task 5: Live verification (controller/human checkpoint)

Requires brian's `~/.nido/projects/brian/github.edn` to gain an `:issues` block and the daemon to be restarted. Real GitHub assigned issues needed to see rows.

- [ ] **Step 1: Enable intake for brian.** Add to `~/.nido/projects/brian/github.edn` an `:issues {:assignee "@me"}` block (keep the existing `:repo`/`:on-merge`). Confirm `gh issue list --repo <repo> --assignee @me --state open` returns your assigned issues from the shell.
- [ ] **Step 2: Restart the daemon** so it loads the new code: `bb nido:coordinator:restart` (launchd) or `bb nido:coordinator:down && bb nido:coordinator:up`. Confirm via `bb nido:coordinator:status`.
- [ ] **Step 3: Confirm intake ran.** After one poll interval (≤5m), check `~/.nido/projects/brian/workstreams/` for new workstreams carrying a `:github-issue` ref at stage `:ready` — one per assigned open issue. (Or `bb nido:coordinator:logs` for the poll.)
- [ ] **Step 4: Confirm the view.** `bb nido:tui` → brian → Tab to the **GitHub** view → the assigned issues appear under "Ready to pick up". (Interactive; human checkpoint.)
- [ ] **Step 5: Confirm reverse-reconcile.** Unassign yourself from one issue on GitHub (or close it); after the next poll, its `:ready` workstream drops from the GitHub view.

---

## Self-Review

**Spec coverage (Phase 3 slice):**
- ":github-issues source polls `gh issue list --assignee @me`, upserts a workstream per issue, no spawn" → Tasks 1 + 2. ✓
- "Born :ready, skip triage" → Task 2 `upsert-issue!` (`:stage :ready`). ✓
- "No cold-start suppression — backlog IS the queue" → snapshot-free reconcile; every poll upserts all currently-assigned. ✓
- "Reverse reconcile: unassigned + unpromoted dropped; promoted left" → Task 2 `reverse-reconcile!` + `unpromoted?`. ✓
- "Adapter `:github-issue` distinct from `:github` (PR)" → Task 2 uses `:github-issue`; merge poller still uses `:github`. ✓
- "GitHub view tab; adding a source = one entry" → Task 4 (single `view-defs` line). ✓
- "No auto-start; manual promote (Phase 4)" → no spawn anywhere; promote untouched. ✓

**Type/name consistency:** `gh/list-assigned-issues [repo assignee (limit)]` → `{:status :ok :issues [...]}`; intake `poll-and-reconcile! [project {:keys [repo issues]}]`; `issue-id` = `repo#number` (same format as the merge poller's `pr-id`); `:github-issue` adapter throughout; `view-defs` `:github` `:source :github` matches `ws-source`'s `:github` return.

**Deferred (documented):** workstream-level promote (Phase 4); issue-body fetch (Phase 4, on-demand).

---

## Execution Handoff

Subagent-driven. Tasks 1, 2, 4 are fully TDD'd (stub `gh/*`, temp nido-root) — implementer-automatable. Task 3 (tick wiring) is daemon-loop glue verified by code review + suite compile. Task 5 is a human checkpoint (needs real config + assigned issues + a daemon restart). After Phase 3, Phase 4 (generalize `promote!` to dispatch on workstream source — Notion keeps its triage-record gate; GitHub seeds the impl payload from the fetched issue body) is the last phase.
