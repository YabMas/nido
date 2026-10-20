---
name: triage-slack
description: Investigate a Slack-reported bug against the running brian session, then conclude — a product-terms ticket nido files for verification and links in the thread, or a doubt nido puts to Jaap in the thread — and park the technical analysis for human review. Writes Slack and Notion only through nido's own verbs.
---

# triage-slack skill

> **Harness-side skill, owned by nido.** It lives at `nido/.claude/skills/triage-slack/` and is injected as a native harness skill into every spawned session's composed `.claude/skills/` (see `nido.session.launcher/compose-claude-dir!`), so claude resolves `/triage-slack` independent of the target project's checked-out branch.
>
> **State and the proposal live in the nido ticket record**, keyed by the Slack event id. This skill investigates, writes ONE `:proposed-ticket` carrying two texts for two readers, and hands it to `bb nido:ticket:conclude`, which does every outward write deterministically. The skill itself contains no `notion` CLI call and no direct Slack API call — only the `bb nido:*` verbs below.

## What happens to a report

1. **Pickup** — you react :eyes: on the reported message (Step 1), so the reporter sees nido has it.
2. **Conclusion** — your proposal concludes one of two ways:
   - **A ticket** (`:product`) — `conclude` files a Notion page at Status *Needs verification*, Lifecycle *Development*, your App Domain, Ball Holder Jaap, whose body is ONLY your product-terms text, and posts its link in the thread. No human gate before this.
   - **A doubt** (`:doubt`) — `conclude` posts your short reasoning in the thread, mentioning Jaap. Nothing is filed.
3. **Park** — either way the workstream parks in the gate with your technical analysis. Jaap reads it there:
   - **Apply** (ticket only) — nido adds your technical analysis to the page as a collapsed *Engineering notes & history* toggle and starts the `:plan-bug` implementation leg. Apply on a doubt is refused.
   - **Reply** — resumes you with feedback (see "Resume behaviour"). This is how a doubt is overridden: Jaap replies, you revise into a ticket.
   - **Drop** — nido closes it.

Nothing implements the report before Jaap accepts the analysis: filing holds the ticket so the Notion-side `/triage-bug` and `bb nido:pickup` both stand down until then.

## When this fires

The nido coordinator fires `/triage-slack <payload>` when someone reacts to a message in the watched channel to flag it. The envelope payload includes:
- `{{event/id}}` — the ticket-record key, e.g. `slack-C123-1718000000.000123` (`slack-<channel>-<ts>`)
- `{{event/url}}` — the Slack permalink to the flagged message
- the message text (the report itself)

There is no Notion page yet — do not attempt to fetch one.

The session is a **`:full` session**: the brian REPL and app are running, so you can investigate GROUNDED against live behavior (read-only — reproduce and locate root cause, but do not edit files, run migrations, or mutate data).

The Run record lives at `./run-link/` (symlink to `~/.nido/coordinator/runs/<run-id>/`); `./run-link/run.edn` `:event-payload` carries `:channel` and `:ts`.

## Lifecycle: ticket status

Keyed by the **Slack event id** (`{{event/id}}`).

You never set a status. nido derives it from what this run and the ticket's ledger already say:

| Status | Holds while | Means |
|---|---|---|
| `:investigating` | this run is open and no proposal waits | Investigating, drafting the proposal |
| `:awaiting-input` | a `:proposed-ticket` you appended waits for a person | Proposal concluded and appended. Parked for Jaap in the gate. |

`bb nido:ticket:open` **creates the record**, and `conclude` needs that record — so Step 1's open must come before Step 3.

## Step 1 — Acknowledge, then investigate (autonomous)

1. Read the envelope for `{{event/id}}` (call it `<slack-id>`), `{{event/url}}`, and the message text — this text IS the report.
2. **Acknowledge and record** — your first two actions:

   ```bash
   bb nido:slack:react :channel <channel> :ts <ts> :name eyes
   bb nido:ticket:open :project brian :br <slack-id> :url <url> :title "<first line of the message>" :opened-by <trigger-name>
   ```

   `<channel>` and `<ts>` come from `./run-link/run.edn` `:event-payload` (or split `<slack-id>`). `react` tolerates `already_reacted`; if it fails otherwise, carry on — the ack is a courtesy, not the work.

3. Investigate the brian codebase at `./worktree` and the live app/REPL — READ-ONLY. Strategy:
   - Heuristic grep for module/feature names from the message text.
   - Reproduce the report against the running app where feasible; use the REPL to inspect real state/behavior rather than guessing.
   - Read 2–5 relevant files, ~500 lines total cap. Don't go deeper than needed.
   - If the report mentions a stack trace or specific error message, grep for it verbatim first.
   - Stop once you have enough to write a real, specific ticket — this is triage, not a fix.
3. **Classify** the report as one of exactly two `:ticket-type` values:
   - **`"bug"`** — a defect: something is broken, regressed, or not working as intended.
   - **`"improvement"`** — a request for new or better behavior. Feature requests are `"improvement"`, not a third type.
   - When genuinely borderline, make the call and record the uncertainty in `:watch-out` rather than stalling on the label.
5. **Decide the conclusion** — ticket or doubt:
   - **Ticket** when the report describes something real: you reproduced it, or the code plainly allows it, or it is a clear, scoped ask.
   - **Doubt** when you have a concrete reason to think it is not a real problem as reported: it works as designed, you could not reproduce it and the code rules it out, it is already fixed on main, it is a duplicate, or it is a data/config issue rather than a product defect. "I didn't get to the bottom of it" is NOT a doubt — file the ticket and say what is unresolved in `:watch-out`.
6. Determine, for the report:
   - **Is this a local fix, or does it move a boundary?** See "The design reading" below.
   - **For a `"bug"`:** what part of the system is affected, and what's the likely root cause?
   - **For an `"improvement"`:** don't manufacture a root cause — *scope the change*: where it would land, its rough size, the touchpoints.
   - A concise title, in product terms — it becomes the Notion page title.
   - The **App Domain** it concerns (see "App Domain") and a Notion **Priority** guess, or `nil` if unsure.

### The design reading

**Read the stance before you scope anything:**

```bash
cat .claude/skills/design/stances/default.md
```

Two things make this matter more here than it looks.

**This path skips triage.** The page lands at *Needs verification*, but held:
`/triage-bug` never runs on it, and accepting your analysis starts implementation
directly. Nothing downstream gives this work a design reading. Whatever design
context the analysis carries is the context it will ever have.

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
  implementing.* That is what lets the human approve the ticket without also,
  silently, approving the shape.

One clause in `:watch-out` when it applies, nothing when it doesn't. A proposal
that flags every change as boundary-moving is as useless as one that flags none.

## Step 2 — Compose the proposal

Write a `ProposedTicket` EDN map (schema: `nido.coordinator.report/ProposedTicket`) to a temp `.edn` file. It carries **two texts for two readers**:

- **The technical analysis** — `:problem`/`:root-cause`/`:fix` (bug) or `:request`/`:proposed-change`/`:rationale` (improvement), plus `:watch-out`. For Jaap, in the gate. Compact: 1–2 lines each; your investigation's depth shows as confidence, not length.
- **The conclusion** — exactly one of:
  - `:product` + `:app-domain` — the ticket text, filed as the Notion page body. For someone who uses brian and never opens the code.
  - `:doubt` — 1–3 sentences to Jaap in the thread: why you think this is not a real problem, with the one observation that convinced you.

```clojure
;; ── ticket-type "bug" ──
{:format      :proposed-ticket
 :title       "Long links run past the chat bubble in the Teaching Assistant"
 :ticket-type "bug"
 :priority    "3 - Could"     ; a Notion Priority option below, or nil
 :source-url  "…"             ; the ACTUAL Slack permalink (NOT the literal "{{event/url}}")
 ;; technical analysis — gate only
 :problem     "1-2 lines: what's wrong + who it affects."
 :root-cause  "1-2 lines: the cause + evidence — cite the commit; say 'verified live in REPL' when you did."
 :fix         "1-2 lines: fix shape + rough size + the CONCRETE file:line targets."
 :watch-out   "optional caveat or the boundary-moving flag; nil when none"
 ;; conclusion — EITHER this pair …
 :app-domain  "Teacher"
 :product     {:where           "The Teaching Assistant chat sidebar, as a teacher."
               :what-happens    "Long words, links, code and tables run past the right edge of the message bubble."
               :expected        "Everything stays inside the bubble; wide content scrolls sideways."
               :steps           ["Open the Teaching Assistant chat."
                                 "Ask for an answer containing a long link or a table."]   ; optional
               :who-is-affected "Any teacher using the chat; the overflowing text is unreadable."}
 ;; … OR, instead of :app-domain + :product:
 ;; :doubt "This looks like browser zoom at 300%: at normal zoom the bubble wraps fine, and the reporter's screenshot shows the zoom badge."
 }

;; ── ticket-type "improvement" ──  same conclusion fields; technical fields are
;; :request / :proposed-change / :rationale (+ optional :watch-out).
;; In :product, :what-happens is how it works today and :expected is what is asked for.
```

### Writing `:product` — product terms only

The page body is read by product people, QA and the reporter. It says **what someone using brian meets**, never why:

- **No cause.** Not "because the bubble lacks overflow-wrap", not "the query is missing a filter". The cause lives in `:root-cause`.
- **No code.** No file, namespace, function, CSS class, table, endpoint, flag or PR number. Name things as the UI names them ("the Teaching Assistant chat", "the quiz results page") — German label beside the English one where the UI has both.
- **No properties.** Title, type, priority and app domain are set as page properties; don't repeat them in the sections.
- `:steps` only when you know a sequence that shows it; omit the key otherwise.

`append` **rejects** a `:product` section containing a backtick, a path with a source-file extension (`.clj`, `.css`, `.js`, …) or a `name:123` coordinate — but it cannot catch a cause written in plain words. That part is on you.

### App Domain

Exactly one of `"Student"`, `"Teacher"`, `"Backend"`, `"Misc"` — where the reporter met the problem (a teacher-facing screen is `"Teacher"` even if the cause is backend). Required with `:product`, absent with `:doubt`.

### Priority guidance

The Notion Priority options (select field, exact strings — note the en-dash on "0"):

- `"0 – Release Blocker"`
- `"1 - Must"`
- `"2 - Should"`
- `"3 - Could"`
- `"4 - Would"`
- `"5 - Wont"`

Use `nil` for `:priority` if you're not confident — don't guess just to fill the field.

The schema is closed and `:ticket-type`-dispatched: only `"bug"` and `"improvement"`, no stray keys, the right technical fields for the type, and exactly one of `:product` (with `:app-domain`) or `:doubt`. `append` rejects anything else with an explain dump — fix and retry.

## Step 3 — Append, conclude, park

```bash
bb nido:ticket:append   :project brian :br <slack-id> :kind proposed-ticket :session <session-id> :run-id <run-id> :file <temp.edn>
bb nido:ticket:conclude :project brian :br <slack-id>
```

(Derive `<session-id>` from the cwd / session name and `<run-id>` from the `./run-link/` symlink — it points at `~/.nido/coordinator/runs/<run-id>/`.)

`conclude` prints what it did — `filed <url> (link posted)`, `already-filed`, or `doubted`. If it exits non-zero, **still park** (exit with the proposal appended) and say why in your final message; the gate's Apply retries filing, and nido never files twice:

- `filing-interrupted` — an earlier filing may have created a page nido could not record. Do NOT retry; Jaap has to check Notion.
- `auth` / `http` / `network` / `server` — Notion or Slack failed; Apply will retry.

Then **exit**. Exiting with a proposal nobody has answered is the park mechanism — the coordinator parks your run at `:awaiting-review`, and the gate shows Jaap the proposal.

## Resume behaviour (= revise)

When the session is resumed (Jaap used **Reply** in the gate), the latest user message is revision feedback, not a fresh brief. Treat every resume as a **revise** cycle:

1. Read the feedback. Treat it as guidance for another investigation pass.
2. Re-investigate as needed (Step 1 — skip the react and the open), compose a fresh proposal (Step 2), and run Step 3 again. The append is additive; the latest entry is what the gate reads.

What the revision can and cannot change:

- **A doubt overridden** ("it's real, file it") — conclude with `:product`; `conclude` files it then.
- **A ticket already filed** — `conclude` will not file again or rewrite the page. Your revised technical analysis is what Apply writes; if the product text itself was wrong, say so in your final message so Jaap can edit the page.

If you need to confirm what you last proposed:

```bash
bb nido:workstream:show :project brian :ref <slack-id>
```

If the reply is `drop`/`dismiss`/`cancel` — do nothing. Those are gate actions nido handles directly; you should not normally be resumed for them.
