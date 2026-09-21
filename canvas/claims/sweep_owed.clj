(ns canvas.claims.sweep-owed
  "The claims the sweep's owed-while-held design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.source.sweep :refer [poll-once!]]))

(Claim owed-counted-while-held
  "poll-once! returns :owed as the number of proposals owed over the project's proposals, plans and claim attempts, whether or not an :improvement workstream is open — the same count a free poll over the same ledger returns."
  {:about [poll-once!] :evidence "round"})

(Claim held-only-silences-the-poll
  "An open :improvement workstream decides only whether poll-once! emits: a held poll emits nothing, and a free poll emits exactly what it would have emitted before the owed count was derived while held."
  {:about [poll-once!] :evidence "round"})
