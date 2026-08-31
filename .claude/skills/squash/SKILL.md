---
name: squash
description: Fold the current session's stack so each layer is exactly one coherent commit, push the whole stack, and regenerate every PR's title and description. Mechanical — never halts, no ledger events. Run from a session worktree. Usage: /squash
---

# /squash

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/squash/`
> and is injected into every spawned session's composed `.claude/skills/`.
> Sibling of `/align`, `/local-ci`, `/land`, and `/drive-home` — the squash + PR-text
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

Read the stack. **Scope the bookmark list to this session** — `jj bookmark list`
covers the whole shared jj repo, which holds every other workspace's bookmarks
too. Derive `<session>` from cwd (`/stack` §4, "Deriving `<session>`") and grep on
the prefix, anchored:

```bash
jj log -r 'trunk()..@' --no-graph
jj bookmark list -T 'if(remote, "", name ++ "\n")' | grep "^<session>--"
```

An unanchored `grep -- '--'` matches other sessions' layer bookmarks — this repo
has a dozen live workspaces — and would make `/squash` fold and publish another
session's layers. It also matches a `--` inside a commit's first line, which the
default template prints; `-T 'name ++ "\n"'` prints names only.

Bookmarks are listed bottom to top by `jj log` order, not by the grep's output
order; take the ordering from `jj log -r 'trunk()..@'`.

### The fold, per layer

A layer's range is `<lower>..<this-bookmark>`, where `<lower>` is the bookmark of
the layer beneath it — and `trunk()` for the bottom layer. Squash everything
in that range into its lowest commit:

```bash
LOW='trunk()'                   # or <session>--<slug-of-layer-beneath>
TIP=<session>--<slug>
BASE=$(jj log -r "roots($LOW..$TIP)" --no-graph -T 'change_id.short()')
jj squash -u --from "$LOW..$TIP ~ $BASE" --into "$BASE"
```

**`-u` is required, not optional polish.** Folding two or more non-empty
descriptions into a non-empty destination makes plain `jj squash` open an
**editor** to merge the messages. `/squash` runs headless under `claude -p` in
the coordinator daemon, where nothing can answer that prompt — the command hangs
forever, with no output and no typed ledger event for the lane to classify, which
is worse than a halt. `-u` (`--use-destination-message`) skips the prompt by
keeping the destination's message, which is fine here because the very next step
re-describes the surviving commit anyway. **Do not remove `-u` as a "cleanup"** —
without it this step reintroduces the headless hang.

The layer bookmark follows onto the folded commit, and the layers above rebase
automatically — so work bottom to top and each layer's `$LOW` is already folded
when you reach it. If the layer is already one commit, `jj squash` reports
`Nothing changed.` and exits 0, so the fold is a safe no-op and the whole step is
re-runnable.

Then set the surviving commit's description to the layer commit format
(`/stack` §5) — subject, body, `Layer:` trailer, and the four review-brief
fields. (The fold leaves it carrying the *lowest* commit's message, which is
usually not the message the layer wants.)

```bash
jj describe -r <the-one-commit> -m "$(cat <<'MSG'
<type>(<scope>): <one sentence, no "and">

<what this layer does>

Layer: <mechanical | structural | behavioral>

Claims: <what this layer asserts about itself — the :claim from the design
  record's :layers, verbatim or close to it>
Verify: <concrete checks>
Lane: <specialism>
Out of scope: <what this layer's reviewer should not flag, and where it lives:
  a layer above, a spun-out ref, an explicit decline, or a citation of the
  design — "the record puts this behind the X boundary">

Refs BR-####
MSG
)"
```

- **Preserve the existing trailer and brief** where the layer already has them —
  they were authored when the layer was written, which is when the author knew
  most. Refresh only what the fold changed.
- **A fold that changes what a layer claims is a signal, not a formality.** If
  the surviving commit asserts something the design record's `:layers` does not,
  the squash moved a decision — either the record needs amending or the fold was
  wrong. Say which; do not paper over it in the brief.
- **Already one coherent commit?** The fold is a no-op for that layer; refresh
  the description if needed.
- **Single-layer session?** This is exactly the old behaviour — one commit, one
  PR — and the `Layer:` trailer is optional.

## 2. Push the whole stack

```bash
jj git push -b 'glob:<session>--*'
```

**Always scope the push with `-b 'glob:<session>--*'`.** Bare `jj git push` pushes
every *tracked* bookmark, and the session bookmark `<session>` is tracked in any
session that ran the single-PR `/prepare-draft-pr` — several such sessions exist
in this repo. There, a bare push also force-updates `refs/heads/<session>`,
republishing the whole stack as one branch beside the layers and contradicting
`/prepare-draft-pr`'s "only `<session>--*` goes up". The glob is what makes the
invariant hold regardless of what is tracked; it matches `/prepare-draft-pr` §2.

**The glob excludes the session bookmark, not a previous arc's layers.** A
session that has already landed layers still carries their bookmarks: a
squash-merge leaves them pointing at commits that are not ancestors of trunk, so
nothing prunes them and `glob:<session>--*` sweeps them back up alongside the
live ones. Observed on a brian session holding eight layer bookmarks, five of
them from arcs that had already shipped. Pushing those force-updates branches
whose PRs are closed. Read the live layers from §1's `jj log -r 'trunk()..@'`
and push those bookmarks by name, or delete the landed ones first — the glob is
a floor, not the whole rule.

**No `--allow-new`.** jj 0.42 removed the flag — a command carrying it exits
`error: unexpected argument '--allow-new' found` and pushes nothing at all. `-b`
already implies it: a bookmark that isn't tracking anything yet gets tracked
automatically, so a layer bookmark's first push is covered. The fold rewrote
history, so this force-updates each layer's branch on the remote — expected, and
fine for a session's own branches.

Then re-link, in case the shape changed since publish. `gh stack` needs the
colocated source repo — it cannot run in this worktree. Re-derive `$SRC` here:
**shell variables do not survive between commands** (`/stack` §4).

**Export the bookmarks first, or the re-link is rejected.** `gh stack link`
pushes the branches itself, with git, from `$SRC`. A session worktree is a
NON-COLOCATED jj workspace whose `.jj/repo` points into that colocated repo, so
the `jj git push` above updates the REMOTE while `$SRC`'s own
`refs/heads/<session>--*` stay where they were — at the pre-rebase commits.
`gh stack link` pushes those, and the remote refuses every branch at once:

    ! [rejected]  <session>--<slug> (non-fast-forward)
    ✗ failed to push branches: ... atomic push failed ... status: 2

The rejection is protective — it does not overwrite what jj just pushed — but it
aborts the link before any PR is touched. `jj git export` writes the bookmarks
into the shared git repo, after which the same call is a fast-forward no-op and
proceeds:

```bash
jj git export   # sync $SRC's refs with the bookmarks jj just pushed
```

It prints `Hint: Git doesn't allow a branch/tag name that looks like a parent
directory of another` whenever ANY bookmark in the shared repo collides that way
— usually another session's. That hint is not about this stack and does not stop
these bookmarks exporting; confirm with `git -C "$SRC" rev-parse <branch>`
against `origin/<branch>` rather than reading the hint as a failure.

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
TRUNK=$(gh repo view "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
(cd "$SRC" && gh stack link --base "$TRUNK" <session>--<l1> <session>--<l2> <session>--<l3> 2>&1); echo "EXIT=$?"
```

**Always pass `--base "$TRUNK"`.** Every `gh stack link` call — this re-link
included — force-resets the bottom PR's base to the repository default branch
unless told otherwise (`/stack` §4; observed retargeting a PR at `main`
unasked). It's a no-op for nido today but removes the failure mode
structurally, the same reasoning as `--` over `/` in bookmark names.

Bottom to top, listing **every** layer, **by branch name**. Branch names are the
re-link form: `gh stack link` reuses the open PR for a branch that has one and
creates one for a branch that doesn't. Push and re-link regardless of whether PRs
exist.

**Redirect `2>&1` and read the exit code.** `gh stack link` prints its progress
and success lines to **stderr** — stdout is empty, so anything capturing stdout
sees nothing — and it exits **5** on a partial failure.

**A non-zero exit means the stack shape is wrong on GitHub, not that `/squash`
failed.** GitHub locks the base of a stacked PR, so this call can only append at
the top: if a layer was *inserted or reordered below the top* during the work and
`/stack` §6's unstack-then-link repair was never run, the link fails here — and
it fails *after* creating the inserted layer's PR, leaving it orphaned and
leaving the layer above mis-based, so that PR's diff swallows the layer below it.

`/squash` **does not halt for this and does not unstack** — dissolving a live
stack is a shape decision, and shape belongs to `/stack`. Finish §1–§3 as normal,
then **report it prominently**: which layer, that the stack on GitHub is
mis-shaped, and that the fix is `/stack` §6 case B (unstack, then one link). Same
rule as a mis-cut layer: report it, carry on.

`gh stack link` never removes PRs, in any of its outcomes.

## 3. Regenerate every PR's title + description

First discover the PRs. This is the shared discovery primitive from `/stack` §4 —
**not** `gh stack view`, which takes no arguments, always resolves the current
branch, and therefore always fails in a jj-colocated repo. §2 just linked the
stack, so read it from the stacks endpoint, which returns the PRs **already
ordered** bottom to top. It runs in the worktree; it needs no git repo and no
current branch:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh api repos/"$SLUG"/stacks \
  --jq '.[] | select(.open) | select(any(.pull_requests[]; .head.ref | startswith("<session>--")))
            | "stack #\(.number) base=\(.base.ref) prs=\([.pull_requests[] | {number, ref: .head.ref}])"'
```

**Keep the `startswith("<session>--")` filter.** The endpoint returns every stack
in the repo, and this repo runs a dozen sessions at once — unfiltered, `/squash`
would regenerate another session's PR bodies. Same rule as §1's anchored
`grep "^<session>--"`.

**Keep `select(.open)` too.** A merged stack stays listed with `open:false`
forever; without the filter a re-run against an already-shipped stack would
still "find" it and attempt to regenerate PR text on PRs that already merged.

**That list's `{number, ref}` pairs are what the `gh pr edit` calls below
consume** — nothing else produces them, so this step is not optional. Match
`<number>` to each layer **by `ref` (the branch name), never by position in the
list.** `/stack` §6 already notes the stacks endpoint's objects carry
`head.ref`; that's why it's pulled out here instead of just `number`. §2
explicitly does not halt on a non-zero exit, so this step can run against a
stack an orphaned PR has shifted — every PR from that point down sits one
position off from where a clean stack would put it. A positional match (`n`-th
list entry ↔ `n`-th layer in `jj log`) would silently pair each of those PRs
with the wrong layer's body. Build a branch-name → number map from this list
first, then look up each layer's `<session>--<slug>` bookmark in it.

If the endpoint returns `[]` — no stack object, e.g. a single-layer session, or
PRs created but never linked — fall back to `gh pr list` and order by matching
`headRefName` against the `jj log` order from §1:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh pr list -R "$SLUG" --state open --limit 50 \
  --json number,url,headRefName,baseRefName,isDraft \
  --jq '.[] | select(.headRefName == "<session>" or (.headRefName | startswith("<session>--")))'
```

**Keep the `.headRefName == "<session>"` half of the filter.** A single-layer
session's PR head is exactly `<session>`, with no `--<slug>` suffix — the
`startswith("<session>--")` half alone never matches it, so today's default
single-PR flow would fall through as "nothing published" and silently skip
PR-text regeneration here. `/drive-home` already gets this right (its §2
discovery uses the same `== "<session>" or startswith("<session>--")` shape);
this mirrors it.

- **Both empty** → nothing is published yet; §1 and §2 were the whole job; stop
  here. (Empty means "not published", not "discovery failed" — an error from `gh`
  is a different thing and should be reported.)
- **PRs found** → regenerate each layer's PR from its folded commit.

For each layer, replace the PR body **wholesale** — the quick body
`prepare-draft-pr` wrote plus anything hand-typed — with one synthesized from the
folded commit (subject, body, `Layer:` trailer, review brief), that layer's diff,
and the ticket:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh pr edit <number> -R "$SLUG" --title "[<n>/<N>] <subject>" --body "<regenerated body>"
```

`<number>` is looked up from the discovery above **by matching this layer's
branch name against `ref`/`headRefName`** — never taken by position. Both `-R`
**and** an explicit number are required: with `-R` and no number, `gh pr edit`
exits `argument required when using the --repo flag`; with a number and no
`-R`, it cannot resolve the repo from this worktree. And re-derive `$SLUG` in
this block — it does not survive from the discovery block.

`<n>` is the layer's position bottom-to-top and `<N>` the stack depth — both read
fresh from `jj log`, so an inserted or removed layer renumbers every title
correctly.

Every body must carry **Claims / Verify / Lane / Out of scope** (`/stack` §5 for
what each field means — Claims traces to the design record, Out of scope may cite
it); that is what makes per-layer review bounded.

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
  `$SRC`. (`gh pr list`/`gh pr edit` need neither and run here.)
- **Reading the stack with `gh stack view`** — it has no positional arguments, so
  it always resolves the current branch and always fails here. Use §3's
  `gh api repos/"$SLUG"/stacks` discovery (`gh pr list` when there is no stack).
- **`jj git push --allow-new`** — the flag does not exist in jj 0.42; the command
  exits `unexpected argument` and pushes nothing. `-b` implies it (§2).
- **Capturing `gh stack link`'s stdout, or ignoring its exit code** — it prints
  to **stderr** and exits 5 on a partial failure that has already half-mutated
  the stack. Redirect `2>&1`, check the exit, report a non-zero one (§2).
- **Halting or unstacking because §2's link failed** — `/squash` never halts and
  never reshapes. Report it and point at `/stack` §6 case B (§2).
- **Calling `gh pr edit` with `-R "$SLUG"` but no PR number** — `-R` alone is not
  enough for any `gh pr` subcommand that resolves a PR; it then exits `argument
  required when using the --repo flag`. Pass `-R` **and** the number from
  discovery.
- **Assuming `$SLUG`/`$SRC` carry between commands** — each Bash call is a fresh
  shell. Re-derive them in every block, or inline the values.
- **Bare `jj git push`** — it also pushes the session bookmark when that bookmark
  is tracked. Always scope with `-b 'glob:<session>--*'` (§2).
- **Pushing `glob:<session>--*` in a session that has already landed layers** —
  the glob keeps the session bookmark out, not a shipped arc's layer bookmarks;
  those survive a squash-merge pointing at commits trunk never took (§2).
- **Calling `gh stack link` without `jj git export` first** — it pushes `$SRC`'s
  pre-rebase refs, every branch is rejected non-fast-forward, and the link
  aborts (§2).
- **`jj bookmark list | grep -- '--'`** — that is repo-global and matches other
  sessions' layers. Anchor it: `grep "^<session>--"` (§1).
- **Re-linking with PR numbers** — a re-link passes branch names, so a layer
  inserted during the work gets a PR at all (§2).
- **Omitting `--base "$TRUNK"` on the re-link** — `gh stack link` force-resets
  the bottom PR's base to the repo default branch on every call unless told
  otherwise; harmless today, live the moment a stack isn't based on trunk (§2,
  `/stack` §4).
- **Discovering the stack without `select(.open)`** — a merged stack stays
  listed forever; without the filter a re-run regenerates PR text against an
  already-shipped stack (§3).
- **Proofreading/appending to the old PR body** — §3 is a full overwrite
  synthesized from the final state, not an edit of the existing text.
- **Stopping without pushing** — the folded commits must land on the remote;
  §2's `jj git push` is part of the job. (`ready`/`merge` are still the
  caller's.)
- **Running from the session home** — `cd worktree` first.
