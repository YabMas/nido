---
name: phase
description: Decide whether a change has to reach production in more than one landing, and plan the landings — the temporal axis of the shipping doctrine. The boundary test, the exit criterion each phase carries, the habitability rule, and where the point of no return goes. Sibling of /stack (vertical) and /spin-out (horizontal). Usage: /phase
---

# /phase

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/phase/`
> and is injected into every spawned session's composed `.claude/skills/`.
> The temporal axis; `/stack` owns the vertical one and `/spin-out` the
> horizontal one.

## What this is

How to decide that a change cannot safely arrive all at once, and how to cut it
into landings the system can live in.

`/stack` cuts **vertically** — same story, ordered by dependency, and its
boundaries are dissolved by the merge. `/spin-out` cuts **horizontally** — a
different story, which leaves the branch. This skill cuts **temporally**: same
story, same design, delivered in landings separated by a deploy.

That is the whole difference, and it is worth stating before anything else:

> **A layer boundary is dissolved by the merge. A phase boundary *is* the
> deploy.**

A stack lands in one `gh stack merge`, so no intermediate layer state is ever
observed by a running system. A phase, by construction, is observed — by
production, by users, for as long as the phase lasts.

**Invoke this at planning time.** A phase plan authored at ship time is a
description of what you got away with. And **invoke `/design` before it**: a
phase plan is part of the design, it lives on the design record as `:phases`, and
the invariants it is judged against have to say *when* they hold before there is
anything to phase.

## 0. What is being optimised

The same thing the whole doctrine optimises — **change landed per unit of
reviewer attention, at constant-or-rising trust** — served from the third side.
`/stack` and `/spin-out` bound how much arrives at once; `/design` makes what
arrives evaluable; this bounds **how much of the system is at risk in one
landing**, and buys real evidence between landings.

It is a trade, and both sides are real. It costs more landings, more ceremony,
and a cold re-entry at every boundary — the context you are holding right now is
context whoever runs phase 3 has to rebuild. It buys a smaller blast radius per
landing and an observation of the running system before you commit further.

Two failure modes, symmetric, and guidance that guards only one produces the
other:

- **Big bang.** The whole change lands at once. You find out it was wrong from
  production, at the worst possible moment, with no way back.
- **Stalled midway.** Phase 1 ships, phase 3 never does, and the system lives
  permanently with two writers, two paths and a flag nobody dares remove. This
  is the more common one **and the one this doctrine is most at risk of
  creating**, because `/spin-out` already licenses visible incompleteness and
  nothing has ever asked whether the gap got closed.

One rule resolves the tension, and it is §4:

> **Every phase must leave a system you would accept as permanent.**

Hold that and both failures are bounded. You cannot big-bang, because each state
has to stand on its own; and a stall is survivable, because stopping early is
merely a smaller win rather than a wound.

## 1. Should this be phased at all?

**No phase plan** when one deploy can carry the change and be reverted whole.
That is most changes, and the ordinary case must stay ordinary — a phase boundary
you did not need is pure cost.

**Phase** when any of these is true:

- **Something in here is irreversible** — a deletion, a data migration, an
  external side effect — and you want that step isolated, gated, and last.
- **The system cannot be stopped**, so the half-changed state is not a window
  nobody sees; it is a state real traffic runs through.
- **You need evidence from production before committing further** — a shadow
  read, a discrepancy count, a soak. The next decision genuinely depends on what
  the live system does.
- **The blast radius of one landing exceeds what you are willing to roll back at
  once.**

Target **2–4 phases**. One phase is not a plan, it is a shipment — the schema
refuses it. Past ~4 it is a **program, not a change**: decompose the story rather
than lengthening the plan. Same shape as `/stack`'s ~7 layers and `/spin-out`'s
~3 spin-outs, and the same usual cause — a design boundary in the wrong place.

**Size is not the trigger; exposure is.** A 3,000-line change that can be
reverted with one revert is a stack. A 40-line change that drops a column is a
plan. **A large change is a stack; a risky one is a plan** — and a change that is
both is a plan whose phases are stacks.

## 2. The boundary test

One question, and it is the only one:

> **Must the system run in this state?**

**No** — nobody is ever on it, it exists only between two commits inside one
merge — then it is a layer, and `/stack` owns it. **Yes** — traffic passes
through it, for minutes or for weeks — then it is a phase.

That question extends the doctrine's routing table by one row:

| The unit is | Destination |
|---|---|
| same story, same claim | **this layer** |
| same story, a different claim | **another layer** — vertical, `/stack` |
| same story, a claim that needs the previous one **live** first | **another phase** — temporal, this skill |
| a different story | **spun out** — horizontal, `/spin-out` |
| a different story you will not do | **declined** — named in the brief |

### Independent correctness is not independent deployability

This is the confusion that actually happens, so it gets its own rule.

`/stack` §3 requires that the build pass and the tests be green at every layer —
**stopping after any layer leaves a working system.** That is not the same claim
as the system being *habitable* there. A layer that adds a `NOT NULL` column with
the backfill in the layer above satisfies the layer rule at every layer and fails
the phase rule at the first one. Green is a fact about the build; habitable is a
fact about production.

### Phases and layers compose

A phase is a shipment, and a shipment may itself be a stack. Phase 2 of a plan
being a three-layer stack is normal and correct — the two axes are not
alternatives.

But the counts **multiply rather than trade**. Four phases of five layers each is
not thoroughness, it is the mis-scoping signal firing on both axes at once. Stop
and re-read the design record before re-cutting; that is where the boundary in
the wrong place is visible.

### A phase's claim is about the running system

A layer's `Claims:` is about the diff — *the rename is uniform across all 40 call
sites*. A phase's claim is about production — *both writers maintain the new
column; nothing reads it yet*. Same one-sentence-no-"and" test, different subject.

If a phase's claim can be checked by reading the diff, it is a layer.

## 3. The gate

Every phase carries an **exit criterion**: an observation of the running system
that must hold before the next phase starts. It is the field that separates a
phase from a to-do with an ordinal.

Three kinds, and saying which one decides how it gets checked:

| kind | what it is | honest when |
|---|---|---|
| `:observation` | a measurement on the live system — a counter, a discrepancy rate, an error budget | you can instrument the thing you are afraid of |
| `:soak` | elapsed time with nothing observed — a period, not a metric | the risk is a rare path and the only instrument is time |
| `:completion` | a finite job done — a backfill drained, N of N callers moved | the remaining work is countable |

**Write it so it can fail.** *"Looks fine in prod"* is not a criterion, for the
same reason *"the code is clean"* is not an invariant: nothing could disprove it,
so it is always met. `zero shadow-read mismatches for seven days` can fail. That
is what makes it a gate.

**Nothing checks it for you.** nido has no production telemetry of its own — it
polls GitHub for merges and nothing else. So a gate is **asserted with its
evidence** when the phase advances, exactly as a spin-out's `:reason` is required
and not verified. Stating that limit out loud is what keeps a gate from decaying
into a formality: the discipline is that you write down what you actually looked
at, and a reader can tell the difference between a number and a vibe.

**A gate that will not open is data.** If a phase sits behind its criterion for a
month, either the criterion was wrong or the phase was. Amend the record — or
supersede it (`/design` §5). **Do not quietly advance anyway**: that converts the
one honest signal the plan produces into a lie about it.

## 4. Habitability — the rule the layer rule is not

> **Every phase must leave a system you would accept as permanent.**

Not one that compiles, not one whose tests are green — that is the layer's
obligation, and it is a much weaker one. A state you could be **left in**,
because you might be. A reorg, an incident, a shifted priority, and phase 3 is
nobody's job. The plan that survives that is the plan where stopping early is a
smaller win rather than a wound.

This is what the record's `:habitable` field says, and it is the field that
catches the phase that is really half a phase:

- Two writers and one reader — **habitable**. Legible, revertible, and you can
  explain it to whoever is on call.
- Two writers, one of which sometimes fails silently — **not habitable**. That is
  phase 1 unfinished, wearing a phase boundary as a disguise.

### It bounds the seam that `/spin-out` licenses

`/spin-out` §3 permits deliberate incompleteness when it is *visible*. That was
only half the obligation: visibility tells a reader the gap is intentional, and
nothing told anyone it would ever be closed. So the veto now reads:

> Visibly incomplete is a **decision** when a phase closes it, or it is declared
> permanent. Visibly incomplete with nothing scheduled to close it is a wish with
> better manners.

Three honest closures, and every seam names one: a **phase** of this record's own
plan, a **spun-out** follow-up (and then the ref is the deferral — "later" in a
PR comment is still a wish), or **permanent**, which is a real answer and has to
carry its reason. The ledger refuses a seam naming a phase that is not in the
plan, which is also what stops a record with no phase plan from promising one.

## 5. Reversibility, and where the point of no return goes

Each phase says how it is taken back — answered before it ships, not during the
incident:

- `:revert` — roll back to the previous phase; `:by` says how.
- `:forward` — no way back, but a further landing fixes it; `:by` says what.
- `:none` — the point of no return; `:why` says what makes it irreversible.

**The chief output of a phase plan is knowing which phase is the one you cannot
undo.** Put it as late as the plan allows and give it the strongest gate: every
phase before it is cheap to be wrong in, and that one is not.

A plan in which *every* phase is `:none` is not a plan. It is a big bang
delivered in instalments, and it has all of the cost and none of the benefit.

## 6. The plan lives on the design record

One design record governs every landing of one story — that is what makes a phase
different from a follow-up, which resumes cold against nothing. `:phases` sits
beside `:layers`: the temporal cut and the vertical one are two claims of one
design, and both are stated before either is built.

```clojure
{:format     :design
 ;; …
 :invariants [{:invariant "no request reads a column no writer maintains"
               :holds :always}          ; checked at EVERY phase boundary
              {:invariant "exactly one writer maintains the address"
               :holds :on-completion}]  ; false during the plan, ON PURPOSE
 :phases
 [{:claim     "both writers maintain the new column; nothing reads it"
   :habitable "readers are unchanged; the new column is write-only and unobserved"
   :exit      {:kind :observation
               :criterion "shadow-read discrepancy counter flat at zero for 7 days"}
   :undo      {:how :revert :by "stop dual-writing; nothing reads the new column"}}
  {:claim     "reads move to the new column"
   :habitable "the old column is still written, so a revert is a config flip"
   :exit      {:kind :soak :criterion "one full billing cycle with no incident"}
   :undo      {:how :revert :by "flip the read path back"}}
  {:claim     "the old column is dropped"
   :habitable "one writer, one reader — the end state"
   :exit      {:kind :completion :criterion "nothing follows; the migration is done"}
   :undo      {:how :none :why "the column and its data are gone; no backup past 30 days"}}]
 :seams     [{:what        "the old column is still written through phases 1 and 2"
              :visible-how "both writers sit side by side in one namespace"
              :closed-by   :phase
              :phase       "the old column is dropped"}]}
```

**`:holds` is not bookkeeping.** A phase plan creates intermediate states that
are wrong *by design* — during the middle phase above, "exactly one writer" is
deliberately untrue — and the verdict pass judges findings against the
invariants. Without the marker it reads the plan working as the design failing,
and escalates a decision you already made. With it, an `:on-completion`
invariant is checked at the last phase and nowhere before.

**An unphased record keeps plain strings.** One landing has exactly one moment
for an invariant to hold at, so `:holds` there would be ceremony with one legal
answer. The schema dispatches on `:phases`, and it is a dispatch rather than two
optional fields on purpose: a record carrying a phase plan and bare-string
invariants is not a lenient case to wave through, it is a phase plan whose author
has not said which of its claims survive the middle of it.

### What a phase's PR says

The layer brief (`/stack` §5) is unchanged, plus one line at the top of the body
when a phase plan exists — a reviewer's first question is what state the system
is being left in:

```
Phase: 2/3 — reads move to the new column.
While live: the old column is still written, so a revert is a config flip.
Next phase opens when: one full billing cycle with no incident.
```

And `Out of scope:` gains a fourth legal form, alongside the layer above, the
spun-out ref, and the explicit decline:

```
Out of scope: dropping the old column — that is phase 3, gated on the soak.
```

Like the design citation, it is checkable rather than taken on trust, which is
what a bare "later" never is.

## 7. Between phases — where the obligation lives

The **workstream is the spine** across phases. It closes when each phase's PR
merges — the GitHub poller does that unprompted — and comes back when the gate
passes. That is the same cycle a staging findings round already runs, and closing
is right rather than a bug: a phase that landed genuinely is done, and a
workstream parked open for six weeks is board noise that trains everyone to
ignore the board.

**And this is the weakest joint in the doctrine today, so plan around it.**
Nothing sweeps for settled workstreams with phases remaining, and nothing puts
one back on a board when its gate comes due. Until that exists (it is phase 2 of
the change that introduced this skill), the obligation is carried by whoever
holds it. Two things make that survivable, and both are cheap:

- **Name the next phase and its gate in the last layer's `Out of scope:`**, so
  the obligation is in the PR a reviewer actually reads.
- **Put the exit criterion somewhere you will see it** — the ticket, the
  follow-up band, a calendar reminder. Not only in a ledger nobody re-reads.
  A gate whose only home is the design record is a gate nobody will check.

## 8. Mechanics

### Starting a phased change

1. `/design` §4 — the `:baseline`, before you decide anything.
2. The `:design` record, with `:phases`, `:holds` on every invariant, and every
   seam naming its closure. The append validates and rejects a malformed record
   with an explain dump; it also refuses a seam naming a phase that is not in
   the plan.
3. Build phase 1 — as a stack if it has dependency seams (`/stack`), as one plain
   PR if it does not.
4. Ship it. The workstream closes on merge.

### Advancing a phase

**There is no `phase:advance` verb yet** — that is the declared seam, and the
manual path is a choice rather than an omission. By hand:

```bash
# 1. Assert the gate, with its evidence. If you cannot name what you looked at,
#    the phase is not ready — that is the check doing its job.
# 2. Re-open the settled workstream. reopen! is what clears :closed;
#    stage:advance alone does not, and :in-progress runs the full promote gesture.
bb -e '(require (quote [nido.coordinator.workstream :as ws]))
       (ws/reopen! :<project> "<ws-id>" :in-progress)'

# 3. Amend the design record: append a new :design carrying :supersedes
#    {:seq <n> :why "phase 1 landed; gate met by <evidence>"}. Never edit the old
#    entry — the superseded record staying put is the point (/design §5).
bb nido:workstream:entry:add :project <p> :ws-id <ws-id> :kind design :file /tmp/design.edn
```

Then build phase N+1 in a fresh session, which reads the ledger through
`/continue-ticket` and finds the plan waiting for it.

**What would automate each step**, so the seam is legible rather than merely
unbuilt: a `:phase-landed` ledger event recording the gate and its evidence; a
live `:phase` tracker on the workstream record, sibling of `:findings`; a
`nido.coordinator.phase/advance!` modelled on `findings/file!`, which already
does assert-settled → append → seed tracker → reopen → enqueue a session; and a
derived board band for a settled workstream with phases remaining, so the
obligation stops depending on memory.

### Reading the plan back

```bash
bb nido:workstream:show :project <p> :ref <ref>
```

The design record renders its phases with each one's gate, what is live
meanwhile, and its undo — the point of no return in bold.

## Common mistakes

- **Phasing a change that one deploy could carry.** Every boundary costs a
  deploy, a wait and a cold re-entry. Phase for exposure, never for size (§1).
- **Calling a layer a phase.** If nobody is ever on that state, it is a layer and
  `/stack` owns it — the merge dissolves the boundary anyway (§2).
- **Reading `/stack`'s independent correctness as independent deployability.**
  Green build ≠ habitable system, and the gap is exactly where a `NOT NULL`
  column with a backfill above it hides (§2).
- **A phase with no exit criterion.** That is a to-do with an ordinal. Same rule
  as `/spin-out`: if you cannot state the acceptance criterion, it is not a task
  (§3).
- **A criterion that cannot fail.** "Looks fine in prod" is always met, so it
  checks nothing (§3).
- **Advancing through a gate that did not open**, because the plan says it is
  time. The gate not opening is the most valuable thing the plan produces (§3).
- **A phase you would not accept as permanent.** You may be left in it. That is
  the rule, not a caution (§4).
- **Leaving a seam with nothing scheduled to close it** — the ledger refuses one
  that names a phantom phase, and "permanent" is a real answer that has to carry
  its reason (§4).
- **Every phase `:none`.** A big bang in instalments — all the cost, none of the
  benefit (§5).
- **Putting the irreversible phase early.** It is the one thing the plan exists
  to isolate; it goes as late as the plan allows, behind the strongest gate (§5).
- **Writing a phase plan with plain-string invariants.** Refused on write, and
  rightly: without `:holds` the verdict pass reads the middle of your migration
  as a broken design (§6).
- **More than ~4 phases**, or four phases of five layers each. That is a program
  and a mis-scoped ticket; re-read the design record before re-cutting (§1, §2).
- **Leaving the next phase's gate only in the ledger.** Nothing sweeps for it
  today. Name it in the last layer's `Out of scope:` and put the criterion where
  you will see it (§7).
- **Editing the design record to advance a phase** instead of superseding it.
  The superseded record staying in the ledger is what makes the reasoning
  recoverable (§8, `/design` §5).
- **Reading this skill at ship time.** By then the change has already landed all
  at once, and the only phase plan available is a description of what you got
  away with.
