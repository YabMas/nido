---
name: local-ci
description: Run brian's CI for the session you're in and fix findings. Bare `/local-ci` triages then STOPS for human approval before any edit. `/local-ci auto` drives autonomously — commits a clean tree, runs CI, applies mechanical fixes and halts on anything needing judgement (the path /drive-home composes). Invoked in-session from the session home.
---

# /local-ci

## Overview

Run brian's own CI for the session you're in, triage the failures into a
lane-grouped report, and fix them **only after the user approves**. This drives
the existing `bb nido:run … ci` task and brian's lane/dev agents (present
in-session). It does not reimplement CI or parse output in code.

## Two modes

- **bare `/local-ci`** — run CI, triage into a report, **STOP for human
  approval**, then fix only the approved subset. Refuses a dirty tree (tells you
  to commit). This is the default and the rest of "## Flow" describes it.
- **`/local-ci auto`** — the **autonomous** path (what `/drive-home` composes):
  commit a clean tree, run CI, apply **mechanical** fixes and **halt** on
  anything needing judgement, no approval gate. See "## Autonomous mode (auto)".

Pick the mode from the argument: no argument ⇒ bare; the literal `auto` ⇒
autonomous.

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

Image caching is ENABLED and jj-safe: brian keys the CI image cache on the
working-tree CONTENTS (sha256 of the bytes that get COPY'd — brian PR #3380),
so a cache hit always means "same source" regardless of the git/jj view.
Expect a slow full Docker rebuild only when source changed since the last CI
run; unchanged re-runs (flake re-runs, e2e right after unit) reuse the image.
Do not pass `--no-cache` or warn about forced rebuilds. Run, capturing output:

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

## Autonomous mode (auto)

Reached only when invoked as `/local-ci auto`. No approval gate — this is the
path `/drive-home` composes, so it must be able to fix and re-run without a human
in the loop. It reuses the **same** CI command and the **same Routing table**
below; only the gate differs.

### 1. Commit a clean tree

CI Docker-COPYs the worktree, so `:ci` aborts on a dirty `jj st`. In auto mode,
commit any working-copy changes yourself (bare mode refuses instead):

```bash
cd worktree
jj st                                   # if dirty…
jj commit -m "chore(ci): clean tree for CI"   # …fold it in
cd ..                                   # back to the session home
```

(A clean tree ⇒ skip the commit.) These `chore(ci): …` commits are throwaway
scaffolding — `/squash` folds them into the final commit, so the message
doesn't matter.

### 2. Run CI and triage

Run `bb nido:run :project <project> <session> ci` (see "### 2. Run CI" for the
caching model). Separate flake/infra from real regressions
exactly as in "### 4. Triage". Get the real error from logs; don't guess from the
job name.

### 3. Tiered fix — no gate

Classify every real failure with the **Routing** table below (single source of
truth):

- **AUTO-FIX (no approval)** — failures whose owner is "fix directly
  (mechanical)". Fix and commit in the worktree
  (`cd worktree; jj commit -m "chore(ci): fix <job>"; cd ..`), then re-run CI once.
- **HALT (report, do not fix)** — every other row (anything the table routes to a
  domain agent or skill). Produce the lane-grouped report (job, salient error
  lines, owning agent) and **stop**. Do not dispatch fix agents.

**Loop guard:** at most **two** mechanical fix→re-run cycles. Still red after
that, or a non-mechanical failure appears ⇒ halt and report. Never loop CI to
green.

Auto mode emits **no** coordinator ledger events — when `/drive-home` composes it,
`/drive-home` records the halt in its ledger.

## The Approval Gate (do not skip)

**This gate applies to bare `/local-ci` only.** `/local-ci auto` deliberately
skips it — see "## Autonomous mode (auto)".

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
  the centralized `:ci` config (the clean-worktree gate and the private-dep
  token wiring live there).
- Running `bb nido:session:up` first — unnecessary; brian's CI is self-contained.
- Auto-fixing before approval (see The Approval Gate).
- Looping CI to green — this skill does ONE run; re-run only after fixes, on request.
- Treating flaky e2e / Docker infra errors as code bugs — separate them before routing.
- Running `auto` when you wanted review, or bare mode inside `/drive-home` — the
  bare gate halts the autonomous flow; `/drive-home` composes `/local-ci auto`.
