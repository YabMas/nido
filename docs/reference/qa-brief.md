# The QA brief on a brian ticket

The person who checks a change on staging or production reads the Notion
ticket, not the PR, and usually has never seen either. The QA brief is the
part of the ticket written for that person: what to click and what should
happen. Two skills write one — `/land` §6 when a stack lands, `/review-queue`
when it grooms the Review queue — and both hold it to this page.

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
`QA – what to test:` block. Keep it when all of these hold; rewrite it when any
fails:

1. **Self-contained** — followable without reading anything else on the page,
   the comments or a linked video.
2. **Product terms** — nothing from the "names no" list above.
3. **Steps with outcomes** — each action says what should happen.
4. **Current** — it describes what shipped, not a plan, and says nothing that
   the release has made false ("not released yet", "until this ships").

A brief that passes 1–3 and fails only 4 is corrected in place, not rewritten.

## How it is written to Notion

One callout block whose text starts `QA instructions`, with the sections nested
inside it. That text is how a later run finds an earlier brief to replace, so a
ticket never carries two.

A brief is written on top or not at all. In this order:

1. **Note the earlier briefs by id.** List the page's top-level blocks and keep
   the ids of every callout whose text starts `QA instructions`. From here on
   they are deleted by those ids — never found again by their title, which the
   new brief now shares.
2. **Prepend the new one** — `PATCH /v1/blocks/<page>/children` with
   `"position": {"type": "start"}` — and keep the id Notion returns for it.
3. **Read the first child back.** Notion can ignore `position` and append at the
   bottom instead (`nido.notion.client/prepend-block-children!` documents the
   same caveat).
   - **It is the new brief** → delete the ids noted in step 1. Written.
   - **It is not** → delete the new brief by the id from step 2, leave the
     earlier ones as they were, and record the write as failed: "did not land on
     top". A brief at the bottom is not a brief a reviewer reads first, and
     leaving it would also leave the ticket with two.

Keep each rich-text run under 2000 characters: one oversized run rejects the
whole request.
