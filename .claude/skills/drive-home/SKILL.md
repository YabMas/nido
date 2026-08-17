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

**Shell variables do not survive between commands** — each Bash call is a fresh
shell (cwd persists, the environment does not). Re-derive `$SLUG`/`$SRC` in every
block below that uses them, or inline the values; `-R ""` is what you get
otherwise.

Then read the stack with the shared discovery primitives (`/stack` §4), using the
`<session>` derived in §1. Ask the **stacks endpoint** first — it returns the
stack's number, base, and its PRs **already ordered**, and a mis-based PR cannot
confuse it. Re-derive `$SLUG` here; it does not survive from the block above:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh api repos/"$SLUG"/stacks \
  --jq '.[] | select(any(.pull_requests[]; .head.ref | startswith("<session>--")))
            | "stack #\(.number) base=\(.base.ref) prs=\([.pull_requests[].number])"'
```

**Keep the `startswith("<session>--")` filter** — the endpoint returns every
stack in the repo, and this repo runs a dozen sessions at once. Unfiltered, this
step would drive another session's stack to the merge queue.

Empty means *this session* has no stack object — which is also the state of a
single-PR session, and of layers created but never linked. Fall back to
`gh pr list`, which sees both:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
gh pr list -R "$SLUG" --state open --limit 50 \
  --json number,url,headRefName,baseRefName,isDraft,mergeStateStatus \
  --jq '.[] | select(.headRefName == "<session>" or (.headRefName | startswith("<session>--")))'
```

**Not `gh stack view`.** It takes no positional arguments, so it always resolves
the *current branch* — which a jj-colocated repo does not have. In `$SRC` it
fails unconditionally with `✗ failed to get current branch: … not on any branch`,
which would make this step conclude "neither" on every stack and re-invoke
`/prepare-draft-pr` against PRs that already exist. Both primitives above need no
git repo and no current branch, so they run here in the worktree.

Classify the result:

- **A stack object** → **keep every layer's `number` and `url` in the endpoint's
  order** (bottom to top). §6 needs those numbers and nothing else produces them.
  `gh pr view <n> -R "$SLUG" --json url,isDraft,mergeStateStatus` fills in
  per-PR detail the stack object does not carry.
- **No stack, but layers found** (`headRefName`s of the form `<session>--*`) →
  the layers were published but never linked. Order them by walking the
  `baseRefName` chain from the layer whose base is `$TRUNK`, and note that the
  link step still owes them a stack.
- **One PR whose `headRefName` is exactly `<session>`** → today's single-PR path,
  unchanged. Keep its number and URL.
- **Both empty** → run the **`/prepare-draft-pr` skill** now; it publishes the
  stack (or the single PR) and wires the correlation links the merge poller
  needs. Then re-read as above.

> Discovery asks GitHub directly rather than reading the session `:pr` link, so
> it's correct on idempotent re-runs and doesn't depend on nido's link
> bookkeeping — which can mis-resolve slash-namespaced sessions. The `:pr` /
> `:github` links are stamped by `/prepare-draft-pr` for the merge poller; they
> aren't needed for discovery here.

Never hand-roll `gh pr create` or `gh stack link` here — delegate to
`/prepare-draft-pr` so the poller bookkeeping is correct.

## 3. Rebase — `/align`

`cd worktree`, then invoke the **`/align`** skill. It rebases onto a fresh
`trunk()` and auto-resolves only trivial conflicts, halting on anything
semantic.

Re-check the observable outcome:

```bash
jj resolve --list   # lists nothing ⇒ clean; lists files ⇒ /align halted
```

**Judge by the listing, not the exit code.** The clean case *errors* — exit 2,
`Error: No conflicts found at this revision`, empty stdout. Reading that exit as
a failure would record a `:blocker` on every successful rebase.

Files listed ⇒ `/align` halted — record a `:blocker` (see "When it halts") and
stop. Nothing listed ⇒ continue.

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
jj git push -b 'glob:<session>--*'   # layer bookmarks only
```

`/squash` §2 already pushed, so this **normally reports nothing to push** — that
is the expected outcome, a safety net rather than a failure. Do not treat "no
bookmarks to push" as a problem.

Scope the push with `-b 'glob:<session>--*'`. Bare `jj git push` pushes every
*tracked* bookmark, and the session bookmark is tracked in any session that ran
the single-PR `/prepare-draft-pr` — there it would also force-update
`refs/heads/<session>`, republishing the whole stack as one branch beside the
layers. (Single-PR session: that branch **is** the PR's head, so push it —
`jj git push -b '<session>'`.)

Mark every layer ready. For a stack:

```bash
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
(cd "$SRC" && gh stack link <session>--<l1> <session>--<l2> <session>--<l3> --open 2>&1); echo "EXIT=$?"
```

`--open` flips new and existing PRs from draft to ready for review. Pass **branch
names** here, bottom to top, listing every layer: that form also picks up any
layer that still has no PR (`/stack` §4).

**Redirect `2>&1` and check the exit code.** `gh stack link` prints everything to
**stderr** — stdout is empty — and exits **5** on a partial failure that it does
not roll back. A non-zero exit here means the stack shape on GitHub is wrong,
most often a layer inserted below the top during the work (GitHub locks a stacked
PR's base, so this call cannot rewire one). **Do not proceed to `gh stack merge`
on a non-zero exit** — merging a mis-shaped stack lands a mid-stack PR whose diff
swallows the layer below it. Report the stderr text and point at `/stack` §6 case
B: unstack, then one link. Then re-run `/drive-home`.

For a single-PR session, `gh pr ready <number> -R "$SLUG"` — **both** the number
(from §2's discovery) and `-R`. `-R` alone is not enough: `gh pr ready -R <slug>`
exits `argument required when using the --repo flag`, and bare `gh pr ready`
cannot resolve a repo from this worktree.

Then merge:

```bash
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
(cd "$SRC" && gh stack merge <top-pr-number> --yes)
```

**Merge by the top layer's PR number.** `gh stack link --help` states the
guarantee directly: *"Because stack and PR numbers never overlap, a numeric
first argument is treated as a stack only when it matches an existing stack."*
A PR number can therefore never collide with a stack number, so passing one is
safe. `<top-pr-number>` is the top layer's `number`, already in hand from §2's
discovery (the last entry in the bottom-to-top order) — the shortest path, and
the one this flow takes. (The stack number is obtainable too — §2's
`gh api repos/"$SLUG"/stacks` returns it — but nothing here needs it except
`/stack` §6's repair.)

`gh stack merge` is all-or-nothing: if any layer cannot merge, none do. When the
base branch uses a merge queue, the stack is added to the queue and lands when
the queue processes it — so this **replaces** `gh pr merge --auto` entirely; do
not call both.

For a single-PR session, `gh pr merge <number> -R "$SLUG" --auto` — again both
the number and `-R`. **No strategy flag** (`--squash`/`--merge`/`--rebase`): on a
merge-queue branch `gh pr merge --auto` takes none, and passing one is rejected.

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
- Branch already on `trunk()` → `jj rebase` is a no-op; continue.
- CI already green → skip fixing.
- Each layer already a single coherent commit (single-PR session: `trunk()..@`
  has one commit with a real message) → the squash fold is a no-op.
- PR description regen is a deterministic overwrite from the final state → safe to
  repeat (no append-drift).
- PR already `isDraft:false` (§2's discovery reports it) → skip `gh pr ready`.
- Auto-merge already enabled → skip `gh pr merge --auto`. Check it with **both**
  `-R` and the number: `gh pr view <number> -R "$SLUG" --json autoMergeRequest`.
  Without the number it exits `argument required when using the --repo flag`.
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
- **Calling `gh pr merge --auto` on a stack** — use
  `gh stack merge <top-pr-number> --yes`; calling both double-enqueues.
- **Merging a stack whose §6 link exited non-zero** — the shape is wrong on
  GitHub; `gh stack merge` would land a mid-stack PR carrying the layer below
  it. Repair via `/stack` §6 case B first (§6).
- **Judging `jj resolve --list` by its exit code** — the clean case exits 2 with
  `Error: No conflicts found at this revision`, which would record a `:blocker`
  on every successful rebase (§3).
- **Marking only the top PR ready** — every layer must be ready or the stack
  merge refuses.
- **Merging layer-by-layer with `gh pr merge`** — that abandons atomicity;
  `gh stack merge` lands the whole stack or none of it.
- **Running `gh stack` in the worktree** — it needs a git repository. Run it from
  `$SRC`. (§2's discovery calls, `gh api …/stacks` and `gh pr list`, need none
  and run in the worktree.)
- **Reading the stack with `gh stack view`** — it has no positional arguments, so
  it always resolves the current branch and always fails here; the run then
  concludes "no stack" and re-publishes existing PRs. Use §2's
  `gh api repos/"$SLUG"/stacks`, with `gh pr list` as the fallback.
- **Thinking `-R "$SLUG"` alone fixes bare `gh`** — it does not for any `gh pr`
  subcommand that resolves a PR (`view`/`ready`/`merge`/`edit`). Those need `-R`
  **and** an explicit PR number from §2's discovery, or they exit `argument
  required when using the --repo flag`. Only `gh pr create` and `gh pr list` work
  on `-R` alone.
- **Assuming `$SLUG`/`$SRC` carry between commands** — each Bash call is a fresh
  shell. Re-derive them in every block (§2).
- **Bare `jj git push` on a stack** — it also pushes the session bookmark when
  that bookmark is tracked. Scope it: `-b 'glob:<session>--*'` (§6).
