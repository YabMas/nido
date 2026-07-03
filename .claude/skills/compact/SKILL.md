---
name: compact
description: Squash the current session's branch (main@origin..@) into one coherent commit with a synthesized conventional-commit subject + layered body, then — if a PR exists — regenerate the PR title/description from it. Mechanical — never halts, no push, no ledger events. Run from a session worktree. Usage: /compact
---

# /compact

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/compact/`
> and is injected into every spawned session's composed `.claude/skills/`.
> Sibling of `/align`, `/local-ci`, and `/drive-home` — the squash + PR-text
> phase `/drive-home` composes.

## What this is

Collapse the whole branch into **one** coherent commit and, if the branch has a
PR, rewrite that PR's title and description to match. It is **mechanical** — it
**never halts** and never touches the coordinator ledger. It **does not push**;
the caller publishes (`/drive-home` does push/ready/enqueue after this).

## Where to run it

Squash happens in the **worktree**; PR text uses `gh`. If you're in a session
home, `cd worktree` first. Sanity-check:

```bash
jj root   # errors if not a jj repo → stop; run from the worktree
```

## 1. Squash the branch into one coherent commit

Collapse the whole branch — the original work commits **plus** the throwaway
`chore(ci): …` rebase/fix commits — into a **single** commit. Squashing
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
  throwaway `chore(ci)` message.
- **Body — narrate the layers:** the logical pieces the author built up (refactor
  → feature → wiring → tests) survive as bullets/short paragraphs in the body,
  **not** as separate commits. This is the whole point of squash-to-one here.
- **Already one coherent commit?** (single commit in `main@origin..@` with a real
  message) → the squash is a no-op; just refresh the description if needed.

## 2. Regenerate the PR title + description (if a PR exists)

Discover the PR for the current branch:

```bash
gh pr view --json number,url 2>/dev/null
```

- **No PR** (`gh` errors "no pull requests found") → the squash above is the whole
  job; stop here.
- **PR exists** → regenerate its title/description:

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

## What this skill does NOT do

- **No push.** `jj git push` / `gh pr ready` / `gh pr merge` belong to the caller
  (`/drive-home`'s finish). `gh pr edit` only sets PR metadata and needs no push.
- **No halt.** Commit-shaping is mechanical — always one commit, never a split,
  never a question. The layering lives in the commit *body*, not in separate
  commits.
- **No ledger events.**

## Common mistakes

- **Leaving the squashed commit's subject as a `chore(ci)` message** — synthesize
  a real conventional-commit subject from the change.
- **Splitting into multiple commits, or halting to ask about commit structure** —
  `/compact` always squashes to one.
- **Proofreading/appending to the old PR body** — §2 is a full overwrite
  synthesized from the final state, not an edit of the existing text.
- **Pushing** — not `/compact`'s job.
- **Running from the session home** — `cd worktree` first.
