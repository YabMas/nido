# Descriptions

**Harness-side doctrine, owned by nido.** It reaches every session because the
root nido checkout is on `--add-dir`; read it at
`~/Code/nido/docs/reference/descriptions.md`. Nothing judges a line against it —
see §8.

After Google's [CL descriptions][g], the Linux kernel's [Describe your
changes][k], [Chris Beams][b] and [Tim Pope][p].

[g]: https://google.github.io/eng-practices/review/developer/cl-descriptions.html
[k]: https://www.kernel.org/doc/html/latest/process/submitting-patches.html
[b]: https://cbea.ms/git-commit/
[p]: https://tbaggery.com/2008/04/19/a-note-about-git-commit-messages.html

## 0. What this covers, and why it is one doctrine and not two

The prose that ships *with* a change and outlives it: **the commit body and the
PR description.**

They are not two artifacts. brian's merge queue runs `mergeMethod: SQUASH`, so
the collapsed PR's title and body *become* the one commit that lands on `main`
(`/land` §8) — the per-layer commits do not land at all. What you write in the
PR is what trunk keeps. Two doctrines would be two answers for the same text.

**Each project owns the spelling**, and this never overrides one — brian's
`docs/guidelines/commits.md` fixes the conventional-commit type and scope, the
lowercase imperative subject, no trailing period, and the branch name. Cite the
project's rule when the fix is a token; cite this when the fix is a judgement.

**`/stack` §5 owns the skeleton** — subject, one to three sentences, the `Layer:`
trailer, the `Claims` / `Verify` / `Lane` / `Out of scope` brief, the refs, and
the `Phase:` block when the change is phased. That is settled and this does not
reopen it. **This doctrine governs what goes in those slots, and what stays out.**

## 1. The test a line has to pass

> **A line earns its place if a reader holding this change cannot get that fact
> from anything else they can reach.**

Everything below is that test applied. What makes it decidable is that
*reachable* is a fact about each artifact, not a feeling — and the artifacts
around a change differ enormously in reach:

| what else holds this fact | can a future reader reach it? | so the description |
|---|---|---|
| **the diff** | yes — attached to the PR, and in the history forever | never restates it |
| **the code and docs this change ships** | yes — it is in the tree, and it is the authority | never re-explains them |
| **the design record in the ledger** | **no** — `~/.nido/projects/<p>/workstreams/`, one machine, unversioned, never pushed | **carries the conclusion; it is the only durable copy** |
| a Notion ticket, a Slack thread, a review comment | maybe, and it rots | summarises the point, then links |
| the tests | yes, but only if you name them | points, never narrates |

**The trunk commit is the only artifact with the same lifetime as the code.**
Every other place a rationale might live is a cache: the ledger is one laptop's
state directory, a PR thread is a vendor's database, a ticket is a subscription.
That asymmetry is the whole reason the third row inverts the first two — *don't
repeat yourself* is right about the diff and wrong about the design record,
because only one of them will still be there.

So the kernel's rule holds without amendment: **"try to make your explanation
understandable without external resources."** A reader with `git log` and the
tree must be able to reconstruct why, with nothing else open.

**The failure this actually prevents is not padding.** A long body is rarely
filler — every paragraph is interesting, which is exactly why it survived the
edit. It is long because it re-explains the file the change ships, or re-argues
a point the diff makes plainly. Interesting is not the test. Unreachable is.

## 2. Problem first, then what you did about it

> "There must be an underlying problem that motivated you to do this work."
> — the kernel

**Open with what was wrong.** Not with what you built. A body that leads with
the solution makes the reader carry it unexplained until the justification
arrives, and they will stop before it does.

Then, in order:

- **the problem** — what the system did, and why that was wrong. Concretely
  enough that a reviewer can agree it is a problem before reading on.
- **the impact** — who this bites and how. *"Describe user-visible impact"*
  (the kernel) applies even to internal changes: name what someone can now do,
  or now stop working around. A change with no statable impact is one nobody can
  prioritise.
- **what you did** — in plain English, so a reviewer can check the code does
  that. This is the shortest part, because the diff carries it (§1).
- **why this way** — only where a reader would reasonably ask, and always for a
  choice that looks wrong from outside.

Google asks for the same four and adds the one people skip: **the shortcomings
of the approach.** A known weakness stated by the author is cheap; the same
weakness found by a reviewer costs a round, and found in production costs more.

## 3. The subject line is a claim, not a label

- **About 50 characters, and never past 72.** Pope's 50/72 rule, and it is
  still load-bearing: `git log --oneline`, `gh pr list`, and every TUI truncate.
  The limit is not typography — it "forces the author to think for a moment
  about the most concise way to explain what's going on."
- **Imperative mood**, "as if you are giving orders to the codebase to change
  its behaviour" (the kernel). The test: *If applied, this commit will* ______.
  If your subject does not complete that sentence, rewrite it.
- **No "and".** Already nido's rule (`/stack`): needing "and" means it is two
  layers. It is also a description rule — a subject joining two claims lets a
  reader check neither.
- **Say what becomes true, not where bytes went.** Google lists *"Moving code
  from A to B"* among its bad descriptions for this reason: it is a true
  sentence that tells a reader nothing they could act on. So are *"fix bug"*,
  *"address feedback"*, and *"phase 1"*.

The project owns the rest of the spelling — in brian, that means lowercase after
the `type(scope):` prefix and no full stop.

## 4. Describe the change, never your route to it

`comments.md` §4 sends the transition story here, and it is worth reading what
it actually sends. Its routing table gives the commit message and PR body one
row — *"what changed, and why the change is safe"* — and consigns the remainder
to *"the history; it is already there."* That last row is §1 under another name,
written before this doctrine existed.

**This section is what happens to the story on arrival.** A rule that only
evicts narration from comments relocates it; without this, the body inherits
everything the comments were spared.

What arrives legitimately is the *before → after*: what the code used to do, and
why that was wrong (§2). **What does not arrive is the account of your afternoon.**

Keep out:

- the approach you tried first and abandoned
- what a review round told you, and how many rounds it took
- what you learned about the tooling on the way
- how a requirement was established, when the requirement is what matters

Keep in — **an alternative rejected on the merits, with the reason.** That is
not history; it is the boundary of the design, and it is the one thing a
reviewer will otherwise re-propose. It is why `:rejected` is a field in the
design record at all.

> **A rejected alternative is a property of the design. A discarded attempt is a
> property of your afternoon.**

And drop the frame. *"This PR adds…"*, *"In this commit we…"*, *"I've updated…"*
— the reader knows where they are. Write the claim.

## 5. The brief's four fields are answers, not essays

`/stack` §5 defines them; this bounds them. Each is one or two lines.

- **Claims** — one sentence, the design record's `:claim`, about *the diff*.
- **Verify** — checks a reviewer can run or look at. **Not the account of how
  you convinced yourself.** If a line does not tell the reviewer where to look
  or what to run, it is not a check — it is §4 narration wearing a field name.
- **Lane** — a name.
- **Out of scope** — what not to flag, and where it went instead. One clause
  each: a layer above, a spun-out ref, a later phase, or a decline with its
  reason.

A field that needs a paragraph is almost always carrying §4 narration or §1
restatement. Cut it there, not by trimming words evenly.

## 6. The budget is a smell, not a limit

**The prose is about 15 lines; each brief field is about 2, on top.** The
budget covers what you write freely — the paragraphs between the subject and
the `Layer:` trailer. The brief is bounded per field (§5), not squeezed into
the same allowance, because it is mandated: a change cannot drop `Verify` to
buy another paragraph. For calibration, this repo's own median commit body is
19 lines and ~200 words all in.

**Going over is a signal to re-read against §1 — never to truncate.** A change
that genuinely needs 25 lines gets 25; a compressed description of a subtle
change is worse than a long one, because it costs the reader the reconstruction
it saved the author.

But read the overrun before you accept it, because it usually has one of two
causes and only the first is about prose:

1. **The body restates something reachable** (§1). Delete, do not compress.
2. **The change is too big to describe.** Then the finding is not about the
   description at all — it is about the batch, and the remedy is `/stack` or
   `/spin-out`, not a shorter paragraph.

That second case is `comments.md` §2 one level up. An interface comment that
turns awkward is reporting a bad abstraction, not bad prose; **a description
that will not come in under budget is reporting a batch that should have been
two.** Both fail the same way if you answer them with better writing: fluent
prose seals the defect and destroys the signal.

## 7. Re-read it against the final diff

> "Review the CL description before submitting the CL, to ensure that the
> description still reflects what the CL does." — Google

This is the rule most often skipped, and the harness makes it sharper rather
than softer. `/squash` regenerates bodies from commits, `/drive-home`
regenerates PR descriptions on finish, and review rounds change the diff
underneath a body written before them. **A description written against layer 3
and landed against layer 5 is not stale — it is false**, and under §0 it is
false on trunk permanently.

So the last act before landing is to read the body against the diff it now
describes. Nothing else will catch it (§8).

## 8. What this doctrine deliberately does not do

- **It does not own the subject's spelling** — type, scope, case, ticket-ref
  shape are the project's (§0).
- **It does not reopen the skeleton.** `/stack` §5 fixes the slots.
- **Nothing checks it.** There is no lane and no pre-flight gate: the session
  briefing is the only thing that carries it, and re-reading (§7) is the only
  thing that applies it. That is a deliberate choice, not an omission — but it
  means a description defect is caught by the author or not at all.
- **It does not govern the ledger's own `:summary` fields, Notion properties, or
  workstream refs.** Those are one-liners with their own callers, and
  `/prepare-draft-pr` already says what goes in them.
