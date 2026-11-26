# Asking the person a question

A question you write for the person lands on their needs-you list as a card. They read it cold —
often days later, between other workstreams, without the conversation you had. Write it so they can
answer from the card alone.

Every question is written in these parts, and the card shows each in its own place. A ledger
record (a blocker, an escalation, a triage verdict) and a core ask (the `ask` tool,
`raise_operator_ask`) name them differently; write each part in your path's field:

| part | ledger field | core ask field | what it holds |
|---|---|---|---|
| the question | `:needs` (a triage question's `:question`) | `question` | one sentence, at most 240 characters, ending in `?` |
| its context | `:summary` (`:context`) | `explored` | what the answer depends on |
| a recommendation | `:recommendation` | `recommendation` | the answer your reasoning supports, and why — optional |
| what follows | `:after` | `after` | what runs once they answer, and what waits |
| the options | `:options` | `options` | the branches of one decision, each with what taking it costs |
| several questions | `:questions` | `questions` | one entry per decision, in place of a single question's options |

**Give a recommendation only when it is a genuine one** — when you hold a view on which answer is
right. Leave the field out otherwise. "Answer in your own words" or a restatement of the options is
filler, and filler costs the reader the line it takes.

The ledger refuses a blocker or escalation whose question is not one sentence ending in `?`, or that
leaves out what follows the answer. The refusal names what is missing; write the parts and append
again.

- **Ask each decision as its own question.** The thing they have to decide, as a question, in
  their words rather than nido's. Never pack several into one sentence or one line: one decision
  is the question with its options; two or more are several questions, one entry per decision
  (below). Not how it reached them ("the conductor escalated…", "the round stopped…") — that is never context either.
- **Make it self-contained.** Never cite the ledger by coordinate — no "blocker 6", "entry 19",
  "done-when 2", "the seat", "MOBILE-N". Say what each one *is*. The card shows the workstream's
  goal beside your question, so refer to the goal by what it says.
- **Give the context the answer depends on**, and only that — what the work is, where it stands,
  and what makes this a question at all.
- **When it is a choice, give the branches as options**, never as prose ("(a) … (b) …",
  "Option A … Option B" are refused). Each option says what taking it means and what it costs or
  forecloses. Mark the one your own reasoning supports as recommended.
- **Say what happens after an answer**: what runs next, what waits. Always — the person decides
  differently when they know an answer starts a build than when it only records a note.
- **Several decisions are several questions.** A question that needs more than one answer asks
  them as several questions, one entry per decision, each held to the same sentence; each entry's
  context, recommendation and options are that question's own and optional. The card asks them
  one at a time and records an answer to each — a branch, the person's words, or left open. An
  option there cannot approve a design: that is a whole answer, so ask it alone.
  - On a ledger record: `:questions` instead of `:needs` — never both —
    `[{:id "q1" :question "…?" :context "…" :recommendation "…" :options […]} {:id "q2" …}]`, ids
    q1, q2… in order. `:summary` is the context they share, and `:after` follows all the answers.
  - On a core ask: `questions`, each entry `{question, context, recommendation, options}`, with
    `question` as one headline sentence over them and no `options` of its own. `explored` is the
    context they share, and `after` follows all the answers.
- **Ask what is already owed only once.** If the person already owes this workstream a move, do not
  re-ask it; your words ride on that card as a note.
