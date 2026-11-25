(ns canvas.claims.no-design-grant
  "The claims the no-design-grant design commits nido to: a design is built once its decision round
   proceeds, no person's grant is written, and a person's judgement reaches a design only as a
   blocker the next round reads.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [lane-pipeline next-action]]
            [canvas.coordinator.lane.reentry :refer [lane-reentry]]
            [canvas.coordinator.record.standing :refer [of-design]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.tasks.nido-design :as nido-design]
            [canvas.tasks.nido-land :as nido-land]
            [canvas.tasks.nido-test :as nido-test]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.report.ask :refer [ask]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.review.record :refer [review-record answered clear!]]))

(Claim convergence-is-the-gate
  "A design no :design-approved written before this change names reaches implementation only on a :design-cleared, and the round, or the clearance stage after it, writes one for every such standing design whose newest decision proceeds — by report/proceeds?, which a decision keeping a person's unanswered ask (:unpaired-recommend) does not, so that decision parks as a blocker — whatever its :standing or :baseline relation declares. Once a :design-cleared or a :design-approved names a design, no decision round judges it again and the ledger takes no decision naming it: its review is over, and revisiting it is a superseding design, decided by a round of its own. A design a :design-approved written before this change names is admitted as [old-grants-still-read] reads it, and owes no clearance. A standing design whose newest decision proceeds owes no person a move while it is placed at :design-decided, :design-cleared or :design-approved."
  {:about [review-record clear! lane-pipeline] :evidence "round"})

(Claim no-grant-is-written
  "The ledger refuses every new :design-approved append, and no verb, gate action or round offers to write one; nor does any verb's output or way-out — nido:land:check, nido:review:design and nido:attach among them — or any skill's text tell a person to approve, accept or grant a design, or name bb nido:design:approve."
  {:about [record-workstream coordinator-work] :evidence "round"})

(Claim old-grants-still-read
  "A :design-approved already on a ledger is read as before — it validates, it counts toward :cleared?, and it answers an invalidating verdict appended before it — so no ledger existing today changes position or becomes :unplaceable because of this change, except a design at :design-decided or :design-invalidated, which moves to the step this design names for those positions."
  {:about [of-design coordinator-report] :evidence "round"})

(Claim blocker-answers-reach-the-round
  "A person's :blocker-answered — not one nido wrote itself (:by :nido) nor one recorded for an act of the person's (:act) — whose blocker was appended after the newest decision naming the design is given to the next decision round's judge as the person's answer to that decision's asks, and no decision older than such an answer is reused in place of a fresh judgement; answering the blocker does not itself let the design proceed."
  {:about [answered review-record] :evidence "round"})

(Claim invalidation-routes-back
  "An invalidating verdict on the governing design, with no grant appended after it, leaves the design not cleared, and a workstream placed at :design-invalidated has next action the :design stage in :authoring mode; a superseding design answers it, and implementation waits for that design's own proceeding decision."
  {:about [of-design lane-pipeline lane-reentry] :evidence "round"})

(Claim a-person-owes-four-moves
  "On a workstream's arc from intent to landing, the position level names the moves only a person can make as :human stages of next-action — accept-triage, assert-gate, answer-blocker and land — each with one ask kind. A drain keeps the moves it owes a person, approve-recipe and close-drain."
  {:about [next-action ask] :evidence "round"})

(Claim decisions-come-from-the-round
  "A :design-decision joins a ledger only as the judgement a decision round reached: the ledger takes the kind through one writer, which review-record's round alone calls, and every other append — the one bb nido:workstream:entry:add makes among them — refuses it."
  {:about [record-workstream review-record] :evidence "round"})

(Claim the-landing-passes-its-checks
  "The tree this change lands passes bb nido:test, bb nido:design:check and bb nido:land:check."
  {:about [nido-test/run nido-design/check nido-land/check] :evidence "round"})
