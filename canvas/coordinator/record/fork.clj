(ns canvas.coordinator.record.fork
  "Self-spec: `nido.coordinator.record.fork` — a unit forked into a child workstream."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream :refer [Workstream]]
            [canvas.coordinator.report.model :as model]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Lineage
  "Where a child unit came from: the parent workstream, and the baseline and design on its ledger
   the child was derived from. Read off the child's `:fork` entry, which is the only place it is
   written."
  [:map [:parent WorkstreamId] [:baseline-seq :int] [:design-seq :int]])

(Module record-fork
  "A fork is a CHILD WORKSTREAM, never a second unit on the parent's.

   That is the whole of what keeps the parent standing as it stood. Every reader of a workstream
   takes its newest design, so a child unit on the parent's ledger would become the parent's
   design; on its own ledger it is nobody else's. Forking therefore writes only to the child: a
   `:fork` entry citing the parent's baseline and design by workstream and seq, and a baseline
   derived from those two. The parent's ledger and record are untouched.

   The citation crosses workstreams, which no other citation does. It resolves against the
   parent's entries, which are immutable, so what it names cannot change under the child."
  {:child [Lineage]}
  (Operation lineage
    "The child's lineage, from its `:fork` entry, or nil for a workstream that was not forked."
    {:signature [:=> [:catn [:w Workstream]] [:maybe Lineage]]})
  (Operation parent-records
    "The baseline and design a lineage names, read from the parent's ledger by seq."
    {:signature [:=> [:catn [:project ProjectName] [:lineage Lineage]] :map]
     :delegates [workstream/entry-at-seq]})
  (Operation fork!
    "Mint a child workstream from a parent whose design stands: append its `:fork` entry and the
     baseline derived from the parent's baseline and design. Refuses a parent whose cited records
     predate the shared model, naming them. Writes nothing on the parent."
    {:signature [:=> [:catn [:project ProjectName] [:parent WorkstreamId] [:opts :map]] Workstream]
     :delegates [workstream/create! workstream/append-entry! parent-records model/overlay]}))
