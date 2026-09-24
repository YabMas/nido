(ns canvas.coordinator.record.phase
  "Self-spec: `nido.coordinator.record.phase` — where a phased workstream is in its plan."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Kind PhaseProgress
  "Where a workstream is in its standing design's phase plan: the current phase, how many there
   are, whether the current one has landed, the current phase's exit — the gate that opens its
   successor — and the next phase. nil for a workstream whose standing design has no plan."
  [:map [:current :int]
        [:of :int]
        [:landed? :boolean]
        [:exit [:maybe :map]]
        [:next [:maybe :map]]])

(Module record-phase
  "Which phase of its plan a workstream is in, read off the entries it is handed.

   DERIVED, never stored, for the reason standing is: the current phase changes exactly when an
   entry is appended, so a stored copy would be wrong at the moment it mattered. The current
   phase is the furthest phase of the standing design's plan that a :phase-gate entry opens —
   never a count of :merged entries, which a findings round or a landing recorded twice also
   produce."
  {:child [PhaseProgress]}
  (Operation progress
    "Where a workstream is in a phased design's plan, given the design and the workstream's
     entries, or nil when the design has none. Reads no ledger and resolves no design: the caller
     supplies both, which is what lets the ledger's own gate check ask it without a cycle."
    {:signature [:=> [:catn [:design [:maybe :map]] [:entries [:sequential :map]]] [:maybe PhaseProgress]]})
  (Operation landing-outcome
    "The outcome a landing closes a workstream with, given the same: :between-phases when the
     plan has a phase after the one that just landed, :done otherwise."
    {:signature [:=> [:catn [:design [:maybe :map]] [:entries [:sequential :map]]] :keyword]
     :delegates [progress]}))
