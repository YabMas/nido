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

Two diff-scoped hygiene lints in the `Checks` job — `Comment Lint` and
`Ticket Refs` — are reproduced locally first, in ~2s, so a comment typo does not
cost a five-minute Docker cycle to discover. See "### 2. Hygiene pre-flight".

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

### 2. Hygiene pre-flight

Two checks in the `Checks` job are pure diff scans: `Comment Lint` (comment
archaeology, and comments naming a symbol the same diff deletes) and
`Ticket Refs` (non-canonical ref shapes on added lines). Reproduce them before
spending a CI cycle:

```bash
cd worktree
bb <LIB>/hygiene-scan.bb
```

`<LIB>` is this skill's own lib dir — `$PWD/.claude/skills/local-ci/lib` from the
session home, else `$HOME/Code/nido/.claude/skills/local-ci/lib`. Resolve it
once, then use the **literal absolute path**: shell variables do not survive
between Bash calls (each call is a fresh shell; cwd persists, the environment
does not). The script must run with **cwd = the worktree** — that is where jj
answers about the right tree and where brian's `bb.edn` puts its own lint
namespaces on the classpath.

Exit 1 ⇒ the `Checks` job **will** fail. Fix these first (see "## Hygiene fixes")
and re-run the scan until it is clean, then continue to CI. Exit 0 ⇒ proceed.

The scan also reports two **advisory** classes that CI has no check for at all:

- **migration narration** in comments (`no longer`, `previously`, `formerly`,
  `replaces the`) — the soft vocabulary brian's comment-lint deliberately
  refuses to blocker-gate ("handled by review lanes, NOT this commit blocker").
  This is that review lane. It is also the class human reviewers actually flag.
- **stray artifacts** — added debug output, commented-out code, `.orig`/`.rej`
  files.

Fold both into the triage report in step 5. They are findings like any other,
and **neither is auto-fixed** — every fix for them is a code edit (see
"## Hygiene fixes").

**Why not just run `bb lint:comments`.** In a session worktree those tasks are
structurally broken, and broken *silently* — see "## Common mistakes".

### 3. Run CI

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

### 4. Green path

Exit 0 → report "CI green, nothing to fix", plus any advisory hygiene items from
step 2. Stop.

### 5. Triage

On failure, read the output: enumerate failed jobs (`:check`, `:unit`,
`:integ-{a,b,c}`, `:e2e-{1,2,3}`) and brian's `ACTION REQUIRED:` tail.
Distinguish real regressions from flake/infra (Docker errors, e2e flakiness —
the summary reports flaky counts; a partition imbalance is not a code bug).
Produce a **lane-grouped findings report**: for each real failure — the job, the
salient error lines, a proposed fix, and the **owning agent** (see Routing). Get
the actual error from logs; don't guess from the job name.

### 6. STOP for approval

Present the report. Ask which findings to fix: all / a named subset / none.
**Make no edits** — see The Approval Gate.

### 7. Fix (only after approval)

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

### 2. Hygiene pre-flight, then CI

Run the pre-flight exactly as in "### 2. Hygiene pre-flight" above. Blocking
hits are auto-fixable here (see "## Hygiene fixes" — the gate is what makes
them safe without a human), so fix, gate, commit, and re-scan before running CI.
The two advisory classes are **reported, never fixed**, in auto mode as in bare.

Then run `bb nido:run :project <project> <session> ci` (see "### 3. Run CI" for
the caching model). Separate flake/infra from real regressions exactly as in
"### 5. Triage". Get the real error from logs; don't guess from the job name.

### 3. Tiered fix — no gate

Classify every real failure with the **Routing** table below (single source of
truth):

- **AUTO-FIX (no approval)** — failures whose owner is "fix directly
  (mechanical)" or "hygiene fix (gated)". Fix and commit in the worktree
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

Steps 1–6 NEVER edit files. Present the triage report and wait for explicit
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

## Hygiene fixes

`Comment Lint` and `Ticket Refs` are **not** clj-kondo-class mechanical fixes.
The linter checks a token; the fix is a judgement about what the comment should
say instead. What makes them auto-fixable anyway is that every fix lands inside
a comment, and that is *checkable*.

**Fix inside comments only. Then prove it:**

```bash
cd worktree
bb <LIB>/no-code-change.bb --from <rev-before-your-edits> --to @
```

Three outcomes per file:

- **`proven`** — top-level forms identical, or every changed line is a comment.
  (Whitespace, `;` comments and `#_` blocks are not sexpr-able, so a
  comment-confined edit leaves the forms bit-identical by construction.) This is
  the outcome to aim for.
- **`needs-eyes`** — a real string in the program changed: a docstring, a string
  literal, or a quoted span in a non-Clojure file. The gate prints each one
  before → after, narrowed to the region that differs, and holds. `(def timeout
  "30s")` → `"9000s"` lands here too, which is why it is never silent.
  **Bare mode:** show the strings, get approval, re-run with `--allow-prose`.
  **Auto mode:** never pass `--allow-prose` — halt and report the strings. A ref
  inside a `testing` label or an allium `open question "…"` legitimately lands
  here; that halt is correct, not a bug.
- **`CODE`** — forms differ outside strings, a file was added or removed, or the
  file type has no comment grammar. This is a bug in **your fix**, not a finding:
  `jj restore <path>` and redo the edit inside the comment.

**Write the comment, don't redact it.** Deleting the banned token leaves a
comment that still narrates a transition, just more vaguely. Read the code it
sits above and describe *that*:

> ✗ `;; This block previously described a RED phase that is long over…`
> ✗ `;; This block described a phase that is over…` (token gone, story kept)
> ✓ `;; The mocked var is `routing.router/route-stream`.`

Same for a comment naming a symbol the diff deletes — re-anchor it on what
survives, rather than citing a name that is about to stop existing:

> ✗ `;; brian's own `max-retries` is deleted along with the direct retry loop`
> ✓ `;; Retries belong to the babel router; brian sets no retry policy here.`

`lint-ok: deleted-ref` / `lint-ok: refs` on the line are escape hatches, not
fixes. Use one only when the comment genuinely must name the departed symbol,
and say why in the report.

**Ticket refs: never touch the PR body or a commit message.** Native `#N` /
`owner/repo#N` are *required* there — GitHub honours `Fixes`/`Closes` auto-close
and cross-repo backlinks only on the native forms, and the convention exempts
them on purpose. Canonicalizing them breaks auto-close.

**Advisory classes are reported, never fixed.** Removing an added `println`
changes what the program emits; deleting a commented-out block is a judgement
about whether someone parked it there deliberately; deleting a `.orig` changes
the file set. All three are code edits, so they leave through the report.

## Routing (failure class → owner in the fix phase)

Defer to brian's `docs/reference/agent-delegation.md` for anything ambiguous.
Starter map:

| Failure | Owner |
|---|---|
| format / lint (clj-kondo) / lint-deps / shellcheck / css / js build | fix directly (mechanical) |
| `Comment Lint` / `Ticket Refs` (the diff-scoped hygiene lints) | hygiene fix (gated) — see "## Hygiene fixes" |
| migration narration / stray artifacts (advisory, no CI check) | report only — never auto-fixed |
| i18n / translation | `translate-i18n` skill |
| migrations | `database-dev` (deploy/safety angle: `lane-db-deploy`) |
| Allium specs | `allium:weed` |
| unit / integration test | `test-dev`, or the domain `lane-*` by failing namespace |
| e2e (Playwright) | `e2e-dev` |
| version gates / e2e partition rebalance / unclear | surface to the user with the exact `ACTION REQUIRED` instruction; do not guess |

## Common mistakes

- **Verifying a diff-scoped lint by running it in the worktree.** `bb
  lint:comments` and `bb lint:refs` shell out to git. A session worktree is a
  non-colocated jj workspace nested inside the colocated source repo, so git
  walks up and binds to the **parent** repo. Measured on a live session:
  `bb lint:comments` scanned the parent's **7-file** diff instead of the
  session's **114-file** branch and printed a confident `OK`; `bb lint:refs`
  reported `{:status :failed :reason :engine-unavailable}` — unable to find an
  engine sitting right there in the worktree — and **exited 0**. Neither
  announces it is wrong. Use `hygiene-scan.bb`, which computes the diff with jj
  and drives brian's own rule functions over it.
- **Treating `Comment Lint` as clj-kondo-class mechanical.** It matches on
  "lint" and it is not. See "## Hygiene fixes".
- **Deleting the banned token instead of rewriting the comment.** Passes the
  linter, leaves the archaeology.
- **Passing `--allow-prose` to get to green.** The flag means "a human read
  these strings", and the report has to show them. Auto mode never passes it.
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
