---
name: drive-home
description: Take the current nido session's branch home by composing /align (rebase + trivial-conflict resolution), /local-ci auto (CI + autonomous mechanical fix), and /squash (fold to one commit + regenerate the PR title/description), then mark the PR ready and put it on the merge queue. Halts for human judgement on semantic conflicts or non-mechanical CI failures. Usage: /drive-home
---

# drive-home

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/drive-home/`
> and is injected into every spawned session's composed `.claude/skills/` (see
> `nido.session.launcher/compose-claude-dir!`). Sibling of `/align`, `/local-ci`,
> `/squash`, and `/prepare-draft-pr`.

## What this is

One command that drives the **current session's** branch from "work written" to
"on the merge queue" by composing three sibling skills:

1. `/align` — rebase on a fresh `origin/main`, auto-resolve only trivial
   conflicts (halt on anything semantic),
2. `/local-ci auto` — run CI and auto-fix only the mechanical class (halt on
   anything needing judgement),
3. `/squash` — squash the whole branch into one coherent commit and regenerate
   the PR title/description from it,
4. mark the draft PR ready and enable auto-merge (native merge queue).

It is **autonomous within a safe boundary** and **stops for a human** the moment
real judgement is required. It assumes the nido 1:1 invariant — one session, one
branch, one PR — so it takes **no arguments**. (Splitting a session into several
PRs is a separate, out-of-scope concern; spin a sibling session per branch.)

## Invoked headless by the merge lane

Besides interactive use, `nido ship` runs this skill **headless** under the
coordinator daemon: `claude -p "/drive-home"` with the **session-home as cwd**
(same as an interactive run), serialized one branch at a time. Nothing about the
flow changes — the same halt boundary applies. A halt still records a typed
`:blocker` event (§"When it halts"); the daemon reads that fingerprint, parks the
session, and surfaces it in the gate inbox as **blocked**. A clean finish records
`:implementation-completed` (§7) and the daemon marks the workstream
*awaiting-merge*. So: keep emitting both fingerprints exactly as today — they are
how the lane classifies the run.

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

## 3. Rebase — `/align`

`cd worktree`, then invoke the **`/align`** skill. It rebases onto a fresh
`main@origin` and auto-resolves only trivial conflicts, halting on anything
semantic.

Re-check the observable outcome:

```bash
jj resolve --list   # empty ⇒ clean; non-empty ⇒ /align halted on a semantic conflict
```

Non-empty ⇒ `/align` halted — record a `:blocker` (see "When it halts") and stop.
Empty ⇒ continue.

## 4. CI — `/local-ci auto`

`cd ..` (session home), then invoke **`/local-ci auto`**. It commits a clean
tree, runs CI, auto-fixes the mechanical class, and halts on anything needing
judgement.

Read its reported outcome — **CI green** ⇒ continue; **halted** (a non-mechanical
failure) ⇒ record a `:blocker` and stop. CI is a slow Docker build, so trust
`/local-ci auto`'s green/halted report rather than re-running to check.

## 5. Squash + PR text — `/squash`

Reached **only** on green CI with no unresolved conflicts. `cd worktree`, then
invoke **`/squash`**. It squashes the branch into one coherent commit and
regenerates the PR title/description. It is mechanical and never halts.

## 6. Finish — push, ready, enqueue

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

## 7. Record completion on the ticket ledger

Append a typed `:implementation-completed` event so the workstream's report
timeline closes the loop (CI green, squashed, on the merge queue). The `BR-####`
is the `:event-payload :id` in `./run-link/run.edn` (run from the session home;
`cd ..` out of the worktree if needed):

```bash
cat > /tmp/impl-completed.edn <<'EDN'
{:format    :implementation-completed
 :summary   "<one-line: what shipped; CI green; on the merge queue>"
 :artifacts [{:kind :pr :ref "<owner>/<repo>#<number>" :url "<pr-url>"}]}
EDN
bb nido:ticket:append :project <project> :br <BR-####> :kind implementation-completed \
  :file /tmp/impl-completed.edn
```

(Validated against `ImplementationCompleted`; `:artifacts []` :kind is one of
`:commit :pr :branch`.)

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

On a semantic conflict surfaced by `/align` (§3) or a non-mechanical CI failure
surfaced by `/local-ci auto` (§4), drive-home leaves the worktree exactly as it
is, reports **what** blocks and **how to resume** (resolve conflict X / fix test
Y, then re-run `/drive-home`), and stops. It makes no `ready`/`merge` calls on a
halted journey.

When it halts on a semantic conflict surfaced by `/align` (§3) or a non-mechanical
CI failure surfaced by `/local-ci auto` (§4), also record a typed `:blocker` event
so the parked workstream shows what blocks it:

```bash
cat > /tmp/blocker.edn <<'EDN'
{:format  :blocker
 :summary "<what blocks: the semantic conflict / the non-mechanical CI failure>"
 :needs   "<what the human must do: resolve conflict X / fix test Y, then re-run /drive-home>"}
EDN
bb nido:ticket:append :project <project> :br <BR-####> :kind blocker :file /tmp/blocker.edn
```

This records the halt; it does not change the halt behaviour — drive-home still
stops and makes no `ready`/`merge` calls.

## Common mistakes

- **Calling bare `/local-ci` instead of `/local-ci auto`** — the bare approval
  gate halts the autonomous flow; `auto` is the composed path (§4).
- **Parsing the session as the last path segment** — slash-namespaced sessions
  span multiple segments; take everything after `/sessions/<project>/`.
- **Running `gh`/`git` from the session home** — it's not git-colocated;
  `cd worktree` first (§2 and §6 run from the worktree; §4 runs from the session
  home).
- **Flipping `ready` before CI is green** — §6 is reached only after §4 reports
  green.
- **Proceeding to `/squash` before `/local-ci auto` reports green** — `/squash`
  (squash + PR text) is the last phase, after green CI. Commit-shaping itself
  lives in `/squash`; drive-home never splits or reshapes commits here.
