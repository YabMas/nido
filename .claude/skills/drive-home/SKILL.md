---
name: drive-home
description: Take the current nido session's branch (with or without a draft PR) home — rebase on origin/main, auto-resolve trivial conflicts, run CI and auto-fix mechanical failures, squash the branch into one coherent commit (layering narrated in the body), regenerate the PR title/description, then mark the PR ready and put it on the merge queue. Halts for human judgement on conflicts or test failures it shouldn't guess at. Usage: /drive-home
---

# drive-home

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/drive-home/`
> and is injected into every spawned session's composed `.claude/skills/` (see
> `nido.session.launcher/compose-claude-dir!`). Sibling of `/local-ci` and
> `/prepare-draft-pr`.

## What this is

One command that drives the **current session's** branch from "work written" to
"on the merge queue":

1. rebase on a fresh `origin/main`,
2. auto-resolve **only** trivial conflicts (halt on anything semantic),
3. run CI and auto-fix **only** the mechanical class (halt on anything needing judgement),
4. squash the whole branch into **one** coherent commit whose body narrates the
   layers, and regenerate the PR title/description from it,
5. mark the draft PR ready and enable auto-merge (native merge queue).

It is **autonomous within a safe boundary** and **stops for a human** the moment
real judgement is required. It assumes the nido 1:1 invariant — one session, one
branch, one PR — so it takes **no arguments**. (Splitting a session into several
PRs is a separate, out-of-scope concern; spin a sibling session per branch.)

## Relationship to /local-ci — read this before "simplifying"

drive-home does **NOT** invoke `/local-ci`. `/local-ci` has a hard approval gate
("never edit a file without explicit human approval"), which is incompatible with
autonomous fixing — it would halt the flow dead. Instead drive-home:

- runs the **same underlying CI command** (`bb nido:run … ci`), and
- applies an **autonomous, non-gated tiered triage**, and
- **reuses `/local-ci`'s definitions by reference** — the mechanical-fix class
  and the lane routing table in `local-ci/SKILL.md`. Do not fork a second copy.

If you are tempted to replace the CI step with a call to `/local-ci`: don't. The
gate is the whole reason this skill exists separately.

## 1. Resolve the session from cwd

Run `pwd`. A session home is `…/.nido/sessions/<project>/<session>/`. Split the
path on `/sessions/`: the **first** segment after it is `<project>`; the **rest**
(which may contain slashes, e.g. `fix/add-delay`) is `<session>`. Sanity-check
that cwd contains a `worktree` symlink and a `bb.edn`.

Do **not** trust `bb nido:session:link:list`'s echoed session name for
slash-namespaced sessions — it can mis-resolve (`feat/x/y` → `feat`). Derive
`<project>`/`<session>` yourself from cwd as above.

If cwd is not a session home, stop and tell the user to run it from one
(`bb nido:session:enter :project <p> <session>` lands there).

## 2. Acquire the PR

All `gh`/`git`/`jj` work happens **inside the worktree** — `cd worktree` from the
session home first (the session home itself is not git-colocated). Read the
current branch's PR:

```bash
cd worktree
gh pr view --json number,url,state,isDraft,headRefName,mergeStateStatus
```

- **PR exists** → keep its `number`, `url`, `headRefName` (the branch). Reuse it.
- **No PR** (`gh` errors "no pull requests found for branch") → create one by
  running the **`/prepare-draft-pr` skill** now (it opens the draft PR *and* wires
  the three correlation links the merge poller needs: session `:pr`, workstream
  `:github`, Notion `GitHub PR`). Then re-run the `gh pr view` above to read back
  the canonical PR identity.

> Discovery uses `gh pr view` (not the session `:pr` link): it asks GitHub for the
> current branch's PR directly, so it's correct on idempotent re-runs and doesn't
> depend on nido's link bookkeeping — which can mis-resolve slash-namespaced
> sessions. The `:pr` / `:github` links are stamped by `/prepare-draft-pr` for the
> merge poller; they aren't needed for discovery here.

Never hand-roll `gh pr create` here — delegate to `/prepare-draft-pr` so the
poller bookkeeping is correct.

## 3. Rebase onto origin/main

From the worktree:

```bash
jj git fetch
jj rebase -b @ -d main@origin
```

`-b @` rebases the whole branch containing the working copy onto the freshly
fetched trunk — no bookmark name needed. If the branch is already on top of
`main@origin`, jj reports "Nothing changed" — that's fine, continue.

Then check for conflicts:

```bash
jj resolve --list   # empty output ⇒ no conflicts
jj st               # also flags "There are unresolved conflicts"
```

### Conflict policy — auto-resolve trivial, HALT on semantic

jj records conflicts first-class (in the commit), so you classify *after* the
rebase. For each conflicted file, inspect the conflicted regions (`jj resolve
--list`, then read the markers in the file).

**Auto-resolve — ONLY these mechanically-unambiguous cases:**
- both sides only **added import/`require`/`ns`-block lines** → union them.
- **lockfiles / generated files** (e.g. `package-lock.json`, generated EDN/CSS,
  i18n `.pot`) → regenerate via the file's own regen command, or take the union.

(Hunks jj merged cleanly on its own never appear in `jj resolve --list` — there is
nothing to classify, so a shorter-than-expected list is normal, not a problem.)

**HALT — everything else.** If a conflicted region overlaps *any* line of real
logic, or you cannot resolve it by a purely mechanical rule, **stop**: report the
conflicted files and which regions are semantic, and tell the user to resolve
them and re-run `/drive-home`. A wrong auto-resolution silently corrupts behavior
in ways CI won't catch — when unsure, halt. Never guess at logic.

After resolving trivial conflicts (`jj resolve` or editing the files), confirm
`jj resolve --list` is empty before continuing.

## 4. Run CI and auto-fix the mechanical class

### Commit first — the `:ci` gate refuses a dirty worktree

CI Docker-COPYs the worktree, so `:ci` aborts if `jj st` is dirty. After the
rebase and any conflict resolution, fold working-copy changes into the branch so
the tree is clean. From the worktree:

```bash
jj st                       # if it shows changes…
jj commit -m "chore(drive-home): rebase onto origin/main"   # …commit them
```

(A pure no-op rebase leaves `jj st` already clean — skip the commit then.)

These `chore(drive-home): …` commits are **throwaway scaffolding** — §5's squash
folds them into the single final commit, so their messages don't matter.

### Run CI (same command /local-ci uses — NOT the /local-ci skill)

Warn the user this is a slow, full `--no-cache` Docker rebuild. Run it **from the
session home** (`cd ..` out of the worktree — `bb nido:run` resolves the session
from there):

```bash
cd ..   # session home
bb nido:run :project <project> <session> ci
```

- **Exit 0** → green. Go to §5.
- **Failure** → triage, separating flake/infra (Docker errors, e2e flakiness,
  partition imbalance — the summary reports flaky counts) from real regressions.
  Get the real error from the logs; don't guess from the job name.

### Tiered fix policy

Classify every real failure with **`/local-ci`'s Routing table**
(`local-ci/SKILL.md` § "Routing") — that table is the single source of truth, so
this skill never restates it:

- **AUTO-FIX (no approval)** — failures whose owner in that table is
  "fix directly (mechanical)". Fix and commit **in the worktree** (`cd worktree`;
  `jj commit -m "chore(drive-home): fix <job>"`), then `cd ..` back to the session
  home and **re-run CI once** to confirm green.
- **HALT (report, do not fix)** — every other row, i.e. anything the table routes
  to a domain agent or skill rather than to "fix directly (mechanical)". Produce a
  lane-grouped report (job, salient error lines, owning agent per the Routing
  table) and **stop**. Do **not** dispatch fix agents, do **not** proceed to §5.

**Loop guard:** at most **two** mechanical fix→re-run cycles. If CI is still red
after that, or a non-mechanical failure appears, halt and report — never loop CI
to green.

## 5. Finish — squash, rewrite description, push, ready, enqueue

Reached **only** on green CI with no unresolved conflicts (drive-home never
enqueues broken or conflicted code). The CI step (§4) ran from the session home,
so return to the worktree first:

```bash
cd worktree
```

### 5a. Squash the branch into one coherent commit

Collapse the whole branch — the original work commits **plus** the throwaway
`chore(drive-home): …` rebase/fix commits — into a **single** commit. Squashing
happens last and rewrites only history, not the tree, so the CI that just passed
still describes the committed state.

**Always one commit. No splitting, no regrouping, no halt** — commit-shaping is
mechanical here and introduces no new judgement gate. (Producing *separate*
commits per concern is explicitly **not** what this does; the layering lives in
the commit body instead — see below.)

The branch is the linear stack `main@origin..@`. Fold it into one commit and set
its description:

```bash
jj log -r 'main@origin..@' --no-graph    # inspect the stack you're about to squash
# fold the whole stack into a single commit (e.g. squash each child into its
# parent until one remains, or squash the range into the base), then set its
# description with `jj describe`:
jj describe -r <the-one-commit> -m "$(cat <<'MSG'
<type>(<scope>): <coherent subject>

<one-line summary of the change>

- <layer 1 — e.g. refactor X to make Y possible>
- <layer 2 — add the Y feature>
- <layer 3 — wire Y into Z; add tests>

Refs BR-####
MSG
)"
```

- **Subject:** a coherent conventional-commit line. Synthesize the type/scope
  from the diff + ticket + the existing commit messages — do not just reuse a
  throwaway `chore(drive-home)` message.
- **Body — narrate the layers:** the logical pieces the author built up (refactor
  → feature → wiring → tests) survive as bullets/short paragraphs in the body,
  **not** as separate commits. This is the whole point of squash-to-one here.
- **Already one coherent commit?** (single commit in `main@origin..@` with a real
  message) → the squash is a no-op; just refresh the description if needed.

### 5b. Regenerate the PR title + description (full overwrite)

Replace the PR body **wholesale** — the quick body `prepare-draft-pr` wrote, plus
anything hand-typed into the draft — with one synthesized from the **final
squashed commit (subject + layered body) + the diff + the Notion ticket**. Set
the title from the squashed commit's subject so title, commit, and body tell one
story.

```bash
ls .github/PULL_REQUEST_TEMPLATE* .github/pull_request_template* 2>/dev/null
# if a template exists, fill ITS structure; otherwise use a default
# summary / what-changed / refs shape.
gh pr edit --title "<squashed commit subject>" --body "<regenerated body>"
```

This is a deterministic overwrite derived from the final state, so re-running
re-derives the same body — idempotent, no append-drift.

### 5c. Push, ready, enqueue

```bash
jj git push                         # force-moves the bookmark to the squashed commit
gh pr ready                         # draft → ready for review
gh pr merge --auto                  # enable auto-merge → native merge queue
```

The squash rewrote history, so `jj git push` moves the bookmark to a new commit
(a force-update of the PR branch) — expected, and fine for a session's own PR
branch.

`gh pr merge --auto` takes **no** strategy flag on a merge-queue branch — the
queue defines the strategy. It enables auto-merge if checks are pending, or adds
the PR to the queue if they've passed.

Report: the PR URL, what was rebased/auto-fixed, that the branch was squashed to
one commit and the description regenerated, and that it's on the merge queue. The
coordinator's `github-merge` poller closes the workstream and nudges Notion when
it lands — nothing further to do here.

## Idempotency (safe to re-run)

Every step checks its own precondition, so re-running after a halt-and-fix
continues rather than redoing:

- PR already exists → reuse (don't create a second).
- Branch already on `main@origin` → `jj rebase` is a no-op; continue.
- CI already green → skip fixing.
- Branch already a single coherent commit (`main@origin..@` has one commit with a
  real message) → the squash is a no-op.
- PR description regen is a deterministic overwrite from the final state → safe to
  repeat (no append-drift).
- PR already `isDraft:false` → skip `gh pr ready`.
- Auto-merge already enabled (`gh pr view --json autoMergeRequest`) → skip
  `gh pr merge --auto`.

This makes drive-home loop-ready: a future `/loop` wrapper can re-invoke it until
the PR merges. (No watcher is built here.)

## When it halts

On a semantic conflict (§3) or a judgement CI failure (§4), drive-home leaves the
worktree exactly as it is, reports **what** blocks and **how to resume** (resolve
conflict X / fix test Y, then re-run `/drive-home`), and stops. It makes no
`ready`/`merge` calls on a halted journey.

## Common mistakes

- **Invoking `/local-ci`** instead of running `bb nido:run … ci` directly — its
  approval gate halts the autonomous flow. (See "Relationship to /local-ci".)
- **Parsing the session as the last path segment** — slash-namespaced sessions
  span multiple segments; take everything after `/sessions/<project>/`.
- **Auto-resolving a semantic conflict** — guessing at logic. Halt instead.
- **Auto-fixing beyond the mechanical class** — test/i18n/migration/allium/
  version-gate failures need judgement. Halt and report.
- **Running `gh`/`git` from the session home** — it's not git-colocated;
  `cd worktree` first.
- **Flipping `ready` before CI is green**, or **looping CI to green** past the
  two-cycle guard.
- **Forgetting to commit before CI** — the `:ci` gate refuses a dirty `jj st`.
- **Squashing before CI, or pushing the unsquashed branch** — the squash is the
  last step (§5a), after green CI; the throwaway `chore` commits must never reach
  the PR.
- **Splitting into multiple commits, or halting to ask about commit structure** —
  drive-home **always** squashes to one. The layering goes in the commit *body*,
  not in separate commits, and it is never a halt.
- **Leaving the squashed commit's subject as a `chore(drive-home)` message** —
  synthesize a real conventional-commit subject from the change.
- **Proofreading/appending to the old PR body** — §5b is a full overwrite
  synthesized from the final state, not an edit of the existing text.
