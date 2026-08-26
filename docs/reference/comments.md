# Comments

**Harness-side doctrine, owned by nido.** It reaches every session because the
root nido checkout is on `--add-dir`; read it at
`~/Code/nido/docs/reference/comments.md`.

After John Ousterhout, *A Philosophy of Software Design*, ch. 12–16.

## 0. Who owns what

This is here rather than in a project because it is not project knowledge. What
a comment is for does not change between a Clojure web app, a Babashka
orchestrator and a spec compiler, and four copies of it would be four things to
amend and three that silently go stale.

**Each project still owns its own spelling**, and this doctrine never overrides
one:

- the banned-token list its commit gate blocks on
- the canonical shape of a ticket reference
- its docstring conventions (`mx/defn` schemas, argument backticks, whatever)

Where a project's rule and this one meet, **the project's is the spelling and
this is the reason.** Cite the project's rule when the fix is a token; cite this
when the fix is a judgement.

A project may also carry a content rule of its own — brian's
`docs/guidelines/documentation.md` says types belong in schemas and prose in
intent and side effects. That is not in tension with §1. It is §1 applied to one
project's docstring form, it is the more specific rule wherever it applies, and
it wins there. Read this for the questions it does not answer: whether a comment
earns its line at all, what an awkward one is telling you about the abstraction,
and how far a change's obligation to the comments around it reaches.

## 1. The test a comment has to pass

**A comment earns its line by carrying what the code cannot.** There are exactly
two kinds worth writing:

- **Precision** — facts a reader cannot recover from the signature. Units.
  Whether a bound is inclusive. Whether nil is legal, and what it means when it
  arrives. Who owns the thing after the call returns. What is still true if it
  throws. Which lock the caller must already hold. What the caller must not do.
- **Intuition** — the sentence the code cannot state. What this is *for*. Why it
  is shaped this way. What breaks if someone changes it back.

Everything else is restatement, and restatement is worse than silence: it costs
a line to read, and it is a second thing that can go stale.

**The test is mechanical. If the comment's words are the identifiers rearranged,
delete it.**

```clojure
;; ✗ increment the counter
(swap! attempts inc)

;; ✓ Counts attempts, not sends — a retried send must not spend the
;;   per-hour cap twice.
(swap! attempts inc)
```

## 2. Write the interface comment first — it is a design tool, not a tax

Write the comment for a function, a namespace or a module **before** its body.

The point is not the comment. The point is that **a comment you cannot write
cleanly is telling you the abstraction is wrong**, and it tells you at the only
moment the information is cheap — before there is code to be attached to.

> If the interface comment is long, awkward, or has to enumerate the caller's
> cases, the problem is the interface. Not the prose.

This is the single most valuable rule in this document, and the hardest to act
on, because the awkward comment is always fixable by writing better English.
These are design defects wearing a comment's clothes:

| what the comment ends up doing | what it is telling you |
|---|---|
| enumerates modes — "if `opts` carries `:x` this does A, else B" | it is two functions sharing a name |
| describes the caller's situation rather than this thing's contract | the boundary is drawn in the wrong place |
| has to explain what the arguments mean *together* | the arguments want a name — a record, not a list |
| needs "and" more than once to say what it does | the unit does more than one thing |
| runs longer than the body it introduces | the interface costs about what it hides; the seam buys nothing |

**Report the abstraction, not the sentence.** Rewriting an awkward comment into
a fluent one is how a design defect gets sealed under good prose, and the next
reader has no way to recover the signal.

## 3. A comment sits at a different level from the code beneath it

A comment at the *same* level as its code is a restatement however well written,
because it can only re-say what is already there in another notation. Every
useful comment is either **above** its code or **below** it.

- **Above — interface.** What a caller must know, and nothing about how. A
  caller who reads it should never need to open the body. If the comment
  describes the algorithm, the algorithm has leaked into the contract, and
  changing it later becomes a breaking change nobody notices.
- **Below — precision.** The exact fact the code leaves implicit: the unit, the
  bound, the ordering that must not change, the case the reader will assume
  wrong.
- **Above — implementation.** *What this block accomplishes and why*, so a
  reader can skip it. Never a line-by-line paraphrase.

A useful heuristic: an interface comment written from inside the implementation
reads as a summary of the body. Written first, it reads as a promise.

## 4. Describe what is, never what happened

**A comment must read correctly to someone who never saw the previous version of
the file and does not know your change happened.** They are the overwhelming
majority of its readers, and to them a comment about the change is a comment
about a file they have never seen.

The transition story is not lost — it belongs where it is addressed to someone
who *is* comparing two versions:

| the information | where it goes |
|---|---|
| what this code does and why it is like this | the comment |
| what changed, and why the change is safe | the commit message, the PR body |
| that the new behaviour holds | a test |
| everything else about the transition | the history; it is already there |

So: **when you change a pattern, just change it.** Remove the old code, don't
mark it. Don't leave a comment explaining that the thing next to it is now
different, and don't leave one apologising for what used to be there.

Ousterhout puts the boundary the same way from the other side: *if the
information is needed to understand the code, it belongs in the code.* The
corollary is this section. If it is needed to understand the *change*, it does
not.

Two comment shapes look like history and are not — both describe a live
contract, and neither is a violation:

- a pointer to a doctrine document that still exists and still governs
- a dated ratchet (`DELETE-AT`, `REMOVE-AFTER`) — it names a future removal, not
  past work

**The token lists are the project's.** Where a project ships a commit gate, that
gate's banned tokens are the mechanical floor and this section is the rule the
gate approximates. A comment can honour every token list and still be pure
archaeology; that residue is what a review lane is for.

**Write the comment, don't redact it.** Deleting the offending word leaves a
comment that still narrates a transition, just more vaguely — which passes the
gate and fails the reader.

> ✗ `;; This block previously described a phase that is long over…`
> ✗ `;; This block described a phase that is over…` — token gone, story kept
> ✓ `;; The mocked var is `routing.router/route-stream`.`

## 5. One authoritative place

**Never say the same thing twice.** Two copies of a fact drift, and a reader who
finds both has no way to tell which is current — so duplicated documentation is
worse than a single copy in a slightly inconvenient place.

- A fact that belongs to one thing is documented on that thing, once.
- A fact that spans several is documented in the one place that owns it, and
  referenced from the others.
- A comment that restates a doc paragraph will fall behind the doc. Point at it.

The same rule is why **a comment must not restate a schema, a type annotation,
or a name**. Those are the authoritative copy, and they are machine-checked; the
comment is neither.

## 6. A reference anchors a live contract; it is never provenance

A ticket, issue or PR reference in a comment is legitimate in exactly one shape:
**it names something that still constrains this code.**

- ✓ `;; The BR-4679 gate rejects these before they reach the queue.` — the
  contract is live and the ref is how you find its statement.
- ✓ `TODO(BR-5617): drop the fallback once every tenant is migrated.` — the
  machine-collectable follow-up shape.
- ✗ `;; Added for BR-5617.` — provenance. This is §4 archaeology wearing a
  ticket number: it tells a reader the file changed, which they can see, and
  nothing about what the code is.

**One canonical token per ticket.** Which token — `BR-5617`, `BRIAN-4321`,
`owner/repo#N`, a full URL — is the project's decision, usually enforced by a
gate. Any ref, in any spelling, still has to earn its line by §1.

## 7. Keep the comment where the code is

A comment far from what it describes does not get updated, because the person
changing the code never sees it. Distance is the mechanism behind almost every
stale comment.

- Put each comment immediately above, or on, the thing it is about.
- Prefer many small comments near their code to one block at the top of a file
  describing everything below it.
- **When you change code, change its comments in the same edit.** Not in a
  follow-up.

A stale comment is worse than no comment, and worse in a specific way: it is
*citable*. A reader will believe it over the code, and the ones who do not will
waste their time proving it wrong.

## 8. The scope of repair is the code you touched

Every rule above is about a comment you are writing. This one is about the
comments that were already there when you arrived.

**When you change code, the comments on what you changed are yours.** Fix them
in the same edit — not a follow-up — if they are wrong, if they omit the fact
you just had to work out, or if they only restate the code. You are the last
person who will hold this context cheaply, and a comment you knew was wrong and
left behind is the worst kind: citable, and believed.

**Stop at the edge of what you touched.** The next function is not your
business. The file is not your business. A comment sweep is a different change,
with its own claim and its own review cost, and folding one into this change
buries the claim you actually came to make.

| | what it costs |
|---|---|
| leaving the comments on code you just changed | decay — a defect you knew about, handed to a reader with less context than you had |
| fixing the comments across the file | scope creep — the diff stops being reviewable, and the claim gets buried in prose edits |

The boundary is what you **touched**, which is wider than what you **added** and
far narrower than the file. A comment two lines above a function whose body you
rewrote is in scope even though the diff never touches that line. The comment on
the function below it is out of scope even if it is worse.

If something outside the boundary is genuinely wrong and worth fixing, that is a
change of its own. Make it separately, or file it — do not smuggle it in here.

This is the shipping doctrine in comment shape: *each batch leaves the codebase
better along the axis it was already moving on — not better everywhere (scope
creep), not merely no worse (decay).*

## 9. A missing comment is a finding

Every rule above is a rule about comments that exist, so **writing none passes
all of them**, and passes every scanner any of these projects run. That is the
gap this section closes.

A comment is **required** wherever a reader cannot get the fact from the code:

- a non-obvious contract — nullability, ordering, ownership, units, what holds
  after a throw
- a decision that looks arbitrary and is not, especially one someone will
  "simplify" back
- a workaround for behaviour outside this file: a library quirk, a wire-format
  constraint, a race
- an invariant that several places conspire to maintain, stated in the one place
  that owns it (§5)

Reviewers: absence is a finding when the fact is genuinely unavailable from the
code — not whenever a function lacks a docstring.

## 10. What this doctrine deliberately does not do

- **It does not set a coverage bar.** No rule here is satisfied by adding
  comments, and none is violated by a file that needs few. A change that adds
  comments to pass a review has made the codebase worse.
- **It does not adjudicate spelling.** Token lists, ref shapes and docstring
  formats belong to the projects (§0), and every one of them is enforced by
  something cheaper than a review.
- **It does not judge the existing corpus.** Every check built on it is scoped
  to what a change adds or touches (§8) — never to the file, and never to code
  the change left alone. A rule adopted mid-life that indicts the corpus is a
  rule nobody adopts. The machine lints are narrower still: they see added lines
  only, which is what makes them safe to gate on.
