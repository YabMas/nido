# TUI runs screen: resume on enter, inspect on `w`

Date: 2026-05-21
Status: Design — ready for implementation plan

## Problem

The runs surface in the TUI groups runs that the user should look at —
`:awaiting-review`, `:failed`, `:halted` — under "Needs attention". By the
time the user opens the TUI, the watchdog has almost always idle-stopped
the corresponding session, which removes the session-home.

Today both row actions on that screen depend on the session-home:

- `↵` enters `<session-home>/`
- `w` enters `<session-home>/worktree/`

So the two most prominent keys on the most prominent rows fail with:

```
[nido:tui] action failed: No session home for '<run-id>' — is it running?
```

This is structural: the row group's purpose ("the agent finished, come
look") is mismatched with actions that require live services.

## Resolution

Split the two keys along intent and decouple `w` from session-home:

- **`↵` (resume).** Auto-`session:up` (idempotent), then cd into the
  session-home. No prompt. If the session is already up, the up call is a
  no-op and only the cd happens.
- **`w` (inspect).** Resolve directly to the on-disk worktree path
  (`~/Code/<project>-worktrees/<session>/`), independent of session-home.
  No services touched. If the worktree itself is gone, print a focused
  error and stay in the TUI.

`d` (details modal), `D` (delete-run), `f` (fire), `h` (halt),
`c` (clear breaker) are unchanged.

`claude --resume` is **not** auto-launched. After `↵` you land in the
session-home and pick the agent (matches today's launchpad model).

A persistent "review snapshot" of session-home that outlives `session:down`
is **out of scope** for this design.

## Changes

### `nido.session.lifecycle`

`enter!` gains an `:auto-up?` opt (alternatively: a sibling `resume!` that
delegates). When `:auto-up? true` is passed:

1. Call `up!` for the named session (idempotent).
2. Proceed with the existing enter logic (resolve `:cd` target, write
   `~/.nido/.last-cd`).

Without `:auto-up?` the function behaves exactly as today.

`enter!` with `:cd :worktree` learns a session-home-independent fallback:

- Compute the worktree path from project config:
  `<project>-worktrees-root / <session-name>` (the same path
  `session:up` would symlink into the session-home as `worktree`).
- If the session-home symlink exists, prefer it (today's behavior, keeps
  things consistent for live sessions).
- If the session-home is missing **but the on-disk worktree exists**,
  resolve to the on-disk path and write it directly.
- If neither exists, throw an `ex-info` with message
  `"Worktree no longer exists for '<session>'"` and a `:hint` pointing at
  `session:up` to recreate the worktree.

The current "No session home for '<session>' — is it running?" error
stays as the failure mode for `enter!` with `:cd :home` **without**
`:auto-up?`. The TUI runs screen never takes that path, so the user no
longer sees it from that surface.

### TUI (`nido.tui`, `tasks.nido-tui`)

Action queue shape — extended with an auto-up flag for the runs screen:

- `[:enter-run project session :home]` is reinterpreted in the bb task
  wrapper as **up + enter**: the wrapper calls `session:up` (printing
  output to the normal terminal, identical UX to today's `[:up p s]`
  arm), then `session:enter` with `:cd home`.
- `[:enter-run project session :worktree]` calls a worktree-direct
  variant: no `up`, resolve the on-disk worktree path via the lifecycle
  fallback above. On success, write `~/.nido/.last-cd` and exit. On
  missing worktree, print the focused error and re-enter the TUI rather
  than leaving the user at a stale cwd.

The sessions screen continues to use `[:enter project session :home]` /
`[:enter project session :worktree]` with their current semantics — a
session listed there is expected to be live, and "No session home" is a
correct failure mode for that surface.

Runs-screen legend updated:

```
[↵] resume  [w] inspect worktree  [d]etails  [D]elete  [f]ire  [h]alt  [c]lear breaker  [s]essions  [q]uit
```

### Tests

- `nido.session.launcher-test` / `nido.session.lifecycle-test`: cover the
  new `:auto-up?` path (running session = no-op + cd; downed session =
  up + cd) and the worktree fallback (session-home missing, worktree
  present → resolves; both missing → focused error).
- TUI handler test: `[:enter-run … :home]` produces the up+enter command
  sequence; `[:enter-run … :worktree]` produces the worktree-direct
  command.

## What doesn't change

- Watchdog idle-stop behavior.
- `session:down` still removes session-home; worktree preserved.
- Sessions-screen action semantics.
- Run state machine and group classification (`runs-view/classify`).

## Out of scope

- Auto-launching `claude --resume <session-id>`.
- Persisting a review-only session-home that outlives `session:down`.
- Watchdog tuning to keep awaiting-review sessions alive longer.
