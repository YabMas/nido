(ns canvas.claims.recipe-activation
  "The claims the recipe-activation design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [activate! deactivate!]]
            [canvas.tasks.nido-land :refer [check]]
            [canvas.tasks.nido-session :refer [start]]))

(Claim activation-is-recorded
  "After work/activate! returns, the workstream's ledger activation is :activated, whether or not the core already held its unit; when this activate! opens the unit, the entry precedes that unit's first turn."
  {:about [activate!] :evidence "test"})

(Claim deactivation-is-recorded
  "After work/deactivate! returns on an activated workstream, its ledger activation is :deactivated."
  {:about [deactivate!] :evidence "test"})

(Claim start-names-the-recipe
  "The first instruction nido:workstream:start hands the core names intent, baseline and design as owed before code, then the brief verbatim."
  {:about [start] :evidence "test"})

(Claim activated-designless-refused
  "land:check's standing check answers 1 on an activated workstream holding no design record, naming the stage the ledger owes; on a plain (never activated or deactivated) workstream with none it still answers 0."
  {:about [check] :evidence "test"})
