---
name: drive-home
description: Take the current nido session's stack home by composing /align (rebase + trivial-conflict resolution), /local-ci (CI + autonomous fix of every failure it can settle), and /squash (fold each layer to one commit + regenerate every PR title/description), then mark every layer ready, answer Codex's review and the PR checks it triggers, merge the stack atomically and watch it land. Halts for human judgement on semantic conflicts, on CI findings that resisted /local-ci's attempts, and on review findings it could neither fix nor defensibly decline. Usage: /drive-home
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
2. `/local-ci` — run CI, attempt every failure through its owning agent, and
   halt only on what resisted or needs a decision,
3. `/squash` — fold each layer into one coherent commit and regenerate every
   layer's PR title/description from it,
4. mark every layer ready — which is what starts Codex's review and the PR's
   own checks — then answer every Codex finding and fix every red check,
5. merge the stack atomically and watch it until it lands (a single-PR session
   marks the one PR ready and enables auto-merge, same as today).

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
`:implementation-completed` (§9) and the daemon marks the workstream
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
  --jq '.[] | select(.open) | select(any(.pull_requests[]; .head.ref | startswith("<session>--")))
            | "stack #\(.number) base=\(.base.ref) prs=\([.pull_requests[].number])"'
```

**Keep the `startswith("<session>--")` filter** — the endpoint returns every
stack in the repo, and this repo runs a dozen sessions at once. Unfiltered, this
step would drive another session's stack to the merge queue.

**Keep `select(.open)` too.** A merged stack stays listed by this endpoint
forever, with `open:false` — there is no way to delete a merged stack record.
Without the filter, a session whose stack already shipped still "has a stack"
here, and this step re-runs §3–§8 for no reason (harmless — §8's merge is
verified idempotent — but pointless, and confusing to reason about).

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
  order** (bottom to top). §6 and §8 need those numbers, and nothing else
  produces them.
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

## 4. CI — `/local-ci`

`cd ..` (session home), then invoke **`/local-ci`**. It commits a clean
tree, runs CI, attempts every failure through the agent that owns it, and halts
only on findings that resisted those attempts or would need a decision.

It returns a report with `Resolved` / `Unresolved` / `Flake` sections. **`Unresolved`
empty and CI green ⇒ continue.** Anything under `Unresolved` ⇒ record a
`:blocker` and stop.

Carry its `Resolved` entries forward: they are edits that are now in the branch
and belong in the layer's commit brief at `/squash` (§5), and if you halt, the
blocker should say what was already settled so the human does not re-derive it.
CI is a slow Docker build, so trust the report rather than re-running to check.

## 5. Squash + PR text — `/squash`

Reached **only** on green CI with no unresolved conflicts. `cd worktree`, then
invoke **`/squash`**. It folds each layer of the stack into one coherent
commit, pushes the whole stack, and regenerates every layer's PR
title/description. It is mechanical and never halts.

## 6. Mark every layer ready for review

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
B: unstack, then one link. Then re-run `/drive-home`.

For a single-PR session, `gh pr ready <number> -R "$SLUG"` — **both** the number
(from §2's discovery) and `-R`. `-R` alone is not enough: `gh pr ready -R <slug>`
exits `argument required when using the --repo flag`, and bare `gh pr ready`
cannot resolve a repo from this worktree.

## 7. Review — Codex, and the checks that ready-for-review started

Marking ready in §6 is what fires Codex (`chatgpt-codex-connector`) and starts
the PR's GitHub Actions checks. **Neither ran during `/local-ci`** — that was the
local Docker CI, and the PR adds e2e, integration shards and a staging deploy on
top of it. This is where the branch first meets everything that will actually
gate it.

Do this for **every layer**. A stack of three gets three Codex reviews and three
check rollups, and a finding on layer 2 is layer 2's to fix (§7.3).

### 7.1 Wait for Codex — it has two ways of answering

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
stamps the commit it reviewed (§7.2); the reaction does not — it carries
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

### 7.2 Read what it found

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

### 7.3 Attempt every finding — and land the fix in the layer that owns it

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

### 7.4 The checks

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

### 7.5 Cap the rounds at three

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

## 8. Merge — enqueue, and watch it land

Reached only with §7 settled: every layer's Codex verdict answered, and no red
checks left. `cd worktree` if you are not already there, then merge:

```bash
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
(cd "$SRC" && gh stack merge <top-pr-number> --yes --rebase)
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

### Watch it to completion

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
  combination. Treat it exactly as §7.4, fix, and re-enqueue.

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
and finish §9 anyway, because nido already has a completion watcher: the
`github-merge` poller runs every 5 minutes, closes the workstream, appends its
terminal `:merged` event and nudges Notion, correlating on the `:github` external
ref that `/prepare-draft-pr` stamped. It needs nothing from this session.

That handoff is not a nicety. Run headless by `nido ship`, this skill holds a
**cap-1 merge lane** — one branch at a time, repo-wide — under an 8h
SIGTERM→SIGKILL backstop sized for "a CI cycle or two", not for an open-ended
queue wait. A watch that never gives up turns a slow queue into a stalled merge
lane for every branch behind it.

## 9. Record completion on the ticket ledger

Append a typed `:implementation-completed` event so the workstream's report
timeline closes the loop (CI green, reviewed, squashed, landed or on the queue).
Write it whether §8 saw the merge land or handed off on budget — the ledger entry
is what the merge lane classifies on, not the merge itself. The `BR-####`
is the `:event-payload :id` in `./run-link/run.edn` (run from the session home;
`cd ..` out of the worktree if needed):

```bash
cat > /tmp/impl-completed.edn <<'EDN'
{:format    :implementation-completed
 :summary   "<one-line: what shipped; CI green; Codex answered; landed / on the queue>"
 :artifacts [{:kind :pr :ref "<owner>/<repo>#<number>" :url "<pr-url>"}]
 :design-delta {:held? true}}
EDN
bb nido:ticket:append :project <project> :br <BR-####> :kind implementation-completed \
  :file /tmp/impl-completed.edn
```

### `:design-delta` — did what landed match what we said?

**Only when the workstream has a `:design` record** (`bb nido:workstream:show`);
omit the key entirely when there is none, which is normal for scratch work.

This is the one moment the question is cheap. You are holding the whole change
right now; a week from now answering it costs a full re-read. And it is what
stops the record aging into fiction — the next change in this area will infer
its assumptions from *code* and declare `:conforms` against a stance nobody
re-checked, so an unrecorded drift enters silently right here.

- **It held** → `{:held? true}`. One word, no ceremony. That is the expected
  answer and it should stay free.
- **It held, with deviations** → `{:held? true :deviations ["…"]}`. The boundary
  landed slightly elsewhere, an invariant needed a caveat. Name each concretely
  — a file, a boundary, an invariant. This is the amendment signal for whoever
  touches the area next.
- **It did not hold** → `{:held? false :deviations ["…"]}` (deviations are
  required here; the schema rejects a bare `false`). Say so plainly. It also
  means the design should have been superseded *during* the work and was not —
  worth a line in your report, because that is a process finding, not a
  footnote.

**Do not answer this from the commit messages.** A vague deviation ("some things
moved") is worse than `{:held? true}`, because it reads as diligence while
saying nothing. If you cannot name what deviated, it held.

For a stack, list every layer's PR as an artifact so the report timeline shows
the whole shipment:

```bash
cat > /tmp/impl-completed.edn <<'EDN'
{:format    :implementation-completed
 :summary   "<one-line: what shipped; N layers; CI green; stack on the merge queue>"
 :artifacts [{:kind :pr :ref "<owner>/<repo>#<n1>" :url "<pr-url-1>"}
             {:kind :pr :ref "<owner>/<repo>#<n2>" :url "<pr-url-2>"}
             {:kind :pr :ref "<owner>/<repo>#<n3>" :url "<pr-url-3>"}]
 :design-delta {:held? true}}
EDN
bb nido:ticket:append :project <project> :br <BR-####> :kind implementation-completed \
  :file /tmp/impl-completed.edn
```

(Validated against `ImplementationCompleted`; `:artifacts []` :kind is one of
`:commit :pr :branch`.)

**`:design-delta` is a field on this event, never its own ledger entry.** The
merge lane classifies a Run by the *latest* ledger kind
(`nido.coordinator.ship/classify-outcome`), so appending a separate entry after
this one would hide the `:implementation-completed` fingerprint and park a
perfectly healthy branch as blocked.

Report: the PR URL(s), what was rebased/auto-fixed, **what Codex found and what
you did about each finding** — fixed, or declined and why — that the stack was
folded (one commit per layer) with every PR description regenerated, and whether
it landed or is still on the queue.

The review summary is the part a human actually reads. A run that fixed four
findings and declined one is a different thing from a run that sailed through
clean, and only this report says which happened. Keep the declines explicit: a
decline is a judgement made on someone's behalf, and it is reviewable only if it
is stated.

If §8 handed off on budget, the coordinator's `github-merge` poller closes the
workstream and nudges Notion when it lands — nothing further to do here.

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
- Codex already answered for the layer's current head (a review stamped with that
  commit, or a `+1` left after the last push) → don't wait again, and don't
  re-request (§7.1).
- A finding already replied to or already fixed → skip it. Thread replies are
  additive, so a second pass would post the same reasoning twice (§7.3).
- Already merged → the §8 watch returns immediately; §9 still runs, because the
  ledger entry is what the merge lane classifies on.

This makes drive-home loop-ready: a future `/loop` wrapper can re-invoke it until
the PR (or stack) merges.

## When it halts

On a semantic conflict surfaced by `/align` (§3), an `Unresolved` CI finding
surfaced by `/local-ci` (§4), or a review finding that survived §7's three rounds,
drive-home leaves the worktree exactly as it is, reports **what** blocks and
**how to resume** (resolve conflict X / fix test Y / rule on finding Z, then
re-run `/drive-home`), and stops. It makes no `merge` call on a halted journey.

**A §7 halt leaves the layers ready and reviewed, which is the point.** Unlike an
§3 or §4 halt, the work is published and a human can read the open threads on
GitHub. Say which finding is unresolved and what was tried; do not un-ready the
PRs to "clean up" — that discards Codex's verdict and re-triggers the whole
review on the next run.

**Running out of watch budget in §8 is not a halt.** The stack is on the queue
and the `github-merge` poller owns it from there; finish §9 and report it as
shipped-and-landing, not as blocked. Recording a `:blocker` there would park a
healthy branch.

**A CI halt arrives with an attempt history.** `/local-ci` reports what it
tried on each unresolved finding and what came back; pass that through rather
than restating the job name. The human's first question is "what has already
been ruled out", and the answer is in the report.

When it halts on a semantic conflict surfaced by `/align` (§3), an `Unresolved`
CI finding surfaced by `/local-ci` (§4), or a review finding still standing after
§7's three rounds, also record a typed `:blocker` event so the parked workstream
shows what blocks it:

```bash
cat > /tmp/blocker.edn <<'EDN'
{:format  :blocker
 :summary "<what blocks — conflict / CI finding that resisted / review finding still open — and what was tried>"
 :needs   "<the decision the human must make, then re-run /drive-home>"}
EDN
bb nido:ticket:append :project <project> :br <BR-####> :kind blocker :file /tmp/blocker.edn
```

This records the halt; it does not change the halt behaviour — drive-home still
stops and makes no `ready`/`merge` calls.

## Common mistakes

- **Waiting for `/local-ci` to ask for approval** — it has no approval gate any
  more; it attempts, then halts only on what resists (§4). The `auto` argument
  is still accepted and does nothing.
- **Waiting for a Codex *review* on a clean PR** — a clean verdict is a `+1`
  reaction and no review at all, so this waits forever on the PRs that were
  fine. Check both signals (§7.1).
- **Reading the reaction from `reactionGroups`** — it does not say who reacted, so
  a human 👍 passes as Codex's verdict. Use the REST reactions endpoint and filter
  on `chatgpt-codex-connector[bot]` (§7.1).
- **Trusting a `+1` left before your last push** — reactions carry no commit, only
  `created_at`, so a stale one reads as a clean verdict on code Codex never saw
  (§7.1).
- **Fixing a Codex finding on top of the stack** — it lands in the wrong PR and
  breaks one-commit-per-layer. Squash it into the owning layer (§7.3).
- **Acting on a review whose `Reviewed commit` is not the layer's head** — those
  findings were raised against code you have already replaced (§7.2).
- **Silently skipping a finding you disagree with** — decline it *on the thread*,
  against something already on the record, or fix it (§7.3).
- **Reading `removed_from_merge_queue` as failure** — it fires on success too;
  what distinguishes them is whether a merge landed beside it (§8).
- **Watching the merge queue indefinitely** — the merge lane is cap-1, so this
  blocks every other branch. Budget the watch and hand off to the `github-merge`
  poller (§8).
- **Parsing the session as the last path segment** — slash-namespaced sessions
  span multiple segments; take everything after `/sessions/<project>/`.
- **Running `gh`/`git` from the session home** — it's not git-colocated;
  `cd worktree` first (§2, §6, §7 and §8 run from the worktree; §4 runs from the
  session home).
- **Flipping `ready` before CI is green** — §6 is reached only after §4 reports
  green.
- **Proceeding to `/squash` before `/local-ci` reports green** — `/squash`
  (squash + PR text) is the last phase, after green CI. Commit-shaping itself
  lives in `/squash`; drive-home never splits or reshapes commits here.
- **Calling `gh pr merge --auto` on a stack** — use
  `gh stack merge <top-pr-number> --yes`; calling both double-enqueues (§8).
- **Merging a stack whose §6 link exited non-zero** — the shape is wrong on
  GitHub; `gh stack merge` would land a mid-stack PR carrying the layer below
  it. Repair via `/stack` §6 case B first (§6).
- **Judging `jj resolve --list` by its exit code** — the clean case exits 2 with
  `Error: No conflicts found at this revision`, which would record a `:blocker`
  on every successful rebase (§3).
- **Marking only the top PR ready** — every layer must be ready or the stack
  merge refuses. Observed: with only the top of a two-layer stack marked
  ready, `gh stack merge 14 --yes` exits 5, `✗ pull request #14 cannot be
  merged yet: #13 below it is a draft`. The refusal is a clean halt — nothing
  merges — not damage.
- **Merging layer-by-layer with `gh pr merge`** — that abandons atomicity;
  `gh stack merge` lands the whole stack or none of it.
- **Omitting `--base "$TRUNK"` on the §6 `gh stack link --open` re-link** —
  force-resets the bottom PR's base to the repo default branch unless given
  explicitly; observed doing this unasked against a non-trunk base (§6,
  `/stack` §4).
- **Omitting `--rebase` on `gh stack merge`** — the method otherwise falls
  back to whatever was last used on this machine; a stray `--squash` there
  would collapse the stack's layers into one commit (§8).
- **Trusting §2's discovery without `select(.open)`** — a merged stack stays
  listed forever with `open:false`; without the filter, a session whose stack
  already shipped still looks like it needs driving home (§2).
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
