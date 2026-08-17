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

### Stack discovery — the stacks API first, `gh pr list` as the fallback

**`gh stack view` cannot be used here.** It takes no positional arguments
(`Usage: gh stack view [flags]`; the only flags are `--json/--short/-h`), so it
always resolves the *current branch* — which a jj-colocated repo does not have.
In `$SRC` it fails with `✗ failed to get current branch: … not on any branch`,
unconditionally.

**Read a published stack from the REST stacks endpoint.** It needs no git
repository and no current branch, so it runs **in the worktree**, and it returns
the stack's identity, its base, and its PRs **already ordered** — no chain to
walk, and nothing a mis-based PR can confuse:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh api repos/"$SLUG"/stacks \
  --jq '.[] | select(.open) | select(any(.pull_requests[]; .head.ref | startswith("<session>--")))
            | "stack #\(.number) base=\(.base.ref) prs=\([.pull_requests[].number])"'
# → stack #12 base=main prs=[5,9,6,7,11]
```

**Filter on `.open`.** A merged (or otherwise closed) stack stays listed by this
endpoint forever, with `open:false` — there is no way to delete a merged stack
record. Without the filter, a session whose stack already shipped still "has a
stack" by this query, and every caller that branches on that result (this
skill's own callers, `/squash` §3, `/drive-home` §2) does pointless work
against a stack that is already done. Verified: a merged stack's object
persists with `open:false` and the same PR numbers.

The full objects carry per-PR `number`, `state`, `draft`, and `head.ref`, so one
call answers "which layers, in what order, and are they still drafts". The
`number` at the top of the object is the **stack number**, which
`gh stack unstack` requires (§6). Verified against real PRs.

**Scope it to this session.** The endpoint returns *every* stack in the repo, and
this repo runs a dozen sessions at once — the `startswith("<session>--")` filter
is what keeps a read (or worse, an unstack) off another session's stack. Same
rule as the anchored `grep "^<session>--"` on `jj bookmark list`.

**Empty means this session has no stack yet** — not that discovery failed.

**Fallback — `gh pr list`, for reading PRs before a stack object exists.** The
stacks endpoint only knows about linked stacks, so the window between
`gh pr create` and `gh stack link` is invisible to it. That is exactly
`/prepare-draft-pr`'s re-run guard, which must see the PRs it just created:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh pr list -R "$SLUG" --state open --limit 50 \
  --json number,url,headRefName,baseRefName,isDraft \
  --jq '.[] | select(.headRefName | startswith("<session>--"))'
```

Substitute the derived `<session>` into the `startswith` filter — the prefix is
what scopes the result to *this* session's layers.

**Order that result bottom to top** by walking the `baseRefName` chain: the
bottom layer is the one whose `baseRefName` is the trunk branch (`$TRUNK`, below);
the next is the one whose `baseRefName` is the bottom layer's `headRefName`; and
so on. (Equivalently, match `headRefName` against the bookmark order read from
`jj log`.) **A mis-based PR breaks that walk** — which is why the stacks endpoint
is preferred wherever a stack already exists.

That ordered list of `number`s is what every `gh pr edit`/`gh stack link` step
below consumes.

`/prepare-draft-pr`, `/squash`, and `/drive-home` all read the stack through
these two primitives — stacks API first, `gh pr list` when there is no stack yet.
Do not hand-roll a third.

### The trunk branch is derived, never hardcoded

jj revsets use the **`trunk()`** revset function rather than a literal
`main@origin` — it resolves to the remote trunk whatever the default branch is
called. For `gh`, read the name from the repo:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
```

`$TRUNK` is the bottom layer's `--base` and the `baseRefName` that identifies the
bottom of the chain. Derive it in the same block that uses it — shell variables
do not survive between commands.

### Publish / push / re-link

Derive `$SLUG`/`$SRC` in this block — they do not survive from any earlier one:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')

# 1. jj pushes the layers (from the worktree)
jj git push -b 'glob:<session>--*'

# 2. one PR per layer, explicit base/head — -R means no local git is needed
gh pr create -R "$SLUG" --base "$TRUNK"          --head <session>--<l1> --draft --title … --body …
gh pr create -R "$SLUG" --base <session>--<l1>   --head <session>--<l2> --draft --title … --body …
gh pr create -R "$SLUG" --base <session>--<l2>   --head <session>--<l3> --draft --title … --body …

# 3. link by PR NUMBER, from the colocated source repo
(cd "$SRC" && gh stack link --base "$TRUNK" <n1> <n2> <n3> 2>&1); echo "EXIT=$?"
```

Arguments run **bottom to top**, in the order read from `jj log`.

**Always pass `--base "$TRUNK"`.** Without it, `gh stack link` silently
force-resets the bottom PR's base to the repository default branch — observed,
verified: `gh stack link 13 14` on a PR deliberately created against a
non-trunk base printed `✓ Updated base branch for PR #13 to main`, unasked.
`gh stack link --help` explains why: `--base string   Base branch for the
bottom of the stack (defaults to the repository default branch)` — the bottom
PR's base is overwritten on **every** call, never read from the PR. For nido
today the bottom layer's base already equals `$TRUNK`, so passing it is a
no-op — but it stops being one the moment any stack is based on something
other than trunk, and the silent retarget lands on the one branch this whole
design is careful never to touch.

**No `--allow-new` on the push.** jj 0.42 removed the flag — `jj git push
--allow-new -b …` exits with `error: unexpected argument '--allow-new' found`,
so a command carrying it never runs at all. `-b` now implies it: *"If a bookmark
isn't tracking anything yet, the remote bookmark will be tracked
automatically."* Scope the push with `-b 'glob:<session>--*'` and nothing else is
needed.

### `gh stack link` writes to stderr and is not atomic

**Its progress and success lines go to stderr; stdout is empty.** Capturing
stdout — `$(...)`, `| tee`, `--json`-style parsing — gets nothing at all. Redirect
`2>&1` if you want to read what it did.

**Check the exit code.** It exits `5` on a partial failure, and a partial failure
leaves damage: in the observed case it had already created a new PR, left that PR
orphaned outside the stack, and left a mid-stack PR mis-based. **Nothing rolls
back.** An agent that ignores the exit code ships a corrupted stack whose
mid-stack PR diff swallows a layer below it.

On a non-zero exit, read the stderr text, then repair with §6's
unstack-then-link recipe — do not simply re-run the same call.

### Numbers for the initial link, branch names for a re-link

`gh stack link` accepts branch names, PR numbers, or PR URLs, and behaves
differently for each — pick by what the call has to do.

- **Initial link — pass PR numbers.** Step 2 just created the PRs, so the numbers
  are in hand and nothing needs reusing or chaining. Numbers also skip
  `gh stack link`'s automatic branch push, which step 1's `jj git push` already
  did.
- **Re-link after a reshape — pass branch names** (`<session>--<l1>
  <session>--<l2> …`). This is the only form that can create a PR for a layer
  that has none: *"For branches that already have open PRs, those PRs are used.
  For branches without PRs, new PRs are created automatically with the correct
  base branch chaining."* Numbers cannot do that.

**Branch arguments are safe because `gh stack link` resolves them server-side.**
It looks each branch up through the GitHub API and prints `Found PR #5 for branch
<name>` — it never needs a local ref. An earlier version of this skill instead
justified them by claiming jj exports every bookmark into the colocated source
repo's `refs/heads/*`; **that is false from a non-colocated workspace.** jj only
exports when a jj command runs *in the colocated repo*, so measured right after a
push from the worktree, `git branch --list '*<session>*'` in `$SRC` is empty
while `git ls-remote` shows every layer on the remote. The conclusion holds, the
old reason does not — and reasoning from the old one leads to wrong commands.

**A re-link only ever appends at the top.** `gh stack link` is incremental for a
top-append and a no-op re-run, and it never removes a PR. It **cannot** insert or
reorder below the top of a live stack: GitHub locks the base of every PR that
belongs to a stack, so the call fails (exit 5,
`✗ Cannot update stack: new PRs must be added to the top of the existing stack`)
*after* half-mutating. §6 has the verified repair — unstack first, then link.

### Ready and merge — also from the source repo

Derive `$SLUG`/`$SRC`/`$TRUNK` in this block too — they do not survive from an
earlier one:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
(cd "$SRC" && gh stack link --base "$TRUNK" <session>--<l1> <session>--<l2> <session>--<l3> --open 2>&1); echo "EXIT=$?"
(cd "$SRC" && gh stack merge <top-pr-number> --yes --rebase)
```

`--open` flips new and existing PRs from draft to ready for review. Verified:
it flips **both** newly-created and pre-existing PRs, with an explicit
per-PR confirmation line (`✓ Marked PR #13 as ready for review`), and branch
names resolve server-side with no local ref needed.

`--base "$TRUNK"` on this link is the same guard as §4's initial link — see
above; it is required here too, not just at publish time.

**Do not merge if the `--open` link exited non-zero** — the stack shape on GitHub
is wrong, and `gh stack merge` would land a mid-stack PR carrying the layer below
it. Repair with §6 case B first.

**Merge by the top layer's PR number.** `gh stack link --help` states the
guarantee directly: *"Because stack and PR numbers never overlap, a numeric
first argument is treated as a stack only when it matches an existing stack."*
A PR number can therefore never collide with a stack number, so `gh stack merge
<top-pr-number>` is unambiguous. `<top-pr-number>` is the top layer's PR number,
already in hand from the discovery above (§4) — the shortest path, and the one
this flow takes.

**The stack number is obtainable too** — `gh api repos/"$SLUG"/stacks` returns it
(§4), and `gh stack unstack` requires it (§6). Merging by top-PR number is a
convenience, not a workaround for a number nobody can get.

`gh stack merge` is atomic and all-or-nothing; if any layer cannot merge, none
do. Verified: a real two-layer merge landed both PRs one second apart from a
single invocation, with the base branch's final history showing both layer
commits in order, linear, no merge commits, each carrying exactly its own
layer's files.

**Always pass `--rebase`.** With no method flag, `gh stack merge --yes` uses
*"your last-used merge method"* (`gh stack merge --help`) — mutable,
per-machine state, not a guaranteed default. Observed: an unflagged call
merged *"via rebase"* only because that happened to be this machine's
last-used method. `--rebase` is what a stack wants — `/squash` leaves exactly
one commit per layer, and rebase lands exactly one commit per layer on
trunk — but nothing guarantees the next machine, or this one after someone
runs `gh stack merge --squash` once, picks it. A stray `--squash` would
collapse a later stack's layers into one commit and destroy the layering the
whole design exists to produce. Pinning it removes the dependency on that
state.

Per `gh stack merge --help`: *"If the base branch uses a merge queue, the
stack is added to the queue and merges once the queue processes it; otherwise
it is merged directly."* **This is vendor-documented, not independently
verified** — `YabMas/nido` has no rulesets and no branch protection, so no
merge queue exists here to exercise; only the direct-merge half has been
observed.

### `gh stack link` and `gh stack merge` need explicit arguments

With no arguments they look up the current branch, which a jj-colocated repo does
not have (it sits in detached HEAD). With explicit arguments the lookup never
happens.

**This does not rescue `gh stack view`** — it has no positional arguments to make
explicit, so it always resolves the current branch and always fails here. Use the
discovery primitives above (`gh api repos/"$SLUG"/stacks`, or `gh pr list` before
a stack exists) instead.

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
jj split -r <change-id> -m "<subject for the lower half>" <paths…>   # the listed paths go in the LOWER half
jj bookmark create <session>--<new-slug> -r <the-new-lower-change>
```

**Name the paths, and always pass `-m`; never run bare `jj split -r <rev>`.**
With no filesets jj opens the builtin diff editor — an interactive TUI — and a
headless run (`claude -p` under the coordinator daemon) hangs there forever with
no output and no typed event, the same failure class as the `jj squash`
message-editor hang `/squash` §1 guards against. Filesets fix only that half of
it: `jj split [OPTIONS] [FILESETS]...` selects non-interactively — *"Files
matching any of these filesets are put in the selected changes"*, and the
selected changes are the lower half — but every layer commit carries a
description by mandate (§5), and per `jj split --help`, *"If the change you
split had a description, you will be asked to enter a change description for
each commit."* Filesets alone still hit that second, description editor and
hang just the same. `-m` is what suppresses it: *"The change description to use
for the selected changes (don't open editor)... The other revision will keep
its original description, if any."* (Don't reach for `--editor` instead — it
*forces* an editor open even when `-m` is given.) Split by file when you can;
when a single file must be divided, that genuinely needs a human — say so
rather than opening an editor nothing can close.

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
jj log -r 'trunk()..@' -T 'change_id.short() ++ " " ++ bookmarks ++ " | " ++ description.first_line() ++ "\n"'
```

If you wanted the *lower* half to keep the existing PR, move the bookmarks
explicitly (`jj bookmark set <session>--<old-slug> -r <lower>` and create the new
one on the upper half) — and say so in your report, since the open PR's diff
changes underneath its reviewer.

Then re-describe both halves with their own trailer and brief (§5) — the `-m`
subject above is only enough to clear the split without hanging, not a real
brief, and the upper half keeps whatever description the layer had before the
split, which is now stale — and re-link (below).

**Insert a new layer below an existing one:**

```bash
jj new --insert-before <target-change-id>   # new change between target and its parent
# ...make the edits...
jj bookmark create <session>--<new-slug> -r @
```

Descendants rebase automatically. **`--insert-before` (or the equivalent
`jj new -A <parent-of-target>`) is required.** Bare `jj new <parent-of-target>`
creates a **sibling** of the target, not a link in the chain: the stack silently
forks into two heads, `trunk()..@` stops containing the upper layers,
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

Check for conflicts afterward with `jj resolve --list`. **Read the listing, not
the exit code:** when the reorder was clean, `jj resolve --list` *errors* — exit
2, `Error: No conflicts found at this revision` — so an agent branching on exit
status reads the success case as a failure. A **listed** conflict means the
reorder was not legal: the layers have a real dependency and the original order
was right.

**After any reshape — re-link by BRANCH NAME. Which recipe depends on where the
shape changed.**

Do **not** "re-run §4" literally in either case: §4 step 2 is N × `gh pr create`,
which errors for every layer that already has a PR. Branch arguments are the
re-link form — `gh stack link` reuses the open PR for a branch that has one and
creates a PR for a branch that doesn't.

#### Case A — pure top-append (a new layer above the current top)

Link incrementally. No unstacking; this path is verified to extend a live stack:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
jj git push -b 'glob:<session>--*'
(cd "$SRC" && gh stack link --base "$TRUNK" <session>--<l1> <session>--<l2> <session>--<l3> 2>&1); echo "EXIT=$?"
```

Bottom to top, **every** layer listed, including unchanged ones.

#### Case B — anything below the top: insertion, reorder, or a split that adds a lower layer

**Unstack first.** GitHub locks the base of every PR that belongs to a stack, so
a plain re-link *cannot* rewire a mid-stack base — it fails **and half-mutates**:
it creates the new layer's PR, leaves it orphaned outside the stack, leaves the
layer above mis-based, and rolls nothing back. The mid-stack PR then shows two
layers' commits and files, so the reviewer who was already handed it is now
reviewing the layer below as well. Proven twice, at both levels:

```
$ gh stack link … (delta inserted mid-stack)
✗ Cannot update stack: new PRs must be added to the top of the existing stack
$ gh pr edit 6 -R "$SLUG" --base <session>--delta
GraphQL: Cannot change the base branch because the pull request is part of a stack.
```

Dissolving the stack object releases the base lock, and then a **single**
`gh stack link` re-chains every base and rebuilds the stack:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
jj git push -b 'glob:<session>--*'
# scope to THIS session's stack — the repo holds other sessions' stacks too
STACKNUM=$(gh api repos/"$SLUG"/stacks \
  --jq '[.[] | select(.open) | select(any(.pull_requests[]; .head.ref | startswith("<session>--")))][0].number // empty')
if [ -n "$STACKNUM" ]; then
  (cd "$SRC" && gh stack unstack "$STACKNUM")
  # confirm the stack is actually gone before linking — see below for why
  STILL=$(gh api repos/"$SLUG"/stacks --jq "[.[] | select(.number == $STACKNUM)] | length")
  if [ "$STILL" != "0" ]; then
    echo "stack $STACKNUM still exists after unstack — a PR in it is queued for merge or has auto-merge enabled; resolve that before linking" >&2
    exit 1
  fi
else
  echo "no stack for this session — skip the unstack, link directly"
fi
(cd "$SRC" && gh stack link --base "$TRUNK" <session>--<l1> <session>--<l2> … <session>--<lN> 2>&1); echo "EXIT=$?"
```

Bottom to top, **every** layer listed. Verified against a stack whose bases had
been deliberately corrupted: the one call reported `✓ Updated base branch for PR
#6 …`, `✓ Updated base branch for PR #7 …`, rebuilt the stack, and the mid-stack
PR was a clean single-layer diff again.

**Select the stack by this session's layer prefix, not `.[0]`.** The endpoint
returns *every* stack in the repo, and this repo runs a dozen sessions at once —
`.[0]` would happily unstack somebody else's. Same lesson as the anchored
`grep "^<session>--"` on `jj bookmark list` (§4): repo-wide listings must be
scoped to the session before they are acted on. An empty `$STACKNUM` means this
session has no stack — the `if`/`else` above branches on that rather than
running the unstack anyway. (`// empty` is what makes the check work: without it
`--jq` prints the string `null`, which `[ -n … ]` reads as set.)

**Also filter to `select(.open)`**, same as the discovery queries above. The
endpoint keeps a merged stack listed indefinitely with `open:false`. Without
this filter, re-running this recipe after the session's stack has already
merged — which `/drive-home` documents as safe — would match the merged stack,
fail to unstack it, and then have the `STILL` check below misreport that as a
PR queued for merge or with auto-merge enabled, blocking a re-run that has
nothing left to do.

`gh stack unstack` takes the stack number **positionally** and, per its own help,
*"works from anywhere in the repository, whether or not the stack is checked out
locally"* — it needs no current branch, so it is safe in `$SRC`. In the clean
case it removes only the stack object — the PRs and their bases survive, and the
following link puts both back — but per its own help it is not guaranteed to be
a clean case: *"PRs that are queued for merge or have auto-merge enabled are
left stacked. When some pull requests remain stacked, the stack is kept (and
local tracking, if any, is unchanged)."* A queued or auto-merge PR mid-stack
makes the unstack a partial no-op described as a kept stack, not a hard
failure — nothing in its own help promises a non-zero exit for that case — so a
bare exit-code check isn't enough, and
linking straight into a base lock that never actually released reproduces the
exact half-mutation this recipe exists to prevent (the two failures quoted
above). That's why the block above re-queries `repos/"$SLUG"/stacks` for stack
`$STACKNUM` after unstacking and refuses to link until it's confirmed gone.

**If there is no stack yet** (`gh api repos/"$SLUG"/stacks` returns `[]`), skip
the unstack — there is no base lock to release. Link directly.

#### After either case — fix any PR `gh stack link` created itself

A PR the link auto-creates gets a **branch-derived title and boilerplate body**:

```
title=probe stack  delta      ← from the branch name; the "--" became two spaces
body=<sub>Stack created with GitHub Stacks CLI…</sub>
isDraft=true
```

Draft is right; the rest is not. Read the stderr for `✓ Created PR #<n> for
<branch>`, then give that PR the layer's real title and brief (§5) — otherwise an
inserted layer ships with a garbage title and **no Claims/Verify/Lane/Out of
scope**, which is exactly the bounded review the stack exists for:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh pr edit <new-n> -R "$SLUG" --title "[<n>/<N>] <subject>" --body "<layer brief>"
```

Renumber the other layers' titles too — an insertion changes every `[n/N]` above
it. `/squash` §3 regenerates all of them from `jj log` at ship time; do it here as
well if the stack is being handed to a reviewer before then.

#### After a stack merges — cleaning up the base branch needs a fetch first

`gh stack merge` advances the base branch on the remote as part of the merge.
If you then try to delete that branch bookmark from the worktree the normal
way, it fails on stale info:

```
$ jj git push -b 'glob:<session>--*'
Warning: The following references unexpectedly moved on the remote:
  refs/heads/<base-branch> (reason: stale info)
Error: Failed to push some bookmarks
```

(Verified: the two *layer* branches deleted fine in the same call — only the
branch the merge itself moved was rejected.) `jj git fetch` resurrects the
bookmark, but in a **conflicted** state; a second delete-and-push after that
succeeds. Not something to design around, just an expected extra step when
cleaning up a merged stack's branches.

## Common mistakes

- **Running `gh stack` in the worktree** — it needs a git repository; a
  non-colocated jj workspace has none. Run it from `$SRC` (§4). (`gh api …/stacks`
  and `gh pr list`, the discovery primitives, need neither and run in the
  worktree.)
- **Using `gh stack view` at all** — it takes no arguments, so it always resolves
  the current branch and always fails in a jj-colocated repo. Read a published
  stack with `gh api repos/"$SLUG"/stacks` (§4).
- **Calling `gh stack link`/`gh stack merge` with no arguments** — they then look
  up the current branch, which a jj-colocated repo does not have. Always pass
  explicit arguments.
- **Omitting `--base "$TRUNK"` on `gh stack link`** — every call, without
  exception, force-resets the bottom PR's base to the repository default
  branch (`gh stack link --help`). Observed retargeting a PR at `main` when it
  had deliberately been created against another base, with no prompt. Harmless
  today only because the bottom layer's base already is `$TRUNK`; pass it
  explicitly anyway so the failure mode can't reappear later (§4).
- **Omitting `--rebase` on `gh stack merge`** — with no method flag it uses
  whatever method was last used on that machine, which is state, not a
  guarantee. A stray `--squash` on any machine would collapse a stack's
  layers into one commit (§4).
- **Trusting stacks-API discovery without `select(.open)`** — a merged stack
  stays listed forever with `open:false`; unfiltered discovery treats a
  shipped stack as still live and does pointless (though harmless) work
  against it (§4).
- **Passing `--allow-new` to `jj git push`** — the flag does not exist in jj
  0.42; the command exits `unexpected argument` and nothing is pushed at all.
  `-b` implies it (§4).
- **Re-linking a mid-stack insertion without unstacking first** — GitHub locks a
  stacked PR's base, so the link fails *after* creating an orphan PR and leaving
  the layer above mis-based, swallowing the inserted layer's commits. Unstack,
  then link (§6, case B).
- **Ignoring `gh stack link`'s exit code, or capturing its stdout** — it prints
  everything to **stderr** and exits 5 on a partial failure that has already
  half-mutated the stack. Redirect `2>&1` and check the exit (§4).
- **Leaving an auto-created PR's title and body as `gh stack link` wrote them** —
  a branch-derived title and boilerplate body, with no review brief. `gh pr edit`
  it (§6).
- **Passing PR numbers to `gh stack link` on a re-link** — numbers cannot create
  the PR an inserted layer needs. Numbers are for the initial link only; a
  re-link passes branch names (§4, §6).
- **Branching on `jj resolve --list`'s exit code** — the *clean* case exits 2
  with `Error: No conflicts found at this revision`. Read the listing (§6).
- **Bare `jj split -r <rev>`** — it opens the interactive diff editor and hangs a
  headless run forever. Name the filesets: `jj split -r <rev> <paths>` (§6).
- **Hardcoding `main`** — use the `trunk()` revset for jj and a derived `$TRUNK`
  for `gh` (§4).
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
