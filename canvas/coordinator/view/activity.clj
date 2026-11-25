(ns canvas.coordinator.view.activity
  "Self-spec: `nido.coordinator.view.activity` — whether anything is happening in a workstream,
   and what, as one answer."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.activity :as claims]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Activity
  "Every facet of what is happening in a workstream: what is underway, whether anything runs,
   who owes the next move, whether a person's pause stands, whether it has stalled, whether the
   core asks the person, and its engagement.

   ONE ANSWER, so no two facets can disagree: each is a field of `of` over one observation.
   Underway is live; stalled implies nothing underway and an open unit of the core's that nobody
   holds; needs-you implies the person owes it."
  [:map {:closed true}
   [:doing [:maybe :map]]
   [:live :boolean]
   [:owed-by [:enum :person :agent :machine :nobody]]
   [:paused :boolean]
   [:stalled :boolean]
   [:needs-you :boolean]
   [:engagement [:enum :settled :parked-at-gate :active :stalled :queued :idle]]])

(Module view-activity
  "A workstream's activity: one pure function over one observation, and the read of the claim that
   says what a running turn is doing. Pure read model — NO WRITES."
  {:child [Activity]}
  (Operation of
    "Every facet of a workstream's activity, from its observation alone: the record facts status
     gives, the core's state of it and the claim its running turn holds. Reads nothing."
    {:signature [:=> [:catn [:obs :map]] Activity]})
  (Operation observe
    "The activity claim held on a workstream now, with its round's progress — what a turn the core
     runs is doing. Never evidence that anything runs."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [claims/read-live round-progress]})
  (Operation round-progress
    "How far a running round has got — its number, the phase running, what its judging
     raised — read off the report it keeps as it goes."
    {:signature [:=> [:catn [:report [:maybe :map]]] [:maybe :map]]}))
