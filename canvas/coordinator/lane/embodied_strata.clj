(ns canvas.coordinator.lane.embodied-strata
  "Self-spec: `nido.coordinator.lane.embodied-strata` — a workstream carried by one seat per stratum."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.seats :as seats]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-embodied-strata
  "The first embodiment: a design's strata, each embodied by a seat. A design carried this way marks
   its strata generation-defining; which ids are seats is still the generation's, read below.

   HIDES that the generation-defining elements it carries are exactly a design's strata — it
   refuses a design that leaves a stratum unmarked or marks anything else, naming each; that a seat
   is named by its stratum's id; and that an occupant is briefed to raise a tension first with the
   strata its stratum rests on or that rest on it — practice in a brief, since nothing below may
   read the :rests-on edges canvas declares. Written in the seats vocabulary; nothing is written in it."
  (Operation embodiment
    "The strata embodiment's seat naming, brief practice and refusal of a workstream its strata do
     not carry, as the data occupancy takes — the refusal run inside each occupancy transaction."
    {:signature [:=> [:catn] :map]})
  (Operation refused?
    "Whether an exception is the embodiment refusing a workstream, as against an occupant whose
     launch failed."
    {:signature [:=> [:catn [:e :any]] :boolean]})
  (Operation carry!
    "Carry a workstream whose governing design marks exactly its strata: occupy each stratum's
     seat; refuse any other, naming each stratum left unmarked and each non-stratum marked."
    {:signature [:=> [:catn [:project ProjectName] [:ws WorkstreamId]] :map]
     :delegates [seats/occupy!]})
  (Operation occupy!
    "Occupy one stratum's seat, refused as carry! refuses a workstream its strata do not carry."
    {:signature [:=> [:catn [:project ProjectName] [:ws WorkstreamId] [:seat :string]] :map]
     :delegates [seats/occupy!]})
  (Operation replace!
    "Replace one stratum's seat's occupant, refused as carry! refuses a workstream its strata do
     not carry."
    {:signature [:=> [:catn [:project ProjectName] [:ws WorkstreamId] [:seat :string]] :map]
     :delegates [seats/replace!]}))
