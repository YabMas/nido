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

## Step 1.5 — Read the ticket. Always, before the ledger.

**Whatever the ledger holds, read the ticket itself first.** It is one command,
and skipping it is how a session spends an hour deriving a scope the ticket
states in five bullets.

```bash
PAGE=$(bb -e "(:notion-page-id (:event-payload (clojure.edn/read-string (slurp \"./run-link/run.edn\"))))")
notion page markdown "$PAGE"
notion comment list "$PAGE" --all
```

Reach Notion through the **`notion` CLI**, never `bb notion:*` and never
`WebFetch`. `bb notion:*` reads `NOTION_TOKEN`, which a coordinator-spawned Run
under launchd does not inherit — `NOTION_TOKEN or NOTION_API_TOKEN env var
required` means *this shell carries no token*, **not** that you have no Notion
access. The CLI carries its own credential. See
`~/Code/nido/docs/reference/notion-access.md`.

On a pickup, nido has usually already transcribed the ticket onto the ledger as
a `:ticket` entry at provision time (`nido.coordinator.brief`) — read that, and
treat the live ticket as authoritative where they differ.

This is not only for the empty ledger. A triage entry is a twenty-minute
reading of the ticket, not the ticket; where they disagree, the ticket wins and
that disagreement is worth recording. **The one thing you may never do is halt
for scope without having read the body and the comments.**

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
| *nothing, or only `:ticket`* | **implementation, from the ticket itself** | This workstream was handed to nido directly (`bb nido:pickup`), which bypasses triage on purpose. There is **no `:design-frame` and there never will be** — an empty ledger here is the expected state, not ambiguity, and not a reason to stop. The ticket body you read in Step 1.5 **is** the brief. Author an `:intent` from it (`/design` §7), then survey and proceed exactly as the `:triage` row does, minus the triage findings. Where the ticket is genuinely ambiguous, halt on *that* — quoting the line — not on the ledger being empty. |
| `:triage` (status `:planning`) | **implementation** | Read the latest `:triage` entry file (`~/.nido/projects/brian/tickets/<BR-####>/entries/NNNN-triage.md`) — the enriched brief, its `:design-frame`, the solution direction(s), and the `file:line` leads it found. The frame says whether this stage is a fix or a decision (Step 3). Start on those findings. |
| `:proposed-ticket` (Slack-sourced) | **implementation, with no triage behind it** | This workstream came in through `/triage-slack`, whose approved proposal creates the Notion ticket at `Status = Not started` — past the view that fires `/triage-bug`. So there is **no `:design-frame`, and there never will be**. Read the proposal's `:fix` / `:proposed-change` as a *proposal*, not a decision: it was scoped in twenty minutes by an agent that had not read the design. Infer the area yourself (`/design` §4) before committing to a shape, and take `:watch-out` seriously — a boundary-moving flag there is the design question, raised and unanswered. |
| `:design` with `:phases`, on a workstream reopened after a merge | **the next phase** | This workstream is mid-plan: an earlier phase already shipped and its gate opened. Read the design record's `:phases` and find the first one not yet landed — the ledger's `:merged` entries say how many have. **Do not re-derive the plan**; it is the same design record, and the phase you are on is the claim you are making. Check its `:habitable` against the code before you start: if the state you are in does not match what the record says is live, the plan is wrong or the previous phase did not finish, and that is a `:blocker`, not something to work around. See `/phase` §7. |
| `:blocker` (unanswered) | **check the halt against the ticket, then either resume or wait** | A previous session stopped here. Before treating that as a live gate, test its `:needs` against the ticket you read in Step 1.5. A halt that asks what the ticket already states — or that asks for Notion access, which `notion` has — is **stale, not open**: say so plainly, append an `:intent` citing the ticket line that settles it, and carry on. Only a halt whose `:needs` the ticket genuinely does not answer is a real gate, and then you wait rather than picking a branch yourself. |
| `:blocker-answered` | **the branch the human chose** | A previous session halted on a choice and died before the answer arrived; nido recorded the human's pick on the ledger rather than losing it. The entry names the letter, the branch and the `:blocker-seq` it answers — read that blocker for the full framing, then **proceed on the chosen branch**. It is a decision, already made: do not re-open it or re-derive the alternatives. If the branch turns out not to hold once you are in the code, that is a NEW `:blocker` naming what broke — see below. |
| `:design-approved` | **implement the design it names** | A human granted this design and nido recorded the grant rather than leaving it in a resume argument that dies with the session. The entry names the design by `:seq` — read THAT record, not the latest one, and build what it commits to. It is a decision already made: do not re-derive the shape or re-open the alternatives it rejected. Before you start, check it still stands (`bb nido:land:check` answers the same question the landing gate will ask); if the survey under it was retracted while the resume was in flight, you are on the row below instead. |
| `:retraction` | **re-establish the premise, then come back** | Somebody found the record this work stands on to be untrue, and said so with a counterexample. Read the retraction: `:retracts` names the entry, `:because` and `:evidence` say what refutes it. **Do not continue the implementation on it** — that is the whole reason the entry exists. Survey the area again (`/design` §4), verify it (`bb nido:review:baseline`), then supersede the design so it cites the corrected survey, re-run `bb nido:review:design`, and take the new decision back to the human for approval. If the retraction turns out to be wrong about the code, that is a `:blocker` naming what it got wrong — you do not silently proceed past one. |
| `:findings` with open items | **address findings round N** | This workstream shipped, was reviewed on staging, and reopened. `bb nido:workstream:show` prints the round's items and the ws-id. Work the open items (severity `:blocker` first). Mark each resolved **the moment its fix lands**: `bb nido:findings:resolve :project brian :ws <ws-id> :items [<id>] :by <commit-or-PR>`. Then open a **follow-up PR** — see `/prepare-draft-pr` (its "Follow-up PR on a reopened workstream" section) — and ship with `nido ship` / `/drive-home`. |

If the ledger state is genuinely ambiguous — an unexpected status, or a latest
entry that matches no row — say so and check with the user rather than
inventing a stage. **An absent ledger is not that case**; it has its own row
above, and routing a pickup to "check with the user" is how a ticket that was
explicitly handed to nido to drive comes back unstarted.

## Step 3 — Set the stage marker

Advance the ticket to the stage you're starting. This also tells nido's coordinator the ticket is no longer a parked review item, which frees the provisioning Run's in-flight slot:

```bash
bb nido:ticket:status :project brian :br <BR-####> :status implementing
```

Then the ledger events this session owes before any code. **`/design` §7 owns
them and what goes in them — read it and follow it there.** Three appends, in
order: `:intent`, then `:baseline`, then `:design`, each written to a temp file
and appended with `bb nido:ticket:append :project brian :br <BR-####> :kind
<kind> :session <session> :run-id <run-id> :file /tmp/<kind>.edn`.

**With the survey verified between the second and the third**, not after both:

```bash
bb nido:review:baseline    # loops until the code stops refuting the survey
# …read the FINAL seq back; the loop appends a superseding :baseline per amendment
bb nido:review:design      # then decide against the survey that held
```

This is not optional polish and the order is not cosmetic. The design round
looks for a `sufficient` review *of the seq the design cites*, so a design
written before the survey was verified cites a seq whose only reviews are the
`:falsified` ones that caused the corrections — `bb nido:review:design` answers
`:premise-unverified` and no amount of running it later helps. The repair is a
superseding design. `/design` §7 has the full reason.

Neither round writing code and neither touching the working copy: they judge the
records, amend them, and hand you what only you can settle.

This section used to restate the design record's shape, and drifted from the
schema the moment `:intent` became required — an agent following the copy here
wrote a record the append boundary rejected. It is a pointer now for that
reason: there is one write contract, and one place that teaches it.

Two things about the appends are specific to *this* stage, and are not in
`/design`:

**Skip `:intent` only when a `:triage` entry already states the goal** — the
design may cite that entry instead. A pickup has neither, so it authors one from
the ticket body you read in Step 1.5. Nothing else is citable.

**Triage's `:design-frame` is a provisional read, not a verdict.** It was made in
twenty minutes, often on a shallow route, by an agent that had not surveyed. Use
its `:trail` and `:violated` as leads — they say where to look — and let the
survey confirm or overturn the frame. **When they disagree, say so in the design
record's `:baseline :note`.** That disagreement is worth more than either reading
alone: it is the only thing that measures whether triage's design reading is
worth anything. The same holds when the *ticket* disagrees with triage — there,
the ticket wins outright.

If you cannot yet state the shape or name an invariant, that is a finding rather
than a formality — the design question triage deferred is still open. Put it in
`:open` and raise it with the user before writing code.

## Step 4 — Do the work (the harness owns the *how*)

You now know the stage and have the prior stage's findings in front of you. **How to actually do it is not this command's concern** — it's known and managed by the wider harness: brian's working-model rules, REPL discipline, lane/agent delegation, commit conventions (this is a full brian session — your nido briefing and the project docs carry the conventions, as usual). Use your judgment within it: when the work is clear and low-risk, just do it; when it needs a product or design call, pull the user in.

Session reminders:
- **Edit in `./worktree`**, not in nido's tree. The REPL / app / DB are nido-managed — connect to the running services, don't start your own.
- **Don't WRITE to Notion** — nido already set the ticket's Notion status when this session was provisioned; the triage stage owns the Notion contract, not this one. Reading is not only allowed but required (Step 1.5).
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

  **Before filing a `:blocker` about scope, check you actually read the ticket
  (Step 1.5).** A halt whose `:needs` is "what does this ticket mean" and whose
  session never opened the body is not a decision for the human — it is the
  first step, skipped. Nor is a credential a blocker on its own: `bb notion:*`
  reporting no token means *this shell* has none, and `notion` is right there.

- If this is a **findings round**, mark each item resolved the moment its fix
  lands (`bb nido:findings:resolve :project brian :ws <ws-id> :items [<id>] :by <ref>`) —
  the board's open-findings badge and the round's completeness both read that
  tracker. Leftover open items only warn at merge; they don't block, but an
  unresolved item silently means "still outstanding."
