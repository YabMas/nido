# Workstream environment decomplection — design

**Date:** 2026-07-22
**Status:** approved (brainstorm), spec under review
**Surfaces:** TUI (`nido.tui`) + web dashboard (`nido.ui.views`)

## Problem

The word **session** braids two unrelated concerns into one noun, and the UI
represents them together:

1. **Agent session** — the transcript/conversation an agent had.
2. **Resource lifecycle** — the worktree + PG + JVM + app server + ports.

The workstream detail screen (both surfaces) renders a **list of sessions**, each
carrying its own resource lifecycle controls and info. This is wrong on both axes:

- **Transcripts are mostly redundant with the ledger.** The ledger already threads
  everything of significance through a workstream. A first-class, always-present
  list of past agent sessions is over-weighted: the only time you'd want a raw
  transcript is forensically — "something on the ledger looks off, how did it get
  there?" — and even then you want to land on *the specific session that wrote that
  line*, not scan a list.
- **Resources are only ever "now."** You never start a *past* session's resources.
  What you want is to run **the latest version** and open it in the browser. Binding
  a resource lifecycle to each historical session entry is a category error — the
  lifecycle belongs to the *workstream*, not to a session-in-a-list.

## The reframe

A workstream has **one environment** and **a ledger**.

- **Environment** (resource-lifecycle concern) — the single stage-resolved current
  work session. You Start it, Stop it, Open its URL, Enter it. Exactly one, or none.
- **Ledger** (record concern) — unchanged as a record, but every entry is stamped
  with the session/run that produced it, so a suspicious line drills straight to
  that transcript.

The **multi-session list is deleted from both surfaces.** Its two real jobs —
*run something* and *find out what happened* — are taken over by the environment
panel and by ledger provenance, respectively.

This keeps the two concerns cleanly separated across surfaces:

- **web** = record + forensics (ledger reader lives here)
- **TUI** = run the latest version + drop in (deliberately ledger-free)

## Non-goals (YAGNI)

- **Multiple live environments per workstream.** Single environment is sufficient
  for now; revisit only if a concrete need appears (e.g. wanting an autonomous run's
  env and an interactive env side-by-side). Explicitly deferred.
- **Renaming nido's underlying `session`.** The worktree+PG+JVM bundle stays named
  "session" everywhere in the engine/registry/lifecycle — that name is pervasive and
  renaming is pure churn. "Environment" is only the *UI-level* name for "the
  workstream's one current session." Under the hood it is still a session.
- **A transcript viewer.** Forensic drill-down reveals/opens the raw record
  (`agent.log` / transcript path). No in-app transcript rendering.
- **Changing the session data model.** A workstream may still technically have had
  multiple sessions over its life. We re-present (resolve one as the environment,
  treat the rest as ledger-anchored provenance); we do not re-architect storage.

## Component 1 — the environment panel (Phase 1)

### Resolver

A single source of truth, `work/environment`:

```
(work/environment project ws-id) => session-map | nil
```

Resolves the workstream's **one current work session** — the most-advanced work
session (impl over triage; latest among candidates), by stage, not by liveness (a
down-but-provisioned impl session is still the environment). Returns `nil` when the
workstream has no runnable work session yet (still in triage, or a scratch that
never got a heavy session).

This formalizes the resolution the dev-environment launcher already does ad hoc
(see `project_workstream_dev_environment_launcher`) into one function both surfaces
call. The returned map carries what the panel renders: `:name`, dev state
(`:state :url` via `dev/dev-state-for`, which reads the registry `:url`), and
machine facts (`:app-port :pg-port :nrepl-port :repl-rss :pg-rss :heap-max` via the
existing machine-facts assembly).

### Panel

When `work/environment` resolves, both surfaces show **one block**, not a list:

- **status** — running / down / starting… (from `dev/dev-state-for`)
- **URL** — the app URL (`:url`); clickable on web, `open`-able on TUI
- **ports** — app / pg / nrepl (+ RSS / heap on web, where it already shows)
- **actions** — Start · Stop · Restart · Open in browser · Enter · Worktree

When it's `nil`: the panel shows **"no runnable version yet"** and offers nothing to
start — no resources spun up for a session with no code to serve.

### Action consolidation

Today two lifecycle levels are braided together: session up/down/destroy (`u`/`d`/`x`)
and dev-app start/stop/restart (`S`/`X`/`R`). For a single environment that is
needless. Collapse to one conceptual "is it up and reachable" lifecycle:

| Action  | Backing call (subject to plan refinement)                         |
|---------|-------------------------------------------------------------------|
| Start   | `session:up` (idempotent — brings PG + JVM + app; yields a URL)    |
| Stop    | `session:down` (releases resources)                               |
| Restart | dev-app restart (`dev/dev-action! … "restart"`) when up; else Start |
| Open    | open `:url` in browser                                            |
| Enter   | `session:enter` → session-home/chat (TUI's existing `enter-session`) |
| Worktree| `session:enter :cd worktree`                                     |
| Destroy | demoted — available behind a confirm, not a primary action        |

Exact mapping (session lifecycle vs dev-action) is settled in the implementation
plan; the design commitment is: **one Start/Stop/Restart state, not two.**

### Surface changes

- **TUI** (`nido.tui`): the `:workstream` detail screen becomes this one block. The
  session list (`detail-rows`) *and* the inline session-info added earlier this
  session fold into the environment panel. `update-workstream` keys collapse to the
  consolidated action set. `main-list-height`'s `:workstream` special-case and the
  inline-info plumbing are superseded.
- **Web** (`nido.ui.views`): `workstream-pane`'s per-session table (the
  `[:tr … session-dev-cell … machine-facts]` rows) collapses to the one environment
  block rendered above the ledger. `pane-action-bar` (gate actions) is unaffected.

## Component 2 — ledger provenance & forensics (Phase 2)

### Stamp at the source

`nido.coordinator.workstream/append-entry!` is already the choke point and already
persists whatever keys the entry carries (`:entries` is an open `[:map-of keyword?
any?]`; the entry is stored as `(assoc entry :seq … :at … :file …)`). Some sites
already stamp `:session`/`:run-id` (`findings.clj`; `migrate.clj` carries them).
The gap is sites that *know* the session and drop it:

- `tasks.nido-review/append-review-entry!` — resolves `session-from-cwd` →
  `{:project session}`, then appends `{:kind :review}` **without** `:session`. Stamp it.
- `tasks.nido-workstream/entry-add*` — appends `{:kind …}`; when run from a session
  cwd, resolve and stamp `:session`.
- autonomous/coordinator appends — stamp `:session` + `:run-id` from the run context.
- genuinely system-generated entries (`notion_sync` `:note`) stay **unstamped** —
  they have no producing session, and leaving them blank is honest.

### Carry it through

`index-row` and `entry->report` (in `nido.work`) currently surface only
`{:seq :kind :at :title}` / a report map — they discard `:session`/`:run-id`. Carry
them through so each ledger entry knows its origin.

### Resolve to the raw record

A resolver: ledger entry → transcript location.

- `:run-id` → run-dir `agent.log` (`cstate/run-agent-log`) + artifacts
  (`cstate/run-artifacts-dir`).
- `:session` (session-name) → `session-state/session-home-dir` → the claude
  transcript (`.jsonl`), also reachable via the run's `session-home` symlink.

Both survive session-home reclamation (transcript is keyed by the home path; see
`project_session_home_ephemeral`).

### Web affordance

Provenance is a **web** feature (the ledger reader lives on the web pane; the TUI is
ledger-free). Each ledger entry shows a small **origin tag** (e.g. `· impl-BR-1` or
`· run-…`). A suspicious entry exposes a **"view transcript"** affordance that opens
`agent.log` / reveals the transcript path. Deliberately minimal — a thread from the
record to the raw session, not a transcript viewer. The TUI gains nothing here.

## What is removed

- The session **list** on both surfaces (TUI `detail-rows` rows; web
  `workstream-pane` table rows). Its jobs move to the environment panel (run) and
  ledger provenance (forensics).
- The uncommitted TUI trim + inline-info work from this session is **superseded** by
  Phase 1 — it was the stepping-stone, not a separate deliverable.

## Phasing

**Phase 1 — environment panel** (delivers the primary ask: start/stop the latest
version + browser URL, both surfaces):
- `work/environment` resolver.
- TUI detail → one environment block; consolidated action set.
- Web `workstream-pane` → collapse the session table to one block above the ledger.

**Phase 2 — ledger provenance & forensics** (lower-frequency half; can follow once
Phase 1 proves out):
- Stamp `:session`/`:run-id` at every append site; carry through `index-row`.
- Origin tag + "view transcript" drill-down + transcript resolver on the web ledger.

Removal of the "bag of sessions" is not a separate step — it *is* Phase 1.

## Testing

- **Resolver** (`work/environment`): unit tests for stage resolution (impl over
  triage, latest among candidates), and `nil` for a triage-only / no-work-session
  workstream. Down-but-provisioned impl still resolves.
- **TUI**: the detail view renders the environment block for the resolved session
  (URL, ports, actions) and the empty state when `nil`; the consolidated action keys
  route to the right lifecycle calls (redef the lifecycle/dev fns, assert the call).
- **Web**: `workstream-pane` renders one environment block (not a table) with the
  correct dev-state/URL/ports; empty state when `nil`. Pure hiccup assertions.
- **Provenance (Phase 2)**: `append-entry!` persists `:session`/`:run-id`;
  `index-row` carries them; the review/entry-add sites stamp `:session`; the
  resolver maps `:run-id`/`:session` to the expected agent.log / transcript path.

## Risks / open questions

- **Restart semantics** (session recycle vs app-only restart) — settled in the plan;
  the design only commits to a single Start/Stop/Restart state.
- **Provenance backfill** — legacy entries written before stamping have no
  `:session`; they simply show no origin tag / no drill-down. Graceful, no migration.
- **Resolver ambiguity** — if a workstream somehow has two equally-advanced work
  sessions, "latest" (most recent) breaks the tie; acceptable given single-environment
  is the model and multi-env is an explicit non-goal.
```
