(ns canvas.claims.operations-home
  "The claims the Operations home design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.ui.surface :refer [handle-request operations-home-cards]]))

(Claim home-cards-read-what-pages-read
  "The Operations home renders one card per concern — the improvement backlog, session recovery, and the review queue wherever a project declares its trigger — each linking to that concern's own page and computed from the same work readings that page renders; a concern whose reading failed says so on its card rather than showing zero."
  {:about [operations-home-cards handle-request] :evidence "round"})
