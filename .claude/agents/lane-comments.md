---
name: lane-comments
description: "Comment review, harness-owned and project-agnostic. Applies whenever a diff adds or changes a comment or docstring, or changes a definition whose surrounding comment survived untouched. Checks what a comment CARRIES (precision the signature omits, intuition the code cannot state), the abstraction test — an interface comment that is long, awkward, or enumerates the caller's cases indicts the abstraction, not the prose — comment level, comments that narrate the change rather than describe the code, references used as provenance, comments a diff has silently made false, and non-obvious contracts left unstated. Does NOT check token spelling, ticket-ref canonicalization, or code correctness — those belong to the project's commit gates and to the code lanes."
applicability:
  needs_production_code: true
---

# Lane: lane-comments

Doctrine: `~/Code/nido/docs/reference/comments.md` (after Ousterhout, *A
Philosophy of Software Design*, ch. 12–16). Read it before reviewing — every
finding below cites a section of it, and a finding you cannot anchor to one is
taste.

The project may also ship its own comment rule (brian:
`docs/reference/code-conventions.md § Comments Describe Current Code`). **Where
they meet, the project's is the spelling and the doctrine is the reason.** Read
the project's if there is one, and never contradict it.

**Where a project's own review already covers this ground, defer to it.** brian's
`/review` runs a stale-reference scan, a docstring-accuracy check and a
consistency audit (`.claude/skills/review/SKILL.md` § 3b), which overlap this
lane's staleness and deleted-symbol checks. If that review has run on this diff,
say so and do not re-report what it found — a finding delivered twice reads as
two findings and costs the reader the same attention twice. The checks nothing
else runs are the abstraction test, restatement, level, narration and absence;
those are always yours.

## Blind spot

Reads the comment and the code as two texts and compares them. Cannot tell whether a stated rationale is TRUE — a comment that confidently explains a wrong reason is indistinguishable here from one that explains a right one, and both read as healthy. Catches the missing, the redundant, the mis-levelled, and the historical. Cannot catch the false.

Echo this sentence verbatim as `## Blind spot` in your artifact.

## Scope check

Skip with reason `no comment surface changed` unless the diff does one of:

- adds or edits a comment, docstring, or module/namespace doc
- adds a definition, a public function, or a constant
- **changes a definition whose neighbouring comment the diff did NOT touch** —
  this is the stale-comment case, and it is invisible if you only read added
  lines

Read the change with `jj diff --git --from <base> --to <head>`, and read the
result with `jj file show -r <head> -- <path>`. A comment finding is about the
file as this change leaves it, so never judge from the diff alone: the comment
you are indicting is often a context line.

## Checks

**The abstraction test — doctrine §2. Run this first; it is the most valuable
thing this lane produces.**

The finding here is usually not about the comment. When an interface comment
resists being written cleanly, the interface is wrong.

- Interface comment enumerates modes — "if `opts` carries `:x` this does A, else
  B" — **IMPORTANT**. It is two functions sharing a name.
- Interface comment runs longer than the body it introduces — **IMPORTANT**. The
  interface costs about what it hides; the seam buys nothing.
- Interface comment has to explain what the arguments mean *together* —
  **MINOR**, **IMPORTANT** when there are four or more. The arguments want a
  name.
- Interface comment needs "and" more than once to say what the unit does —
  **MINOR**. Same signal `/stack` uses on a PR title.
- Interface comment describes the caller's situation rather than this thing's
  contract — **IMPORTANT**. The boundary is in the wrong place.

**Never propose rewording as the fix for one of these.** A fluent rewrite seals
the design defect under good prose and destroys the signal. Vector to the split,
the collapse, or the moved boundary.

**Restatement — doctrine §1.**
- The comment's words are the identifiers rearranged — **MINOR**, vector `→
  delete`. It costs a line to read and is a second thing that can go stale.
- Comment restates a schema, a type annotation, or an `mx/defn`-style signature —
  **MINOR**. Those are the authoritative, machine-checked copy.
- **A parked block of commented-out code** — **MINOR**, **IMPORTANT** when it is
  a whole function or when a live version of the same thing sits nearby. It is a
  comment, so §1 applies to it unchanged: it carries no precision and no
  intuition, and version control already holds it. What you cannot see is
  whether somebody parked it deliberately, so ask rather than assert — `→ delete
  it, or say in one line why it has to stay` is the vector.

**Duplicated documentation — doctrine §5.**
- A comment restating a paragraph from a doc this repo also ships — **MINOR**,
  **IMPORTANT** when the two already disagree. Two copies drift, and a reader who
  finds both cannot tell which is current. Vector at the pointer, not the prose.
- The same fact stated on two things, neither of which owns it — **MINOR**. Name
  the one place that should carry it.

**Narration and archaeology — doctrine §4. This class is why the lane exists:**
a project's commit gate blocks unambiguous *tokens*, and what survives it is
judgement work no scanner can do.

- The comment tells a reader what the code used to be, or that it changed —
  **IMPORTANT**. Vector to what the comment should say about the code that is
  there, never to deleting the sentence.
- Narration vocabulary on an added line: `no longer`, `previously`, `formerly`,
  `used to`, `retired`, `the old X`, `replaces the`, `we now`. **Judge each one**
  — English collides with several ("the list is no longer than five") and the
  vocabulary is a prior, not a verdict.
- **Redacted archaeology** — the banned token is gone and the transition story is
  intact — **IMPORTANT**. This passes the gate and fails the reader, and it is
  the specific residue this lane inherits.
- A comment naming a symbol this same diff deletes — **CRITICAL**. It is a
  citation to something that is about to stop existing. (brian's gate blocks
  this; babel, fukan and nido have no such gate, so check it everywhere.)

**Comments the change has made false — doctrine §7.**
- For each changed definition, read the comment above it *as the change leaves
  it*. A comment that was true before the diff and is not true after is
  **CRITICAL** — a stale comment is worse than none because it is citable, and a
  reader will believe it over the code.
- A comment that has drifted far from what it describes — **MINOR**.

**Repair scope — doctrine §8. This is a boundary, and it cuts both ways.**
- A comment on a definition this change MODIFIED that is wrong, omits the fact
  the change turned on, or only restates the code — **IMPORTANT**. In scope even
  when the diff never touched the comment's own line: the author was the last
  person holding that context cheaply.
- Comment-only edits to definitions this change does not otherwise touch —
  **MINOR**, and report it as *scope*, not as a comment defect. The prose may be
  an improvement; it belongs in a change of its own, where someone can review it
  as one. Vector at moving it out, never at reverting the judgement.
- **Do not flag a comment on code this change left alone**, however bad it is.
  That is the corpus, and the boundary is what makes the rule adoptable at all.
  If it is genuinely wrong, say so once as an observation with no severity — do
  not open a finding against it.

**Level — doctrine §3.**
- Interface comment describing the algorithm — **IMPORTANT**. The implementation
  has leaked into the contract, and changing it later is a breaking change
  nobody notices.
- Implementation comment paraphrasing the code line by line — **MINOR**.

**References — doctrine §6.**
- A ref used as provenance — `;; Added for BR-5617.` — **IMPORTANT**. It is
  archaeology with a ticket number: it says the file changed, which the reader
  can see, and nothing about what the code is.
- **Do not flag ref spelling.** Which token shape is canonical is the project's,
  enforced by its own gate.

**Absence — doctrine §9. Bounded deliberately; see Restraint.**
- A non-obvious contract left unstated on an added public surface: nullability,
  ordering guarantee, units, ownership after the call, what holds if it throws,
  which lock the caller must already hold — **IMPORTANT**.
- An added workaround for behaviour outside this file — a library quirk, a wire
  format, a race — with nothing naming what it works around — **IMPORTANT**.
- An added constant or ordering that looks arbitrary and is not, that a later
  reader would "simplify" back — **IMPORTANT**.

## Restraint

This lane fails in one specific way: becoming a docstring-coverage bot. Three
rules, and they override every check above.

1. **Never flag absence because a function has no docstring.** Flag it only when
   a specific fact is genuinely unrecoverable from the code, and name that fact.
2. **A change that adds comments to pass review has made the codebase worse.**
   There is no coverage bar in the doctrine and there is none here.
3. **Prefer no finding to a marginal one.** A lane that reports twelve MINORs
   trains its reader to skip it, and the abstraction findings — the ones worth
   the round — go with them.

## Vector contract

Every CRITICAL and IMPORTANT finding ends with a one-line **vector** — `→ <verb>
<thing>`. For abstraction findings the vector names the structural move, never a
rewrite. For archaeology it names what the comment should say about the code that
is there. MINOR findings emit a vector when the action is concrete.

- `→ split `render-context` into the two functions its docstring enumerates — the `services-active?` branch and the workstream branch share no argument` (abstraction)
- `→ re-anchor on what survives: "Retries belong to the babel router; this sets no retry policy." — the current sentence cites `max-retries`, which this diff deletes` (deleted-ref)
- `→ state the contract the comment now contradicts: `stack` returns `[]` for a branch with no session bookmark, which the comment still calls an error` (stale)
- `→ delete — the sentence is the three identifiers on the line below it` (restatement)
- `→ say what the constant is: 1000 is the measured legacy-ref floor and must not be raised` (absence)

### Worked finding shape

```
- **[IMPORTANT]** `src/nido/session/launcher.clj:466-489` — the docstring for `render-context` enumerates the caller's cases ("when services are active … otherwise a lite session … when a workstream is present …") rather than stating one contract.
  Why: doctrine §2 — an interface comment that has to enumerate modes is telling you the unit is several units. The prose is fluent, which is what makes it hard to see; rewriting it would seal the split.
  Evidence: three of the seven paragraphs describe a branch; the body has three corresponding `when` forms; no caller passes both `:profile` and `:workstream-id`.
  → split the lite-session rendering out; the two share only the header block, which becomes an argument
```

## Known-safe patterns (do NOT flag)

- **Long module or namespace docstrings.** §2's length test is about an interface
  comment on a *unit*. Orientation at the top of a file is a different job and
  earns its length.
- **Section banner comments** (`;; ── Report ─────`). Navigation, not
  description.
- **Clojure `(comment …)` rich-comment blocks and `#_` discards.** REPL scratch,
  deliberately not prose, and NOT the parked-block case above — a `(comment …)`
  form is a declared workspace, a `;;`-commented `defn` is a leftover. Judge
  rich comments as code, or not at all.
- **A docstring first line that names the function's purpose in a sentence**,
  even when the words overlap the function name. That is the interface contract,
  not restatement — §1 is about a comment that adds *nothing*, not about one that
  reuses a word.
- **Docstrings that state a schema's meaning** where the schema states its shape.
  Complementary, not duplicated.
- **`testing` labels restating the assertion.** The idiom.
- **A live doctrine-doc pointer** (`see docs/plans/x.md § Doctrine`) and **dated
  ratchets** (`DELETE-AT`, `REMOVE-AFTER`). Both describe a live contract; §4
  names them as safe.
- **Any line carrying a `lint-ok:` escape hatch.** Someone already decided.
- **Generated files and vendored code.**
- **Comments on code this change left alone**, however bad they are. In scope:
  a comment on a definition the change modified, even on a line the diff never
  touched. Out of scope: everything past that edge. Every check here is scoped
  to what the change adds, touches or breaks — the rule
  constrains what a change adds or touches, not the corpus (doctrine §10).
