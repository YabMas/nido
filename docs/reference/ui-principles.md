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
pane that is the question. It leads by place and by frame, not by size: it
opens the question card, the one bordered block the pane's controls live in,
and is set at `--fs-lg` one weight lighter than a title, so it reads as the
thing to answer rather than as a shout. Section headings are smaller than
titles, and nothing outside the card is framed like it.

**Check.** Find what the eye lands on after the title. If it is not the thing
the pane exists to ask, or if two blocks are framed as the card is, the pane
fails.

**From.** GOV.UK Design System, *Question pages*: the question is the page
heading. BLUF and Minto's pyramid principle: the ask leads, the support follows.

## 2. Type — prose is proportional, identifiers are monospace

**Rule.** Sentences are set in the prose face (`--font-prose`). The mono face
(`--font-mono`) is only for code, ids, tags, paths and commands —
`BR-7285`, `ws-…`, `bb nido:…`. A prose block is bounded to a reading measure
(`--measure`, ~72ch) and is never clipped or line-clamped; only a one-line
label in a list row may be ellipsised, because its row opens the full text. Sizes come only from
the four-step scale: `--fs-xs` 12 (labels, chips), `--fs-sm` 13 (secondary),
`--fs-md` 15 (body), `--fs-lg` 18 (titles and the question).

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

**An Ask of several questions** — a triage verdict with one question per
defect — cannot let each answer give itself, because no one answer is the
whole of it. Its questions sit in one card, one question at a time, with a
line saying which of how many is shown, a marker per question saying whether
it is answered, and Previous/Next to move between them. Each answer is a
choice within its question: a button whose first line is its name in bold,
then what it does, then its cost — never a letter, which stays in the
record. Choosing records nothing; the one submitting action (Accept) is the
primary button, in a row after the card beside what it does, and a question
may be left open. Defer stands beside it, for a verdict whose questions should
wait for the design work rather than be answered now.

**Check.** An answer described in one place and clicked in another; a
consequence missing from beside its control; the same sentence repeated per
option; two primary buttons on one decision; two questions of one card
visible at once, or a choice that submits on its own.

**From.** Claude Code's permission prompt and LangChain's Agent Inbox: the
options an interrupt allows are the actions it renders. GOV.UK question pages:
the answers sit directly under the question. Microsoft human-AI guidelines G16
(convey the consequences of user actions) and G17 (provide global controls).

## 5. Disclosure — overview, then the ask, then evidence

**Rule.** A pane that asks reads in this order: the overview that orients the
reader, then what they must decide with what they need to decide it, then the
evidence. The card itself reads question first, then its support, then its
controls. The records a question was read from stay in the pane's own stage
list and History, a click away; nothing a decision needs is folded.

**Check.** Evidence between the question and its answers; something the answer
depends on hidden behind a fold.

**From.** Nielsen Norman Group, *Progressive disclosure*: show what most
readers need, defer the rest behind one obvious control. Shneiderman: overview
first, details on demand.

## 6. Orientation — the context first, the question below it

**Rule.** A pane that asks for a decision first orients a cold reader — what
the work is for, where it stands, what it has been through — and then asks. That
context is the workstream's own pane: its status heading, its plan, its stage
list and its standing records. The question card sits below them and above the
History. A gate is answered on its workstream's pane, never on a second page that
re-tells that context in a band of its own.

**Check.** Open a workstream that waits on you: does the pane tell you where the
work stands before it asks, is the question the next thing you reach, and is
everything you need to answer it either on the card or above it, without a
second page?

**From.** The person this dashboard serves. Reviewing the shipped gate pane,
they dissolved the Needs-you page into the workstream pane, because a card
inside the pane "saves reinventing the context question". Reviewing the merged
pane, they set the order: "Start with the context overview and put the question
below." Shneiderman: overview first.

## 7. Placement — never state what the placement already says

**Rule.** A line earns its place only if a reader could not get it from where
it sits. A card on a workstream's pane does not say which workstream it is
for; a stage marked as waiting on you does not also say so in a sentence; a
heading that names the position is not followed by a line that names it again.
State is a mark — a glyph in its semantic colour, its words in a `title` — and
not a sentence beside the thing it marks.

**Check.** For every line of text in the pane, cover it: is anything lost that
its position, its heading or a mark beside it did not already say? If not,
delete it.

**From.** The person's review of the shipped gate pane, which cut the
"what this decision holds up" line ("doesn't add anything"), the
"Blocked / next · your answer / agent · answering" position lines, and every
"agent doing X" label. Tufte's data-ink ratio: ink that restates is ink to
erase.

## 8. States — working, needs you, paused, and nothing else at rest

An open workstream is in exactly one of three resting states, and the overview says which with one mark and no words:

| state | mark | means |
|---|---|---|
| working | ● running colour | one or more agents are doing something on it |
| needs you | lit position chip | it is blocked on a question it has stated to you |
| paused | ‖ dormant colour | you chose not to push it forward for now, with a note saying why |

Paused is only ever the person's choice (the pane's Pause, a `:paused` ledger entry). nido never infers it.

Everything else is a fault. A workstream where an agent owes the move but nothing runs, nothing asks you, and nobody paused it is **stalled**: it shows ⚠ in the failed colour, never ‖. A stall is something nido has to recover from, by restarting the owed work or turning it into a stated question. It is not a state to display and leave.

Who runs and on what, which round, who owes the move: these are detail. They go in the mark's title and in the pane, never on the overview row. The person's words: "All I care about is if its in progress … or if it needs my input on something. The rest i see as irrelvant details at this level and can be opaque."
