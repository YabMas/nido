---
name: land
description: Land the current session's stack — mark every layer ready for review, answer the PR checks that readiness triggers and any review thread a human left (fixing what it can, declining what it can defensibly decline), collapse the stack into its top PR and merge that one PR, watching it until it lands. No ledger events. Run from a session worktree. Usage: /land
---

# /land

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/land/`
> and is injected into every spawned session's composed `.claude/skills/`.
> Sibling of `/align`, `/local-ci`, and `/squash` — the landing phase
> `/drive-home` composes, after the branch has been rebased, tested and folded.

## What this is

The branch's meeting with GitHub. Everything before this happened on your
machine: `/align` reconciled with trunk, `/local-ci` ran the Docker CI, `/squash`
shaped the commits, and the **review rounds** (`bb nido:review:loop`) already
judged the diff — the code arrives here reviewed. **Marking ready is what starts
the PR's own checks** (e2e, integration shards, staging deploy), which run for
the first time. This skill marks ready, answers what comes back, **collapses the
reviewed stack into a single PR**, merges that, and watches the result land. The
collapse is not tidiness: a merge queue merges its entries one at a time, so
a stack that enters it as n pull requests lands in pieces the moment anything
fails mid-arc (§6).

**The GitHub-side review is not this skill's business.** Codex reviews nido's
work in the review rounds, against the branch diff, before anything is marked
ready — so `/land` waits on no reviewer and parses no bot. A thread on a PR here
is a person's, and §3 says what to do with one.

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

Keep every layer's `number` and `url` in bottom-to-top order — §2 and §6 need
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
TRUNK=$(gh repo view "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
jj git export   # sync $SRC's git refs with the bookmarks jj pushed — see below
(cd "$SRC" && gh stack link --base "$TRUNK" <session>--<l1> <session>--<l2> <session>--<l3> --open 2>&1); echo "EXIT=$?"
```

**`jj git export` is not optional before this call**, for the reason `/squash`
§2 sets out: `gh stack link` pushes the branches itself from `$SRC`, whose git
refs a non-colocated worktree's `jj git push` never updated, so it pushes
pre-rebase commits and the remote rejects all of them non-fast-forward. Nothing
is marked ready when that happens — the link aborts before it reaches the PRs,
so this reads as "readiness silently did nothing" rather than as a push error.

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
PR's base, so this call cannot rewire one). **Do not proceed to §6's collapse
on a non-zero exit** — merging a mis-shaped stack lands a mid-stack PR whose diff
swallows the layer below it. Report the stderr text and point at `/stack` §6 case
B: unstack, then one link. Then re-run `/land`.

For a single-PR session, `gh pr ready <number> -R "$SLUG"` — **both** the number
(from §1) and `-R`. `-R` alone is not enough: `gh pr ready -R <slug>`
exits `argument required when using the --repo flag`, and bare `gh pr ready`
cannot resolve a repo from this worktree.

Marking ready is what starts the PR's GitHub Actions checks. **They did not run
during `/local-ci`** — that was the local Docker CI, and the PR adds e2e,
integration shards and a staging deploy on top of it. This is where the branch
first meets everything that will actually gate it.

Do this for **every layer**. A stack of three gets three check rollups, and a
failure on layer 2 is layer 2's to fix (§4). The top PR has to be out of draft
for the queue to take it at all (§6); the layers beneath are marked ready so each
stands as a readable record beside the commit that lands it.

### 3. Wait for the checks

Readiness started them, and they are what this phase waits on. Poll the rollup
on a slow cadence — a minute or more apart — until nothing is still running:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh pr view <n> -R "$SLUG" --json statusCheckRollup \
  --jq '[.statusCheckRollup[]
        | select((.status // "COMPLETED") != "COMPLETED" or (.state // "") == "PENDING")
        | (.name // .context)]'
```

**The two defaults in that filter are load-bearing.** The rollup mixes two node
types: a `CheckRun` carries `status`/`conclusion`/`name`, a `StatusContext`
carries `state`/`context` and no `status` at all. Without `// "COMPLETED"` every
status context reads as forever-pending and the poll never terminates; without
`// ""` every check run does, against the `PENDING` test.

Then read what failed:

```bash
gh pr view <n> -R "$SLUG" --json statusCheckRollup \
  --jq '[.statusCheckRollup[] | select(.conclusion=="FAILURE" or .conclusion=="TIMED_OUT")
       | {name: (.name//.context), url: (.detailsUrl//.targetUrl)}]'
```

A check concluding `FAILURE` or `TIMED_OUT` is a finding like any other. Attempt
them through `/local-ci`'s protocol — read the log, dispatch the owning agent,
verify narrowly, two attempts — with the log pulled from the run rather than the
local Docker CI: `gh run view <run-id> --log-failed`.

**Do not try to work out which checks are required.** `isRequired` comes back
null through `gh pr view` here, and branch protection is not readable without
admin rights. Auto-merge and the merge queue already know what gates the branch,
and §6 delegates to them. The question here is narrower: a red check is worth
fixing whether or not it gates anything.

**Read any review thread on the PR too — and do not wait for one.** The review
rounds judged this diff before it was ever pushed, so no reviewer is on its way;
a thread here was left by a person, at whatever moment they looked, and none at
all is the normal case. But §6's merge gate requires every thread on the
collapsed PR resolved, so a thread that does exist is a finding carrying §4's
dispositions:

```bash
gh api repos/"$SLUG"/pulls/<n>/comments \
  --jq '.[] | "\(.id)\t\(.user.login)\t\(.path):\(.line // .original_line)"'
```

### 4. Attempt every finding — and land the fix in the layer that owns it

Same posture as `/local-ci`: attempt first, halt only on what genuinely resists.
But **three dispositions here, not two**, and the third exists because a person
can be answered where a test cannot:

- **Fixed** — the finding is right. Fix it.
- **Declined** — open to a *review thread* only: the comment is wrong, or the
  code is deliberate. Reply on the thread saying why. A failing check cannot be
  mistaken and has no thread to answer on; a person's suggestion can be, and
  answering it *is* a response to the review rather than a dodge.
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
jj squash -u --into <layer-2-change-id>
jj git push -b 'glob:<session>--*'
```

**`-u` is required here for the reason `/squash` §1 gives.** Folding a described
commit into another described commit makes plain `jj squash` open `$EDITOR` to
merge the two messages. Run headless there is nobody to answer it and the
command hangs indefinitely — observed hanging 14 minutes on a `nvim` holding a
`.jjdescription` tempfile, with no output and no failure. `-u` keeps the
destination's message, which is what a fix folded into its layer wants anyway.
Belt and braces for an unattended run: `JJ_EDITOR=true jj squash -u --into …`.

Never append a fix commit on top of the stack. It lands the fix in the wrong PR,
puts two commits in one layer, and leaves the layer it was meant for still
carrying the finding.

### 5. Cap the rounds at three

Pushing a fix is a `synchronize`, and a `synchronize` re-fires the PR's checks on
the new head — so every fix buys another wait and another rollup to read.

**Three rounds, then stop** and report what is left. An uncapped fix↔check loop
is this phase's characteristic failure: every round is cheap enough to justify
one more, and a flaky shard can always find one more thing.

### 6. Collapse the stack to one PR, then merge it

Reached only with §3–§5 settled: no red checks left, and every review thread
answered.

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

#### The collapse: unstack, then retarget — never re-create

```bash
(cd "$SRC" && gh stack unstack <stack-number>)     # the number from §1
gh pr edit <top-pr-number> -R "$SLUG" --base main
```

**The retarget alone is refused while the stack record exists:**

    GraphQL: Cannot change the base branch because the pull request is part
    of a stack. (updatePullRequest)

GitHub locks a stacked PR's base — the same lock that makes `gh stack link` the
only way to shape a stack (§2). `gh stack unstack` takes the stack NUMBER from
§1, goes through the API so it works from anywhere in the repository, and
removes the stack record **without touching the pull requests**: every one stays
open on the base it had, with its reviews, its threads and its checks intact.
Run it from `$SRC` like every other `gh stack` call.

**Unstack before the merge, never after.** GitHub refuses to unstack a PR that
is queued or has auto-merge enabled, and when any PR resists the whole stack is
kept — so an unstack attempted after enqueueing can leave the record standing
with no way to clear it.

**Retarget the existing top PR — do not open a new
one.** The top layer's branch already contains every layer beneath it, so moving
its base to `main` makes its diff the whole arc without touching a commit.

**The head SHA must not move, and that is what buys you the checks you already
have.** Checks are keyed to the head commit, so every green check stays green
and nothing re-runs. A base retarget does not touch the head, so it fires
nothing.

**This means never pushing after the collapse.** A push is a `synchronize`, and a
`synchronize` is a new head commit: it re-fires CI on the full arc, which is
exactly the re-run the collapse exists to avoid. If the arc genuinely needs
another commit, you are back in §4 — fix it in the layer that owns it, and
collapse afterwards.

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

**And the body ends with the delivery claim.** This is the one PR body in the
flow that no commit generates, so nothing carries the claim here for you — and
it is the only body that matters to a merge-reading automation, because it is
the only PR of the stack that merges. Take the line from the TOP layer's commit
footer verbatim:

    Closes BR-4312

Left off, brian's `bb notify:deploy` finds no claim on the PR the merge maps to,
and the ticket sits where it was while the work is on `main` — the exact silent
failure the claim exists to prevent. Take `Refs` layers' citations *out*: with
the layers gone, several `Refs BR-####` lines describe PRs that no longer exist,
and a second `Closes` would claim a ticket this arc does not deliver. See
`/prepare-draft-pr` §"The delivery claim" for when the verb is legal at all —
a stack for a follow-up `FU-#`, or in a project with no `:delivery-claim`
config, carries no claim line here either.

#### Merge and watch — one PR

```bash
gh pr merge <top-pr-number> -R "$SLUG" --auto
```

**No strategy flag.** On a merge-queue branch `gh pr merge --auto` takes none and
rejects one that is passed; the queue's own `mergeMethod` decides, and pinning a
method here would be pinning a value that is ignored. **`gh stack merge` is not
used at all in this flow** — do not call both.

**A repository can run a merge queue and still disable auto-merge, and then this
call cannot reach the queue at all.** `--auto` is `enablePullRequestAutoMerge`
under the skin, so such a repo answers:

    GraphQL: Auto merge is not allowed for this repository
    (enablePullRequestAutoMerge)

and leaves the PR sitting at `mergeStateStatus: BLOCKED` beside a `mergeable:
MERGEABLE` — a pair that reads like a failing gate and is really just an
un-enqueued PR. Observed on brian, whose `main` carries the queue configuration
quoted above with auto-merge off. The queue's own entry point takes no such
setting:

```bash
PRID=$(gh pr view <top-pr-number> -R "$SLUG" --json id --jq '.id')
gh api graphql -f query='mutation($id:ID!){enqueuePullRequest(input:{pullRequestId:$id}){mergeQueueEntry{position state}}}' -f id="$PRID"
```

Success returns the entry — `{"position":1,"state":"QUEUED"}` — and from there
the watch below is unchanged.

**Its refusals are the branch protection you are otherwise told not to guess
at.** §3 says not to work out which checks are required, because `isRequired`
comes back null and protection needs admin rights. This mutation answers that
question by refusing, in one line, where `--auto` only ever says `BLOCKED`:

    UNPROCESSABLE ... Pull request All comments must be resolved.

Read the refusal, satisfy it, enqueue again.

**That particular one is a review gate, so satisfy it as a reviewer would.** Every
thread on the collapsed PR must be resolved. List them, reply on each with what
changed, then resolve:

```bash
gh api graphql -f query='{repository(owner:"OWNER",name:"REPO"){pullRequest(number:N){
  reviewThreads(first:50){nodes{id isResolved comments(first:1){nodes{author{login} path line}}}}}}}'
gh api graphql -f query='mutation($id:ID!){resolveReviewThread(input:{threadId:$id}){thread{isResolved}}}' -f id="<thread-id>"
```

**Resolving is a claim that the thread was answered, and it hides the thread from
the reviewer's default view.** Resolve what §4 recorded as Fixed. A finding you
DECLINED is answered by the reply that carries its reasoning, and resolving it
buries that reasoning behind a fold — so when a decline is what stands between
the arc and the queue, resolve it only with the reply already posted, and say in
§7's report which threads were declined-then-resolved. Never resolve a thread you
neither fixed nor answered: that is not satisfying the gate, it is removing it.

Only the collapsed PR's threads gate the merge. The lower layers' threads do not
— those PRs close unmerged (below) — so leave them as the review left them.

Enqueueing does not wait. Poll until it lands — and poll the QUEUE ENTRY beside
the merge state, because the PR's own fields cannot tell you it was evicted:

```bash
gh pr view <top-pr-number> -R "$SLUG" --json state,mergedAt \
  --jq '"\(.state) merged=\(.mergedAt // "-")"'
gh api graphql -f query='{repository(owner:"OWNER",name:"REPO"){pullRequest(number:N){
  mergeQueueEntry{state position}}}}' \
  --jq '.data.repository.pullRequest.mergeQueueEntry | if . == null then "no-entry" else "\(.state)@\(.position)" end'
```

- **`MERGED`** → done. With one entry in the queue this reading is now the whole
  truth, which it was not while the stack was enqueued as n.
- **an entry** (`AWAITING_CHECKS@1`, `QUEUED@2`, …) → still in the queue; keep
  waiting.
- **`no-entry` and not merged** → the queue **kicked it out**. Its build failed
  against the merged result — something the PR's own checks could not have
  caught, since they never tested that combination. Treat it exactly as §3, fix,
  and re-enqueue.

**Watching `state`/`mergeStateStatus` alone cannot see an eviction, and this is
the trap.** Neither field moves when the queue drops a PR: it reads `OPEN` and
`CLEAN` before it is enqueued, while it sits in the queue, and again after it is
evicted — the same pair in all three states. Measured on brian PR #4807: evicted
at 12:06:10Z, and a watch polling those two fields still printed `OPEN merged=-
CLEAN` every minute until its 45-minute budget ran out at 12:41, reporting a
timeout where it should have reported a failed build 35 minutes earlier. The
`mergeQueueEntry` going null is the signal; a timeline read (below) confirms it
after the fact but is not what a poll loop should key on.

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
  --comment "Landed in #<top-pr-number>, which carries this layer's commits."
```

They close **unmerged**, and that is the honest record — their commits reached
`main` inside the squash, not as themselves. The comment is what keeps each
layer — its diff, its checks, and whatever was said on it — reachable from the
commit that landed it.

**Budget the watch, and hand off rather than hold on.** Poll every few minutes
and give the whole watch a ceiling — an hour is generous. Past it, stop watching
and report it as landing rather than landed (§7), because nido already has a
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

### 7. Report

Emit this every time — landed, still landing, or halted. It is the whole output
of this skill, and the only thing `/drive-home` sees.

```
Land: <landed | landing | halted> · <n> findings · <f> fixed · <d> declined · <u> unresolved

PRs
- <owner/repo#n> <url>   (bottom to top)

Fixed
- <pr#> <check name | path:line> · <the finding in one line>
  fix: <what changed, and in which layer's commit>

Declined
- <pr#> <path>:<line> · <the thread in one line>
  because: <what on the record says this is deliberate>
  replied: <yes — thread <comment-id>>

Unresolved
- <pr#> <check name | path:line> · <the finding in one line>
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
  workstream is how `nido.coordinator.lane.ship/classify-outcome` ends up reading the
  wrong fingerprint and parking a healthy branch as blocked.
- **No publishing.** It expects the PRs to exist; `/prepare-draft-pr` creates
  them and stamps the `:github` ref the merge poller correlates on.
- **No rebasing, no CI run, no commit reshaping.** Those are `/align`,
  `/local-ci` and `/squash`, and this skill assumes all three already passed.
- **No GitHub-side code review.** The review rounds (`bb nido:review:loop`) judge
  the diff before this runs, so `/land` waits on no reviewer and parses no bot.
  It answers the PR's checks, and whatever thread a person happened to leave.
- **No un-readying.** A halt leaves the layers ready and the threads open —
  that is what lets a human read the review on GitHub.

## Idempotency (safe to re-run)

- PR already `isDraft:false` → skip `gh pr ready` / the `--open` re-link.
- Stack already linked → `gh stack link` updates rather than duplicating.
- Every check already green for the layer's current head → nothing to wait for;
  a re-run reads the same rollup and moves on (§3).
- A finding already replied to or already fixed → skip it. Thread replies are
  additive, so a second pass posts the same reasoning twice (§4).
- Stack record already removed (a previous run reached §6 and halted after) →
  §1's stacks endpoint returns empty, discovery falls through to `gh pr list`,
  and the layers are still found. Do not read that as "never published".
- Top PR's base already `main` → the collapse already happened; skip it and go
  straight to the merge (§6).
- Top PR already queued → `enqueuePullRequest` answers that it is already in the
  queue; no second entry, and the watch below is what you want anyway.
- Top PR already merged or queued → `gh pr merge --auto` reports it; no second
  merge.
- Already merged → §6's watch returns immediately; still emit the report.

## Common mistakes

- **Waiting for a review on the PR** — the review rounds already judged this
  diff, and nothing on GitHub is coming. What this phase waits on is the check
  rollup; a thread, if one exists, is a person's and is read, not awaited (§3).
- **Polling the rollup without the `//` defaults** — a `StatusContext` has no
  `status` field and a `CheckRun` has no `state`, so an unguarded filter reads
  one kind as forever-pending and never terminates (§3).
- **Fixing a finding on top of the stack** — it lands in the wrong PR and breaks
  one-commit-per-layer. Squash it into the owning layer (§4).
- **Silently skipping a finding you disagree with** — decline it *on the thread*,
  against something already on the record, or fix it (§4).
- **Marking only the top PR ready** — readiness is what starts a layer's checks,
  and the top PR has to be out of draft for the queue to take it at all. The
  collapse then carries an unchecked layer to trunk, and unlike the old stack
  merge nothing refuses (§2).
- **Reading `removed_from_merge_queue` as failure** — it fires on success too;
  what distinguishes them is whether a merge landed beside it (§6).
- **Watching only `state`/`mergeStateStatus` for the merge** — an evicted PR
  reads `OPEN`/`CLEAN`, which is also what it reads while queued and before it
  was ever enqueued. The watch has to poll `mergeQueueEntry`; without it an
  eviction is invisible until the budget runs out and gets reported as a
  timeout (§6).
- **Watching the merge queue indefinitely** — under `nido ship` this runs on a
  cap-1 merge lane, so it blocks every other branch. Budget the watch and hand
  off to the `github-merge` poller (§6).
- **Omitting `--base "$TRUNK"` on the `gh stack link --open` re-link** —
  force-resets the bottom PR's base to the repo default branch unless given
  explicitly; observed doing this unasked against a non-trunk base (§2).
- **Retargeting the top PR while the stack record still stands** — GitHub locks
  a stacked PR's base and refuses `gh pr edit --base`. `gh stack unstack <n>`
  first, and before the merge, never after (§6).
- **Reading `gh pr merge --auto`'s refusal as a gate** — a repo can run a merge
  queue and disable auto-merge, and then `--auto` cannot reach the queue at all.
  `BLOCKED` beside `MERGEABLE` is an un-enqueued PR, not a failing check; use
  the `enqueuePullRequest` mutation, whose refusals name the protection (§6).
- **Resolving a review thread you neither fixed nor answered** — resolving
  claims the thread was addressed and folds it out of sight. It satisfies the
  "all comments resolved" gate by removing the review, not by passing it (§6).
- **`jj squash --into` without `-u`** — folding two described commits opens an
  editor and hangs headless, silently (§4).
- **Calling `gh stack link` without `jj git export`** — it pushes `$SRC`'s stale
  refs, the remote rejects them non-fast-forward, and the link aborts before
  marking anything ready (§2).
- **Enqueueing the layers instead of collapsing them** — this is the failure this
  section exists to prevent. A queue merges its entries one at a time, so any
  failure mid-arc lands the layers below it and evicts the rest, leaving a
  half-arc on an auto-deploying trunk (§6).
- **Reading `gh stack merge`'s "all-or-nothing" as covering a queued merge** — it
  covers the direct-merge path only. On a queue-protected branch the call
  enqueues n entries and the queue decides per entry (§6).
- **Collapsing without carrying the delivery claim onto the top PR** — its body
  is authored, not generated, so the claim is lost unless you write it. The PR
  merges, the ticket never moves, and nothing says so (§"Rewrite the top PR").
- **Pushing after the collapse** — a `synchronize` is a new head commit, which
  re-fires CI on the full arc. The frozen head SHA is the whole reason the green
  checks still stand (§6).
- **Passing a method flag to `gh pr merge --auto`** — a merge-queue branch
  rejects one, and the queue's own `mergeMethod` decides anyway (§6).
- **Closing the lower PRs before the top one lands** — they are the rollback if
  the queue rejects the collapsed PR (§6).
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
