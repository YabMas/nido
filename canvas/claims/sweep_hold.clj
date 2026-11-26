(ns canvas.claims.sweep-hold
  "The claims the sweep-hold reading commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.ui.surface :refer [handle-request improvements-page sweep-fragment]]))

(Claim operations-shows-the-hold
  "The improvement-backlog page (/operations/improvements) and its five-second poll render improvement-holds above the proposals as a fragment of its own, patched in the same SSE event as the proposals: a stuck hold as a notice naming the workstream, saying nothing is planned or implemented until it is closed and when its last session ended; a waiting hold as a pointer to its gate; a working hold as one line naming what it works on; no holds as nothing; and a reading that could not be made as a sentence saying so, with the proposals still rendered."
  {:about [sweep-fragment improvements-page handle-request] :evidence "round"})
