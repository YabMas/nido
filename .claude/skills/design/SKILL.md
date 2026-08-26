---
name: design
description: Survey the area's current design, then state what the change commits to relative to it, both recorded in the ledger — the frame that /stack and /spin-out cut against. The four layers of design in a project, how to write a baseline and what belongs in a design record, how to tell an implementation defect from a design defect, and how to amend a design that turned out wrong. Usage: /design
---

# /design

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/design/`
> and is injected into every spawned session's composed `.claude/skills/`.
> Sibling of `/stack` and `/spin-out`.

## What this is

`/stack` cuts work **vertically** — same story, ordered by dependency.
`/spin-out` cuts **horizontally** — a different story altogether. `/phase` cuts
**temporally** — same story, landings separated by a deploy. **This skill is not
a fourth axis. It is the frame all three cut against.** They route work; this
says what the work is *for*.

**Invoke it before any of them, at planning time.** A design authored at ship
time is a description, not a decision — and by then the layers have already been
cut against nothing.

## 0. What is being optimised

The same thing the shipping doctrine optimises: **change landed per unit of
reviewer attention, at constant-or-rising trust.** This skill serves it from the
other end. `/stack` and `/spin-out` bound how much arrives at once and `/phase` bounds how
much is at risk in one landing; a design record makes what arrives **evaluable**.

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
| **the current design** | what the system actually *is* right now | the ledger, as a `:baseline` event (§4) | not violated — but it can be *falsified*, and that is a different failure from a wrong design |
| **the checkable layer** | concrete, citable rules | `.claude/agents/lane-*.md`, `docs/reference/` | **yes — this is the only one that can be cited against a line** |
| **the change design** | what *this* change commits to | the ledger, as a `:design` event | yes — by its own `:invariants` |

**Read the stance before you survey.** It ships with this skill, so it
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
  boundary is carrying its weight, whether a structure is complexity the problem
  ever asked for, or whether what the code does is intent or drift. That is what
  it is for.

## 2. The record

One `:design` event per workstream, appended to the ledger:

```clojure
{:format     :design
 :summary    "2–4 sentences: what this change makes true of the system."
 :shape      "The structural claim: which parts exist, where the boundaries
              fall, what crosses them."
 :invariants ["what must hold once this lands — one per line, checkable"]
              ;; a PHASED record writes maps instead, each saying when it holds:
              ;; [{:invariant "…" :holds :always | :on-completion}]
 :standing   {:relation   :conforms          ; :conforms | :extends | :challenges
              :principles ["refs into the project's stance"]
              :note       "REQUIRED for :extends / :challenges — what, and why"}
 :baseline   {:seq      2                  ; the :baseline entry this was judged against
              :relation :within            ; :within | :extends | :revisit
              :breaks   ["load-bearing property that cannot survive"]  ; REQUIRED for :revisit
              :note     "REQUIRED for :extends / :revisit"}
 :rejected   [{:alternative "…" :why-not "…"}]
 :layers     [{:claim "one sentence, no \"and\"" :mode :judgment}]
 :phases     [{:claim     "what is true of the RUNNING system once this lands"
                :habitable "what is true of it while this phase is live"
                :exit      {:kind :observation :criterion "what must be observed first"}
                :undo      {:how :revert :by "…"}}]  ; only when phased — see /phase
 :seams      [{:what "deliberate incompleteness" :visible-how "how a reader sees it"
                :closed-by :phase :phase "the phase that closes it"}]
              ;; …or :closed-by :spun-out :ref "FU-12", or :permanent :why "…"
 :open       ["design questions still unresolved"]
 :supersedes {:seq 4 :why "…"}       ; only when amending — §5
 :effort     :M}                     ; concrete; resolves a triage :squirrel
```

Required: `:format :summary :shape :invariants :standing :baseline :effort`. The
append validates and rejects a malformed record with an explain dump — fix and
retry, and it also refuses a `:baseline :seq` that names no baseline on this
workstream.

**What is deliberately not in it:** file lists, function names, task breakdowns,
ordering. *If it would be stale after the first commit, it is plan, not design.*
Step lists are working memory and belong in your todo state; the ledger holds
what survives the session.

### The fields that carry weight

- **`:invariants` is the yardstick.** The review judge checks findings against
  these and nothing else. Required, non-empty, and each one written so a reviewer
  could state an observation that proves it false. **On a phased record each one
  also says *when* it holds** — `:always` at every phase boundary, or
  `:on-completion` — because a phase plan creates intermediate states that are
  wrong on purpose, and without the marker the judge reads the plan working as
  the design failing.
- **`:rejected` is what makes a later review round decidable.** Without it,
  round three re-proposes what round zero rejected and nobody can tell whether
  that is new insight or re-litigation. With it, a finding is either *answered*
  or *the reason no longer holds*.
- **`:routes` is where the survey's health observations get answered.** The
  baseline observed them and deliberately did not route them, because routing
  needs the change (§4a). This is the other half. Four destinations:

  | The observation | Route |
  |---|---|
  | bounds what this change may leave behind | `:constrains` |
  | you are doing it, here, in this branch | `:fix-here` |
  | a different story, filed | `:spin-out` — needs a `:ref` |
  | a different story you will not do | `:declined` — needs the reason |

  **The ledger checks this is total**: every observation routed exactly once,
  none invented, and — the one that is not a judgment call — **nothing the
  survey marked `:invisibly-incomplete?` may be spun out**. That is
  `/spin-out`'s veto, enforced where it cannot depend on remembering to be
  principled. `:fix-here` needs no reason; every form of *not doing it here*
  does.

- **`:baseline` is the relation to what was already there** (§4). Not the same
  question as `:standing`: that relates the change to the *stance*, this relates
  it to the *current design*, and a change can satisfy either while breaking the
  other. `:revisit` must name what it breaks — otherwise "the design needs
  revisiting" is a feeling, and deriving it instead of feeling it is the entire
  point of surveying first.
- **`:layers` is the design's decomposition claim** — `/stack` realises it.
  Claim and review mode only; bookmarks, slugs and ordering are mechanics.
- **`:phases` is the design's *other* decomposition claim** — `/phase` realises
  it, as `/stack` realises `:layers`. Present only when the change cannot reach
  production in one landing. Each phase claims something about the running
  system, says what is true of it while that phase is live, and names the
  observation that lets the next one start.
- **`:seams` is what makes `/spin-out`'s veto checkable.** Visibly incomplete is
  a decision; invisibly incomplete is a defect — and incompleteness nothing is
  scheduled to close is a third thing. So every seam names its closure: a
  `:phase` of this record's own plan, a `:spun-out` ref, or `:permanent` with its
  reason. The ledger refuses a seam naming a phase the plan does not contain.

## 3. The principles

A claim, a test, and the failure mode it prevents.

1. **A design is a set of claims about structure, not a plan of action.** *Test:*
   every line either states something that will be true of the system, or it does
   not belong. *Failure:* a design nobody can disagree with, because it only says
   what you are going to type.
2. **Name the invariants — they are what review has to check.** *Test:* can a
   reviewer state a concrete observation that would prove this design wrong?
   *Failure:* every finding becomes a matter of taste, and no argument resolves.
3. **Survey before you decide, and say how you stand to what you found.** *Test:*
   was every line of the baseline written before you knew the fix? *Failure:* the
   inference bends toward the change, "is" gets derived from "ought", and the
   design confirms whatever you already meant to do.
4. **Record the rejected alternatives with their reasons.** The shape of a design
   is defined as much by what it is not.
5. **Declare the relation to the stance** — conforms, extends, or challenges,
   always one of the three. *Failure:* architecture erodes with no single change
   looking wrong.
6. **The design must be smaller than the change.** *Test:* can a reader hold it
   whole while weighing a review finding? If it is as long as the diff, it is a
   plan.
7. **Boundaries before mechanisms.** Say where the seams are and what crosses
   them; do not name libraries or data shapes unless the seam is *about* them.
   *Failure:* an implementation sketch that cannot survive the first surprise in
   the code, so it is abandoned rather than amended.
8. **The layering is part of the design.** If the layers cannot be stated from
   the design, the design is not decomposed yet. *Test:* every layer's `Claims:`
   traces to a line in the record.
9. **Visible incompleteness is a design decision; invisible incompleteness is a
   defect; incompleteness nothing will close is a wish.** A design may leave a
   seam — then say so in `:seams`, with what makes it visible *and* what closes
   it. *Test:* for every seam, name the phase, the ref, or the reason it is
   permanent. *Failure:* the system lives forever with two paths and a flag
   nobody dares remove, and no single change ever looked wrong.
10. **Amend the design when it is wrong; never quietly patch around it** (§5).
11. **The stance earns its authority by being amendable.** A constitution nobody
    can change gets routed around instead of followed. Declaring `:challenges` is
    a sign of health.

## 4. The baseline — survey before you commit

**Every workstream starts by writing down what is already there.** One
`:baseline` event, appended before the design record, describing the area as it
*is*. Then the design record declares how the change stands to it.

This exists because of a specific failure. The inference used to live inside the
design record, as `:assumes` — a field in the document that also states the
commitment, written by someone who already knew the fix. That is the one
condition under which an inference is worth nothing: you infer the design that
makes your change look right. Splitting `is` from `ought` is the whole move; the
rest of this section is how to keep them split.

### The test for what belongs in it

**Every field must be fillable without knowing the change.** If a field needs the
fix, the effort, or the intended shape to fill in, it belongs in the design
record. The schema enforces this by being closed, but the discipline is yours: a
baseline written with the fix in mind will pass validation and still be worthless.

### Scope to the design that governs, not the files you will touch

The blast radius is defined by the change. **The design flaw is routinely
upstream of it.** Scoping to what a diff would touch is how a survey confirms
whatever the fix already assumed — so bound it by what *governs the behaviour*,
and say where you put the boundary in `:bounded-by`. That field is required
because scoping is the first claim the record makes, and the only guard against
both failure modes: reading the whole codebase, and reading three files and
calling it a design.

### The level a survey is written at

**Describe the area as a decomposition, not as an implementation.** This is the
single decision that determines whether everything downstream works, so it comes
before the field list.

The frame is *Out of the Tarpit* plus one level down. Of everything in the area,
which part does the **problem** require and which part is this **solution's**
doing? And how is it cut into modules — in Parnas's sense, where a module is
what it **hides**, not what it contains?

- **`:modules`** — one entry per module: what it `:hides` (the design decision
  nothing outside may depend on — a storage choice, a wire format, an ordering,
  an algorithm) and its `:interface` (what the rest is allowed to know). A module
  that hides nothing is a file. Two modules hiding the same decision are one
  module with a seam drawn through it.
- **`:composition`** — how those produce the required behaviour. This is where
  the area's actual design lives: *only the aggregate can see the lines, so only
  it can sum them*. If you cannot write this sentence, you have a file list.

**Why this and not file-level truth.** A survey pitched at the implementation has
no fixed point. Ask a judge whether a description of a large subsystem is true
and it will find true things about it forever — every predicate, every query, an
unbounded supply. A decomposition is a small closed set of claims, so checking it
terminates. That is not a stylistic preference; it is the difference between a
review that converges and one that does not.

It also draws the line that keeps the passes apart: **a bug in code the survey
correctly describes is not a finding here.** That is code review's job. Confusing
the two is how a design round spends its rounds auditing SQL.

### The two lists are what do the work

- **`:load-bearing`** — not what *ought* to hold; what would break if you
  violated it. This is what makes the routing question answerable by derivation
  rather than taste:

  > behaviour that violates one of these is an **implementation** defect;
  > behaviour that honours every one of them and is still wrong indicts the
  > **design**.

  **`:id`** is a short stable slug, and it is what lets a review round mean
  anything across amendments. Health observations have carried one from the
  start so a design record can route them; a claim needs one so a *review* can
  say which claim it refuted, and so the next round can say whether that claim is
  now right or still wrong. It never changes once written, however much the
  wording does — a new id means a new claim nobody has judged.

  **`:falsified-by`** is required, and names the counterexample: the thing that,
  if it exists, makes this claim false. It replaced a required `file:line`, and
  it is the *harder* obligation — a counterexample has to be constructed, a
  coordinate only located. The old rule quietly meant only claims *with* a
  coordinate could be made, which excluded every claim about the decomposition,
  because a composition property is true of no single line.

  `:evidence` is still there and now optional: where you looked, when there is
  somewhere to look.

### `:readings` — the borrowed perspectives

This is where the analysis lives, and it replaced a flat `:kind` that had crushed
three unrelated questions into one enum. `essential / derived / accidental` is
not a property of design claims in general — **it is Out of the Tar Pit's verdict
about state**, and it means nothing applied to anything else.

So a claim or a module carries zero or more *readings*, each naming a **lens**: one
source's view of one subject, with a closed set of verdicts.

```clojure
{:property     "the denominator is read once per request and applied to every instant"
 :falsified-by "a caller that re-reads it per instant"
 :readings [{:lens :tarpit/control :verdict :imposed
             :because "nothing in the problem requires one read to serve every
                       instant; it is here because a per-instant read was expensive"}]}
```

The seeded lenses:

| lens | subject | verdicts | from |
|---|---|---|---|
| `:tarpit/state` | a claim about state | `:essential` `:derived` `:accidental` | *Out of the Tar Pit* |
| `:tarpit/control` | a claim about ordering | `:required` `:imposed` | *Out of the Tar Pit* |
| `:ousterhout/depth` | a module | `:deep` `:shallow` | *A Philosophy of Software Design* |

**What counts as a module's interface**: what is *depended on from outside* — Parnas's "what others may assume" — not what the namespace makes public. A var published and called by nobody outside is a visibility choice, not interface. Two live rounds were spent on this exact disagreement, one side counting callers and the other counting `defn`s, both correct about different things.
| `:parnas/dependency` | a claim about a dependency | `:on-interface` `:on-secret` `:cyclic` | *On the Criteria…* (Parnas) |

Three rules the ledger enforces:

- **A verdict must come from its own lens's vocabulary.** `:accidental` means
  something precise in Out of the Tar Pit's view of state and nothing at all in
  Ousterhout's view of a module.
- **A lens only reads the subject it is about.** Module depth says nothing about
  a claim; state says nothing about a module.
- **`:because` is required.** A verdict without a reason is a label, and labels
  are what reading through a perspective is meant to replace.

**A reading is itself refutable**, which is the point of borrowing a closed
vocabulary rather than writing prose. State read as `:essential` is refuted by a
derivation that computes it. An ordering read as `:required` is refuted by
showing the two things commute. A module read as `:deep` is refuted by an
interface that costs about what it hides. A dependency read as `:on-interface` is
refuted by a caller reaching past it.

**The set is extendable, and deliberately small to start.** A lens nobody uses is
noise in every prompt. `nido.coordinator.report/lenses` is the registry; adding a
perspective is adding an entry, and the schema, the prompts and the weakening
detector all derive from it.

- **`:extension-points`** — where the design already admits change, and how. The
  same question asked of a feature: one that lands on an existing point
  `:extends` the design; one that needs a point which is not there is asking the
  core to move, which is `:revisit`.

One bit, derived rather than judged, and **the same bit for a bug and a
feature**. That symmetry is the point — it is what makes this a way of working
rather than a bug-triage nicety.

### Health — and why it is still an `is`

`:load-bearing` says what holds. `:health` says whether what holds is *sound* —
and it is the field most likely to smuggle an `ought` past you, so it has two
rules.

**It is what the survey ran into, not what an audit went looking for.** Go
looking and you will always find something, and you will have written a code
review of the whole module before the ticket starts. Bounded by the same
`:bounded-by` as everything else here.

**Split it by axis, because the halves fail differently:**

- `:axis :implementation` — a strong design shakily executed. Debt. No threat to
  what you are about to build, and the most routable class there is.
- `:axis :design` — a weak design cleanly executed. **The dangerous one**: it
  looks healthy, everything downstream inherits it, and it is what should turn
  your `:relation` from `:within` into `:revisit`.

Collapsing them gives you *"this area is a bit messy"*. Keeping them apart gives
you *"the boundary is in the wrong place, and the code honouring it is why
nobody noticed"* — the same derivation this record already uses for routing a
defect, applied to the area.

Set `:invisibly-incomplete?` when a reader would take the inconsistency for
intent — a half-applied invariant, a migration that stopped, a rule with silent
exceptions. That is the class `/spin-out`'s veto turns on, and it is an is-claim
about the code, true whoever is building whatever, which is why it can be stated
here without the record crossing into routing.

### The baseline never routes

You will notice, while surveying, that some of what you found should be fixed
and some of it should not be fixed *by this change*. **Do not write that down
here.** Which of them are in scope depends on what you are about to build, and a
survey by someone who already knows the change is the one thing this record
exists to prevent.

Noticing is free; deciding is batched — the same split §6 uses for mid-work
noticings. Give each observation an `:id`, and the design record that cites this
baseline is what routes it (§4a). The schema enforces the boundary: `:health` is
a closed map with no destination field in it.

### Never confuse *is* with *ought*

Reading code tells you what is there, including the accidents, the half-finished
migrations, and the patterns that were never decided, only copied. The stance is
what separates them, and its sharpest form is the essential/accidental question:
what here did the problem require, and what did our solution introduce? The most
valuable sentence a survey produces is often:

> the current design here is X; per the stance, X is drift rather than intent

That goes in `:drift`. Deriving *ought* from a majority vote of *is* — "most
schemas here are open, so open must be the convention" — is the failure this rule
names.

### Survey theatre is the failure mode

A fluent, generic baseline inferred from three files will read well and be worth
nothing. `:read` is required and non-empty; so is an honest `:unknowns`. **An
empty `:unknowns` is a smell, not a clean bill of health** — a survey that found
nothing it could not determine usually did not look. Same signal as the design
buffer that sweeps to nothing (§6), and the same signal as an empty `:health` on
an area anyone has worked in.

### It stays in the ledger

Per workstream, and **never written into the codebase**. A checked-in current
design rots and then lies, which is worse than one that is absent, because it is
citable. The cost is real and accepted: the same area gets re-surveyed by every
workstream that touches it. Baselines accumulate in ledgers, and a written
current design can be harvested from them later if it is ever wanted — that is a
different decision, not this one.

### When the review says the baseline was wrong

The verdict can classify a finding as `:baseline`: a property you claimed is
simply not true of the code. **That is not the design being wrong.** The design
may be sound on a bad premise, and the remedy is to re-survey, not to supersede.
Getting these two confused is how a sound design gets thrown away.

## 5. Amending a design

A design record is **mutable across the workstream and immutable once written**.
When review shows the design itself was wrong (`:invalidated`), you do not edit
the old entry — you append a new one carrying `:supersedes {:seq <n> :why "…"}`.

The superseded record stays in the ledger. That is the point: the failure mode is
a design that stayed "true" only because nobody updated it, and the second worst
is one that was quietly rewritten so the reasoning that failed is unrecoverable.

**Amendments that each fix a distinct defect are the process working.**
Amendments that contradict each other are not: a field widened in one and left
narrow in the next means you are patching the record rather than re-thinking it,
and those contradictions are yours, not the reviewer's. When that starts, rewrite
the record whole and supersede once rather than appending another patch.

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
| a fact about the area the baseline got wrong or missed | **append a new `:baseline`**, and cite it from the amended design record |
| a question the record can't answer yet | `:open` — amend the record |
| incompleteness you are choosing to leave | `:seams` — amend, and say what makes it visible and what closes it |
| the change cannot safely arrive in one deploy | `:phases` — amend, and read `/phase` |
| the shape you committed to cannot hold | **supersede** the record (§5) |
| a different story altogether | `/spin-out` — file it, with a ref |
| true, and not worth acting on | decline it in the layer's brief, with the reason |

Nothing is lost and nothing is smuggled — the same discipline the shipping
doctrine applies to work, applied to what you learned about the design while
doing it.

**A buffer that sweeps to nothing is itself a signal.** Zero design noticings
across a substantial change usually means the record was written once and never
looked at again — which is exactly how a design stays "true" (principle 10).

## 7. Mechanics

**Three** appends, in order — intent, baseline, design. Write the EDN to a temp
file each time; a typed report does not survive a shell argument at any useful
size. The ledger key is the `BR-####` (or the slack `:id` for a Slack-sourced
workstream); for a workstream with no ticket key, use
`bb nido:workstream:entry:add :ws-id <id>` with the same `:kind` / `:file` pair.

**First the intent**, before you have surveyed anything:

```bash
cat > /tmp/intent.edn <<'EDN'
{:format    :intent
 :goal      "what this is for — one or two sentences"
 :done-when ["what would make it done — each one falsifiable"]
 :context   "optional: why now, what prompted it"}
EDN
```

Skip it only when the intent is already written down: a triaged ticket's
`:triage` entry states the goal, and the design may cite that entry instead.
Nothing else is citable — a design cannot say it is *for* a review or a blocker.

**A goal authored after the design is a goal the design satisfies.** That is the
same contamination §4 exists to prevent for the current-design inference, which
is why this is a separate record written first rather than a field on the design.
`:done-when` is what makes it falsifiable, and an unfalsifiable goal cannot tell
an over-serving design from a right-sized one.

**Then the baseline**, before you have decided anything:

```bash
cat > /tmp/baseline.edn <<'EDN'
{:format       :baseline
 :area         "…"
 :bounded-by   "why the boundary sits there"
 :shape        "…"
 :modules      [{:id "short-stable-slug"
                 :module "…" :hides "the decision nothing outside may depend on"
                 :interface "what the rest may know"}]
 :composition  "how those produce the required behaviour"
 :load-bearing [{:id "short-stable-slug"      ; never changes once written
                 :property "…"
                 :falsified-by "the counterexample that would make this false"
                 :readings [{:lens :tarpit/state :verdict :derived :because "…"}]
                 :evidence ["src/…:41"]        ; optional — where you looked
                 :drift "…"}]
 :extension-points [{:at "…" :how "…"}]
 :health       [{:id "short-slug" :axis :design :observation "…"
                 :evidence ["src/…:88"] :invisibly-incomplete? false}]
 :governing    ["refs into the stance"]
 :drift        ["…"]
 :read         ["src/…"]
 :unknowns     ["…"]}
EDN
bb nido:ticket:append :project brian :br <BR-####> :kind baseline \
  :session <session> :run-id <run-id> :file /tmp/baseline.edn
```

Note the `:seq` it prints back — the design record cites it.

**Then the design**, declaring how the change stands to it:

```bash
cat > /tmp/design.edn <<'EDN'
{:format     :design
 :summary    "…"
 :shape      "…"
 :invariants ["…"]
 :standing   {:relation :conforms}
 :baseline   {:seq 2 :relation :within}
 :intent     {:seq 1}
 :routes     [{:health-id "short-slug" :to :spin-out
               :why "…" :ref "FU-##"}]
 :effort     :M}
EDN
bb nido:ticket:append :project brian :br <BR-####> :kind design \
  :session <session> :run-id <run-id> :file /tmp/design.edn
```

Derive `<session>` from cwd and `<run-id>` from the `./run-link/` symlink target.

Then verify what you wrote before building on it:

```bash
bb nido:review:baseline    # is the survey true, and does it say ENOUGH to decide against?
bb nido:review:design      # given all of it, should we execute on this?
```

Not "complete". Completeness has no fixed point — measured against a real area
there is always another true thing to add, and a round asked for it never
finishes. The question is whether the four derivations the design round makes
can be made against this survey; a gap that blocks none of them is not a
finding.

Both loop. They judge, repair what is derivable, and come back — so a run ends
either holding nothing, or naming the two or three things only you can settle.
Add `:seq <n>` to the baseline loop when the workstream holds more than one
survey and you mean a particular one; the default is the newest.

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
- **Surveying the whole codebase.** Scope to the design that governs the
  behaviour, and say where you bounded it (§4).
- **Surveying three files and calling it a design.** The other half of the same
  mistake, and the more common one. `:read` and `:unknowns` are what a reader
  checks it by.
- **Writing the baseline after you know the fix.** It will validate and be worth
  nothing. Every field has to be fillable without knowing the change (§4).
- **`:load-bearing` full of things that ought to hold.** The question is what
  breaks if you violate it, and `:falsified-by` is what keeps the answer honest.
- **A survey that lists files instead of modules.** If `:composition` cannot be
  written as a sentence about how the modules produce the behaviour, the area has
  not been understood yet — and every judgement made against that record will be
  made about the implementation instead.
- **Readings that restate the property.** "the total is derived" read as
  `:tarpit/state :derived` adds nothing. A reading earns its place when it says
  something the property does not — most often about *necessity*, which is the
  question the property text usually skips.
- **Dropping a reading to quiet a round.** Losing one loses analysis whatever the
  prose still says, and the amend rounds measure it. Changing a verdict is fine
  and often right; removing the perspective is not.
- **Reporting a code defect as a survey finding.** Real, and not this pass's
  business. File it; do not let it become a round.
- **An empty `:unknowns`.** A survey that could determine everything usually did
  not look (§4).
- **Reading a `:baseline` finding as a wrong design.** The premise was wrong;
  re-survey. Superseding a sound design over it throws away the wrong thing (§4).
- **Codifying drift as intent** — "this is how it is done here" derived from a
  majority vote of what is there. Say it is drift, in `:drift`.
- **Editing a design that turned out wrong** instead of superseding it (§5).
- **Deciding at the moment of noticing** — that is the interrupt §6 exists to
  prevent. Capture the line; sweep at ship time.
- **Sweeping a design noticing into the code and not the record** — the fix
  lands, the record still describes a system that no longer exists.
- **Keeping the buffer in the worktree**, where it can ride along in a commit.
- **A phase plan with plain-string invariants** — refused on write, because
  without `:holds` the verdict pass judges the middle of a migration against the
  end of it (`/phase` §6).
- **A seam with no closure named** — "visible" was only ever half the obligation;
  the other half is what will close it, and `:permanent` is a real answer that
  has to carry its reason (principle 9).
- **Cutting a phase where a layer belongs.** If nobody is ever on the
  intermediate state, it is a layer — the merge dissolves the boundary anyway
  (`/phase` §2).
- **A design longer than its diff.** It is a plan; cut it back to claims.
- **Layers that do not appear in the record** — either the design is incomplete
  or the layer is smuggling a decision nobody stated (`/stack` §2).
