(ns canvas.coordinator.view.drains
  "Self-spec: `nido.coordinator.view.drains` — the drains surface, read-only."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]))

(Module view-drains
  "Each open drain as the /drains page shows it: phase, recipe version, newest census and its
   trend, slices by state, open escalations and misfits. Pure read model — NO WRITES.

   HIDES how a drain's phase, each slice's state and its open escalations are read off the
   drain's records, its slices' ledgers and the core.

   Its interface is one value per open drain: :project, :ws-id, :phase, :version, :recipe,
   :proposals (:seq :reason :at :recipe — the recipe each pending proposal would install),
   :census (:seq :remaining :groups :at), :trend, :plans, :slices (:ws-id :plan :started-at
   :state :sites :misfits count), :by-state, :misfits, :residue, :charter (:exit
   :established-by :at), :escalations."
  (Operation drain
    "Drain `drain-id` of `project` as the page shows it, or nil when it is no drain."
    {:signature [:=> [:catn [:project ProjectName] [:drain-id WorkstreamId]] :any]})
  (Operation drains
    "Every open drain of `project`, as `drain` reads it."
    {:signature [:=> [:catn [:project ProjectName]] :any]}))
