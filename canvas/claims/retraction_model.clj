(ns canvas.claims.retraction-model
  "The claims the retraction-model design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [lane-pipeline of]]
            [canvas.coordinator.lane.reentry :refer [lane-reentry]]
            [canvas.coordinator.record.standing :refer [of-baseline of-design of-intent]]
            [canvas.strata :refer [record-status workstream-position]]))

(Claim retraction-unseats-what-rests-on-it
  "A design does not stand while its own seq, its cited baseline's seq, or its cited intent's seq is named by any retraction on the workstream; a baseline is not verified while its own seq or its cited intent's seq is. Which retraction is newest, and whether others follow, does not matter."
  {:about [of-design of-baseline] :evidence "test"})

(Claim retracted-goal-without-replacement-is-its-own-reason
  "A retracted intent blocks what cites it with the reason :goal-retracted until the goal is restated AFTER the retraction — the newest intent of its supersession chain appended later than the retraction; from then the goal-superseded reasons answer instead and name that intent. A restatement written before the retraction answers nothing. The same order decides whether a baseline's correction answers its retraction."
  {:about [of-design of-baseline of-intent] :evidence "test"})

(Claim land-refuses-a-retracted-goal
  "The land gate, which reads a design's standing, refuses a branch whose design serves a retracted intent."
  {:about [of-design] :evidence "test"})

(Claim position-reads-retraction-through-standing
  "The pipeline's retracted positions come from standing's blocked reason for the workstream's newest design, else its newest baseline, else its newest intent — :design-retracted, :premise-retracted, or :intent-retracted for :goal-retracted — and a retraction that unseats none of those holds no position. The pipeline reads no :retraction entry."
  {:about [of lane-reentry] :evidence "test"})

(Claim retracted-position-advises-its-own-repair
  "Each retracted position's next stage writes the record that replaces what was retracted: :design-retracted a design, :premise-retracted a baseline, :intent-retracted an intent."
  {:about [of] :evidence "test"})

(Claim readers-stratified-over-standing
  "Standing is declared as the record-status stratum resting on the ledger; the pipeline and re-entry as workstream-position resting on record-status and the ledger; the review programs rest on record-status. design:check refuses any dependency between their modules no edge declares."
  {:about [record-status workstream-position] :evidence "law"})

(Claim no-live-workstream-reseated
  "Every workstream on disk when this lands keeps the position it had under the parent change — brian ws-20260921-82071b included: its retracted intent 57 is cited by no baseline or design, so it unseats nothing, and its retracted baseline 73 is not what its current design stands on."
  {:about [lane-pipeline] :evidence "round"})
