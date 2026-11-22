(ns canvas.core.buffer
  "Self-spec: `nido.core.buffer`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module core-buffer
  "The workstream's one conductor, as the core runs it. Every outcome of any of
   its units' turns, in any project, and every outside event that bears on them
   (a merge, a premise moved) comes to it, and it directs the arc: the
   unit's next turn, a new unit proposed for the person to grant, or an
   operator ask, which binds the pending calls of the units' asks it raises or,
   where no unit's call is pending, its own conductor turn's call [conductor-asks-go-to-the-person]. Each ask
   a unit's turn raises that no hold on its unit has taken over, it
   answers, returns for further exploration, or raises in an operator ask, alone or consolidated
   with asks from sibling units, bound to their pending calls; it withdraws an operator
   ask whose premise changed, including by a sibling unit — one binding a conductor
   turn's own call among them — which answers no call it binds. It runs
   asleep — woken per outcome or event — or resident, while a person chats
   with it.

   HIDES the policy that decides between them, so a unit's ask awaits the person only
   in an operator ask it raised.")
