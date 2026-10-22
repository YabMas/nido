(ns canvas.coordinator.record.standing
  "Self-spec: `nido.coordinator.record.standing` — whether a record still holds."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.generation :as generation]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Standing
  "Whether a stamped record still holds, and what stops it if it does not — decided, decidable,
   what blocks it, who approved it."
  [:map [:decided? :boolean]
        [:decidable? :boolean]
        [:blocked [:maybe :any]]
        [:approved-by [:maybe :any]]
        [:owes-person? {:optional true} :boolean]])

(Module record-standing
  "Whether a design or baseline record still stands, derived from the ledger's own citations.

   DERIVED, never stored. A stored standing is a second source of truth that goes stale the
   moment anything is appended after it — and what makes a design stop standing is precisely a
   later entry, so the stored copy would be wrong exactly when it mattered."

  {:child [Standing]}
  (Operation of-design
    "Whether a stamped design record still holds, and what its standing rests on — the designs and
     decisions it weighs read from the current generation's span, intents and baselines whole."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:design :map]] Standing]
     :delegates [workstream/entries-of generation/current-span]})
  (Operation governing-design
    "The design that governs a workstream — the one record-generation's current-design names over
     the current span — read whole, or nil. Every reader of the governing design takes it from
     here or from `governing`, never from the newest :design entry."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [workstream/read-ws workstream/entry-at-seq generation/current-design]})
  (Operation governing
    "The governing design with its standing, or nil when none governs."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe [:map [:design :map] [:standing Standing]]]]
     :delegates [governing-design of-design]})
  (Operation proceeding?
    "Whether the newest decision round naming a design lets it proceed — the round's answer as
     it stands now, so a later sendback retracts an earlier proceed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:design-seq :int]] :boolean]
     :delegates [workstream/entries-of]})
  (Operation of-baseline
    "Whether a stamped baseline is still footing a design may be written on: found sufficient at
     its own number, and scoped for a goal nothing has replaced since."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:baseline :map]] :map]
     :delegates [workstream/entries-of]})
  (Operation of-intent
    "Whether an intent is still a goal work may rest on: not retracted, or retracted and replaced
     by an intent superseding it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:intent :map]] :map]
     :delegates [workstream/entries-of]})
  (Operation open-findings
    "The items of the newest findings round of the current generation's span that no later
     resolution entry names — the one reading of what a findings round still owes, for the
     position and the board's count alike."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:set :string]]
     :delegates [workstream/entries-of generation/current-span]})
  (Operation why-not-decided
    "Why a standing is not decided, in words a person can act on. nil when it is."
    {:signature [:=> [:catn [:standing Standing]] [:maybe :string]]})
  (Operation ever-chose?
    "Whether a stage was ever chosen for the workstream — what tells work started once from work
     never started."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:stage :keyword]] :boolean]
     :delegates [workstream/entries-of]})
  (Operation chosen-stage
    "The board stage a person or nido chose, while that choice still says where the work is: no
     record of the work is newer than it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:w :any]] [:maybe :keyword]]
     :delegates [workstream/entry-at-seq]})
  (Operation restored-at
    "The number of the newest reopen that undid a dismissal, or 0: what was recorded before it
     no longer counts toward where the ticket's triage stands."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:w :any]] :int]
     :delegates [workstream/entry-at-seq]})
  (Operation verdict
    "The newest verdict a person accepts or dismisses, and whether one did — or that the ticket
     was accepted as reported, with no verdict to cite."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:w :any]] [:maybe :map]]
     :delegates [restored-at workstream/entries-of]})
  (Operation born-scratch?
    "Whether the workstream was born a one-off: its ledger holds the birth entry."
    {:signature [:=> [:catn [:w :any]] :boolean]})
  (Operation newest-findings
    "The newest findings round of the current generation's span as written — its number and the
     phase it belongs to are what the next round and its settling derive from. Fails closed on a
     round that cannot be read."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:w :any]] [:maybe :map]]
     :delegates [workstream/entries-of generation/current-span]}))
