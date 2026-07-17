---
name: triage-bug
description: Triage a bug-report ticket (Notion ticket or Slack-channel message). Investigates the brian codebase, drafts a structured report, halts for human review in the session chat, and applies the human-confirmed verdict — to Notion for Notion tickets, or to the nido ledger only for Slack-sourced reports.
---

# triage-bug skill

> **Harness-side skill, owned by nido.** It lives at `nido/.claude/skills/triage-bug/` and is injected as a native harness skill into every spawned session's composed `.claude/skills/` (see `nido.session.launcher/compose-claude-dir!`), so claude resolves `/triage-bug` independent of the target project's checked-out branch. (Previously it lived in brian's `.claude/`, which coupled it to whatever branch was checked out — when that branch changed, the skill silently disappeared and triage runs no-op'd.)
>
> **Triage state + the full report live in the nido ticket record**, not in Notion. Each ticket gets a per-ticket record at `~/.nido/projects/brian/tickets/BR-####/` (`meta.edn` + `entries/*.edn`), managed via `bb nido:ticket:*`. On `apply` Notion receives the human-confirmed structured properties (Type/Effort/Status), the **enriched title**, and an **enriched-description callout prepended above the reporter's original body** (the original is preserved intact, never overwritten). Comments are never written, and there is no longer a `🤖 Triaged` Notion comment.

## When this fires

The nido coordinator fires this skill when a triage trigger matches — from a Notion view or the Slack bug channel. The live Notion trigger is `:triage-new` — `Status = Needs verification` Notion view (see `~/.nido/projects/brian/triggers.edn` for the current set; don't assume other trigger names are wired up).

The envelope payload for a **Notion** run (per nido's normalised Notion event) includes:
- `:page-id` — Notion page id
- `:url` — Notion page URL
- `:title` — page title
- `:trigger-name` — `:triage-new`
- Other Notion properties flattened (e.g. `:type`, `:status`, `:priority`)

The envelope payload for a **Slack** run includes `:adapter :slack-message`, `:id` (e.g. `slack-C123-1718000000.000123`), `:url` (Slack permalink), `:title` (truncated text), and `:text` (the full message). It has **no `:page-id`** — see "Source adapter" below.

The session is a **lite session**: the worktree at `./worktree` is a symlink to `~/Code/brian` (read-only intent). No PG, no JVM, no app server. Notion access is via the `notion` CLI (on `PATH`).

The Run record lives at `./run-link/` (symlink to `~/.nido/coordinator/runs/<run-id>/`).

## Source adapter (Notion vs Slack)

This skill triages reports from two sources. Branch on the payload's `:adapter`:

- **Notion ticket** (`:adapter` absent / the payload carries a `:page-id`) — the original path. The **ticket key** is the `BR-####` resolved from the Notion page. On `apply`, the verdict is written back to Notion.
- **Slack message** (`:adapter :slack-message`) — a bug posted in the Slack bug channel. The payload carries `:id` (e.g. `slack-C123-1718000000.000123`), `:url` (Slack permalink), `:title`, and `:text` (full message). There is **no `:page-id`** and **no Notion ticket**. The **ticket key** is the payload `:id`.

The **ticket key** is what goes in every `bb nido:ticket:*` call's `:br` argument below — `BR-####` for a Notion run, the payload `:id` for a Slack run. (`bb nido:ticket:*` accepts any string as `:br`, so the slack id works as the ledger key directly.)

**Global rule for Slack runs — ledger-only, never touch Notion.** When `:adapter` is `:slack-message`, skip EVERY Notion interaction:

- **Step 1.2 / 1.3:** do NOT fetch the Notion page or block-children. The brief IS the payload — investigate from `:text` (the report body) and `:url` (the Slack permalink). Open the record with the slack `:id` as `:br` and **omit `:page`** (there is no page-id):
  ```bash
  bb nido:ticket:open :project brian :br <slack-id> :url <url> :title "<title>" :opened-by <trigger-name>
  ```
- **Report (Step 1.6 / Step 2):** there is no Notion owner to route to, so the report EDN's `:routing` field is `:routing nil` (see the schema in Step 2).
- **Step 4 (apply):** skip the optimistic concurrency check and ALL Notion writes (`notion page set`, the callout `notion api PATCH`, `notion block delete`). The verdict is already captured in the nido record (Step 1.6) — just `bb nido:ticket:complete … :status triaged :disposition applied`. There is no Notion mutation, so write **no** `notion-mutations.log` entry — the nido record is the audit trail.
- **Step 5 (dismiss):** dismiss is a Slack-only, nido-ledger action (Notion runs never dismiss) — just `bb nido:ticket:dismiss … :br <slack-id>`. No Notion write, no `notion-mutations.log` entry.

Everything else is **identical** for both sources: the codebase investigation, the report format (§3 "Proposed Notion writes" is simply omitted for Slack — there are none), the HITL halt at `:awaiting-input`, the `bb nido:ticket:*` ledger writes, and the verb grammar. Wherever a step below says "fetch the page", "BR-####", or names a Notion-write command, a Slack run uses the payload brief, the slack `:id`, or skips the call, respectively.

## Routing (Notion runs)

The triage of a Notion report is a **routing decision**: get it to the right owner.
Set the Notion **Ball Holder** (people, replace) and **App Domain** (multi_select,
additive) accordingly. The owner AND the bug determination together decide how deep
this triage goes (see Depth below) — owner alone is not enough.

| Report is about… | Ball Holder (user-id) | App Domain | Depth |
|---|---|---|---|
| Student environment / mobile app | Ataberk Koroglu — `3eb98667-d12e-4e9e-9342-48fec803b571` | `Student` | shallow |
| Auth (backend), external integrations (Canvas, Entra, …), large architecture / design-pattern concerns | Eric Dvorsak — `955b4c25-7bce-4ca2-ab5e-d99acbcd423a` | `Backend` | shallow |
| Teacher environment | Jaap Maaskant — `169d872b-594c-8160-b432-000250f98e86` | `Teacher` | deep if `:determination :bug`, else shallow |
| General backend (not Eric's specialty) | Jaap Maaskant — `169d872b-594c-8160-b432-000250f98e86` | `Backend` | deep if `:determination :bug`, else shallow |
| Doesn't clearly fit Student / Teacher / Backend | Jaap Maaskant — `169d872b-594c-8160-b432-000250f98e86` | `Misc` | shallow |

Jaap's depth is **conditional**, not fixed: deep only when the report is an actionable
bug (`:determination :bug`); a not-a-bug / needs-info / can't-verify report routed to
Jaap is always shallow, same as an Ataberk/Eric route. See Depth below.

**The Backend split is the one real judgment.** Authentication / login / SSO / Entra /
Canvas / any external-system integration / large architectural or design-pattern
concern → **Eric**. Any other backend work → **Jaap**. When you genuinely can't tell
which side of the split a report falls on, route to **Jaap** and say so in chat.

**A report that doesn't clearly fit Student / Teacher / Backend** uses App Domain
`"Misc"` and routes to **Jaap** (the default owner), shallow.

**Every report gets an owner, routed by area — not by determination.** A report you
read as not-a-bug / noise / needs-info is NOT dismissed — route it exactly like a bug
report would be, by AREA: Student → **Ataberk**, the Eric-split list
(auth/login/SSO/Entra/Canvas/external-integration/large-architecture) → **Eric**,
Teacher / general Backend → **Jaap**. `:determination` never changes the owner, only
the depth (see Depth below). **Jaap is the default owner only when the report has no
clear specialist area** — it isn't clearly Student and isn't clearly Eric's
auth/integration/architecture domain — which is where a vague/noise/"Misc" report
lands. Either way this is always a **shallow** route: set Ball Holder + App Domain
only, keep the status at "Needs verification", and let the owner disposition it in
Notion. There is no ownerless outcome and no `dismiss` for Notion tickets.

The report's `:routing` field carries the semantic owner keyword — `:ataberk`,
`:eric`, or `:jaap` — plus `:app-domain` and `:depth`. The user-ids above are only for
the Notion write in Step 4.

### Depth: shallow vs deep

Depth is gated on the **determination**, not purely on the owner:

- **Deep applies ONLY to an actionable bug routed to Jaap** — `:determination :bug`
  AND owner Jaap (Teacher, or general Backend not in Eric's specialty). The full
  triage: investigate the codebase, propose 1–3 directions with effort (or
  `:squirrel`), enrich the title and prepend the enriched-description callout, and
  transition **Status "Needs verification" → "Not started"**. Emit the populated
  `:directions`, `:notion-writes` (as today), and `:routing {… :depth :deep}`.
- **A not-a-bug / needs-info / can't-verify report is shallow, regardless of owner** —
  including one routed to Jaap. Shallow also covers every Ataberk/Eric route
  unconditionally. Set Ball Holder + App Domain and **nothing else**. No solution
  directions, no effort, no enriched title/description, and **no theories you can't
  verify from the codebase**. Notion **Status stays "Needs verification"** — the owner
  picks it up from there (Jaap dispositions his own not-a-bug/needs-info reports in
  Notion). You may read the report and take a light look to *confirm the area*, but do
  not root-cause. Emit `:directions []` and `:notion-writes nil`; set
  `:routing {… :depth :shallow}`.

A not-a-bug / needs-info report is **never** deep and **never** transitions Notion
Status away from "Needs verification" — that transition happens only for a confirmed,
actionable bug.

Either way, HITL is unchanged: propose, halt at `:awaiting-input`, and wait for the
human to `apply`. Shallow routes still pass the human — every routing is acked.

## Lifecycle: ticket status

**One terminal outcome for a Notion report: `apply` (route it).** Every new report is
triaged to an owner (see Routing) — there is no `dismiss` for Notion tickets. `dismiss`
survives only for **Slack** runs (ledger-only, off the nido radar); a Notion run never
uses it. On `apply` the nido record is marked `:triaged`, which takes the ticket off
nido's Intake radar even though a shallow route leaves the Notion status at "Needs
verification".

Triage state lives in the nido ticket record, keyed by `BR-####`. Set the record's status via `bb nido:ticket:status`; this is recorded in the ticket's `meta.edn` and is the durable triage state the coordinator's pre-spawn gate reads. Terminal completion goes through `bb nido:ticket:complete` (triaged) or `bb nido:ticket:dismiss` (off-radar, Slack-only).

| Status | Set by | Means |
|---|---|---|
| `:investigating` | `bb nido:ticket:open` (Step 1), and `redo:` | Reading the report (Notion page or Slack brief), walking codebase, drafting report |
| `:awaiting-input` | `bb nido:ticket:status … :status awaiting-input` | Draft written + appended to the record. Awaiting human reply in chat. |
| `:triaged` | `bb nido:ticket:complete … :status triaged` | Verdict applied — Notion properties written on `apply` (Notion run); ledger-only, no Notion (Slack run). Run done. |

`:dismissed` (`bb nido:ticket:dismiss …`) remains a status, but it's **Slack-only** — off-radar, nido-only, no Notion write. A Notion run never reaches it.

To set a non-terminal status mid-run:

```bash
bb nido:ticket:status :project brian :br <BR-####> :status awaiting-input
```

## Step 1 — Investigate (autonomous)

> **Notion access — `notion` CLI (Bash).** Reads: `notion page props <id> ID
> --format json` (→ `BR-\(.unique_id.number)`); `notion page view <id> --format json` (→
> `.page.last_edited_time`, `.page.properties`); `notion block list <id> --md --depth 3`
> (body); `notion comment list <id> --all`. Writes (apply only): `notion page set <id>
> "Type=…" "Effort=…" "Status=…" "Task result=…"`; callout via `notion api PATCH`.

1. Read the envelope payload to get `:page-id`, `:url`, `:title`, `:trigger-name`. The payload is in the first user message you receive at session start.
2. **Resolve the ticket key and open the record (first recorded action).** **(Notion run; a Slack run skips this fetch — see "Source adapter" above, open the record with the slack `:id`.)** Fetch the ID property with `notion page props <page-id> ID --format json` — a Notion `unique_id` property with prefix `BR` — and read `.unique_id.prefix` + "-" + `.unique_id.number` (e.g. `BR-5236`) to get the `BR-####`. This is the key for every later `bb nido:ticket:*` call. Then open the nido record as your first recorded action:

   ```bash
   bb nido:ticket:open :project brian :br <BR-####> :page <page-id> :url <url> :title "<title>" :opened-by <trigger-name> :edited <last_edited_time>
   ```

   `open` creates/refreshes the record and sets status `:investigating`. The `:edited` value is stored as `:notion-last-edited-at` in `meta.edn` for future change-detection. (There is no dedup step here — nido's coordinator pre-spawn gate already reads this record and won't spawn a Run for a ticket that's already triaged/dismissed or has a live session.)
3. Capture `last_edited_time` for optimistic concurrency at apply time via `notion page view <page-id> --format json` → `.page.last_edited_time`. Fetch the body blocks with `notion block list <page-id> --md --depth 3` (description text). **(Notion run only — a Slack run has no page; the description body is the payload `:text`, and there is no concurrency check.)**

### Pre-staged artifacts (videos)

Before grepping the worktree, **read `./run-link/preprocess/manifest.edn` and
`./run-link/preprocess/transcripts.md`**. The coordinator has already walked
the Notion page and transcribed any embedded videos (Loom share/embed URLs
and Notion-uploaded screen captures). The manifest lists each video with
its source kind, transcript source (Loom official captions vs. local
whisper), and full-VTT path; the digest gives a one-paragraph preview per
video.

- If the user's bug report leans on a video (Loom screen-share, repro
  recording), the relevant content is in those VTTs — quote timestamps
  in your triage report (e.g. `at 0:42 the modal closes`).
- If a video shows `:status :failed` in the manifest, note the reason
  in your report's Investigation Trail and proceed without it. Do not
  retry transcription from inside the skill — that's the coordinator's
  job and a one-off retry won't change anything.
- If `./run-link/preprocess/manifest.edn` doesn't exist, the
  preprocessor wasn't run for this Run — proceed without transcripts.

> **The preprocessor is currently dormant.** No Run gets a `manifest.edn`
> today, so the no-manifest path above is the norm — proceed straight to
> the codebase investigation. Keep the transcript-reading guidance: it
> reactivates automatically the moment the coordinator starts staging
> manifests again.

4. Investigate the brian codebase at `./worktree` (READ-ONLY — do not edit files). Strategy:
   - Heuristic grep for module/feature names from the title and description body.
   - Read 2–5 relevant files, ~500 lines total cap. Don't go deeper than needed.
   - Stop early when the area is obvious.
   - If the report mentions a stack trace or specific error message, grep for it verbatim first.
5. Determine, for the report:
   - Is this actually a bug? (vs feature request, vs duplicate, vs noise — `:determination`)
   - If a bug: what part of the system is affected? what's the likely root cause?
   - What are 1–3 candidate solution directions? T-shirt effort per direction.
6. Compose the report EDN (schema in Step 2 below) to a temp `.edn` file, then append it to the nido record as a `triage` entry:

   ```bash
   bb nido:ticket:append :project brian :br <BR-####> :kind triage :session <session-id> :run-id <run-id> :file <temp.edn>
   ```

   (Derive `<session-id>` from the cwd / session name and `<run-id>` from the `./run-link/` symlink — it points at `~/.nido/coordinator/runs/<run-id>/`.)

   The append **validates** the EDN against nido's `TriageReport` schema and **rejects** a malformed report (non-zero exit + an explain dump) — fix and retry until it's accepted. Also print it into chat via `bb nido:ticket:report` (Step 2).
7. Set status `:awaiting-input` (`bb nido:ticket:status :project brian :br <BR-####> :status awaiting-input`) and exit. The user will `nido enter` the session, read the report in chat, and reply.

## Step 2 — Report schema (EDN)

Compose the report as an EDN map and write it to a temp `.edn` file, then append it
via `bb nido:ticket:append … :kind triage :file <temp.edn>` (Step 1.6). The append
**validates** it against nido's `TriageReport` schema and **rejects** a malformed
report (non-zero exit + an explain dump) — fix and retry until it's accepted.

```clojure
{:format        :triage-report
 :ticket-key    "BR-####"            ; the slack :id for a Slack run
 :determination :bug                 ; :bug | :not-a-bug | :needs-info
 :title         "concise enriched title, no BR-#### prefix, no trailing punctuation"
 :summary       "2–6 sentences, self-contained — assumes the reader hasn't seen the original."
 :confidence    {:level :high        ; :high | :medium | :low
                 :reason "one line"}
 :routing       {:owner :jaap        ; :ataberk | :eric | :jaap
                 :app-domain "Teacher" ; "Student" | "Teacher" | "Backend" | "Misc"
                 :depth :deep}         ; :deep ONLY for :determination :bug routed to Jaap; :shallow otherwise (Ataberk/Eric always, or any not-a-bug/needs-info report)
 :directions    [{:label "A" :shape "1 sentence"
                  :effort :M    ; :XS :S :M :L :XL — or :squirrel to defer sizing
                  :confidence {:level :medium :reason "one line"}}]
 :notion-writes {:type "bug"          ; nil for a Slack run (no Notion writes)
                 :effort :M     ; :XS :S :M :L :XL :squirrel ; nil for a Slack run
                 :status-transition ["Needs verification" "Not started"]  ; omit/nil if no transition
                 :title "enriched title (= :title above, or unchanged when identical to original)"
                 :description-prepend "the enriched callout body prepended above the reporter's original"}
 :defer-note    "why sizing was deferred — REQUIRED when any effort is :squirrel, else omit"
 :trail         [{:ref "file:line or transcript ref" :note "what I learned"}]}
```

Notes:
- **`:routing` is required for a Notion run** (nil only for Slack). A **shallow** route
  (Ataberk/Eric always, or a not-a-bug/needs-info report even when routed to Jaap)
  emits `:directions []` and `:notion-writes nil` — routing is the whole outcome. A
  **deep** route (Jaap, and only for `:determination :bug`) populates `:directions` and
  `:notion-writes` as below. See Routing / Depth above.
- `:notion-writes` is **nil for Slack runs** — there are no Notion writes.
- There is **no dismiss-recommendation field**. If the report isn't worth pursuing, say so in chat and `dismiss` — don't encode it in the report (Slack runs only; a Notion report is always routed, never dismissed).
- **`:squirrel` is the joker** — use it for `:effort` when the implementation direction is genuinely ambiguous and sizing should defer to the implementation-plan stage. When you use it, set `:defer-note` explaining why; `/continue-ticket` resolves it into a concrete effort later.
- After appending, print the report into chat with `bb nido:ticket:report :project brian :br <key>` (renders the stored report as markdown) so the user sees it on `nido enter`.

## Step 3 — Confirmation (chat, liberal parsing)

When the session resumes (the user replied), the latest user message contains the verdict. Parse liberally — phrasing variants ("apply it", "looks good, ship", "yes apply") all resolve to one of the verbs below.

| Reply | Effect |
|---|---|
| `apply` | Execute §3 verbatim |
| `apply: <override>` | Apply with overrides, e.g. `apply: effort=L, title="..."` |
| `dismiss` | *(Slack runs only)* Execute §5 — take the ticket off the radar (nido-only). A Notion run has no `dismiss` — every report is routed. |
| `redo: <correction>` | Re-run Step 1 + Step 2 with the correction in mind; new draft, re-halt at `:awaiting-input` |
| `cancel` | Abort and exit. Leave Notion untouched and do NOT mark the record terminal — nido's run-termination hook clears the ticket status so it's re-triable |

**For non-trivial overrides** (e.g. apply with a different effort or new title), confirm back to the user before executing:

> "→ applying with effort=L, ok? (y/n)"

If they say no, treat as redo or cancel.

**Unparseable input** — ask for clarification. Don't guess. Use the verb table above to suggest what they can say.

## Step 4 — Apply (the only Notion-write step)

> **Slack run:** this entire step is Notion-only. For a Slack run, `apply` does NOT touch Notion — the verdict already lives in the nido record (Step 1.6). Skip the concurrency check and all property/description writes below; jump straight to "Complete the record" (4.3) with the slack `:id` as `:br`. There is no Notion mutation → write no `notion-mutations.log` entry. See "Source adapter".

### Optimistic concurrency check first

**(Notion run only — skip for a Slack run.)** Re-fetch via `notion page view <page-id> --format json` → `.page.last_edited_time`. Compare it against the value captured in Step 1. If they differ — a human touched the ticket while we were reviewing — post a warning in chat:

> "⚠️ Ticket was edited externally during review (was: `<old>`, now: `<new>`). Re-read and reconfirm before I apply?"

Wait for re-confirmation in chat before proceeding.

### If concurrency check passes (or user reconfirms):

**Read the verdict from the record, not your memory.** Run
`bb nido:ticket:report :project brian :br <BR-####>` (or read the stored EDN via
`bb nido:ticket:show`); the `:routing` and `:notion-writes` maps are the canonical
apply payload. Write exactly those values — do not re-derive them.

### Routing write (both depths, Notion run)

Always set Ball Holder (replace) and App Domain (additive) from `:routing`. These are a
people + multi_select property, so use `notion api PATCH` (the `notion page set`
key=value form does not handle people). Map `:owner` → user-id via the Routing table.

1. Read the current App Domain so the write is additive:

   ```bash
   notion page view <page-id> --format json \
     | jq -c '[.page.properties["App Domain"].multi_select[].name] + ["<routed-domain>"] | unique | map({name: .})'
   ```

   Let `<domains-json>` be that array (e.g. `[{"name":"Backend"}]`).

2. PATCH Ball Holder + App Domain:

   ```bash
   notion api PATCH /v1/pages/<page-id> --body '{
     "properties": {
       "Ball Holder": {"people": [{"id": "<owner-user-id>"}]},
       "App Domain":  {"multi_select": <domains-json>}
     }
   }'
   ```

**Shallow route stops here** — no Type/Effort/Status/Title write, Status stays "Needs
verification". Record the routing write in `notion-mutations.log` and complete the
record (`bb nido:ticket:complete … :status triaged :disposition applied`).

**Deep route continues** to the Property update + enriched-callout prepend below
(unchanged), which sets Type/Effort/Status ("Needs verification" → "Not started")/Title.

1. **Property update** — one `notion page set <page-id> "<k>=<v>" …` call, with these properties (the CLI fetches the DB schema and coerces select/status/title automatically):
   - **Type** (select) — must be one of `bug`, `feature`, `improvement`, `research`, `chore`
   - **Effort** (select) — must be one of `XS`, `S`, `M`, `L`, `XL`
   - **Status** (status) — transition `Needs verification` → `Not started`. (Deep route only — see Depth above; deep requires `:determination :bug`, so this transition never fires for a not-a-bug/needs-info report, and a shallow route never reaches this property update at all.)
   - **Title** — set the title property to the **enriched title** from §1. When the enriched title is identical to the original, this is a harmless no-op — write it anyway, don't special-case it.

   Example: `notion page set <page-id> "Type=bug" "Effort=M" "Status=Not started" "Task result=<enriched title>"`. Title property key is `Task result`.

   Do NOT overwrite the description body here — that's the prepend in step 2, which leaves the reporter's original text intact.

2. **Description prepend** — prepend the enriched description as a single **callout** above the reporter's original body. The original body is never overwritten.

   a. **Idempotency guard.** A ticket can be re-triaged (e.g. via `redo`, or a fresh triage pass later in its life), so an enriched callout from a prior triage may already exist. Fetch the page's first child block via `notion block list <page-id> --format json --depth 1` → `.results[0]`. If it is a callout whose text contains the marker `🤖 Enriched (triage BR-####)` for THIS `BR-####`, delete it first with `notion block delete <block-id>` (the `<block-id>` is `.results[0].id`) — it's our own block, never the reporter's content. This prevents stacking duplicate callouts.

   b. **Prepend the callout.** Build the JSON with `jq` (pass the enriched description as the `--arg desc` value, shell-quoted — `jq` escapes any quotes/backslashes/newlines into valid JSON; never hand-write the JSON string), and PATCH it with `position` "start" so it lands at the top:

   ```bash
   jq -n --arg marker "🤖 Enriched (triage BR-####)" --arg desc "<enriched description from §1>" \
     '{children:[{type:"callout",callout:{icon:{type:"emoji",emoji:"🤖"},rich_text:[{type:"text",text:{content:($marker + "\n" + $desc)}}]}}],position:{type:"start"}}' \
     | notion api PATCH /v1/blocks/<page-id>/children --body -
   ```

   c. **Verify the prepend landed at the top.** Notion's API may not honor the `position` param for the CLI's Notion-Version, so although it is most likely applied, it is not guaranteed. Re-fetch the first child block (`notion block list <page-id> --format json --depth 1` → `.results[0]`) and confirm it is the enriched callout. **If it is NOT at index 0** (the callout appended at the bottom instead — `position` was stripped), treat it like a partial write: post `⚠️ enriched callout landed at the bottom, not the top (position param not honored) — fix placement manually`, log it per "Partial-write handling" below, and do NOT complete the record cleanly.

3. **Complete the record** — on success (title + description both landed correctly):

   ```bash
   bb nido:ticket:complete :project brian :br <BR-####> :status triaged :disposition applied
   ```

4. **Audit log** — append one line to `~/.nido/coordinator/notion-mutations.log`:

```bash
printf '%s %s page=%s writes=type,effort,status,title,description\n' \
       "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "<run-id>" "<page-id>" \
       >> ~/.nido/coordinator/notion-mutations.log
```

5. Print summary to chat (what landed, what didn't).

### Partial-write handling

If any apply write fails after a partial write (some properties landed but the patch errored midway; the title wrote but the callout prepend failed; or the callout landed at the bottom per step 2c), DON'T try to roll back — Notion has no transactions, and rollback is fragile and worse than honest partial state. Instead:

1. Post a clear warning into chat: `⚠️ partial triage — wrote <what landed>, failed to write <what didn't>: <error>. Re-triage manually.`

2. Append the audit log with a `(PARTIAL)` annotation:

```
<iso-timestamp> <run-id> page=<page-id> writes=<landed-fields> (PARTIAL: <missing>)
```

3. Do NOT call `bb nido:ticket:complete` — leave the record non-terminal so the ticket can be re-triaged.

## Step 5 — Dismiss

**Slack runs only.** A Notion report is never dismissed — it is always routed to an owner (see Routing). For a Notion run this step does not apply.

Dismiss takes the ticket **off the triage radar** and is **nido-only** — it writes **nothing** to Notion. The Notion ticket stays on the team's board untouched; it's simply no longer in nido's triage queue and auto-re-triage skips it. (If a ticket should also change state in Notion — e.g. marked *Not Done* or deleted as spam — that's a human action in Notion, not triage's job.)

```bash
bb nido:ticket:dismiss :project brian :br <BR-####>
```

(For a Slack run, pass the slack `:id` as `:br`.) No `notion` write, no `notion-mutations.log` entry. The run is done.

## Step 6 — Redo

If the user replied `redo: <correction>`:

1. Read the correction text. Treat it as guidance for the next investigation pass — e.g. "look at the AI dialogue module instead", "check this specific file".
2. Set status back to `:investigating` (`bb nido:ticket:status :project brian :br <BR-####> :status investigating`).
3. Re-run Step 1 and Step 2 — fetch fresh page state, re-investigate with the correction in mind, write a fresh report and append it to the record (`bb nido:ticket:append … :kind triage …`).
4. Set status `:awaiting-input` and exit. The user replies again.

There's no hard limit on redo cycles, but the per-Run wall-clock budget (15m) caps the total. If the budget approaches and the user hasn't confirmed, the run dies; nido's run-termination hook clears the ticket's record status, so it re-enters the queue on the next poll. **That's correct behavior** — stale triage drafts shouldn't apply themselves later.

## Step 7 — Cancel

If the user replied `cancel`:

1. Post a one-line acknowledgement to chat.
2. Exit. Do NOT mark the record terminal (no `bb nido:ticket:complete`) and do NOT write anything to Notion. nido's run-termination hook clears the ticket's record status, making it re-triable on the next poll.

## Note on safety: no coordinator dry-run

Triage triggers deliberately do NOT use `:dry-run? true`. The coordinator's dry-run path (used by other triggers like `:smoke-notion`) short-circuits the Run BEFORE the session is spawned — the skill would never get to run, which is the opposite of what we want for validation.

The real safety mechanism for triage is the HITL halt at `:awaiting-input` (Step 1.7 + Step 3): the agent ALWAYS pauses for a chat verdict before any Notion write. No Notion mutation happens until you respond with `apply`. If you say `cancel`, `dismiss`, or `redo`, the apply branch never runs (and `dismiss` is nido-only — it never touches Notion either).

Treat this as a hard contract: never run a Notion-write `notion` command (`page set`, `api PATCH`, `block delete`) outside the explicit `apply` branch in Step 4. `apply` is the ONLY Notion-write verb. On `apply`, the permitted writes are: the **Ball Holder + App Domain PATCH** (both depths — see Routing write above), plus, **deep route only**, the Type/Effort/Status/Title property patch and the prepend (plus idempotency-delete) of **our own** enriched callout. Never overwrite the reporter's original body, and never write comments.

## Resume behaviour

When the session is resumed after `:awaiting-input` (via `claude --resume`), the latest user message is your verdict. You'll typically see:

1. The original first message (the trigger payload, with the page-id etc.)
2. The full history of your own investigation + the report you wrote
3. The user's reply at the end

If you need to confirm the ticket's status or re-read what you proposed, run `bb nido:ticket:show :project brian :br <BR-####>` (or print the report with `bb nido:ticket:report :project brian :br <BR-####>`).

Then parse the user's reply (Step 3 grammar) and execute the matching branch.
