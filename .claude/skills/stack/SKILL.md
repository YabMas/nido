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
  This is an **authoring obligation on you, checked by review — not
  machine-verified.** No gate enforces it; CI runs on the merged tip. Don't go
  looking for the check, and don't treat its absence as permission to skip it.
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

> **Shell variables do not survive between commands.** Each Bash call in this
> harness is a fresh shell — the working directory persists, the environment does
> not. **Re-derive `$SLUG`/`$SRC` in every block that uses them**, or inline the
> literal values. A block that references `$SLUG` without deriving it in the same
> block sends `-R ""` and fails. Every sibling skill repeats this derivation for
> that reason; the repetition is deliberate.

### Deriving `<session>`

Layer bookmarks are prefixed with the session name, so several steps need it.
Derive it from cwd rather than from `bb nido:session:link:list`, whose echoed
name mis-resolves slash-namespaced sessions (`feat/x/y` → `feat`): a session home
is `…/.nido/sessions/<project>/<session>/`, so split the path on `/sessions/` —
the **first** segment after it is `<project>`, and the **rest** (slashes and all)
is `<session>`.

### Stack discovery — the one way to read a published stack

**`gh stack view` cannot be used here.** It takes no positional arguments
(`Usage: gh stack view [flags]`; the only flags are `--json/--short/-h`), so it
always resolves the *current branch* — which a jj-colocated repo does not have.
In `$SRC` it fails with `✗ failed to get current branch: … not on any branch`,
unconditionally.

Read a published stack with `gh pr list` instead. It needs no git repository and
no current branch, so it runs **in the worktree**:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh pr list -R "$SLUG" --state open --limit 50 \
  --json number,url,headRefName,baseRefName,isDraft \
  --jq '.[] | select(.headRefName | startswith("<session>--"))'
```

Substitute the derived `<session>` into the `startswith` filter — the prefix is
what scopes the result to *this* session's layers.

**Order the layers bottom to top** by walking the `baseRefName` chain: the bottom
layer is the one whose `baseRefName` is `main`; the next is the one whose
`baseRefName` is the bottom layer's `headRefName`; and so on. (Equivalently,
match `headRefName` against the bookmark order read from `jj log`.) That ordered
list of `number`s is what every `gh pr edit`/`gh stack link` step below consumes.

**Empty result** means the stack is not published yet, not that discovery failed.

`/prepare-draft-pr`, `/squash`, and `/drive-home` all read the stack this way.
There is one primitive; do not hand-roll a second.

### Publish / push / re-link

Derive `$SLUG`/`$SRC` in this block — they do not survive from any earlier one:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)

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

### Numbers for the initial link, branch names for a re-link

`gh stack link` accepts branch names, PR numbers, or PR URLs, and behaves
differently for each — pick by what the call has to do.

- **Initial link — pass PR numbers.** Step 2 just created the PRs, so the numbers
  are in hand and nothing needs reusing or chaining. Numbers also skip
  `gh stack link`'s automatic branch push, which step 1's `jj git push` already
  did.
- **Re-link after a reshape — pass branch names** (`<session>--<l1>
  <session>--<l2> …`). This is the only form that repairs the stack in one call:
  *"For branches that already have open PRs, those PRs are used. For branches
  without PRs, new PRs are created automatically with the correct base branch
  chaining."* Numbers cannot create the PR a newly-inserted layer needs, and
  nothing else in this skill rewires an existing PR's base — see §6.

**The source repo does have the branches.** An earlier version of this skill
justified numbers by claiming the colocated source repo has no local git branches
— that is false. jj exports every bookmark to the colocated repo's
`refs/heads/*`, so `git branch --list` there shows one branch per layer. Branch
arguments are safe; the automatic push is redundant, not broken.

`gh stack link` is incremental: re-running after a shape change extends or
updates the stack and never removes PRs.

### Ready and merge — also from the source repo

Derive `$SRC` in this block too — it does not survive from an earlier one:

```bash
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
(cd "$SRC" && gh stack link <session>--<l1> <session>--<l2> <session>--<l3> --open)
(cd "$SRC" && gh stack merge <top-pr-number> --yes)
```

`--open` flips new and existing PRs from draft to ready for review.

**Merge by the top layer's PR number.** `gh stack link --help` states the
guarantee directly: *"Because stack and PR numbers never overlap, a numeric
first argument is treated as a stack only when it matches an existing stack."*
A PR number can therefore never collide with a stack number, so `gh stack merge
<top-pr-number>` is unambiguous. `<top-pr-number>` is the top layer's PR number,
already in hand from the discovery above (§4) — nothing else in this flow
supplies a stack number: `gh stack view` is banned, and `gh stack link`'s stack
number is printed only to its own output, never captured or threaded through
here.

`gh stack merge` is atomic and all-or-nothing; if any layer cannot merge, none
do. It hands the stack to the merge queue natively when the base branch has one.

### `gh stack link` and `gh stack merge` need explicit arguments

With no arguments they look up the current branch, which a jj-colocated repo does
not have (it sits in detached HEAD). With explicit arguments the lookup never
happens.

**This does not rescue `gh stack view`** — it has no positional arguments to make
explicit, so it always resolves the current branch and always fails here. Use the
`gh pr list` discovery primitive above instead.

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
jj split -r <change-id>        # interactively choose what goes in the LOWER half
jj bookmark create <session>--<new-slug> -r <the-new-lower-change>
```

**The original bookmark stays on the UPPER half — so the existing PR keeps the
upper half's diff, and the new lower half needs a new bookmark and a new PR.**
Verified against jj 0.42: `jj split` puts the *selected* changes in the lower
commit and the *remaining* changes in the upper one, and moves any bookmark that
pointed at the split commit to the **upper** half. Get this backwards and an open
PR silently repoints at a different diff than the one already reviewed.

One trap: the **lower** half inherits the *original change id*; the upper half
gets a fresh one. So the bookmark is not on the change id you started with. Read
the two halves back before naming them:

```bash
jj log -r 'main@origin..@' -T 'change_id.short() ++ " " ++ bookmarks ++ " | " ++ description.first_line() ++ "\n"'
```

If you wanted the *lower* half to keep the existing PR, move the bookmarks
explicitly (`jj bookmark set <session>--<old-slug> -r <lower>` and create the new
one on the upper half) — and say so in your report, since the open PR's diff
changes underneath its reviewer.

Then re-describe both halves with their own trailer and brief (§5), and re-link
(below).

**Insert a new layer below an existing one:**

```bash
jj new --insert-before <target-change-id>   # new change between target and its parent
# ...make the edits...
jj bookmark create <session>--<new-slug> -r @
```

Descendants rebase automatically. **`--insert-before` (or the equivalent
`jj new -A <parent-of-target>`) is required.** Bare `jj new <parent-of-target>`
creates a **sibling** of the target, not a link in the chain: the stack silently
forks into two heads, `main@origin..@` stops containing the upper layers,
`/squash` folds the wrong set, and `/align` rebases only the fork. Verified
against jj 0.42 — only the `--insert-*` forms relocate children.

**Land a fixup on a lower layer** (the review loop: fix, then fold at ship time):

```bash
jj new --insert-after <layer-tip-change-id>   # fixup lands on that layer
# ...make the fix...
jj bookmark set <session>--<slug> -r @         # REQUIRED
```

**The `jj bookmark set` is not optional.** jj moves a bookmark when the commit it
points at is *rewritten*, not when a *child* is added — so after `--insert-after`
the layer bookmark still points at the old tip. Skip it and the push publishes
nothing for that layer: the reviewer sees an unchanged PR and the fixup rides up
into the next layer's PR instead. `/squash` folds the fixup into the layer commit
later; until then it is a normal commit on that layer.

**Reorder two layers:**

```bash
jj rebase -r <change-id> --insert-before <other-change-id>
```

Check for conflicts afterward with `jj resolve --list`; a non-empty list means
the reorder was not legal — the layers have a real dependency and the original
order was right.

**After any reshape — re-link by BRANCH NAME:**

```bash
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
jj git push --allow-new -b 'glob:<session>--*'
(cd "$SRC" && gh stack link <session>--<l1> <session>--<l2> <session>--<l3>)
```

Bottom to top, **every** layer listed, including unchanged ones — that is how the
bases get re-chained.

Do **not** "re-run §4" literally: §4 step 2 is N × `gh pr create`, which errors
for every layer that already has a PR. Branch arguments are the re-link path
because `gh stack link` reuses the open PR for a branch that has one, creates a
PR for a branch that doesn't, and rewires every base in the chain — the last of
which nothing else here does. Insert a layer between L1 and L2 without it and
L2's PR keeps `--base <session>--<l1>`, so L2's diff silently swallows the
inserted layer's commits, destroying the bounded review the stack exists for.

## Common mistakes

- **Running `gh stack` in the worktree** — it needs a git repository; a
  non-colocated jj workspace has none. Run it from `$SRC` (§4). (`gh pr list`,
  the discovery primitive, needs neither and runs in the worktree.)
- **Using `gh stack view` at all** — it takes no arguments, so it always resolves
  the current branch and always fails in a jj-colocated repo. Read a published
  stack with the `gh pr list` primitive (§4).
- **Calling `gh stack link`/`gh stack merge` with no arguments** — they then look
  up the current branch, which a jj-colocated repo does not have. Always pass
  explicit arguments.
- **Hunting for a stack number to merge by** — nothing here captures one:
  `gh stack view` is banned and `gh stack link`'s stack number is never
  threaded through. Merge by the top layer's PR number instead — `gh stack
  link --help` guarantees stack and PR numbers never collide (§4).
- **Passing PR numbers to `gh stack link` on a re-link** — numbers cannot create
  the PR an inserted layer needs, nor re-chain bases. Numbers are for the initial
  link only; a re-link passes branch names (§4, §6).
- **Inserting a layer with bare `jj new <parent>`** — that makes a sibling and
  forks the stack. Use `jj new --insert-before <target>` (§6).
- **Landing a fixup with `--insert-after` and not running `jj bookmark set`** —
  the bookmark stays on the old tip and the layer's PR never updates (§6).
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
