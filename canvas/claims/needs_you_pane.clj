(ns canvas.claims.needs-you-pane
  "The claims the needs-you-pane design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [list-workstreams gates]]))

(Claim paused-is-not-needs-you
  "An open workstream's row is :needs-you exactly when status says a person owes its next move and no person's pause stands on it. No workstream with a standing pause is a gate, held or unheld: it is absent from all-gates and carries no :gate on its workstream map, and a paused drain is not put on the list by the slices that raise it. Lifting the pause gives back exactly what its position and its drain give it."
  {:about [list-workstreams gates] :evidence "round"})

(Claim one-gate-rule
  "gates answers, held or unheld, through one inbox-gates fold over rows that each carry a gate only by one rule: no person's pause stands on the workstream, and it is :needs-you or a between-phases workstream awaiting its gate (a pause left standing when a closure came after it still keeps it off). The fold then lists a drain in place of its owing slices, unless a pause stands on the drain. A permission denial recorded in a session nido drives makes no gate on either path."
  {:about [gates] :evidence "round"})
