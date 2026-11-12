# Asking the person a question

A question you write for the person lands on their needs-you list as a card. They read it cold —
often days later, between other workstreams, without the conversation you had. Write it so they can
answer from the card alone.

Every question is written in four parts, and the card shows each in its own place:

| part | field | what it holds |
|---|---|---|
| the question | `:needs` (a triage question's `:question`) | one sentence, at most 240 characters, ending in `?` |
| its context | `:summary` (`:context`) | what the answer depends on |
| a recommendation | `:recommendation` | the answer your reasoning supports, and why — optional |
| what follows | `:after` | what runs once they answer, and what waits |

**Give a recommendation only when it is a genuine one** — when you hold a view on which answer is
right. Leave the field out otherwise. "Answer in your own words" or a restatement of the options is
filler, and filler costs the reader the line it takes.

The ledger refuses a blocker or escalation whose question is not one sentence ending in `?`, or that
leaves out what follows the answer. The refusal names what is missing; write the parts and append
again.

- **Ask one question.** The thing they have to decide, as a question, in their words rather than
  nido's. Not how it reached them ("the conductor escalated…", "the round stopped…") — that is
  never context either.
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
- **Ask what is already owed only once.** If the person already owes this workstream a move, do not
  re-ask it; your words ride on that card as a note.
