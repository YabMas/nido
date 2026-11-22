(ns canvas.claims.project-landing
  "The claims the project-landing design commits nido to: each project declares how it lands, one
   Land gate grants the landing for every project, and a granted seat lands only through the one
   verb that runs the declared procedure.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.landing :refer [land! names-pr?]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.core.api :refer [core-api]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.platform.project :refer [landing]]
            [canvas.tasks.nido-commit-gate :refer [refusal]]
            [canvas.tasks.nido-workstream :refer [pr-land*]]))

(Claim landing-declared-per-project
  "landing answers the procedure a project's registry entry declares under :landing — :pr-auto, :pr-squash or :push-main — and :pr-auto for an entry that declares none; any other value it answers as {:error :undeclared-landing :value v}, never as the default. CLAUDE.md's landing section states the three values and the default."
  {:about [landing] :evidence "test"})

(Claim one-place-chooses-the-procedure
  "A registry entry's :landing is read only by platform-project's landing, and lane-landing is the only module that calls landing or chooses a procedure by it. land! chooses the procedure by landing alone — :pr-auto puts the PR on auto-merge or its merge queue, :pr-squash squash-merges it after marking a draft ready, :push-main runs bb nido:land in the workstream's worktree; names-pr? tells a caller only whether that landing names a PR. pr-land* lands only through land!, after the own-PR check where names-pr? answers true."
  {:about [land! names-pr? pr-land*] :evidence "test"})

(Claim seat-lands-only-through-pr-land
  "In a turn the conductor answers for, refusal refuses nido:land and nido:ship as the person's acts whatever the workstream's grants, so nido:workstream:pr:land is the only landing verb a seat may run."
  {:about [refusal] :evidence "test"})

(Claim land-gate-grants-never-ships
  "The gate's own Land control — the one a workstream's position offers, apart from any ask — is offered at position :published only on a workstream whose core state holds an open unit, and never at :slice-cleared, whose slice lands by its drain. It does one thing: it sends the person's grant of :merge as one keyed core command through core-api [person-acts-by-command]. It appends no ledger entry, lands nothing itself and enqueues no :ship envelope, so a click on it writes no :ship-submitted and creates no merge run. An open ask's Land is that ask's option, not this control [land-answers-the-escalation]."
  {:about [coordinator-work core-api] :evidence "round"})

(Claim land-answers-the-escalation
  "On a workstream whose core state holds an open unit, an open operator ask that asks one question offers its option carrying :grants [:merge] as a Land action of the ask's own, labelled Land whatever the project and apart from the gate's own Land control [land-gate-grants-never-ships], and choosing it answers that ask by that option in one core command, so the one click grants the landing and answers the ask. An ask that asks several questions offers no Land: it is answered whole by one core command carrying the grants of the options picked."
  {:about [coordinator-work core-domain] :evidence "round"})
