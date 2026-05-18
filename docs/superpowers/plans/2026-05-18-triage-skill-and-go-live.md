# Triage Skill, Triggers, and Go-Live — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **jj commit hygiene:** This is a jujutsu repo. In every commit step, run `jj log -r '@-..@' --no-graph` BEFORE editing files (if `@` has a description, `jj new` first), and AGAIN AFTER editing (confirm parent is the previous task's commit). Subagents have repeatedly mashed two tasks into one commit by skipping this check.

**Goal:** Ship the actual triage agent on top of the infrastructure from Plans A–C. Add the `Effort` Notion property; extend brian's view registry with `:new-reports` and `:bugs`; write the `/triage-bug` skill that investigates, halts for human review in the session chat, and applies the user's verdict to Notion; wire the two triage triggers in dry-run mode; ramp from dry-run to live once the agent's output looks trustworthy.

**Architecture:** The skill is markdown — claude executes it. Notion reads + writes happen via the `mcp__notionApi__*` MCP tools already available in any session with a notion MCP server. The skill instructs claude to halt at the `:awaiting-input` phase by writing the run-status file and exiting; the existing `claude --resume` mechanism (Plan A shim) restarts the same session with the user's chat reply, and the skill's resume branch parses the verdict and acts. Notion mutations are also logged to a single append-only file (`~/.nido/coordinator/notion-mutations.log`) by the skill itself.

**Tech Stack:** Markdown (the skill), Notion (manual property creation), `notion-views.edn` (config), `triggers.edn` (config). No new Clojure namespaces — all run-time behavior lives in the skill body, which calls MCP tools claude already has.

**Spec reference:** [2026-05-18-notion-triage-agent-design.md §Triage skill contract and §Wiring](../specs/2026-05-18-notion-triage-agent-design.md). This plan delivers Stages 4–6 of the six-stage rollout (combined per design discussion).

**Skill location decision:** The spec called for the skill to live at `~/Code/nido/.claude/skills/triage-bug/`. However, the lite session profile from Plan B symlinks the session-home's `.claude/` to `worktree/.claude/` (which for lite sessions = `~/Code/brian/.claude/`), so claude in the session would not see a skill that lives in nido. For v1, the skill lives at **`~/Code/brian/.claude/skills/triage-bug/SKILL.md`** with a header note that it's harness-side conceptually (will move when a non-brian project needs triage — the spec's "Cross-project triage" follow-up). When that move happens, the lite profile gains a `:claude-dir` override.

---

## File Structure

**New:**
- `~/Code/brian/.claude/skills/triage-bug/SKILL.md` — the skill body (single markdown file, no helper files in v1).
- `~/.nido/projects/brian/notion-views.edn` — updated with `:new-reports` and `:bugs` views (the file exists from Plan C; this plan adds entries).
- `~/.nido/projects/brian/triggers.edn` — gains two trigger entries (`:triage-new`, `:triage-backlog`).

**Modified:**
- (Notion DB) — add `Effort` select property. Manual step in the Notion UI.

**Untouched:** nido source code. All Plan D behavior is config + markdown.

---

## Task 1 — Notion: create the `Effort` select property

**Files:** none — this is a manual change in the Notion UI.

In the Task Database (id `124fca9f-403c-80d4-896f-fc857e105e35`):

- [ ] **Step 1: Add a new select-type property named `Effort`** with options `XS`, `S`, `M`, `L`, `XL` (in that order).

- [ ] **Step 2: Verify via API**

```
bb -e "(require '[nido.notion.client :as c]) (let [db (c/retrieve-database \"124fca9f-403c-80d4-896f-fc857e105e35\" (c/keychain-token))] (prn (-> db :properties (get \"Effort\") :select :options (->> (map :name)))))"
```

Expected output: `("XS" "S" "M" "L" "XL")` (in whatever order Notion returns them — the set should match).

If the property doesn't show up, double-check the property type is `Select` (not `Multi-select`, not `Status`).

- [ ] **Step 3: Not a commit step.** No code or repo file changed. Move on.

---

## Task 2 — Update brian's `notion-views.edn` with triage views

**Files:**
- Modify: `~/.nido/projects/brian/notion-views.edn`

Plan C added the `:open-bugs` view (to keep the smoke trigger working). This task adds the two views that the triage triggers will use.

- [ ] **Step 1: Read the current file**

```
cat ~/.nido/projects/brian/notion-views.edn
```

- [ ] **Step 2: Add the two triage views** (preserve the existing `:open-bugs` if present)

```clojure
{:database "124fca9f-403c-80d4-896f-fc857e105e35"
 :views
 {:open-bugs                                       ;; pre-existing — keep
  {:filter {:and [{:property "Type"   :select {:equals "bug"}}
                  {:property "Status" :status {:does_not_equal "Done"}}
                  {:property "Status" :status {:does_not_equal "Not Done"}}]}}

  :new-reports
  {:filter {:property "Status" :status {:equals "Needs verification"}}}

  :bugs
  {:filter {:and [{:property "Type"   :select {:equals "bug"}}
                  {:property "Status" :status {:does_not_equal "Done"}}
                  {:property "Status" :status {:does_not_equal "Not Done"}}
                  {:property "Status" :status {:does_not_equal "Needs verification"}}
                  {:property "Status" :status {:does_not_equal "Review"}}]}}}}
```

- [ ] **Step 3: Validate via the Plan C check tool**

```
bb nido:notion:views:check :project brian
```

Expected: "Registry check passed." If it reports a missing property/value, double-check the strings against the Notion DB.

- [ ] **Step 4: Not a code commit.** Runtime config file lives outside the repo.

---

## Task 3 — Write the `/triage-bug` skill

**Files:**
- Create: `~/Code/brian/.claude/skills/triage-bug/SKILL.md`

This is the bulk of Plan D. A single markdown file the claude agent reads when invoked as `/triage-bug`. The file is read by humans too — keep prose terse and instructions explicit.

- [ ] **Step 1: jj hygiene check** (skill file lives in the brian repo, NOT nido — switch context). For brian:

```
cd ~/Code/brian && jj log -r '@-..@' --no-graph
```

If `@` has a description, `jj new`. Confirm empty.

- [ ] **Step 2: Create the skill file**

```
mkdir -p ~/Code/brian/.claude/skills/triage-bug
```

Write `~/Code/brian/.claude/skills/triage-bug/SKILL.md`:

````markdown
---
name: triage-bug
description: Triage a Notion bug-report ticket. Investigates the brian codebase, drafts a structured report, halts for human review in the session chat, and applies the human-confirmed verdict to Notion.
---

# triage-bug skill

> Harness-side skill, lives in brian for v1 (lite session shape symlinks `.claude/` to `~/Code/brian/.claude/`). When a second project needs triage, this moves to nido and the lite profile grows a `:claude-dir` override.

## When this fires

The coordinator fires this skill when a Notion row matches one of the two triage triggers (`triage-new` for Status=Needs-verification, `triage-backlog` for untriaged bugs). The envelope payload includes:

- `:page-id` — Notion page id
- `:url` — Notion page URL
- `:title` — page title
- `:trigger-name` — `:triage-new` or `:triage-backlog`
- Other Notion properties flattened (see `nido.notion.client/normalise-page`).

The session is a lite session: the worktree is a symlink to `~/Code/brian` (read-only intent). No PG, no JVM.

## Lifecycle phases

Write the current phase to `./run-link/_run-status.edn`:

| Phase | Means |
|---|---|
| `:investigating` | Fetching page, walking codebase, drafting report |
| `:awaiting-input` | Draft in `./run-link/artifacts/triage-report.md`. Awaiting human reply in chat. |
| `:applying` | Writing Notion mutations + comment |
| `:complete` | Notion updated, run done |
| `:skipped` | Status transition + comment + (if `delete`) archived |
| `:error` | Notion untouched, run failed |

To write the phase file:

```bash
echo '{:phase :awaiting-input :at "<ISO>"}' > ./run-link/_run-status.edn
```

## Step 1 — Investigate (autonomous, no human in loop yet)

1. Set phase `:investigating`.
2. Read the envelope payload to get `:page-id`, `:url`, `:title`, `:trigger-name`.
3. **Dedup check:** use `mcp__notionApi__API-retrieve-a-comment` (or list comments via the page-retrieve) on the page. If any existing comment starts with `🤖 Triaged automatically via nido`, set phase `:complete` and exit cleanly — this row was already triaged.
4. Fetch the full Notion page:
   - `mcp__notionApi__API-retrieve-a-page` for properties.
   - `mcp__notionApi__API-get-block-children` for the body blocks (description text).
5. Capture `last_edited_time` from the page response — needed for optimistic concurrency at apply time.
6. Investigate the brian codebase at `./worktree` (read-only — do not edit). Strategy:
   - Heuristic grep for module/feature names from the title and description body.
   - Read 2–5 relevant files, ~500 lines total cap. Don't go deeper than needed.
   - Stop early when the area is obvious.
   - If the report mentions a stack trace or error message, grep for it verbatim first.
7. Determine:
   - Is this actually a bug? (vs feature request, vs duplicate, vs noise)
   - If a bug: what part of the system is affected? what's the likely root cause?
   - What are 1–3 candidate solution directions? T-shirt effort per direction.
8. Write the report to `./run-link/artifacts/triage-report.md` (template below).
9. Set phase `:awaiting-input` and exit claude. The user will `nido enter` the session, read the report in chat, and reply.

## Step 2 — Report format (write to `./run-link/artifacts/triage-report.md`)

```markdown
# Triage: BR-#### — <original title>

**Source view:** new-reports | bugs
**Determination:** bug | not-a-bug | needs-info

## 1. Enriched description
<2–6 sentences. What the report is actually about, self-contained.>

**Confidence in this analysis:** high | medium | low — <one-line reason>

## 2. Solution directions
- **Direction A** — <shape, 1 sentence>. Effort: M. Confidence: medium — <reason>
- **Direction B** — <shape>. Effort: L. Confidence: low — <reason>

## 3. Proposed Notion writes (on `apply`)
- Title: `<original>` → `<cleaned-up>`
- Description: rewrite per §1
- Type: <unchanged | "bug">
- Effort: M
- Status: `Needs verification` → `Not started`   ; for triage-new
        (or no change)                            ; for triage-backlog

## 4. If you want to skip instead
**Recommended disposition:** Not Done | On Hold | leave-as-is | delete
**Reason:** <one line>

## 5. Investigation trail
- <file:line — what I learned>
- <file:line — what I learned>
```

Also output the report into your chat so the user sees it when they enter the session.

## Step 3 — Confirmation (chat, liberal parsing)

When the session resumes (the user replied), the latest user message contains the verdict. Parse liberally — phrasing variants ("apply it", "looks good, ship") all resolve to one of the verbs below.

| Reply | Effect |
|---|---|
| `apply` | Execute §3 verbatim |
| `apply: <override>` | Apply with overrides, e.g. `apply: effort=L, title="..."` |
| `skip` | Execute §4 with the recommended disposition |
| `skip: <disposition>` | Override disposition (`skip: delete`, `skip: on-hold`) |
| `redo: <correction>` | Re-run §1–2 with correction in mind; new draft, re-halt at `:awaiting-input` |
| `cancel` | Abort, leave Notion untouched, set phase `:complete` with note `cancelled` |

**Non-trivial overrides:** confirm intent back to the user ("→ applying with effort=L, ok? (y/n)") before executing.

**Unparseable input:** ask the user for clarification using the verbs above. Do not guess.

## Step 4 — Apply (the only Notion-write step)

Set phase `:applying`.

**Optimistic concurrency check first:** re-fetch the page via `mcp__notionApi__API-retrieve-a-page`. If `last_edited_time` differs from the value captured in Step 1, post a warning to chat:

> ⚠️ Ticket was edited externally during review (was: `<old>`, now: `<new>`). Re-read and reconfirm before I apply?

Wait for re-confirmation in chat before proceeding.

If the user confirms, or if `last_edited_time` is unchanged:

1. **Property update** — `mcp__notionApi__API-patch-page` with the page-id and properties:
   - Title (rich_text)
   - Type (select)
   - Effort (select) — must be one of `XS S M L XL`
   - Status (status) — for `:triage-new`, transition `Needs verification` → `Not started` (or `On Hold` if not actionable). For `:triage-backlog`, leave unchanged.

2. **Description body update** — `mcp__notionApi__API-patch-block-children` with the page-id and the new description as paragraph blocks. To replace the body entirely, you may need to first list existing blocks via `API-get-block-children` and delete them via `API-delete-a-block` before appending the new ones — Notion's API doesn't have a "replace all blocks" call.

3. **Comment** — `mcp__notionApi__API-create-a-comment` with the page-id and the body:

```
🤖 Triaged automatically via nido.
Confidence in analysis: <high|medium|low>
Confidence in solution direction: <high|medium|low>
Effort estimate: <XS|S|M|L|XL>

— review session: <session-id>
```

4. **Audit log** — append one line to `~/.nido/coordinator/notion-mutations.log`:

```
<ISO-timestamp> <run-id> page=<page-id> writes=title,type,effort,status,comment
```

Use `Bash` tool: `echo '<line>' >> ~/.nido/coordinator/notion-mutations.log`.

5. Set phase `:complete`. Print summary to chat.

**Partial-write failure handling:** if any of steps 1–3 fails after a partial write (e.g., property update succeeded but block update failed), post a warning comment with `mcp__notionApi__API-create-a-comment`:

```
⚠️ partial triage by nido — wrote <what landed>, failed to write <what didn't>: <error>.
Run-id: <run-id>. Re-triage manually.
```

Then append the audit log line with the partial-writes annotation, and set phase `:error`. **Do not roll back.** Notion has no transactions; honest partial state with a warning comment beats a fragile rollback.

## Step 5 — Skip

Set phase `:skipped`.

Dispositions:

- `Not Done` — `mcp__notionApi__API-patch-page` setting Status = `Not Done`.
- `On Hold` — Status = `On Hold`.
- `leave-as-is` — no Status change; just the comment.
- `delete` — `mcp__notionApi__API-delete-a-block` on the page-id (archives the page via `in_trash: true`).

In all cases, post a comment:

```
🤖 Marked <disposition> via nido.
Reason: <one-line reason from the human>

— review session: <session-id>
```

…unless disposition is `delete`, in which case the page is archived and the comment is moot — skip the comment.

Append to the audit log:

```
<ISO-timestamp> <run-id> page=<page-id> writes=status:<new-status>,comment(skipped)
```

Or for delete:

```
<ISO-timestamp> <run-id> page=<page-id> writes=archived
```

Set phase `:complete`.

## Step 6 — Redo

If the user replied `redo: <correction>`:

1. Read the correction text. Treat it as guidance for the next investigation pass.
2. Set phase `:investigating` again.
3. Re-run Steps 1 and 2 — fetch fresh page state, re-investigate with the correction in mind, write a new draft to `./run-link/artifacts/triage-report.md` (overwrite the old one).
4. Set phase `:awaiting-input` and exit. The user replies again.

There's no limit on redo cycles, but the per-Run budget (15m wall-clock) caps the total. If the budget approaches and the user hasn't confirmed, the run dies; the ticket stays Untriaged and re-enters the queue on the next poll.

## Step 7 — Cancel

If the user replied `cancel`:

1. Post a one-line message to chat acknowledging.
2. Set phase `:complete`.
3. Exit. Notion is untouched. Audit log gets a single line:

```
<ISO-timestamp> <run-id> page=<page-id> writes=none (cancelled by user)
```

## Step 8 — Dry-run mode

If the Run's trigger has `:dry-run? true`, Steps 4 and 5 do NOT call Notion-write MCP tools. Instead:

1. Write the intended writes to `./run-link/artifacts/would-write.edn` as a Clojure map, e.g.:

```clojure
{:page-id "..."
 :writes [{:type :patch-page :properties {...}}
          {:type :patch-block-children :blocks [...]}
          {:type :create-comment :body "..."}]}
```

2. Append to the audit log with a `dry-run` annotation:

```
<ISO-timestamp> <run-id> page=<page-id> writes=DRY-RUN <would-do>
```

3. Set phase `:complete`. Note in chat: "dry-run — no Notion writes made."

The user can inspect `would-write.edn` to verify the agent's intent.
````

- [ ] **Step 3: Commit in brian**

```
cd ~/Code/brian
jj log -r '@-..@' --no-graph    # confirm @ is your new commit, not an existing one
jj describe @ -m "feat(.claude/skills): triage-bug skill for nido auto-triage

The skill body that the nido coordinator's triage-new and
triage-backlog triggers invoke. Lives in brian for v1 because lite
session profile symlinks .claude/ to the worktree; will move to nido
when a second project needs triage. Spec: nido docs/superpowers/specs/
2026-05-18-notion-triage-agent-design.md."
```

- [ ] **Step 4: Switch back to nido for the remaining tasks**

```
cd ~/Code/nido
```

---

## Task 4 — Wire the two triage triggers in dry-run

**Files:**
- Modify: `~/.nido/projects/brian/triggers.edn`

Add the two triggers below the existing `smoke-notion`. Both `:dry-run? true` for first install.

- [ ] **Step 1: Read the current file**

```
cat ~/.nido/projects/brian/triggers.edn
```

- [ ] **Step 2: Add the two triage triggers**

```clojure
{:triggers
 [;; existing smoke-notion stays unchanged
  {:name :smoke-notion
   :source {:type :notion-view :view :open-bugs :poll "30s"}
   :filter {:type #{"bug"} :status #{"Not started" "In progress" "Code Review" "Review"}}
   :skill :investigate-bug
   :payload "{{event/title}} ({{event/page-id}})"
   :payload-key :title
   :limits {:budget "2m" :max-failures 3}
   :dry-run? false}

  ;; NEW
  {:name :triage-new
   :source {:type :notion-view :view :new-reports :poll "2m"}
   :skill :triage-bug
   :session-profile :lite
   :priority 10                        ; new always > any backlog item
   :payload "Triage BR-{{event/properties/id/number}}: {{event/title}}"
   :limits {:budget "15m" :max-failures 3}
   :dry-run? true}

  ;; NEW
  {:name :triage-backlog
   :source {:type :notion-view :view :bugs :poll "10m"
            :additional-filter {:property "Effort" :select {:is_empty true}}}
   :skill :triage-bug
   :session-profile :lite
   :priority-from {:property "severity-calc"}    ; 1..5 directly
   :payload "Triage BR-{{event/properties/id/number}}: {{event/title}}"
   :limits {:budget "15m" :max-failures 3}
   :dry-run? true}]}
```

The `:payload` template uses the Notion `id` property's `number` field (the auto-incrementing unique-id with the `BR` prefix — so e.g. `BR-5236`).

- [ ] **Step 3: Validate**

```
bb nido:notion:views:check :project brian        ;; registry is still valid
bb nido:trigger:list :project brian              ;; new triggers appear, loaded cleanly
```

If `trigger:list` errors with a schema violation, fix the trigger config (most likely a typo in a keyword).

- [ ] **Step 4: Not a code commit.** Runtime config.

---

## Task 5 — End-to-end dry-run smoke

**Files:** none (verification only)

Bring up the coordinator with `:global-parallel-cap 1` (training wheels), let the triage triggers fire against the real backlog in dry-run mode, inspect what the agent would have done. No Notion writes happen.

- [ ] **Step 1: Set training-wheels cap**

```
bb -e '(let [p (str (System/getenv "HOME") "/.nido/coordinator/config.edn") e (if (.exists (java.io.File. p)) (read-string (slurp p)) {})] (spit p (pr-str (assoc e :global-parallel-cap 1))))'
```

- [ ] **Step 2: Bring up the daemon**

```
bb nido:coordinator:down 2>/dev/null || true
sleep 1
bb nido:coordinator:up
sleep 5
bb nido:coordinator:status | head -20
```

Expected: status healthy. Source instances for `triage-new` and `triage-backlog` appear under `Sources:`. The first poll for each is a snapshot seed (no events emitted — by design from Stage 5).

- [ ] **Step 3: Wait for an event**

Wait 2 minutes (or until a ticket lands on `Status = Needs verification`, which is the typical intake state). The `triage-new` source should emit. Watch:

```
bb nido:coordinator:logs | tail -30
bb nido:runs:list | head -10
```

If after 2 minutes nothing has fired (because the New reports view is empty), either:
(a) Manually fire one: `bb nido:trigger:fire :project brian triage-new :page-id "<an-existing-untriaged-page-id>"` — verify with the user that this is OK.
(b) Wait for organic intake.

- [ ] **Step 4: Inspect the resulting Run**

When a Run lands:

```
bb nido:runs:show <run-id>
cat ~/.nido/coordinator/runs/<run-id>/artifacts/triage-report.md
cat ~/.nido/coordinator/runs/<run-id>/artifacts/would-write.edn
```

Read the report. Read what the agent intended to write. Does it look plausible?

- [ ] **Step 5: Try a chat-driven review**

```
nido          # the shell wrapper — opens the TUI
              # press `r` for runs, find the triage Run, press enter
              # in the session, chat: `apply` (will NOT actually write because :dry-run? true)
```

The agent's `:applying` phase should run, write the dry-run audit log, exit `:complete`. Verify:

```
cat ~/.nido/coordinator/notion-mutations.log | tail -5
```

Expected: a line ending with `writes=DRY-RUN ...`.

- [ ] **Step 6: Tear down**

```
bb nido:coordinator:down
```

Report any surprises: missing field on the agent's report, MCP tool errors, parse failures on the chat verdict, etc.

If the agent produces clearly bad reports (e.g., wrong area, fabricated solution directions), STOP — this is a "the skill needs more iteration" moment, not a Plan D bug per se. Iterate on `SKILL.md` in brian's worktree, re-fire, repeat. Don't go live until report quality is good.

---

## Task 6 — Validation period (calendar checkpoint, not work)

**Files:** none — this is a multi-day observational gate.

Let the triage triggers run in dry-run mode for **~3 business days** against the real backlog. During this period:

- [ ] **Step 1: Each day, sample 3–5 triage Runs**

Look at `~/.nido/coordinator/runs/*/artifacts/triage-report.md` for fresh Runs. Read the report. Compare with the original Notion ticket. Is the enriched description better than the original? Are the solution directions plausible? Are the effort estimates reasonable?

- [ ] **Step 2: Track outcomes**

Keep a short log (anywhere — even a brian/scratch.md). For each sampled Run, note: `GOOD / OK-WITH-EDIT / BAD` and one-line reason.

- [ ] **Step 3: Iterate on `SKILL.md` if needed**

If you see a recurring failure mode (e.g., the agent consistently underestimates effort, or routinely misclassifies a class of tickets), edit `~/Code/brian/.claude/skills/triage-bug/SKILL.md` to address it. New behavior takes effect at the next Run — no restart needed.

- [ ] **Step 4: Decision checkpoint**

After ~3 days: is >75% of the agent's output `GOOD` or `OK-WITH-EDIT`? If yes, proceed to Task 7 (go-live). If no, iterate on SKILL.md and rerun the validation.

This task does not produce a commit. It's a sign-off gate before flipping dry-run off.

---

## Task 7 — Go-live: flip dry-run, raise cap

**Files:**
- Modify: `~/.nido/projects/brian/triggers.edn`
- Modify: `~/.nido/coordinator/config.edn`

The skill is good. The agent has been writing trustworthy dry-run reports. Time to actually mutate Notion.

- [ ] **Step 1: Flip `:dry-run?` to `false` on both triage triggers**

Edit `~/.nido/projects/brian/triggers.edn`. Change `:dry-run? true` → `:dry-run? false` on `:triage-new` and `:triage-backlog`. Leave `:smoke-notion` untouched.

- [ ] **Step 2: Raise `:global-parallel-cap` to 5**

```
bb -e '(let [p (str (System/getenv "HOME") "/.nido/coordinator/config.edn") e (if (.exists (java.io.File. p)) (read-string (slurp p)) {})] (spit p (pr-str (assoc e :global-parallel-cap 5))))'
```

- [ ] **Step 3: Restart the daemon**

```
bb nido:coordinator:down 2>/dev/null || true
sleep 1
bb nido:coordinator:up
sleep 3
bb nido:coordinator:status | head -20
```

Verify the TUI header now reads `in-flight: N/5` (the cap is 5).

- [ ] **Step 4: Watch the first few real Runs**

For the first 1–2 hours after go-live, keep an eye on:
- `~/.nido/coordinator/notion-mutations.log` — any new lines should reflect intentional writes.
- The Notion DB itself — manually verify the first 2–3 triaged tickets look correct (Effort set, Status transitioned, comment posted).

If anything looks wrong, immediately:

```
bb nido:halt                                          ;; halts the daemon
```

…then investigate before flipping back on.

- [ ] **Step 5: Not a code commit.** Runtime config flips.

---

## Self-review — spec coverage check

| Spec requirement | Task |
|---|---|
| `Effort` Notion property (XS, S, M, L, XL) | Task 1 |
| `notion-views.edn` gains `:new-reports` and `:bugs` views | Task 2 |
| `/triage-bug` skill exists and follows the contract | Task 3 |
| Investigation → report → halt → chat verdict → apply | Task 3 (Steps 1–4 of the skill) |
| Liberal confirmation parsing (apply / skip / redo / cancel) | Task 3 (Step 3) |
| Notion-comment dedup check at start of skill | Task 3 (Step 1) |
| Optimistic concurrency on `last_edited_time` | Task 3 (Step 4) |
| Partial-write `⚠️ partial triage` comment | Task 3 (Step 4) |
| Append-only `notion-mutations.log` | Task 3 (Steps 4, 5) |
| `:dry-run?` mode writes `would-write.edn`, no Notion writes | Task 3 (Step 8) |
| Triage-new trigger with `:priority 10` and `:session-profile :lite` | Task 4 |
| Triage-backlog with `:priority-from severity-calc` and `:additional-filter` on Effort | Task 4 |
| Dry-run validation against real backlog | Task 5, Task 6 |
| Go-live: flip dry-run, ramp cap to 5 | Task 7 |
| Triage skill location (nido vs brian) decision documented | Plan header |
| TUI keystrokes `t` (jump to awaiting-input) and `g` (filter to triage) | **deferred** — not in v1; can be added later if the runs-view bar gets crowded |
| Per-trigger caps | **deferred** — named as follow-up in the spec |
| Cross-project triage | **deferred** — named as follow-up in the spec |

No placeholders. The `would-write.edn` shape, the audit log format, the comment text, and the chat verbs are all specified concretely.
