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

2. **Push the branch and open the draft PR** from the worktree. Author a clear
   title and body (summarize the change; reference the `BR-####`). If a PR
   already exists for this branch, reuse it instead of creating a second one:

   ```bash
   cd worktree
   gh pr view --json number,url >/dev/null 2>&1 \
     || gh pr create --draft --title "<title>" --body "<body>"
   ```

3. **Read back the canonical PR identity:**

   ```bash
   gh pr view --json number,url,title
   ```

   Construct the external-ref id as `<owner>/<repo>#<number>` (e.g.
   `brian-study/brian#412`). The repo slug is the one `gh` reports for this
   worktree's remote.

4. **Add the session `:pr` link** (run from the session home so it auto-resolves
   the session from cwd):

   ```bash
   cd ..    # back to the session home
   bb nido:session:link:add :type :pr :url "<pr-url>" :title "<pr-title>"
   ```

5. **Stamp the workstream `:github` ref** (this is the key the poller matches on):

   ```bash
   bb nido:workstream:ref:add :project <project> :ws-id <workstream-id> \
     :adapter github :id "<owner>/<repo>#<number>" :url "<pr-url>" :title "<pr-title>"
   ```

6. **Record the PR on the ticket ledger** as a typed `:pr-opened` event so the
   workstream's report timeline shows the PR (the report browser reads this). The
   ledger key is the `BR-####` — read it from the run record
   (`:event-payload :id` in `./run-link/run.edn`). Write the EDN to a temp file
   and append it:

   ```bash
   cat > /tmp/pr-opened.edn <<'EDN'
   {:format  :pr-opened
    :url     "<pr-url>"
    :title   "<pr-title>"
    :summary "<one-line: what this PR does, refs BR-####>"}
   EDN
   bb nido:ticket:append :project <project> :br <BR-####> :kind pr-opened \
     :file /tmp/pr-opened.edn
   ```

   The append validates against nido's `PrOpened` schema and rejects a malformed
   report (non-zero exit + an explain dump) — fix and retry. For a Slack-sourced
   workstream with no `BR-####`, use the slack `:id` as `:br`.

7. **Optional — record the PR on the Notion ticket.** If you have the ticket's
   Notion page-id (from the `:notion` external-ref on the workstream, visible in
   the session briefing/CLAUDE.md), set its `GitHub PR` property to the PR URL via
   the notionApi MCP `API-patch-page` tool (property `GitHub PR`, type `url`).

## Done

Report the PR URL and confirm the session link + workstream ref were written.
When this PR merges, the coordinator's GitHub poller will close the workstream
and move the Notion ticket to Code Review automatically.
