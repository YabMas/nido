---
name: drive-home
description: Take the current nido session's stack home by composing /align (rebase + trivial-conflict resolution), /local-ci auto (CI + autonomous mechanical fix), and /squash (fold each layer to one commit + regenerate every PR title/description), then mark every layer ready and merge the stack atomically. Halts for human judgement on semantic conflicts or non-mechanical CI failures. Usage: /drive-home
---

# drive-home

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/drive-home/`
> and is injected into every spawned session's composed `.claude/skills/` (see
> `nido.session.launcher/compose-claude-dir!`). Sibling of `/align`, `/local-ci`,
> `/squash`, and `/prepare-draft-pr`.

## What this is

One command that drives the **current session's** stack from "work written" to
"on the merge queue" by composing three sibling skills:

1. `/align` — rebase on a fresh `origin/main`, auto-resolve only trivial
   conflicts (halt on anything semantic),
2. `/local-ci auto` — run CI and auto-fix only the mechanical class (halt on
   anything needing judgement),
3. `/squash` — fold each layer into one coherent commit and regenerate every
   layer's PR title/description from it,
4. mark every layer's PR ready and merge the stack atomically (a single-PR
   session marks the one PR ready and enables auto-merge, same as today).

It is **autonomous within a safe boundary** and **stops for a human** the moment
real judgement is required. It assumes the nido invariant — **one session, one
stack** — so it takes no arguments. A stack may be one layer (exactly today's
single-PR flow) or several; every step below forks on layer count, never on a
flag.

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

## 2. Acquire the stack

All `gh`/`jj` work happens **inside the worktree** — `cd worktree` from the
session home first. Derive the two values the `gh` calls need:

```bash
cd worktree
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
```

Then read the stack rather than a single PR:

```bash
(cd "$SRC" && gh stack view --json) 2>/dev/null \
  || gh pr view -R "$SLUG" --json number,url,state,isDraft,headRefName,mergeStateStatus
```

- **Stack exists** → keep every layer's number and URL, bottom to top.
- **Single PR exists** → today's path, unchanged.
- **Neither** → run the **`/prepare-draft-pr` skill** now; it publishes the stack
  (or the single PR) and wires the correlation links the merge poller needs. Then
  re-read as above.

> Discovery uses `gh pr view` (not the session `:pr` link): it asks GitHub for the
> current branch's PR directly, so it's correct on idempotent re-runs and doesn't
> depend on nido's link bookkeeping — which can mis-resolve slash-namespaced
> sessions. The `:pr` / `:github` links are stamped by `/prepare-draft-pr` for the
> merge poller; they aren't needed for discovery here.

Never hand-roll `gh pr create` or `gh stack link` here — delegate to
`/prepare-draft-pr` so the poller bookkeeping is correct.

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
invoke **`/squash`**. It folds each layer of the stack into one coherent
commit, pushes the whole stack, and regenerates every layer's PR
title/description. It is mechanical and never halts.

## 6. Finish — push, ready, enqueue

```bash
jj git push                  # force-moves every layer bookmark to its folded commit
```

Mark every layer ready. For a stack:

```bash
(cd "$SRC" && gh stack link <n1> <n2> <n3> --open)
```

`--open` flips new and existing PRs from draft to ready for review. For a
single-PR session, `gh pr ready -R "$SLUG"` as today.

Then merge:

```bash
(cd "$SRC" && gh stack merge <n_top> --yes)
```

`gh stack merge` is all-or-nothing: if any layer cannot merge, none do. When the
base branch uses a merge queue, the stack is added to the queue and lands when
the queue processes it — so this **replaces** `gh pr merge --auto` entirely; do
not call both.

For a single-PR session, `gh pr merge -R "$SLUG" --auto` as today.

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

For a stack, list every layer's PR as an artifact so the report timeline shows
the whole shipment:

```bash
cat > /tmp/impl-completed.edn <<'EDN'
{:format    :implementation-completed
 :summary   "<one-line: what shipped; N layers; CI green; stack on the merge queue>"
 :artifacts [{:kind :pr :ref "<owner>/<repo>#<n1>" :url "<pr-url-1>"}
             {:kind :pr :ref "<owner>/<repo>#<n2>" :url "<pr-url-2>"}
             {:kind :pr :ref "<owner>/<repo>#<n3>" :url "<pr-url-3>"}]}
EDN
bb nido:ticket:append :project <project> :br <BR-####> :kind implementation-completed \
  :file /tmp/impl-completed.edn
```

(Validated against `ImplementationCompleted`; `:artifacts []` :kind is one of
`:commit :pr :branch`.)

Report: the PR URL(s), what was rebased/auto-fixed, that the stack was folded
(one commit per layer) with every PR description regenerated, and that it's on
the merge queue. The coordinator's `github-merge` poller closes the workstream
and nudges Notion when it lands — nothing further to do here.

## Idempotency (safe to re-run)

Every step checks its own precondition, so re-running after a halt-and-fix
continues rather than redoing:

- PR or stack already exists → reuse (don't create a second).
- Branch already on `main@origin` → `jj rebase` is a no-op; continue.
- CI already green → skip fixing.
- Each layer already a single coherent commit (single-PR session:
  `main@origin..@` has one commit with a real message) → the squash fold is a
  no-op.
- PR description regen is a deterministic overwrite from the final state → safe to
  repeat (no append-drift).
- PR already `isDraft:false` → skip `gh pr ready`.
- Auto-merge already enabled (`gh pr view --json autoMergeRequest`) → skip
  `gh pr merge --auto`.
- Stack already linked → `gh stack link` updates rather than duplicating.
- All layers already `isDraft:false` → skip the `--open` re-link.
- Stack already merged or queued → `gh stack merge` reports it; no second merge.

This makes drive-home loop-ready: a future `/loop` wrapper can re-invoke it until
the PR (or stack) merges. (No watcher is built here.)

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
- **Calling `gh pr merge --auto` on a stack** — use `gh stack merge <n> --yes`;
  calling both double-enqueues.
- **Marking only the top PR ready** — every layer must be ready or the stack
  merge refuses.
- **Merging layer-by-layer with `gh pr merge`** — that abandons atomicity;
  `gh stack merge` lands the whole stack or none of it.
- **Running `gh stack` in the worktree** — it needs a git repository. Run it from
  `$SRC`.
- **Omitting `-R "$SLUG"` from `gh pr` commands** — bare `gh` cannot resolve a
  repo from a non-colocated worktree.
