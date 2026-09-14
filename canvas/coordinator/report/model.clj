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
   hides and what the rest may assume of it. A role names who plays it, each a module, operation or
   kind the same model lists, and only a role names players."
  [:map [:id :string] [:sort [:enum :module :operation :kind :role]]
        [:plays {:optional true} [:vector :string]]])

(Kind Claim
  "One claim: a stable id, the element or role ids it is about, its statement, what would falsify
   it, and its evidence — judged by a round, covered by named tests, or checked by a named law."
  [:map [:id :string] [:about [:vector :string]] [:statement :string]
        [:evidence [:map [:by [:enum :round :test :law]]]]])

(Kind Model
  "An area as elements and the claims made about them. The common part of a baseline and a
   design."
  [:map [:elements [:vector Element]] [:claims [:vector Claim]]
        [:removed {:optional true} [:map [:elements {:optional true} [:vector :string]]
                                         [:claims {:optional true} [:vector :string]]]]])

(Kind Conflict
  "Why models cannot be combined without a person, named by id: an element or claim each side
   changed differently, a claim kept about an element — or a role's player, as any of the three
   models names it — the combination no longer holds, a claim one side stated about a subject only
   the other side restated, or a law the combined declaration violates."
  [:map [:kind [:enum :element-diverged :claim-diverged :subject-removed :subject-restated
                :law-violated]]
        [:names [:vector :string]]])

(Module report-model
  "The shared model of an area, the readings every consumer takes it through, and the two
   derivations over it that fork and merge need.

   A record of any era answers through the readings: a current record carries its model, and an
   older one's modules, load-bearing properties, composition and invariants are read AS elements
   and claims, so no reader asks which era wrote a record. What an older record could not say
   stays missing — an invariant's claim has no id — rather than invented.

   Both derivations are pure and total over MODELS, and neither reads a record's own fields: what
   a design decided about its delivery, and what a baseline observed about the area's health, are
   authored beside a model and never computed from one."
  {:child [Element Claim Model Conflict]}
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
    {:signature [:=> [:catn [:model Model]] [:map-of :string Claim]]})
  (Operation predates
    "What a record was written before, of the two things laying one model over another needs: the
     shared model, or a role naming its players. nil for a record written after both."
    {:signature [:=> [:catn [:record [:maybe :map]]] [:maybe [:enum :shared-model :role-players]]]})
  (Operation overlay
    "A design's effective model: the model it leaves when laid over its baseline's, by id. An id
     it neither states nor removes is carried unchanged, one it states as removed is dropped, a
     claim it states is the claim, and an element it states is laid over the baseline's — what
     the design says wins, and what it leaves unsaid is carried."
    {:signature [:=> [:catn [:baseline Model] [:design Model]] Model]})
  (Operation combine
    "Three effective models — the fork's base, the parent's and the child's — combined by id, each
     side read against the base. A side that alone changed an element or claim wins; both changing
     it differently, a claim kept about what the combination no longer holds, and a claim one side
     stated about a subject only the other restated are conflicts. Returns the combined model and
     every conflict; laws are not its business."
    {:signature [:=> [:catn [:base Model] [:parent Model] [:child Model]]
                 [:map [:model Model] [:conflicts [:vector Conflict]]]]}))
