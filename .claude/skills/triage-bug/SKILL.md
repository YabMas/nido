---
name: triage-bug
description: Triage a Notion bug-report ticket. Investigates the brian codebase, drafts a structured report, halts for human review in the session chat, and applies the human-confirmed verdict to Notion.
---

# triage-bug skill

> **Harness-side skill, owned by nido.** It lives at `nido/.claude/skills/triage-bug/` and is injected as a native harness skill into every spawned session's composed `.claude/skills/` (see `nido.session.launcher/compose-claude-dir!`), so claude resolves `/triage-bug` independent of the target project's checked-out branch. (Previously it lived in brian's `.claude/`, which coupled it to whatever branch was checked out — when that branch changed, the skill silently disappeared and triage runs no-op'd.)
>
> **Triage state + the report live in the nido ticket record**, not in Notion. Each ticket gets a per-ticket record at `~/.nido/projects/brian/tickets/BR-####/` (`meta.edn` + `report.md` + `entries/`), managed via `bb nido:ticket:*`. Notion only receives the human-confirmed structured properties on `apply`. There is no longer a `🤖 Triaged` Notion comment.

## When this fires

The nido coordinator fires this skill when a Notion row matches one of the two triage triggers:
- `:triage-new` — `Status = Needs verification` view
- `:triage-backlog` — `Type = bug AND Status NOT IN {Done, Not Done, Needs verification, Review}` view, filtered to rows with no `Effort` set

The envelope payload (per nido's normalised Notion event) includes:
- `:page-id` — Notion page id
- `:url` — Notion page URL
- `:title` — page title
- `:trigger-name` — `:triage-new` or `:triage-backlog`
- Other Notion properties flattened (e.g. `:type`, `:status`, `:priority`)

The session is a **lite session**: the worktree at `./worktree` is a symlink to `~/Code/brian` (read-only intent). No PG, no JVM, no app server. Only the notion MCP server is wired into `.mcp.json`.

The Run record lives at `./run-link/` (symlink to `~/.nido/coordinator/runs/<run-id>/`).

## Lifecycle: ticket status

Triage state lives in the nido ticket record, keyed by `BR-####`. Set the record's status via `bb nido:ticket:status`; this is recorded in the ticket's `meta.edn` and is the durable triage state the coordinator's pre-spawn gate reads. Terminal completion goes through `bb nido:ticket:complete`.

| Status | Set by | Means |
|---|---|---|
| `:investigating` | `bb nido:ticket:open` (Step 1), and `redo:` | Fetching page, walking codebase, drafting report |
| `:awaiting-input` | `bb nido:ticket:status … :status awaiting-input` | Draft written + appended to the record. Awaiting human reply in chat. |
| `:triaged` | `bb nido:ticket:complete … :status triaged` | Notion properties written on `apply`. Run done. |
| `:skipped` | `bb nido:ticket:complete … :status skipped` | Skip disposition applied. Run done. |

To set a non-terminal status mid-run:

```bash
bb nido:ticket:status :project brian :br <BR-####> :status awaiting-input
```

## Step 1 — Investigate (autonomous)

1. Read the envelope payload to get `:page-id`, `:url`, `:title`, `:trigger-name`. The payload is in the first user message you receive at session start.
2. **Resolve BR-#### and open the record (first recorded action).** Fetch the page (`mcp__notionApi__API-retrieve-a-page`) and read the **"ID" property** — a Notion `unique_id` property with prefix `BR` (e.g. `BR-5236`) — to get the `BR-####`. This is the key for every later `bb nido:ticket:*` call. Then open the nido record as your first recorded action:

   ```bash
   bb nido:ticket:open :project brian :br <BR-####> :page <page-id> :url <url> :title "<title>" :opened-by <trigger-name> :edited <last_edited_time>
   ```

   `open` creates/refreshes the record and sets status `:investigating`. The `:edited` value is stored as `:notion-last-edited-at` in `meta.edn` for future change-detection. (There is no dedup step here — nido's coordinator pre-spawn gate already reads this record and won't spawn a Run for a ticket that's already triaged/skipped or has a live session.)
3. While you have the page response from step 2, capture `last_edited_time` — needed for optimistic concurrency at apply time. Also fetch the body blocks via `mcp__notionApi__API-get-block-children` (description text).

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
6. Write the report (template in Step 2 below) to a temp file, then append it to the nido record as a `triage` entry:

   ```bash
   bb nido:ticket:append :project brian :br <BR-####> :kind triage :session <session-id> :run-id <run-id> :file <temp-file>
   ```

   (Derive `<session-id>` from the cwd / session name and `<run-id>` from the `./run-link/` symlink — it points at `~/.nido/coordinator/runs/<run-id>/`.)

   The record's `report.md` is the human entry point; the appended entry is the immutable copy. Also print the report verbatim into chat (Step 2).
7. Set status `:awaiting-input` (`bb nido:ticket:status :project brian :br <BR-####> :status awaiting-input`) and exit. The user will `nido enter` the session, read the report in chat, and reply.

## Step 2 — Report format

Write this body to a temp file (then append it via `bb nido:ticket:append`, per Step 1.6):

```markdown
# Triage: BR-#### — <original title>

**Source view:** new-reports | bugs
**Determination:** bug | not-a-bug | needs-info

## 1. Enriched description
<2–6 sentences. What the report is actually about, self-contained — assumes the reader hasn't seen the original.>

**Confidence in this analysis:** high | medium | low — <one-line reason>

## 2. Solution directions
- **Direction A** — <shape, 1 sentence>. Effort: M. Confidence: medium — <reason>
- **Direction B** — <shape>. Effort: L. Confidence: low — <reason>

## 3. Proposed Notion writes (on `apply`)
- Type: <unchanged | "bug">
- Effort: M
- Status: `Needs verification` → `Not started`     (for triage-new only)

(Only Type / Effort / Status go to Notion. The reporter's original title and
description are left untouched — the enriched narrative in §1 lives in the
nido record, not Notion.)

## 4. If you want to skip instead
**Recommended disposition:** Not Done | On Hold | leave-as-is | delete
**Reason:** <one line>

## 5. Investigation trail
- Transcripts read (if any): list manifest entries the report drew from.
- <file:line — what I learned>
- <file:line — what I learned>
```

Print the report verbatim into your chat output (in addition to appending it to the record per Step 1.6) so the user sees it as soon as they `nido enter` the session.

## Step 3 — Confirmation (chat, liberal parsing)

When the session resumes (the user replied), the latest user message contains the verdict. Parse liberally — phrasing variants ("apply it", "looks good, ship", "yes apply") all resolve to one of the verbs below.

| Reply | Effect |
|---|---|
| `apply` | Execute §3 verbatim |
| `apply: <override>` | Apply with overrides, e.g. `apply: effort=L, title="..."` |
| `skip` | Execute §4 with the recommended disposition |
| `skip: <disposition>` | Override disposition (`skip: delete`, `skip: on-hold`, `skip: leave-as-is`, `skip: not-done`) |
| `redo: <correction>` | Re-run Step 1 + Step 2 with the correction in mind; new draft, re-halt at `:awaiting-input` |
| `cancel` | Abort and exit. Leave Notion untouched and do NOT mark the record terminal — nido's run-termination hook clears the ticket status so it's re-triable |

**For non-trivial overrides** (e.g. apply with a different effort or new title), confirm back to the user before executing:

> "→ applying with effort=L, ok? (y/n)"

If they say no, treat as redo or cancel.

**Unparseable input** — ask for clarification. Don't guess. Use the verb table above to suggest what they can say.

## Step 4 — Apply (the only Notion-write step)

### Optimistic concurrency check first

Re-fetch the page via `mcp__notionApi__API-retrieve-a-page`. Compare `last_edited_time` against the value captured in Step 1. If they differ — a human touched the ticket while we were reviewing — post a warning in chat:

> "⚠️ Ticket was edited externally during review (was: `<old>`, now: `<new>`). Re-read and reconfirm before I apply?"

Wait for re-confirmation in chat before proceeding.

### If concurrency check passes (or user reconfirms):

1. **Property update (the only Notion write)** — `mcp__notionApi__API-patch-page` with the page-id and **only** these properties:
   - **Type** (select) — must be one of `bug`, `feature`, `improvement`, `research`, `chore`
   - **Effort** (select) — must be one of `XS`, `S`, `M`, `L`, `XL`
   - **Status** (status) — for `:triage-new`, transition `Needs verification` → `Not started` (or `On Hold` if you assessed not-actionable). For `:triage-backlog`, leave unchanged.

   Do NOT touch the title or the description body — the reporter's original text stays as written; the enriched narrative lives in the nido record.

2. **Complete the record** — on success:

   ```bash
   bb nido:ticket:complete :project brian :br <BR-####> :status triaged :disposition applied
   ```

3. **Audit log** — append one line to `~/.nido/coordinator/notion-mutations.log`:

```bash
printf '%s %s page=%s writes=type,effort,status\n' \
       "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "<run-id>" "<page-id>" \
       >> ~/.nido/coordinator/notion-mutations.log
```

4. Print summary to chat (what landed, what didn't).

### Partial-write handling

If the property update fails after a partial write (e.g. some properties landed but the patch errored midway), DON'T try to roll back — Notion has no transactions, and rollback is fragile and worse than honest partial state. Instead:

1. Post a clear warning into chat: `⚠️ partial triage — wrote <what landed>, failed to write <what didn't>: <error>. Re-triage manually.`

2. Append the audit log with a `(PARTIAL)` annotation:

```
<iso-timestamp> <run-id> page=<page-id> writes=<landed-fields> (PARTIAL: <missing>)
```

3. Do NOT call `bb nido:ticket:complete` — leave the record non-terminal so the ticket can be re-triaged.

## Step 5 — Skip

Dispositions (the Status / archive write is the only Notion mutation — no skip comment):

- `Not Done` — `mcp__notionApi__API-patch-page` setting Status = `Not Done`. Record disposition: `not-done`.
- `On Hold` — Status = `On Hold`. Record disposition: `on-hold`.
- `leave-as-is` — no Status change. Record disposition: `leave-as-is`.
- `delete` — `mcp__notionApi__API-delete-a-block` on the page-id (archives the page via `in_trash: true`). Record disposition: `deleted`.

Audit log line:

```
<iso-timestamp> <run-id> page=<page-id> writes=status:<new-status>
```

Or for delete:

```
<iso-timestamp> <run-id> page=<page-id> writes=archived
```

On success, complete the record:

```bash
bb nido:ticket:complete :project brian :br <BR-####> :status skipped :disposition <not-done|on-hold|leave-as-is|deleted>
```

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

The real safety mechanism for triage is the HITL halt at `:awaiting-input` (Step 1.7 + Step 3): the agent ALWAYS pauses for a chat verdict before any Notion write. No Notion mutation happens until you respond with `apply`. If you say `cancel`, `skip`, or `redo`, the apply branch never runs.

Treat this as a hard contract: never call a Notion-write MCP tool (`API-patch-page`, `API-delete-a-block`) outside the explicit `apply` or `skip` branches in Step 4 / Step 5. On `apply`, the only Notion write is the Type/Effort/Status property patch — never touch the title, description body, or comments.

## Resume behaviour

When the session is resumed after `:awaiting-input` (via `claude --resume`), the latest user message is your verdict. You'll typically see:

1. The original first message (the trigger payload, with the page-id etc.)
2. The full history of your own investigation + the report you wrote
3. The user's reply at the end

If you need to confirm the ticket's status or re-read what you proposed, run `bb nido:ticket:show :project brian :br <BR-####>` (and read the record's `report.md` under `~/.nido/projects/brian/tickets/BR-####/`).

Then parse the user's reply (Step 3 grammar) and execute the matching branch.
