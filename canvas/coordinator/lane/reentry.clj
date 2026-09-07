(ns canvas.coordinator.lane.reentry
  "Self-spec: `nido.coordinator.lane.reentry` — how far up the arc a workstream's records still
   stand.

   Its own spec file rather than a module beside `lane-pipeline`, because an Operation's symbol
   IS its var: both modules publish an `of`, and two in one file would have the second silently
   replace the first."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.standing :as standing :refer [Standing]]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream :refer [Workstream]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-reentry
  "How far up the arc a workstream's records still stand.

   Its own boundary, taken OUT of `lane-pipeline` rather than added to it. The position fold
   decides its record-trail clauses on whether an entry kind is present in the index, and an
   index is append-only — so those clauses are monotone and no later entry can un-pass a stage
   they placed. The missing half is a reading of the same ledger saying how far up it is still
   good for; this is that reading, and the clamp stays `lane-pipeline`'s.

   Not folded into `record-standing` either. That module answers about ONE design record and is
   what the landing gate, the approval gate and this all ask; a version of it that also knew
   about draft PRs would keep two secrets. DERIVED, never stored, for the reason both
   neighbours give."

  (Operation trail-standing
    "How each trail kind stands against the current design: which are current, which are behind."
    {:signature [:=> [:catn [:w Workstream] [:current :int]] :map]})
  (Operation of*
    "Re-entry from what a caller already holds — pure, and the arity the fold uses."
    {:signature [:=> [:catn [:w Workstream] [:design [:maybe :map]] [:standing [:maybe Standing]]]
                 [:maybe :map]]
     :delegates [trail-standing]})
  (Operation of
    "Re-entry for a workstream, reading what it needs."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [of* workstream/read-ws standing/of-design]}))
