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

Read BOTH the ticket ledger and the workstream's active ledger — a staging
findings round lands in the *workstream* ledger, not the ticket ledger, so
`ticket:show` alone will miss it:

```bash
bb nido:ticket:show :project brian :br <BR-####>
bb nido:workstream:show :project brian :ref <BR-####>   # active ledger + latest entry; prints the ws-id
```

Match the **latest** entry against this table — the stage to do is the one that
comes *after* the latest recorded entry:

| latest entry | your stage | what to do |
|---|---|---|
| `:triage` (status `:planning`) | **implementation** | Read the latest `:triage` entry file (`~/.nido/projects/brian/tickets/<BR-####>/entries/NNNN-triage.md`) — the enriched brief, solution direction(s), and the `file:line` leads it found. Start the implementation on those findings. |
| `:findings` with open items | **address findings round N** | This workstream shipped, was reviewed on staging, and reopened. `bb nido:workstream:show` prints the round's items and the ws-id. Work the open items (severity `:blocker` first). Mark each resolved **the moment its fix lands**: `bb nido:findings:resolve :project brian :ws <ws-id> :items [<id>] :by <commit-or-PR>`. Then open a **follow-up PR** — see `/prepare-draft-pr` (its "Follow-up PR on a reopened workstream" section) — and ship with `nido ship` / `/drive-home`. |

If the ledger state is ambiguous (no entries, an unexpected status), say so and
check with the user — don't invent a stage.

## Step 3 — Set the stage marker

Advance the ticket to the stage you're starting. This also tells nido's coordinator the ticket is no longer a parked review item, which frees the provisioning Run's in-flight slot:

```bash
bb nido:ticket:status :project brian :br <BR-####> :status implementing
```

Then author the **design record** — a typed `:design` ledger event, and the one
durable artifact this session produces before any code. It states what the change
commits to in design terms, and it resolves any triage `:squirrel` into a concrete
effort: sizing follows from the design, not the other way round.

**Read `/design` for the doctrine** — what belongs in the record, what does not,
and how to infer the current design safely. The short version: claims about
structure, not a plan of action. There is no step list; the schema rejects one.

Start from the triage entry — its `:directions` and its `:trail`. The deepdive
already read the area, so `:assumes` is where you carry that inference forward
rather than re-deriving it cold.

```bash
cat > /tmp/design.edn <<'EDN'
{:format     :design
 :summary    "<2–4 sentences: what this change makes true of the system>"
 :shape      "<the structural claim: which parts exist, where the boundaries
                fall, what crosses them>"
 :invariants ["<what must hold once this lands — checkable; at least one>"]
 :standing   {:relation :conforms}   ; :extends / :challenges MUST carry :note
 :assumes    [{:about "<the area's current design, as you inferred it>"
               :read  ["<file you read to infer it>"]
               :drift "<where that departs from the stance>"}]      ; optional
 :rejected   [{:alternative "<…>" :why-not "<…>"}]                  ; optional
 :layers     [{:claim "<one sentence, no \"and\">" :mode :judgment}]  ; optional
 :effort     :M}          ; concrete :XS :S :M :L :XL — resolve a :squirrel here
EDN
bb nido:ticket:append :project brian :br <BR-####> :kind design \
  :session <session> :run-id <run-id> :file /tmp/design.edn
```

The append validates against nido's `DesignVision` schema and rejects a malformed
record (non-zero exit + explain) — fix and retry. Derive `<session>`/`<run-id>`
from the cwd and the `./run-link/` symlink target.

If you cannot yet state the shape or name an invariant, that is a finding rather
than a formality — the design question triage deferred is still open. Put it in
`:open` and raise it with the user before writing code.

## Step 4 — Do the work (the harness owns the *how*)

You now know the stage and have the prior stage's findings in front of you. **How to actually do it is not this command's concern** — it's known and managed by the wider harness: brian's working-model rules, REPL discipline, lane/agent delegation, commit conventions (this is a full brian session — your nido briefing and the project docs carry the conventions, as usual). Use your judgment within it: when the work is clear and low-risk, just do it; when it needs a product or design call, pull the user in.

Session reminders:
- **Edit in `./worktree`**, not in nido's tree. The REPL / app / DB are nido-managed — connect to the running services, don't start your own.
- **Don't touch Notion** — nido already set the ticket's Notion status when this session was provisioned; the triage stage owns the Notion contract, not this one.
- If what you find contradicts the prior stage's findings (the bug doesn't reproduce, the root cause is elsewhere), say so plainly and reorient with the user rather than forcing the earlier hypothesis.

  Check which layer the contradiction is at. If the code merely differs from what triage expected, amend your design record and carry on. If it invalidates the *design* — the shape you committed to cannot hold — supersede the record rather than editing it (`/design` §5), and treat the open question as a blocker below.

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

- If this is a **findings round**, mark each item resolved the moment its fix
  lands (`bb nido:findings:resolve :project brian :ws <ws-id> :items [<id>] :by <ref>`) —
  the board's open-findings badge and the round's completeness both read that
  tracker. Leftover open items only warn at merge; they don't block, but an
  unresolved item silently means "still outstanding."
