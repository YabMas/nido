---
name: local-ci
description: Use when asked to run local CI, run `bb ci`, or fix CI findings (lint/test/e2e/migration failures) for the brian session you're working in. Invoked in-session from the session home.
---

# /local-ci

## Overview

Run brian's own CI for the session you're in, triage the failures into a
lane-grouped report, and fix them **only after the user approves**. This drives
the existing `bb nido:run … ci` task and brian's lane/dev agents (present
in-session). It does not reimplement CI or parse output in code.

## When to use

- Asked to "run local CI", "run `bb ci`", or "fix CI findings" for the session.
- You are running **from a nido session home** (cwd
  `~/.nido/sessions/<project>/<session>/` — where `bb nido:run` works and this
  skill is injected). If you're in the worktree or elsewhere, see Resolve below.

## Flow

### 1. Resolve the session from cwd

Run `pwd`. A session home is `…/.nido/sessions/<project>/<session>/`. Split the
path on `/sessions/`: the **first** segment after it is `<project>`, and the
**rest** (which may contain slashes, e.g. `fix/add-delay`) is `<session>`.
Sanity-check that cwd contains a `worktree` symlink and a `bb.edn`.

If cwd is not a session home (no `/sessions/` segment, or `bb nido:run` isn't
found), stop and tell the user to run it from the session home —
`bb nido:session:enter :project <p> <session>` lands there.

### 2. Run CI

Warn the user this is a slow, full Docker rebuild (the `:ci` command uses
`--no-cache` for jj correctness — its cache key derives from git HEAD/diff,
unreliable under jj). Then run, capturing output:

```
bb nido:run :project <project> <session> ci
```

No `nido:session:up` needed — brian's CI is self-contained Docker, path-isolated
by its own `CI_SUFFIX`.

**Clean-worktree gate:** the `:ci` command refuses to run if the worktree is
dirty (output starts `nido: working copy is dirty …`, followed by `jj st`).
This is **not** a CI failure — the Docker build COPYs the worktree, so a dirty
tree would test code the remote can't see. Don't triage it as a failed job:
tell the user to commit their changes, then re-run `/local-ci`. Do **not**
commit on their behalf.

### 3. Green path

Exit 0 → report "CI green, nothing to fix." Stop.

### 4. Triage

On failure, read the output: enumerate failed jobs (`:check`, `:unit`,
`:integ-{a,b,c}`, `:e2e-{1,2,3}`) and brian's `ACTION REQUIRED:` tail.
Distinguish real regressions from flake/infra (Docker errors, e2e flakiness —
the summary reports flaky counts; a partition imbalance is not a code bug).
Produce a **lane-grouped findings report**: for each real failure — the job, the
salient error lines, a proposed fix, and the **owning agent** (see Routing). Get
the actual error from logs; don't guess from the job name.

### 5. STOP for approval

Present the report. Ask which findings to fix: all / a named subset / none.
**Make no edits** — see The Approval Gate.

### 6. Fix (only after approval)

For each approved finding, delegate to its owning agent via the Agent tool with
a focused prompt: the worktree path (`cd worktree` from the session home, or
`~/Code/brian/.worktrees/<session>`), the failing job, and the error. Mechanical
findings you may fix directly. **No auto-commit** — leave changes in the worktree
for review (if it's a jj worktree, follow jj commit hygiene). Then suggest
re-running `/local-ci`.

## The Approval Gate (do not skip)

Steps 1–5 NEVER edit files. Present the triage report and wait for explicit
approval before ANY fix — **including "obvious", "trivial", or "just
formatting" ones.** The whole point is review-before-edit.

| Rationalization | Reality |
|---|---|
| "It's just a lint/format nit" | Still wait. Present it; fix on approval. |
| "Fixing now saves a round-trip" | Review-before-edit is the point. Stop. |
| "The fix is obvious / low-risk" | Obvious ≠ approved. Present it, wait. |
| "They said 'fix all findings' upfront" | That authorizes the fix *phase*, after triage — not edits during triage. |

**Red flags — STOP:** editing a worktree file before the user picks findings;
dispatching a fix agent during triage; "I'll just quickly fix this one."

## Routing (failure class → owner in the fix phase)

Defer to brian's `docs/reference/agent-delegation.md` for anything ambiguous.
Starter map:

| Failure | Owner |
|---|---|
| format / lint (clj-kondo) / lint-deps / shellcheck / css / js build | fix directly (mechanical) |
| i18n / translation | `translate-i18n` skill |
| migrations | `database-dev` (deploy/safety angle: `lane-db-deploy`) |
| Allium specs | `allium:weed` |
| unit / integration test | `test-dev`, or the domain `lane-*` by failing namespace |
| e2e (Playwright) | `e2e-dev` |
| version gates / e2e partition rebalance / unclear | surface to the user with the exact `ACTION REQUIRED` instruction; do not guess |

## Common mistakes

- Parsing the session name as only the last path segment — slash-namespaced
  sessions (`fix/add-delay`) span multiple segments; take everything after
  `/sessions/<project>/`.
- Running `bb ci` directly in the worktree instead of `bb nido:run … ci` — skips
  the centralized `:ci` config (the `--no-cache` flag lives there).
- Running `bb nido:session:up` first — unnecessary; brian's CI is self-contained.
- Auto-fixing before approval (see The Approval Gate).
- Looping CI to green — this skill does ONE run; re-run only after fixes, on request.
- Treating flaky e2e / Docker infra errors as code bugs — separate them before routing.
