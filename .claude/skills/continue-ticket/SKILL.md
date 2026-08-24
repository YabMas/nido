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
| `:triage` (status `:planning`) | **implementation** | Read the latest `:triage` entry file (`~/.nido/projects/brian/tickets/<BR-####>/entries/NNNN-triage.md`) — the enriched brief, its `:design-frame`, the solution direction(s), and the `file:line` leads it found. The frame says whether this stage is a fix or a decision (Step 3). Start on those findings. |
| `:proposed-ticket` (Slack-sourced) | **implementation, with no triage behind it** | This workstream came in through `/triage-slack`, whose approved proposal creates the Notion ticket at `Status = Not started` — past the view that fires `/triage-bug`. So there is **no `:design-frame`, and there never will be**. Read the proposal's `:fix` / `:proposed-change` as a *proposal*, not a decision: it was scoped in twenty minutes by an agent that had not read the design. Infer the area yourself (`/design` §4) before committing to a shape, and take `:watch-out` seriously — a boundary-moving flag there is the design question, raised and unanswered. |
| `:design` with `:phases`, on a workstream reopened after a merge | **the next phase** | This workstream is mid-plan: an earlier phase already shipped and its gate opened. Read the design record's `:phases` and find the first one not yet landed — the ledger's `:merged` entries say how many have. **Do not re-derive the plan**; it is the same design record, and the phase you are on is the claim you are making. Check its `:habitable` against the code before you start: if the state you are in does not match what the record says is live, the plan is wrong or the previous phase did not finish, and that is a `:blocker`, not something to work around. See `/phase` §7. |
| `:findings` with open items | **address findings round N** | This workstream shipped, was reviewed on staging, and reopened. `bb nido:workstream:show` prints the round's items and the ws-id. Work the open items (severity `:blocker` first). Mark each resolved **the moment its fix lands**: `bb nido:findings:resolve :project brian :ws <ws-id> :items [<id>] :by <commit-or-PR>`. Then open a **follow-up PR** — see `/prepare-draft-pr` (its "Follow-up PR on a reopened workstream" section) — and ship with `nido ship` / `/drive-home`. |

If the ledger state is ambiguous (no entries, an unexpected status), say so and
check with the user — don't invent a stage.

## Step 3 — Set the stage marker

Advance the ticket to the stage you're starting. This also tells nido's coordinator the ticket is no longer a parked review item, which frees the provisioning Run's in-flight slot:

```bash
bb nido:ticket:status :project brian :br <BR-####> :status implementing
```

Then two ledger events, **in this order** — the durable artifacts this session
produces before any code.

### First the baseline: what is already there

Survey the area and append a `:baseline` event **before you decide anything**.
Read `/design` §4 for the doctrine; the short version is that every field must be
fillable without knowing the fix, because an inference made by someone who
already knows the fix is bent toward it.

Triage's `:design-frame` is a **provisional read, not a verdict**. It was made in
twenty minutes, often on a shallow route, by an agent that had not surveyed. Use
its `:trail` and `:violated` as leads — they say where to look — and let the
survey confirm or overturn the frame. **When they disagree, say so in the design
record's `:baseline :note`.** That disagreement is worth more than either reading
alone: it is the only thing that measures whether triage's design reading is
worth anything.

Scope by what *governs* the behaviour, not by the files you expect to touch —
`:violated` cites the checkable layer, which is a good starting point and a bad
boundary.

### Then the design: how the change stands to it

The `:design` event states what the change commits to, cites the baseline by
`:seq`, and resolves any triage `:squirrel` into a concrete effort — sizing
follows from the design, not the other way round. Claims about structure, not a
plan of action; there is no step list and the schema rejects one.

The `:baseline :relation` is the job, and now you can derive it rather than guess:

| relation | what you are doing |
|---|---|
| `:within` | every load-bearing property survives. A defect here is an **implementation** defect — a fix. `:shape` restates the design the code should have had; the invariants are the ones it already broke. |
| `:extends` | the change lands on an existing extension point, or adds one that contradicts nothing load-bearing. Say which, in `:at`. |
| `:revisit` | a load-bearing property has to change — a **decision**, and where a `:squirrel` came from. Name the properties in `:breaks`, say what the new shape is, and check whether it also needs `:extends` or `:challenges` against the *stance*. |

`:violated` names rules you are now on the hook for, so they usually belong in
`:invariants`.

```bash
cat > /tmp/design.edn <<'EDN'
{:format     :design
 :summary    "<2–4 sentences: what this change makes true of the system>"
 :shape      "<the structural claim: which parts exist, where the boundaries
                fall, what crosses them>"
 :invariants ["<what must hold once this lands — checkable; at least one>"]
 :standing   {:relation :conforms}   ; :extends / :challenges MUST carry :note
 :baseline   {:seq 2 :relation :within}  ; :seq = the baseline you just filed;
                                         ; :revisit MUST carry :breaks
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
   :needs   "<the decision you need from the user before proceeding>"
   ;; REQUIRED when that decision is a choice between branches you can see.
   ;; 2–6, in reading order; the gate letters them A/B/… and resumes you with
   ;; the one the human clicks. The append is REJECTED if you enumerate options
   ;; in :needs prose instead ("Option A … Option B …").
   :options [{:label        "<the branch, a few words — this is the button>"
              :summary      "<what taking it means, concretely>"
              :consequence  "<what it costs / forecloses>"
              :recommended? true}
             {:label   "<the other branch>"
              :summary "<what taking it means>"}]}
  EDN
  bb nido:ticket:append :project brian :br <BR-####> :kind blocker \
    :session <session> :run-id <run-id> :file /tmp/blocker.edn
  ```

  Omit `:options` only when the halt has no branches at all (a missing
  credential, an unavailable environment). A blocker whose answer is "A or B"
  and whose record says so in prose can only be answered by typing an essay
  back at it.

- If this is a **findings round**, mark each item resolved the moment its fix
  lands (`bb nido:findings:resolve :project brian :ws <ws-id> :items [<id>] :by <ref>`) —
  the board's open-findings badge and the round's completeness both read that
  tracker. Leftover open items only warn at merge; they don't block, but an
  unresolved item silently means "still outstanding."
