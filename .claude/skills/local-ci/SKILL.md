---
name: local-ci
description: Use when asked to run brian's local CI for a nido-managed session, triage CI failures, or fix CI findings (lint/test/e2e/migration) for a brian worktree — from a claude running in the nido repo.
---

# /local-ci

## Overview

Run a project's own CI for a nido-managed session, triage the failures into a
lane-grouped report, and fix them **only after the user approves**. nido is a
thin orchestrator here: this skill drives the existing `bb nido:run` task and
brian's lane/dev agents (mirrored into nido). It does not reimplement CI or
parse output in code.

## When to use

- Asked to "run local CI", "run `bb ci`", or "fix CI findings" for a brian
  session / worktree.
- You are a claude running in the **nido repo** (cwd `~/Code/nido`), which has
  brian's lane agents mirrored in. This skill is NOT visible to the in-session
  agent (whose `.claude` resolves to brian's worktree) — that agent runs brian's
  CI directly.

## Invocation

```
/local-ci <session>            # project defaults to brian
/local-ci <project> <session>  # explicit project (only brian declares :ci today)
```

If `<session>` is missing, list candidates (`bb nido:session:list :project
<project>`, or `ls ~/Code/brian/.worktrees`) and ask which one.

## Flow

1. **Run.** Tell the user this is a slow, full Docker rebuild (the `:ci` command
   uses `--no-cache` for jj correctness — its cache key keys off git HEAD/diff,
   which is unreliable under jj). Then run, capturing output:
   ```
   bb nido:run :project <project> <session> ci
   ```
   Run it from the nido repo root. No `nido:session:up` needed — brian's CI is
   self-contained Docker, path-isolated by its own `CI_SUFFIX`. (Missing
   worktree → the task errors and points you to `session:up`.)
2. **Green path.** Exit 0 → report "CI green, nothing to fix." Stop.
3. **Triage.** On failure, read the output: enumerate failed jobs (`:check`,
   `:unit`, `:integ-{a,b,c}`, `:e2e-{1,2,3}`) and brian's `ACTION REQUIRED:`
   tail. Distinguish real regressions from flake/infra (Docker errors, e2e
   flakiness — the summary reports flaky counts; a partition imbalance is not a
   code bug). Produce a **lane-grouped findings report**: for each real failure
   — the job, the salient error lines, a proposed fix, and the **owning agent**
   (see Routing). Get the actual error from logs; don't guess from the job name.
4. **STOP for approval.** Present the report. Ask which findings to fix:
   all / a named subset / none. **Make no edits** — see The Approval Gate.
5. **Fix (only after approval).** For each approved finding, delegate to its
   owning agent via the Agent tool with a focused prompt: the worktree path
   (brian: `~/Code/brian/.worktrees/<session>`), the failing job, and the error.
   Mechanical findings you may fix directly. **No auto-commit** — leave changes
   in the worktree for review (if it is a jj worktree, follow jj commit
   hygiene). Then suggest re-running `/local-ci <session>` to verify.

## The Approval Gate (do not skip)

Steps 1–4 NEVER edit files. Present the triage report and wait for explicit
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

Defer to brian's `docs/reference/agent-delegation.md` (readable from nido) for
anything ambiguous. Starter map:

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

- Running `bb ci` directly in the worktree instead of `bb nido:run … ci` —
  skips the nido path and risks the wrong cwd.
- Running `bb nido:session:up` first — unnecessary; brian's CI is self-contained.
- Auto-fixing before approval (see The Approval Gate).
- Looping CI to green — this skill does ONE run; re-run only after fixes, on request.
- Treating flaky e2e / Docker infra errors as code bugs — separate them before routing.
