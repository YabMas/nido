(ns canvas.coordinator.record.standing
  "Self-spec: `nido.coordinator.record.standing` — whether a record still holds."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.vocabulary :refer [ProjectName WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream]
            [fukan.common.typing.malli]))

(Kind Standing
  "Whether a stamped record still holds, and what stops it if it does not — decided, decidable,
   what blocks it, who approved it."
  [:map [:decided? :boolean]
        [:decidable? :boolean]
        [:blocked [:maybe :any]]
        [:approved-by [:maybe :any]]])

(Module record-standing
  "Whether a design record still stands, derived from the ledger's own citations.

   DERIVED, never stored. A stored standing is a second source of truth that goes stale the
   moment anything is appended after it — and what makes a design stop standing is precisely a
   later entry, so the stored copy would be wrong exactly when it mattered."
  (Operation of-design
    "Whether a stamped design record still holds, and what its standing rests on."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:design :map]] Standing]
     :delegates [workstream/entries-of]})
  (Operation why-not-decided
    "Why a standing is not decided, in words a person can act on. nil when it is."
    {:signature [:=> [:catn [:standing Standing]] [:maybe :string]]}))
