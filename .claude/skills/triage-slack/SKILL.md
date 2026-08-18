---
name: triage-slack
description: Investigate a Slack-reported bug against the running brian session, propose a grounded ticket, and park for human approval. Never writes to Notion or Slack — approval and ticket creation happen deterministically in nido.
---

# triage-slack skill

> **Harness-side skill, owned by nido.** It lives at `nido/.claude/skills/triage-slack/` and is injected as a native harness skill into every spawned session's composed `.claude/skills/` (see `nido.session.launcher/compose-claude-dir!`), so claude resolves `/triage-slack` independent of the target project's checked-out branch.
>
> **State + the proposal live in the nido ticket record**, keyed by the Slack event id — not in Notion. There is **no Notion ticket yet**: this skill's whole job is to produce a grounded `:proposed-ticket` for a human to approve. Notion-page creation and the Slack link-back happen elsewhere, deterministically, when the human clicks **Apply** in the gate.

## When this fires

The nido coordinator fires `/triage-slack <payload>` when a `:slack-message` reaction trigger matches (someone reacted to a message in the watched channel to flag it as a bug). The source is **always Slack** — there is no Notion branch in this skill.

The envelope payload includes:
- `{{event/id}}` — the ticket-record key, e.g. `slack-C123-1718000000.000123` (`slack-<channel>-<ts>`)
- `{{event/url}}` — the Slack permalink to the flagged message
- the message text (the bug report itself)

There is no `:page-id` and no Notion page — do not attempt to fetch one.

The session is a **`:full` session**: the brian REPL and app are running, so you can investigate the report GROUNDED against live behavior (read-only — reproduce and locate root cause, but do not edit files, run migrations, or mutate data).

The Run record lives at `./run-link/` (symlink to `~/.nido/coordinator/runs/<run-id>/`).

## Lifecycle: ticket status

Keyed by the **Slack event id** (`{{event/id}}`), not a `BR-####` — there is no Notion ticket until a human approves.

| Status | Set by | Means |
|---|---|---|
| `:investigating` | `bb nido:ticket:status … :status investigating` (Step 1), and revision passes | Reading the report, investigating the brian codebase/app, drafting a proposed ticket |
| `:awaiting-input` | `bb nido:ticket:status … :status awaiting-input` | Proposal written + appended to the record. Parked for human review in chat. |

**There is no `:triaged`/`:dismissed`/apply step in this skill.** Approval, Notion-page creation, BR-#### association, and the Slack link-back are all nido-side (see "No apply step" below) — this skill's only terminal action is parking at `:awaiting-input` and exiting.

```bash
bb nido:ticket:status :project brian :br <slack-id> :status investigating
```

`bb nido:ticket:status` **creates the record** on first call (there's no separate "open" step for a Slack-only ticket) and is the durable state nido's park/resume wiring reads.

## Step 1 — Investigate (autonomous)

1. Read the envelope for `{{event/id}}` (the ticket-record key, call it `<slack-id>`), `{{event/url}}` (the Slack permalink), and the message text (the brief — there is no page to fetch, this text IS the report).
2. **Set status `:investigating` (first recorded action):**

   ```bash
   bb nido:ticket:status :project brian :br <slack-id> :status investigating
   ```

3. Investigate the brian codebase at `./worktree` and the live app/REPL — READ-ONLY (do not edit files, do not mutate data). Strategy:
   - Heuristic grep for module/feature names from the message text.
   - Reproduce the report against the running app where feasible; use the REPL to inspect real state/behavior rather than guessing.
   - Read 2–5 relevant files, ~500 lines total cap. Don't go deeper than needed.
   - If the report mentions a stack trace or specific error message, grep for it verbatim first.
   - Stop once you have enough to write a real, specific ticket — this is triage, not a fix.
4. **Classify** the report as one of exactly two `:ticket-type` values:
   - **`"bug"`** — a defect: something is broken, regressed, or not working as intended.
   - **`"improvement"`** — a request for new or better behavior. Feature requests are `"improvement"`, not a third type.
   - When genuinely borderline (e.g. "this is confusing" could be a UX bug or a feature ask), make the call and record the uncertainty in `:watch-out` rather than stalling on the label.
5. Determine, for the report:
   - Is this actually reproducible / a real bug worth a ticket (bug case), or a real, scoped ask (improvement case)? (If clearly not worth a ticket, say so plainly in the proposal body and let the human `dismiss` — see "Resume behaviour".)
   - **Is this a local fix, or does it move a boundary?** See "The design reading" below.
   - **For a `"bug"`:** what part of the system is affected, and what's the likely root cause?
   - **For an `"improvement"`:** don't manufacture a root cause — instead *scope the change*: locate where it would land, assess its rough size, and note the touchpoints.
   - A concise, enriched title.
   - A Notion Priority guess, or `nil` if unsure (see "Priority guidance" below).

### The design reading

**Read the stance before you scope anything:**

```bash
cat .claude/skills/design/stances/brian.md
```

Two things make this matter more here than it looks.

**This path skips triage.** An approved proposal creates its Notion ticket at
`Status = Not started`, past the `Needs verification` view that fires
`/triage-bug` — so nothing downstream will ever give this work a design reading.
The next thing to touch it is the implementer. Whatever design context the
ticket carries is the context it will ever have.

**And `:proposed-change` / `:fix` are design proposals in miniature.** "Change
shape + rough size + concrete file:line touchpoints" is a commitment about where
something belongs, written after twenty minutes in the code, which a human then
approves with one click — and that click reads as endorsement of the shape.

So make the one distinction, and put it in `:watch-out` when it is the second:

- **A local fix** — the intended shape is clear from the surrounding code and
  this restores it. Most bugs. Nothing to add.
- **It moves a boundary** — the change is really about where something lives,
  what owns a piece of state, or whether two things should be one. Say so in
  `:watch-out`, plainly: *this moves the X boundary; worth a design call before
  implementing.* That is what `:watch-out` is for, and it is what lets the human
  approve the ticket without also, silently, approving the shape.

Do not add fields or prose sections for this. One clause in `:watch-out` when it
applies, nothing when it doesn't. A proposal that flags every change as
boundary-moving is as useless as one that flags none.

## Step 2 — Propose (compose + append + park)

Compose a `ProposedTicket` EDN map (schema: `nido.coordinator.report/ProposedTicket`) to a temp `.edn` file. The map is **compact and structured**, not a freeform essay — keep doing the deep investigation in Step 1, but distill it down to these 1–2-line fields; the depth of your investigation shows up as *confidence* in what you write, not as length.

The schema is a **closed, `:ticket-type`-dispatched multi-schema with no default** — emit exactly one of these two shapes, matching the classification from Step 1:

```clojure
;; ── if ticket-type "bug" (a defect) ──
{:format      :proposed-ticket
 :title       "concise enriched title, no trailing punctuation"
 :ticket-type "bug"
 :priority    "2 - Should"   ; one of the Notion Priority options below, or nil if unsure
 :source-url  "…"            ; the ACTUAL Slack permalink from the envelope (NOT the literal "{{event/url}}")
 :problem     "1-2 lines: what's wrong + who it affects. No prose sections."
 :root-cause  "1-2 lines: the cause + evidence — cite the root-cause commit and say 'verified live in REPL' when you confirmed it there."
 :fix         "1-2 lines: fix shape + rough size (e.g. 'revert-shaped, small') + the CONCRETE file:line targets. This is the only field carrying file:line refs — it must be actionable."
 :watch-out   "optional: a real caveat / scope question (e.g. the reporter may mean a different screen), or the boundary-moving flag from The design reading. nil when there is none."}

;; ── if ticket-type "improvement" (a request for new/better behavior; feature requests too) ──
{:format          :proposed-ticket
 :title           "concise enriched title, no trailing punctuation"
 :ticket-type     "improvement"
 :priority        "3 - Could"   ; one of the Notion Priority options below, or nil if unsure
 :source-url      "…"           ; the ACTUAL Slack permalink from the envelope (NOT the literal "{{event/url}}")
 :request         "1-2 lines: what's being asked for + the current gap."
 :proposed-change "1-2 lines: the change shape + rough size + CONCRETE file:line touchpoints (the actionable field — where the scoped-in-Step-1 change would land)."
 :rationale       "1-2 lines: why it's worth doing / who benefits."
 :watch-out       "optional: a real caveat / scope question, or the boundary-moving flag from The design reading — an improvement that moves a boundary should say so here. nil when there is none."}
```

The **only** accepted `:ticket-type` values are `"bug"` and `"improvement"` — any other value (including `"feature"`) is rejected by `append`, so label feature requests `"improvement"`. Emitting the wrong body fields for the chosen type (e.g. `:root-cause` on an `"improvement"`, or `:request` on a `"bug"`) also fails validation — both shapes are closed maps, so stray/extra keys are rejected too; fix and retry.

Append it to the ticket record and re-park:

```bash
bb nido:ticket:append :project brian :br <slack-id> :kind proposed-ticket :session <session-id> :run-id <run-id> :file <temp.edn>
bb nido:ticket:status :project brian :br <slack-id> :status awaiting-input
```

(Derive `<session-id>` from the cwd / session name and `<run-id>` from the `./run-link/` symlink — it points at `~/.nido/coordinator/runs/<run-id>/`.)

The `append` **validates** the EDN against `ProposedTicket` and **rejects** a malformed proposal (non-zero exit + an explain dump) — fix and retry until it's accepted.

Then **exit**. Exiting right after `:awaiting-input` is the park mechanism — the coordinator maps `:awaiting-input` to `:awaiting-review` in the gate, where the human sees the proposal and can Apply / Reply / Drop.

### Priority guidance

The Notion Priority options (select field, exact strings — note the en-dash on "0"):

- `"0 – Release Blocker"`
- `"1 - Must"`
- `"2 - Should"`
- `"3 - Could"`
- `"4 - Would"`
- `"5 - Wont"`

Use `nil` for `:priority` if you're not confident — don't guess just to fill the field.

## No apply step — this skill never writes to Notion or Slack

**Hard contract: this skill contains no `notion` CLI call and no Slack-write call, anywhere, ever.** Unlike `triage-bug`, there is no "apply" branch here at all. When a human clicks **Apply** in the gate (or replies `apply` in chat), that action is handled **entirely by nido**, outside this skill's session:

- nido creates the Notion page from the stored `:proposed-ticket` payload (`Status = Not started`)
- nido associates the new `BR-####` with this workstream
- nido posts the ticket link back to the originating Slack thread
- nido completes the ticket record so the parked session sweeps to done

None of that is this skill's job, and this skill's session never sees it happen (it has already exited). If you find yourself reaching for `notion page set`, `notion api PATCH`, or any Slack-posting command inside this skill — stop. That's a sign the design has drifted; it belongs in nido's apply path, not here.

Likewise, **Drop/Dismiss are nido-side gate actions**, not something this skill implements — there is no dismiss step below.

## Resume behaviour (= revise)

When the session is resumed (the human used **Reply** with feedback in the gate), the latest user message is revision feedback, not a fresh brief. You'll typically see:

1. The original first message (the trigger payload — `{{event/id}}`, `{{event/url}}`, message text)
2. Your prior investigation + the proposed ticket you appended
3. The human's reply at the end — feedback on the proposal (e.g. "wrong module, check the export flow instead", "priority should be higher", "this isn't actually reproducible, look again at X")

Treat every resume as a **revise** cycle:

1. Read the feedback. Treat it as guidance for another investigation pass.
2. Set status back to `:investigating`:

   ```bash
   bb nido:ticket:status :project brian :br <slack-id> :status investigating
   ```

3. Re-investigate as needed given the feedback (Step 1), then compose and append an **updated** `:proposed-ticket` (Step 2) — a fresh EDN file, appended the same way. The append is additive; the latest entry is what the gate reads.
4. Set status `:awaiting-input` again and exit.

There's no hard limit on revise cycles, but the per-Run wall-clock budget caps the total. If you need to confirm what you last proposed, re-read it with:

```bash
bb nido:ticket:show :project brian :br <slack-id>
```

(prints `meta.edn`; the latest `:proposed-ticket` entry is on the workstream ledger the record points at).

If the human's reply is `drop`/`dismiss`/`cancel` — do nothing. Those are gate actions nido handles directly on the workstream; you should not normally be resumed for them at all, and there is no skill-side branch to run.
