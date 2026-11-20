(ns canvas.claims.project-landing
  "The claims the project-landing design commits nido to: each project declares how it lands, one
   Land gate grants the landing for every project, and a granted seat lands only through the one
   verb that runs the declared procedure.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor refusal grant-landing! instruct-landing!]]
            [canvas.coordinator.lane.drive :refer [lane-pipeline reap-landed! reap-stranded!]]
            [canvas.coordinator.lane.landing :refer [land! names-pr?]]
            [canvas.coordinator.record.workstream :refer [unspent-grants]]
            [canvas.coordinator.work :refer [gate-actions resolve-gate!]]
            [canvas.platform.project :refer [landing]]
            [canvas.strata :refer [workstream-position]]
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
  "Land is offered at position :published only on an activated workstream, and never at :slice-cleared, whose slice lands by its drain; there resolve-gate! does one thing: grant-landing!, which appends the person's :grant [:merge] only while the workstream is activated and at :published and otherwise writes nothing. It lands nothing itself and enqueues no :ship envelope, so a Land click writes no :ship-submitted and creates no merge run."
  {:about [gate-actions resolve-gate! grant-landing!] :evidence "test"})

(Claim land-answers-the-escalation
  "On an activated workstream, an open blocker that asks one question (its :options) offers its option carrying :grants [:merge] as the Land action, labelled Land as it is at :published, whatever the project, and resolve-gate! records that blocker's :blocker-answered by that option, so the one click grants the landing and answers the blocker. A blocker that asks several questions (:questions) offers no Land: it is answered whole through :answer-questions, whose :blocker-answered carries the grants of the options picked, and instruct-landing! dispatches that grant as it does any other."
  {:about [gate-actions resolve-gate!] :evidence "test"})

(Claim landing-instructed-under-grant
  "instruct-landing! posts each seat held by an activated workstream whose unspent-grants hold :merge one instruction per grant, keyed on the :seq unspent-grants answers for it, to run bb nido:workstream:pr:land — where names-pr? answers true, only once the workstream records a PR own-pr answers for, and naming it — and never /drive-home or nido ship; the daemon runs it every pass, so a grant is instructed however it was recorded. It is the one dispatcher of that landing: neither the conductor's brief nor refusal's answer to a conductor tells it to instruct a seat to land under a grant, and refusal's answer to a seat names nido:workstream:pr:land without requiring a PR."
  {:about [instruct-landing! refusal unspent-grants lane-conductor] :evidence "test"})

(Claim granted-landing-owes-no-gate
  "A workstream at :published whose unspent-grants hold :merge reads :land-granted — stage :land, mode :working-copy — so its next move is not a person's and no gate offers Land on it."
  {:about [lane-pipeline unspent-grants workstream-position] :evidence "test"})

(Claim landed-merge-runs-settle
  "reap-landed! settles to :done every merge run not yet terminal whose workstream holds a :merged or :closed entry written after the run was created, and touches no other run; the daemon runs it every pass."
  {:about [reap-landed!] :evidence "test"})

(Claim stranded-merge-runs-reaped
  "reap-stranded! settles to :done each merge run it is given that is not yet terminal, and touches no other; the daemon gives it, at each start, the 2026-11-19-fukan-merge-* runs, so none of those is left non-terminal whether or not reap-landed! reaches it."
  {:about [reap-stranded!] :evidence "round"})
