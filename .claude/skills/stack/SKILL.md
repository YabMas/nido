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

How to develop a session's work as an ordered stack of pull requests, one per
level of abstraction the change builds, so it is read the way it was built: each
layer in terms of what the layers below it provide.

**Invoke this at planning time, not at ship time.** The layers are planned
before they are built. A doctrine read only when shipping cannot produce layered
commits — by then the work is a heap.

**And invoke `/design` before this.** Layers are the levels of the design's
shape, so you cannot cut them before you have said what the shape is. The design
record's `:layers` is where the intended cut is stated; this skill is how it gets
built. If the record has no `:layers`, the design is not decomposed yet — go back
and finish it rather than inventing a cut here.

## 0. Should this be a stack at all?

**No stack** when the change is written at one level of abstraction, however
large, and none of §2's three boundaries applies. One plain PR, today's flow.
Nor when it is under ~200 lines, even with two levels — pure deletions and
generated files count toward nothing. GitHub's own docs: stacking *"is not a
reason to split one small change into five PRs."*

**Stack** when the change builds a level and then writes something in it — a
schema and the domain code expressed against it, a new abstraction and the
callers moved onto it — or when one of §2's boundaries applies: the old path
removed on top, a refactor beneath the change it enables, a mechanical sweep
apart from the judgment around it.

**The layer count is not the test — one story is.** State what the whole stack
claims, in one sentence, with no "and". If you cannot, it is two tickets, and
that is as true of four layers as of nine. A change that genuinely builds many
levels is not mis-scoped for having them.

**Why levels, and not what a reviewer can hold.** Cut to fit a reviewer's
working memory and every split looks like a gain — a smaller piece is always
easier to hold — so "can this be separated?" gets more true after each one, and
any check that asks it ratchets toward stacks of layers too small to mean
anything. The level test (§1) does not ratchet: split a level in two and one
half provides nothing the other is written in, so the split fails the very test
it was meant to pass. A reader is served by the structure — each layer readable
from the interfaces of the layers below it — not by the size of the pieces.

**And a cut is deleted at land time**, which bounds what getting it wrong can
cost: §3's collapse means no layer survives in the merged history. The levels
survive, because they are in the code, and the code lands whole whichever way it
was cut — so a cut that misreads them costs only the reading it was drawn for,
and a concern about the cut alone never blocks a design round or a review. The
shipping doctrine's *The words, and which boundaries survive a landing* is where
that rule and the four boundary words are stated; this section is it applied to
the vertical cut.

**The shape to aim at is a language being built.** Read bottom to top, each
layer is written in what the layers below provide, and provides what the layers
above are written in. A reader who stops at any layer holds a working
vocabulary, not half of one.

Record the decision in the plan. A one-layer stack is exactly today's flow,
which is why every sibling skill forks on **layer count**, not on a flag.

**This skill is the vertical cut only** — same story, ordered by dependency.
Deciding what belongs to a *different* story, and does not ship in this branch
at all, is the horizontal cut: see **`/spin-out`**. Deciding that the change
cannot safely arrive in one deploy, so it lands in several, is the temporal cut:
see **`/phase`**. All three are the same operation (routing a unit of work to a
destination) and all three cut along claim identity, so plan them together — a
layer you were about to build, a follow-up you were about to file and a phase
you were about to schedule are one question with three answers.

**A phase is a shipment, and a shipment may be a stack.** They compose rather
than compete: phase 2 of a plan being a three-layer stack is normal. The counts
multiply though — four phases of five layers each is the mis-scoping signal
firing on both axes at once, not thoroughness.

## 1. Levels — where a boundary goes

A layer is a **level of abstraction**, in the sense of stratified design
(Abelson & Sussman, *Structure and Interpretation of Computer Programs* §2.2.4):
a system built as a sequence of levels, each described in a language made from
the primitives of the level below it, each providing the primitives the next
level is written in.

> **The level test: name what this layer provides that the layer above is
> written in.**

What a level provides is vocabulary — a function, a type, a schema, a protocol,
a table — that the layer above uses by its interface, without reading its body.
Nothing to name, no boundary. That is also why the cut is stated in the design
record before it is cut in jj: the levels are a fact about the design's shape.

- **A level is a vocabulary, not a helper.** One function called once is not
  something a layer is written in; it belongs to the layer that calls it.
- **A level is not a pipeline stage.** Parse → transform → persist over one
  representation is one level expressed in three steps — the stance's point that
  a pipeline is not a decomposition. Nor is a file type, a directory, or a
  review lane.
- **One abstraction is one level.** Two layers that each provide half of it —
  one module's secret split across a boundary — leave the layer above with
  nothing it can use by interface alone.
- **The code has the levels; the cut reads them.** A change written at one
  level is one layer however large it is. A boundary the code does not have is a
  design change: build it in the code (§2's *refactor before change*), never only
  in the packaging.

One hard rule for order, and it falls out of the test: **if code in layer A
depends on code in layer B, B is in the same layer or lower.** A level sits below
everything written in it. Dependency direction is objective; ordering is never a
taste call.

The common shape, read as levels:

**data definition → domain vocabulary → the program written in it → supersede**

- **data definition** — migrations, malli schemas, `defattr`, shared types: what
  the domain code is written against
- **domain vocabulary** — the functions and module interfaces the change adds.
  **Plural** only when the change builds more than one level — a UI level
  composed from a domain level, say — never one per area of the codebase it
  touches.
- **the program written in it** — routes, call sites, the existing system moved
  onto the new vocabulary. A level when it is written in what the layer below
  provides; wiring that merely calls one new function belongs with that function.
- **supersede** — delete the old path, drop the flag, remove dead code. Not a
  level: §2's first boundary, always on top.

This is a common shape, not a schema. Plenty of changes have two of these, and a
change that has one is one layer.

## 2. Three boundaries that are not levels

The level test finds the change's structure. Three further boundaries are worth
drawing although they are not levels, because each separates a different *kind*
of change — and they are the only three. Nothing else splits a level: not its
size, not review mode, not which lane reviews it.

1. **Introduce, then remove.** Removing what a new abstraction replaces — the old
   path, the flag, the dead code — is its own layer, **always on top**: you can
   only delete the old thing once nothing is written in it any more. Introducing
   the new abstraction and moving callers onto it are levels already (§1); the
   removal is the boundary the level test would not draw. Pure deletions count
   toward nothing, so a 2,000-line supersede layer is a one-minute read.
2. **Refactor before change.** A behaviour-preserving restructure sits below the
   behaviour change it makes easy — *make the change easy, then make the easy
   change*. Often the refactor is what builds the level the change is written
   in, and §1 has already drawn it. When it is not, the boundary still separates
   *nothing changed* from *this changed*, which is what the `Layer:` trailer
   (§4) asserts.
3. **Mechanical sweep apart.** One uniform transformation — a rename, a
   reformat, a regenerated file — statable in one sentence with no exceptions,
   stays out of any layer that carries judgment. **Unbounded size.** Lines are a
   bad proxy here: a 2,000-line uniform rename costs a reader *O(1)* — confirm
   uniformity, spot-check, done — while a 200-line judgment diff costs
   *O(lines)*, and one buried in the other hides the judgment. A sweep of a
   handful of sites is part of the layer it serves.

   **The sharp edge:** a layer labelled mechanical that contains
   specially-handled sites **is not mechanical**. Either those sites move into
   the layer that carries the judgment, or the layer is reclassified. This is the
   rule that stops a real decision being smuggled into a diff the reader was
   about to skim.

**Every layer's claim must be checkable from that layer's own diff**, given the
interfaces of the layers below it — which is what the level test guarantees. A
reviewer is handed one layer and its `Claims:`, and nothing else — so a
claim they cannot confirm or refute from what is in front of them is a claim
written at the wrong altitude, and no amount of care further up recovers it. If
verifying "this is mechanical" needs the layer above to see what compensates for
it, the boundary is in the wrong place or the claim is overstated; fix one of
them, and do it now rather than discovering it in review. A reviewer saying *I
cannot check this from here* is reporting a defect in the cut, not asking a
question.

**A layer that provides nothing is not a small layer — it is not a layer.** One
that only forwards what the layer below already accepts, or renames on the way
through, gives the layer above nothing new to be written in. Fold it into the
layer whose vocabulary it serves.

The way these get into a plan is always the same: **the cut was drawn against
the call chain someone expected, not the one that exists.** A parameter
"threaded rung by rung, so a reviewer sees which contract changed" builds no
level at any rung — each rung is written in the same vocabulary it was before —
so it is one layer, and when the rungs pass an open map it is barely a change at
all. Only the code says which. Read the rungs before committing to the cut.

**When a layer resists on contact, suspect the cut.** Collapsing one boundary
while executing is a local fix. Collapsing a second is a finding about the
decomposition: stop and amend the design record then, not at the end. Two
collapses is the signal; a third means you are executing a plan you have already
disproved.

## 3. Universal rules

- **The title names what the layer provides.** One sentence, no "and". A title
  that needs "and" is listing the layer's parts — name the level instead, or the
  §2 boundary it is. If there is no one thing to name, it is two layers.
- **Every layer's claim traces to the design record.** A layer whose `Claims:`
  is not a line in `:layers` is a signal, and a useful one in both directions:
  either the design is incomplete, or the layer is smuggling a decision nobody
  stated. Cheap to check, and it catches the failure §2 warns about — a real
  decision hidden in a diff the reviewer was about to skim.
- **Independent correctness.** The build passes and tests are green at every
  layer, not only the top. Stopping after any layer leaves a working system.
  This is an **authoring obligation on you, checked by review — not
  machine-verified.** No gate enforces it; CI runs on the merged tip. Don't go
  looking for the check, and don't treat its absence as permission to skip it.
- **Independent correctness is not independent deployability**, and reading it
  as such is how a migration blows up. Green build is a fact about CI; habitable
  system is a fact about production. A layer that adds a `NOT NULL` column with
  the backfill in the layer above satisfies this rule at every layer and would
  take the site down if it shipped alone. It never does — but **only because
  `/land` collapses the reviewed stack into one PR before merging it.** That is
  a step someone has to perform, not a property of stacking: a merge queue
  merges its entries one at a time, so a stack enqueued as n pull requests lands
  in pieces the moment anything fails mid-arc. The layer boundary is survivable
  *because the collapse means no layer boundary is ever a merge boundary*. When
  a boundary **does** have to be survivable, it is not a layer: it is a phase,
  and `/phase` §2 is the test.

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

One per layer commit, exactly one value — the strongest thing the layer does, so
a level that restructures and changes behaviour is `behavioral`:

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
TRUNK=$(gh repo view "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
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
TRUNK=$(gh repo view "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')

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
TRUNK=$(gh repo view "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
(cd "$SRC" && gh stack link --base "$TRUNK" <session>--<l1> <session>--<l2> <session>--<l3> --open 2>&1); echo "EXIT=$?"
```

`--open` flips new and existing PRs from draft to ready for review. Verified:
it flips **both** newly-created and pre-existing PRs, with an explicit
per-PR confirmation line (`✓ Marked PR #13 as ready for review`), and branch
names resolve server-side with no local ref needed.

`--base "$TRUNK"` on this link is the same guard as §4's initial link — see
above; it is required here too, not just at publish time.

**Do not proceed to the merge if the `--open` link exited non-zero** — the stack
shape on GitHub is wrong, and the top PR may not carry every layer. Repair with
§6 case B first.

#### Merging is not `gh stack merge` — it is `/land` §8's collapse

**A stack is never enqueued as n pull requests.** `/land` §8 retargets the top
layer's PR onto trunk so its diff becomes the whole arc, and merges that one PR.
The layers are a review decomposition; they have done their work by the time
anything merges, and carrying them into a merge queue is what puts a half-arc on
trunk.

The reason is that a merge queue has no way to express "these n go together". It
merges an entry as soon as that entry is green — brian's queue carries
`minimumEntriesToMerge: 1` — so any failure mid-arc lands the layers below it and
evicts the rest. Observed 2026-08-24: a seven-layer stack landed layers 1–3 and
had 4–7 evicted, leaving trunk half-migrated for 2h01m.

**`gh stack merge` is genuinely atomic — on the direct-merge path only**, and
that is the path this skill verified: a real two-layer merge on `YabMas/nido`
landed both PRs one second apart from a single invocation, history linear
afterward. `YabMas/nido` has no rulesets and no branch protection, so **no merge
queue was ever exercised by that test**, and its result was generalised to
queue-protected repos where it does not hold. `gh stack merge --help` says both
things in one page: *"a single, all-or-nothing operation: if any PR cannot be
merged, none are"*, and *"If the base branch uses a merge queue, the stack is
added to the queue."* Those are two different operations, and every project this
skill targets uses the second.

**The method flags are moot in the flow that is actually used.** A queue applies
its own configured `mergeMethod` (brian's is `SQUASH`), and `gh pr merge --auto`
rejects a method flag on a queue-protected branch. `/land` §8 passes none.

**What this costs, stated plainly:** the collapsed PR is one queue entry, so it
lands as **one commit on trunk**, not one per layer. The layer manifest goes in
the collapsed PR's body, which becomes that commit's body — see `/land` §8. The
per-layer PRs and their reviews stay readable; they just are not what trunk
records.

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
    Deviation: three call sites got special handling — qualifies the claim:
      the rename is uniform across all 40 call sites.

    Refs BR-####

### The footer line is a delivery claim or a citation, never both

The last line names the ticket, and **which verb it uses decides whether a
machine acts on it.** A project may run automation that reads PR bodies and
moves a ticket when one says it was delivered — brian's `bb notify:deploy` moves
the Notion ticket to Review on staging deploy — and such automation reads a
closing verb, never a bare id.

    Closes BR-4312     ← this PR delivers the ticket; the ticket moves
    Refs BR-4312       ← this PR does work on the ticket; nothing moves

So a layer whose PR is not the one that merges writes `Refs`. **Exactly one PR
per workstream carries the closing verb** — the single PR, or a stack's TOP
layer, because `/land` collapses the stack into the top PR and only that one
merges. Every layer beneath it cites with `Refs`, which is what the example
above shows.

Two more cases take `Refs`, not `Closes`:

- **A phase that is not the last.** Phase 2 of 3 does not deliver the ticket, so
  it does not claim it — `/phase`'s whole point is that the intermediate states
  are deliberate, and a claim there moves the ticket before the work is done.
- **A ticket this change merely cites** — merge narration, archaeology, a
  related ticket. A closing verb in front of a ticket this PR does not deliver
  is how a ticket gets marked delivered by a PR that never touched it.

**The verb and which ticket ids may carry it are the project's, not nido's.**
Read `~/.nido/projects/<project>/github.edn` for a `:delivery-claim` key:

    :delivery-claim {:prefix "BR-" :verb "Closes"}

No such key — every project but brian today — and no claim line is written at
all: cite with `Refs` and leave the machinery out of it. `/prepare-draft-pr`
§"The delivery claim" has the full rule, including why a follow-up `FU-#` ref
never claims.

**On a phased change, the PR body opens with where in the plan this lands** —
a reviewer's first question is what state the system is being left in:

    Phase: 2/3 — reads move to the new column.
    While live: the old column is still written, so a revert is a config flip.
    Next phase opens when: one full billing cycle with no incident.

### The four brief fields you author

- **Claims** — what this layer asserts about itself: for a level, what it
  provides to the layers above; for a §2 boundary, that the removal, refactor or
  sweep is exactly that. It should be the `:claim` from the design record's
  `:layers`, verbatim or close to it; if you find yourself writing something the
  record doesn't contain, one of the two is wrong. A layer's claim is about the
  **diff**; if it is about the running system, you are describing a phase
  (`/phase` §2).
- **Verify** — concrete checks, never "review this".
- **Lane** — which specialism applies; also how a reviewer agent is picked.
- **Out of scope** — what this layer's reviewer should *not* flag, and where it
  lives instead: a layer above, a spun-out ref (`spun out as FU-12`, see
  `/spin-out`), a later phase (`that is phase 3, gated on the soak`, see
  `/phase`), an explicit decline with its reason, or — the strongest form — a
  citation of the design: *the record puts this behind the X boundary*. All five
  are legal; a bare "later" is not. The design citation is worth reaching
  for, because it is the only one the reviewer can check rather than take on
  trust.

**Out of scope is the field that makes bounded review work.** Without it, every
reviewer re-derives the whole change and the stack's benefit is lost.

### `Deviation:` — written by the review loop, never by you

A fifth field, and the only one in the brief you do not author: `bb
nido:review:loop` appends one line per finding it settled as a **deviation** —
the layer's stated claim is not true, and the decision was that the claim was
overstated rather than that the code is wrong.

**Both are kept, and that is the point.** `Claims:` says what was intended;
`Deviation:` says what actually happened. Do not edit the claim to match — a
claim silently narrowed until it is true tells a reader nothing, and the pair
tells them exactly where to look.

Preserve every `Deviation:` line through a fold or a rewrite. It is the only
place the finding reaches the person the claim was written for: the loop's own
record is in nido's ledger, which nobody reviewing the PR opens.

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
TRUNK=$(gh repo view "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
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
TRUNK=$(gh repo view "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
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

The merge advances the base branch on the remote. If you then try to delete that
branch bookmark from the worktree the normal way, it fails on stale info:

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

- **Splitting a level** — by size, by review mode, by pipeline stage, by lane.
  "Can this be separated?" is more true after every split, so it converges on
  more layers forever. The question is what the lower layer provides that the
  upper one is written in (§1), or which of §2's three boundaries this is.
- **Drawing a boundary the code does not have.** If the change wants a level
  that is not there, build it — a refactor below the change (§2) — rather than
  cutting the packaging as if it existed.
- **Cutting against the call chain you expect rather than the one that exists.**
  A parameter threaded "one rung per layer" is one layer, not four: threading it
  builds no level at any rung. Read the code before committing to the cut (§2).
- **Collapsing a second boundary during execution without amending the design.**
  One collapse is a local fix; two is a finding about the decomposition (§2).
- **`gh repo view -R "$SLUG"`** — `gh repo view` takes the repo as a
  POSITIONAL argument, unlike every other `gh` command here. With `-R` it
  exits `unknown shorthand flag: 'R'`, `$TRUNK` comes out empty, and the next
  command goes out as `--base ""`. Write `gh repo view "$SLUG"`.

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
- **Enqueueing the layers as n pull requests** — a merge queue merges its
  entries one at a time, so any failure mid-arc lands the lower layers and
  evicts the rest, leaving trunk half-migrated. Collapse first: `/land` §8 (§4).
- **Generalising `gh stack merge`'s atomicity from a repo with no merge queue** —
  it holds on the direct-merge path and says nothing about a queued one. That
  inference is exactly how this skill got it wrong (§4).
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
- **Burying a mechanical sweep in a judgment layer, or judgment in a sweep** —
  a "mechanical" layer with three special-cased sites is not mechanical (§2).
- **Stacking a change with one level** — ship one plain PR however large it is,
  and under ~200 lines ship one even with two (§0).
- **Using a layer boundary where a phase boundary is needed** — if the system
  has to run in that state, green tests are not the obligation; habitability is
  (§3, `/phase` §2).
- **Pushing the session bookmark** — only `<session>--*` bookmarks get pushed.
- **Writing "and" in a layer's PR title** — name what the layer provides; if
  there is no one thing, it is two layers (§3).
