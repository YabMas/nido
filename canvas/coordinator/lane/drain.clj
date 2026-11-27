(ns canvas.coordinator.lane.drain
  "Self-spec: `nido.coordinator.lane.drain` — running a drain."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]))

(Module lane-drain
  "A drain's life: how a drain is started, how the coordinator's slice plan is recorded and when a
   slice may start from it, how a slice's brief is composed
   from the drain's current recipe and the coordinator's from the drain's records, how a slice's misfit
   is recorded (from a session of a slice of the drain, naming the site its recipe version does not
   cover) and answered, when the coordinator is woken and what waits for its wake, who may land a
   slice, what suspends autonomy, and when the drain may close.

   The coordinator's LEVERS, each a typed append through this lane: plan a slice, withdraw or
   replace an unconsumed plan, order slices by what they rest on, clear a ready slice with the
   residue it leaves and each item's disposition (a carved misfit answer carries its site's the
   same way), dispose of a residue item again (a follow-up slice now, or the closing residue
   slice), answer a misfit, and propose a recipe or a change to it — which becomes the drain's
   recipe only by the person's approval. Each slice lands in its own session, in parallel with its
   siblings, once the core grants it the landing.

   ONE COORDINATOR. The drain workstream's conductor in the core coordinates it; a slice's unit is
   opened in the slice's own workstream. The lane reaches the core only through the map of
   functions its caller injects, so it names no core namespace. Landing is a person's act, or this
   lane's while the person's grant stands — never a coordinator's or a slice's turn."
  (Operation answer-misfit!
    "The coordinator answering an open misfit."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId] [:misfit-seq :any] [:answer :any]] :any]})
  (Operation approve!
    "The person approving a recipe proposal. Answers the new version."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId] [:proposal-seq :any]] :any]})
  (Operation clear!
    "The coordinator clearing a slice for landing with the residue it leaves."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId] [:slice-id WorkstreamId] [:residue-items :any]] :any]})
  (Operation close!
    "The person closing the drain: the exit observed — the newest census reads zero and every residue item is covered by a landed slice, or it is refused — then the drain closed done. Answers the :exit-observed seq."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId]] :any]})
  (Operation coordinator-brief
    "Drain `drain-id`'s coordinator brief, composed from the drain's records alone: its charter, its recipe and pending proposals, its census series, its plans, its slices and their positions, its misfits and residue, and its open escalations — and the levers it pulls."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId]] :any]})
  (Operation dispose!
    "The coordinator disposing of a residue item again."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId] [:residue-seq :any] [:disposition :any]] :any]})
  (Operation handle-fire!
    "Carry out one fire source-drain emitted: start the slice it names, or wake the coordinator with the census failure it reports. A start refused because what it decided from has moved is logged, not thrown: the next poll decides again."
    {:signature [:=> [:catn [:core :map] [:fire :any]] :any]})
  (Operation land!
    "The person asking for slice `slice-id` to land: refused while a slice it rests on has not landed (:rests-unlanded) or its newest review under its recipe version has not passed (:not-ready). Then the landing is granted on the slice in the core, whose conductor lands it in the slice's own session, beside its siblings. A slice the coordinator has not cleared is landed as the person's override. Answers {:slice :override? :granted}."
    {:signature [:=> [:catn [:core :map] [:project ProjectName] [:slice-id WorkstreamId]] :map]})
  (Operation misfit!
    "A slice's session recording a site its recipe version does not cover, on its drain. Refused from any session but one of a slice of the drain it names — or, given none, of the drain its session's slice belongs to. Answers {:drain :misfit}."
    {:signature [:function [:=> [:catn [:project ProjectName] [:arg1 :any]] :any] [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId] [:arg2 :any]] :any]]})
  (Operation plan!
    "The coordinator planning a slice. Answers the plan's seq."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId] [:plan :any]] :any]})
  (Operation propose!
    "The coordinator proposing a recipe, or a change to it. Answers the proposal's seq."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId] [:proposal :any]] :any]})
  (Operation slice-brief!
    "Slice `slice-id`'s brief, composed from its drain's current recipe — steps, ready gates and standing orders verbatim — and the plan it was started from, the slice first moved to that recipe with a :slice-rebased when it ran an older version, so every brief a slice session is given is of the version its ledger records."
    {:signature [:=> [:catn [:project ProjectName] [:slice-id WorkstreamId]] :any]})
  (Operation start-drain!
    "The person starting a drain from a pattern established elsewhere. Answers its ws-id."
    {:signature [:=> [:catn [:project ProjectName] [:charter :any]] :any]})
  (Operation wake-pending?
    "Whether drain `drain-id`'s coordinator has not yet heard one of its wakes — its charter, a census, a landing or a failed landing: a wake not posted, or posted and still in its conductor's inbox. While one is pending, no slice of the drain starts."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId] [:core :map]] :any]})
  (Operation wakes!
    "Post to each drain the core holds open the wake for each event its coordinator has not been woken for — a notice to its conductor whose id is the event's key, so each is posted once. Answers what each post answered."
    {:signature [:=> [:catn [:project ProjectName] [:core :map]] :any]})
  (Operation withdraw!
    "The coordinator withdrawing an open plan."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId] [:plan-seq :any] [:why :any]] :any]}))
