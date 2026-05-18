# Notion triage agent

**Status:** spec, awaiting implementation plans
**Date:** 2026-05-18
**Companion specs:** [nido coordination layer](2026-05-13-nido-coordination-layer-design.md), [Stage 5 Notion source](2026-05-15-nido-stage-5-notion-source-design.md)

## Goal

Automatically triage Notion bug reports as they arrive (and drain the existing backlog), with a human-in-the-loop confirmation before any Notion mutation. The agent investigates the brian codebase to enrich the ticket beyond what the original reporter wrote, produces a structured report, and only writes to Notion after explicit human approval in the session chat.

Throughput target: up to five triage agents running in parallel, draining the queue priority-first.

## Non-goals

- Per-trigger concurrency caps. Global cap is enough for v1.
- A separate UI for review. Review happens in the session chat. The TUI surfaces *state* and routes you in; decisions happen in chat.
- Cross-project triage. brian-only for v1; the skill is project-agnostic but config wiring is brian-specific.
- A Notion-side "Triaged?" boolean property. We dedup via the presence of the bot's triage comment on the page.
- An eval harness for triage report quality. Worth doing once a corpus exists; out of scope here.

## Constraints carried in from prior conversation

- **Notion intake isn't reliably `Type`-tagged.** Many new reports come in with `Type` empty. The "New reports" view (`Status = Needs verification`) is exactly where Type is most often null. Triage filter cannot rely on `Type = bug`; the agent decides bug-vs-not-a-bug itself.
- **Notion REST API does not expose view filter definitions.** View filters live in the Notion UI only. nido must encode the relevant ones in config; we cannot fetch them at runtime.
- **`/v1/databases/<id>/query` is deprecated in API version 2025-09-03.** New endpoint is `/v1/data_sources/<ds-id>/query`. nido's notion source migrates as part of this work.
- **Chat is the review UI** in nido. Coordination surfaces (TUI, dashboards) reflect state and route the human in; decisions live in the chat with the agent.
- **Autonomous nido features need first-class safety + staged rollout.** Default off (dry-run), hard caps, circuit breakers, visible state.

## Glossary

- **Triage trigger** — a coordinator trigger whose skill is `:triage-bug`. Two ship in v1: `triage-new` and `triage-backlog`.
- **Triage Run** — a coordinator Run spawned by a triage trigger.
- **Lite session** — a new session-shape variant with no PG/JVM, worktree symlinked to a target directory.
- **Notion views registry** — `~/.nido/projects/<project>/notion-views.edn`, a stable mapping from view-keyword to Notion filter object.
- **Session profile registry** — `~/.nido/projects/<project>/session-profiles.edn`, a stable mapping from profile-keyword to session shape.
- **Confirmation grammar** — the small set of verbs (`apply`, `skip`, `redo`, `cancel`) parsed liberally from the user's chat reply.

## Architecture

```
Notion Task Database (124fca…e35)
  ├── view "New reports" ─────── trigger triage-new
  └── view "Bugs"        ─────── trigger triage-backlog (+ additional-filter)
                                      │
                                      ▼
                  coordinator/sources/notion (upgraded)
                     emits envelopes with :priority + payload
                                      │
                                      ▼
                  coordinator/queue (envelopes carry :priority)
                                      ▼
                  coordinator/executor (NEW)
                  ├── global cap (default 5)
                  ├── pops highest :priority, ties FIFO
                  └── spawns Run via existing agent/launch!
                                      ▼
                  per-Run session-home (NEW :lite profile)
                  ├── no PG, no JVM
                  ├── worktree symlink → ~/Code/brian (read-only intent)
                  └── .claude/skills/triage-bug/ ← /triage-bug
                                      ▼
                  triage-bug skill (NEW)
                    1. fetch Notion page via mcp__notionApi__*
                    2. investigate worktree (grep, read)
                    3. write artifacts/triage-report.md
                    4. phase :awaiting-input → exit claude
                                      ▼
                  HUMAN: `nido enter <session>` → read report in chat
                         reply: apply | skip | redo: <correction> | cancel
                                      ▼
                  triage-bug (resumed via claude --resume)
                    ├── apply  → Notion patch + comment
                    ├── skip   → Status transition + comment + maybe archive
                    └── redo   → re-investigate, post new draft, re-halt
                                      ▼
                  Run terminal → executor frees slot → next envelope pops
```

Five new or substantially-changed pieces:

1. **`nido.coordinator.executor`** — slot-based scheduler with priority-aware pop.
2. **Envelope schema** — adds `:priority` (int, default 0) and `:received-at`.
3. **Notion source** — migrates to data-source API; resolves view keywords against the registry; emits per-envelope priority.
4. **Session profile registry + `:lite` profile** — empty services, worktree-symlink strategy.
5. **`triage-bug` skill** — lives in `~/Code/nido/.claude/skills/triage-bug/`.

## Coordinator changes

### Executor

New namespace `nido.coordinator.executor`. Replaces the inline `run-now!` call in the daemon loop.

```clojure
(defprotocol Executor
  (submit! [this envelope])       ;; enqueue with envelope's :priority
  (free-slots [this])
  (snapshot [this]))              ;; in-flight + queued (TUI + status)

(defn start! [{:keys [global-cap on-spawn]}] ...)
;; on-spawn = (fn [envelope] => future) — wraps existing agent/launch!
```

Internals:

- **Priority queue:** `java.util.concurrent.PriorityBlockingQueue` keyed by `[(- priority) received-at]`. Higher `:priority` pops first; ties broken FIFO.
- **Worker model:** one `Thread/start` loop per slot. Each blocks on the queue, runs the envelope to terminal, releases its slot. Not core.async — keeps cancellation/budget logic explicit, matches the existing `agent/launch!` semantics.
- **Cancellation:** each in-flight Run is wrapped in a `Future`, so the existing per-Run budget kill-path (SIGTERM → SIGKILL) keeps working unchanged.
- **Backpressure:** queue is unbounded; the daemon's outer poll just keeps draining the on-disk envelope directory into the executor.

Coordinator config (`~/.nido/coordinator/config.edn`) gains:

```clojure
{:global-parallel-cap 5            ;; was 2; now actually enforced
 :executor {:idle-poll-ms 250      ;; how often a free slot checks the queue
            :shutdown-grace-ms 5000};; SIGTERM grace on daemon stop
 …existing keys…}
```

### Envelope schema

Today: `{:target | :broadcast … :payload … :created-at}`.
After: `{… :payload … :created-at :priority <int, default 0> :received-at <iso, set on submit!>}`.

`:priority` is set by the trigger (or overridden per-event by the source). Existing triggers that don't set it inherit `0` → FIFO. No regression.

### What does not change

- The daemon outer loop (poll triggers → drain queue → route → submit). Only the last step swaps `run-now!` for `executor/submit!`.
- Run lifecycle, transitions, on-disk run.edn shape, startup reconciliation.
- Safety brakes (budget, breaker, halt, anomaly auto-halt) — all per-Run, untouched.

## Session profile registry

Today `bb nido:session:up` always provisions PG + JVM + app. Triage is read-only against the codebase — heavy services are wasted.

New file: `~/.nido/projects/brian/session-profiles.edn`.

```clojure
{:profiles
 {:full {…current default…}            ;; PG + JVM + worktree-add
  :lite {:services []                  ;; no PG, no JVM
         :worktree {:strategy :symlink
                    :target "~/Code/brian"}}}}
```

Effects of `:lite`:

- `session:up` skips `:postgresql` and `:process` service init entirely.
- Worktree is `ln -s ~/Code/brian session-home/worktree` instead of `git worktree add`. Multiple parallel triage Runs share the symlink safely — they investigate read-only.
- `.mcp.json` is still written, but without the per-session postgres MCP entry. `notion` MCP is wired in.
- `session:reset` / `session:destroy` skip the PG drop and just `rm -rf` the session-home.

Triggers reference profiles by keyword (`:session-profile :lite`). Manual `bb nido:session:up` calls default to `:full` — no change to the existing TUI/CLI manual flow.

## Notion data model

### One new property

| Property | Type | Options | Set by |
|---|---|---|---|
| `Effort` | select | `XS`, `S`, `M`, `L`, `XL` | triage agent on apply |

`Effort` being empty is the signal for "untriaged" in the backlog trigger. `Cost in days` is untouched by triage; humans can still populate it later.

### Existing properties consumed

- `Status` (status): `Needs verification`, `Not started`, `On Hold`, `In progress`, `Code Review`, `Review`, `Done`, `Not Done`.
- `Type` (select): `bug`, `feature`, `improvement`, `research`, `chore`.
- `Priority` (select): `0 – Release Blocker` … `5 - Wont`.
- `severity-calc` (formula): Must=5, Should=4, Could=3, Would=2, Wont=1, Release Blocker=5, null=0. **Used directly as the backlog envelope priority** — no mapping in trigger config.

### Notion views registry

New file: `~/.nido/projects/brian/notion-views.edn`. Single source of truth for view filters (since the Notion REST API can't return them).

```clojure
{:database "124fca9f-403c-80d4-896f-fc857e105e35"
 :views
 {:new-reports
  {:filter {:property "Status"
            :status   {:equals "Needs verification"}}}

  :bugs
  {:filter {:and [{:property "Type"   :select {:equals "bug"}}
                  {:property "Status" :status {:does_not_equal "Done"}}
                  {:property "Status" :status {:does_not_equal "Not Done"}}
                  {:property "Status" :status {:does_not_equal "Needs verification"}}
                  {:property "Status" :status {:does_not_equal "Review"}}]}}}}
```

Filters use Notion's native filter-object DSL so they're directly the body of an API query — no translation layer to maintain. A new `bb nido:notion:views:check :project brian` task validates the registry against the live database (verifies properties exist, select values resolve) and catches drift.

### Notion source code changes

`src/nido/notion/client.clj`:

- Replace `database-query` (deprecated `/v1/databases/<id>/query` without filter) with `data-source-query` (new `/v1/data_sources/<ds-id>/query` with filter body).
- Resolve database id → data-source-id once at source start (cache it).

`src/nido/coordinator/sources/notion.clj`:

- Accept `:view` as a keyword. Resolve via `notion-views.edn`.
- Merge `:additional-filter` from trigger config with the view filter using `{:and [...]}`.
- Set envelope `:priority` from either trigger-level `:priority` constant or `:priority-from {:property "<name>"}` (read from the normalised page properties).

## Triage skill contract

### Location

`~/Code/nido/.claude/skills/triage-bug/SKILL.md`. Harness-side, not project-side — triage is about nido's triage workflow, not brian's domain. The skill still grep's the brian worktree to investigate.

### Lifecycle phases

| Phase | Set by | Means |
|---|---|---|
| `:investigating` | skill on start | Fetching page, walking codebase, drafting report |
| `:awaiting-input` | skill before halt | Draft in `artifacts/triage-report.md`. Awaiting human reply in chat. |
| `:applying` | skill on resume after `apply` | Writing Notion mutations + comment |
| `:complete` | skill on terminal success | Notion updated, run done |
| `:skipped` | skill on terminal `skip:*` | Status transition + comment + (if `delete`) archived |
| `:error` | skill on unrecoverable failure | Notion untouched, run failed |

Phases write through to `_run-status.edn`. TUI runs surface shows the lane each in-flight Run is in.

### Step 1 — investigate (autonomous)

1. Read envelope payload → grab `:page-id`, `:url`, `:title`, `:trigger-name`.
2. Fetch the Notion page in full via `mcp__notionApi__API-retrieve-a-page`, body blocks via `API-get-block-children`, existing comments via `API-retrieve-a-comment`.
3. Dedup check: if any existing comment starts with `🤖 Triaged automatically via nido`, exit `:complete` with log "already triaged, skipping."
4. Codebase investigation in the symlinked worktree (`~/Code/brian`):
   - Heuristic grep for module/feature names from title and description.
   - Read 2–5 relevant files, ~500 lines total cap.
   - Stop early when the area is obvious.
5. Write `artifacts/triage-report.md`. Set phase `:awaiting-input`. Exit claude.

### Step 2 — report format

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
- Status: `Needs verification` → `Not started`

## 4. If you want to skip instead
**Recommended disposition:** Not Done | On Hold | leave-as-is | delete
**Reason:** <one line>

## 5. Investigation trail
<bulleted list of files grep'd / read, ~5 lines max>
```

### Step 3 — confirmation grammar (chat, liberal parsing)

| Reply | Effect |
|---|---|
| `apply` | Execute §3 verbatim |
| `apply: <override>` | Apply with overrides, e.g. `apply: effort=L, title="..."` |
| `skip` | Execute §4 with the recommended disposition |
| `skip: <disposition>` | Override disposition (`skip: delete`, `skip: on-hold`) |
| `redo: <correction>` | Re-run §1–2 with correction in mind; new draft, re-halt |
| `cancel` | Abort, leave Notion untouched, Run terminates `:cancelled` |

Parsing is liberal — phrasing variants ("apply it", "looks good, ship") all resolve to one of the four verbs. For non-trivial cases the skill confirms the parsed intent back ("→ applying with effort=L, ok? (y/n)") before executing.

Skip dispositions:

- `Not Done` — we looked, it's not a real problem / won't action.
- `On Hold` — real but parked.
- `leave-as-is` — keep `Status = Needs verification`, just post a comment with reasoning. The triage comment marker means we won't re-pick this row on next poll.
- `delete` — Notion has no hard-delete; this archives via `in_trash: true` (`API-delete-a-block` on the page). Reserved for genuinely false reports / noise.

The agent's report recommends a disposition; the human confirms or overrides per-ticket.

### Step 4 — apply (only Notion-write step)

- `mcp__notionApi__API-patch-page` for property mutations (Title, Type, Effort, Status).
- `mcp__notionApi__API-patch-block-children` for the description rewrite.
- `mcp__notionApi__API-create-a-comment` for the triage comment.
- `mcp__notionApi__API-delete-a-block` for `skip: delete`.

All wrapped in a single skill helper that does them in order. If any step fails after a partial write, the skill posts a `⚠️ partial triage` comment listing what landed and what didn't, then exits `:error`. We don't roll back — Notion has no transactions and rollback is worse than honest partial state.

### Step 5 — concurrency / re-entry safety

- **Already-triaged dedup** via comment-presence check at the top of step 1.
- **Optimistic concurrency:** the skill captures the page's `last_edited_time` during investigation. At apply time, it re-fetches and compares. If `last_edited_time` changed (a human touched the ticket while we were thinking), the apply step halts with a `⚠️ ticket changed during review — re-read and re-confirm?` warning and re-asks before writing.

### Triage comments (posted to Notion)

After apply:

```
🤖 Triaged automatically via nido.
Confidence in analysis: <high|medium|low>
Confidence in solution direction: <high|medium|low>
Effort estimate: <XS|S|M|L|XL>

— review session: <session-id>
```

After skip:

```
🤖 Marked <disposition> via nido.
Reason: <one-line reason from the human>

— review session: <session-id>
```

## Wiring

### Triggers

`~/.nido/projects/brian/triggers.edn`:

```clojure
{:triggers
 [{:name :triage-new
   :source {:type :notion-view :view :new-reports :poll "2m"}
   :skill :triage-bug
   :session-profile :lite
   :priority 10                        ;; constant; new always > any backlog item
   :limits {:budget "15m" :max-failures 3}
   :dry-run? true}                     ;; default ON for first install

  {:name :triage-backlog
   :source {:type :notion-view :view :bugs :poll "10m"
            :additional-filter
            {:property "Effort" :select {:is_empty true}}}
   :skill :triage-bug
   :session-profile :lite
   :priority-from {:property "severity-calc"}    ;; 1..5 directly
   :limits {:budget "15m" :max-failures 3}
   :dry-run? true}]}
```

`:additional-filter` narrows the canonical "Bugs" view to untriaged bugs (`Effort` empty) without polluting the registry. The Bugs view is owned by the Notion team; "is it triaged yet?" is a triage-specific concern.

### Per-Run on-disk layout

```
~/.nido/sessions/brian/triage-BR-5236-<short-id>/
├── CLAUDE.md                          ;; auto-generated briefing
├── .mcp.json                          ;; notion MCP only (no postgres)
├── .claude/                           ;; symlink → worktree/.claude/
├── worktree/                          ;; symlink → ~/Code/brian
├── bin/claude                         ;; existing resume shim
└── run-link/                          ;; symlink → ~/.nido/coordinator/runs/<run-id>/
    ├── run.edn
    ├── _run-status.edn
    ├── agent.log
    └── artifacts/
        ├── triage-report.md
        └── would-write.edn            ;; only when :dry-run? true
```

Session name: `triage-BR-<id>-<short-hash>` — predictable so duplicate fires resolve to the same directory and existing reconciliation logic notices. Run-id stays UUID.

### CLAUDE.md (briefing, auto-generated per Run)

```markdown
# Triage Run — BR-5236

Source: triage-new (Notion view: new-reports)
Notion page: https://www.notion.so/...
Title: <original>

You are running the `/triage-bug` skill. Investigate the brian
worktree at ./worktree (read-only — do not edit). When you have a
draft, write it to ./run-link/artifacts/triage-report.md and set
phase to :awaiting-input.

Read the skill at .claude/skills/triage-bug/SKILL.md for the full
contract (report format, confirmation grammar, Notion-write steps).
```

### `bb` tasks (additions)

```
bb nido:notion:views:check :project brian
  → walks notion-views.edn, hits the API, verifies every filter parses
    and every referenced property/value exists. Exits non-zero on drift.

bb nido:triage:status
  → list of in-flight + queued triage Runs by trigger, with phase.
    Shortcut over `bb nido:runs:list` filtered by skill=:triage-bug.
```

## Safety

### Carried over from existing nido machinery

- **Per-Run budget:** `:limits.budget "15m"` covers investigation + arbitrarily long human review. Wall-clock — does not stop while halted at `:awaiting-input`. If a review is abandoned, the Run hits budget, gets SIGTERM'd, the ticket stays on `Needs verification`, next poll re-fires. **This is correct behavior** — stale triage drafts should not apply themselves later.
- **Per-trigger breaker:** `:limits.max-failures 3` opens the breaker after three consecutive `:error` terminals. Resume with `bb nido:coordinator:resume`.
- **Kill switch:** `bb nido:halt` halts the daemon globally.

### New for triage

- **Dry-run mode (`:dry-run? true`):** the executor still spawns the lite session and the agent still investigates and writes `triage-report.md`, but the apply step short-circuits — it logs intended Notion writes to `artifacts/would-write.edn` and exits `:complete`. Default ON for both triggers on first install.
- **First-week training wheels:** keep `:global-parallel-cap 1` for the first few days. Drive throughput up to 5 only after quality is good.
- **Notion mutation log:** every Notion write the skill makes is appended to `~/.nido/coordinator/notion-mutations.log` (page-id, timestamp, before/after delta, run-id). Append-only, never rotated by nido. Audit trail for "what did the bot touch yesterday."

## TUI changes

- **Runs view** grows a phase column. Triage phases (`:investigating`, `:awaiting-input`, `:applying`, `:skipped`) appear alongside whatever other skills use.
- **Keystroke `t`** jumps to the next Run in `:awaiting-input` and `nido enter`s it — one-keystroke triage flow.
- **Keystroke `g`** toggles a filter that hides Runs whose skill ≠ `:triage-bug`. Focus mode for the triage queue.
- **Header line:** `triage: <in-flight>/<cap> · queued: <N> (new: <a>, backlog: <b>) · awaiting-input: <M>`. Five scannable numbers.

## Rollout

Six sequenced stages. Each ships independently; only the ordering matters.

1. **Coordinator executor + envelope priority.** Replaces synchronous `run-now!` with the slot scheduler. Lifts `:global-parallel-cap` from cosmetic to enforced. Default priority 0 → FIFO; no behavior change for existing triggers. Verifies on the existing smoke trigger.
2. **Session profile registry + `:lite` profile.** New resolver, `:lite` shape, `:symlink` worktree strategy. Manual `bb nido:session:up :session-profile :lite` works end-to-end. No triage involvement yet.
3. **Notion source upgrade.** `database-query` → `data-source-query`, view-keyword resolution against the registry, `:additional-filter`, `:priority` / `:priority-from`. Smoke trigger migrates to the registry — same behavior, new plumbing.
4. **`triage-bug` skill.** The skill itself plus lifecycle phases, report format, confirmation grammar, Notion-write helpers, dedup-via-comment, optimistic concurrency. Tested manually by firing one envelope via `bb nido:trigger:fire`.
5. **Triggers wired, dry-run.** Both triggers installed with `:dry-run? true`, `:global-parallel-cap 1`. Quality assessment over a few days against the real backlog.
6. **Go live.** Flip `:dry-run? false`, raise cap to 5.

Each stage gets its own implementation plan after this spec is signed off.

## Open follow-ups (out of scope for v1)

- **Per-trigger caps.** Executor data structures support it; config doesn't expose it. Add when a second concurrent-heavy trigger lands.
- **Cross-project triage.** v1 is brian-only. The skill is project-agnostic but the worktree symlink isn't. Generalize once a second project needs triage.
- **Triage output eval harness.** Score reports against human-applied versions to catch regressions in the skill. Worth doing once ~50 tickets have been triaged.

## What this design explicitly does not introduce

- No new `Triage` property on the Notion side. Dedup via comment presence is sufficient and avoids a schema change.
- No backfill CLI. Both triggers naturally drain whatever matches their view on next poll.
- No new UI surface for review — chat is the review UI, TUI just routes you in.
- No per-trigger cap config. Named as a follow-up if a second concurrent trigger lands.
- No rollback for partial Notion writes. Notion has no transactions; an honest `⚠️ partial triage` comment beats a fragile rollback path.
