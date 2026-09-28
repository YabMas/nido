(ns canvas.claims.review-queue
  "The claims the review-queue grooming design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.control :refer [fire!]]
            [canvas.coordinator.work :refer [begin-review-apply! decide-review-item! review-queues]]
            [canvas.ui.surface :refer [handle-request review-queue-fragment]]))

(Claim review-apply-fires-once-on-frozen-decisions
  "A review-queue plan's decisions are recorded per item number, only for items the plan holds and only as approved or skipped. begin-review-apply! refuses, freezing nothing, while any item holds no decision; otherwise it freezes the decisions, after which no decision changes, and under the plan's lock queues the apply through the control/fire! handle-request hands it, keyed by the plan run's id. control/fire! queues a keyed envelope and takes its key in one atomic write that the drain never undoes, and a fire whose key is taken queues nothing — so one plan's apply trigger is queued exactly once however often Apply is pressed and wherever a process stops: a stop after queueing leaves the key taken and the retry queues nothing, a stop before it leaves the key free and the retry queues the one envelope. A plan whose fire is recorded is refused; a frozen plan whose fire is unrecorded is fired again with the same decisions on the next Apply."
  {:about [decide-review-item! begin-review-apply! handle-request fire!] :evidence "round"})

(Claim triggers-fire-through-control
  "Every envelope the Operations surface queues, it queues through control/fire! — the review-queue page's run lever calling it, and its apply lever handing it to begin-review-apply! to call under the plan's lock — and never through the queue namespace or an envelope the work plane builds."
  {:about [handle-request fire!] :evidence "round"})

(Claim review-queue-in-rank-order
  "review-queues presents a plan's tickets in the order of their rank, and the review-queue page renders them in that order, each with its rank and its reason; every rank the plan would write to Notion, adding the Review rank property, clearing a departed ticket's rank and sorting the Review view by Review rank are each a numbered item, approved or skipped through decide-review-item! like any other write, so no rank reaches Notion that a person did not approve."
  {:about [review-queues review-queue-fragment decide-review-item!] :evidence "round"})
