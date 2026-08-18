---
name: design
description: State the high-level design a change commits to, and record it in the ledger — the frame that /stack and /spin-out cut against. The four layers of design in a project, what belongs in a design record and what does not, how to infer the current design safely, and how to amend a design that turned out wrong. Usage: /design
---

# /design

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/design/`
> and is injected into every spawned session's composed `.claude/skills/`.
> Sibling of `/stack` and `/spin-out`.

## What this is

`/stack` cuts work **vertically** — same story, ordered by dependency.
`/spin-out` cuts **horizontally** — a different story altogether. **This skill is
not a third axis. It is the frame both of them cut against.** They route work;
this says what the work is *for*.

**Invoke it before either of them, at planning time.** A design authored at ship
time is a description, not a decision — and by then the layers have already been
cut against nothing.

## 0. What is being optimised

The same thing the shipping doctrine optimises: **change landed per unit of
reviewer attention, at constant-or-rising trust.** This skill serves it from the
other end. `/stack` and `/spin-out` bound how much arrives at once; a design
record makes what arrives **evaluable**.

The failure it exists to prevent: *a finding is only meaningful relative to an
intent.* With no stated intent, "this is wrong" and "this is not what we decided"
are indistinguishable, every reviewer re-derives the design from the diff, and
they each derive a different one.

## 1. Four layers, and which one you are touching

"The design" names four things that behave differently. Confusing them is the
most common way this goes wrong.

| layer | what it is | where it lives | can a diff violate it? |
|---|---|---|---|
| **the stance** | the project's durable architectural convictions — what the system is *for* | `.claude/skills/design/stances/<project>.md` (see below) | no — it primes reasoning, it is not a checklist |
| **the current design** | what the system actually *is* right now | **nowhere — inferred per change** (§4) | no — it is a description, and a partial one |
| **the checkable layer** | concrete, citable rules | `.claude/agents/lane-*.md`, `docs/reference/` | **yes — this is the only one that can be cited against a line** |
| **the change design** | what *this* change commits to | the ledger, as a `:design` event | yes — by its own `:invariants` |

**Read the stance before you write the record.** It ships with this skill, so it
resolves from any session:

```bash
cat .claude/skills/design/stances/brian.md
```

Housing it here is interim. A stance belongs in the repo whose architecture it
describes — nido's own rule is that per-project knowledge is borrowed on demand,
never restated in the harness. It sits here because it needs to reach a session
today, and `.claude/skills/` is what the launcher already injects. When it moves,
this path is the only thing that changes. A project with no stance file yet is
not blocked: write the record, and leave `:standing :principles` out.

Two rules fall straight out of the table:

- **Cite the checkable layer, not the stance,** when you claim something is
  wrong. Asking a priming document to adjudicate a specific line produces
  confabulated specificity.
- **Cite the stance** when the question is which register you are in, whether a
  boundary is carrying its weight, or whether what the code does is intent or
  drift. That is what it is for.

## 2. The record

One `:design` event per workstream, appended to the ledger:

```clojure
{:format     :design
 :summary    "2–4 sentences: what this change makes true of the system."
 :shape      "The structural claim: which parts exist, where the boundaries
              fall, what crosses them."
 :invariants ["what must hold once this lands — one per line, checkable"]
 :standing   {:relation   :conforms          ; :conforms | :extends | :challenges
              :principles ["refs into the project's stance"]
              :note       "REQUIRED for :extends / :challenges — what, and why"}
 :assumes    [{:about "the area's current design, as inferred from the code"
               :read  ["src/order/calc.clj"]
               :drift "where that departs from the stance"}]
 :rejected   [{:alternative "…" :why-not "…"}]
 :layers     [{:claim "one sentence, no \"and\"" :mode :judgment}]
 :seams      [{:what "deliberate incompleteness" :visible-how "how a reader sees it"}]
 :open       ["design questions still unresolved"]
 :supersedes {:seq 4 :why "…"}       ; only when amending — §5
 :effort     :M}                     ; concrete; resolves a triage :squirrel
```

Required: `:format :summary :shape :invariants :standing :effort`. The append
validates and rejects a malformed record with an explain dump — fix and retry.

**What is deliberately not in it:** file lists, function names, task breakdowns,
ordering. *If it would be stale after the first commit, it is plan, not design.*
Step lists are working memory and belong in your todo state; the ledger holds
what survives the session.

### The fields that carry weight

- **`:invariants` is the yardstick.** The review judge checks findings against
  these and nothing else. Required, non-empty, and each one written so a reviewer
  could state an observation that proves it false.
- **`:rejected` is what makes a later review round decidable.** Without it,
  round three re-proposes what round zero rejected and nobody can tell whether
  that is new insight or re-litigation. With it, a finding is either *answered*
  or *the reason no longer holds*.
- **`:assumes` is the inference, captured** (§4).
- **`:layers` is the design's decomposition claim** — `/stack` realises it.
  Claim and review mode only; bookmarks, slugs and ordering are mechanics.
- **`:seams` is what makes `/spin-out`'s veto checkable.** Visibly incomplete is
  a decision; invisibly incomplete is a defect.

## 3. The principles

A claim, a test, and the failure mode it prevents.

1. **A design is a set of claims about structure, not a plan of action.** *Test:*
   every line either states something that will be true of the system, or it does
   not belong. *Failure:* a design nobody can disagree with, because it only says
   what you are going to type.
2. **Name the invariants — they are what review has to check.** *Test:* can a
   reviewer state a concrete observation that would prove this design wrong?
   *Failure:* every finding becomes a matter of taste, and no argument resolves.
3. **Record the rejected alternatives with their reasons.** The shape of a design
   is defined as much by what it is not.
4. **Declare the relation to the stance** — conforms, extends, or challenges,
   always one of the three. *Failure:* architecture erodes with no single change
   looking wrong.
5. **The design must be smaller than the change.** *Test:* can a reader hold it
   whole while weighing a review finding? If it is as long as the diff, it is a
   plan.
6. **Boundaries before mechanisms.** Say where the seams are and what crosses
   them; do not name libraries or data shapes unless the seam is *about* them.
   *Failure:* an implementation sketch that cannot survive the first surprise in
   the code, so it is abandoned rather than amended.
7. **The layering is part of the design.** If the layers cannot be stated from
   the design, the design is not decomposed yet. *Test:* every layer's `Claims:`
   traces to a line in the record.
8. **Visible incompleteness is a design decision; invisible incompleteness is a
   defect.** A design may leave a seam — then say so in `:seams`, with what makes
   it visible.
9. **Amend the design when it is wrong; never quietly patch around it** (§5).
10. **The stance earns its authority by being amendable.** A constitution nobody
    can change gets routed around instead of followed. Declaring `:challenges` is
    a sign of health.

## 4. Inferring the current design

**The current design is not written down, and for now will not be.** You infer
the slice you need, from the code, at the moment you need it. Three rules make
that safe enough to build on.

**Scope it to the blast radius.** "Infer the design" without a bound means read
the codebase. What you need is the design of *the area this change touches* — the
modules it crosses, the data that flows between them, the boundaries it will
move. Anything wider is a survey, not an inference.

**Never confuse *is* with *ought*.** Reading code tells you what is there,
including the accidents, the half-finished migrations, and the patterns that were
never decided, only copied. The stance is what separates them. The most valuable
sentence an inference produces is often:

> the current design here is X; per the stance, X is drift rather than intent

That goes in `:drift`. Deriving *ought* from a majority vote of *is* — "most
schemas here are open, so open must be the convention" — is the failure this rule
names.

**Capture it where you make it.** An inference left in your head is re-derived,
differently, by the next session. Write it into `:assumes` with what you read.
These entries accumulate: the written current design does not have to be
authored, it can be harvested from them later.

## 5. Amending a design

A design record is **mutable across the workstream and immutable once written**.
When review shows the design itself was wrong (`:invalidated`), you do not edit
the old entry — you append a new one carrying `:supersedes {:seq <n> :why "…"}`.

The superseded record stays in the ledger. That is the point: the failure mode is
a design that stayed "true" only because nobody updated it, and the second worst
is one that was quietly rewritten so the reasoning that failed is unrecoverable.

## 6. Noticing mid-work

The record is written at hour zero. The most valuable design thoughts arrive at
hour three, from inside the code — *this boundary is in the wrong place*, *these
two things are the same thing*, *the record assumed something that isn't true*.
They arrive as interruptions, and evaluating one properly means stopping the
work, so in practice they get dropped.

`/spin-out` §1 already solved this shape for work that might leave the branch:
**noticing is free, deciding is batched.** Same move here — append one line and
carry on:

```bash
echo "- <what you noticed> (<file:line>)" \
  >> ~/.nido/sessions/<project>/<session>/design-notes.md
```

The session home, **not** the worktree: the buffer must not be able to ride along
in a commit. A separate file from `spin-outs.md` because the two route
differently — a spin-out asks *where does this work go*, a design noticing asks
*is what we said still true*.

Sweep it before you ship, and route every line:

| The noticing is | Destination |
|---|---|
| a fact about the area the record didn't have | `:assumes` — amend the record |
| a question the record can't answer yet | `:open` — amend the record |
| incompleteness you are choosing to leave | `:seams` — amend, and say what makes it visible |
| the shape you committed to cannot hold | **supersede** the record (§5) |
| a different story altogether | `/spin-out` — file it, with a ref |
| true, and not worth acting on | decline it in the layer's brief, with the reason |

Nothing is lost and nothing is smuggled — the same discipline the shipping
doctrine applies to work, applied to what you learned about the design while
doing it.

**A buffer that sweeps to nothing is itself a signal.** Zero design noticings
across a substantial change usually means the record was written once and never
looked at again — which is exactly how a design stays "true" (principle 9).

## 7. Mechanics

Write the EDN to a temp file, then append it. The ledger key is the `BR-####`
(or the slack `:id` for a Slack-sourced workstream):

```bash
cat > /tmp/design.edn <<'EDN'
{:format     :design
 :summary    "…"
 :shape      "…"
 :invariants ["…"]
 :standing   {:relation :conforms}
 :effort     :M}
EDN
bb nido:ticket:append :project brian :br <BR-####> :kind design \
  :session <session> :run-id <run-id> :file /tmp/design.edn
```

Derive `<session>` from cwd and `<run-id>` from the `./run-link/` symlink target.
For a workstream with no ticket key, use `bb nido:workstream:entry:add`.

Read back what is there with `bb nido:workstream:show :project <p> :ref <ref>`.

## Common mistakes

- **Writing the design at ship time** — by then the layers are cut and the
  record is a description of what happened. Author it at planning time.
- **Listing steps.** The schema rejects `:steps`, and that is deliberate.
- **Naming no invariants, or naming unfalsifiable ones** ("the code is clean").
  The judge has nothing to check and every finding becomes taste.
- **`:conforms` by default, without looking.** An undeclared `:extends` is how
  the next change contradicts a commitment it never knew existed.
- **Citing the stance to condemn a line of code.** The stance frames; the lanes
  and `docs/reference/` check. Cite the layer that can actually be violated.
- **Inferring the whole codebase.** Scope to the blast radius (§4).
- **Codifying drift as intent** — "this is how it is done here" derived from a
  majority vote of what is there. Say it is drift, in `:drift`.
- **Editing a design that turned out wrong** instead of superseding it (§5).
- **Deciding at the moment of noticing** — that is the interrupt §6 exists to
  prevent. Capture the line; sweep at ship time.
- **Sweeping a design noticing into the code and not the record** — the fix
  lands, the record still describes a system that no longer exists.
- **Keeping the buffer in the worktree**, where it can ride along in a commit.
- **A design longer than its diff.** It is a plan; cut it back to claims.
- **Layers that do not appear in the record** — either the design is incomplete
  or the layer is smuggling a decision nobody stated (`/stack` §2).
