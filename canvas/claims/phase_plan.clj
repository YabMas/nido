(ns canvas.claims.phase-plan
  "The claims tracking a phase plan on the running system commits nido to, declared where they
   live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.phase :refer [progress]]
            [canvas.coordinator.record.workstream :refer [append-entry! open-phase!]]))

(Claim phase-read-from-gates
  "record-phase/progress answers, for a workstream whose standing design carries :phases, that the current phase is the furthest phase of that plan a :phase-gate entry opens, the first when none does, and that it has landed when a :merged was appended after the newest :phase-gate; the number of :merged entries never decides which phase is current."
  {:about [progress] :evidence "round"})

(Claim gate-checked-on-append
  "record-workstream's three public generic appends — append-entry!, append-entry-at! and append-entry-once! — each refuse a :phase-gate outright on entry, so entry:add and every other caller of them cannot write one; open-phase! is the kind's only writer, writing through the private append-locked! under the lock it takes itself, and under that lock it resolves the design the gate cites from its own entries and refuses unless the workstream is closed :between-phases, the evidence is not blank, and record-phase/progress over that design and those entries says the current phase has landed — a :merged appended after the newest :phase-gate — and names the gate's phase as the next one; otherwise it appends the gate and reopens the workstream. So a phase's :merged is always on the ledger before the gate that opens the next phase, and a landing whose :merged the merge poll has not yet appended holds the gate shut. The next-phase decision is record-phase's alone: open-phase! repeats none of it, and record-phase requires nothing of record-workstream, so the dependency runs one way."
  {:about [append-entry! open-phase! progress] :evidence "round"})
