(ns canvas.coordinator.view.activity
  "Self-spec: `nido.coordinator.view.activity` — whether anything is happening in a workstream,
   and what, as one answer."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.activity :as claims]
            [canvas.coordinator.record.runs :as runs]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Activity
  "Every facet of what is happening in a workstream: what is underway, whether anything runs,
   who owes the next move, whether a person's pause stands, whether it has stalled and why,
   whether the core asks the person, and its engagement.

   ONE ANSWER, so no two facets can disagree: each is a field of `of` over one observation.
   Underway implies live, save a merge waiting its turn; stalled implies nothing live and an
   agent owing the move; needs-you implies the person owes it."
  [:map {:closed true}
   [:doing [:maybe :map]]
   [:live :boolean]
   [:owed-by [:enum :person :agent :machine :nobody]]
   [:paused :boolean]
   [:stalled :boolean]
   [:needs-you :boolean]
   [:engagement [:enum :settled :parked-at-gate :active :stalled :queued :idle]]
   ;; Present exactly when :stalled is true — held by the claim stall-carries-why.
   [:why {:optional true}
    [:or
     [:map {:closed true}
      [:turn-end [:enum :recorded]]
      [:session :string]
      [:ended-at :string]
      [:owes [:maybe :keyword]]
      [:said {:optional true} :string]]
     [:map {:closed true}
      [:turn-end [:enum :none]]
      [:owes [:maybe :keyword]]]]]])

(Module view-activity
  "A workstream's activity: one pure function over one observation, and the read that makes the
   observation's live evidence. Pure read model — NO WRITES."
  {:child [Activity]}
  (Operation of
    "Every facet of a workstream's activity, from its observation alone: the record facts status
     gives, the live evidence observed now and the core's activity. Reads nothing."
    {:signature [:=> [:catn [:obs :map]] Activity]})
  (Operation observe
    "The live evidence of a workstream's observation, as its records hold it now: the claim and
     whether it is held, each run with whether its agent holds its lock, each session with whether
     it is up and mid-turn. Which sessions are up and which are mid-turn are the caller's to
     observe."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]
                            [:up [:maybe [:set :string]]] [:mid-turn [:maybe [:set :string]]]]
                 :map]
     :delegates [claims/read-live claims/held? runs/live-runs runs/runs-of session/list-sessions round-progress]})
  (Operation round-progress
    "How far a running round has got — its number, the phase running, what its judging
     raised — read off the report it keeps as it goes."
    {:signature [:=> [:catn [:report [:maybe :map]]] [:maybe :map]]}))
