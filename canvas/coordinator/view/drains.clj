(ns canvas.coordinator.view.drains
  "Self-spec: `nido.coordinator.view.drains` — the drains surface, read-only."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]))

(Module view-drains
  "Each open drain as the /drains page shows it: phase, recipe version, newest census and its
   trend, slices by state, open escalations and misfits. Pure read model — NO WRITES."
  (Operation drain
    "Drain `drain-id` of `project` as the page shows it, or nil when it is no drain."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId]] :any]})
  (Operation drains
    "Every open drain of `project`, as `drain` reads it."
    {:signature [:=> [:catn [:project ProjectName]] :any]}))
