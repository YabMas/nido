---
name: drive-home
description: Take the current nido session's stack home by composing /align (rebase + trivial-conflict resolution), /local-ci (CI + autonomous fix of every failure it can settle), /squash (fold each layer to one commit + regenerate every PR title/description) and /land (ready for review, answer Codex + the PR checks, collapse the stack to one PR, merge and watch it land), then record completion on the ticket ledger. Halts for human judgement on semantic conflicts, on CI findings that resisted /local-ci's attempts, and on review findings /land could neither fix nor defensibly decline. Usage: /drive-home
---

# drive-home

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/drive-home/`
> and is injected into every spawned session's composed `.claude/skills/` (see
> `nido.session.launcher/compose-claude-dir!`). Sibling of `/align`, `/local-ci`,
> `/squash`, `/land`, and `/prepare-draft-pr`.

## What this is

One command that drives the **current session's** stack from "work written" to
"landed on trunk" by composing four sibling skills, then recording the outcome
on the ticket ledger:

1. `/align` — rebase on a fresh `origin/main`, auto-resolve only trivial
   conflicts (halt on anything semantic),
2. `/local-ci` — run CI, attempt every failure through its owning agent, and
   halt only on what resisted or needs a decision,
3. `/squash` — fold each layer into one coherent commit and regenerate every
   layer's PR title/description from it,
4. `/land` — mark every layer ready, answer the Codex review and the PR checks
   that readiness starts, then collapse the stack into one PR, merge that and
   watch it land.

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
  --jq '.[] | select(.open) | select(any(.pull_requests[]; .head.ref | startswith("<session>--")))
            | "stack #\(.number) base=\(.base.ref) prs=\([.pull_requests[].number])"'
```

**Keep the `startswith("<session>--")` filter** — the endpoint returns every
stack in the repo, and this repo runs a dozen sessions at once. Unfiltered, this
step would drive another session's stack to the merge queue.

**Keep `select(.open)` too.** A merged stack stays listed by this endpoint
forever, with `open:false` — there is no way to delete a merged stack record.
Without the filter, a session whose stack already shipped still "has a stack"
here, and this step re-runs §3–§6 for no reason (harmless — `/land`'s merge is
verified idempotent — but pointless, and confusing to reason about).

Empty means *this session* has no stack object — which is also the state of a
single-PR session, and of layers created but never linked. Fall back to
`gh pr list`, which sees both:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
TRUNK=$(gh repo view "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
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
  order** (bottom to top). `/land` re-derives them itself, but keep them: §7's
  artifact list is built from them.
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

## 6. Land it — `/land`

Reached **only** on green CI with no unresolved conflicts. `cd worktree`, then
invoke **`/land`**. It marks every layer ready for review — which is what starts
Codex and the PR's own GitHub checks, neither of which `/local-ci` ran — answers
what comes back, collapses the reviewed stack into its top PR, merges that one
PR and watches it until it lands.

It returns a report with `Fixed` / `Declined` / `Unresolved` / `Checks` sections
and an `Outcome:` line. **`Unresolved` empty ⇒ continue to §7**, whether the
outcome is `merged` or `on the queue`. Anything under `Unresolved` ⇒ record a
`:blocker` and stop.

Carry the whole report forward. `Fixed` entries are edits now in the branch;
`Declined` entries are judgements made on the reviewer's behalf, and §7's report
is where a human gets to see them. If you halt, the blocker should say what was
already settled so the human does not re-derive it.

**Do not un-ready the PRs on a halt.** `/land` deliberately leaves the layers
ready and the review threads open — that is what lets a human read the review on
GitHub. Reverting to draft would discard Codex's verdict and re-trigger the whole
review on the next run.

## 7. Record completion on the ticket ledger

Append a typed `:implementation-completed` event so the workstream's report
timeline closes the loop (CI green, reviewed, squashed, landed or on the queue).
Write it whether `/land` saw the merge land or handed off on budget — the ledger entry
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

If `/land` handed off on budget, the coordinator's `github-merge` poller closes the
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
- Everything on the GitHub side — already ready, already reviewed, already
  merged — is `/land`'s own precondition set; it re-runs clean. See its
  Idempotency section.
- Already merged → `/land` returns immediately; §7 still runs, because the ledger
  entry is what the merge lane classifies on, not the merge.

This makes drive-home loop-ready: a future `/loop` wrapper can re-invoke it until
the PR (or stack) merges.

## When it halts

On a semantic conflict surfaced by `/align` (§3), an `Unresolved` CI finding
surfaced by `/local-ci` (§4), or a review finding `/land` could neither fix nor
defensibly decline (§6),
drive-home leaves the worktree exactly as it is, reports **what** blocks and
**how to resume** (resolve conflict X / fix test Y / rule on finding Z, then
re-run `/drive-home`), and stops. It makes no `merge` call on a halted journey.

**A `/land` halt leaves the layers ready and reviewed, which is the point.** Unlike
an §3 or §4 halt, the work is published and a human can read the open threads on
GitHub. Say which finding is unresolved and what was tried; do not un-ready the
PRs to "clean up" — that discards Codex's verdict and re-triggers the whole
review on the next run.

**A `landing` outcome from `/land` is not a halt.** The stack is on the queue
and the `github-merge` poller owns it from there; finish §7 and report it as
shipped-and-landing, not as blocked. Recording a `:blocker` there would park a
healthy branch.

**A CI halt arrives with an attempt history.** `/local-ci` reports what it
tried on each unresolved finding and what came back; pass that through rather
than restating the job name. The human's first question is "what has already
been ruled out", and the answer is in the report.

When it halts on a semantic conflict surfaced by `/align` (§3), an `Unresolved`
CI finding surfaced by `/local-ci` (§4), or a review finding still standing after
`/land` (§6), also record a typed `:blocker` event so the parked workstream shows
what blocks it:

```bash
cat > /tmp/blocker.edn <<'EDN'
{:format  :blocker
 :summary "<what blocks — conflict / CI finding that resisted / review finding still open — and what was tried>"
 :needs   "<the decision the human must make, then re-run /drive-home>"
 ;; REQUIRED when the decision is a choice between branches you can already see.
 ;; Two to six of them, in the order you want them read; the gate letters them
 ;; A/B/… and resumes you with the one the human clicks.
 :options [{:label        "<the branch, a few words — this is the button>"
            :summary      "<what taking it means, concretely>"
            :consequence  "<what it costs / forecloses — size, follow-on calls>"
            :recommended? true}
           {:label   "<the other branch>"
            :summary "<what taking it means>"}]}
EDN
bb nido:ticket:append :project <project> :br <BR-####> :kind blocker :file /tmp/blocker.edn
```

**A choice goes in `:options`, not in `:needs` prose.** The append is REJECTED if
`:needs`/`:summary` enumerate branches ("Option A … Option B …") without
`:options` — re-emit it structured and the append succeeds. The reason is what
happens at the gate: options render as lettered cards with one button each, and a
click resumes you with that branch spelled out in full. Written as prose, the only
way to answer is a free-text essay — which is the answer that does not arrive.
Leave `:needs` as the question itself ("a product decision on the Keep Old
branch"), and put `:recommended? true` on the branch your own derivation supports
— it is a hint, and the gate still waits.

Omit `:options` when the halt genuinely has no branches ("I need the Stripe test
key"). Do not invent a second branch to fill the field.

This records the halt; it does not change the halt behaviour — drive-home still
stops and makes no `ready`/`merge` calls.

## Common mistakes

- **Waiting for `/local-ci` to ask for approval** — it has no approval gate any
  more; it attempts, then halts only on what resists (§4). The `auto` argument
  is still accepted and does nothing.
- **Discarding `/land`'s `Declined` entries** — they are judgements made on the
  reviewer's behalf, and §7's report is the only place a human sees them (§6).
- **Un-readying the PRs after a `/land` halt** — that throws away Codex's verdict
  and re-triggers the whole review next run (§6).
- **Treating a `landing` outcome as a halt** — the stack is on the queue and the
  `github-merge` poller owns it; file §7 and report it as shipped (§6).
- **Parsing the session as the last path segment** — slash-namespaced sessions
  span multiple segments; take everything after `/sessions/<project>/`.
- **Running `gh`/`git` from the session home** — it's not git-colocated;
  `cd worktree` first (§2 and §6 run from the worktree; §4 runs from the session
  home).
- **Flipping `ready` before CI is green** — §6 is reached only after §4 reports
  green.
- **Proceeding to `/squash` before `/local-ci` reports green** — `/squash`
  (squash + PR text) is the last phase, after green CI. Commit-shaping itself
  lives in `/squash`; drive-home never splits or reshapes commits here.
- **Judging `jj resolve --list` by its exit code** — the clean case exits 2 with
  `Error: No conflicts found at this revision`, which would record a `:blocker`
  on every successful rebase (§3).
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
