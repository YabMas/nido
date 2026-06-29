---
name: continue-ticket
description: Continue a nido-managed ticket in this session. Reads the per-ticket ledger, reports which stage the ticket is at and what earlier stages found, sets the stage, and hands off to the wider harness to do the work. Use at the start of a nido-provisioned session (e.g. an `impl-<br>` session created by `bb nido:ticket:promote`). Usage: /continue-ticket [BR-####]
---

# continue-ticket skill

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/continue-ticket/` and is injected as a native harness skill into every spawned session's composed `.claude/skills/` (see `nido.session.launcher/compose-claude-dir!`), so it resolves regardless of the target project's checked-out branch. Sibling of `/triage-bug`.

## What this is

nido runs a ticket's life as a sequence of **stages**, each recorded in a per-ticket **ledger**. A nido-managed session is handed to you mid-pipeline — an earlier stage already happened, and this session exists to carry the ticket to the next one.

This command's only job is **continuity**: read the ledger, tell you *which stage the ticket is at* and *what the previous stage produced*, set the stage marker, and get out of the way. **It does not tell you how to do the work** — fixing a bug (or whatever the stage is) is already known and managed by the wider harness (brian's working-model rules, your own judgment). This command just points you at where nido's pipeline left off.

## The ledger

Each ticket has a durable record at `~/.nido/projects/brian/tickets/<BR-####>/`:

- `meta.edn` — `:status` (the current stage), `:title`, `:url`, `:disposition`, and `:entries` (an append-only log of every stage's output).
- `entries/NNNN-<kind>.md` — one immutable file per stage (e.g. `0001-triage.md`). This is where a stage writes what it produced.

Read it with `bb nido:ticket:show :project brian :br <BR-####>`. Append to it with `bb nido:ticket:append`. Advance the stage marker with `bb nido:ticket:status`.

## Step 1 — Resolve the ticket (BR-####)

In order of preference:
1. An explicit argument: `/continue-ticket BR-4659`.
2. `./run-link/run.edn` → `:event-payload :id` — a nido-provisioned Run records the BR there:
   ```bash
   bb -e "(:id (:event-payload (clojure.edn/read-string (slurp \"./run-link/run.edn\"))))"
   ```
3. Fallback — the session name: an `impl-br-4659` session ⇒ `BR-4659` (uppercase the `br` prefix).

If you can't resolve a `BR-####`, ask the user rather than guessing.

## Step 2 — Read the ledger and determine the stage

```bash
bb nido:ticket:show :project brian :br <BR-####>
```

Look at `:status` and `:entries` to see where the pipeline is:

- **`:status :planning` with a `:triage` entry** → triage is done; **your stage is implementation.** Read the latest `:triage` entry file (`~/.nido/projects/brian/tickets/<BR-####>/entries/NNNN-triage.md`) — it's the brief the previous stage left you: enriched description, the solution direction(s) it proposed, and the `file:line` leads it already found. Start the implementation on those findings.

In general: the stage to do is the one that comes *after* the latest recorded entry. If the ledger state is ambiguous (no entries, an unexpected status), say so and check with the user — don't invent a stage.

## Step 3 — Set the stage marker

Advance the ticket to the stage you're starting. This also tells nido's coordinator the ticket is no longer a parked review item, which frees the provisioning Run's in-flight slot:

```bash
bb nido:ticket:status :project brian :br <BR-####> :status implementing
```

Then record the implementation plan as a typed ledger event — this resolves any
triage `:squirrel` (deferred sizing) into a concrete direction + effort, and is
what the report browser shows for the impl stage. Write the EDN to a temp file:

```bash
cat > /tmp/impl-plan.edn <<'EDN'
{:format    :implementation-plan
 :summary   "<2-4 sentences: the plan you're about to execute>"
 :direction "<the chosen solution direction, concretely>"
 :effort    :M           ; concrete :XS :S :M :L :XL — resolve a triage :squirrel here
 :steps     ["<step 1>" "<step 2>"]}   ; optional
EDN
bb nido:ticket:append :project brian :br <BR-####> :kind implementation-plan \
  :session <session> :run-id <run-id> :file /tmp/impl-plan.edn
```

The append validates against nido's `ImplementationPlan` schema and rejects a
malformed report (non-zero exit + explain) — fix and retry. Derive
`<session>`/`<run-id>` from the cwd and the `./run-link/` symlink target.

## Step 4 — Do the work (the harness owns the *how*)

You now know the stage and have the prior stage's findings in front of you. **How to actually do it is not this command's concern** — it's known and managed by the wider harness: brian's working-model rules, REPL discipline, lane/agent delegation, commit conventions (this is a full brian session — your nido briefing and the project docs carry the conventions, as usual). Use your judgment within it: when the work is clear and low-risk, just do it; when it needs a product or design call, pull the user in.

Session reminders:
- **Edit in `./worktree`**, not in nido's tree. The REPL / app / DB are nido-managed — connect to the running services, don't start your own.
- **Don't touch Notion** — nido already set the ticket's Notion status when this session was provisioned; the triage stage owns the Notion contract, not this one.
- If what you find contradicts the prior stage's findings (the bug doesn't reproduce, the root cause is elsewhere), say so plainly and reorient with the user rather than forcing the earlier hypothesis.

  When you reorient, record it as a typed `:blocker` event so the parked
  workstream's ledger shows *why* it stalled:

  ```bash
  cat > /tmp/blocker.edn <<'EDN'
  {:format  :blocker
   :summary "<what you found that contradicts the prior stage>"
   :needs   "<the decision you need from the user before proceeding>"}
  EDN
  bb nido:ticket:append :project brian :br <BR-####> :kind blocker \
    :session <session> :run-id <run-id> :file /tmp/blocker.edn
  ```
