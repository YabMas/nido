---
name: stack
description: Develop a session's work as a stack of layered PRs — the layering doctrine (how to cut layers), the jj + gh stack mechanics, and the restack recipes. Cited by /prepare-draft-pr, /squash, /align, and /drive-home. Usage: /stack
---

# /stack

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/stack/`
> and is injected into every spawned session's composed `.claude/skills/`.
> The doctrine reference for `/prepare-draft-pr`, `/squash`, `/align`, and
> `/drive-home`.

## What this is

How to develop a session's work as an ordered stack of pull requests, each a
focused layer, so a large autonomous change can be reviewed layer by layer with
targeted feedback.

**Invoke this at planning time, not at ship time.** The layers are planned
before they are built. A doctrine read only when shipping cannot produce layered
commits — by then the work is a heap.

## 0. Should this be a stack at all?

**No stack** when the change is under ~200 lines total, or has no real
dependency seam. One plain PR, today's flow. GitHub's own docs: stacking *"is
not a reason to split one small change into five PRs."*

**Stack** when there are genuine dependency boundaries — schema beneath the code
that reads it, a mechanical rename apart from the judgment call it enables.

Target **3–5 layers**. Past ~7, say so: the ticket is probably two tickets.

Record the decision in the plan. A one-layer stack is exactly today's flow,
which is why every sibling skill forks on **layer count**, not on a flag.

## 1. Ordering — dependency direction

One hard rule: **if code in layer A depends on code in layer B, B is in the same
layer or lower.** Dependency direction is objective; ordering is never a taste
call.

Strata are a topological sort of *this change's* dependency graph, named from
the change. The common shape:

**foundation → core (one per area of substance) → wiring → supersede**

- **foundation** — migrations, malli schemas, `defattr`, shared types
- **core** — domain logic, module internals. **Plural** when the change has
  substance in more than one area. Substantial UI work is its own core stratum,
  usually above domain core since components consume the domain.
- **wiring** — routes, call sites, connecting existing components to new
  functionality
- **supersede** — delete the old path, drop the flag, remove dead code.
  **Always on top**: you can only delete the old thing once the new one is wired.

This is a common shape, not a schema. The test for whether something earns its
own stratum:

> Does it carry its own design decisions, and does reviewing it require a
> different mindset?

New components: yes. Wiring existing components to new functionality: no — that
is wiring.

## 2. Subdivision — one review mode per layer

**Mechanical or judgment. Never mixed.**

Lines are a bad proxy for what matters, which is review cost. A 2,000-line
uniform rename costs a reviewer *O(1)* — confirm uniformity, spot-check, done. A
200-line judgment diff costs *O(lines)*.

- **Mechanical layer** — one uniform transformation, statable in one sentence,
  with no exceptions hidden inside. **Unbounded size.**
- **Judgment layer** — everything else. Soft target 50–200 changed lines. Past
  ~400, ask yourself whether this is really one thing; if you keep it, say why.
  These are guidance, not gates.
- **The sharp edge:** a layer labelled mechanical that contains
  specially-handled sites **is not mechanical**. Either those sites move up into
  a judgment layer, or the layer is reclassified. This is the rule that stops a
  real decision being smuggled into a diff the reviewer was about to skim.

**Tiebreaker.** Unsure whether two things belong in one stratum? *Would these go
to the same specialist?* Project review lanes are real subject boundaries.

## 3. Universal rules

- **One-sentence title test.** Every layer's PR title is one sentence containing
  no "and". Needing "and" means it is two layers. Cheapest check here, highest
  yield.
- **Independent correctness.** The build passes and tests are green at every
  layer, not only the top. Stopping after any layer leaves a working system.
- **Size exemptions.** Pure deletions and generated files count toward nothing.
  A 2,000-line supersede layer is a one-minute review.

## 4. Mechanics

### Bookmarks

**`<session>--<slug>`** — double dash, content-named slugs, never numbered.

Use `--`, not `/`. jj's own bookmark store accepts `<session>/<slug>` and even a
dry-run push succeeds, because the directory/file conflict is a **git ref-store**
constraint: it only bites once `refs/heads/<session>` exists on the remote. The
session bookmark is unpushed today, so `/` appears to work — until anything
pushes it, at which point every layer push breaks at once. `--` costs nothing and
removes the failure mode structurally.

No ordinals in branch names: layers get inserted mid-stack, and renumbering would
rename branches and orphan open PRs. **Order is derived from `jj log`, never
encoded in a name.** Ordinals live in the PR title (`[2/5] …`), which `/squash`
regenerates each round.

Examples: `impl-BR-1234--malli-schemas`, `impl-BR-1234--rubric-core`,
`impl-BR-1234--wiring`.

### The session bookmark stays local and unpushed

Bare `jj git push` pushes *tracked* bookmarks. Once the layer bookmarks are
tracked and the session bookmark never is, every bare `jj git push` already means
"push the whole stack". Do not push the session bookmark.

### The `Layer:` trailer

One per layer commit, exactly one value:

| value | meaning | asserts |
|---|---|---|
| `mechanical` | one uniform transformation | no behavior change |
| `structural` | hand refactor | no behavior change |
| `behavioral` | changes behavior | tests move with it |

### Two derived values

`gh stack` cannot run in this worktree — it needs a git repository, and a
non-colocated jj workspace has none. It runs from the **colocated source repo**
instead. Derive both values in the worktree:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
```

### Publish / push / re-link

```bash
# 1. jj pushes the layers (from the worktree)
jj git push --allow-new -b 'glob:<session>--*'

# 2. one PR per layer, explicit base/head — -R means no local git is needed
gh pr create -R "$SLUG" --base main              --head <session>--<l1> --draft --title … --body …
gh pr create -R "$SLUG" --base <session>--<l1>   --head <session>--<l2> --draft --title … --body …
gh pr create -R "$SLUG" --base <session>--<l2>   --head <session>--<l3> --draft --title … --body …

# 3. link by PR NUMBER, from the colocated source repo
(cd "$SRC" && gh stack link <n1> <n2> <n3>)
```

Arguments run **bottom to top**, in the order read from `jj log`.

**PR numbers, not branch names.** Numbers skip `gh stack link`'s automatic branch
push, which would otherwise need local git branches the colocated source repo
does not have checked out — jj already pushed them in step 1.

`gh stack link` is incremental: re-running after a shape change extends or
updates the stack and never removes PRs.

### Ready and merge — also from the source repo

```bash
(cd "$SRC" && gh stack link <n1> <n2> <n3> --open)   # draft → ready, all layers
(cd "$SRC" && gh stack merge <n_top> --yes)
```

`gh stack merge` is atomic and all-or-nothing; if any layer cannot merge, none
do. It hands the stack to the merge queue natively when the base branch has one.

### Every `gh stack` argument must be explicit

With no arguments these commands look up the current branch, which a jj-colocated
repo does not have (it sits in detached HEAD) — `gh stack view` there fails with
`✗ failed to get current branch: … not on any branch`. With explicit arguments
the lookup never happens.

## 5. Layer commit format

Every layer commit carries the trailer and the review brief. `/prepare-draft-pr`
lifts this into the PR body; `/squash` regenerates it.

    <type>(<scope>): <one sentence, no "and">

    <what this layer does, 1–3 sentences>

    Layer: mechanical

    Claims: the rename is uniform across all 40 call sites.
    Verify: confirm no call site got special handling; confirm no behavior
      changed alongside the rename.
    Lane: lane-malli
    Out of scope: the new validation logic — that lands in the layer above.

    Refs BR-####

### The four brief fields

- **Claims** — what this layer asserts about itself.
- **Verify** — concrete checks, never "review this".
- **Lane** — which specialism applies; also how a reviewer agent is picked.
- **Out of scope** — what this layer's reviewer should *not* flag, and where it
  lives instead.

**Out of scope is the field that makes bounded review work.** Without it, every
reviewer re-derives the whole change and the stack's benefit is lost.

## 6. Restacking — you own the shape

You may restructure the stack at any time, published or not. Report what you
changed and why. jj rebases descendants automatically, so the layers above
follow.

**Split one layer into two:**

```bash
jj split -r <change-id>        # interactively choose what stays below
jj bookmark create <session>--<new-slug> -r <the-new-lower-change>
```

Then re-describe both halves with their own trailer and brief (§5), and re-run
the publish sequence (§4) — `gh stack link` will insert the new PR in place.

**Insert a new layer below an existing one:**

```bash
jj new <parent-of-target>      # new change between parent and target
# ...make the edits...
jj bookmark create <session>--<new-slug> -r @
```

Descendants rebase automatically.

**Reorder two layers:**

```bash
jj rebase -r <change-id> --insert-before <other-change-id>
```

Check for conflicts afterward with `jj resolve --list`; a non-empty list means
the reorder was not legal — the layers have a real dependency and the original
order was right.

**After any reshape:** re-run the publish sequence from §4. `gh stack link` is
incremental and never removes PRs.

## Common mistakes

- **Running `gh stack` in the worktree** — it needs a git repository; a
  non-colocated jj workspace has none. Run it from `$SRC` (§4).
- **Calling `gh stack` with no arguments** — it then looks up the current
  branch, which a jj-colocated repo does not have. Always pass explicit
  arguments.
- **Passing branch names to `gh stack link`** — pass PR numbers, so it does not
  try to push branches the source repo has not checked out.
- **Using `/` in a layer bookmark name** — it appears to work locally and breaks
  once the session bookmark reaches the remote. Use `--`.
- **Numbering branch names** — insertions then force a rename, which orphans
  open PRs. Ordinals go in PR titles only.
- **Reading this skill only at ship time** — by then the work is a heap and the
  only option left is post-hoc restacking. Invoke at planning time.
- **Mixing review modes in one layer** — a "mechanical" layer with three
  special-cased sites is not mechanical.
- **Stacking a small change** — under ~200 lines with no dependency seam, ship
  one plain PR.
- **Pushing the session bookmark** — only `<session>--*` bookmarks get pushed.
- **Writing "and" in a layer's PR title** — that is two layers.
