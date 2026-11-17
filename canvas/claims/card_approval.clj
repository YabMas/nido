(ns canvas.claims.card-approval
  "The claims the card-approval design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [resolve-gate! grant-design!]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.report.ask :refer [report-ask]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor]]))

(Claim approving-pick-is-the-grant
  "Picking an option that names a design under :approves-design calls grant-design! for that design, its note the option's label and summary, before the card's answer is written. The answer is written only when grant-design! answers :approved or :already-granted; on any other answer the click is refused, naming grant-design!'s :because, or its :decision when it carries none, and writes nothing at all. An option naming no design writes no :design-approved."
  {:about [resolve-gate! grant-design!] :evidence "test"})

(Claim options-grant-only-the-goal
  "A blocker option may hand the person's act to a seat only for :intent, under :grants; it approves a design only by naming that design under :approves-design, never under :grants. The card says beside the option what picking it approves or hands over, and the conductor's brief tells it to mark an approving option with the design it approves and never to ask again or hand the person a command for that approval."
  {:about [coordinator-report report-ask lane-conductor] :evidence "test"})
