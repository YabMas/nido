---
name: spin-out
description: Decide what leaves the current branch and file it — the horizontal axis of the shipping doctrine. The reasoning axes for keep-vs-defer, the tiebreak order when they disagree, and the mechanics of filing to the personal follow-up DB. Sibling of /stack, which owns the vertical axis. Usage: /spin-out
---

# /spin-out

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/spin-out/`
> and is injected into every spawned session's composed `.claude/skills/`.
> The horizontal half of the doctrine `/stack` states vertically; the condensed
> version of both is resident in every session's `CLAUDE.md`.

## What this is

How to decide what belongs in the batch you are shipping and what belongs
somewhere else, and how to file the somewhere-else so it is not lost.

`/stack` cuts work **vertically** — same story, ordered by dependency. This
skill cuts **horizontally** — a different story altogether. They are the same
operation (routing a unit of work to a destination) along different axes, which
is why their tests rhyme: the one-sentence-no-"and" title test, the
review-mode test, and "does it carry its own design decisions" are all
**claim-identity** tests, and claim identity is what both axes cut along.

**Invoke this at planning time and again at ship time.** In between, capture
without deciding (§1).

**`/design` comes before both.** Claim identity is what these axes cut along,
and the design record is where the claim is written down — its `:invariants` are
what makes §3's veto checkable instead of a matter of nerve, and its `:seams`
are where a deliberate deferral is declared.

## 0. What is being optimised

**Change landed per unit of reviewer attention, at constant-or-rising trust.**
Agents make code cheap; verified comprehension is the bottleneck. Every rule
below is downstream of that, and when a rule seems to conflict with it, the rule
is wrong.

Two failure modes, symmetric. **Smuggling** — unrelated work rides along,
attention cost blows up, and the batch's claim stops being falsifiable.
**Dropping** — rot accumulates and the codebase's net trajectory turns negative.
Guidance that guards only one produces the other.

The invariant that resolves the tension: **each batch leaves the codebase better
along the axis it was already moving on.** Not better everywhere (that is scope
creep). Not merely no worse (that is ratcheting decay). The axis you are already
travelling is both your licence to ignore the mess two modules over and your
obligation not to leave your own dimension half-done.

## 1. Three moments, three different behaviours

**Planning time.** Decompose into layers (`/stack`) *and* decide what is out of
the ticket entirely. Cheapest moment; most of the value is here.

**In-flight — capture, do not decide.** The "huh, that's broken too" moment.
Evaluating six axes at every noticing is itself an interrupt, and the
alternative is forgetting. So append one line and move on:

```bash
echo "- <what you noticed> (<file:line>)" >> ~/.nido/sessions/<project>/<session>/spin-outs.md
```

The session home, **not** the worktree — the buffer must not be able to pollute
the repo or ride along in a commit. **Noticing is free; deciding is batched.**

`/design` §6 keeps a sibling buffer, `design-notes.md`, for the other kind of
noticing: not *this work might belong elsewhere* but *what we said about the
design may no longer be true*. Same discipline, different routing — if you catch
yourself unsure which buffer a line belongs in, ask who acts on it. Someone
picking up a ticket → this one. Someone amending the record → that one.

**Ship time.** Sweep the buffer, read your own diff for what it reveals, then
route every item (§2–§5) and file what leaves (§6). An item you cannot decide
is an item you keep — the branch is what you understand best right now.

## 2. The routing question

| The unit is | Destination |
|---|---|
| same story, same claim | **this layer** |
| same story, a different claim | **another layer** — `/stack` |
| a different story | **spun out** — §6 |
| a different story you will not do | **declined** — named in the brief, not filed |

**"Declined" must stay sayable.** Without it every noticing becomes a filed
ticket nobody pulls, and the legitimate items drown. Declining in the layer's
`Out of scope:` — *noticed X, not doing it, because Y* — is an honest,
reviewable act.

**Most "spin-out" instincts are actually the second row.** If the answer to
"who picks this up?" is *me, next, while I still hold this context*, it is a
layer or the next branch — not a backlog row. Route by whether the work rides
your current context, not by whether it feels separate.

## 3. The veto

**Never defer what would leave the branch untrue.** Everything else in this
skill is a judgment call; this one is not.

**And it has a concrete list.** The branch's design record names its
`:invariants` — that is what "untrue" means here, in this branch, written down
before the work started. Walk them: *does deferring this break one?* Yes → veto,
no argument, no weighing against the other axes. That question is answerable in
seconds, which is the point; a veto that depends on remembering to be principled
is a veto that fires when you are fresh and not when you are tired.

A NOT NULL one writer doesn't honour. An invariant enforced on the new path
only. A documented rule with silent exceptions. An abstraction with two call
sites migrated and eight not.

The distinction that matters: an incomplete migration is fine when it is
**visibly** incomplete — old path still standing, flag still on, a reader can
see there are two worlds — and toxic when it is **invisibly** incomplete, where
a future reader reads the inconsistency as intentional. This is `/stack`'s
independent-correctness rule applied to the ship boundary instead of the layer
boundary.

**Visible incompleteness is a design decision, so declare it as one.** A seam you
are deliberately leaving goes in the design record's `:seams`, with what makes it
visible to a reader. That is the difference between a seam and a defect: the
defect is the one nobody wrote down.

## 4. The reasoning axes

Guidelines, not gates. They will disagree; §5 breaks the tie.

**Created or revealed?** Did this branch *cause* the problem or merely *surface*
it? Created → yours, fix it here. Revealed → candidate. Near-objective, free to
evaluate, and it settles most cases before any other axis is consulted. It is
also what stops "while I was in there" from being self-justifying.

**Review cost, not lines.** The currency is reviewer working memory (`/stack`
§2: a uniform 2,000-line rename costs *O(1)*, a 200-line judgment diff costs
*O(lines)*). Deleting the path you just superseded grows the diff and *shrinks*
the review — keep it. A clever 20-line optimisation does the reverse — spin it
out.

**Does deferring need scaffolding?** A shim, an adapter, a flag, a second live
path that exists *only because you deferred* is paid for twice — built now,
removed later — and it is usually the decisive argument for doing the work now.
**If the spin-out cannot be done without leaving a seam, it is not a spin-out.**

**Effort now versus effort cold.** You are holding context a follow-up must
rebuild. Work that needs that context costs multiples cold; work that doesn't —
mechanical sweeps, renames, test backfills against stable behaviour — is nearly
free to defer. **Keep what is expensive to reload; spin out what is cheap to
resume cold.** This is the `Cold-start` field.

**Proportional to what?** "Effort proportional to gain" needs a denominator, and
the useful one is *how many future changes walk through here*. Ugliness on a hot
path pays back; ugliness in a cold corner does not, however offensive. **Fix
along the path you expect to walk again.**

**Which way does the cost of delay run?** Some work gets genuinely *cheaper*
later — more call sites converge, an upstream decision resolves, the right
abstraction becomes obvious. That is correct sequencing, not cowardice. Other
work compounds: every week more code copies the pattern you know is wrong.
Fast-compounding *and* cheap → do it now even if it is unrelated to your ticket.
This is the `Decay` field, and it is what earns the right to defer at all.

**Can you write the ticket right now?** Three sentences, a concrete acceptance
criterion, from what you know at this moment. If not, it is not a task — it is
an unresolved question, and unresolved questions are what rot. Two honest exits:
resolve it here (it turns out to be part of this work), or file it as
`:kind question` with the question stated as the title.

**Yak-shaving has a signature: recursion, not size.** "To do X I must first do
Y, and for Y I need Z." **At two levels of "first I need to…", stop and spin out
from the deepest point where a shippable batch still exists.** Paired test,
answerable in the moment: *can this branch still ship if I abandon this
sub-quest right now?* Yes → abandon and file. No → you are on the critical path;
it is not a yak.

**The remainder test.** Would you file this ticket if this branch had never
existed? If no, it is an artifact of your decomposition, not a task — it belongs
back in the branch or in the next layer, never in a backlog where a cold reader
finds an orphan.

**Standalone reviewability.** Can someone who has *not* read this branch judge
the spun-out piece? If understanding it requires reconstructing your branch, you
have split the diff, not the work.

## 5. Tiebreak order

Criteria that don't order themselves don't decide anything.

1. **Untruth is a veto** (§3).
2. **Review cost beats size.**
3. **Created-vs-revealed sets the default.**
4. **Re-entry cost and cost-of-delay break the remainder.**
5. **Still torn → ship the smaller batch and file it**, gated on the
   writable-now test and §7. Asymmetry of regret: a too-small PR is trivially
   followed up; a too-big PR is never un-merged.

## 6. Filing — mechanics

**No spin-out without a ref.** "We should clean this up later" in a PR comment
is a wish, not a deferral.

```bash
bb nido:followup:add \
  :title "drop the compat shim in work.clj" \
  :kind cleanup \
  :reason "revealed not caused; cheaper once the remaining callers converge" \
  :decay cheaper-later \
  :cold-start cheap \
  :effort S
# → filed FU-12 · https://notion.so/...
```

`:origin` and `:project` derive from cwd (worktree or session home). **Pass
`:origin <pr-url>` explicitly once a PR exists** — a PR is a better origin than
a session name, because it carries the diff the decision was made against.

| field | values |
|---|---|
| `:kind` | `cleanup` `bug-found-not-caused` `test-debt` `migration-remainder` `question` `perf` |
| `:decay` | `compounding` `flat` `cheaper-later` — §4, cost of delay |
| `:cold-start` | `cheap` `needs-context` — §4, effort cold |
| `:effort` | `XS` `S` `M` `L` `XL` `squirrel` (triage's vocabulary) |

`:title :kind :reason :decay :cold-start` are required — a deferral that doesn't
carry *why* is a shrug, and `decay`/`cold-start` are what make the band
drainable. Out-of-vocabulary values are rejected locally, before any HTTP call.

**Then name the ref in the layer's brief**, or you pay the review cost without
collecting the benefit — the reviewer flags what they cannot see was decided.
When the reason the work leaves is a design one, say that instead of paraphrasing
it — *the record puts this behind the X boundary* is checkable, "out of scope for
this PR" is not:

```
Out of scope: the compat shim in work.clj — spun out as FU-12.
```

There is **no approval gate**: this is a personal DB, so an autonomous session
files directly. That is the point of it being personal — the gate that guards
the shared Task DB would otherwise make this doctrine unusable unattended.

**Read the band with `bb nido:followup:list`** — ordered by decay pressure, not
by date.

## 7. Honesty

**A ticket nobody will ever pull is a deletion with extra steps.** For those,
either do the work now (it is cheap and you are already here) or decline it
explicitly. A DB full of never is what makes the real items invisible.

**Past ~3 spin-outs from one branch, the ticket was mis-scoped** — or you are
strip-mining. Same shape as `/stack`'s "past ~7 layers, it is two tickets": stop
and revisit the decomposition rather than filing a fourth. Both counts are
symptoms with the same usual cause — a design boundary in the wrong place — and
the record is where you can see that, so re-read it before you re-cut.

**Zero spin-outs off a large change is also a signal** — either genuinely tight,
or nobody looked.

**Say what you routed.** Report the spin-outs and the declines with their
reasons. A routing decision nobody can see is indistinguishable from an
oversight.

## Common mistakes

- **Deferring something that leaves the branch untrue** — a half-applied
  invariant is a veto, not a trade-off (§3).
- **Running the veto from memory** — the design record lists the invariants; read
  them rather than recalling them (§3).
- **Leaving a seam without declaring it** — an undeclared seam is
  indistinguishable from a defect, which is exactly how a later reader reads it
  (§3).
- **Filing what should have been a layer** — if you are picking it up next while
  the context is hot, it is `/stack`'s axis, not this one (§2).
- **Filing instead of declining** — "someone might want this someday" fills the
  band with never and hides the real items (§7).
- **Counting lines instead of review cost** — a big deletion is cheap to review;
  a small clever diff is not (§4).
- **Spinning out something that needs a shim to spin out** — you now pay twice
  and ship a seam (§4).
- **Deciding at the moment of noticing** — that is the interrupt this skill
  exists to prevent. Capture to the buffer; decide at ship time (§1).
- **Keeping the capture buffer in the worktree** — it can then ride along in a
  commit. It lives in the session home (§1).
- **Filing a ticket you cannot write** — an unresolved question is not a task;
  either resolve it or file it as `:kind question` (§4).
- **Filing without naming the ref in `Out of scope:`** — the reviewer re-derives
  it and flags it anyway (§6).
- **Omitting `:reason`** — rejected, and rightly: a deferral without its why
  cannot be triaged later (§6).
- **Reading this skill only at ship time** — by then the work is a heap and the
  only move left is post-hoc surgery. Invoke at planning time (§1).
- **Treating the four criteria as a checklist** — they disagree by design; §5 is
  the part that decides.
