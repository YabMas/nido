# Phase 4: Workstream-Level Promote Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]`.
>
> **VCS:** jj repo. Activate `jujutsu`; never git. One commit per task. The working copy carries the planning docs as UNCOMMITTED changes — each implementer `jj new`s off the code tip so docs stay out of code commits (controller positions them).
>
> **DAEMON RESTART:** Tasks 1–3 change coordinator code. The running daemon must be restarted (`bb nido:coordinator:restart`) for the live verification (Task 6) — unit tests load fresh.

**Goal:** `promote` becomes a workstream-level gesture that dispatches on source. Notion keeps its triage-record gate + `:plan-bug` leg unchanged. A GitHub-issue workstream gets a trivial gate (`:ready` ⇒ promotable), advances to `:in-progress`, fetches the issue body, and provisions a `:full` impl session launched headlessly with the issue body as its brief (Option B — nido-only, no new brian skill). Pressing `p` on a GitHub issue in the TUI now actually starts work.

**Architecture:** `promote-workstream! [project ws-id]` reads the workstream, classifies via `ws-source`, and routes: `:notion` → the existing `promote!` (unchanged); `:github` → a new sub-path that gates on `:ready`, fetches the body via `gh/view-issue`, `advance-stage!`s to `:in-progress`, and enqueues a direct-target envelope for a new brian `:plan-github-issue` trigger (provision-only, `:full`, `impl-` prefix, no Notion `:on-promote`). The coordinator's provision-only launch path — currently hardcoded to `:plan-bug` → `/continue-ticket` — is generalized minimally to also recognize the GitHub trigger and launch it with the issue-body brief (plain text, no slash command) under a GitHub-flavored pre-orientation system prompt. The brian trigger is live config (like Phase 3's `github.edn`), added in setup.

**Tech Stack:** Babashka, Clojure, the `gh` CLI, `clojure.test` (stub `gh/*` + read the queue dir).

**Out of scope / left as-is (per decision):** a dedicated brian `/continue-issue` skill (Option A — later enhancement; B seeds the session generically); the stale `:plan-bug` trigger comment ("NOTHING runs autonomously" — code actually launches `/continue-ticket` headlessly).

---

## File Structure
- **Modify** `src/nido/github/client.clj` — add `view-issue`.
- **Modify** `src/nido/coordinator/promote.clj` — add `promote-workstream!` (source dispatch) + the GitHub sub-path. Keep `promote!` (Notion, br-id) for the CLI task.
- **Modify** `src/nido/coordinator/core.clj` — generalize the provision-only launch to dispatch the GitHub trigger to an issue-body brief; add a GitHub pre-orientation system prompt.
- **Modify** `src/nido/tui.clj` — rewire `promote-selected` to `promote-workstream!` (ws-id, not br-id); GitHub-aware `promote-result-message`.
- **Modify** `src/nido/coordinator/workstreams_view.clj` — extend `promote-result-message` for the GitHub/source-dispatched decisions.
- **Setup (live config)** `~/.nido/projects/brian/triggers.edn` — add the `:plan-github-issue` trigger.
- **Tests:** `test/nido/github/client_test.clj`, `test/nido/coordinator/promote_test.clj`, `test/nido/coordinator/workstreams_view_test.clj`.

---

## Task 1: `gh/view-issue` client

**Files:** Modify `src/nido/github/client.clj` + `test/nido/github/client_test.clj`.

- [ ] **Step 1: Failing tests.** Match the existing `sh!`-stub style. Add:
```clojure
(deftest view-issue-parses-body
  (with-redefs [gh/sh! (fn [args]
                         (is (= ["gh" "issue" "view" "42" "--repo" "o/r"
                                 "--json" "number,url,title,body"] args))
                         {:exit 0
                          :out "{\"number\":42,\"url\":\"u\",\"title\":\"t\",\"body\":\"do the thing\"}"
                          :err ""})]
    (is (= {:status :ok :issue {:number 42 :url "u" :title "t" :body "do the thing"}}
           (gh/view-issue "o/r" 42)))))

(deftest view-issue-flags-auth-errors
  (with-redefs [gh/sh! (fn [_] {:exit 1 :out "" :err "gh auth required"})]
    (is (= :auth (:error (gh/view-issue "o/r" 42))))))
```

- [ ] **Step 2: Run, verify FAIL.** `bb nido:test :only nido.github.client-test`.

- [ ] **Step 3: Implement.** After `list-assigned-issues`:
```clojure
(defn view-issue
  "Fetch one issue's metadata + body. Returns
   {:status :ok :issue {:number :url :title :body}} or {:error :auth|:gh}."
  [repo number]
  (let [{:keys [exit out err]}
        (sh! ["gh" "issue" "view" (str number) "--repo" repo
              "--json" "number,url,title,body"])]
    (if (zero? exit)
      {:status :ok
       :issue  (let [m (json/parse-string out true)]
                 {:number (:number m) :url (:url m) :title (:title m) :body (:body m)})}
      {:error (if (auth-error? err) :auth :gh) :detail (str/trim (or err ""))})))
```

- [ ] **Step 4: Run PASS** (`bb nido:test :only nido.github`, then full). **Step 5: Commit** `feat(github): view-issue client (fetch issue body)`.

---

## Task 2: `promote-workstream!` source dispatch + GitHub sub-path

**Files:** Modify `src/nido/coordinator/promote.clj` + `test/nido/coordinator/promote_test.clj`.

Current `promote!` (keep it — the CLI `bb nido:ticket:promote` and the Notion path call it):
```clojure
(defn promote! [project br-id]
  (let [decision (tickets/promote-decision project br-id)]
    (if (not= :promote decision) {:decision decision}
      (let [m (tickets/read-meta project br-id)
            payload {:id br-id :notion-page-id (:notion-page-id m) :url (:url m)
                     :title (:title m) :report-path (latest-entry-of-kind project br-id :triage)}]
        (cstate/ensure-dirs!) (tickets/set-status! project br-id :planning)
        {:decision :promote
         :queued (queue/enqueue! {:target {:project project :trigger :plan-bug} :payload payload})}))))
```

- [ ] **Step 1: Failing tests.** Mirror `promote_test.clj`'s `with-tmp` + `queued-envelopes` helpers. Require `nido.github.client :as gh`, `nido.coordinator.workstream :as ws`. Add:
```clojure
(deftest promote-workstream-routes-notion-to-the-plan-bug-leg
  (with-tmp
    (fn [_]
      ;; a :triaged Notion ticket + a workstream carrying its :notion ref
      (tickets/open! :brian "BR-7" {:notion-page-id "pg" :url "nu" :title "nt"})
      (tickets/complete! :brian "BR-7" :triaged "looks real")
      (let [w (ws/create! :brian {:stage :ready :external-refs [{:adapter :notion :id "BR-7" :page-id "pg"}]})]
        (let [res (promote/promote-workstream! :brian (:id w))]
          (is (= :promote (:decision res)))
          (let [[env] (queued-envelopes)]
            (is (= {:project :brian :trigger :plan-bug} (:target env)))
            (is (= "BR-7" (-> env :payload :id)))))))))

(deftest promote-workstream-github-enqueues-issue-leg-and-advances-stage
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :ready
                                  :external-refs [{:adapter :github-issue :id "o/r#42"
                                                   :url "iu" :title "it"}]})]
        (with-redefs [gh/view-issue (fn [repo n]
                                      (is (= "o/r" repo)) (is (= 42 n))
                                      {:status :ok :issue {:number 42 :url "iu" :title "it" :body "do it"}})]
          (let [res (promote/promote-workstream! :brian (:id w))]
            (is (= :promote (:decision res)))
            (is (= :in-progress (:stage (ws/read-ws :brian (:id w)))) "advanced out of the queue")
            (let [[env] (queued-envelopes)]
              (is (= {:project :brian :trigger :plan-github-issue} (:target env)))
              (is (= "o/r#42" (-> env :payload :id)))
              (is (= "do it"  (-> env :payload :body))))))))))

(deftest promote-workstream-github-refuses-when-already-promoted
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress
                                  :external-refs [{:adapter :github-issue :id "o/r#42"}]})]
        (is (= :skip-active (:decision (promote/promote-workstream! :brian (:id w)))))
        (is (empty? (queued-envelopes)))))))

(deftest promote-workstream-github-surfaces-a-fetch-error
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :ready :external-refs [{:adapter :github-issue :id "o/r#42"}]})]
        (with-redefs [gh/view-issue (fn [_ _] {:error :auth})]
          (is (= :gh-error (:decision (promote/promote-workstream! :brian (:id w)))))
          (is (= :ready (:stage (ws/read-ws :brian (:id w)))) "not advanced on error")
          (is (empty? (queued-envelopes))))))))

(deftest promote-workstream-scratch-is-not-promotable
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :scratch :external-refs []})]
        (is (= :skip-not-promotable (:decision (promote/promote-workstream! :brian (:id w)))))))))
```

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement.** Add to `promote.clj` (requires `nido.coordinator.workstream :as ws`, `nido.coordinator.workstreams-view :as wsv`, `nido.github.client :as gh`, `clojure.string :as str`):
```clojure
(defn- notion-br-id [w]
  (some #(when (= :notion (:adapter %)) (:id %)) (:external-refs w)))

(defn- github-issue-ref [w]
  (some #(when (= :github-issue (:adapter %)) %) (:external-refs w)))

(defn- parse-issue-id
  "\"owner/repo#42\" → {:repo \"owner/repo\" :number 42}."
  [id]
  (let [i (.lastIndexOf ^String id "#")]
    {:repo (subs id 0 i) :number (parse-long (subs id (inc i)))}))

(defn- promote-github! [project w]
  (if (not= :ready (:stage w))
    {:decision :skip-active}                                   ; already promoted / not in the queue
    (let [ref (github-issue-ref w)
          {:keys [repo number]} (parse-issue-id (:id ref))
          res (gh/view-issue repo number)]
      (if (:error res)
        {:decision :gh-error}
        (let [{:keys [title url body]} (:issue res)
              payload {:id (:id ref) :url url :title title :body body}]
          (cstate/ensure-dirs!)
          (ws/advance-stage! project (:id w) :in-progress)
          {:decision :promote
           :queued (queue/enqueue! {:target {:project project :trigger :plan-github-issue}
                                    :payload payload})})))))

(defn promote-workstream!
  "Promote a workstream by id, dispatching on its source. :notion → the existing
   triage-gated :plan-bug leg; :github → fetch the issue body and provision the
   issue-impl leg; anything else (scratch) isn't promotable. Returns {:decision}."
  [project ws-id]
  (if-let [w (ws/read-ws project ws-id)]
    (case (wsv/ws-source w)
      :notion (promote! project (notion-br-id w))
      :github (promote-github! project w)
      {:decision :skip-not-promotable})
    {:decision :skip-not-promotable}))
```

- [ ] **Step 4: Run PASS** (targeted + full). **Step 5: Commit** `feat(coordinator): promote-workstream! — source-dispatched promote (Notion + GitHub)`.

---

## Task 3: Generalize the coordinator provision-only launch (the delicate one)

**Files:** Modify `src/nido/coordinator/core.clj`. No unit test (deep executor/agent-launch path) — verify by code review + the suite compiling + the live run (Task 6). READ `core.clj` lines ~50–60 (the `:plan-system-prompt` in `defaults`) and ~277–330 (the `provision-only?` detection + the `agent/launch!` call) BEFORE editing.

- [ ] **Step 1: Add a GitHub pre-orientation system prompt** to `defaults` (next to `:plan-system-prompt`), without the Notion/`/continue-ticket` references:
```clojure
   :plan-issue-system-prompt
   "You are pre-orienting a nido impl session unattended: the human who owns this session is not here yet but will resume THIS conversation shortly. Your first message is the GitHub issue to implement. Do clear, low-risk implementation work autonomously toward a draft PR. The moment you reach something that needs a human decision — a product/design call, genuine ambiguity, or a risky/destructive change — STOP and leave a concise summary of what you did, where things stand, and exactly what you need; do not guess."
```

- [ ] **Step 2: Generalize `provision-only?`.** Where it currently reads `(= :plan-bug (:skill (runs/read-run run-id)))`, introduce a set and use it:
```clojure
(def ^:private provision-only-skills #{:plan-bug :plan-github-issue})
;; ... at the call site:
provision-only? (contains? provision-only-skills (:skill (runs/read-run run-id)))
```
Check every place that special-cases `:plan-bug` for provision/outliving behavior (grep `:plan-bug` in `core.clj`, `runs.clj`, `review.clj`, `migrate.clj`): the GitHub leg has the SAME "session outlives the run, handed to the human" semantics, so each `(= :plan-bug ...)` / `(#{:triage-bug :plan-bug} ...)` guard that governs *provision/outlive* behavior should also accept `:plan-github-issue`. Do NOT touch the `tickets/on-run-terminal!` Notion-status reconciliation (GitHub has no ticket meta — it must stay Notion-only; a `:plan-github-issue` run simply has no `br-id` so that code already no-ops, but confirm it).

- [ ] **Step 3: Launch with the issue brief for the GitHub leg.** At the `agent/launch!` call, the first-message and system-prompt are currently hardcoded to `/continue-ticket` + `:plan-system-prompt`. Make them dispatch on skill:
```clojure
(let [run' (runs/read-run run-id)
      github? (= :plan-github-issue (:skill run'))
      {:keys [title url body]} (:event-payload run')]
  (agent/launch! {:run-id run-id
                  :cwd (cstate/run-session-home-link run-id)
                  :first-message (if github?
                                   (str "Implement this GitHub issue, then open a draft PR.\n\n"
                                        "# " title "\n" url "\n\n" body)
                                   "/continue-ticket")
                  :system-prompt (get defaults (if github? :plan-issue-system-prompt :plan-system-prompt))
                  :budget (-> run' :limits :budget)
                  :claude-session-id session-id}))
```
(Adapt to the EXACT current `agent/launch!` arg map and the surrounding `let` bindings — read it; keep every other arg identical.)

- [ ] **Step 4: Verify it compiles + suite green.** `bb nido:test` (loads core transitively). Report totals (should be unchanged).

- [ ] **Step 5: Commit** `feat(coordinator): provision-only launch dispatches the GitHub issue leg`.

---

## Task 4: TUI promote wiring + result messages

**Files:** Modify `src/nido/tui.clj` (`promote-selected`) + `src/nido/coordinator/workstreams_view.clj` (`promote-result-message`) + `test/nido/coordinator/workstreams_view_test.clj`.

- [ ] **Step 1: Failing test** for the generalized message. In `workstreams_view_test.clj`:
```clojure
(deftest promote-result-message-covers-github-and-source-decisions
  (is (re-find #"in progress" (wsv/promote-result-message "o/r#42" :promote)))
  (is (= "o/r#42 already promoted" (wsv/promote-result-message "o/r#42" :skip-active)))
  (is (re-find #"couldn't reach GitHub" (wsv/promote-result-message "o/r#42" :gh-error)))
  (is (re-find #"nothing to promote" (wsv/promote-result-message nil :skip-not-promotable))))
```

- [ ] **Step 2: Run, verify FAIL.**

- [ ] **Step 3: Implement message.** Extend `promote-result-message` (keep the existing Notion cases) with the new decisions:
```clojure
      :gh-error            (str "couldn't reach GitHub for " br " — try again")
      :skip-not-promotable "nothing to promote on this workstream"
```
And make the `nil br` branch handle `:skip-not-promotable` too (a scratch/ref-less workstream): if `(nil? br)` return `"nothing to promote on this workstream"`. Keep `:promote`/`:skip-active`/etc. working for a non-nil id (the GitHub id `o/r#42` flows through the same strings — "promoted o/r#42 → in progress", "o/r#42 already promoted").

- [ ] **Step 4: Rewire the TUI.** In `tui.clj` `promote-selected`, change the call from `(promote/promote! (keyword (:project state)) (:br-id ws))` to `(promote/promote-workstream! (keyword (:project state)) (:ws-id ws))`, and pass an id to `promote-result-message` that works for both sources — use `(or (:br-id ws) (:ws-id ws))`... but for GitHub the message should show the issue ref, not the ws-id. The row's `:label` for a github workstream is the issue title; the ref id (`o/r#42`) isn't a row field today. SIMPLEST: pass `(:br-id ws)` (nil for GitHub) and let the message read generically — OR add the issue-ref id to the row. Pick: extend `wsv/workstream-row` to also carry `:promote-id` = `(or br-id (github-issue-id ws))`, and have `promote-selected` pass `(:promote-id ws)`. Implement that small row addition + use it.

- [ ] **Step 5: Run PASS + Commit** `feat(tui): promote dispatches on workstream source (Notion + GitHub)`.

---

## Task 5: brian `:plan-github-issue` trigger (live config setup)

Live config, not nido repo (like Phase 3's `github.edn`). Required for the envelope to route.

- [ ] **Step 1:** Add to `~/.nido/projects/brian/triggers.edn` `:triggers` vector (mirrors `:plan-bug` but no Notion `:on-promote`):
```clojure
  {:name                :plan-github-issue
   :source              {:type :manual}
   :skill               :plan-github-issue
   :session-profile     :full
   :session-name-prefix "impl-"
   :max-in-flight       3
   :payload             "Implement {{event/title}} ({{event/id}}). {{event/url}}"
   :limits              {:max-failures 3}}
```
- [ ] **Step 2:** Confirm the file still reads (`bb nido:coordinator:status` doesn't error on triggers, or load it via a quick edn check).

---

## Task 6: Live verification (human/controller checkpoint)

- [ ] **Step 1:** Ensure Phase 3 intake is on (brian `github.edn` `:issues`) and at least one assigned issue exists as a `:ready` GitHub-view workstream.
- [ ] **Step 2:** `bb nido:coordinator:restart`; `bb nido:coordinator:status`.
- [ ] **Step 3:** `bb nido:tui` → brian → GitHub view → highlight an issue → press `p`. Expect status "promoted o/r#NN → in progress"; the row moves Ready → In progress.
- [ ] **Step 4:** Within a tick, an `impl-…` `:full` session is provisioned and (headlessly) launched with the issue body as its brief; confirm via `bb nido:coordinator:logs` / the session list. `nido enter` it to confirm the agent picked up the issue and parked sensibly.
- [ ] **Step 5:** Confirm reverse-reconcile no longer drops it (it's `:in-progress` ⇒ promoted ⇒ kept) even if you later close the issue.

---

## Self-Review

**Spec coverage (Phase 4 slice):**
- "Generalize promote to dispatch on workstream source" → Task 2 `promote-workstream!`. ✓
- "Notion path unchanged (triage-record gate)" → `promote!` untouched; `:notion` routes to it. ✓
- "GitHub: trivial gate, seed payload from issue body" → `promote-github!` (gate `:ready`; `gh/view-issue` body). ✓
- "Reuse the same impl leg" (Option B) → same provision-only `:full` session + headless launch, generalized in Task 3; brief = issue body. ✓
- "Born→promoted lifecycle, reverse-reconcile keeps promoted" → `advance-stage! :in-progress` makes `unpromoted?` false. ✓

**Type/name consistency:** `gh/view-issue [repo number]`→`{:issue {:body}}`; `promote-workstream! [project ws-id]`→`{:decision}`; decisions `:promote|:skip-active|:gh-error|:skip-not-promotable` covered in `promote-result-message`; trigger `:plan-github-issue` matches across promote envelope + brian config + core.clj `provision-only-skills` + the launch dispatch; `:event-payload` `{:id :url :title :body}` is what Task 3 reads.

**Risk note:** Task 3 is the delicate one (the coordinator's hardcoded headless-launch). It's small, but it has no unit test — it rides on review + the live run. The GitHub leg deliberately shares `:plan-bug`'s "session outlives the run" semantics; the only thing it must NOT share is `tickets/on-run-terminal!` (Notion-status reconciliation), which already no-ops without a `br-id`.

---

## Execution Handoff

Subagent-driven. Tasks 1, 2, 4 are TDD-automatable. Task 3 is review-gated coordinator surgery (no interactive test). Task 5 is live config; Task 6 is the human checkpoint (needs a real assigned issue + daemon restart). This completes the four-phase arc.
