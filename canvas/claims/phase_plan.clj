(ns canvas.claims.phase-plan
  "The claims tracking a phase plan on the running system commits nido to, declared where they
   live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [next-action of]]
            [canvas.coordinator.lane.github :refer [poll-and-react!]]
            [canvas.coordinator.lane.reentry :refer [lane-reentry]]
            [canvas.coordinator.record.phase :refer [landing-outcome progress]]
            [canvas.coordinator.record.workstream :refer [append-entry! open-phase!]]
            [canvas.review.passes :refer [build-prompt]]
            [canvas.tasks.nido-land :refer [record]]))

(Claim phase-read-from-gates
  "record-phase/progress answers, for a workstream whose standing design carries :phases, that the current phase is the furthest phase of that plan a :phase-gate entry opens, the first when none does, and that it has landed when a :merged was appended after the newest :phase-gate; the number of :merged entries never decides which phase is current."
  {:about [progress] :evidence "round"})

(Claim gate-checked-on-append
  "record-workstream's three public generic appends — append-entry!, append-entry-at! and append-entry-once! — each refuse a :phase-gate outright on entry, so entry:add and every other caller of them cannot write one; open-phase! is the kind's only writer, writing through the private append-locked! under the lock it takes itself, and under that lock it resolves the design the gate cites from its own entries and refuses unless the workstream is closed :between-phases, the evidence is not blank, and record-phase/progress over that design and those entries says the current phase has landed — a :merged appended after the newest :phase-gate — and names the gate's phase as the next one; otherwise it appends the gate and reopens the workstream. So a phase's :merged is always on the ledger before the gate that opens the next phase, and a landing whose :merged the merge poll has not yet appended holds the gate shut. The next-phase decision is record-phase's alone: open-phase! repeats none of it, and record-phase requires nothing of record-workstream, so the dependency runs one way."
  {:about [append-entry! open-phase! progress] :evidence "round"})

(Claim verdict-told-current-phase
  "The verdict prompt for a phased design names the current phase and whether it is the last, and asks that an :on-completion claim be judged as required on the last phase and excused before it."
  {:about [build-prompt] :evidence "round"})

(Claim landing-closes-by-plan
  "Both landing paths close the workstream with the outcome record-phase/landing-outcome gives: :between-phases while the plan has a phase after the one that landed, :done otherwise."
  {:about [poll-and-react! record landing-outcome] :evidence "round"})

(Claim awaiting-gate-owed-by-person
  "lane-pipeline places a workstream closed :between-phases at :awaiting-gate, whose next action is a person's :assert-gate, and there is no :phase-landed position."
  {:about [of next-action] :evidence "round"})

(Claim trail-restarts-at-gate
  "A trail record appended before the newest :phase-gate or :findings entry does not stand. A workstream that has just opened a phase owes that phase's implementation again, and one reopened by a findings round, once the round's items resolve, is placed only by what was written since the round was filed — :published by its fix's own PR, and with no implementation since, owing :implement — so the route back to implementation that :phase-landed gave a reopened workstream survives its removal. That route is for a round on a workstream with no phase still to come: one whose round was filed between phases is closed :between-phases by resolve! before the fold can read it as owing implementation, so it reads :awaiting-gate."
  {:about [lane-reentry] :evidence "round"})
