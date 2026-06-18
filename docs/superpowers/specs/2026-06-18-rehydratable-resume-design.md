# Re-hydratable resume + visible outcomes — design

**Date:** 2026-06-18
**Status:** approved (brainstorm), spec under review
**Sub-project:** #1 of the durable-parked-review effort (#2 = dormant-aware liveness, later).

## Goal

A parked review can always be resumed from the gate inbox — or fail clearly — **even after its runtime (session-home, worktree, services) was reclaimed** — because the durable identity (claude transcript + run record + ledger) survives. And the resume outcome is always visible on the session record (and thus the UI), never the silent hang it is today.

## Problem (verified on live data)

The gate inbox showed five parked triage gates. Clicking "Apply" (reply) on one did nothing visible. Investigation:

- The run is still `:awaiting-review`; the **claude transcript survives** at `~/.claude/projects/<encoded-home-path>/<claude-session-id>.jsonl`. The conversation is intact.
- But the **session-home + worktree were reclaimed out-of-band** (a `down!`/`destroy!`/worktree cleanup that never called `session/archive!`), leaving a `:substrate :live` record pointing at a dead runtime.
- `resume!` launched `claude --resume` with cwd = the now-**dangling** session-home symlink → `ProcessBuilder` failed with ENOENT → the turn died instantly, re-parked, and logged to `*err*` only.
- The web surfaced nothing: the optimistic state is plumbed but unrendered, and `gate-resolve!`'s future clears it the instant `resume!` *returns* (before the turn completes), so even the plumbed state would lie.

Root model insight: a **session-home is ephemeral, regeneratable scaffolding** (briefing, `.mcp.json`, worktree/`.claude` symlinks, shim — all rebuilt idempotently by `up!`); the **resumable conversation is durable** and only *looks* tied to the home because the home path is the transcript's key. So reclaiming runtime is correct; assuming it's still live on resume is the bug.

## Scope

**In:** `resume!` re-provisions a reclaimed home before resuming; resume outcomes (success/failure) are recorded durably on the session and surfaced in the inbox; immediate pane feedback on a reply click.

**Out (Sub-project 2 — dormant-aware liveness):** a `:dormant` substrate state; a reconciliation probe that flips stale `:live` records to `:dormant`/terminal ahead of a click; clean transcript-lost detection; hiding truly-lost gates; the inbox distinguishing live/dormant/lost *before* you act. Here we only make resume *re-hydrate* and *report*.

## Components

### 1. `resume!` re-hydrates a reclaimed home (`nido.coordinator.resume`)

`resume!` already: finds the parked session, recovers its run via `runs/find-for-session` (for `:claude-session-id` + run-id), flips the session `:parked → :running`, and runs one bounded turn on a background thread (`run-turn!`), re-parking in a `finally`.

Change `run-turn!` to **probe the runtime and re-provision if needed, before launching**:

- `home-present? run` → `(fs/exists? (cstate/run-session-home-link (:id run)))`. `fs/exists?` follows symlinks, so a dangling session-home link reads absent.
- If **present** → launch `claude --resume` as today (instant path).
- If **absent** → call `runs/spawn-session-for-run! run` to re-provision (idempotent `up!` recreates the worktree-if-missing + restarts services per the run's `:session-profile`; rewrites the shim/run-link/session-home link), **then** launch `--resume`. The transcript is keyed by the deterministic home path, so resuming at the rebuilt-same-path picks the conversation back up.

`:lite` re-provision is cheap (symlink worktree); `:full` is a real rebuild (slower but correct). It all runs inside `run-turn!`'s existing background thread, so the request never blocks.

A genuinely-lost conversation (transcript also gone) is not specially detected here — it surfaces as a failed resume turn (§2). Clean lost-detection + reconciling the gate out is Sub-project 2.

### 2. Durable, visible outcomes

Record the outcome where it survives daemon restarts and is visible to every surface — on the session record, not a transient web atom.

- **`nido.coordinator.session` gains `set-error!`** `[project ws-id session-name err]` — writes `err` (or nil to clear) onto the session's `:autonomy :error` (the field already exists, seeded nil by `autonomy-from`). Mirrors `set-phase!`; throws on a human/non-autonomous session.
- **`run-turn!` records its outcome.** On a clean success it clears `:error`; on any failure (re-provision threw, exec failed, claude errored / did no work) its `catch` writes `:error {:at <iso> :reason <kw> :message <str>}` before the `finally` re-parks. (The existing `*err*` log stays.)
- **The gate facet surfaces it (`nido.work`).** `->gate` adds `:resume-error` — the parked session's `:autonomy :error` (nil when none). `session-facet` (the detail view) adds `:error` too. (A resume *in flight* makes the session `:running`, so it temporarily isn't a gate and drops out of the inbox until it re-parks — expected; the error appears when it re-parks.)
- **The web renders it (`nido.ui`).**
  - The reply POST (`gate-resolve!` for `:reply`) returns a **pane** fragment (`id="gate-pane"`) showing "Resuming… (re-hydrating the session if needed)" — immediate feedback on the pane the human is looking at, replacing the silent nothing.
  - `gate-card` renders a persistent **"⚠ last resume failed: \<reason\>"** badge for any gate carrying `:resume-error`. A successful reply clears `:error`, so the badge disappears and the fresh report shows.

This also removes the latent "clears too early" bug: the outcome lives on the session, so nothing depends on `gate-resolve!`'s future tracking turn completion.

## Data flow

Reply click → `POST /gate/:p/:ws/reply` → `gate-resolve!`:
1. respond with the pane "Resuming…" fragment (immediate).
2. `future`: `resume!` → flip session `:running` → `run-turn!`:
   - home present? launch `--resume`. absent? `spawn-session-for-run!` (re-provision) then launch.
   - success → clear `:error`, agent writes its next report / advances the ticket, re-park (or the run completes and the workstream advances `triage → ready`).
   - failure → `set-error!` with the reason, re-park.
3. The inbox's 3s poll reflects the result: the gate either advances/leaves (success) or re-appears with the `:resume-error` badge (failure).

## Error handling

- `run-turn!` never throws on the request thread (all in the background future; failures are caught, recorded as `:error`, and re-parked). The `*err*` log line stays for operator visibility.
- A re-provision failure (e.g. the branch the `:lite` worktree symlinks is gone) is caught and recorded as `:error :reason :rehydrate-failed` — the human sees a clear badge, the session stays parked for a retry.
- A no-such-conversation `--resume` (truly lost) records `:error :reason :resume-failed` (generic) — honest, rare; Sub-project 2 adds clean `:conversation-lost` detection + reconcile-out.

## Testing

- **`resume!`/`run-turn!`** (`with-tmp`, redef `agent/launch!` + `runs/spawn-session-for-run!`):
  - home present → launches `--resume`, does NOT call `spawn-session-for-run!`.
  - home absent → calls `spawn-session-for-run!` once, then launches `--resume`; session re-parks.
  - launch throws → `:error` recorded on the session (with a reason) + re-parked; no throw escapes.
  - re-park-on-success clears a pre-existing `:error`.
- **`session/set-error!`**: sets/clears `:autonomy :error`; throws on a human session.
- **`nido.work`**: a gate whose parked session carries `:autonomy :error` surfaces `:resume-error`; absent → nil.
- **`nido.ui`**: the reply POST returns a `gate-pane` fragment containing "Resuming…"; `gate-card` renders the `:resume-error` badge when present (pure view tests).

## Non-goals

Sub-project 2 (dormant-aware liveness): the `:dormant` substrate state, the reconciliation probe, transcript-lost detection, hiding lost gates, and surfacing dormant-vs-live *before* a click. No change to the pure `live?`/`parked?` predicates here. No "keep parked reviews warm / never reclaim" pinning (a later tuning knob).
