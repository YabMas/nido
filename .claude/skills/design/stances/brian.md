# Domain Modeling & System Design

> **brian's stance.** Read before designing anything in brian. Housed here in
> nido for now; it belongs in brian's own repo, and moving it changes one path
> in `/design` §1.

**This document primes design reasoning. It is not a conformance checklist, and
nothing in it is a rule to check a diff against** — the review lanes and
`docs/reference/` own that job. Read it before designing, not after.

## The yardstick

*Out of the Tar Pit* (Moseley & Marks) is not one reference among several. **It
supplies the standard a design here is measured against; everything else supplies
the means.** When you argue that one shape is better than another, the argument
that counts is the one made in its terms.

The standard is complexity, and it comes in two kinds. **Essential** complexity
is inherent in the problem *as the user states it*. **Accidental** complexity is
everything the attempt to solve it introduced. That definition is stricter than
it first reads, and the strictness is where the value is: the language, the
framework, the schema library, the requirement that it be fast, and every
structure we invented to keep ourselves organised are all accidental. Almost
nothing is essential. That is not an indictment; you cannot build without
accidental structure. It is what stops the test from being usable to justify
anything.

**The test, in the form it gets used:** for each structure you are about to add —
a table, a column, a module, a flag, a level of indirection — ask what in the
user's statement of the problem required it. When the honest answer is "nothing
did, the solution needed it", it is accidental, and the only question left is
whether it is the cheapest accidental structure that will do the job.

**Three things generate it, and naming them is what makes the test operable
rather than a slogan:**

- **State.** The largest by far. Every bit of mutable state doubles the space you
  have to reason about and destroys the ability to understand a component by
  looking at it. Hold the essential state; derive everything else.
- **Control.** Sequencing the problem never asked for. If two things do not have
  to happen in an order, saying they do is complexity you added.
- **Code volume.** Sheer bulk is a cause in its own right — complexity breeds
  complexity. The cheapest structure is frequently the one that is not there.

**Two moves against them, in that order: avoid, then separate.** Complexity never
introduced needs no containing. What cannot be avoided gets pushed to a boundary
and named, rather than spread through the system where it compounds.

Two corollaries the paper is explicit about, and that are the easiest to lose:

- **Declarative beats functional beats imperative,** in that order, every time
  the choice is available. A malli schema, a database constraint and a
  data-driven dispatch table *state* what is true; a function computes it; a loop
  over an atom performs it. Move down that list only when the level above cannot
  express the thing.
- **Restricting power is a virtue.** A construct that *cannot* express most
  things bounds what a reader has to consider. Generality added before it is
  needed is accidental complexity with a good reputation.

And the reason any of it matters: **informal reasoning is what actually catches
mistakes.** Not tests, which sample, and not proofs, which nobody writes here — a
human reading a component and being right about what it does. Every practice
below exists to protect that.

**What we take and what we do not.** The paper's own proposal — Functional
Relational Programming — is a target we approximate, not a system we implement.
What translates directly: essential state held relationally, the rest derived
rather than stored, effects confined to the edges. What we do not have is its
separate layer of *accidental state and control* — the declared,
meaning-preserving place where caching, indexing and performance work belong.
Here those land in the logic instead. A design that finds them a containable
home should say so — that is honouring the paper's structure rather than citing
it.

**And the limit of its authority.** This is the yardstick for design decisions —
which shape, which boundary, which of two readings of a requirement. It is not
something a line of code can violate and it never adjudicates a diff; the lanes
and `docs/reference/` do that (`/design` §1). Measure the design against it, not
the code.

## The four that say how

The yardstick says what to minimise. These say how it gets built in a Clojure
system, and each earns its place by materialising one part of it:

- ***Domain Modeling Made Functional*** (Wlaschin) — how the essential state and
  its transitions get *shaped*, so that illegal states cannot be represented and
  the reasoning is carried by the data.
- ***A Philosophy of Software Design*** (Ousterhout) — where the separation
  happens, and what a boundary has to be worth. Deep modules are the restriction
  of power applied at the seam.
- ***How to Design Programs*** (Felleisen et al.) — how a single function is
  written once its data is defined. Derivation from the shape of the data, at the
  leaf.
- ***Parse, Don't Validate*** (King) — the one boundary where untrusted shape
  becomes trusted, past which nothing re-checks. Unparsed input is accidental
  complexity admitted into the core.

Assume familiarity with all five. Nothing here is a summary of any of them — it
is a treatment of how to hold them together as one method, and how to resolve the
calls they individually leave open.

**When they tension, the yardstick decides.** Making illegal states
unrepresentable can fight keeping a module shallow; a constrained value type can
fight simplicity. Ask which choice removes more complexity the user did not ask
for, and take it — including when the answer cuts against everything below, which
is a means and not a law.

## The synthesis

**In one line: the shape of the data is the design, and every layer's job is to
protect the reasoning that shape makes possible.** Model the domain's data first;
derive code, tests, docs and contracts from it; treat everything that erodes
local reasoning about that data — mutable state, imposed sequencing, unparsed
input, leaked effects — as the accidental complexity to be pushed outward and
contained. This is *Out of the Tar Pit* read at every altitude rather than only
at the persistence layer. One instinct, applied everywhere: define the data, let
it drive, defend it.

**Two registers of data, and the discipline is knowing which you're in.** Rich
domain values in motion should make illegal states unrepresentable — tagged
unions over optional-key maps. Persisted state at rest should stay flat,
normalized, and declaratively constrained. Holding Wlaschin and the relational
*Tar Pit* view together isn't a compromise between them; it's using each where it
is essential and refusing to let either colonize the other's territory. Blurring
them is the most common failure, and it looks like this: a domain union persisted
as a discriminator column beside a row of nullable fields, or a database row
travelling into domain logic unparsed.

**A workflow is a typed function, and here the type is a malli schema at the
boundary, written before the body.** Design the input shape → output shape first,
then write to it. That is the declarative level, taken because it is available.
Enumerate the failures as data in that output rather than raising them; a failure the shape
doesn't name is one the caller cannot be expected to handle, and a thrown one is
control flow the problem never asked for. Compose by matching shapes, each stage's
output to the next one's input, so the whole sequence stays legible in one place:
that is the local-reasoning property again, applied to control instead of to
state. In-process composition throughout — not the event-driven reading these
texts invite. No buses, no pub/sub, no sourcing unless an integration forces it,
and then say so in the design record.

**Parsing is a place, not a habit.** One identifiable boundary where untrusted
shape becomes trusted, past which nothing re-checks. What remains is functional
core / imperative shell — the pure pipeline between parsing and effect — and that
shape **is** the state-containment strategy, not a stylistic layer on top of it.

**Where the sources conflict, depth wins over recipe.** Ousterhout distrusts
decomposition into many small units; *How to Design Programs* builds everything
out of them. They govern different altitudes. Take HtDP at the leaf — the recipe
is how a single function gets written once its data is defined — and Ousterhout
at the seam: interfaces are few, deep, and expensive to add. A design that
followed the recipe *upward*, into a wide shallow surface of small modules, has
gone wrong however clean each function reads — it added code volume and bought
nothing.

**This document is amendable, and that is where its authority comes from.** It
earns its place by being argued with, not deferred to. A design that needs to
challenge something here should say so, and say what it found; a stance that
survives the challenge is stronger for it, and one that doesn't should never have
been load-bearing. That applies to the yardstick too — but challenging it is a
different order of claim from challenging a practice below, and should be made as
one.
