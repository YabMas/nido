---
name: nido-pipeline
description: Hand this session's workstream to nido's recipe from inside the chat — record the person's activation in the ledger, then write the records the recipe owes (intent, baseline, design) from what this session already understands, so the review loops can check that understanding. Run only when the person asks for it. Usage: /nido-pipeline
---

# /nido-pipeline

> **Harness-side skill, owned by nido.** Lives at `nido/.claude/skills/nido-pipeline/`
> and is injected into every session's composed `.claude/skills/`. The in-chat
> form of `nido activate` and of the TUI board's <kbd>A</kbd>.

A session a person starts is a plain one: no conductor, nothing owed. Activation
is the person's act that hands its workstream to nido's recipe. Invoking this
skill **is** that act — the person typed it — so carry it out; never invoke it
on your own initiative.

## 1. Activate

From this session's worktree or home, run:

```bash
bb --config ~/Code/nido/bb.edn nido:activate
```

- It prints `Activated …` or `… was already activated`, then an instruction.
  Either way, go on to step 2: the instruction is what you are to do now.
- It exits 1 on `resolves to no workstream` or `Refused — …` (a closed
  workstream). Report that line to the person and stop; do not work around it.

## 2. Follow the printed instruction

Do exactly what it says. In short: write the records the recipe owes — intent,
baseline, design, as `/design` says — **from your understanding of this session
so far**. Do not re-survey from scratch to make them look independent: their
bias is expected, and the review loops (`bb nido:review:baseline`,
`bb nido:review:design`) are what check them.

Keep the person's steps theirs: an intent nothing in this conversation states,
a design approval and a merge are the person's to give. When you reach one, end
your turn saying what the person owes.

## 3. Say where it stands

End with one line from `bb --config ~/Code/nido/bb.edn nido:owed`, so the
person sees what the recipe owes next. From here the conduct pass starts the
workstream's conductor once a person works in it.
