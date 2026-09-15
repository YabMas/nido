(ns canvas.coordinator.report.model
  "Self-spec: `nido.coordinator.report.model` — the part a baseline and a design have in common.

   A second shared value type beside the ledger's vocabulary, and in the same band for the reason
   that one is: every altitude that speaks a baseline or a design speaks this, it reads nothing,
   and it depends on nothing above the floor.

   What it models is an area as ELEMENTS and CLAIMS. An element is a declared Module, Operation,
   Kind or Role, named by its canvas identity. A claim has an id, the elements or roles it is
   about, its statement, and the evidence that checks it. A baseline's model is the area as it
   is; a design's is the area as the change leaves it. Both records carry one of these, and
   nothing structural about the area lives outside it."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.report :as report]
            [fukan.common.typing.malli]))

(Kind Element
  "One declared element of the area: its canvas identity, its sort, and — for a module — what it
   hides and what the rest may assume of it."
  [:map [:id :string] [:sort [:enum :module :operation :kind :role]]])

(Kind Claim
  "One claim: a stable id, the element or role ids it is about, its statement, what would falsify
   it, and its evidence — judged by a round, covered by named tests, or checked by a named law."
  [:map [:id :string] [:about [:vector :string]] [:statement :string]
        [:evidence [:map [:by [:enum :round :test :law]]]]])

(Kind Model
  "An area as elements and the claims made about them. The common part of a baseline and a
   design."
  [:map [:elements [:vector Element]] [:claims [:vector Claim]]])

(Module report-model
  "The shared model of an area, and the readings every consumer takes it through.

   A record of any era answers through these: a current record carries its model, and an older
   one's modules, load-bearing properties, composition and invariants are read AS elements and
   claims, so no reader asks which era wrote a record. What an older record could not say stays
   missing — an invariant's claim has no id — rather than invented."
  {:child [Element Claim Model]}
  (Operation model
    "The model a baseline or design states, whichever era wrote it; nil for any other record."
    {:signature [:=> [:catn [:record [:maybe report/LedgerEvent]]] [:maybe Model]]
     :delegates [report/invariant]})
  (Operation claims
    "A record's claims, whichever era wrote it."
    {:signature [:=> [:catn [:record [:maybe report/LedgerEvent]]] [:vector Claim]]
     :delegates [model]})
  (Operation elements
    "A record's elements, whichever era wrote it."
    {:signature [:=> [:catn [:record [:maybe report/LedgerEvent]]] [:vector Element]]
     :delegates [model]})
  (Operation claims-by-id
    "A model's claims keyed by id. A claim with no id cannot be named, so it is not keyed."
    {:signature [:=> [:catn [:model Model]] [:map-of :string Claim]]}))
