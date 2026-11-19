(ns canvas.claims.card-approval
  "The claims the card-approval design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.report.ask :refer [report-ask]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor]]))

(Claim options-grant-only-the-goal
  "A blocker option may hand the person's act to a seat only for :intent or :merge, under :grants, and no option approves a design. The card says beside the option what picking it hands over, and the conductor's brief tells it that no option approves a design and that the next decision round reads a person's answer to a design question."
  {:about [coordinator-report report-ask lane-conductor] :evidence "test"})
