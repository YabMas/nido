# Intent-organized TUI + GitHub issue intake

**Status:** design approved, pre-implementation
**Date:** 2026-06-12
**Builds on:** `2026-06-05-workstream-session-model-design.md` (the source-agnostic
workstream spine + coordinator-session projection).

## Problem

The TUI's `:sessions` screen has become the de-facto primary surface, and it is
organized by the wrong axis. It reads the lifecycle *registry* and lists every
worktree-backed thing flat — manual `feat/*`/`fix/*` branches, workstream impl
sessions (`impl-br-####`), coordinator runs (`run-brian-triage-*`), and genuine
one-offs (`refshot`, `text2speech-latex`) — all in one undifferentiated bucket
with ports attached. It clutters quickly because **substrate is the wrong axis to
organize by.** Every managed session already has a better home in the
`:workstreams` surface; the flat list merely duplicates them without structure
and, being the default landing screen, wins attention.

## Principle

**Organize the surface you land on by *intent*; demote *substrate* to an ops view.**

The workstreams surface (intent-organized: stage-grouped, engagement-projected,
drill-in to sessions) becomes the home. The flat session list survives only as a
raw ops view you *act* on (ports, up/down/destroy) but never *organize* by.

This rests on one model commitment: **every session belongs to a workstream.**
There is no "loose session" concept distinct from workstreams — a one-off is just
a workstream with no external refs. That uniformity is what makes the rest cheap.

## The universal workstream model

`Workstream` already carries `:external-refs` as a *vector* of `{:adapter :id …}`.
A workstream is source-agnostic and holds zero or more refs. This is the entire
basis for the redesign — no model change is required for multiple source views.

Three **birth paths**, one record type:

| Birth | Enters at stage | Refs at birth | Promote |
|-------|-----------------|---------------|---------|
| Notion source poll | `:triage` | `:notion` | after HITL triage record |
| GitHub source poll | `:ready` (skip triage) | `:github-issue` | trivial gate (exists ⇒ promotable) |
| Manual `session:up` | loose (no stage lifecycle) | none | n/a — scratch |

"Claiming" dissolves: there is no background process deciding what attaches to
what. A session is *born into* a workstream and stays there.

## Multi-view TUI navigation

One cohesive TUI. Within a project, a set of **source-scoped views** the user
tabs between (peer views, navigated with `Tab` / `←→`), plus the demoted ops view.

```
projects
  └─ <project>                         ↹ Tab / ←→ cycles the views below
       ┌─────────────────────────────────────────────────────────┐
       │ ● Notion   ○ GitHub   ○ Scratch        │ Sessions (ops)  │
       └─────────────────────────────────────────────────────────┘
         Notion   → workstreams whose refs include :notion, stage-grouped
         GitHub   → refs include :github-issue, a flat queue (no triage)
         Scratch  → loose workstreams (no refs) — one-offs as work items
         Sessions → raw substrate: every running session, ports, up/down
            └─ ↵ on a workstream → its sessions → ↵ enters one in chat
```

**A view is `workstream-rows` filtered by an adapter predicate.** Notion, GitHub,
Scratch, and any future source share the same row shape, stage grouping,
engagement projection, drill-in, and key bindings. Adding a source later =
register one predicate + a tab. No new screen logic.

**Scratch vs. Sessions are different axes, both earn their place:**
- **Scratch** is the *intent* slice with no source — one-offs as first-class,
  drillable work items.
- **Sessions** is the *substrate* layer — all running worktrees flat, with ports,
  for "bounce a service / check a port / destroy" ops moments.

You stop *organizing* by substrate but never lose the ability to *act* on it.

### Scratch / loose workstreams: birth + reaping

- A manual `bb nido:session:up` (and the TUI `up`/`add` paths) mints a fresh
  **loose workstream** (no refs, no stage lifecycle) and births the session into it.
- A loose workstream that never grew a ref or a ledger entry is **auto-reaped on
  `session:destroy`** — so the one-off flow stays exactly as zero-ceremony as today.
- Loose workstreams show in the Scratch view grouped by engagement only
  (`active`/`idle`), not by the triage→ready→in-progress stage lifecycle.

## GitHub issue intake

The back half (impl session → draft PR → merge-detection → close) already exists.
Only the front half + the promote gesture are new.

### Intake source (`:github-issues`)

- Polls `gh issue list --assignee @me` (reuses the existing `gh` client +
  per-project `github.edn`).
- **Reconciles** a workstream per issue — upsert, **no spawn**. Each carries a
  `:github-issue` external-ref.
- **No cold-start suppression.** The Notion source suppresses the first poll's
  backlog because each new row auto-fires triage; GitHub fires nothing, so
  surfacing every currently-assigned issue *is* the intended queue.
- **Reverse reconcile:** an issue unassigned/closed upstream while its workstream
  is still *unpromoted* drops out of the queue. Once **promoted** (work underway),
  reconcile leaves it alone.

### Stage: born `:ready`, no triage

GitHub-sourced workstreams skip the triage→triaged leg and land directly in the
promotable band. They sit in the GitHub view as a queue. **Nothing auto-starts.**

### Promote: generalize to a workstream-level gesture

Today `promote!` is welded to the Notion ticket model (reads `tickets/*`, gates on
a triage record, enqueues the bug-specific `:plan-bug` trigger). Lift it to a
**workstream-level gesture that dispatches on source:**

- **Notion path** — unchanged: triage-record gate, `:plan-bug` envelope, payload
  seeded from the latest `:triage` ledger entry.
- **GitHub path** — trivial gate (the workstream exists ⇒ promotable); payload
  seeded from the **issue body** instead of a triage report.

Both hand off to the **same post-promote impl leg**. GitHub issues inherit exactly
whatever autonomy that leg already has — no new autonomy model is invented.

### Adapter naming

| Ref | Adapter | Stamped by | View filter |
|-----|---------|-----------|-------------|
| GitHub issue (intake) | `:github-issue` | `:github-issues` source poll | GitHub view |
| GitHub PR (merge-correlation) | `:github` | `/prepare-draft-pr` | — (merge poller) |

These are distinct on purpose. The payoff: an issue-born workstream carries
`:github-issue` from birth, gets the `:github` PR ref stamped at draft-PR time, and
the **existing merge poller closes it on merge**. Two refs on one workstream =
the full loop closes for free.

## Scope: what's actually new code

- TUI: multi-view tab navigation; per-source `workstream-rows` filtering; the
  Scratch view; demotion of the Sessions screen to last-tab ops view.
- Loose-workstream birth on manual `session:up`; reaping on `destroy`.
- `:github-issues` intake source (poll + reconcile, no spawn).
- `:ready`-at-birth path for source-without-triage workstreams.
- Source dispatch in `promote!` (workstream-level).

Everything downstream of promote — impl session, draft PR, merge-detection,
close — is reused unchanged.

## Implementation order

1. **Universal workstream + Scratch.** Loose-workstream birth/reaping; make every
   session belong to a workstream. Migrate the existing flat-list semantics so
   nothing is lost.
2. **Multi-view TUI.** Tab navigation; Notion + Scratch + Sessions(ops) views off
   the adapter-predicate filter. Notion view = today's `:workstreams` surface.
3. **GitHub view (verification slice).** Add the `:github-issues` source, the
   `:github-issue` adapter, `:ready`-at-birth, and the GitHub tab. This *proves*
   adding a source is trivial — the design's load-bearing claim.
4. **Workstream-level promote.** Source-dispatch in `promote!`; GitHub path seeds
   the impl payload from the issue body and reuses the post-promote leg.

## Deferred (flagged, not in scope)

- **Auto-attach by name:** a manual `impl-br-4672` session *could* auto-attach to
  an existing BR-4672 Notion workstream by name-matching. Nice-to-have, not
  load-bearing.
- **Multiple Notion views / GH-issues-from-other-assignees / other sources:** the
  predicate-per-view architecture supports them; none are built now.

## Testing

- Pure data layer (`workstreams-view`): adapter-predicate filtering produces the
  right rows per view; Scratch = empty-refs; GitHub = `:github-issue` refs.
- Loose-workstream lifecycle: birth on `up`, reaped on `destroy` only when
  ref-less and entry-less; *not* reaped once it has a ref or entry.
- `:github-issues` reconcile: cold-start surfaces full backlog; upstream
  unassign drops an unpromoted workstream; promoted workstream survives unassign.
- `promote!` source dispatch: Notion path unchanged (triage-record gate); GitHub
  path promotable without a triage record, payload carries the issue body.
- TUI navigation: tab cycles peer views; drill-in + enter-in-chat unchanged.
```
