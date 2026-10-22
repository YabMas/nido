(ns canvas.claims.landing
  "The claims the landing design commits nido to: a landing with no pull request is recorded as
   a :merged naming its commit, completed once however often its recording is run.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.tasks.nido-land :refer [nido-land]]
            [canvas.coordinator.record.standing :refer [record-standing]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]))

(Claim landing-names-what-it-landed-as
  "A :merged landing names what it landed as — the pull request a merge carried, or the commit main was fast-forwarded to, exactly one of the two — with the design whose work it carries, and a landing written naming a pull request reads as it did."
  {:about [coordinator-report] :evidence "round"})

(Claim landing-recorded-by-the-gate
  "bb nido:land:record records a landing only for the worktree's own tip, once origin's main holds that commit, and only while the workstream's governing design, as record-standing's governing-design gives it, stands. On a workstream still open it attempts once to append a :merged naming that commit and that design through `append-entry-once!` with that commit as the entry's identity — so a :merged an earlier run's interrupted append left on disk without its index row is indexed rather than written again, and none is appended when one already names that commit — and then closes the workstream, in the order landing-closes-by-plan gives both landing paths. Its outcome is one contract, which its entry point exits with: non-zero, naming its way out, when it cannot show the landing eligible or the close fails; zero once the close is made, whether or not the best-effort :merged was appended, and over a workstream already closed, where it writes nothing."
  {:about [nido-land] :evidence "round"})

(Claim interrupted-append-completed-once
  "`append-entry-once!` writes an entry of a kind only when no entry of that kind satisfying the caller's identity exists on the workstream, whether indexed or written by an interrupted append and never indexed — which a read holds at the :seq its file name carries. Under the append lock it writes nothing over a match and otherwise appends as `append-entry!` does. A file that does not parse through the read contract satisfies no identity and is left as it is."
  {:about [record-workstream] :evidence "round"})

(Claim landing-recipe-runs-the-recording
  "Nido's landing recipe for its own work, CLAUDE.md's 'Landing work — no pull requests', runs bb nido:land:record as its step after jj git push -b main. The step stops the recipe, naming why, on every outcome land:record answers non-zero — origin's main not holding the worktree's tip and the workstream's governing design not standing among them — and the recipe's way out is to repair the cause and run the step again; a :merged that could not be appended before a close that was made does not stop it."
  {:about [nido-land] :evidence "round"})

(Claim recording-a-landing-changes-no-standing
  "Recording a landing changes no design's standing: standing reads no landing entry, so a design stands identically before and after its workstream's landing is appended."
  {:about [record-standing] :evidence "round"})
