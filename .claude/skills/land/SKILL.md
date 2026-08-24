---
name: land
description: Land the current session's stack — mark every layer ready for review, answer the Codex review and the PR checks that readiness triggers (fixing what it can, declining what it can defensibly decline), merge the stack atomically and watch it until it lands. No ledger events. Run from a session worktree. Usage: /land
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
the first time. This skill marks ready, answers what comes back, merges, and
watches the result land.

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
PR's base, so this call cannot rewire one). **Do not proceed to `gh stack merge`
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

### 8. Merge — enqueue, and watch it land

Reached only with §3–§7 settled: every layer's Codex verdict answered, and no
red checks left. Merge:

```bash
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
(cd "$SRC" && gh stack merge <top-pr-number> --yes --rebase)
```

**Merge by the top layer's PR number.** `gh stack link --help` states the
guarantee directly: *"Because stack and PR numbers never overlap, a numeric
first argument is treated as a stack only when it matches an existing stack."*
A PR number can therefore never collide with a stack number, so passing one is
safe. `<top-pr-number>` is the top layer's `number`, already in hand from §1
(the last entry in the bottom-to-top order) — the shortest path, and
the one this flow takes. (The stack number is obtainable too — §1's
`gh api repos/"$SLUG"/stacks` returns it — but nothing here needs it except
`/stack` §6's repair.)

`gh stack merge` is all-or-nothing: if any layer cannot merge, none do —
verified against a real two-layer merge, both landed one second apart from a
single invocation, base branch history clean and linear afterward.

**Always pass `--rebase`.** With no method flag, `--yes` uses *"your
last-used merge method"* (`gh stack merge --help`) — mutable, per-machine
state, not a guarantee. Observed: an unflagged call merged *"via rebase"*
only because that was this machine's last-used method. `--rebase` is what a
stack wants — `/squash` leaves one commit per layer, and rebase lands exactly
one commit per layer on trunk — but a stray `--squash` on any machine would
collapse a later stack's layers into a single commit and destroy the
layering. Pin it rather than rely on machine state.

Per `gh stack merge --help`, when the base branch uses a merge queue, the
stack is added to the queue and lands when the queue processes it; otherwise
it is merged directly. **This is vendor-documented, not independently
verified** — `YabMas/nido` has no rulesets or branch protection, so no merge
queue exists here to exercise; only the direct-merge path has been observed.
Either way this **replaces** `gh pr merge --auto` entirely; do not call both.

For a single-PR session, `gh pr merge <number> -R "$SLUG" --auto` — again both
the number and `-R`. **No strategy flag** (`--squash`/`--merge`/`--rebase`): on a
merge-queue branch `gh pr merge --auto` takes none, and passing one is rejected.

#### Watch it to completion

`gh stack merge` and `gh pr merge --auto` both *enqueue* — neither waits. Poll
until it lands:

```bash
gh pr view <top-pr-number> -R "$SLUG" --json state,mergedAt,mergeStateStatus \
  --jq '"\(.state) merged=\(.mergedAt // "-") \(.mergeStateStatus)"'
```

- **`MERGED`** → done. For a stack the top layer's state is the whole stack's:
  `gh stack merge` is all-or-nothing (above).
- **`OPEN`, with a `removed_from_merge_queue` event and no merge** → the queue
  **kicked it out**. Its own CI run failed against the merged result — something
  the PR's own checks could not have caught, since they never tested that
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
- Stack already merged or queued → `gh stack merge` reports it; no second merge.
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
- **Marking only the top PR ready** — every layer must be ready or the stack
  merge refuses: `✗ pull request #14 cannot be merged yet: #13 below it is a
  draft`, exit 5. A clean halt, not damage — but a halt (§2).
- **Reading `removed_from_merge_queue` as failure** — it fires on success too;
  what distinguishes them is whether a merge landed beside it (§8).
- **Watching the merge queue indefinitely** — under `nido ship` this runs on a
  cap-1 merge lane, so it blocks every other branch. Budget the watch and hand
  off to the `github-merge` poller (§8).
- **Omitting `--base "$TRUNK"` on the `gh stack link --open` re-link** —
  force-resets the bottom PR's base to the repo default branch unless given
  explicitly; observed doing this unasked against a non-trunk base (§2).
- **Omitting `--rebase` on `gh stack merge`** — the method otherwise falls back
  to whatever was last used on this machine; a stray `--squash` there would
  collapse the stack's layers into one commit (§8).
- **Merging layer-by-layer with `gh pr merge`** — that abandons atomicity;
  `gh stack merge` lands the whole stack or none of it (§8).
- **Calling `gh pr merge --auto` on a stack** — use
  `gh stack merge <top-pr-number> --yes`; calling both double-enqueues (§8).
- **Bare `jj git push` on a stack** — it also pushes the session bookmark when
  that bookmark is tracked. Scope it: `-b 'glob:<session>--*'` (§2).
- **Merging a stack whose §2 link exited non-zero** — the shape is wrong on
  GitHub; `gh stack merge` would land a mid-stack PR carrying the layer below it.
  Repair via `/stack` §6 case B first (§2).
- **Running `gh stack` in the worktree** — it needs a git repository. Run it from
  `$SRC`. (`gh api …/stacks` and `gh pr list` need none and run here.)
- **Thinking `-R "$SLUG"` alone fixes bare `gh`** — every `gh pr` subcommand that
  resolves a PR (`view`/`ready`/`merge`/`edit`) needs `-R` **and** an explicit
  number from §1, or it exits `argument required when using the --repo flag`.
- **Assuming `$SLUG`/`$SRC` carry between commands** — each Bash call is a fresh
  shell. Re-derive them in every block (§1).
