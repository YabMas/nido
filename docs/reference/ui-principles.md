# UI principles — the web dashboard

The rules the dashboard (`nido.ui.views`, `nido.ui.server`) is built on. Each is
written so a rendered page can be checked against it, and each names the
established pattern it is drawn from. A page that breaks one is a defect, the
same as a test that fails.

**Scope: the web dashboard only.** The TUI (`nido.ui.tui`) is a terminal with
its own constraints — no type scale, sixteen colours, no disclosure widget — and
these rules do not govern it.

Nido's dashboard is a human-in-the-loop surface: agents work, and a person is
asked to decide. The person is usually arriving from somewhere else, so most of
what follows is about getting a cold reader to a sound decision quickly.

## 1. Hierarchy — one focal point per pane

**Rule.** A pane has exactly one element at the top of its hierarchy. On a gate
pane that is the question: it is the largest text on the pane (`--fs-xl`), and
nothing else is set as large. Titles are `--fs-lg`; section headings are
smaller than titles.

**Check.** Find the largest text on the pane. If it is not the thing the pane
exists to say, or if two things tie, the pane fails.

**From.** GOV.UK Design System, *Question pages*: the question is the page
heading. BLUF and Minto's pyramid principle: the ask leads, the support follows.

## 2. Type — prose is proportional, identifiers are monospace

**Rule.** Sentences are set in the prose face (`--font-prose`). The mono face
(`--font-mono`) is only for code, ids, tags, paths and commands —
`BR-7285`, `ws-…`, `bb nido:…`. A prose block is bounded to a reading measure
(`--measure`, ~72ch) and is never clipped or line-clamped; only a one-line
label in a list row may be ellipsised, because its row opens the full text. Sizes come only from
the five-step scale: `--fs-xs` 12 (labels, chips), `--fs-sm` 13 (secondary),
`--fs-md` 15 (body), `--fs-lg` 18 (titles), `--fs-xl` 22 (the question).

**Check.** A paragraph in monospace, a prose line wider than the measure, a
`font-size` that is not a token (`nido.ui.tokens-test` fails on one), or text
cut off with no way to read the rest.

**From.** GitHub Primer typography: system-ui for UI text, monospace for code
and metadata, a fixed type scale, lines around 80 characters or fewer.

## 3. Colour — semantic, tokenised, one hue per state

**Rule.** Every colour is a custom property declared once in the `:root` block
`shell` emits. A token is named for the role the colour plays — what it is (a
surface, a border, a text tier) or what it marks (a state, a workstream's
source, an action's emphasis) — never for its hue. Each state has one hue, as a
foreground, a dimmer foreground and a tint:

| state | means |
|---|---|
| `--needs` | a person owes a move |
| `--waiting` | nido waits, or a record went stale |
| `--working` | nido is working |
| `--running` | something is running right now |
| `--ok` | done, healthy, approved |
| `--failed` | failed, halted, refused |
| `--warning` | caution: a breaker, a finding |
| `--dormant` | asleep, resumable |
| `--neutral` | finished, asks nothing |
| `--recommended` | the answer nido recommends |

Colour is never the only carrier of a meaning; a glyph or a word says it too.

**Check.** A colour literal outside `:root` (`nido.ui.tokens-test` fails on
one); a state drawn in two hues on two pages; a token named `--blue`.

**From.** Primer's and Carbon's semantic colour tokens; Microsoft's guidelines
for human-AI interaction, G2 (make clear how well the system can do what it
does) — status must be readable at a glance.

## 4. Actions — an answer and its action are one element

**Rule.** Each answer on offer is the control that gives it: a row holding its
button, labelled as the answer, beside its summary and its consequence. The
recommended answer is the one primary button; destructive actions use the
danger style. Every other control — another mutation, the reply, the evidence
box — is a row beside what it does. What is the same for every answer (what
choosing one records) is said once, not once per answer. Global controls (halt,
breakers, fire) never sit among a decision's answers.

**Check.** An answer described in one place and clicked in another; a
consequence missing from beside its control; the same sentence repeated per
option; two primary buttons on one decision.

**From.** Claude Code's permission prompt and LangChain's Agent Inbox: the
options an interrupt allows are the actions it renders. GOV.UK question pages:
the answers sit directly under the question. Microsoft human-AI guidelines G16
(convey the consequences of user actions) and G17 (provide global controls).

## 5. Disclosure — ask, then support, then evidence

**Rule.** A pane reads in this order: what the reader must decide, what they
need to decide it, and then the evidence, folded. The record a question was
read from is one click away under a `<details>`, never above the answers.
Nothing a decision needs is folded.

**Check.** Evidence above the decision; something the answer depends on hidden
behind a fold.

**From.** Nielsen Norman Group, *Progressive disclosure*: show what most
readers need, defer the rest behind one obvious control. Shneiderman: overview
first, details on demand.

## 6. Orientation — re-orient a cold reader before asking

**Rule.** A pane that asks for a decision first says, unfolded and above the
question, what the work is for (the live goal and its done-whens), where it is
(the arc of stages with the current one marked, the position named and its next
move, what is running), and what this decision holds up (the Ask's `:holding`).
Every line comes from the data — the Ask, the gate's arc and position — never a
sentence the view composes for itself.

**Check.** Arrive at the pane from another workstream: can you say what this is
for, where it stands and what waits on you, without opening anything or
scrolling past the answers? If not, the pane fails.

**From.** GOV.UK's caption-above-heading pattern (the section a question belongs
to, set above it); Microsoft human-AI guidelines G11 (make clear why the system
did what it did); the person this dashboard serves, who moves between
workstreams all day and re-calibrates on every one.
