---
name: prepare-draft-pr
description: Open a draft PR for the current impl session and record the linkage (session :pr link, workstream :github ref, Notion GitHub PR property) so the merge poller can correlate the merge back to this ticket. Run from an impl-BR-#### session when ready to open the PR. Usage: /prepare-draft-pr
---

# prepare-draft-pr

Harness-side skill, owned by nido. Injected into every spawned session's composed
`.claude/skills/` (see `nido.session.launcher/compose-claude-dir!`), so it resolves
regardless of the target project's checked-out branch.

Run this from inside an `impl-BR-####` session home when the work is ready to
review. It opens a **draft** PR and wires up the three links the GitHub merge
poller needs to react when the PR later merges.

## Preconditions

- You are in a nido session home (`~/.nido/sessions/<project>/<session>/`).
- `cd worktree` reaches the code; the branch has commits to push.
- `gh` is authenticated (it uses the machine's GitHub auth).

## Steps

1. **Determine the workstream + Notion ticket.** Read the run record the session
   was spawned from:

   ```bash
   cat ./run-link/run.edn
   ```

   Note `:workstream-id` and `:project`. The Notion `GitHub PR` write below is
   optional polish — the poller only requires the session `:pr` link and the
   workstream `:github` ref — so don't block on finding a page-id.

2. **Count the layers.** Read the stack from jj:

   Derive `<session>` from cwd first (`/stack` §4, "Deriving `<session>`") — the
   bookmark list must be **scoped to this session**:

   ```bash
   cd worktree
   jj log -r 'trunk()..@' --no-graph -T 'change_id.short() ++ " " ++ description.first_line() ++ "\n"'
   jj bookmark list -T 'if(remote, "", name ++ "\n")' | grep "^<session>--"
   ```

   `jj bookmark list` covers the **whole shared jj repo**, which holds every
   other workspace's bookmarks — this repo has a dozen live workspaces. An
   unanchored `grep -- '--'` therefore sees foreign layer bookmarks, takes the
   stack fork, and tries to publish another session's layers. It also matches a
   `--` inside a commit's first line, which the default template prints;
   `-T 'name ++ "\n"'` prints names only.

   - **One layer** (no `<session>--*` bookmarks, or exactly one) → follow the
     single-PR path below, unchanged.
   - **Multiple layers** → follow §"Publishing a stack".

   A one-layer stack is exactly today's flow. The fork is on **layer count**,
   never on a flag.

3. **Push the branch and open the draft PR** from the worktree. Author a clear
   title and body, and **lead the body with a Design section** — the diff is
   already in the PR, so re-describing it spends the reviewer's attention on what
   they can already see. What they cannot reconstruct is the intent. Read the
   workstream's design record (`bb nido:workstream:show :project <p> :ref <ref>`)
   and lift from it:

   ```markdown
   ## Design

   <the record's :shape, in a sentence or two>

   **Holds:** <each :invariant — what a reviewer can check this against>
   **Considered and rejected:** <each :rejected alternative, with why not>
   **Deliberately left:** <each :seam, and what makes it visible>

   <then the usual summary; reference the BR-####>
   ```

   Omit any line the record doesn't carry rather than padding it. The rejected
   alternatives are the highest-value part: they are what stops a reviewer
   proposing something you already weighed, and they turn that exchange into
   *here is why not* instead of a round trip. If a PR already exists for this
   branch, reuse it instead of creating a second one:

   ```bash
   cd worktree
   SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
           | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
   jj git push -b '<session>'
   # reuse check, keyed on the head branch — no current branch needed
   gh pr list -R "$SLUG" --head '<session>' --state open --json number,url
   ```

   **Not `--allow-new`** — jj 0.42 removed the flag, and a command carrying it
   exits `error: unexpected argument '--allow-new' found` without pushing
   anything. `-b` implies it: an untracked bookmark is tracked automatically on
   its first push.

   If that reports a PR, reuse it. If it reports `[]`, create one — re-derive
   `$SLUG` here, since it does not survive from the block above, and read the
   trunk branch rather than hardcoding `main`:

   ```bash
   SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
           | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
   TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
   gh pr create -R "$SLUG" --base "$TRUNK" --head '<session>' --draft \
     --title "<title>" --body "<body>"
   ```

   `-R` with an explicit `--head`/`--base` is what makes this work from a
   non-colocated worktree; bare `gh pr view`/`gh pr create` cannot resolve a repo
   or a current branch here. (`gh pr list` and `gh pr create` are fine on `-R`
   alone — it is the PR-*resolving* subcommands, `view`/`edit`/`ready`/`merge`,
   that additionally need an explicit number.)

4. **Read back the canonical PR identity:**

   ```bash
   SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
           | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
   gh pr view <number> -R "$SLUG" --json number,url,title
   ```

   `<number>` comes from step 3. Both `-R` and the number are required: `-R`
   alone exits `argument required when using the --repo flag`.

   Construct the external-ref id as `<owner>/<repo>#<number>` (e.g.
   `brian-study/brian#412`). `$SLUG` is that `<owner>/<repo>`.

5. **Add the session `:pr` link** (run from the session home so it auto-resolves
   the session from cwd):

   ```bash
   cd ..    # back to the session home
   bb nido:session:link:add :type :pr :url "<pr-url>" :title "<pr-title>"
   ```

6. **Stamp the workstream `:github` ref** (this is the key the poller matches on):

   ```bash
   bb nido:workstream:ref:add :project <project> :ws-id <workstream-id> \
     :adapter github :id "<owner>/<repo>#<number>" :url "<pr-url>" :title "<pr-title>"
   ```

7. **Record the PR on the ticket ledger** as a typed `:pr-opened` event so the
   workstream's report timeline shows the PR (the report browser reads this). The
   ledger key is the `BR-####` — read it from the run record
   (`:event-payload :id` in `./run-link/run.edn`). Write the EDN to a temp file
   and append it:

   ```bash
   cat > /tmp/pr-opened.edn <<'EDN'
   {:format  :pr-opened
    :url     "<pr-url>"
    :title   "<pr-title>"
    :summary "<one line, in design terms: what this makes true of the system —
               the record's :summary, not a restatement of the diff. Refs BR-####>"}
   EDN
   bb nido:ticket:append :project <project> :br <BR-####> :kind pr-opened \
     :file /tmp/pr-opened.edn
   ```

   The append validates against nido's `PrOpened` schema and rejects a malformed
   report (non-zero exit + an explain dump) — fix and retry. For a Slack-sourced
   workstream with no `BR-####`, use the slack `:id` as `:br`.

8. **Optional — record the PR on the Notion ticket.** If you have the ticket's
   Notion page-id (from the `:notion` external-ref on the workstream, visible in
   the session briefing/CLAUDE.md), set its `GitHub PR` property (type `url`) to the
   PR URL with `notion page set <page-id> "GitHub PR=<pr-url>"`.

## Publishing a stack

Every layer must already carry its `Layer:` trailer and review brief (see
`/stack` §5). If any does not, fix the commit descriptions first — the PR bodies
are generated from them.

### 1. Derive the two values the rest needs

```bash
cd worktree
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
```

`$SRC` is the colocated source repo — `gh stack` cannot run in this worktree,
which has no git repository.

**Shell variables do not survive between commands.** Each Bash call in this
harness is a fresh shell — cwd persists, the environment does not. Re-derive
`$SLUG`/`$SRC` in every block below that uses them, or inline the literal values;
otherwise `-R "$SLUG"` goes out as `-R ""`.

### 2. Push every layer bookmark

```bash
jj git push -b 'glob:<session>--*'
```

Only `<session>--*` bookmarks go up. The session bookmark itself stays local.

**No `--allow-new`** — jj 0.42 removed it; the command would exit `unexpected
argument` and push nothing, taking the whole publish flow down at step one. `-b`
already tracks a bookmark that isn't tracking anything yet.

### 3. Open one PR per layer, bottom to top

**First, check what already exists.** This step is re-run whenever `/drive-home`
finds no stack, so it must reuse rather than duplicate. Use `gh pr list` here —
**this is the case the stacks endpoint cannot serve** (`/stack` §4): the PRs
this step creates are not linked into a stack until §4, so `gh api …/stacks`
returns `[]` for exactly the state this guard has to see:

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
gh pr list -R "$SLUG" --state open --limit 50 \
  --json number,url,headRefName,baseRefName,isDraft \
  --jq '.[] | select(.headRefName | startswith("<session>--"))'
```

For each layer, branch on whether its bookmark appears as a `headRefName`:

- **No open PR for that `headRefName`** → `gh pr create` it (below).
- **An open PR already exists** → **do not create a second.** Update it instead:
  `gh pr edit <number> -R "$SLUG" --title … --body …`. If the shape moved and the
  base needs to change, check first whether the PRs are already linked into a
  stack (`gh api repos/"$SLUG"/stacks`) — this step is exactly the re-run/repair
  path, so a stack from an earlier `/stack` §6 reshape is likely to already
  exist. Against a stacked PR, `gh pr edit <number> -R "$SLUG" --base
  <layer-beneath>` fails: `GraphQL: Cannot change the base branch because the
  pull request is part of a stack.` GitHub locks a stacked PR's base, so it
  cannot be edited directly. If a stack exists, point at `/stack` §6 case B
  (unstack, confirm it's gone, then one link) instead of editing the base.

Without this guard, a re-run makes `gh pr create` fail on every layer that
already has a PR — while `/drive-home` promises "PR or stack already exists →
reuse (don't create a second)". The single-PR path above has the same guard.

Each layer's base is the layer beneath it; the bottom layer's base is the trunk
branch, read from the repo rather than hardcoded. `-R` means no local git is
needed. Title is `[n/N] <the layer commit's subject>`; body is generated from
that commit's message body and its review brief.

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
gh pr create -R "$SLUG" --base "$TRUNK"          --head <session>--<l1> --draft \
  --title "[1/3] <subject>" --body "<generated body>"
gh pr create -R "$SLUG" --base <session>--<l1>   --head <session>--<l2> --draft \
  --title "[2/3] <subject>" --body "<generated body>"
gh pr create -R "$SLUG" --base <session>--<l2>   --head <session>--<l3> --draft \
  --title "[3/3] <subject>" --body "<generated body>"
```

Every body must carry the brief's four fields — **Claims**, **Verify**, **Lane**,
**Out of scope**. That is what makes per-layer review bounded.

**The bottom layer's body additionally carries the Design section** (step 3's
template), since it is the entry point a reviewer reads first and the design
covers the whole stack, not one layer. Layers above it reference it rather than
repeating it.

**Say what the layering claims, not just what order it is in.** A cover note that
lists dependency order tells a reviewer something jj already told them. State the
decomposition as the design decision it is — *these are the separable decisions,
and here is the one each layer carries* — which is exactly the design record's
`:layers`. A reviewer who knows which decision a layer owns can accept or reject
that layer on its own, which is the entire point of stacking it.

### 4. Link them into a stack, by PR number

```bash
SLUG=$(jj git remote list | awk '/^origin/{print $2}' \
        | sed -E 's#^git@github\.com:##; s#^https://github\.com/##; s#\.git$##')
SRC=$(cd .jj && cd "$(dirname "$(cat repo)")/.." && pwd)
TRUNK=$(gh repo view -R "$SLUG" --json defaultBranchRef -q '.defaultBranchRef.name')
(cd "$SRC" && gh stack link --base "$TRUNK" <n1> <n2> <n3> 2>&1); echo "EXIT=$?"
```

**Always pass `--base "$TRUNK"`.** Every `gh stack link` call force-resets the
bottom PR's base to the repository default branch unless told otherwise —
observed retargeting a deliberately non-trunk-based PR at `main`, unasked
(`/stack` §4). No-op for nido today; removes the failure mode structurally for
whenever it isn't.

Bottom to top, using the numbers §3 just created. **PR numbers are right for this
*initial* link** — every PR exists already, so nothing needs creating, and
numbers skip `gh stack link`'s automatic branch push, which §2's `jj git push`
already did.

**Redirect `2>&1` and check the exit code.** `gh stack link` writes its progress
and success lines — `Checking existing stacks…`, `✓ Created stack with 3 PRs
(stack #8)` — to **stderr**; stdout is empty, so `$(...)` or `| tee` captures
nothing. It exits **5** on a partial failure, and it is **not atomic**: a failed
call can leave a PR created and orphaned outside the stack. Report a non-zero
exit with the stderr text rather than re-running blindly.

**Branch arguments would also work here — but not for the reason an earlier
version of this skill gave.** It claimed jj exports every bookmark into the
colocated repo's `refs/heads/*`; **that is false from a non-colocated
workspace**, where jj only exports when a jj command runs in the colocated repo
itself (measured: `git branch --list` in `$SRC` empty, `git ls-remote` showing
every layer). Branch names are safe because `gh stack link` resolves them
**server-side** through the GitHub API. Numbers stay the choice here because they
are already in hand.

**A later re-link passes branch names, not numbers** (`/stack` §4, §6): only
branch arguments make `gh stack link` create the PR an inserted layer needs. That
path is `/squash` §2's, not this skill's — and if the layer was inserted *below
the top*, it needs `/stack` §6 case B's unstack-then-link, because GitHub locks
the base of a stacked PR.

`gh stack link` never removes PRs; re-running an identical link is a clean no-op.

### 5. Stamp one `:github` ref per PR

The merge poller correlates by these and matches *any* ref on the workstream, so
all N are needed:

```bash
cd ..
bb nido:workstream:ref:add :project <project> :ws-id <workstream-id> \
  :adapter github :id "<owner>/<repo>#<number>" :url "<pr-url>" :title "<pr-title>"
```

Repeat for every PR in the stack.

### 6. One session link and one ledger event for the whole stack

One session `:pr` link per PR would be noise — link the bottom PR, since GitHub's
stack map is reachable from any member:

```bash
bb nido:session:link:add :type :pr :url "<bottom-pr-url>" :title "<stack title>"
```

Then **one** `:pr-opened` event listing all layers, so the report timeline reads
as one shipment rather than N:

```bash
cat > /tmp/pr-opened.edn <<'EDN'
{:format  :pr-opened
 :url     "<bottom-pr-url>"
 :title   "<stack title>"
 :summary "<one line: what the stack does; N layers: <slug>, <slug>, <slug>; refs BR-####>"}
EDN
bb nido:ticket:append :project <project> :br <BR-####> :kind pr-opened \
  :file /tmp/pr-opened.edn
```

## Done

Report the PR URL and confirm the session link, workstream ref, and the :pr-opened ticket-ledger entry were written.
When this PR merges, the coordinator's GitHub poller will close the workstream
and move the Notion ticket to Code Review automatically.

## Follow-up PR on a reopened workstream (findings round)

When a workstream was shipped and later **reopened** for a staging findings round,
the fix is a **new** PR — not an edit of the merged one:

- **Branch off fresh `main`.** The prior PR already merged; a freshly-provisioned
  impl session is already on current main. Do **not** reuse or resurrect the old
  (merged) branch.
- **Open a brand-new PR.** Never reopen/amend the merged PR. Stamp the new PR's
  `:github` ref via the same PR-linkage steps above — the merge poller matches
  *any* `:github` ref on the workstream and dedups the already-merged one, so the
  new PR's merge re-closes the workstream to `:done`.
- **Scope the PR to the findings.** The title/body should say it addresses
  findings round N and list the finding ids it resolves.

## Common mistakes

- **Running `gh stack` in the worktree** — it needs a git repository. Run it from
  `$SRC`.
- **Passing branch names to `gh stack link` on the initial link** — the numbers
  are already in hand and the branches are already pushed. (A *re-link* is the
  opposite: branch names, `/stack` §6.)
- **Omitting `--base "$TRUNK"` on `gh stack link`** — force-resets the bottom
  PR's base to the repo default branch unless given explicitly; observed doing
  this unasked against a non-trunk base (§4, `/stack` §4).
- **Passing layers to `gh stack link` top-first** — arguments run bottom to top.
- **`jj git push --allow-new`** — the flag does not exist in jj 0.42; the command
  exits `unexpected argument` and pushes nothing, so nothing downstream can work.
  `-b` implies it (step 3, §2).
- **Capturing `gh stack link`'s stdout, or ignoring its exit code** — it prints
  to **stderr** and exits 5 on a partial, non-rolled-back failure (§4).
- **Hardcoding `--base main`** — read the default branch into `$TRUNK` (step 3,
  §3).
- **Thinking `-R "$SLUG"` is enough for every `gh pr` command** — it is not for
  any subcommand that resolves a PR (`view`/`edit`/`ready`/`merge`). Those need
  `-R` **and** an explicit PR number, or they exit `argument required when using
  the --repo flag`. Only `gh pr create` and `gh pr list` work on `-R` alone.
- **`jj bookmark list | grep -- '--'`** — repo-global; it matches other sessions'
  layers. Anchor it: `grep "^<session>--"` (step 2).
- **Creating PRs on a re-run without checking first** — §3 discovers open PRs by
  `headRefName` and edits rather than creates.
- **Assuming `$SLUG`/`$SRC` carry between commands** — each Bash call is a fresh
  shell. Re-derive them in every block.
- **Stamping only one `:github` ref for a stack** — the poller needs one per PR.
- **Emitting N `:pr-opened` events** — one event lists all layers.
- **Publishing layers whose commits lack a `Layer:` trailer or review brief** —
  the PR bodies are generated from them; fix the descriptions first.
- **Pushing the session bookmark** — only `<session>--*` goes up.
