(ns canvas.claims.coordinator-idle
  "The claims the coordinator-idle design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.live :refer [record-live subjects]]))

(Claim subjects-are-live
  "A workstream is a subject while it can change, or a decision on another subject reads it, without anything writing to it: it is open and activated, it is an open drain or a slice an open drain has started, a session record under it names a session the registry or the agent listing holds live, or an unfinished Run names it. Open alone does not make one a subject, and neither does an activation a close has ended."
  {:about [record-live subjects] :evidence "round"})

