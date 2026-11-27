(ns canvas.claims.sweep-hold
  "The claims the sweep-hold reading commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [improvement-holds]]
            [canvas.ui.surface :refer [handle-request improvements-page sweep-fragment]]))

(Claim operations-shows-the-hold
  "The improvement-backlog page (/operations/improvements) and its five-second poll render improvement-holds above the proposals as a fragment of its own, patched in the same SSE event as the proposals: each hold as one line naming what it works on, no holds as nothing; and a reading that could not be made as a sentence saying so, with the proposals still rendered."
  {:about [sweep-fragment improvements-page handle-request] :evidence "round"})

(Claim holds-are-open-attempts
  "improvement-holds answers, for each project whose triggers declare an :improvement-sweep source, one hold per improvement attempt run not yet over, read from the core on every call, each in state :working."
  {:about [improvement-holds] :evidence "round"})
