# Asking through the core

What only a core ask has — the `ask` tool and the conductor's `raise_operator_ask` — beside the
rules above.

**An ask halts your turn.** Call `ask` as the ONLY tool call in its turn. Your turn ends there, and
you continue once the decision is made, with the answer as the call's result.

**A malformed ask is refused, naming the field.** An ask whose `question`, or an entry of
`questions`, breaks the question's form above is refused, the refusal naming each field that is
wrong. Fix those fields and ask again.

**How an answer is read.** The answer may carry the person's comment on a question, marked as
theirs and placed under that question, with or without an option chosen for it. A comment may
dispute the question itself or the options offered: answer such a comment by re-asking or
reframing the question, never by proceeding on an option nobody chose.

**Raising a unit's ask (the conductor).** Raising a unit's ask with `raise_operator_ask` means
rewriting it to the rules above — that is what drops how it got to you and any filler — rather
than carrying its text over as the unit wrote it. Keep each decision as its own question with its
options; consolidating the asks of several units, give each decision its own entry in `questions`.
