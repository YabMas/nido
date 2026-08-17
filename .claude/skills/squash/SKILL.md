---
name: squash
description: Fold the current session's stack so each layer is exactly one coherent commit, push the whole stack, and regenerate every PR's title and description. Mechanical — never halts, no ledger events. Run from a session worktree. Usage: /squash
---

# /squash

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/squash/`
> and is injected into every spawned session's composed `.claude/skills/`.
> Sibling of `/align`, `/local-ci`, and `/drive-home` — the squash + PR-text
> phase `/drive-home` composes.

## What this is

Fold each layer of the session's stack into **one** coherent commit, **push the
whole stack**, and regenerate every layer's PR title and description to match.
It is **mechanical** — it **never halts** and never touches the coordinator
ledger. Publishing to the merge queue (`gh pr ready` / `gh pr merge`) stays with
the caller (`/drive-home`'s finish); the push itself is `/squash`'s job.

## Where to run it

Squash happens in the **worktree**; PR text uses `gh`. If you're in a session
home, `cd worktree` first. Sanity-check:

```bash
jj root   # errors if not a jj repo → stop; run from the worktree
```

## 1. Fold each layer into one coherent commit

Collapse each layer's work commits — the original commits **plus** the throwaway
`chore(ci): …` rebase/fix commits that landed on that layer — into a **single**
commit per layer.

**Always one commit per layer. Never fold across layers, never split a layer,
never halt.** Layer structure is settled by the time `/squash` runs: it was
decided at planning time and may have been reshaped during the work. Squash only
tidies *within* boundaries that already exist.

Read the stack:

```bash
jj log -r 'main@origin..@' --no-graph
jj bookmark list | grep -- '--'
```

For each layer bookmark, fold that layer's range into one commit and set its
description to the layer commit format (`/stack` §5) — subject, body, `Layer:`
trailer, and the four review-brief fields:

```bash
jj describe -r <the-one-commit> -m "$(cat <<'MSG'
<type>(<scope>): <one sentence, no "and">

<what this layer does>

Layer: <mechanical | structural | behavioral>

Claims: <what this layer asserts about itself>
Verify: <concrete checks>
Lane: <specialism>
Out of scope: <what this layer's reviewer should not flag, and where it lives>

Refs BR-####
MSG
)"
```

- **Preserve the existing trailer and brief** where the layer already has them —
  they were authored when the layer was written, which is when the author knew
  most. Refresh only what the fold changed.
- **Already one coherent commit?** The fold is a no-op for that layer; refresh
  the description if needed.
- **Single-layer session?** This is exactly the old behaviour — one commit, one
  PR — and the `Layer:` trailer is optional.

## 2. Push the whole stack

```bash
jj git push
```

Bare `jj git push` pushes tracked bookmarks — every layer bookmark, and never the
session bookmark. The fold rewrote history, so this force-updates each layer's
branch on the remote. Expected, and fine for a session's own branches.

If a layer bookmark has never been pushed:

```bash
jj git push --allow-new -b 'glob:<session>--*'
```

Then re-link, in case the shape changed since publish. `gh stack` needs the
colocated source repo — it cannot run in this worktree:

```bash
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
(cd "$SRC" && gh stack link <n1> <n2> <n3>)
```

Bottom to top, by **PR number**. `gh stack link` is incremental and never removes
PRs. Push regardless of whether PRs exist.

## 3. Regenerate every PR's title + description

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
(cd "$SRC" && gh stack view --json)
```

- **No stack / no PRs** → §1 and §2 were the whole job; stop here.
- **Stack exists** → regenerate each layer's PR from its folded commit.

For each layer, replace the PR body **wholesale** — the quick body
`prepare-draft-pr` wrote plus anything hand-typed — with one synthesized from the
folded commit (subject, body, `Layer:` trailer, review brief), that layer's diff,
and the ticket:

```bash
gh pr edit <number> -R "$SLUG" --title "[<n>/<N>] <subject>" --body "<regenerated body>"
```

`<n>` is the layer's position bottom-to-top and `<N>` the stack depth — both read
fresh from `jj log`, so an inserted or removed layer renumbers every title
correctly.

Every body must carry **Claims / Verify / Lane / Out of scope**; that is what
makes per-layer review bounded.

This is a deterministic overwrite derived from final state, so re-running
re-derives the same bodies — idempotent, no append-drift.

## What this skill does NOT do

- **No `gh pr ready` / `gh pr merge`.** Flipping the PR ready and enqueueing it
  belong to the caller (`/drive-home`'s finish). The `jj git push` itself is
  `/squash`'s job (§2).
- **No halt.** Commit-shaping is mechanical — always one commit per layer, never
  a fold across layers, never a split, never a question. If a layer looks
  mis-cut, **report it and fold it anyway**; reshaping is `/stack`'s job during
  the work, not `/squash`'s at the end.
- **No ledger events.**

## Common mistakes

- **Leaving the squashed commit's subject as a `chore(ci)` message** — synthesize
  a real conventional-commit subject from the change.
- **Folding the whole stack into one commit** — that destroys the layering. One
  commit *per layer*.
- **Halting because a layer looks mis-cut** — report it, fold anyway.
- **Dropping the `Layer:` trailer or review brief when re-describing** — the PR
  bodies are generated from them.
- **Hand-computing `[n/N]`** — read position and depth fresh from `jj log`, so
  inserted layers renumber correctly.
- **Running `gh stack` in the worktree** — it needs a git repository. Run it from
  `$SRC`.
- **Omitting `-R "$SLUG"` from `gh pr edit`** — bare `gh` cannot resolve a repo
  from a non-colocated worktree.
- **Proofreading/appending to the old PR body** — §3 is a full overwrite
  synthesized from the final state, not an edit of the existing text.
- **Stopping without pushing** — the folded commits must land on the remote;
  §2's `jj git push` is part of the job. (`ready`/`merge` are still the
  caller's.)
- **Running from the session home** — `cd worktree` first.
