(ns canvas.claims.default-conductor
  "The claims the default-conductor design commits nido to: every workstream work has started on has one conductor
   and at least one seat, a turn end returns to whoever gave the instruction it ended, and each
   hand-back the conductor receives is answered by exactly one recorded decision.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [lane-pipeline]]
            [canvas.coordinator.lane.work :refer [lane-scratch]]
            [canvas.coordinator.record.seats :refer [record-seats]]
            [canvas.session.commit-gate :refer [session-commit-gate]]))

(Claim seats-of-a-generation
  "Every generation has at least one seat: one per id it fixes, each seat's id that element's id, or — while it fixes none, whether no design marks any or its opening carries none — exactly one whole seat whose id is the generation's own and is no element's id; its interface is the governing design's whole (none before a design)."
  {:about [record-seats] :evidence "round"})

