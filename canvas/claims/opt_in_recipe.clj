(ns canvas.claims.opt-in-recipe
  "The claims the opt-in-recipe design (ws-20261110-14db67) commits nido to: a session a person
   starts is a plain one until the person activates nido's recipe on its workstream, and every
   review launch, and every review loop, ends inside a wall clock.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [carry! conductors! reseat! hand-backs! hand-back!]]
            [canvas.coordinator.record.standing :refer [activated?]]
            [canvas.coordinator.record.workstream :refer [activate!]]
            [canvas.coordinator.work :as work]
            [canvas.coordinator.agent :refer [capped-budget-ms]]
            [canvas.platform.process :refer [wait-within!]]
            [canvas.tasks.nido-activate :refer [nido-activate]]
            [canvas.ui.surface :refer [ui-tui]]))

(Claim recipe-is-opt-in
  "A workstream is activated exactly when its ledger holds an :activated entry, which only workstream/activate! appends — reached from the nido:activate verb and the TUI board's A key through work/activate! — at most once and never on a closed workstream. On a workstream not activated: carry! does not carry a person's start (:start), answering :not-activated; conductors! starts no conductor and retires a running one; reseat! retires, occupies and escalates nothing; hand-backs! and hand-back! post nothing to a conductor. A Run's start (given-by nil) is carried as before, whatever the ledger holds."
  {:about [activated? activate! work/activate! carry! conductors! reseat! hand-backs! hand-back! nido-activate ui-tui] :evidence "test"})

(Claim activation-hands-over-understanding
  "work/activate! answers the instruction for the session's agent: write the records the recipe owes from what the session already understands — the review loops being what checks it — and then work the recipe as the whole seat's default embodiment says. nido:activate prints it; nido:owed reports a workstream not activated as owing nothing."
  {:about [work/activate! nido-activate] :evidence "test"})

(Claim review-runs-inside-a-wall-clock
  "Every review judge launch is killed at its budget — 30m by default, cut to the enclosing loop's deadline by capped-budget-ms — and a launch past the deadline is not started; a killed launch is :timed-out, never retried, never stood in for, and records no verdict. A review loop given a wall clock (2h by default from the review tasks) stops at the top of the first round past it with status :timed-out, which the pipeline escalates to the person."
  {:about [capped-budget-ms wait-within!] :evidence "test"})
