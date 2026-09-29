---
name: review-queue
description: Groom brian's Notion Review queue — every ticket in Review whose Lifecycle is Development, Staging or Prod. The plan leg judges each ticket's QA instructions against docs/reference/qa-brief.md, proposes a brief where they fall short or are in another style, a Follow-up block for what a person still owes, and a cleanup where the page buries them, ranks the queue by what most needs a reviewer's attention, and writes the plan for a person to decide on the Operations page. The apply leg (`/review-queue apply <plan-run-id>`) carries out exactly the writes that person approved. Fired by the :review-queue and :review-queue-apply triggers on brian. Usage: /review-queue | /review-queue apply <plan-run-id>
---

# /review-queue

> **Harness-side skill, owned by nido.** Two legs, each its own `:lite` Run on
> project `brian`, both started from the dashboard's **Operations → Review queue**:
>
> - **plan** — `/review-queue`, fired by `:review-queue`. Reads, judges, ranks,
>   writes the plan (§1–§5). Writes nothing to Notion.
> - **apply** — `/review-queue apply <plan-run-id>`, fired by `:review-queue-apply`
>   once a person has approved writes on the page. Carries them out (§6–§7).
>
> Sibling of `/land` §6, which writes the same QA brief onto one ticket at
> landing; this skill does it for the whole queue, after the fact.

## What this is

Review is where a person checks shipped work, and the queue is read in Notion:
the ticket, not the PR. What arrives there is uneven — some tickets carry a clean
set of steps, some only a bug report written before anyone understood the bug,
some a hundred blocks of engineering handoff with the steps nowhere. And the
queue has no order, so a reviewer picks what looks quick rather than what
matters.

The plan leg makes three proposals per ticket — its **rank**, its **QA brief**,
its **cleanup** — as one numbered plan, and ends. A person goes through it on the
Operations page and approves or skips each write. The apply leg then carries out
**exactly the approved writes, as the plan worded them** — that is the safety:
nothing reaches Notion that a person did not read and approve by number.

## Two hard boundaries

1. **Never change Status, Lifecycle, Ball Holder or any property but
   `Review rank` and `GitHub PR`, and no view but the Review view's sort.** Where one looks wrong — shipped work with no
   Lifecycle, a ticket its own comments call finished — the plan *flags* it for
   the human. Moving a ticket through the workflow is theirs.
2. **Never edit code.** The `:lite` worktree is a symlink to brian's main
   checkout. You read it to understand a change; you write only to Notion, and
   only after approval.

## 1. Say you have started

The session briefing names the run's status file and artifacts dir. Write:

```bash
cat > <run-status-path> <<'EDN'
{:phase :working :note "Reading the Review queue"}
EDN
```

## 2. Read the queue

```bash
notion db query 124fca9f-403c-80d4-896f-fc857e105e35 --all --format json --filter-json '
{"and":[{"property":"Status","status":{"equals":"Review"}},
        {"or":[{"property":"Lifecycle","select":{"equals":"Development"}},
               {"property":"Lifecycle","select":{"equals":"Staging"}},
               {"property":"Lifecycle","select":{"equals":"Prod"}}]}]}'
```

**That filter is the scope, and nothing else is.** Code Review is the
developer's stage, not QA's. Review with a blank Lifecycle is outside it too —
but a ticket there whose comments say it is running in production is exactly
what a human wants to hear about, so run the same query once with
`"Lifecycle": {"is_empty": true}` and list those under **Flags** without
planning anything for them. The Lifecycle clause for that query is
`{"property":"Lifecycle","select":{"is_empty":true}}`.

Also read which tickets carry a `Review rank` now
(`{"property":"Review rank","number":{"is_not_empty":true}}`) — a ticket that
has left the queue keeps its number until something clears it (§5). No such
property yet → this is the first run; skip the query, and the plan proposes
adding it.

**Read the Review view** — the view the queue is read in: the one named `Review`
on the linked database of the Tech Work page (database
`330fca9f-403c-80c4-b087-e35da50a5b6d`).

```bash
notion api GET "/v1/views?database_id=330fca9f403c80c4b087e35da50a5b6d"   # ids
notion api GET "/v1/views/<view-id>"                                       # name, sorts
```

A view's `sorts` name properties by id — the `Review rank` property's id is in
`notion api GET /v1/data_sources/<data-source-id>`. When the Review view's sorts do
not lead with `Review rank` ascending, the plan holds sorting it as one numbered
item. No view named `Review` there, or more than one → a flag, and no sort item:
the plan cannot say which view the queue is read in.

For each ticket in scope, read everything a reviewer would, and what they
would not:

```bash
notion page view <id> --format json          # properties + last_edited_time
notion page markdown <id>                    # the body
notion block list <id> --depth 3 --format json   # block ids + types, for the plan
notion comment list <id> --all
gh pr view <n> -R brian-study/brian --json title,body,additions,deletions,changedFiles,files,mergedAt
```

**Keep each page's `last_edited_time`.** §6 re-checks it before writing, so a
page a human touched after you read it is never overwritten from a stale read.

A `GitHub PR` property, a PR link in the body or a `Moved to Review: delivered
in the PR … release` comment names the change. The PR's body carries the layer
briefs — Claims, Verify, Out of scope — which are the best source a QA brief
has. No PR anywhere (mobile work lives in another repo) → work from the ticket
and comments, and say in the plan that the brief rests on them alone.

Reach Notion through the `notion` CLI only (`docs/reference/notion-access.md`).

## 3. Judge each ticket

### QA brief

Hold what is there to the **adequacy test in `docs/reference/qa-brief.md`**, and
record one verdict:

- **keep** — in that document's shape and passes all four. Nothing to write.
- **correct** — in that shape, fails only *current*. Propose the exact edit
  ("delete 'not released yet'", "drop the support workaround — it shipped").
- **restyle** — any other shape (another bot's `QA – what to test:`, a person's
  paragraph), passes 1–3. Rewrite it into the shape, keeping its steps and
  outcomes; the old blocks are replaced. Every brief on the queue ends up in one
  style.
- **write** — no instructions, or they fail 1, 2 or 3. Draft the full brief,
  to that document's structure.

"Before you start" names production for a Prod ticket, staging otherwise, in a
restyled brief as in a written one.

### Follow-up actions

Collect what a person still owes the product's people — a notification
promised "once it is released", a confirmation owed by sales or CS, a production
check someone asked for and nobody recorded — into one `Follow-up` block under
the brief, exactly as `docs/reference/qa-brief.md` § "Follow-up actions" bounds
it. Be sparing: most tickets have none, and a block that lists chores teaches
readers to skip it.

Ticket bookkeeping — a `GitHub PR` property naming the wrong PR or none, a
Lifecycle or Status that reads wrong, a missing Priority — is never a follow-up
and never written onto the ticket. It goes in `:flags`, for the person running
the grooming: it says the tooling that keeps tickets missed something.

A brief is wanted where it tells a reviewer what to do. A ticket whose Review is
a **design sign-off** — no code yet, a spec or a Figma awaiting approval — needs
no QA brief; flag that its Status reads as QA work when it is not.

### Cleanup

The goal is a page whose body is the brief and nothing else: everything that
led to it is kept, collapsed, underneath. Propose, per ticket, from these and
only these:

- **Delete machine leftovers** — an empty bug-report template ("Can you
  reproduce the bug? Yes / No"), a superseded `QA instructions` callout, blank
  paragraphs in runs, duplicate headings.
- **Delete stale text** — what the release has made false: "not released yet",
  a workaround "until this ships", "ready for review, stacked on PR …".
  Quote each deleted line in the plan.
- **Fold the original report** into one collapsed toggle under the brief and
  its `Follow-up` block, `Original report` — what the reporter or requester wrote, its quotes,
  its screenshots and videos, in their order. It is folded whole and never edited:
  it is the record of what the reporter saw, and the brief already says what a
  reviewer needs from it (self-contained, `docs/reference/qa-brief.md`).
- **Fold the engineering record** into one collapsed toggle at the bottom,
  `Engineering notes & history` — handoffs, root-cause analyses, file paths,
  plans that have since been carried out.

A ticket with no brief keeps its report in the body: without one the report is
the only account of the problem on the page.

**Folding is delete-and-recreate** — Notion's API has no move. **Uploaded media**
(an image, video, file or PDF whose type is `file`) is copied by uploading it
again: its URL expires within the hour, so the apply leg reads it fresh just
before the copy. Some blocks still cannot be folded, and stay where they are:

- **An upload that fails** — too large for the workspace, or a URL that expired
  mid-copy.
- **`child_page`, `child_database`, `synced_block`, `link_preview`** — the API
  cannot create them.
- **A block with an inline comment thread** —
  `notion api GET "/v1/comments?block_id=<block-id>"` returns any. Recreating
  the block would orphan the discussion.

Say in the plan which blocks stay put and why, so the human is not surprised by
a half-folded page.


## 4. Rank the queue

Rank answers one question: **if the reviewer does only the top few today, which
few matter most?** It is a judgement, so every rank carries its one-line
reason — the reasons are what the human approves, and a rank without one cannot
be argued with.

Weigh four things, per ticket:

| factor | high | low |
|---|---|---|
| **Consequence of a flaw** | money, access, licences, sign-in, merges, data loss — anything irreversible or that locks people out; many users or a paying customer | cosmetic, superadmin-only, trivially reverted |
| **Chance of a serious finding** | large or cross-cutting PR, concurrency, auth, billing, migrations; verified only by automated tests; contested in comments | small, local, already clicked through by someone |
| **Exposure now** | Prod — if it is wrong, it is wrong for users today; Staging — review is what gates the release | Development — not in front of anyone yet |
| **Importance of the work** | a named customer waiting, an exam or rollout date, `Priority` 0–1, other tickets `Blocked by` it | internal cleanup, no one waiting |

Order by the first three together — the risk a review retires — then by
importance. **Break ties toward the cheaper review**: two equally risky tickets,
the one a reviewer clears in ten minutes goes first. A ticket that needs a brief
written before anyone can review it ranks as if the brief existed; the plan
writes the brief.

## 5. Write the plan, then end

The page reads one file: `<artifacts-dir>/review-queue-plan.edn`. Write it with
exactly this shape — the dashboard renders it, and the apply leg is held to it:

```clojure
{:tickets [{:br        "BR-6348"
            :title     "Prevent lost course edits, missing quizzes and incorrect group access"
            :url       "https://www.notion.so/<page-id>"
            :page-id   "<page-id>"
            :lifecycle "Prod"
            :rank      1
            :why       "data loss and access for every teacher; five concurrency fixes; tests only"
            :qa        :write          ; :keep | :correct | :restyle | :write | :design-signoff
            :last-read "2026-09-28T09:14:03.000Z"}]
 :items   [{:n 1 :br "BR-6348" :kind :rank   :summary "rank → 1"}
           {:n 2 :br "BR-6348" :kind :brief  :summary "prepend a QA brief (none on the page)"
            :detail "<the full brief, exactly as it will appear>"}
           {:n 3 :br "BR-6348" :kind :fold   :summary "fold 118 blocks into Engineering notes & history"
            :detail "stays put: 1 inline-commented paragraph\n<block ids>"}
           {:n 5 :br "BR-6348" :kind :fold   :summary "fold the original report (9 blocks, 2 images) into Original report"
            :detail "<block ids>"}
           {:n 4 :br "BR-6454" :kind :delete :summary "delete the empty bug-report template (16 blocks)"
            :detail "<every deleted line, quoted>\n<block ids>"}
           {:n 9 :br nil       :kind :schema :summary "add number property Review rank to the Task Database"}
           {:n 10 :br "BR-6056" :kind :clear-rank :summary "left the queue (Status Done) — clear Review rank"}
           {:n 11 :br nil      :kind :view-sort :summary "sort the Review view by Review rank, ascending"
            :detail "view 372fca9f-403c-808b-86af-000c78baf1e2; sorts now: none"}]
 :flags   ["BR-6363, BR-6362 are in Review with no Lifecycle; their comments say running in production"
           "BR-6370 in Review is a design sign-off, not QA work"]}
```

- **`:n` is the identity** a person approves. Number every write once, globally,
  from 1.
- **`:kind`** is one of `:rank`, `:brief`, `:correct`, `:follow-up`, `:delete`,
  `:fold`, `:clear-rank`, `:schema`, `:view-sort`. A restyle is a `:brief` whose
  `:detail` also lists the old blocks it replaces. A `:follow-up` carries every
  to-do's text, and the ids of the body to-dos it moves in. `:br nil` for a write about no one ticket.
- **`:detail` is what the apply leg executes from.** Put in it everything that
  leg needs and a reviewer should read: the full brief text, every quoted line a
  delete removes, the block ids a fold or delete touches, what stays put and why.
  A person approving `:summary` alone approved something they did not see.
- Every ticket in scope appears in `:tickets` with its `:rank`, even when it has
  no write besides the rank.

Write `<artifacts-dir>/review-queue-plan.md` beside it — the same plan as prose,
for a person reading the run's artifacts — and print a one-paragraph summary in
chat. Then end:

```bash
cat > <run-status-path> <<'EDN'
{:phase :complete :note "Plan ready: <n> tickets, <w> writes — decide on Operations → Review queue"}
EDN
```

**Do not wait for an answer in this session**, and write nothing to Notion. The
decisions are made on the page; a separate Run carries them out.

## 6. The apply leg: carry out what was approved

Invoked as `/review-queue apply <plan-run-id>`. Set `{:phase :working}` on your
own run's status file, then read, from the PLAN run's artifacts dir
(`~/.nido/runs/<plan-run-id>/artifacts/`):

- `review-queue-plan.edn` — the plan, as §5 wrote it
- `review-queue-decisions.edn` — `{:decisions {n :approved|:skipped} :applying {:at …}}`

**Claim the plan before anything else:**

```bash
mkdir "<plan-run-artifacts>/apply-claim" && echo "<your-run-id>" > "<plan-run-artifacts>/apply-claim/by"
```

`mkdir` succeeds for exactly one caller. If it fails because the directory exists, another
apply run has this plan: write nothing, set your status
`{:phase :complete :note "duplicate — plan already claimed by <its run id>"}` and stop. The
page queues an apply once per plan, but a process that stops between queueing and recording
the fire can leave a plan that looks unfired, and a retry then queues a second run — this is
what keeps the second one from writing every change twice.

**Carry out every item whose decision is `:approved`, and nothing else** — not
an undecided one, not an improvement you notice on the way. Work from each
item's `:detail`; do not redraft a brief. If an approved write can no longer be
done as worded, fail that item and say why.

**Record each outcome the moment it is known**, into the plan run's
`review-queue-results.edn`, so the page shows progress while you work:

```bash
bb -e '(let [p "<plan-run-artifacts>/review-queue-results.edn"
             m (if (.exists (java.io.File. p)) (clojure.edn/read-string (slurp p)) {})]
         (spit p (pr-str (assoc m <n> {:outcome :applied :note "top of page"}))))'
```

You are the only writer of this file, so a plain read-modify-write is safe.

`:outcome` is `:applied`, `:failed` (`:note` says what Notion answered) or
`:skipped` (`:note` says why — a page edited since it was read).

Go ticket by ticket.

**Re-check first.** Read the page's `last_edited_time` again. Later than the
ticket's `:last-read` → a person has been on it: record `:skipped` for every
approved write on that ticket rather than apply a plan drawn from a page that no
longer exists.

**The schema write, if approved, goes first** — every rank needs it:

```bash
notion api PATCH /v1/data_sources/<data-source-id> --body - <<'JSON'
{"properties": {"Review rank": {"number": {"format": "number"}}}}
JSON
```

(The data-source id comes from `notion api GET /v1/databases/124fca9f-403c-80d4-896f-fc857e105e35`
→ `data_sources[0].id`; see the data-source gotcha in `notion-access.md`.)

**The view sort, if approved, goes after the schema write** — it names the
property by id, so the property has to exist. Put `Review rank` ascending first
and keep the view's other sorts after it:

```bash
notion api PATCH /v1/views/<view-id> --body - <<'JSON'
{"sorts": [{"property": "<Review rank property id>", "direction": "ascending"}, …the view's other sorts…]}
JSON
notion api GET /v1/views/<view-id>     # read it back
```

Record it `:applied` only when the sorts read back lead with `Review rank`
ascending, and `:failed` with what came back otherwise.

**Ranks:** `notion page set <id> "Review rank=<n>"`; clearing one sets it empty.

**Briefs:** write as `docs/reference/qa-brief.md` § "How it is written to Notion"
says — earlier briefs noted by id, the new one prepended, the first child read
back — and record `:applied` only when the new brief is on top; otherwise the
new one is deleted again and the item is `:failed` with "did not land on top". A **correct** is an edit of the
existing blocks' text (`notion api PATCH /v1/blocks/<block-id>`), not a new brief.
A **restyle** is written as a brief, and the old blocks it names are what step 1
notes and the final step deletes.

**Follow-ups, after the ticket's brief:** note any earlier `Follow-up` callout by
id, create the new one with
`"position": {"type": "after_block", "after_block": {"id": <brief-id>}}`, read
the page's blocks back, and only then delete the earlier callout and the body
to-dos it moved in. Notion placing it elsewhere is recorded in the result, not a
failure — the block still stands on its own.

**Folding, in this order, so nothing is lost if a call fails midway:**

1. Create the toggle with copies of the blocks inside it — `Original report`
   after the `Follow-up` block, or after the brief where there is none
   (`"position": {"type": "after_block", "after_block": {"id": <that block>}}`),
   `Engineering notes & history` at the bottom. If Notion puts the report toggle
   anywhere else, say so in the result; it is still collapsed. A request nests
   two levels; append deeper children to the returned child ids.
   An uploaded media block is copied by re-reading the block for a fresh URL,
   `notion file upload <url> --name <its name>`, and creating the copy as
   `{"type": "image", "image": {"type": "file_upload", "file_upload": {"id": <upload-id>}}}`
   (`video`, `file`, `pdf` alike). A failed upload leaves that block where it
   is; the rest still fold.
2. Read the toggle back and check every copied block is there.
3. Only then delete the originals.

Keep every deleted block id in the plan run's `deleted-blocks.edn`, by ticket.
Notion archives a deleted block rather than destroying it, so
`notion api PATCH /v1/blocks/<id> --body '{"in_trash": false}'` restores one —
that file is the undo.

**A failing write stops that ticket, not the run.** Record it and go on.

## 7. Report

When every approved item has an outcome, print the report, write it to the plan
run's `review-queue-report.md`, and set your own status
`{:phase :complete :note "<a> applied, <f> failed, <s> skipped"}`:

```
Review queue applied · plan <plan-run-id> · <a> of <w> approved writes applied

Applied
- [2] BR-6348 brief prepended (top of page)
- [3] BR-6348 118 blocks folded; 1 commented paragraph stayed put
Skipped
- [8] BR-6454 — page edited at 10:02 after it was read
Failed
- [n] BR-#### — <what Notion answered>

Undo: <plan-run-artifacts>/deleted-blocks.edn (<k> blocks)
```

## What this skill does NOT do

- **No workflow moves.** Status, Lifecycle and Ball Holder belong to people;
  anything that looks wrong is a flag (boundary 1).
- **No review.** It prepares the queue for a reviewer; it does not click
  through a single step or judge whether a change works.
- **No Code Review tickets, and no Review tickets without a Lifecycle in scope.**
  The second kind is listed, never touched.
- **No ledger entries.** The plan run's artifacts are its record — plan,
  decisions, results, undo — and there is no workstream for a queue.
- **No approvals of its own.** The plan leg never waits for an answer in chat,
  and the apply leg never widens what was approved.

## Idempotency

A fresh plan run reads the queue fresh. A brief an earlier apply wrote is in the
shape and passes the adequacy test, so it is kept; a `Follow-up` block is
rewritten only when its open actions changed; a fold already done leaves nothing to fold; ranks are
rewritten to the new order. Re-running is how the order stays current as tickets
arrive and leave.

The page freezes the decisions, queues one apply and records it; a plan whose apply is
recorded is refused. A second apply run can still arrive (see the claim in §6), and it
stops at the claim. If one stops midway, the results file says which
items were reached; run a fresh grooming rather than re-applying the old plan,
since the pages it was drawn from have moved.
