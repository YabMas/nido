---
name: triage-slack
description: Read an owled Slack thread, investigate it against the running brian session, then conclude — one or more product-terms tickets (each a bug or an improvement) that nido files for verification and links in the thread, or a doubt nido puts to Jaap in the thread — and park each ticket's technical analysis for human review. Writes Slack and Notion only through nido's own verbs.
---

# triage-slack skill

> **Harness-side skill, owned by nido.** It lives at `nido/.claude/skills/triage-slack/` and is injected as a native harness skill into every spawned session's composed `.claude/skills/` (see `nido.session.launcher/compose-claude-dir!`), so claude resolves `/triage-slack` independent of the target project's checked-out branch.
>
> **State and the verdict live in the nido ticket record**, keyed by the thread's Slack id. This skill reads the thread, investigates, writes ONE `:proposed-tickets` verdict — one or more tickets, each carrying two texts for two readers, or a doubt about the whole thread — and hands it to `bb nido:ticket:conclude`, which does every outward write deterministically. The skill itself contains no `notion` CLI call and no direct Slack API call — only the `bb nido:*` verbs below.

## What happens to a report

1. **Pickup** — you react :eyes: on the owled message (Step 1), so the people in the thread see nido has it.
2. **Conclusion** — your verdict concludes one of two ways:
   - **Tickets** (`:tickets`) — one entry per distinct problem or ask the thread raises. `conclude` gives each ticket a workstream of its own, files a Notion page for it at Status *Needs verification*, Lifecycle *Development*, its App Domain, Ball Holder Jaap, whose body is ONLY that ticket's product-terms text, and posts each link in the thread. No human gate before this. The thread's own workstream is then closed — it has handed everything on.
   - **A doubt** (`:doubt`) — `conclude` posts your short reasoning in the thread, mentioning Jaap. Nothing is filed, and the thread's workstream stays parked.
3. **Park** — each ticket's workstream parks in the gate with that ticket's technical analysis. Jaap reads each on its own:
   - **Apply** — nido adds the technical analysis to that ticket's page as a collapsed *Engineering notes & history* toggle and starts it as a person's start, its impl session coming up at once, for it alone. Its siblings stay gated.
   - **Dismiss** — nido closes that ticket's workstream.
   - A **doubt** parks on the thread's workstream instead, where **Reply** resumes you with feedback (see "Resume behaviour") — that is how a doubt is overridden. Apply on a doubt is refused.

Nothing implements a ticket before Jaap accepts its analysis: filing holds each ticket so the Notion-side `/triage-bug` and `bb nido:pickup` both stand down until then.

## When this fires

The nido coordinator fires `/triage-slack <payload>` when someone reacts :owl: to any message — top-level or reply — in any channel, public or private, that the bot has been added to. Once a thread has a workstream, a further owl on it starts nothing. The envelope payload includes:
- `{{event/id}}` — the thread's ticket-record key, e.g. `slack-C123-1718000000.000123` (`slack-<channel>-<root ts>`) — the thread's ROOT, whichever message was owled
- `{{event/url}}` — the Slack permalink to the thread's root
- the thread's transcript — root first, then every reply in order, each under its author's name (`name: text`, blank line between messages). **This is the report.** Who said what matters: a later reply can narrow, correct or add to the root.

There is no Notion page yet — do not attempt to fetch one.

The session is a **`:full` session**: the brian REPL and app are running, so you can investigate GROUNDED against live behavior (read-only — reproduce and locate root cause, but do not edit files, run migrations, or mutate data).

The Run record lives at `./run-link/` (symlink to `~/.nido/coordinator/runs/<run-id>/`); `./run-link/run.edn` `:event-payload` carries `:channel`, `:ts` (the root) and `:reacted-ts` (the owled message).

## Lifecycle: ticket status

Keyed by the **Slack event id** (`{{event/id}}`).

You never set a status. nido derives it from what this run and the ticket's ledger already say:

| Status | Holds while | Means |
|---|---|---|
| `:investigating` | this run is open and no proposal waits | Investigating, drafting the proposal |
| `:awaiting-input` | a verdict you appended waits for a person | Verdict concluded and appended. Parked for Jaap in the gate. |

`bb nido:ticket:open` **creates the record**, and `conclude` needs that record — so Step 1's open must come before Step 3.

## Step 1 — Acknowledge, then investigate (autonomous)

1. Read the envelope for `{{event/id}}` (call it `<slack-id>`), `{{event/url}}`, and the thread transcript — this transcript IS the report.
2. **Acknowledge and record** — your first two actions:

   ```bash
   bb nido:slack:react :channel <channel> :ts <reacted-ts> :name eyes
   bb nido:ticket:open :project brian :br <slack-id> :url <url> :title "<first line of the root message>" :opened-by <trigger-name>
   ```

   `<channel>` and `<reacted-ts>` come from `./run-link/run.edn` `:event-payload`. `react` tolerates `already_reacted`; if it fails otherwise, carry on — the ack is a courtesy, not the work.

3. Investigate the brian codebase at `./worktree` and the live app/REPL — READ-ONLY. Strategy:
   - Heuristic grep for module/feature names from the thread.
   - Reproduce the report against the running app where feasible; use the REPL to inspect real state/behavior rather than guessing.
   - Read 2–5 relevant files, ~500 lines total cap. Don't go deeper than needed.
   - If the report mentions a stack trace or specific error message, grep for it verbatim first.
   - Stop once you have enough to write real, specific tickets — this is triage, not a fix.
4. **Split, only where the thread raises distinct problems.** One ticket per thing that would be fixed or built on its own: a thread reporting a bug and, further down, asking for an unrelated improvement is two tickets. Several people confirming, narrowing or adding detail to the SAME problem is one ticket — fold their observations into it. When in doubt, one ticket. Never split one problem into steps of its fix.
5. **Classify** each ticket as one of exactly two `:ticket-type` values:
   - **`"bug"`** — a defect: something is broken, regressed, or not working as intended.
   - **`"improvement"`** — a request for new or better behavior. Feature requests are `"improvement"`, not a third type.
   - When genuinely borderline, make the call and record the uncertainty in `:watch-out` rather than stalling on the label.
6. **Decide the conclusion** — tickets or doubt:
   - **Tickets** when the thread describes something real: you reproduced it, or the code plainly allows it, or it is a clear, scoped ask.
   - **Doubt** when you have a concrete reason to think nothing in the thread is a real problem as reported: it works as designed, you could not reproduce it and the code rules it out, it is already fixed on main, it is a duplicate, or it is a data/config issue rather than a product defect. "I didn't get to the bottom of it" is NOT a doubt — file the ticket and say what is unresolved in `:watch-out`. A doubt covers the WHOLE thread; if one thing in it is real and another is not, file the real one and say why you left the other out in that ticket's `:watch-out`.
7. Determine, for each ticket:
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

## Step 2 — Compose the verdict

Write a `ProposedTickets` EDN map (schema: `nido.coordinator.report/ProposedTickets`) to a temp `.edn` file:

```clojure
{:format     :proposed-tickets
 :title      "<the thread in a few words>"
 :source-url "…"                       ; the ACTUAL thread permalink (NOT the literal "{{event/url}}")
 :tickets    [{:key "export-hangs"     ; lower-case, digits and dashes; unique in this verdict
               :ticket {…a ProposedTicket, below…}}
              {:key "csv-column-order"
               :ticket {…}}]}
;; … OR, instead of :tickets, a doubt about the whole thread:
;; {:format :proposed-tickets :title "…" :source-url "…"
;;  :doubt "This looks like browser zoom at 300%: at normal zoom the bubble wraps fine, and the reporter's screenshot shows the zoom badge."}
```

The `:key` names the ticket for as long as nido knows it — it scopes the ticket's own record id (`<slack-id>~<key>`). Pick a short, descriptive slug and never rename it on a revision: a renamed key is a new ticket.

Each `:ticket` is a `ProposedTicket` (schema: `nido.coordinator.report/ProposedTicket`) that **must carry `:product` + `:app-domain`** — a doubt lives on the verdict, never on one ticket. Each carries **two texts for two readers**:

- **The technical analysis** — `:problem`/`:root-cause`/`:fix` (bug) or `:request`/`:proposed-change`/`:rationale` (improvement), plus `:watch-out`. For Jaap, in the gate. Compact: 1–2 lines each; your investigation's depth shows as confidence, not length.
- **The ticket text** — `:product` + `:app-domain`, filed as the Notion page body. For someone who uses brian and never opens the code.

The verdict's `:doubt`, when you write one instead, is 1–3 sentences to Jaap in the thread: why you think this is not a real problem, with the one observation that convinced you.

```clojure
;; ── ticket-type "bug" ──
{:format      :proposed-ticket
 :title       "Long links run past the chat bubble in the Teaching Assistant"
 :ticket-type "bug"
 :priority    "3 - Could"     ; a Notion Priority option below, or nil
 :source-url  "…"             ; the thread permalink, same as the verdict's
 ;; technical analysis — gate only
 :problem     "1-2 lines: what's wrong + who it affects."
 :root-cause  "1-2 lines: the cause + evidence — cite the commit; say 'verified live in REPL' when you did."
 :fix         "1-2 lines: fix shape + rough size + the CONCRETE file:line targets."
 :watch-out   "optional caveat or the boundary-moving flag; nil when none"
 ;; the ticket text
 :app-domain  "Teacher"
 :product     {:where           "The Teaching Assistant chat sidebar, as a teacher."
               :what-happens    "Long words, links, code and tables run past the right edge of the message bubble."
               :expected        "Everything stays inside the bubble; wide content scrolls sideways."
               :steps           ["Open the Teaching Assistant chat."
                                 "Ask for an answer containing a long link or a table."]   ; optional
               :who-is-affected "Any teacher using the chat; the overflowing text is unreadable."}}

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

Exactly one of `"Student"`, `"Teacher"`, `"Backend"`, `"Misc"` — where the reporter met the problem (a teacher-facing screen is `"Teacher"` even if the cause is backend). Required on every ticket.

### Priority guidance

The Notion Priority options (select field, exact strings — note the en-dash on "0"):

- `"0 – Release Blocker"`
- `"1 - Must"`
- `"2 - Should"`
- `"3 - Could"`
- `"4 - Would"`
- `"5 - Wont"`

Use `nil` for `:priority` if you're not confident — don't guess just to fill the field.

Both schemas are closed. A verdict holds exactly one of `:tickets` (non-empty, keys unique) or `:doubt`. Each ticket is `:ticket-type`-dispatched: only `"bug"` and `"improvement"`, no stray keys, the right technical fields for the type, and `:product` with `:app-domain`. `append` rejects anything else with an explain dump — fix and retry.

## Step 3 — Append, conclude, park

```bash
bb nido:ticket:append   :project brian :br <slack-id> :kind proposed-tickets :session <session-id> :run-id <run-id> :file <temp.edn>
bb nido:ticket:conclude :project brian :br <slack-id>
```

(Derive `<session-id>` from the cwd / session name and `<run-id>` from the `./run-link/` symlink — it points at `~/.nido/coordinator/runs/<run-id>/`.)

`conclude` prints what it did — `fanned-out` and then one line per ticket key (`filed <url>`, `already-filed`, or an error, with the ticket's workstream id), or `doubted`. If it exits non-zero, **still park** (exit with the verdict appended) and say why in your final message; each failed ticket's own gate retries its filing on Apply, and nido never files twice:

- `filing-interrupted` — an earlier filing may have created a page nido could not record. Do NOT retry; Jaap has to check Notion.
- `auth` / `http` / `network` / `server` — Notion or Slack failed; Apply will retry.

Then **exit**. Each ticket's workstream is already in the gate with its analysis; a doubt parks on the thread's workstream, where the gate shows Jaap your reasoning.

## Resume behaviour (= revise)

Only a doubted thread can be resumed: once the verdict fanned out into tickets, the thread's workstream is closed and each ticket is decided at its own gate with Apply or Dismiss. When the session is resumed (Jaap used **Reply** on a doubt), the latest user message is revision feedback, not a fresh brief:

1. Read the feedback. Treat it as guidance for another investigation pass.
2. Re-investigate as needed (Step 1 — skip the react and the open), compose a fresh verdict (Step 2), and run Step 3 again. The append is additive; the latest entry is what conclude reads.

A doubt overridden ("it's real, file it") — conclude with `:tickets`; `conclude` files them then.

If you need to confirm what you last concluded:

```bash
bb nido:workstream:show :project brian :ref <slack-id>
```

If the reply is `drop`/`dismiss`/`cancel` — do nothing. Those are gate actions nido handles directly; you should not normally be resumed for them.
