---
name: land
description: Land the current session's stack — mark every layer ready for review, answer the Codex review and the PR checks that readiness triggers (fixing what it can, declining what it can defensibly decline), collapse the stack into its top PR and merge that one PR, watching it until it lands. No ledger events. Run from a session worktree. Usage: /land
---

# /land

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/land/`
> and is injected into every spawned session's composed `.claude/skills/`.
> Sibling of `/align`, `/local-ci`, and `/squash` — the landing phase
> `/drive-home` composes, after the branch has been rebased, tested and folded.

## What this is

The branch's meeting with GitHub. Everything before this happened on your
machine: `/align` reconciled with trunk, `/local-ci` ran the Docker CI, `/squash`
shaped the commits. **Marking ready is what starts the review** — Codex reads the
diff, and the PR's own checks (e2e, integration shards, staging deploy) run for
the first time. This skill marks ready, answers what comes back, **collapses the reviewed
stack into a single PR**, merges that, and watches the result land. The
collapse is not tidiness: a merge queue merges its entries one at a time, so
a stack that enters it as n pull requests lands in pieces the moment anything
fails mid-arc (§8).

It is **autonomous within a safe boundary**: it fixes what it can, declines what
it can defensibly decline, and halts on what needs a human. It writes **no ledger
events** — it reports, and `/drive-home` records.

## When to use

- Composed by `/drive-home` as its landing phase (the usual path).
- Standalone, on a stack that is already rebased, green and squashed, when you
  want it reviewed and landed without re-running the earlier phases.

Do **not** reach for it to publish a stack — that's `/prepare-draft-pr`. This
skill expects the PRs to exist.

## Where to run it

All work happens **inside the worktree**. From a session home
(`…/.nido/sessions/<project>/<session>/`), `cd worktree` first.

```bash
jj root   # errors if not a jj worktree → stop, and run from the worktree
```

## Flow

### 1. Find the stack

Derive the repo slug and the source repo, then read the stack. Both values are
needed throughout, and **shell variables do not survive between commands** —
each Bash call is a fresh shell, so re-derive them in every block that uses them:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
```

`<session>` is everything after `/sessions/<project>/` in the session-home path,
and it may contain slashes (`fix/add-delay`). Then ask the **stacks endpoint**
first — it returns the PRs already ordered, bottom to top:

```bash
gh api repos/"$SLUG"/stacks \
  --jq '.[] | select(.open) | select(any(.pull_requests[]; .head.ref | startswith("<session>--")))
            | "stack #\(.number) base=\(.base.ref) prs=\([.pull_requests[].number])"'
```

**Keep both filters.** `startswith("<session>--")` — the endpoint returns every
stack in the repo, and this repo runs a dozen sessions at once; unfiltered, this
would land another session's stack. `select(.open)` — a merged stack stays listed
forever with `open:false`, and there is no way to delete the record.

Empty means no stack object, which is also how a single-PR session looks. Fall
back to `gh pr list`, which sees both:

```bash
gh pr list -R "$SLUG" --state open --limit 50 \
  --json number,url,headRefName,baseRefName,isDraft,mergeStateStatus \
  --jq '.[] | select(.headRefName == "<session>" or (.headRefName | startswith("<session>--")))'
```

Keep every layer's `number` and `url` in bottom-to-top order — §2 and §8 need
them and nothing else produces them. **Both empty** ⇒ the branch was never
published: stop and run `/prepare-draft-pr`, which also wires the correlation
links the merge poller needs. Never hand-roll `gh pr create` here.

**Not `gh stack view`.** It takes no positional arguments, so it always resolves
the *current branch*, which a jj-colocated repo does not have — it fails
unconditionally with `✗ failed to get current branch`. The two primitives above
need no current branch and run fine in the worktree. (Full treatment of the
discovery primitives: `/stack` §4.)

### 2. Push, and mark every layer ready

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
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
(cd "$SRC" && gh stack link --base "$TRUNK" <session>--<l1> <session>--<l2> <session>--<l3> --open 2>&1); echo "EXIT=$?"
```

`--open` flips new and existing PRs from draft to ready for review — verified
against both a newly-created and a pre-existing draft PR, each with an
explicit per-PR confirmation line. Pass **branch names** here, bottom to top,
listing every layer: that form also picks up any layer that still has no PR
(`/stack` §4).

**Always pass `--base "$TRUNK"`.** Every `gh stack link` call, this one
included, force-resets the bottom PR's base to the repository default branch
unless told otherwise — observed, on a PR deliberately created against a
non-trunk base: `✓ Updated base branch for PR #13 to main`, unasked. No-op for
nido today since the bottom layer's base already is `$TRUNK`; still pass it,
since the silent retarget lands on the branch this whole design exists to
protect (`/stack` §4).

**Redirect `2>&1` and check the exit code.** `gh stack link` prints everything to
**stderr** — stdout is empty — and exits **5** on a partial failure that it does
not roll back. A non-zero exit here means the stack shape on GitHub is wrong,
most often a layer inserted below the top during the work (GitHub locks a stacked
PR's base, so this call cannot rewire one). **Do not proceed to §8's collapse
on a non-zero exit** — merging a mis-shaped stack lands a mid-stack PR whose diff
swallows the layer below it. Report the stderr text and point at `/stack` §6 case
B: unstack, then one link. Then re-run `/land`.

For a single-PR session, `gh pr ready <number> -R "$SLUG"` — **both** the number
(from §1) and `-R`. `-R` alone is not enough: `gh pr ready -R <slug>`
exits `argument required when using the --repo flag`, and bare `gh pr ready`
cannot resolve a repo from this worktree.

Marking ready is what fires Codex (`chatgpt-codex-connector`) and starts
the PR's GitHub Actions checks. **Neither ran during `/local-ci`** — that was the
local Docker CI, and the PR adds e2e, integration shards and a staging deploy on
top of it. This is where the branch first meets everything that will actually
gate it.

Do this for **every layer**. A stack of three gets three Codex reviews and three
check rollups, and a finding on layer 2 is layer 2's to fix (§5).

### 3. Wait for Codex — it has two ways of answering

**A clean review leaves no review.** Codex's own about-box says so: *"If Codex
has suggestions, it will comment; otherwise it will react with 👍."* Two terminal
signals, then — and polling for only the first hangs forever on exactly the PRs
that had nothing wrong with them:

| outcome | signal |
|---|---|
| findings | a review by `chatgpt-codex-connector` |
| clean | a `+1` reaction by `chatgpt-codex-connector[bot]` on the PR |
| not finished yet | neither |

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh api repos/"$SLUG"/issues/<n>/reactions \
  --jq '[.[] | select(.user.login=="chatgpt-codex-connector[bot]" and .content=="+1")] | length'
gh pr view <n> -R "$SLUG" --json reviews \
  --jq '[.reviews[] | select(.author.login=="chatgpt-codex-connector")] | length'
```

**Read the reaction through the REST endpoint, not `reactionGroups`.**
`gh pr view --json reactionGroups` reports `THUMBS_UP` without saying who left
it, so a human thumbs-up on the PR would read as a clean Codex verdict and send
the branch to the queue unreviewed. The REST endpoint carries `.user.login` —
filter on it, and keep the `[bot]` suffix: the reaction is left by
`chatgpt-codex-connector[bot]` while the review is authored by
`chatgpt-codex-connector`, and the two do not match each other.

**On round two and after, a `+1` needs a timestamp check.** The review body
stamps the commit it reviewed (§4); the reaction does not — it carries
`created_at` and nothing else. So once you have pushed a fix, the `+1` sitting on
the PR is the verdict on the code *before* that fix, and reading it as a clean
result would send an unreviewed commit to the queue. Compare `created_at` against
the push, and treat anything older as "not finished yet":

```bash
gh api repos/"$SLUG"/issues/<n>/reactions \
  --jq '.[] | select(.user.login=="chatgpt-codex-connector[bot]" and .content=="+1") | .created_at'
```

Poll on a slow cadence, a minute or more apart. Codex is not a required check —
nothing on GitHub is waiting for it, and nothing breaks if you are unhurried.

### 4. Read what it found

Findings arrive as **inline review comments**, each with a severity badge in its
body (`P1` orange, `P2` yellow):

```bash
gh api repos/"$SLUG"/pulls/<n>/comments \
  --jq '.[] | select(.user.login=="chatgpt-codex-connector[bot]")
      | "\(.id)\t\(.path):\(.line // .original_line)\t\((.body | capture("!\\[(?<p>P[0-9])").p) // "P?")"'
```

The review body stamps **which commit it reviewed** — a `**Reviewed commit:**`
line carrying the short sha. Compare it against the layer's head. A stamp that
does not match is a review of code you have already replaced; wait for the next
one
rather than fixing findings that no longer apply.

### 5. Attempt every finding — and land the fix in the layer that owns it

Same posture as `/local-ci`: attempt first, halt only on what genuinely resists.
But **three dispositions here, not two**, and the third is what separates a
reviewer from a test:

- **Fixed** — the finding is right. Fix it.
- **Declined** — the finding is wrong, or the code is deliberate. Reply on the
  thread saying why. A failing test cannot be mistaken; a P2 suggestion can be,
  and answering it *is* a response to the review rather than a dodge.
- **Unresolved** — it resisted, or the call is not yours to make.

**Decline only against something already on the record** — the layer's stated Out
of scope, a `:design` record, a comment that explains the choice. "I judged it
fine" is not a decline, it is a skipped finding. Where nothing is on the record
and the fix is cheap and safe, do the fix; that is much the cheaper mistake.

```bash
gh api repos/"$SLUG"/pulls/<n>/comments/<comment-id>/replies \
  -f body='<why this is deliberate, pointing at where that is recorded>'
```

**The fix belongs in the owning layer's commit.** `/squash` (§5) left exactly one
commit per layer, and that is the invariant this whole stack design protects. A
finding on layer 2 is fixed *in layer 2*:

```bash
jj new <layer-2-change-id>          # edit, then:
jj squash --into <layer-2-change-id>
jj git push -b 'glob:<session>--*'
```

Never append a fix commit on top of the stack. It lands the fix in the wrong PR,
puts two commits in one layer, and leaves the layer it was meant for still
carrying the finding.

### 6. The checks

A check concluding `FAILURE` or `TIMED_OUT` is a finding like any other:

```bash
gh pr view <n> -R "$SLUG" --json statusCheckRollup \
  --jq '[.statusCheckRollup[] | select(.conclusion=="FAILURE" or .conclusion=="TIMED_OUT")
       | {name: (.name//.context), url: (.detailsUrl//.targetUrl)}]'
```

Attempt them through `/local-ci`'s protocol — read the log, dispatch the owning
agent, verify narrowly, two attempts — with the log pulled from the run rather
than the local Docker CI: `gh run view <run-id> --log-failed`.

**Do not try to work out which checks are required.** `isRequired` comes back
null through `gh pr view` here, and branch protection is not readable without
admin rights. Auto-merge and the merge queue already know what gates the branch,
and §8 delegates to them. The question here is narrower: a red check is worth
fixing whether or not it gates anything.

### 7. Cap the rounds at three

Pushing a fix starts the cycle again — **observed: Codex re-reviewed a new head
commit with no `@codex review` comment**, 36 minutes after its first review,
stamping the new commit. (Its about-box lists only "open for review", "mark a
draft as ready" and a `@codex review` comment as triggers, so either that list is
incomplete or the human's inline reply re-triggered it; the two were not
isolated. Either way: wait for a review stamped with the new head, and if none
arrives, comment `@codex review` — that trigger is documented — and keep
waiting.)

**Three review rounds, then stop** and report what is left. An uncapped
fix↔review loop is this phase's characteristic failure: every round is cheap
enough to justify one more, and a reviewer can always find one more thing.

### 8. Collapse the stack to one PR, then merge it

Reached only with §3–§7 settled: every layer's Codex verdict answered, and no
red checks left.

**Do not enqueue the layers.** A stack that enters a merge queue as n pull
requests lands in pieces. The layers were a review decomposition; they have done
their work by now, and carrying them into the queue is what puts a half-arc on
`main`.

#### Why a queue cannot merge a stack as a unit

`gh stack merge` really is all-or-nothing — on the **direct-merge** path. Its own
help text says both halves in one page: *"a single, all-or-nothing operation: if
any PR cannot be merged, none are"*, and four paragraphs later *"If the base
branch uses a merge queue, the stack is added to the queue."* Those describe two
different operations. On a queue-protected branch the call **enqueues n
entries** and returns; the queue decides what merges, and it decides per entry.

brian's queue configuration, read 2026-08-25:

```bash
gh api graphql -f query='{repository(owner:"OWNER",name:"REPO"){
  mergeQueue(branch:"main"){configuration{
    mergeMethod mergingStrategy minimumEntriesToMerge maximumEntriesToBuild}}}}'
```

> `mergeMethod: SQUASH`, `mergingStrategy: ALLGREEN`,
> `minimumEntriesToMerge: 1`, `minimumEntriesToMergeWaitTime: 0`,
> `maximumEntriesToBuild: 5`

**`minimumEntriesToMerge: 1` is the whole story.** The queue merges an entry the
moment it is green, on its own, with no notion that six others belong with it.
Nothing in the configuration can express "these n go together" — the grouping
the stack means simply does not exist at this layer.

**Observed, and it is the designed behaviour rather than a fault.** A seven-layer
stack (brian PRs #4560–#4565 and #4604) enqueued at 13:50 on 2026-08-24.
Layers 1–3 merged at 14:03; layers 4–7 were evicted, unmerged, at 14:03:58. One
second after the eviction GitHub emitted `automatic_base_change_succeeded` on
layer 4, retargeting it onto `main` — **the queue rebasing the survivors of a
partial merge is a feature it ships**. `main` then carried three of seven layers
for 2h01m, auto-deploying to staging the whole time, until a human noticed and
re-enqueued the remainder by hand.

#### The collapse: retarget, never re-create

```bash
gh pr edit <top-pr-number> -R "$SLUG" --base main
```

That is the entire operation. **Retarget the existing top PR — do not open a new
one.** The top layer's branch already contains every layer beneath it, so moving
its base to `main` makes its diff the whole arc without touching a commit.

**The head SHA must not move, and that is what buys you the reviews you already
have.** Checks are keyed to the head commit, so every green check stays green
and nothing re-runs. Codex's triggers are "open for review", "mark a draft as
ready", a `@codex review` comment, and — observed (§7) — a new head commit. A
base retarget is none of them. So the layer reviews stand and no fresh review of
the collapsed diff is requested.

**This means never pushing after the collapse.** A push is a `synchronize`, and a
`synchronize` is a new head commit: it re-fires CI and re-fires Codex on the full
arc, which is exactly the re-review the collapse exists to avoid. If the arc
genuinely needs another commit, you are back in §5 — fix it in the layer that
owns it, let that layer be reviewed, and collapse afterwards.

**Nothing gates the collapsed PR on its own, and that is fine.** brian's
`pull_request.yml` carries `branches: [main]`, so only the bottom layer ever ran
PR CI at all — every layer above it was gated solely by the queue's own
`merge_group` build. The collapsed PR inherits that: its gate is the queue
building the merged result under `ALLGREEN`, which is the authoritative test of
what actually lands, and `/local-ci` has already run the same content locally
at the tip.

Confirm the collapse before merging — the diff should equal the arc:

```bash
gh pr diff <top-pr-number> -R "$SLUG" --name-only | sort > /tmp/pr-files
jj diff -r "main..<top-layer-rev>" --name-only | sort > /tmp/arc-files
diff /tmp/pr-files /tmp/arc-files && echo "collapse matches the arc"
```

#### Rewrite the top PR to describe the arc

The queue squashes, so **the collapsed PR's title and body become the one commit
that lands on `main`.** That commit is now the only trunk artifact the arc gets —
the `[1/7] … [7/7]` commits do not land, because only one entry enters the queue.

So the body carries the layer manifest, and the layers stay legible on trunk:

    ## Layers

    1. feat(analytics): add the RELEASED visibility helpers beside the ACTIVE ones (#4560)
    2. fix(analytics): divide learner progress by RELEASED content, not ACTIVE (#4561)
    ...

Title: the whole arc's one sentence, no "and", no `[n/m]` prefix. It is not a
layer any more.

#### Merge and watch — one PR

```bash
gh pr merge <top-pr-number> -R "$SLUG" --auto
```

**No strategy flag.** On a merge-queue branch `gh pr merge --auto` takes none and
rejects one that is passed; the queue's own `mergeMethod` decides, and pinning a
method here would be pinning a value that is ignored. **`gh stack merge` is not
used at all in this flow** — do not call both.

`--auto` enqueues; it does not wait. Poll until it lands:

```bash
gh pr view <top-pr-number> -R "$SLUG" --json state,mergedAt,mergeStateStatus \
  --jq '"\(.state) merged=\(.mergedAt // "-") \(.mergeStateStatus)"'
```

- **`MERGED`** → done. With one entry in the queue this reading is now the whole
  truth, which it was not while the stack was enqueued as n.
- **`OPEN`, with a `removed_from_merge_queue` event and no merge** → the queue
  **kicked it out**. Its build failed against the merged result — something the
  PR's own checks could not have caught, since they never tested that
  combination. Treat it exactly as §6, fix, and re-enqueue.

```bash
gh api repos/"$SLUG"/issues/<n>/timeline --paginate \
  --jq '[.[] | select(.event|test("merge_queue"))] | last | "\(.event) \(.created_at)"'
```

**`removed_from_merge_queue` fires on success too** — observed on a PR that
merged cleanly, `added_to_merge_queue` then `removed_from_merge_queue` fourteen
minutes later by `github-merge-queue[bot]`. It is the *absence of a merge beside
it* that means failure, never the event alone. Judging by the event would report
every successful merge as a queue rejection.

#### Close the lower layers — after it lands, never before

The lower PRs stay open through the merge. They are the rollback: if the queue
rejects the collapsed PR and the arc has to go back to being a stack, they are
still there. Only once the top PR reads `MERGED`:

```bash
gh pr close <layer-pr-number> -R "$SLUG" \
  --comment "Landed in #<top-pr-number>, which carries this layer's commits. Reviewed here."
```

They close **unmerged**, and that is the honest record — their commits reached
`main` inside the squash, not as themselves. The comment is what keeps each
layer's review reachable from the commit that landed it.

**Budget the watch, and hand off rather than hold on.** Poll every few minutes
and give the whole watch a ceiling — an hour is generous. Past it, stop watching
and report it as landing rather than landed (§9), because nido already has a
completion watcher: the
`github-merge` poller runs every 5 minutes, closes the workstream, appends its
terminal `:merged` event and nudges Notion, correlating on the `:github` external
ref that `/prepare-draft-pr` stamped. It needs nothing from this session.

That handoff is not a nicety. Run headless by `nido ship` — which drives
`/drive-home`, which composes this — the whole journey holds a
**cap-1 merge lane** — one branch at a time, repo-wide — under an 8h
SIGTERM→SIGKILL backstop sized for "a CI cycle or two", not for an open-ended
queue wait. A watch that never gives up turns a slow queue into a stalled merge
lane for every branch behind it.

### 9. Report

Emit this every time — landed, still landing, or halted. It is the whole output
of this skill, and the only thing `/drive-home` sees.

```
Land: <landed | landing | halted> · <n> findings · <f> fixed · <d> declined · <u> unresolved

PRs
- <owner/repo#n> <url>   (bottom to top)

Fixed
- <pr#> <path>:<line> · P<n> · <the finding in one line>
  fix: <what changed, and in which layer's commit>

Declined
- <pr#> <path>:<line> · P<n> · <the finding in one line>
  because: <what on the record says this is deliberate>
  replied: <yes — thread <comment-id>>

Unresolved
- <pr#> <path>:<line> · P<n> · <the finding in one line>
  attempted: <what was tried, and what came back>
  needs:     <the decision a human has to make>

Checks
- <check name> — <fixed how | still red>

Outcome: <merged at <sha> | on the queue, github-merge poller owns it | halted before merge>
```

**The declines are the part a human actually reads.** A decline is a judgement
made on someone else's behalf, and it is reviewable only if it is stated with
what it rests on. A run that fixed four findings and declined one is a different
thing from a run that sailed through clean, and only this report says which
happened.

This skill emits **no** coordinator ledger events. When `/drive-home` composes
it, `/drive-home` records the outcome — `:implementation-completed` or
`:blocker` — from this report.

## What this skill does NOT do

- **No ledger events.** It reports; `/drive-home` records. Two writers on one
  workstream is how `nido.coordinator.ship/classify-outcome` ends up reading the
  wrong fingerprint and parking a healthy branch as blocked.
- **No publishing.** It expects the PRs to exist; `/prepare-draft-pr` creates
  them and stamps the `:github` ref the merge poller correlates on.
- **No rebasing, no CI run, no commit reshaping.** Those are `/align`,
  `/local-ci` and `/squash`, and this skill assumes all three already passed.
- **No un-readying.** A halt leaves the layers ready and the threads open —
  that is what lets a human read the review on GitHub.

## Idempotency (safe to re-run)

- PR already `isDraft:false` → skip `gh pr ready` / the `--open` re-link.
- Stack already linked → `gh stack link` updates rather than duplicating.
- Codex already answered for the layer's current head — a review stamped with
  that commit, or a `+1` left after the last push — → don't wait again, and
  don't re-request (§3).
- A finding already replied to or already fixed → skip it. Thread replies are
  additive, so a second pass posts the same reasoning twice (§5).
- Top PR's base already `main` → the collapse already happened; skip it and go
  straight to the merge (§8).
- Top PR already merged or queued → `gh pr merge --auto` reports it; no second
  merge.
- Already merged → §8's watch returns immediately; still emit the report.

## Common mistakes

- **Waiting for a Codex *review* on a clean PR** — a clean verdict is a `+1`
  reaction and no review at all, so this waits forever on the PRs that were
  fine. Check both signals (§3).
- **Reading the reaction from `reactionGroups`** — it does not say who reacted,
  so a human 👍 passes as Codex's verdict. Use the REST reactions endpoint and
  filter on `chatgpt-codex-connector[bot]` (§3).
- **Trusting a `+1` left before your last push** — reactions carry no commit,
  only `created_at`, so a stale one reads as a clean verdict on code Codex never
  saw (§3).
- **Acting on a review whose `Reviewed commit` is not the layer's head** — those
  findings were raised against code you have already replaced (§4).
- **Fixing a finding on top of the stack** — it lands in the wrong PR and breaks
  one-commit-per-layer. Squash it into the owning layer (§5).
- **Silently skipping a finding you disagree with** — decline it *on the thread*,
  against something already on the record, or fix it (§5).
- **Marking only the top PR ready** — readiness is what fires Codex, so a layer
  left as a draft is a layer nobody reviews. The collapse then carries it to
  trunk unread, and unlike the old stack merge nothing refuses (§2).
- **Reading `removed_from_merge_queue` as failure** — it fires on success too;
  what distinguishes them is whether a merge landed beside it (§8).
- **Watching the merge queue indefinitely** — under `nido ship` this runs on a
  cap-1 merge lane, so it blocks every other branch. Budget the watch and hand
  off to the `github-merge` poller (§8).
- **Omitting `--base "$TRUNK"` on the `gh stack link --open` re-link** —
  force-resets the bottom PR's base to the repo default branch unless given
  explicitly; observed doing this unasked against a non-trunk base (§2).
- **Enqueueing the layers instead of collapsing them** — this is the failure this
  section exists to prevent. A queue merges its entries one at a time, so any
  failure mid-arc lands the layers below it and evicts the rest, leaving a
  half-arc on an auto-deploying trunk (§8).
- **Reading `gh stack merge`'s "all-or-nothing" as covering a queued merge** — it
  covers the direct-merge path only. On a queue-protected branch the call
  enqueues n entries and the queue decides per entry (§8).
- **Pushing after the collapse** — a `synchronize` is a new head commit, which
  re-fires CI and re-fires Codex on the full arc. The frozen head SHA is the
  whole reason the layer reviews still stand (§8).
- **Passing a method flag to `gh pr merge --auto`** — a merge-queue branch
  rejects one, and the queue's own `mergeMethod` decides anyway (§8).
- **Closing the lower PRs before the top one lands** — they are the rollback if
  the queue rejects the collapsed PR (§8).
- **Bare `jj git push` on a stack** — it also pushes the session bookmark when
  that bookmark is tracked. Scope it: `-b 'glob:<session>--*'` (§2).
- **Collapsing a stack whose §2 link exited non-zero** — the shape is wrong on
  GitHub, so the top PR may not contain every layer. Repair via `/stack` §6
  case B first (§2).
- **Running `gh stack` in the worktree** — it needs a git repository. Run it from
  `$SRC`. (`gh api …/stacks` and `gh pr list` need none and run here.)
- **Thinking `-R "$SLUG"` alone fixes bare `gh`** — every `gh pr` subcommand that
  resolves a PR (`view`/`ready`/`merge`/`edit`) needs `-R` **and** an explicit
  number from §1, or it exits `argument required when using the --repo flag`.
- **Assuming `$SLUG`/`$SRC` carry between commands** — each Bash call is a fresh
  shell. Re-derive them in every block (§1).
