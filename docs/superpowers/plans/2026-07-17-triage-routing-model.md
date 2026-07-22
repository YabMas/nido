# Triage Routing Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce nido's automated intake to the Notion new-reports view, redefine the triage outcome as a routing decision (owner + area) whose depth depends on the owner, and retire `dismiss` for Notion tickets.

**Architecture:** A new `:routing` field on the typed `TriageReport` schema carries `{:owner :app-domain :depth}`. The triage-bug skill (a HITL markdown skill) decides routing and writes Ball Holder + App Domain to Notion, going deeper only for Jaap-owned reports. Config edits narrow the auto-triage triggers/board-views to new-reports. The TUI's `x` (dismiss) is gated off Notion rows; the web board already omits it there.

**Tech Stack:** Babashka/Clojure, Malli schemas, the `notion` CLI (`page set` + `api PATCH`), EDN config files, a markdown skill file.

## Global Constraints

- **Owner → Notion user id (people property "Ball Holder"):** Ataberk Koroglu `3eb98667-d12e-4e9e-9342-48fec803b571`; Eric Dvorsak `955b4c25-7bce-4ca2-ab5e-d99acbcd423a`; Jaap Maaskant `169d872b-594c-8160-b432-000250f98e86`.
- **App Domain (multi_select) options are exactly:** `Student`, `Teacher`, `Backend`, `Misc`.
- **Routing table:** Student env / mobile app → Ataberk (Student, shallow). Auth/login/SSO/Entra/Canvas/external-integration/large-architecture → Eric (Backend, shallow). Teacher env → Jaap (Teacher, deep). General backend not in Eric's specialty → Jaap (Backend, deep). When unsure of the Backend split → Jaap, and say so in chat.
- **Depth:** shallow = Ball Holder + App Domain only, Notion Status stays `"Needs verification"`, no directions/effort/enrichment, no unverifiable theories. deep = directions + effort (or `:squirrel`) + enriched title/description + Status `"Needs verification"` → `"Not started"`.
- **HITL for every route** — the skill proposes and halts at `:awaiting-input`; the human acks with `apply` before any Notion write.
- **Every new report routes to a human owner.** Not-a-bug / needs-info routes to Jaap; nothing is silently hidden. There is **no `dismiss` for Notion tickets** (dismiss stays Slack-only, ledger-only).
- **App Domain is written additively** (union with existing tags); **Ball Holder replaces** (single owner).
- The owner→id map lives in the **skill** (it owns the Notion write); the typed report carries only the semantic `:owner` keyword.
- Load-check any changed source namespace before commit: `bb -e "(require 'the.ns :reload)"`.

---

### Task 1: Routing in the TriageReport schema + renderer

**Files:**
- Modify: `src/nido/coordinator/report.clj` (schema defs near lines 11–39; `TriageReport` at 41–56; `triage->markdown` at 207–232)
- Test: `test/nido/coordinator/report_test.clj`
- Modify (fixtures that validate a `:triage` report through the ledger): `test/nido/work_test.clj:444-449` and `:594-599`

**Interfaces:**
- Produces: `report/Routing` schema; `TriageReport` gains `[:routing [:maybe Routing]]` (nil for Slack/legacy). `:owner` ∈ `#{:ataberk :eric :jaap}`; `:app-domain` ∈ `#{"Student" "Teacher" "Backend" "Misc"}`; `:depth` ∈ `#{:shallow :deep}`. Task 4 (skill) emits exactly this shape.
- Note: `:directions` stays `[:vector Direction]` — an empty `[]` already validates, so a shallow report emits `:directions []` with no schema change. `NotionWrites` is **unchanged** — a shallow report emits `:notion-writes nil`.

- [ ] **Step 1: Write the failing schema tests**

Add to `test/nido/coordinator/report_test.clj`. First extend the existing `valid-report` fixture (lines 7–20) by adding a `:routing` entry — insert this line right after `:confidence {...}` (line 13):

```clojure
   :routing {:owner :jaap :app-domain "Teacher" :depth :deep}
```

Then add these new defs + tests at the end of the file:

```clojure
(def ^:private shallow-report
  {:format :triage-report :ticket-key "BR-8" :determination :needs-info
   :title "Login loops on SSO" :summary "Looks like auth — Eric's area; not investigated."
   :confidence {:level :low :reason "routed without root-causing"}
   :routing {:owner :eric :app-domain "Backend" :depth :shallow}
   :directions [] :notion-writes nil :trail []})

(deftest validate-accepts-a-shallow-routed-report
  (is (= shallow-report (report/validate shallow-report))))

(deftest validate-accepts-nil-routing-for-slack
  (is (report/validate (assoc valid-report :routing nil :notion-writes nil :directions []))))

(deftest validate-rejects-bad-owner
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate (assoc-in valid-report [:routing :owner] :bob)))))

(deftest validate-rejects-bad-app-domain
  (is (thrown? clojure.lang.ExceptionInfo
               (report/validate (assoc-in valid-report [:routing :app-domain] "Mobile")))))

(deftest report->markdown-deep-shows-routing
  (is (str/includes? (report/report->markdown valid-report) "Routing:")))

(deftest report->markdown-shallow-has-routing-no-directions-no-writes
  (let [md (report/report->markdown shallow-report)]
    (is (str/includes? md "Routing:"))
    (is (str/includes? md "Eric"))
    (is (str/includes? md "Backend"))
    (is (not (str/includes? md "Solution directions")) "shallow omits the directions section")
    (is (not (str/includes? md "Proposed Notion writes")) "shallow has no notion-writes")))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bb test :nido.coordinator.report-test`
Expected: FAIL — `valid-report` now has a `:routing` key the closed schema rejects (`report/validate` throws), and `shallow-report`/routing tests reference behavior not yet built.

- [ ] **Step 3: Add the schema defs**

In `src/nido/coordinator/report.clj`, after the `Direction` def (line 30) and before `NotionWrites` (line 32), add:

```clojure
(def AppDomain
  "Notion App Domain multi_select value used for routing."
  [:enum "Student" "Teacher" "Backend" "Misc"])

(def Owner
  "Semantic triage owner; the skill maps it to a Notion Ball Holder user-id."
  [:enum :ataberk :eric :jaap])

(def Routing
  "Where triage routed the report. nil on a TriageReport means no routing (Slack,
   or a legacy pre-routing report). :depth :shallow = Ball Holder + App Domain only
   (Notion status stays Needs verification); :deep = full triage (status → Not started)."
  [:map {:closed true}
   [:owner      Owner]
   [:app-domain AppDomain]
   [:depth      [:enum :shallow :deep]]])
```

- [ ] **Step 4: Add `:routing` to the `TriageReport` schema**

In the `TriageReport` def, add the `:routing` entry immediately after the `:confidence` line (currently line 50):

```clojure
   [:routing       [:maybe Routing]] ; §2 — nil for Slack / legacy
```

- [ ] **Step 5: Update the renderer to show routing + guard the directions section**

Replace `triage->markdown` (lines 207–232) with:

```clojure
(def ^:private owner-display
  {:ataberk "Ataberk" :eric "Eric" :jaap "Jaap"})

(defn- triage->markdown [{:keys [title determination summary confidence
                                 routing directions notion-writes trail]}]
  (str/join
   "\n"
   (concat
    [(str "# Triage: " title)
     (str "**Determination:** " (name determination)
          "  ·  **Confidence:** " (name (:level confidence)) " — " (:reason confidence))]
    (when routing
      [(str "**Routing:** " (owner-display (:owner routing) (name (:owner routing)))
            " · " (:app-domain routing) " · " (name (:depth routing)))])
    ["" summary ""]
    (when (seq directions)
      (cons "## Solution directions"
            (for [{:keys [label shape effort confidence]} directions]
              (str "- **" label "** — " shape ". Effort: " (name effort)
                   ". Confidence: " (name (:level confidence)) " — " (:reason confidence)))))
    (when notion-writes
      (remove nil?
              ["" "## Proposed Notion writes (on `apply`)"
               (str "- Type: " (or (:type notion-writes) "unchanged"))
               (str "- Effort: " (name (:effort notion-writes)))
               (when-let [[from to] (:status-transition notion-writes)]
                 (str "- Status: `" from "` → `" to "`"))
               (str "- Title: " (:title notion-writes))]))
    ["" "## Investigation trail"]
    (for [{:keys [ref note]} trail]
      (str "- `" ref "` — " note)))))
```

- [ ] **Step 6: Fix the two cross-file fixtures that validate a triage report**

The closed schema now requires the `:routing` key on every `TriageReport`. Two fixtures in `test/nido/work_test.clj` are appended through the validating ledger, so add `:routing nil` to each.

In `slack-edn-report` (line 446–449) change the map to include `:routing nil` — insert it right after `:confidence {:level :high :reason "r"}`:

```clojure
(def ^:private slack-edn-report
  "Valid TriageReport EDN for slack triage ledger tests."
  (pr-str {:format :triage-report :ticket-key "slack-C1-1.0" :determination :bug
           :title "Verdict" :summary "bug — slack report."
           :confidence {:level :high :reason "r"}
           :routing nil
           :directions [] :notion-writes nil :trail []}))
```

In `notion-edn-report` (line 594–599) do the same:

```clojure
(def ^:private notion-edn-report
  "Valid TriageReport EDN for notion triage ledger tests."
  (pr-str {:format :triage-report :ticket-key "BR-9" :determination :bug
           :title "Verdict" :summary "ticket-ledger report."
           :confidence {:level :high :reason "r"}
           :routing nil
           :directions [] :notion-writes nil :trail []}))
```

(The `views_test.clj` triage fixture at line 39 carries an `:at` key and is a render-only fixture — never validated — and the renderer tolerates a missing `:routing`, so it needs no change.)

- [ ] **Step 7: Run the tests to verify they pass**

Run: `bb -e "(require 'nido.coordinator.report :reload)"` then `bb test :nido.coordinator.report-test` and `bb test :nido.work-test`
Expected: PASS for both. (Full suite runs in Task-review.)

- [ ] **Step 8: Commit**

```bash
jj describe -m "feat(report): triage routing — :routing on TriageReport + renderer"
```

(The controller runs all jj ops — see the ledger's VCS section. Implementer: stop here, report the files touched and test output.)

---

### Task 2: Reduce automated intake to new-reports only

**Files:**
- Modify: `~/.nido/projects/brian/triggers.edn` (remove the `:triage-teacher-bugs` trigger map)
- Modify: `~/.nido/projects/brian/notion-views.edn` (`:board-views`)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: no code symbols. This is config; the coordinator daemon reads it at startup (restart is a landing-time operational step, not part of this task).

- [ ] **Step 1: Remove the `:triage-teacher-bugs` trigger**

In `~/.nido/projects/brian/triggers.edn`, delete the entire `{:name :triage-teacher-bugs …}` map (the trigger whose `:source` is `{:type :notion-view :view :teacher-bugs …}`, including its leading `:reconcile? true` / `:max-in-flight 5` comment block). Leave `:triage-new`, `:smoke-new-reports`, `:triage-slack-reactions`, `:video-provider-alerts`, `:plan-bug`, `:plan-github-issue` intact.

- [ ] **Step 2: Narrow `:board-views` to new-reports**

In `~/.nido/projects/brian/notion-views.edn`, change:

```clojure
 :board-views [:teacher-bugs :new-reports]
```

to:

```clojure
 :board-views [:new-reports]
```

Leave the `:views` map (including the `:teacher-bugs` view definition, still used elsewhere) unchanged.

- [ ] **Step 3: Verify both files still parse and the trigger is gone**

Run:

```bash
bb -e '(let [t (clojure.edn/read-string (slurp (str (System/getenv "HOME") "/.nido/projects/brian/triggers.edn")))
             v (clojure.edn/read-string (slurp (str (System/getenv "HOME") "/.nido/projects/brian/notion-views.edn")))]
         (println :triggers (mapv :name (:triggers t)))
         (println :board-views (:board-views v)))'
```

Expected: the `:triggers` vector does **not** contain `:triage-teacher-bugs`, and `:board-views` prints `[:new-reports]`.

- [ ] **Step 4: Commit**

```bash
jj describe -m "feat(intake): auto-triage only new-reports; drop teacher-bugs trigger + board-view"
```

(These files live under `~/.nido`, outside the repo working tree — they are not part of any jj commit. The `jj describe` records the code-arc intent; the config edits are applied in place. Implementer: stop here and report the before/after of both files.)

> **NOTE for the controller:** `~/.nido/projects/brian/*.edn` are NOT in the git/jj tree — Step 4's `jj describe` will have nothing to stage from these paths. Treat Task 2 as a config-only change: verify via Step 3's output, and record it in the ledger. Do not expect a file diff in `jj st`. If desired, back up the originals first (`cp triggers.edn triggers.edn.bak-routing`) as the repo does for other trigger edits.

---

### Task 3: Retire `dismiss` for Notion rows (TUI gate + board docstring)

**Files:**
- Modify: `src/nido/tui.clj` (`dismiss-selected`, lines 1010–1024; the keybar hint at ~1556)
- Modify: `src/nido/work.clj` (`gate-actions` docstring/comment, lines 64–89 — no behavior change)
- Test: `test/nido/tui_test.clj`

**Interfaces:**
- Consumes: display rows carry `:origin` (`#{:notion :github :slack :scratch}`), already populated by `work/grouped`/`list-workstreams`.
- Produces: `dismiss-selected` no longer calls `work/dismiss!` when the selected row's `:origin` is `:notion`.

- [ ] **Step 1: Write the failing test**

Add to `test/nido/tui_test.clj`, after `board-x-dismisses-selected-workstream` (line 239):

```clojure
(deftest board-x-noop-on-notion-row
  (let [calls (atom [])]
    (with-redefs [nido.tui/selected-workstream (fn [_] {:ws-id "w1" :br-id "BR-1" :origin :notion})
                  nido.work/dismiss! (fn [p id] (swap! calls conj [p id]) {:decision :dismissed})
                  nido.tui/current-rows (constantly [])]
      (let [[s' _] (#'tui/update-board (board-state :all) (msg/key-press "x"))]
        (is (= [] @calls) "x does NOT dismiss a Notion row — Notion owns it")
        (is (re-find #"Notion" (:status s')) "status explains why")))))
```

The existing `board-x-dismisses-selected-workstream` fixture has no `:origin` key (so `(:origin ws)` is nil, not `:notion`) — it must stay green, proving Slack/scratch/legacy rows still dismiss.

- [ ] **Step 2: Run the test to verify it fails**

Run: `bb test :nido.tui-test`
Expected: FAIL — `board-x-noop-on-notion-row` sees `work/dismiss!` called (`@calls` = `[["brian" "w1"]]`) because `dismiss-selected` doesn't yet gate on origin.

- [ ] **Step 3: Gate `dismiss-selected` on origin**

Replace `dismiss-selected` (lines 1010–1024) with:

```clojure
(defn- dismiss-selected
  "Take the highlighted workstream off the triage radar via work/dismiss! — it
   leaves the queue and is skipped by auto-re-triage. Dismiss is retired for Notion
   tickets: Notion owns their lifecycle, so a local dismiss is a no-op there and we
   refuse it honestly rather than pretend. A bare (workstream-less) row no-ops too —
   mirroring promote-selected's :no-workstream handling."
  [state]
  (if-let [ws (selected-workstream state)]
    (if (= :notion (:origin ws))
      [(assoc state :status "dismiss doesn't apply to Notion tickets — they're owned in Notion")
       nil]
      (let [decision (:decision (work/dismiss! (:project state) (:ws-id ws)))
            label    (or (:br-id ws) (:ws-id ws))]
        [(-> state (refresh-list (current-rows state))
             (assoc :status (if (= decision :no-workstream)
                               (str "no workstream yet — " label)
                               (str "dismissed " label " — off radar"))))
         nil]))
    [(assoc state :status "(no workstream selected)") nil]))
```

- [ ] **Step 4: Update the board keybar hint**

At line ~1556 the board hint string contains `[x] dismiss`. Change that token to `[x] dismiss (slack)` so the key's scope is honest:

```clojure
                    :board      "[↵/o] open  [i]nspect  [n]ew  [p]romote  [P] promote to…  [d]one  [x] dismiss (slack)  [space] fold  [⇄ tab] origin  [ [ ] ] domain  [ { } ] type  [s]ystem  [esc] back  [q]uit"
```

- [ ] **Step 5: Refresh the `gate-actions` docstring (no behavior change)**

In `src/nido/work.clj`, `gate-actions` already omits Dismiss for `:notion` triage rows. Update its docstring (lines 65–67) so the omission reads as a permanent, intentional decision rather than an incidental one. Replace:

```clojure
   `parked?`, and its `origin` (Dismiss is dropped for :notion triage rows — Notion
   drives the board, so a local dismiss no longer hides them; kept for Slack).
```

with:

```clojure
   `parked?`, and its `origin`. Dismiss is retired for :notion triage rows by design —
   Notion owns their lifecycle, so a local dismiss would be a no-op; it is kept only
   for Slack triage (ledger-only, where it works).
```

Leave the `:triage` case body unchanged.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `bb -e "(require 'nido.tui :reload) (require 'nido.work :reload)"` then `bb test :nido.tui-test` and `bb test :nido.work-test`
Expected: PASS — `board-x-noop-on-notion-row` and the unchanged `board-x-dismisses-selected-workstream` both green; `gate-actions-are-stage-derived` unchanged.

- [ ] **Step 7: Commit**

```bash
jj describe -m "feat(dismiss): retire dismiss for Notion rows — TUI gate + board docstring"
```

(Controller runs jj. Implementer: stop here and report.)

---

### Task 4: Rewrite the triage-bug skill for routing

**Files:**
- Modify: `.claude/skills/triage-bug/SKILL.md`

**Interfaces:**
- Consumes: the `Routing`/`TriageReport` shape from Task 1 (`{:owner :ataberk|:eric|:jaap :app-domain "Student"|"Teacher"|"Backend"|"Misc" :depth :shallow|:deep}`; shallow ⇒ `:directions []`, `:notion-writes nil`).
- Produces: no code. This is the skill's instructions. Verification is prose review + a consistency check against Task 1's schema.

This is a documentation task — no TDD. Make these edits, then verify by re-reading the whole file for internal consistency.

- [ ] **Step 1: Add a "Routing" section**

Insert a new `## Routing` section immediately before `## Lifecycle: ticket status`. Content:

```markdown
## Routing (Notion runs)

The triage of a Notion report is a **routing decision**: get it to the right owner.
Set the Notion **Ball Holder** (people, replace) and **App Domain** (multi_select,
additive) accordingly. The owner determines how deep this triage goes (see Depth below).

| Report is about… | Ball Holder (user-id) | App Domain | Depth |
|---|---|---|---|
| Student environment / mobile app | Ataberk Koroglu — `3eb98667-d12e-4e9e-9342-48fec803b571` | `Student` | shallow |
| Auth (backend), external integrations (Canvas, Entra, …), large architecture / design-pattern concerns | Eric Dvorsak — `955b4c25-7bce-4ca2-ab5e-d99acbcd423a` | `Backend` | shallow |
| Teacher environment | Jaap Maaskant — `169d872b-594c-8160-b432-000250f98e86` | `Teacher` | deep |
| General backend (not Eric's specialty) | Jaap Maaskant — `169d872b-594c-8160-b432-000250f98e86` | `Backend` | deep |

**The Backend split is the one real judgment.** Authentication / login / SSO / Entra /
Canvas / any external-system integration / large architectural or design-pattern
concern → **Eric**. Any other backend work → **Jaap**. When you genuinely can't tell
which side of the split a report falls on, route to **Jaap** and say so in chat.

**Every report gets an owner.** A report you read as not-a-bug / noise / needs-info is
NOT dismissed — route it to **Jaap** (Teacher or Backend as fits), keep the status at
"Needs verification", and let him disposition it in Notion. There is no ownerless
outcome and no `dismiss` for Notion tickets.

The report's `:routing` field carries the semantic owner keyword — `:ataberk`,
`:eric`, or `:jaap` — plus `:app-domain` and `:depth`. The user-ids above are only for
the Notion write in Step 4.
```

- [ ] **Step 2: Add a "Depth" subsection**

Right after the Routing table block above, add:

```markdown
### Depth: shallow vs deep

- **Shallow (routed to Ataberk or Eric).** Set Ball Holder + App Domain and **nothing
  else**. No solution directions, no effort, no enriched title/description, and **no
  theories you can't verify from the codebase**. Notion **Status stays "Needs
  verification"** — the owner picks it up from there. You may read the report and take a
  light look to *confirm the area*, but do not root-cause. Emit `:directions []` and
  `:notion-writes nil`; set `:routing {… :depth :shallow}`.
- **Deep (routed to Jaap).** The full triage: investigate the codebase, propose 1–3
  directions with effort (or `:squirrel`), enrich the title and prepend the
  enriched-description callout, and transition **Status "Needs verification" → "Not
  started"**. Emit the populated `:directions`, `:notion-writes` (as today), and
  `:routing {… :depth :deep}`.

Either way, HITL is unchanged: propose, halt at `:awaiting-input`, and wait for the
human to `apply`. Shallow routes still pass the human — every routing is acked.
```

- [ ] **Step 3: Rewrite the "Lifecycle: ticket status" framing**

In `## Lifecycle: ticket status`, replace the opening "**Two terminal outcomes, nothing in between.**" paragraph (the one describing on-radar `apply` vs off-radar `dismiss`) with:

```markdown
**One terminal outcome for a Notion report: `apply` (route it).** Every new report is
triaged to an owner (see Routing) — there is no `dismiss` for Notion tickets. `dismiss`
survives only for **Slack** runs (ledger-only, off the nido radar); a Notion run never
uses it. On `apply` the nido record is marked `:triaged`, which takes the ticket off
nido's Intake radar even though a shallow route leaves the Notion status at "Needs
verification".
```

In the status table just below, delete the `:dismissed` row for the Notion context (keep a one-line note that `:dismissed` remains a Slack-only status). Leave `:investigating`, `:awaiting-input`, `:triaged` rows intact.

- [ ] **Step 4: Update the Step 2 report schema block**

In `## Step 2 — Report schema (EDN)`, update the example EDN so it carries `:routing` and shows both depths. Add the `:routing` key right after `:confidence`:

```clojure
 :routing       {:owner :jaap        ; :ataberk | :eric | :jaap
                 :app-domain "Teacher" ; "Student" | "Teacher" | "Backend" | "Misc"
                 :depth :deep}         ; :shallow (Ataberk/Eric) | :deep (Jaap)
```

Add a note to the "Notes:" list:

```markdown
- **`:routing` is required for a Notion run** (nil only for Slack). A **shallow** route
  (Ataberk/Eric) emits `:directions []` and `:notion-writes nil` — routing is the whole
  outcome. A **deep** route (Jaap) populates `:directions` and `:notion-writes` as
  below. See Routing / Depth above.
```

- [ ] **Step 5: Update Step 4 (apply) with the routing writes**

In `## Step 4 — Apply`, before the existing "Property update" (the `notion page set` for Type/Effort/Status/Title), add a routing-write step that runs for **both** depths on a Notion run:

```markdown
### Routing write (both depths, Notion run)

Always set Ball Holder (replace) and App Domain (additive) from `:routing`. These are a
people + multi_select property, so use `notion api PATCH` (the `notion page set`
key=value form does not handle people). Map `:owner` → user-id via the Routing table.

1. Read the current App Domain so the write is additive:

   ```bash
   notion page view <page-id> --format json \
     | jq -c '[.page.properties["App Domain"].multi_select[].name] + ["<routed-domain>"] | unique | map({name: .})'
   ```

   Let `<domains-json>` be that array (e.g. `[{"name":"Backend"}]`).

2. PATCH Ball Holder + App Domain:

   ```bash
   notion api PATCH /v1/pages/<page-id> --body '{
     "properties": {
       "Ball Holder": {"people": [{"id": "<owner-user-id>"}]},
       "App Domain":  {"multi_select": <domains-json>}
     }
   }'
   ```

**Shallow route stops here** — no Type/Effort/Status/Title write, Status stays "Needs
verification". Record the routing write in `notion-mutations.log` and complete the
record (`bb nido:ticket:complete … :status triaged :disposition applied`).

**Deep route continues** to the Property update + enriched-callout prepend below
(unchanged), which sets Type/Effort/Status ("Needs verification" → "Not started")/Title.
```

Leave the existing "Property update" and callout-prepend instructions in place as the **deep** path. Update the hard-contract sentence at the end of Step 4 to permit the routing PATCH: the permitted `apply` writes are now the **Ball Holder + App Domain PATCH (both depths)**, plus the Type/Effort/Status/Title patch and the enriched-callout prepend (**deep only**).

- [ ] **Step 6: Remove `dismiss` from the Notion paths**

- In the `## Step 3 — Confirmation` verb table, mark `dismiss` as **Slack-only** (e.g. change its row to `dismiss` | *(Slack runs only)* Execute §5 — take the ticket off the radar (nido-only)). A Notion run has no `dismiss`.
- In `## Step 5` (dismiss), prepend a line: "**Slack runs only.** A Notion report is never dismissed — it is always routed to an owner (see Routing). For a Notion run this step does not apply." Keep the Slack `bb nido:ticket:dismiss …` instructions.
- In the `## When this fires` / trigger list, drop the stale `:triage-backlog` / `:triage-slack-bugs` references if present, and note the live Notion trigger is `:triage-new` (new-reports). (Do not invent triggers — match `~/.nido/projects/brian/triggers.edn`.)

- [ ] **Step 7: Verify the file for consistency**

Re-read the whole `SKILL.md`. Confirm: (a) the `:routing` EDN shape matches Task 1's schema exactly (`:owner` keyword ∈ {:ataberk :eric :jaap}, `:app-domain` string ∈ {"Student" "Teacher" "Backend" "Misc"}, `:depth` ∈ {:shallow :deep}); (b) shallow emits `:directions []` + `:notion-writes nil`; (c) no remaining instruction tells a Notion run to `dismiss`; (d) the owner→id map appears once (in Routing) and the ids match the Global Constraints. Fix any drift inline.

- [ ] **Step 8: Commit**

```bash
jj describe -m "feat(triage-bug): routing-based triage — depth by owner, retire Notion dismiss"
```

(Controller runs jj. Implementer: stop here and report which sections you changed.)

---

## Self-Review

**1. Spec coverage:**
- §1 Scope reduction → Task 2. ✅
- §2 Team + routing table → Global Constraints + Task 4 Routing section; ids in Task 1 tests + Task 4. ✅
- §3 Depth policy → Task 4 Depth subsection; schema support (`:depth`, empty directions, nil notion-writes) in Task 1. Radar-exit via `:triaged` is existing behavior (no code change needed — noted). ✅
- §4a schema → Task 1. §4b Notion writes → Task 4 Step 5. §4c system prompt → Task 4 Steps 1–6. ✅
- §5 dismiss retirement → Task 3 (TUI + board docstring) + Task 4 Step 6 (skill). Board already omits it — no behavior change, docstring only. ✅

**2. Placeholder scan:** No TBD/TODO. Every code step carries complete code; the skill steps carry the exact new prose. ✅

**3. Type consistency:** `:owner` keywords `:ataberk/:eric/:jaap` and `:app-domain` strings `"Student"/"Teacher"/"Backend"/"Misc"` and `:depth` `:shallow/:deep` are identical across Task 1 (schema + tests), Task 4 (skill), and the Global Constraints. `Routing` is `[:maybe …]` on `TriageReport`; shallow uses `:directions []` + `:notion-writes nil` (NotionWrites unchanged) consistently. User-ids match across Global Constraints, Task 1 fixtures (none embed ids — they use `:owner` keywords), and Task 4. ✅

## Notes for the controller (VCS)

- Root repo is **jj colocated**. The two planning docs (this plan + the spec) live in `docs/superpowers/` and MUST NOT land (per CLAUDE.md). Keep them in an undescribed `@` and land only the described code commits, exactly as the prior arc did.
- Task 2 edits files under `~/.nido/` — outside the repo tree. They are applied in place, verified by Step 3's output, and recorded in the ledger; they produce no jj diff.
- The controller runs all jj ops; implementers only edit files + run `bb test` / load-checks and report. Use `jj describe` / `jj new` boundaries per the subagent-driven ledger, and land with `jj bookmark set main -r @-` (never `@`).
