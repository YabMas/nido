(ns canvas.claims.phase-plan
  "The claims tracking a phase plan on the running system commits nido to, declared where they
   live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [next-action of]]
            [canvas.coordinator.lane.github :refer [poll-and-react!]]
            [canvas.coordinator.lane.reentry :refer [lane-reentry]]
            [canvas.coordinator.lane.work :refer [advance! file! resolve!]]
            [canvas.coordinator.record.phase :refer [landing-outcome progress]]
            [canvas.coordinator.record.workstream :refer [append-entry! open-phase! plan-design reopen!]]
            [canvas.coordinator.view.workstreams :refer [workstream-rows]]
            [canvas.coordinator.work :refer [advance-phase! board-bands restore!]]
            [canvas.review.passes :refer [build-prompt]]
            [canvas.tasks.nido-land :refer [record]]
            [canvas.ui.surface :refer [ui-tui ui-views]]))

(Claim phase-read-from-gates
  "record-phase/progress answers, for a workstream whose standing design carries :phases, that the current phase is the furthest phase of that plan a :phase-gate entry opens, the first when none does, and that it has landed when a :merged was appended after the newest :phase-gate; the number of :merged entries never decides which phase is current."
  {:about [progress] :evidence "round"})

(Claim gate-checked-on-append
  "record-workstream's three public generic appends — append-entry!, append-entry-at! and append-entry-once! — each refuse a :phase-gate outright on entry, so entry:add and every other caller of them cannot write one; open-phase! is the kind's only writer, writing through the private append-locked! under the lock it takes itself, and under that lock it refuses unless the workstream is closed :between-phases, the evidence is not blank, and record-phase/progress over the design that close names says the gate opens the phase immediately after the current one; otherwise it appends the gate and reopens the workstream. Whether the landing's :merged is on the ledger is not asked: the close is the landing's record for the gate."
  {:about [append-entry! open-phase! progress] :evidence "round"})

(Claim landing-closes-by-plan
  "Both landing paths close the workstream with the outcome record-phase/landing-outcome gives over the design the landing is attributed to — :between-phases while that plan has a phase after the one that landed, :done otherwise — and a :between-phases close names that design. Each path appends its :merged before it closes, never after: the append is attempted once and is best-effort, so a failed one leaves no :merged and the close still happens, and nothing waits on it or retries it. Because open-phase! requires that close, every :merged a landing writes precedes the :phase-gate that opens the next phase."
  {:about [poll-and-react! record landing-outcome] :evidence "round"})

(Claim advance-opens-next-phase
  "lane-phase/advance! is the only operation that opens a phase: it is the only caller of record-workstream's open-phase!, the one writer of a :phase-gate; it refuses a workstream not closed :between-phases and blank evidence, and otherwise has open-phase! append a :phase-gate for the next phase of the design the close names and reopen the workstream. restore! refuses a workstream closed :between-phases. A findings round filed on one keeps the design its close named on the round's tracker, and when lane-findings/resolve! resolves the round's last open item with no fix published since the round, it closes the workstream :between-phases under that design again; a fix published since closes it so when it lands."
  {:about [advance! open-phase! reopen! file! resolve! restore!] :evidence "round"})

(Claim awaiting-gate-owed-by-person
  "lane-pipeline places a workstream closed :between-phases at :awaiting-gate, whose next action is a person's :assert-gate, and there is no :phase-landed position."
  {:about [of next-action] :evidence "round"})

(Claim trail-restarts-at-gate
  "A trail record appended before the newest :phase-gate or :findings entry does not stand. A workstream that has just opened a phase owes that phase's implementation again, and one reopened by a findings round, once the round's items resolve, is placed only by what was written since the round was filed — :published by its fix's own PR, and with no implementation since, owing :implement — so the route back to implementation that :phase-landed gave a reopened workstream survives its removal. That route is for a round on a workstream with no phase still to come: one whose round was filed between phases is closed :between-phases by resolve! before the fold can read it as owing implementation, so it reads :awaiting-gate."
  {:about [lane-reentry] :evidence "round"})

(Claim awaiting-gate-on-the-board
  "board-bands carries an :awaiting-gate band holding every workstream closed :between-phases, whether or not it holds a live session, and every row of a workstream whose standing design is phased carries its progress."
  {:about [board-bands workstream-rows] :evidence "round"})

(Claim surfaces-show-progress
  "The dashboard and the TUI both show a phased row's current phase out of its total and, while it awaits a gate, that gate's criterion; the dashboard offers asserting the gate, with a field for its evidence, on an :awaiting-gate row."
  {:about [ui-views ui-tui] :evidence "round"})

(Claim verdict-told-current-phase
  "The verdict prompt for a phased design names the current phase and whether it is the last, and asks that an :on-completion claim be judged as required on the last phase and excused before it."
  {:about [build-prompt] :evidence "round"})

(Claim plan-governed-by-its-close
  "record-workstream/plan-design answers which design's plan governs a workstream: the one its :between-phases close names while it waits on a gate; once a gate opens, the one that :phase-gate cites, until a :design is appended after it; and its newest design when no gate or close applies. The ledger refuses to append a :design to a workstream closed :between-phases, so no design is written while it waits, and a design written before the gate governs only if the gate cites it. advance!, the rows the board draws, the facade's gate and the verdict prompt read phase progress over that design and no other, so the gate a person is shown is the gate that opens, and the phase it opens is the phase every reader then reports as current."
  {:about [plan-design advance! workstream-rows advance-phase! build-prompt append-entry!] :evidence "round"})
