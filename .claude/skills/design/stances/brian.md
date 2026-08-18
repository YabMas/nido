# Domain Modeling & System Design

> **brian's stance.** Read before designing anything in brian. Housed here in
> nido for now; it belongs in brian's own repo, and moving it changes one path
> in `/design` §1.

**This document primes design reasoning. It is not a conformance checklist, and
nothing in it is a rule to check a diff against** — the review lanes and
`docs/reference/` own that job. Read it before designing, not after.

The guiding principles in this project are drawn from:

- *Out of the Tar Pit* (Moseley & Marks)
- *Domain Modeling Made Functional* (Wlaschin)
- *A Philosophy of Software Design* (Ousterhout)
- *How to Design Programs* (Felleisen et al.)
- *Parse, Don't Validate* (King)

What follows is not a summary of those works — assume familiarity — but a
treatment of how to hold them together as one method, and how to resolve the
calls they individually leave open.

**The synthesis in one line: the shape of the data is the design, and every
layer's job is to protect the reasoning that shape makes possible.** Model the
domain's data first; derive code, tests, docs, and contracts from it; and treat
everything that erodes local reasoning about that data — mutable state, imposed
sequencing, unparsed input, leaked effects — as the accidental complexity to be
pushed outward and contained. This is *Out of the Tar Pit*'s program read at
every altitude rather than only at the persistence layer: hold the essential
state declaratively, derive the rest. One instinct, applied everywhere: define
the data, let it drive, defend it.

**When the practices tension, the essential/accidental test decides.** Making
illegal states unrepresentable can fight keeping a module shallow; a constrained
value type can fight simplicity. Ask which choice removes more complexity the
user didn't ask for, and take it — including when the answer cuts against the
synthesis above, which is a means, not a law. This is the one rule above the
others.

Together they resolve the hard calls the source material leaves open — and the
places where it contradicts itself.

**Two registers of data, and the discipline is knowing which you're in.** Rich
domain values in motion should make illegal states unrepresentable — tagged
unions over optional-key maps. Persisted state at rest should stay flat,
normalized, and declaratively constrained. Holding Wlaschin and the relational
*Tar Pit* view together isn't a compromise between them; it's using each where
it is essential and refusing to let either colonize the other's territory.
Blurring them is the most common failure, and it looks like this: a domain union
persisted as a discriminator column beside a row of nullable fields, or a
database row travelling into domain logic unparsed.

**A workflow is a typed function, and here the type is a malli schema at the
boundary, written before the body.** Design the input shape → output shape
first, then write to it. Enumerate the failures as data in that output rather
than raising them — a failure the shape doesn't name is one the caller cannot be
expected to handle. Compose by matching shapes, each stage's output to the next
one's input, so the whole sequence stays legible in one place: that is the
local-reasoning property again, applied to control flow instead of to state.
In-process composition throughout — not the event-driven reading these texts
invite. No buses, no pub/sub, no sourcing unless an integration forces it, and
then say so in the design record.

**Parsing is a place, not a habit.** One identifiable boundary where untrusted
shape becomes trusted, past which nothing re-checks. What remains is functional
core / imperative shell — the pure pipeline between parsing and effect — and
that shape **is** the state-containment strategy, not a stylistic layer on top
of it.

**Where the sources conflict, depth wins over recipe.** Ousterhout distrusts
decomposition into many small units; *How to Design Programs* builds everything
out of them. They govern different altitudes. Take HtDP at the leaf — the recipe
is how a single function gets written once its data is defined — and Ousterhout
at the seam: interfaces are few, deep, and expensive to add. A design that
followed the recipe *upward*, into a wide shallow surface of small modules, has
gone wrong however clean each function reads.

**This document is amendable, and that is where its authority comes from.** It
earns its place by being argued with, not deferred to. A design that needs to
challenge something here should say so, and say what it found; a stance that
survives the challenge is stronger for it, and one that doesn't should never
have been load-bearing.
