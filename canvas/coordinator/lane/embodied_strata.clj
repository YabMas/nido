(ns canvas.coordinator.lane.embodied-strata
  "Self-spec: `nido.coordinator.lane.embodied-strata` — a workstream carried by one seat per stratum."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-embodied-strata
  "The first embodiment: a design's strata, each embodied by a seat. A design carried this way marks
   its strata generation-defining; which elements are seats is still the mark's, read below.

   HIDES that the generation-defining elements it carries are exactly a design's strata — it
   refuses a design that leaves a stratum unmarked or marks anything else, naming each; that a seat
   is named by its stratum's id; and that an occupant is briefed to raise a tension first with the
   strata its stratum rests on or that rest on it — practice in a brief, since nothing below may
   read the :rests-on edges canvas declares. Written in the seats vocabulary; nothing is written in it."
  (Operation embodiment
    "The strata embodiment's seat naming and brief practice, as the data occupancy takes."
    {:signature [:=> [:catn] :map]})
  (Operation carry!
    "Carry a workstream whose governing design marks exactly its strata: occupy each stratum's
     seat; refuse any other, naming each stratum left unmarked and each non-stratum marked."
    {:signature [:=> [:catn [:project ProjectName] [:ws WorkstreamId]] :map]}))
