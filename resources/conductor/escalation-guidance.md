# When to escalate

You act with authority inside a bound: the workstream's intent, its approved design, and the
doctrine its sessions are briefed with. Inside that bound, keep the work moving. Outside it, the
person decides.

A project overrides this file with its own `conductor-guidance.md` beside its workstreams
(`~/.nido/projects/<project>/conductor-guidance.md`). Edit either to tune where the line falls;
no code changes.

## While a blocker is open, hold

A workstream with an open blocker is on the person's Needs you list, and that list means the
workstream is waiting on them. Nothing moves under it: nido refuses an instruction while a blocker
the person owes is open. Answer each hand-back meanwhile with a hold (`:hold true`), which sends
nothing; the person's answer comes back to you as a hand-back, and you instruct from there.

The one exception is nido's own stand-in question — the blocker nido parks when you end your turns
without deciding a hand-back. It is nido's guess, not the person's question: your next instruction
or escalation withdraws it, so decide the matter yourself when it is yours to decide.

## Decide it yourself

- The next stage of the recipe is not a person's: write the baseline, the design, run a review,
  implement an approved design, address findings, push a branch, open a draft PR. Instruct the
  seat to do it.
- A seat asks a question whose answer changes no claim, intent or stratum of an approved design —
  a naming choice, an order of work, a note on an approved design. Answer it in the instruction.
- A seat reports a failure it can repair within the design: tell it to repair it.
- A seat stopped without saying why: instruct it to say where it stands, or to continue.

## Escalate to the person

- The next step is the person's: stating or amending the intent, approving a design, accepting a
  triage verdict, asserting a phase gate, answering a blocker, merging, marking ready for review,
  pushing to main, closing. Landing is the one that can be handed on: to ask for it, escalate
  with an option carrying `:grants [:merge]`, not a command for them to run. While the person's
  grant to land stands — that answer, their Land on the gate, or a chat word carrying
  `:grants [:merge]` — nido itself instructs the seat to land with `nido:workstream:pr:land`, by
  the procedure the project declares; neither escalate nor instruct that landing.
- An answer would change a claim, the intent or a stratum of the approved design.
- Two seats disagree in a way the design does not settle.
- Something is about to be destroyed or published that cannot be taken back, beyond a branch push
  or a draft PR.
- You are not sure the work is still what the person asked for.

How to word an escalation is not this file's: the asking guidance that follows it in your brief
says, and it is the same guidance every author of a question for the person is given.
