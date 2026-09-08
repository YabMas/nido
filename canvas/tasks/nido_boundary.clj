(ns canvas.tasks.nido-boundary
  "Self-spec: `tasks.nido-boundary` — a bb task entry point, run as a host Stop hook."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.tasks.nido-owed :as owed]
            [fukan.common.typing.malli]))

(Module nido-boundary
  "What nido asks for at a session's turn boundary.

   IT ASKS AND NEVER DECIDES, and every law this module carries is shaped by that. The host
   resolves a turn across every hook that answered and grants a continuation any ONE of them
   requests, so exit 2 here is a vote and exit 0 an abstention. What follows is that nido is never
   the reason a session carries on past a person — and never the reason one stops either, because
   a project's own Stop hook keeps its session going whatever this says. Promising otherwise was
   the defect the decision round found three times in this design.

   IT RUNS NO STAGE. The smaller of the two claims open at design time: a boundary that ran what
   it found would be an actor, judged as one, and would take a working copy out from under
   whoever is typing in it. Running what is owed stays `nido-attach`, which a person or a model
   asks for deliberately.

   FAILING OPEN IS THE CONTRACT rather than a defensive habit. No workstream, a position the
   pipeline will not place, a ledger it cannot read, a throw of any kind — each asks for nothing,
   and a turn nobody asked to continue ends as it does today. A hook that breaks a session it
   could not even resolve is worse than no hook, and this is installed in every guided session.

   THE WAIT KEEPS NO STATE. A boundary a person owes is not ended: the same question is asked
   again, in the same process, until the fold answers differently. An answer is an append and the
   next fold reads it, so nothing is delivered to a waiting session and a session that dies
   mid-wait leaves nothing for a later reader to reconcile."
  (Operation asks-for
    "What nido asks of this boundary, given what the workstream owes. Pure over the answer, and
     the whole of the hook's judgement.

     The CLAIM is read before the position, deliberately: a workstream something else is already
     advancing owes this caller nothing, and waiting there would put a person's session to sleep
     because a robot is busy — the claim excludes other claim-takers, never whoever is typing in
     the tree. `:wait` is the loop's own instruction rather than an answer a caller can act on,
     so it never reaches an exit code."
    {:signature [:=> [:catn [:answer [:maybe :map]]] :map]})
  (Operation ask-at
    "Fold the ledger, and keep folding while a person owes the next move — until the answer
     changes or the wait is spent. Releasing on a RE-READ rather than on a message is what makes
     the wait stateless, and what carries a session across a stage a person owes with nothing
     typed into it. A spent wait withdraws nido's request; it does not end the turn."
    {:signature [:=> [:catn [:cwd :any] [:opts [:? :map]]] :map]
     :delegates [owed/owed-at asks-for]})
  (Operation boundary-cmd*
    "Ask, then answer the way a Stop hook is heard: the reason on stderr for a continuation, and
     nothing otherwise. Catches everything — see the module's failing-open law."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [ask-at owed/owed-line]})
  (Operation boundary-cmd
    "The `boundary-cmd` entry point: exits 2 to request a continuation, 0 to request nothing."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [boundary-cmd*]}))
