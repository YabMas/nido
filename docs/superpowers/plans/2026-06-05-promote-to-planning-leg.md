# Promote-to-Planning-Leg Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `promote` action that turns a `:triaged` ticket into an autonomous `:full`-session planning Run — gather-context-and-plan, then park for human review — reusing the existing Run/ledger/park machinery.

**Architecture:** A `promote` enqueues a *direct-target* envelope (`{:target {:project … :trigger :plan-bug} :payload …}`) into the coordinator queue. A new source-less `:plan-bug` trigger (`:source {:type :manual}`, `:session-profile :full`) routes it through the unchanged `create-run!` → executor → `spawn-session-for-run!` → `agent/launch!` path. The plan agent runs against live services, writes a `:plan` ledger entry, parks at `:awaiting-input`. nido flips the Notion ticket to "In progress" at the spawn chokepoint. Two front doors (CLI `bb nido:ticket:promote`, and the triage skill's in-chat `promote`) share one envelope shape.

**Tech Stack:** Babashka + Clojure, Malli schemas, `babashka.http-client` (Notion REST), `clojure.test` via `bb nido:test`.

**Spec:** `docs/superpowers/specs/2026-06-05-promote-to-planning-leg-design.md`

---

## Boundary: nido vs brian

This plan delivers the **nido mechanism only**. Two pieces are brian-repo content, specified here as contracts (see "Cross-repo dependencies" at the end) but **not** implemented by these tasks:

- the `plan-bug` skill (mirrored into `nido/.claude/` like `triage-bug`), and
- the `triage-bug` skill learning to emit the promote envelope on an in-chat `promote`.

Until those land, promotion is driven by the CLI (`bb nido:ticket:promote`) and the plan Run will launch claude with a `/plan-bug …` first-message that resolves to nothing — that's the expected pre-skill state.

## File structure

| File | Responsibility | Action |
|---|---|---|
| `src/nido/notion/client.clj` | Notion REST client | **Modify** — add `:patch` dispatch + `update-page-status!` |
| `src/nido/coordinator/notify.clj` | Best-effort outbound notifications on Run events | **Create** |
| `src/nido/coordinator/tickets.clj` | Ticket ledger + gates | **Modify** — `promote-decision`; generalize `on-run-terminal!` for `:plan-bug` |
| `src/nido/coordinator/triggers.clj` | Trigger schema | **Modify** — add `:on-promote`, `:session-name-prefix` |
| `src/nido/coordinator/runs.clj` | Run record + create | **Modify** — Run schema `:on-promote`; snapshot it; ticket-stable session-name |
| `src/nido/coordinator/review.clj` | Ticket↔Run bridge + sweep | **Modify** — generalize for `:plan-bug` |
| `src/nido/coordinator/promote.clj` | Shared promote action | **Create** |
| `src/nido/coordinator/core.clj` | Daemon loop / run driver | **Modify** — plan parked-state + notify-on-spawn |
| `src/tasks/nido_ticket.clj` | `nido:ticket:*` tasks | **Modify** — `promote-cmd` |
| `bb.edn` | Task registry | **Modify** — `nido:ticket:promote` |
| `~/.nido/projects/brian/triggers.edn` | brian trigger config (not in repo) | **Modify** — add `:plan-bug` trigger |
| tests under `test/nido/...` | unit coverage | **Create/Modify** per task |

**Test command:** `bb nido:test :only <ns-prefix>` (e.g. `bb nido:test :only nido.coordinator.tickets-test`). Run the whole suite with bare `bb nido:test`.

**Per-task commit:** this repo follows commit-driven development. Commit after each task's tests pass. **Do not commit** the spec or this plan file (global rule: planning artifacts stay uncommitted).

---

## Task 1: Notion client — `:patch` + `update-page-status!`

**Files:**
- Modify: `src/nido/notion/client.clj:40-46` (http-request), and append `update-page-status!`
- Test: `test/nido/notion/client_patch_test.clj` (create)

- [ ] **Step 1: Write the failing test**

Create `test/nido/notion/client_patch_test.clj`:

```clojure
(ns nido.notion.client-patch-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is]]
   [nido.notion.client :as notion]))

(deftest update-page-status-sends-patch-with-status-shape
  (let [captured (atom nil)]
    (with-redefs [notion/http-request
                  (fn [method url opts]
                    (reset! captured {:method method :url url :opts opts})
                    {:status 200 :body "{}"})]
      (let [res (notion/update-page-status! "PAGE1" "Status" "In progress" "tok")]
        (is (= {:ok true} res))
        (is (= :patch (:method @captured)))
        (is (= "https://api.notion.com/v1/pages/PAGE1" (:url @captured)))
        (is (= {:properties {"Status" {:status {:name "In progress"}}}}
               (json/parse-string (-> @captured :opts :body) false))
            "status-type property uses {:status {:name ...}}")
        (is (= "Bearer tok" (get-in @captured [:opts :headers "Authorization"])))))))

(deftest update-page-status-maps-error-codes
  (with-redefs [notion/http-request (fn [_ _ _] {:status 401 :body ""})]
    (is (= {:error :auth} (notion/update-page-status! "P" "Status" "X" "tok"))))
  (with-redefs [notion/http-request (fn [_ _ _] {:status 503 :body ""})]
    (is (= {:error :server} (notion/update-page-status! "P" "Status" "X" "tok"))))
  (with-redefs [notion/http-request (fn [_ _ _] {:status 0})]
    (is (= {:error :network} (notion/update-page-status! "P" "Status" "X" "tok")))))
```

Note: `json/parse-string … false` returns string keys, so the expected map uses `"Status"` and `"properties"` as strings but `:status`/`:name` would also be strings — adjust the expected to all-string keys:

```clojure
        (is (= {"properties" {"Status" {"status" {"name" "In progress"}}}}
               (json/parse-string (-> @captured :opts :body) false)))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.notion.client-patch-test`
Expected: FAIL — `update-page-status!` unresolved / `:patch` not dispatched.

- [ ] **Step 3: Add `:patch` to `http-request`**

In `src/nido/notion/client.clj`, change the `case` in `http-request`:

```clojure
(defn http-request
  "Wrapped HTTP call so tests can stub. Dispatches on method (:get/:post/:patch).
   Returns {:status :body}."
  [method url opts]
  (case method
    :get   (http/get   url (assoc opts :throw false))
    :post  (http/post  url (assoc opts :throw false))
    :patch (http/patch url (assoc opts :throw false))))
```

- [ ] **Step 4: Add `update-page-status!`**

Append to `src/nido/notion/client.clj` (after `data-source-query`, before the private normalise helpers is fine — keep it with the other public REST fns):

```clojure
(defn update-page-status!
  "PATCH /v1/pages/<page-id>, setting a Status-type property to a named option.
   Returns {:ok true} on 200, else {:error :kw} (with :status for generic HTTP).
   Notion 'Status' properties take {:status {:name <option>}} — NOT :select."
  [page-id property-name status-name token]
  (let [resp (try
               (http-request
                 :patch
                 (str "https://api.notion.com/v1/pages/" page-id)
                 {:headers {"Authorization"  (str "Bearer " token)
                            "Notion-Version" notion-api-version
                            "Content-Type"   "application/json"}
                  :body    (json/generate-string
                             {:properties {property-name {:status {:name status-name}}}})
                  :timeout 10000})
               (catch Exception e {:status 0 :exception e}))
        {:keys [status]} resp]
    (cond
      (= status 200)  {:ok true}
      (= status 401)  {:error :auth}
      (>= status 500) {:error :server}
      (= status 0)    {:error :network}
      :else           {:error :http :status status})))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `bb nido:test :only nido.notion.client-patch-test`
Expected: PASS (both deftests).

- [ ] **Step 6: Commit**

```bash
git add src/nido/notion/client.clj test/nido/notion/client_patch_test.clj
git commit -m "feat(notion): patch a page's Status property via update-page-status!"
```

---

## Task 2: `notify` namespace — best-effort Notion status on plan spawn

**Files:**
- Create: `src/nido/coordinator/notify.clj`
- Test: `test/nido/coordinator/notify_test.clj` (create)

- [ ] **Step 1: Write the failing test**

Create `test/nido/coordinator/notify_test.clj`:

```clojure
(ns nido.coordinator.notify-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.notify :as notify]
   [nido.notion.client :as notion]))

(def ^:private base-run
  {:id "run-1"
   :skill :plan-bug
   :event-payload {:id "BR-1" :notion-page-id "PAGE1"}
   :on-promote {:notion-status "In progress"}})

(deftest on-plan-spawn-writes-configured-status
  (let [calls (atom [])]
    (with-redefs [notion/keychain-token   (constantly "tok")
                  notion/update-page-status! (fn [pg prop st tok]
                                               (swap! calls conj [pg prop st tok])
                                               {:ok true})]
      (notify/on-plan-spawn! base-run)
      (is (= [["PAGE1" "Status" "In progress" "tok"]] @calls)
          "defaults property name to \"Status\""))))

(deftest on-plan-spawn-honours-explicit-property
  (let [calls (atom [])]
    (with-redefs [notion/keychain-token (constantly "tok")
                  notion/update-page-status! (fn [pg prop st _] (swap! calls conj [pg prop st]) {:ok true})]
      (notify/on-plan-spawn! (assoc base-run :on-promote {:notion-status "In progress" :property "State"}))
      (is (= [["PAGE1" "State" "In progress"]] @calls)))))

(deftest on-plan-spawn-is-noop-without-config-or-page
  (let [calls (atom 0)]
    (with-redefs [notion/keychain-token (constantly "tok")
                  notion/update-page-status! (fn [& _] (swap! calls inc) {:ok true})]
      (notify/on-plan-spawn! (dissoc base-run :on-promote))                 ; no config
      (notify/on-plan-spawn! (assoc base-run :event-payload {:id "BR-1"}))  ; no page-id
      (is (= 0 @calls)))))

(deftest on-plan-spawn-swallows-errors
  (with-redefs [notion/keychain-token (constantly "tok")
                notion/update-page-status! (fn [& _] (throw (ex-info "boom" {})))]
    (is (nil? (notify/on-plan-spawn! base-run)) "throwing client must not propagate"))
  (with-redefs [notion/keychain-token (constantly nil)
                notion/update-page-status! (fn [& _] (throw (ex-info "should-not-call" {})))]
    (is (nil? (notify/on-plan-spawn! base-run)) "no token ⇒ skip, no throw")))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.notify-test`
Expected: FAIL — namespace `nido.coordinator.notify` not found.

- [ ] **Step 3: Create the namespace**

Create `src/nido/coordinator/notify.clj`:

```clojure
(ns nido.coordinator.notify
  "Best-effort outbound notifications on Run lifecycle events.

   Currently one event: a plan Run spawning flips its ticket's Notion status
   per the Run's snapshotted :on-promote config (promote → \"In progress\").
   Every path is best-effort — any failure logs a warning and returns nil so it
   can never strand the planning Run."
  (:require
   [nido.notion.client :as notion]))

(defn- warn [msg]
  (binding [*err* *err*]
    (.println ^java.io.PrintWriter *err* (str "WARN: " msg))))

(defn on-plan-spawn!
  "Flip the ticket's Notion status when its plan Run spawns. Reads
   {:notion-status <s> :property <s?>} from (:on-promote run) and the page id
   from (-> run :event-payload :notion-page-id). No-op when either is absent."
  [run]
  (let [{:keys [notion-status property]} (:on-promote run)
        page-id (some-> run :event-payload :notion-page-id)]
    (when (and notion-status page-id)
      (try
        (if-let [token (notion/keychain-token)]
          (let [res (notion/update-page-status! page-id (or property "Status")
                                                notion-status token)]
            (when (:error res)
              (warn (str "notify: Notion status write failed for " (:id run)
                         " — " (pr-str res)))))
          (warn (str "notify: no Notion token; skipping status write for " (:id run))))
        (catch Throwable t
          (warn (str "notify: Notion status write threw for " (:id run)
                     " — " (.getMessage t)))))
      nil)))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.coordinator.notify-test`
Expected: PASS (4 deftests).

- [ ] **Step 5: Commit**

```bash
git add src/nido/coordinator/notify.clj test/nido/coordinator/notify_test.clj
git commit -m "feat(coordinator): best-effort Notion status flip on plan spawn"
```

---

## Task 3: ticket ledger — `promote-decision` + `:plan-bug` reconciliation

**Files:**
- Modify: `src/nido/coordinator/tickets.clj` (add `promote-decision`; rewrite `on-run-terminal!`)
- Test: `test/nido/coordinator/tickets_test.clj` (append)

- [ ] **Step 1: Write the failing tests**

Append to `test/nido/coordinator/tickets_test.clj` (it already requires `clock`, `cstate`, `tickets`, `fs`; reuse the file's existing `with-tmp`):

```clojure
(deftest promote-decision-allows-only-triaged
  (with-tmp
    (fn [_]
      (is (= :skip-no-record (tickets/promote-decision :brian "BR-X")))   ; no record
      (tickets/open! :brian "BR-1" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (is (= :skip-untriaged (tickets/promote-decision :brian "BR-1")))   ; :investigating
      (tickets/complete! :brian "BR-1" :triaged :applied)
      (is (= :promote (tickets/promote-decision :brian "BR-1")))          ; the one yes
      (tickets/set-status! :brian "BR-1" :planning)
      (is (= :skip-active (tickets/promote-decision :brian "BR-1")))      ; already planning
      (tickets/complete! :brian "BR-1" :skipped :leave-as-is)
      (is (= :skip-completed (tickets/promote-decision :brian "BR-1"))))))

(deftest on-run-terminal-plan-bug-abnormal-exit-reverts-to-triaged
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-2" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/complete! :brian "BR-2" :triaged :applied)
      (tickets/set-status! :brian "BR-2" :planning)
      ;; a :failed plan run with a stale :planning ⇒ re-promotable (:triaged)
      (tickets/on-run-terminal! {:skill :plan-bug :project :brian
                                 :event-payload {:id "BR-2"}} :failed)
      (is (= :triaged (tickets/status :brian "BR-2"))))))

(deftest on-run-terminal-plan-bug-parked-is-left-alone
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-3" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t0"})
      (tickets/set-status! :brian "BR-3" :awaiting-input)   ; plan parked
      (tickets/on-run-terminal! {:skill :plan-bug :project :brian
                                 :event-payload {:id "BR-3"}} :awaiting-review)
      (is (= :awaiting-input (tickets/status :brian "BR-3"))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bb nido:test :only nido.coordinator.tickets-test`
Expected: FAIL — `promote-decision` unresolved; `on-run-terminal!` ignores `:plan-bug`.

- [ ] **Step 3: Add `promote-decision`**

In `src/nido/coordinator/tickets.clj`, after `gate-decision`:

```clojure
(defn promote-decision
  "Decide whether a ticket may be promoted to a planning Run, by reading its
   meta status:
     :promote        — status :triaged (the only promotable state)
     :skip-active    — a plan Run already owns it (:planning)
     :skip-completed — triage said don't bother (:skipped)
     :skip-no-record — never triaged (no record)
     :skip-untriaged — mid-triage or any other non-:triaged status"
  [project br-id]
  (case (status project br-id)
    :triaged  :promote
    :planning :skip-active
    :skipped  :skip-completed
    nil       :skip-no-record
    :skip-untriaged))
```

- [ ] **Step 4: Generalize `on-run-terminal!`**

Replace the whole `on-run-terminal!` defn with:

```clojure
(defn on-run-terminal!
  "Reconcile a ticket's meta when its triage/plan Run reaches a terminal or
   parked coordinator state. No-op for other skills and record-less tickets.
   - run ended :awaiting-review        → leave (session parked)
   - meta already :triaged/:skipped    → leave (skill wrote the disposition)
   - otherwise (abnormal/stale status):
       :triage-bug → clear   (drop status → re-triable)
       :plan-bug   → :triaged (revert → re-promotable, preserving triage)"
  [run run-state]
  (let [skill   (:skill run)
        project (:project run)
        br-id   (some-> run :event-payload :id)]
    (when (and (#{:triage-bug :plan-bug} skill) br-id (not (str/blank? br-id)))
      (when-let [m (read-meta project br-id)]
        (cond
          (= :awaiting-review run-state)     nil
          (#{:triaged :skipped} (:status m)) nil
          (= :plan-bug skill)                (set-status! project br-id :triaged)
          :else                              (clear-status! project br-id))))))
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `bb nido:test :only nido.coordinator.tickets-test`
Expected: PASS (existing + 3 new deftests).

- [ ] **Step 6: Commit**

```bash
git add src/nido/coordinator/tickets.clj test/nido/coordinator/tickets_test.clj
git commit -m "feat(coordinator/tickets): promote-decision gate + plan-bug reconciliation"
```

---

## Task 4: trigger schema — `:on-promote` + `:session-name-prefix`

**Files:**
- Modify: `src/nido/coordinator/triggers.clj:14-30` (the `Trigger` map)
- Test: `test/nido/coordinator/triggers_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/coordinator/triggers_test.clj` (check its existing requires; it requires `nido.coordinator.triggers :as triggers` and `clojure.test`; `malli.core` may need adding to the ns `:require` — add `[malli.core :as m]` if absent):

```clojure
(deftest trigger-accepts-on-promote-and-session-name-prefix
  (is (m/validate triggers/Trigger
                  {:name :plan-bug :source {:type :manual} :skill :plan-bug
                   :payload "Plan {{event/title}}"
                   :session-profile :full
                   :session-name-prefix "impl-"
                   :on-promote {:notion-status "In progress"}})))

(deftest trigger-rejects-bad-session-name-prefix
  (is (not (m/validate triggers/Trigger
                       {:name :plan-bug :source {:type :manual} :skill :plan-bug
                        :payload "x" :session-name-prefix 42}))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.triggers-test`
Expected: FAIL — closed schema rejects unknown keys `:session-name-prefix` / `:on-promote`.

- [ ] **Step 3: Extend the schema**

In `src/nido/coordinator/triggers.clj`, add two rows inside the `Trigger` `[:map {:closed true} …]`, alongside the other optionals:

```clojure
   [:session-name-prefix {:optional true} string?]
   [:on-promote          {:optional true} [:map-of keyword? any?]]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.coordinator.triggers-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/nido/coordinator/triggers.clj test/nido/coordinator/triggers_test.clj
git commit -m "feat(coordinator/triggers): :on-promote + :session-name-prefix keys"
```

---

## Task 5: Run record — snapshot `:on-promote` + ticket-stable session-name

**Files:**
- Modify: `src/nido/coordinator/runs.clj` (ns require, `Run` schema, `create-run!`)
- Test: `test/nido/coordinator/runs_test.clj` (append)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/coordinator/runs_test.clj` (reuse its `with-tmp`/`cstate` redef pattern — mirror the existing tests in that file for the exact fixture call):

```clojure
(deftest create-run-derives-ticket-stable-session-name-and-snapshots-on-promote
  (with-tmp
    (fn [_]
      (let [trigger {:name :plan-bug :source {:type :manual} :skill :plan-bug
                     :payload "Plan {{event/title}}"
                     :session-profile :full
                     :session-name-prefix "impl-"
                     :on-promote {:notion-status "In progress"}
                     :limits {:budget "45m" :max-failures 3}}
            run (runs/create-run! {:project :brian :trigger trigger
                                   :payload {:id "BR-4659" :title "firefox loading"
                                             :notion-page-id "PG1"}
                                   :priority 0 :session-profile :full :uncapped? false}
                                  {:fired-at "t" :fired-by "u"})]
        (is (= "impl-br-4659" (:session-name run)) "prefix + slugged BR id, lower-cased")
        (is (= {:notion-status "In progress"} (:on-promote run)))
        (is (= "/plan-bug Plan firefox loading" (:first-message run)))))))

(deftest create-run-without-prefix-keeps-random-session-name
  (with-tmp
    (fn [_]
      (let [trigger {:name :triage-bug :source {:type :notion-view} :skill :triage-bug
                     :payload "Triage {{event/title}}"}
            run (runs/create-run! {:project :brian :trigger trigger
                                   :payload {:id "BR-1" :title "x"}
                                   :priority 0 :session-profile :lite :uncapped? false}
                                  {:fired-at "t" :fired-by "u"})]
        (is (re-matches #"run-brian-triage-bug-[0-9a-f]{8}" (:session-name run)))
        (is (nil? (:on-promote run)))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.runs-test`
Expected: FAIL — session-name is the random `run-…` form; `:on-promote` absent.

- [ ] **Step 3: Require `clojure.string`**

In `src/nido/coordinator/runs.clj` ns `:require`, add:

```clojure
   [clojure.string :as str]
```

- [ ] **Step 4: Add `:on-promote` to the `Run` schema**

In the `Run` `[:map {:closed true} …]`, add (after `:session-profile`):

```clojure
   [:on-promote        {:optional true} [:maybe [:map-of keyword? any?]]]
```

(Optional + maybe ⇒ legacy on-disk Runs without the key still validate on write-back; no normalization needed.)

- [ ] **Step 5: Derive ticket-stable session-name + snapshot `:on-promote` in `create-run!`**

Add this private helper above `create-run!`:

```clojure
(defn- ticket-session-name
  "When the trigger sets :session-name-prefix and the payload carries an :id
   (e.g. a BR-#### from promote), build a stable, slugged session-name like
   \"impl-br-4659\". Otherwise fall back to the random per-run name."
  [trigger payload random-name]
  (if-let [prefix (:session-name-prefix trigger)]
    (if-let [id (:id payload)]
      (str prefix (-> (str id) str/lower-case (str/replace #"[^a-z0-9]+" "-")))
      random-name)
    random-name))
```

In `create-run!`, change the `let` binding for the run parts and the `:session-name`/add `:on-promote`. Replace:

```clojure
  (let [{:keys [run-id session-name]} (new-run-parts project (:name trigger))
```

with:

```clojure
  (let [{:keys [run-id session-name]} (new-run-parts project (:name trigger))
        session-name (ticket-session-name trigger payload session-name)
```

and in the `run` map, change the `:session-name` line to use this bound value (it already reads `:session-name session-name`, so no change there) and add immediately after `:session-profile`:

```clojure
                 :on-promote      (:on-promote trigger)
```

- [ ] **Step 6: Run test to verify it passes**

Run: `bb nido:test :only nido.coordinator.runs-test`
Expected: PASS (existing + 2 new). If any existing runs-test asserts the exact `:session-name` shape for non-prefixed triggers, confirm it still matches `run-…` (it should — prefix is absent there).

- [ ] **Step 7: Commit**

```bash
git add src/nido/coordinator/runs.clj test/nido/coordinator/runs_test.clj
git commit -m "feat(coordinator/runs): ticket-stable session-name + snapshot :on-promote"
```

---

## Task 6: review bridge — handle `:plan-bug` parking & sweeping

**Files:**
- Modify: `src/nido/coordinator/review.clj` (`run-state-from-ticket`, `in-review?`, `sweep-resolved!`)
- Test: `test/nido/coordinator/review_test.clj` (append + adjust helper)

- [ ] **Step 1: Write the failing test**

Append to `test/nido/coordinator/review_test.clj`. The file's `mk-run` hardcodes `:skill`; it already takes a `skill` arg in the latest version shown — pass `:plan-bug`:

```clojure
(deftest run-state-from-ticket-maps-planning-parked
  (is (= :awaiting-review (review/run-state-from-ticket :planning))
      "a clean plan exit while :planning ⇒ parked, not dropped"))

(deftest sweep-resolves-parked-plan-runs-too
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-9" {:notion-page-id "p" :url "u" :title "T"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (tickets/complete! :brian "BR-9" :triaged :applied)   ; triage done; plan resolved
      (mk-run "plan-9" :awaiting-review {:id "BR-9"} :plan-bug)
      (is (= 1 (review/sweep-resolved!)))
      (is (= :done (:state (runs/read-run "plan-9")))))))

(deftest sweep-leaves-parked-plan-run-still-in-review
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-10" {:notion-page-id "p" :url "u" :title "T"
                                     :opened-by :triage-new :notion-last-edited-at "t"})
      (tickets/set-status! :brian "BR-10" :planning)        ; still being planned
      (mk-run "plan-10" :awaiting-review {:id "BR-10"} :plan-bug)
      (is (= 0 (review/sweep-resolved!)))
      (is (= :awaiting-review (:state (runs/read-run "plan-10")))))))
```

If the local `mk-run` does **not** already accept a `skill` parameter, update its signature to `(defn- mk-run [id state payload skill] …)` and pass `skill` into the `:skill` field (the version in the repo at review_test.clj already does this).

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.review-test`
Expected: FAIL — `:planning` not mapped; sweep filters only `:triage-bug`.

- [ ] **Step 3: Generalize `run-state-from-ticket`**

```clojure
(defn run-state-from-ticket
  [ticket-status]
  (case ticket-status
    (:awaiting-input :investigating :planning) :awaiting-review
    :done))
```

(Update its docstring's mapping list to include `:planning → :awaiting-review`.)

- [ ] **Step 4: Generalize `in-review?` and `sweep-resolved!`**

`in-review?` — add `:planning` to the active set:

```clojure
(defn- in-review?
  [project br-id]
  (and (some? br-id)
       (contains? #{:investigating :awaiting-input :planning}
                  (tickets/status project br-id))))
```

`sweep-resolved!` — accept both skills:

```clojure
       (filter #(and (= :awaiting-review (:state %))
                     (#{:triage-bug :plan-bug} (:skill %))))
```

- [ ] **Step 5: Run test to verify it passes**

Run: `bb nido:test :only nido.coordinator.review-test`
Expected: PASS (existing + 3 new).

- [ ] **Step 6: Commit**

```bash
git add src/nido/coordinator/review.clj test/nido/coordinator/review_test.clj
git commit -m "feat(coordinator/review): plan-bug parking + sweep handling"
```

---

## Task 7: run driver — plan parked-state + notify-on-spawn

**Files:**
- Modify: `src/nido/coordinator/core.clj` (ns require `notify`; `run-blocking!`)
- Test: `test/nido/coordinator/core_test.clj` (append a focused test if the file has an existing `run-blocking!`/spawn-stubbing pattern; otherwise rely on the lower-level tests + a manual smoke). See Step 1.

- [ ] **Step 1: Write the failing test**

`test/nido/coordinator/core_test.clj` already drives `run-blocking!` with spawn/launch stubbed (see `run-blocking-parks-triage-run-from-ticket-status` and its `gate-with-tmp` fixture). Add the `notify` require to that test ns (`[nido.coordinator.notify :as notify]`), then append a plan-bug analog right after it:

```clojure
(deftest run-blocking-plan-bug-notifies-then-parks-from-ticket
  (gate-with-tmp
    (fn [_]
      (let [notified (atom nil)]
        (tickets/open! :brian "BR-12" {:notion-page-id "PG12" :url "u" :title "T"
                                       :opened-by :triage-new :notion-last-edited-at "t"})
        (tickets/complete! :brian "BR-12" :triaged :applied)
        (tickets/set-status! :brian "BR-12" :awaiting-input)   ; plan skill parked
        (runs/write-run! {:id "rplan" :project :brian :trigger :plan-bug
                          :source {:type :manual} :event-payload {:id "BR-12" :notion-page-id "PG12"}
                          :skill :plan-bug :first-message "/plan-bug x" :agent :claude
                          :session-name "impl-br-12" :claude-session-id nil :limits {}
                          :priority 0 :session-profile :full :uncapped? false
                          :on-promote {:notion-status "In progress"}
                          :state :queued :state-history [{:at "t" :state :queued}]
                          :artifacts [] :error nil})
        (with-redefs [runs/spawn-session-for-run! (fn [_] nil)
                      nido.coordinator.agent/launch! (fn [_] {:exit-code 0 :timed-out? false})
                      cstate/run-session-home-link (constantly "/tmp/nope")
                      notify/on-plan-spawn! (fn [run] (reset! notified (:id run)))
                      breakers/record-success! (fn [& _] nil)]
          (#'core/run-blocking! "rplan")
          (is (= "rplan" @notified) "notify/on-plan-spawn! fires for plan-bug runs")
          (is (= :awaiting-review (:state (runs/read-run "rplan")))
              "clean exit + ticket :awaiting-input ⇒ plan run parks, not :done"))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.core-test`
Expected: FAIL — `run-blocking!` neither calls `notify/on-plan-spawn!` nor derives the parked state for `:plan-bug` (it falls into the `status-file` branch → `:done`/`:failed`).

- [ ] **Step 3: Require `notify` in core**

In `src/nido/coordinator/core.clj` ns `:require`, add:

```clojure
   [nido.coordinator.notify :as notify]
```

- [ ] **Step 4: Notify on plan spawn**

In `run-blocking!`, inside the `result (try …)` form, insert the notify between spawn and launch:

```clojure
        result       (try
                       (runs/spawn-session-for-run! run)
                       (when (= :plan-bug (:skill run))
                         (notify/on-plan-spawn! run))
                       (agent/launch! {:run-id            run-id
                                       :cwd               (cstate/run-session-home-link run-id)
                                       :first-message     (:first-message run)
                                       :system-prompt     (:system-prompt defaults)
                                       :budget            (-> run :limits :budget)
                                       :claude-session-id session-id})
                       (catch Throwable t
                         {:spawn-error true :detail (.getMessage t)}))
```

- [ ] **Step 5: Derive parked state for `:plan-bug` from the ticket**

In the same `run-blocking!`, change the clean-exit branch of `next-state` to treat plan like triage:

```clojure
                     (zero? (:exit-code result))
                     (if (#{:triage-bug :plan-bug} (:skill run))
                       (review/run-state-from-ticket
                         (tickets/status (:project run) (some-> run :event-payload :id)))
                       (status-file/derive-state-after-exit
                         (status-file/read-status run-id)))
```

(`on-run-terminal!` is already called below and now handles `:plan-bug` from Task 3 — no further change there.)

- [ ] **Step 6: Verify — unit + manual integration smoke**

Run the full suite: `bb nido:test`
Expected: PASS.

Manual integration smoke (no real Notion/claude needed if you stub, but the end-to-end path is worth one live dry check once the brian `:plan-bug` trigger exists — see Task 8/9):
- `bb nido:ticket:show :project brian :br <a-triaged-BR>` shows `:status :triaged`.
- `bb nido:ticket:promote :project brian :br <that-BR>` prints `promoted … → queued …` and `ticket:show` now reads `:status :planning`.
- With the daemon running, `bb nido:runs:list` shows a new `:plan-bug` run; its session-home is `impl-<br>`.

- [ ] **Step 7: Commit**

```bash
git add src/nido/coordinator/core.clj test/nido/coordinator/core_test.clj
git commit -m "feat(coordinator): drive plan-bug runs — notify on spawn, park from ticket"
```

---

## Task 8: `promote` action namespace

**Files:**
- Create: `src/nido/coordinator/promote.clj`
- Test: `test/nido/coordinator/promote_test.clj` (create)

- [ ] **Step 1: Write the failing test**

Create `test/nido/coordinator/promote_test.clj`:

```clojure
(ns nido.coordinator.promote-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.promote :as promote]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(defn- queued-envelopes []
  (->> (fs/list-dir (cstate/queue-dir))
       (filter #(re-matches #".*\.edn$" (str (fs/file-name %))))
       (map #(edn/read-string (slurp (str %))))))

(deftest promote-refuses-non-triaged
  (with-tmp
    (fn [_]
      (is (= {:decision :skip-no-record} (promote/promote! :brian "BR-NONE")))
      (is (empty? (queued-envelopes))))))

(deftest promote-enqueues-direct-target-and-marks-planning
  (with-tmp
    (fn [_]
      (tickets/open! :brian "BR-7" {:notion-page-id "PG7" :url "U7" :title "T7"
                                    :opened-by :triage-new :notion-last-edited-at "t"})
      (tickets/complete! :brian "BR-7" :triaged :applied)
      (let [res (promote/promote! :brian "BR-7")]
        (is (= :promote (:decision res)))
        (is (string? (:queued res)))
        (is (= :planning (tickets/status :brian "BR-7")) "gate flips to :planning")
        (let [[env] (queued-envelopes)]
          (is (= {:project :brian :trigger :plan-bug} (:target env)))
          (is (= "BR-7"  (-> env :payload :id)))
          (is (= "PG7"   (-> env :payload :notion-page-id)))
          (is (= "T7"    (-> env :payload :title)))))
      ;; second promote now refused (already :planning) and enqueues nothing more
      (is (= {:decision :skip-active} (promote/promote! :brian "BR-7")))
      (is (= 1 (count (queued-envelopes)))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only nido.coordinator.promote-test`
Expected: FAIL — namespace not found.

- [ ] **Step 3: Create the namespace**

Create `src/nido/coordinator/promote.clj`:

```clojure
(ns nido.coordinator.promote
  "The shared promote action: gate a triaged ticket, mark it :planning, and
   enqueue a direct-target envelope for the project's :plan-bug trigger. Used by
   the `bb nido:ticket:promote` task and — via the identical envelope shape — by
   the triage skill's in-chat `promote` command. See spec §The promote gesture."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]))

(defn- latest-entry-of-kind
  "Absolute path to the most recent ledger entry of `kind`, or nil."
  [project br-id kind]
  (when-let [m (tickets/read-meta project br-id)]
    (when-let [e (->> (:entries m) (filter #(= kind (:kind %))) last)]
      (str (fs/path (tickets/ticket-dir project br-id) (:file e))))))

(defn promote!
  "Attempt to promote a ticket to a planning Run.
   Returns {:decision <kw>}; on :promote also {:queued <envelope-path>}.
   Side effects on :promote only: sets status :planning, enqueues the envelope."
  [project br-id]
  (let [decision (tickets/promote-decision project br-id)]
    (if (not= :promote decision)
      {:decision decision}
      (let [m       (tickets/read-meta project br-id)
            payload {:id             br-id
                     :notion-page-id (:notion-page-id m)
                     :url            (:url m)
                     :title          (:title m)
                     :report-path    (latest-entry-of-kind project br-id :triage)}]
        (cstate/ensure-dirs!)
        (tickets/set-status! project br-id :planning)
        {:decision :promote
         :queued   (queue/enqueue! {:target  {:project project :trigger :plan-bug}
                                    :payload payload})}))))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb nido:test :only nido.coordinator.promote-test`
Expected: PASS (3 deftests).

- [ ] **Step 5: Commit**

```bash
git add src/nido/coordinator/promote.clj test/nido/coordinator/promote_test.clj
git commit -m "feat(coordinator): promote! — gate, mark :planning, enqueue plan-bug envelope"
```

---

## Task 9: `bb nido:ticket:promote` task

**Files:**
- Modify: `src/tasks/nido_ticket.clj` (require `promote`; add `promote-cmd`)
- Modify: `bb.edn` (register `nido:ticket:promote`)
- Test: `test/tasks/nido_ticket_promote_test.clj` (create)

- [ ] **Step 1: Write the failing test**

Create `test/tasks/nido_ticket_promote_test.clj`:

```clojure
(ns tasks.nido-ticket-promote-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.coordinator.promote :as promote]
   [tasks.nido-ticket :as nido-ticket]))

(deftest promote-cmd-passes-project-and-br-and-prints
  (let [seen (atom nil)
        out  (with-out-str
               (with-redefs [promote/promote! (fn [p br] (reset! seen [p br])
                                                {:decision :promote :queued "/q/abc.edn"})]
                 (nido-ticket/promote-cmd ":project" "brian" ":br" "BR-7")))]
    (is (= [:brian "BR-7"] @seen))
    (is (re-find #"promoted BR-7" out))))

(deftest promote-cmd-accepts-positional-br
  (let [seen (atom nil)]
    (with-redefs [promote/promote! (fn [p br] (reset! seen [p br])
                                     {:decision :promote :queued "/q/x"})]
      (with-out-str (nido-ticket/promote-cmd ":project" "brian" "BR-9")))
    (is (= [:brian "BR-9"] @seen))))
```

(Note: the refusal path calls `System/exit`, which would kill the test JVM — do **not** test the refusal branch here; it's covered at the `promote!` layer in Task 8.)

- [ ] **Step 2: Run test to verify it fails**

Run: `bb nido:test :only tasks.nido-ticket-promote-test`
Expected: FAIL — `promote-cmd` unresolved.

- [ ] **Step 3: Add the require + command**

In `src/tasks/nido_ticket.clj` ns `:require`, add:

```clojure
   [nido.coordinator.promote :as promote]
```

Append `promote-cmd`:

```clojure
(defn promote-cmd
  "bb nido:ticket:promote :project <p> :br BR-#### (or positional BR-####)
   Gate a triaged ticket → enqueue a :plan-bug planning Run. Exits non-zero
   (and enqueues nothing) when the ticket isn't promotable."
  [& args]
  (let [[pos o] (task-args/split-args args)
        br      (str (or (:br o) (first pos)))
        res     (promote/promote! (project-kw o) br)]
    (case (:decision res)
      :promote (println "promoted" br "→ queued" (:queued res))
      (do (println "refused" br "—" (name (:decision res)))
          (System/exit 3)))))
```

- [ ] **Step 4: Register the task in `bb.edn`**

After the `nido:ticket:show` task entry (around `bb.edn:271`), add:

```clojure
  nido:ticket:promote
  {:doc "Promote a :triaged ticket → enqueue a :plan-bug planning Run."
   :task (apply nido-ticket/promote-cmd *command-line-args*)}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `bb nido:test :only tasks.nido-ticket-promote-test`
Expected: PASS.

- [ ] **Step 6: Verify the task is wired**

Run: `bb tasks | grep promote`
Expected: shows `nido:ticket:promote`.

- [ ] **Step 7: Commit**

```bash
git add src/tasks/nido_ticket.clj bb.edn test/tasks/nido_ticket_promote_test.clj
git commit -m "feat(tasks): bb nido:ticket:promote entry point"
```

---

## Task 10: brian `:plan-bug` trigger config

**Files:**
- Modify: `~/.nido/projects/brian/triggers.edn` (runtime config, **not** in the nido repo — do not git-add)

- [ ] **Step 1: Add the trigger**

Edit `~/.nido/projects/brian/triggers.edn` and add this entry to the `:triggers` vector:

```clojure
  ;; Source-less: fired ONLY by promote (bb nido:ticket:promote or the triage
  ;; skill's in-chat `promote`). No :source poller exists for :manual, so this
  ;; trigger is default-off by construction — nothing brings it to life unbidden.
  {:name                :plan-bug
   :source              {:type :manual}
   :skill               :plan-bug
   :session-profile     :full
   :session-name-prefix "impl-"
   :on-promote          {:notion-status "In progress"}
   :max-in-flight       3
   :payload             "Plan implementation of {{event/title}} ({{event/id}}). Triage report: {{event/report-path}}. Notion: {{event/url}}"
   :limits              {:budget "45m" :max-failures 3}}
```

- [ ] **Step 2: Verify it loads**

Run: `bb nido:trigger:list :project brian`
Expected: `:plan-bug` appears with no schema warning on stderr. (`load-for-project` validates against the `Trigger` schema extended in Task 4 — a warning here means Task 4 didn't land.)

`:full` is already defined in `~/.nido/projects/brian/session-profiles.edn`, so no profile work is needed.

- [ ] **Step 3: (No commit — runtime config.)** Note the change in the PR description / handoff instead.

---

## Task 11: Full-suite green + end-to-end dry run

**Files:** none (verification task)

- [ ] **Step 1: Run the entire test suite**

Run: `bb nido:test`
Expected: PASS — no failures, no errors.

- [ ] **Step 2: End-to-end dry run against a real triaged ticket**

Pick a ticket the triage pipeline already left `:triaged` (e.g. from `~/.nido/projects/brian/tickets/`). With the coordinator daemon up (`bb nido:coordinator:up`):

```
bb nido:ticket:show    :project brian :br BR-4659     # status :triaged
bb nido:ticket:promote :project brian :br BR-4659     # → queued …
bb nido:ticket:show    :project brian :br BR-4659     # status :planning
bb nido:runs:list                                     # a :plan-bug run appears
```

Expected: a `:full` session `impl-br-4659` comes up (worktree at `~/Code/brian-worktrees/impl-br-4659/`, PG+JVM+app), the Notion ticket flips to "In progress", and the run parks at `:awaiting-review` once the (pre-skill) agent exits. Without the `plan-bug` skill the `/plan-bug …` first-message is a no-op — that's expected until the brian dependency lands.

- [ ] **Step 3: Re-promote guard**

Run `bb nido:ticket:promote :project brian :br BR-4659` again.
Expected: `refused BR-4659 — skip-active`, non-zero exit, no second run.

---

## Cross-repo dependencies (brian — NOT implemented here)

These are contracts the nido mechanism expects. Build them in the brian repo and mirror the skill into `nido/.claude/` (the `triage-bug` pattern + `harness.edn`).

### A. `plan-bug` skill (brian)

Launched as `/plan-bug <payload>` in a `:full` session (live REPL/app/db). Contract:

1. Read the triage report from the durable ledger
   (`bb nido:ticket:show :project <p> :br <BR>` → `:entries`; the `:report-path`
   is also passed in the first-message).
2. Gather context; may exercise live services. **Do not modify code.**
3. Write the plan via the ledger:
   `bb nido:ticket:append :project <p> :br <BR> :kind plan :session <s> :run-id <r> :file <plan.md>`.
4. Park: set ticket status to `:awaiting-input`
   (`bb nido:ticket:status :project <p> :br <BR> :status awaiting-input`) and halt.

On a clean exit, the coordinator reads `:awaiting-input` and parks the Run at
`:awaiting-review` (Task 6/7). The human then resumes the warm session
(`claude --resume`) to review the plan and drive implementation.

### B. `triage-bug` in-chat `promote` (brian)

When the human types `promote` while reviewing a parked triage, the skill writes
the **same** direct-target envelope `promote!` produces, into
`~/.nido/coordinator/queue/<uuid>.edn`:

```clojure
{:target  {:project :brian :trigger :plan-bug}
 :payload {:id "BR-####" :notion-page-id "…" :url "…" :title "…" :report-path "…"}}
```

It must first confirm the gate (`bb nido:ticket:show` → status `:triaged`) and set
status `:planning` before/at enqueue, matching `promote!`. The simplest, drift-free
implementation is to shell out to `bb nido:ticket:promote :project <p> :br <BR>`
rather than hand-writing the envelope — then both front doors are literally one path.
