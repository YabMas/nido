---
name: local-ci
description: Run brian's CI for the session you're in, attempt every failure through the agent that owns it, and halt only on what genuinely resists or would need a decision — then report what was resolved and how. Autonomous; the path /drive-home composes. Invoked in-session from the session home.
---

# /local-ci

## Overview

Run brian's own CI for the session you're in, triage the failures, and **settle
what can be settled** — dispatching each failure to the agent that owns it,
verifying each fix narrowly, and stopping only at findings that resist or that
would require deciding rather than doing. This drives the existing
`bb nido:run … ci` task and brian's lane/dev agents (present in-session). It does
not reimplement CI or parse output in code.

Two diff-scoped hygiene lints in the `Checks` job — `Comment Lint` and
`Ticket Refs` — are reproduced locally first, in ~2s, so a comment typo does not
cost a five-minute Docker cycle to discover. See "### 2. Hygiene pre-flight".

## One mode

There used to be two: a bare mode that stopped for approval before any edit, and
an `auto` mode for `/drive-home`. The gate is gone. A CI failure with a named
owner and a reproducible error is work to do, not a decision to bring someone;
what actually needs a human is a much smaller set, and §5 draws that line
directly instead of proxying it through who typed the command.

**`/local-ci auto` still works** — `auto` is accepted and ignored, so an older
invocation or a habit does not break.

What replaced the gate is not nothing: a **bounded attempt** at every failure,
verified narrowly before it counts as fixed, and a **halt line** drawn at
deciding rather than doing. Both are §5, and both apply however the skill was
invoked.

## When to use

- Asked to "run local CI", "run `bb ci`", or "fix CI findings" for the session.
- You are running **from a nido session home** (cwd
  `~/.nido/sessions/<project>/<session>/` — where `bb nido:run` works and this
  skill is injected). If you're in the worktree or elsewhere, see Resolve below.

## Flow

### 1. Resolve the session from cwd

Run `pwd`. A session home is `…/.nido/sessions/<project>/<session>/`. Split the
path on `/sessions/`: the **first** segment after it is `<project>`, and the
**rest** (which may contain slashes, e.g. `fix/add-delay`) is `<session>`.
Sanity-check that cwd contains a `worktree` symlink and a `bb.edn`.

If cwd is not a session home (no `/sessions/` segment, or `bb nido:run` isn't
found), stop and tell the user to run it from the session home —
`bb nido:session:enter :project <p> <session>` lands there.

### 2. Hygiene pre-flight

Two checks in the `Checks` job are pure diff scans: `Comment Lint` (comment
archaeology, and comments naming a symbol the same diff deletes) and
`Ticket Refs` (non-canonical ref shapes on added lines). Reproduce them before
spending a CI cycle:

```bash
cd worktree
bb <LIB>/hygiene-scan.bb
```

`<LIB>` is this skill's own lib dir — `$PWD/.claude/skills/local-ci/lib` from the
session home, else `$HOME/Code/nido/.claude/skills/local-ci/lib`. Resolve it
once, then use the **literal absolute path**: shell variables do not survive
between Bash calls (each call is a fresh shell; cwd persists, the environment
does not). The script must run with **cwd = the worktree** — that is where jj
answers about the right tree and where brian's `bb.edn` puts its own lint
namespaces on the classpath.

Exit 1 ⇒ the `Checks` job **will** fail. Fix these first — inside comments only,
proven by the gate in "## Hygiene fixes" — commit, and re-scan until clean before
spending a CI cycle. Exit 0 ⇒ proceed.

The scan also reports two **advisory** classes that CI has no check for at all.
Both belong to `lane-comments`, and the scan's own output says so — a class
printed with nobody named is read as a soft warning and acted on by no one.

- **migration narration** in comments (`no longer`, `previously`, `formerly`,
  `replaces the`) — the soft vocabulary brian's comment-lint deliberately
  refuses to blocker-gate ("handled by review lanes, NOT this commit blocker").
  Judged against `~/Code/nido/docs/reference/comments.md` § 4. The word list is
  a prior, not a verdict: English collides with several of them, and the
  narration that uses none of them is invisible to the scan and visible to the
  lane.
- **commented-out code** — a parked block sitting in a comment. It *is* a
  comment, so whether it earns its line is the doctrine's § 1 test rather than a
  separate rule. What the scan cannot tell is whether somebody parked it
  deliberately, which is the judgement the lane is for.

Fold both into the report (§6) with their owner, and fix neither here (see
"## Hygiene fixes").

**Two classes changed shape, and it is worth knowing which way.** A committed
`.orig`/`.rej`/`.bak`/`.DS_Store` is now **blocking** rather than advisory: it
is decided by a path suffix with no question of intent, which is the same shape
as a banned token. Its fix is deleting the file, not editing a comment, so
`no-code-change.bb` will not bless it — delete it, say so in the report, and
re-scan. Added **debug output** is no longer scanned at all: whether a `println`
is production noise or a CLI's own output turns on a pathspec only the project
can write, and the hand-rolled guess made instead (exempt `/test/`, `/dev/`,
`bin/`) flagged the scan's own report lines. A project that wants that check
should put it in its own linter, with its own pathspecs.

**This skill does not dispatch `lane-comments` itself.** Naming the owner is not
the same as spending a review round on comment prose inside a CI skill, and the
call belongs to whoever is reading the report — the narration items are
advisory precisely because nothing about them fails CI.

**Why not just run `bb lint:comments`.** In a session worktree those tasks are
structurally broken, and broken *silently* — see "## Common mistakes".

### 3. Commit a clean tree

CI Docker-COPYs the worktree, so `:ci` aborts on a dirty `jj st`. Commit any
working-copy changes yourself — this used to refuse and hand the job back:

```bash
cd worktree
jj st                                   # if dirty…
jj commit -m "chore(ci): clean tree for CI"   # …fold it in
cd ..                                   # back to the session home
```

(A clean tree ⇒ skip the commit.) These `chore(ci): …` commits are throwaway
scaffolding — `/squash` folds them into the final commit, so the message
doesn't matter.

### 4. Run CI

Image caching is ENABLED and jj-safe: brian keys the CI image cache on the
working-tree CONTENTS (sha256 of the bytes that get COPY'd — brian PR #3380),
so a cache hit always means "same source" regardless of the git/jj view.
Expect a slow full Docker rebuild only when source changed since the last CI
run; unchanged re-runs (flake re-runs, e2e right after unit) reuse the image.
Do not pass `--no-cache` or warn about forced rebuilds. Run, capturing output:

```
bb nido:run :project <project> <session> ci
```

No `nido:session:up` needed — brian's CI is self-contained Docker, path-isolated
by its own `CI_SUFFIX`.

**Clean-worktree gate:** the `:ci` command refuses to run if the worktree is
dirty (output starts `nido: working copy is dirty …`, followed by `jj st`).
This is **not** a CI failure — the Docker build COPYs the worktree, so a dirty
tree would test code the remote can't see. Seeing it here means §3 was skipped
or something wrote to the tree since: go back, commit, and re-run. Never triage
it as a failed job.

Exit 0 ⇒ green. Report it (§6), with any advisory hygiene items from §2. Stop.

On failure, read the output: enumerate failed jobs (`:check`, `:unit`,
`:integ-{a,b,c}`, `:e2e-{1,2,3}`) and brian's `ACTION REQUIRED:` tail.
Distinguish real regressions from flake/infra (Docker errors, e2e flakiness —
the summary reports flaky counts; a partition imbalance is not a code bug).
For each real failure, name the job, the salient error lines, and the **owning
agent** (see Routing) — that list is what §5 works through. Get the actual error
from logs; don't guess from the job name.

### 5. Fix — attempt everything, halt only on what genuinely resists

**The Routing table names who fixes, not whether to fix.** A row
routing to `test-dev`, `e2e-dev`, `database-dev` or a `lane-*` means *dispatch
that agent*. It does not mean stop. Halting because a failure was not in the
mechanical class is the most common way this skill wastes a human: most such
findings settle in one dispatch, and a halt that arrives before anything was
tried tells the human nothing they could not have guessed from the job name.

**Attempt every real failure**, except the three below, which are never
attempted:

1. **`needs-eyes` from the prose gate** — a real string in the program changed.
   The gate exists precisely to put eyes on those. See "## Hygiene fixes".
2. **`ACTION REQUIRED` instructions that name a decision** — version gates, e2e
   partition rebalance, anything the tail says is unclear. Surface the exact
   instruction; do not guess at it.
3. **Flake and infra** — not a failure to fix. Re-run the affected job once; if
   it passes, it was flake and belongs in the report as such, not as a fix.

#### The attempt protocol

Per finding, in order:

1. **Read the actual error from the logs.** Never diagnose from the job name.
2. **Dispatch the owning agent** with: the worktree path, the failing job, the
   error text, and the claim constraint below.
3. **Verify narrowly — but narrow to the BLAST RADIUS, not to the symptom.**
   Re-run the specific thing that failed — that test namespace, that lint, that
   one Playwright spec — not the whole suite. A full CI cycle is a Docker build
   across eight containers; spending one to learn whether a single test now
   passes is how this skill becomes too slow to use.

   **A change to something SHARED is a different size of narrow.** When the fix
   touches a component, constant or helper with call sites beyond the file it
   lives in, the namespaces that can break are not the ones you edited and not
   the ones you can bring to mind — they are every namespace that reaches the
   thing you changed. Find them mechanically and run all of them:

   ```bash
   # every test that renders the changed component, names its classes, or calls it
   grep -rl '<component>/\|<the-constant>\|<a-distinctive-class-it-emits>' src/test/ \
     | sed 's|src/test/||; s|\.clj$||; s|/|.|g; s|_|-|g' | sort -u
   ```

   Then run that list in one REPL call. Measured on brian: a palette change to
   `ui/feedback` with 16 call sites, verified against the six namespaces that
   looked related, went green — and the merge queue then evicted the PR because
   `user_settings_save_dirty_test`, which nobody would list from memory,
   asserted the old colour. The grep found 30 namespaces; running all of them is
   866 tests and takes about as long as the six did.

   **The tell is the call-site count, not the line count.** A 4-line change to a
   shared default has a wider radius than a 400-line change inside one handler.
4. **Failed verification ⇒ one more attempt**, informed by what the first one
   learned. Still failing ⇒ unresolved. **Two attempts per finding**, then stop
   attempting that one and move to the next.

Commit resolved findings in the worktree
(`cd worktree; jj commit -m "chore(ci): fix <job>"; cd ..`).

#### The halt line: doing versus deciding

A finding is unresolved — whatever the routing table says, and however easy the
fix looks — when settling it means **deciding** something rather than **doing**
it. Concretely, when the fix would:

- **change what the branch claims** rather than make the claim hold: editing a
  test's expected value, deleting or skipping a test, loosening an assertion,
  or widening a schema to admit what the test sent. The failing test is the
  claim; a fix that edits the claim has not fixed anything, it has moved the
  goalposts and gone green.
- **choose between two readings of intent** that the layer's own brief (Claims /
  Out of scope) does not settle.
- **touch files this branch does not already change** — that is a different
  story, and it leaves through the report, not through a fix.
- **contradict a fix already made this run** — two findings pulling opposite
  ways is a design question wearing a CI failure's clothes.

These halt *immediately*, without burning attempts on them. Everything else
halts only after its two attempts have genuinely failed.

**Run budget:** at most **three** full CI runs total (the first, plus two
re-runs). Findings surfaced for the first time by the final run are reported,
not attempted — otherwise "attempt everything" is a loop with no floor. Never
re-run CI merely hoping for green.

### 6. Report what resolved, and how

Emit this every time, green or halted — it is the whole output of this skill, and
the only thing `/drive-home` sees.

```
CI: <green | halted> · <n> resolved · <m> unresolved · <k> flake

Resolved
- <job> · <one-line finding>
  cause:    <what was actually wrong — from the log, not the job name>
  fix:      <what changed, and where>  (agent: <who did it>)
  verified: <the narrow check that now passes>

Unresolved
- <job> · <one-line finding>
  attempted: <what attempt 1 did, and what came back>
             <what attempt 2 did, and what came back>   (or: not attempted — <which of the three>)
  needs:     <the decision a human has to make>

Flake
- <job> — passed on re-run
```

The `attempted` lines are what make a halt worth reading. A halt that says only
"unit test failing, routed to test-dev" is the failure mode this section exists
to prevent: say what was tried and what came back, so the human starts from
where the attempt stopped rather than from the top.

This skill emits **no** coordinator ledger events — when `/drive-home` composes it,
`/drive-home` records the halt in its ledger, from this report.


## Hygiene fixes

`Comment Lint` and `Ticket Refs` are **not** clj-kondo-class mechanical fixes.
The linter checks a token; the fix is a judgement about what the comment should
say instead. What makes them auto-fixable anyway is that every fix lands inside
a comment, and that is *checkable*.

**Fix inside comments only. Then prove it:**

```bash
cd worktree
bb <LIB>/no-code-change.bb --from <rev-before-your-edits> --to @
```

Three outcomes per file:

- **`proven`** — top-level forms identical, or every changed line is a comment.
  (Whitespace, `;` comments and `#_` blocks are not sexpr-able, so a
  comment-confined edit leaves the forms bit-identical by construction.) This is
  the outcome to aim for.
- **`needs-eyes`** — a real string in the program changed: a docstring, a string
  literal, or a quoted span in a non-Clojure file. The gate prints each one
  before → after, narrowed to the region that differs, and holds. `(def timeout
  "30s")` → `"9000s"` lands here too, which is why it is never silent.
  **Never pass `--allow-prose` on your own initiative** — the flag asserts that
  a human read these strings, which is a thing only a human can make true. Halt
  and show them, each before → after. If a human is in the conversation and says
  they are fine, that assertion now holds and you may re-run with the flag; a
  coordinator-spawned run has nobody to say it, so it stops there. A ref inside a
  `testing` label or an allium `open question "…"` legitimately lands here; that
  halt is correct, not a bug.
- **`CODE`** — forms differ outside strings, a file was added or removed, or the
  file type has no comment grammar. This is a bug in **your fix**, not a finding:
  `jj restore <path>` and redo the edit inside the comment.

**Write the comment, don't redact it.** Deleting the banned token leaves a
comment that still narrates a transition, just more vaguely. Read the code it
sits above and describe *that*:

> ✗ `;; This block previously described a RED phase that is long over…`
> ✗ `;; This block described a phase that is over…` (token gone, story kept)
> ✓ `;; The mocked var is `routing.router/route-stream`.`

Same for a comment naming a symbol the diff deletes — re-anchor it on what
survives, rather than citing a name that is about to stop existing:

> ✗ `;; brian's own `max-retries` is deleted along with the direct retry loop`
> ✓ `;; Retries belong to the babel router; brian sets no retry policy here.`

`lint-ok: deleted-ref` / `lint-ok: refs` on the line are escape hatches, not
fixes. Use one only when the comment genuinely must name the departed symbol,
and say why in the report.

**Ticket refs: never touch the PR body or a commit message.** Native `#N` /
`owner/repo#N` are *required* there — GitHub honours `Fixes`/`Closes` auto-close
and cross-repo backlinks only on the native forms, and the convention exempts
them on purpose. Canonicalizing them breaks auto-close.

**Advisory classes are reported with their owner, never fixed here.** Whether to
delete a commented-out block is a judgement about whether somebody parked it
there deliberately, and rewriting narration is a judgement about what the
comment should say instead. Both are `lane-comments`' calls, so they leave
through the report. A blocking stray artifact is the opposite case: delete the
file and note that you did, since it is the one blocking fix that is not a
comment edit.

## Routing (failure class → owner in the fix phase)

Defer to brian's `docs/reference/agent-delegation.md` for anything ambiguous.
Starter map. **This table routes ownership, not permission** — the owner named
here is dispatched (§5). A row naming an agent has never meant "halt".

| Failure | Owner |
|---|---|
| format / lint (clj-kondo) / lint-deps / shellcheck / css / js build | fix directly (mechanical) |
| `Comment Lint` / `Ticket Refs` (the diff-scoped hygiene lints) | hygiene fix (gated) — see "## Hygiene fixes" |
| migration narration in comments (advisory, no CI check) | `lane-comments` — **named in the report, not dispatched**; see below |
| commented-out code (advisory, no CI check) | `lane-comments` — **named in the report, not dispatched**; see below |
| stray artifact committed (`.orig`, `.rej`, `.bak`, `.DS_Store`) | blocking — delete the file and re-scan; not a comment fix |
| i18n / translation | `translate-i18n` skill |
| migrations | `database-dev` (deploy/safety angle: `lane-db-deploy`) |
| Allium specs | `allium:weed` |
| unit / integration test | `test-dev`, or the domain `lane-*` by failing namespace |
| e2e (Playwright) | `e2e-dev` |
| version gates / e2e partition rebalance / unclear | surface to the user with the exact `ACTION REQUIRED` instruction; do not guess |

**One row does not dispatch, and it is the exception that proves the rule.**
Every failure above is a CI failure, so its owner is dispatched (§5). Migration
narration is not a failure — nothing about it is red, and it reaches this skill
only because the pre-flight scans for it anyway. Spending a review round on
comment prose inside a CI skill is a call for whoever reads the report, so this
row names `lane-comments` and stops there.

## Common mistakes

- **Verifying a diff-scoped lint by running it in the worktree.** `bb
  lint:comments` and `bb lint:refs` shell out to git. A session worktree is a
  non-colocated jj workspace nested inside the colocated source repo, so git
  walks up and binds to the **parent** repo. Measured on a live session:
  `bb lint:comments` scanned the parent's **7-file** diff instead of the
  session's **114-file** branch and printed a confident `OK`; `bb lint:refs`
  reported `{:status :failed :reason :engine-unavailable}` — unable to find an
  engine sitting right there in the worktree — and **exited 0**. Neither
  announces it is wrong. Use `hygiene-scan.bb`, which computes the diff with jj
  and drives brian's own rule functions over it.
- **Treating `Comment Lint` as clj-kondo-class mechanical.** It matches on
  "lint" and it is not. See "## Hygiene fixes".
- **Deleting the banned token instead of rewriting the comment.** Passes the
  linter, leaves the archaeology.
- **Passing `--allow-prose` to get to green.** The flag means "a human read
  these strings", and the report has to show them. Only an actual human saying
  so makes it true.
- Parsing the session name as only the last path segment — slash-namespaced
  sessions (`fix/add-delay`) span multiple segments; take everything after
  `/sessions/<project>/`.
- Running `bb ci` directly in the worktree instead of `bb nido:run … ci` — skips
  the centralized `:ci` config (the clean-worktree gate and the private-dep
  token wiring live there).
- Running `bb nido:session:up` first — unnecessary; brian's CI is self-contained.
- **Halting because the Routing table names an agent.** The table
  says who fixes, not whether to. Dispatch it; halt only if the attempt fails or
  the fix would require deciding (see "#### The halt line").
- **Halting without saying what was tried.** "Unit test failing, routed to
  test-dev" is the job name restated. The report owes the human the attempts and
  what came back.
- **Going green by editing the claim** — changing an expected value, skipping a
  test, loosening an assertion. That is not a fix that failed; it is a fix that
  succeeded at the wrong thing.
- **Spending a full CI cycle to verify one fix.** Re-run the specific test or
  lint. CI is eight containers and minutes; the narrow check is seconds.
- **Narrowing to the namespaces you can think of, after changing something
  shared.** The set that breaks is the set that REACHES what you changed, and
  memory does not enumerate it — grep does. Verified green against six
  hand-picked namespaces, a brian palette change was then evicted from the merge
  queue by a seventh; the grep listed thirty (§5).
- Looping CI to green — three full runs is the ceiling, and the last one's new
  findings are reported, not chased.
- Treating flaky e2e / Docker infra errors as code bugs — separate them before routing.
- **Looking for the approval gate.** There isn't one any more; §5's halt line
  replaced it. `auto` is still accepted as an argument and does nothing.
