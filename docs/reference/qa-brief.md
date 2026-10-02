# The QA brief on a brian ticket

The person who checks a change on staging or production reads the Notion
ticket, not the PR, and usually has never seen either. The QA brief is the
part of the ticket written for that person: what to click and what should
happen. Two skills write one — `/land` §6 when a stack lands, `/review-queue`
when it grooms the Review queue — and both hold it, and the cleanup of the page
around it, to this page.

## The reader

Someone who knows the product and nothing else. They did not see the ticket's
history, the code, the PR or the chat. The brief must be followable by them
alone.

So it names no file, function, namespace, table, endpoint, flag or PR number,
and never leans on context: no "the fix", no "as discussed", no "see the video
in the second comment". What a comment or a video shows that the reader needs,
the brief says in its own words.

## What goes in it

    QA instructions                                   ← callout, 🧪
    What changed      1–3 sentences: the problem as a user met it, and what
                      they experience now.
    Before you start  where (staging or production), which role/account, what
                      data must exist ("a course with at least one published
                      quiz"), any setting to switch on — named as the UI names
                      it, German label beside the English one where the UI has
                      both.
    Steps             numbered; each is one action, then "Expected: …".
    Also check        1–3 nearby behaviours a user relies on that the change
                      could plausibly disturb.
    Not in this change  what a reviewer might expect but will not find — so it
                      is not filed as a bug.

Draw it from the change's own record: the PR's layer briefs (Claims, Verify,
Out of scope), the ticket and its comments, the design record — then translate
every item into what a user does and sees. A check with no user-visible form (a
migration, a log line, a query count) is dropped, not translated into jargon. If
nothing the change does is visible, the brief says so in one line and names what
to check still works.

Never write a step the change does not back, and never claim something was
tested.

## Is an existing brief adequate?

A ticket may already carry QA instructions — a person's, or another bot's
`QA – what to test:` block. Judge its substance against all of these:

1. **Self-contained** — followable without reading anything else on the page,
   the comments or a linked video.
2. **Product terms** — nothing from the "names no" list above.
3. **Steps with outcomes** — each action says what should happen.
4. **Current** — it describes what shipped, not a plan, and says nothing that
   the release has made false ("not released yet", "until this ships").

Every ticket's brief has the one shape above, so a reviewer moving down the
queue reads the same thing in the same place. What to do with an existing one
follows from its substance and its shape:

- **In this shape, passes all four** — keep it.
- **In this shape, fails only 4** — correct it in place.
- **Any other shape, passes 1–3** — restyle it: the same steps and outcomes,
  rewritten into the sections above, with anything 4 fails dropped. Its
  substance is not re-derived; a person already worked it out.
- **Fails 1, 2 or 3** — write a new one.

## Follow-up actions

Something a person still owes the product's people once the change is out —
tell the customer who reported it, have sales confirm the new view is theirs,
check on production what someone asked to be checked there — is not a QA step,
and buried in the body it is not seen. It goes in its own callout pinned to the
top of the page, above the brief — the one thing on the ticket still undone is
what whoever opens it should read first:

    Follow-up                                          ← callout, 📌
    ☐ one to-do per action: what, and who if the ticket names them —
      "Tell Daniel Riniker (St. Gallen) that the fix is live"

It is written for the same reader as the brief, in the same product terms.
What belongs:

- **communication** owed to a customer, a colleague or a team about this change;
- **a confirmation** someone outside engineering still has to give;
- **a check on production** that a person asked for and nobody recorded doing,
  said as what to look at, not how.

What does not: the ticket's own bookkeeping — a wrong or empty `GitHub PR`
property, a Lifecycle or Status that reads wrong, a missing Priority. Those are
faults in how the ticket was kept, not work the product owes anyone, and they
are reported to whoever runs the tooling, never written onto the ticket. A
question for the reviewer to answer while testing belongs in the brief's steps.

Only actions still open: an unchecked to-do elsewhere on the page that passes
the above moves in here, a checked one does not. No open actions, no block.
The block is found and replaced by its first text, `Follow-up`, as the brief is,
and written the same way: noted by id, prepended with `"position": {"type":
"start"}`, the first child read back, and only then the earlier one deleted — or
the new one, if it did not land first.

## Release blocker

A change that alters user-facing product behaviour — something **learners,
teachers or school admins** will see or do differently once it reaches
production — should be reviewed before it gets there. On this board that is
said by `Priority` `0 – Release Blocker`.

Not a blocker:

- superadmin screens and internal tooling;
- a bug fix that restores behaviour users already had;
- a ticket already in production — there is no release left for it to hold.

If unsure, propose it, and say why in one sentence; the reason is what the
person approving reads. Only ever raise: never clear or lower a Priority a
person set. (This overloads `Priority`, which otherwise ranks work before it is
built. It is accepted for now and will be revisited.)

**The mark reaches further than the web release.** brian-mobile's release gate
(`tool/release_blocker.dart check`) holds a mobile release while any
`0 – Release Blocker` ticket is not Done and either carries `App Domain`
`Student`, or carries no `App Domain` and belongs to the mobile release owner.
A web change marked a blocker under those conditions holds the next app release
too, until its ticket is Done. Say so beside the reason whenever it applies.

`/land` sets the mark itself when it lands the change, unless it would hold the
mobile release; `/review-queue` proposes it for a person to approve, and is
where a mark `/land` held back reaches one.

## How it is written to Notion

One callout block whose text starts `QA instructions`, with the sections nested
inside it. That text is how a later run finds an earlier brief to replace, so a
ticket never carries two.

A brief is written on top or not at all — where "on top" is the page's first
block, or its second directly under a `Follow-up` callout that leads the page. In
this order:

1. **Note the earlier briefs by id.** List the page's top-level blocks and keep
   the ids of every callout whose text starts `QA instructions`, plus the blocks
   of a brief in another shape that this one restyles. From here on they are
   deleted by those ids — never found again by their title, which the new brief
   now shares.
2. **Write the new one on top** — `PATCH /v1/blocks/<page>/children` with
   `"position": {"type": "start"}`, or `{"type": "after_block", "after_block":
   {"id": <follow-up-id>}}` when a `Follow-up` callout leads the page — and keep
   the id Notion returns for it.
3. **Read the first two children back.** Notion can ignore `position` and append
   at the bottom instead (`nido.notion.client/prepend-block-children!` documents
   the same caveat).
   - **The new brief is on top** → delete the ids noted in step 1. Written.
   - **It is not** → delete the new brief by the id from step 2, leave the
     earlier ones as they were, and record the write as failed: "did not land on
     top". A brief at the bottom is not a brief a reviewer reads first, and
     leaving it would also leave the ticket with two.

Keep each rich-text run under 2000 characters: one oversized run rejects the
whole request.

## Cleanup

The goal is a page whose body is the brief and nothing else: everything that
led to it is kept, collapsed, underneath. Cleanup follows a brief that is on
top; a ticket with no brief keeps its report in the body, because without one
the report is the only account of the problem on the page. These and only these:

- **Delete machine leftovers** — an empty bug-report template ("Can you
  reproduce the bug? Yes / No"), a superseded `QA instructions` callout or
  another bot's `QA – what to test:` block the brief replaces, `GitHub PR: <url>`
  paragraphs naming a PR that closed unmerged, blank paragraphs in runs,
  duplicate headings.
- **Delete stale text** — what the change has made false: "not released yet",
  a workaround "until this ships", "ready for review, stacked on PR …".
  Each deleted line is quoted in the run's report.
- **Fold the original report** into one collapsed toggle directly under the
  brief, `Original report` — what the reporter or requester wrote, its quotes,
  its screenshots and videos, in their order. It is folded whole and never
  edited: it is the record of what the reporter saw, and the brief already says
  what a reviewer needs from it.
- **Fold the engineering record** into one collapsed toggle at the bottom,
  `Engineering notes & history` — triage notes, handoffs, root-cause analyses,
  file paths, plans that have since been carried out, and a person's QA notes
  in another shape that the brief supersedes.

Nothing else is touched: comments, the title, and properties are not page body.
A toggle that already exists from an earlier run is appended to, never doubled.

**Folding is delete-and-recreate** — Notion's API has no move. **Uploaded media**
(an image, video, file or PDF whose type is `file`) is copied by uploading it
again: its URL expires within the hour, so it is read fresh just before the
copy. Some blocks cannot be folded, and stay where they are:

- **An upload that fails** — too large for the workspace, or a URL that expired
  mid-copy.
- **`child_page`, `child_database`, `synced_block`, `link_preview`** — the API
  cannot create them.
- **A block with an inline comment thread** —
  `notion api GET "/v1/comments?block_id=<block-id>"` returns any. Recreating
  the block would orphan the discussion.

The run's report names which blocks stayed put and why, so nobody is surprised
by a half-folded page.

**Fold in this order, so nothing is lost if a call fails midway:**

1. Create the toggle with copies of the blocks inside it — `Original report`
   directly after the brief
   (`"position": {"type": "after_block", "after_block": {"id": <brief-id>}}`),
   `Engineering notes & history` at the bottom. If Notion puts the report toggle
   anywhere else, say so; it is still collapsed. A request nests two levels;
   append deeper children to the returned child ids.
   An uploaded media block is copied by re-reading the block for a fresh URL,
   `notion file upload <url> --name <its name>`, and creating the copy as
   `{"type": "image", "image": {"type": "file_upload", "file_upload": {"id": <upload-id>}}}`
   (`video`, `file`, `pdf` alike). A failed upload leaves that block where it
   is; the rest still fold.
2. Read the toggle back and check every copied block is there.
3. Only then delete the originals.

Keep every deleted block id. Notion archives a deleted block rather than
destroying it, so
`notion api PATCH /v1/blocks/<id> --body '{"in_trash": false}'` restores one —
that list is the undo.
