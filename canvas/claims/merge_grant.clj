(ns canvas.claims.merge-grant
  "The claims the merge-grant design commits nido to: a person can hand a seat the landing of its
   workstream's own pull request, once, and the seat lands it instead of escalating.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.landing :refer [land!]]
            [canvas.coordinator.record.workstream :refer [own-pr record-workstream]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.core.api :refer [core-api]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.tasks.nido-commit-gate :refer [nido-commit-gate refusal]]
            [canvas.tasks.nido-workstream :refer [pr-land*]]))

(Claim merge-grant-lands-own-pr
  "In a unit turn the core runs, the commit gate's refusal admits nido:workstream:pr:land when the turn's workstream holds an unspent :merge grant in its core state, read through core-api, run where the task takes that workstream for its own, when the command names no PR or names one own-pr answers for that workstream; refusal reads no landing procedure — whether a landing needs a PR is pr-land*'s to decide [pr-land-lands-only-own]. Without that grant, when the core state cannot be read, in a conductor turn, run in another workstream's directory or one the gate cannot read, or naming a PR own-pr does not answer, refusal refuses it as the person's act. While that grant is unspent, refusal also refuses nido:workstream:ref:add in a turn the core runs. No ledger entry is read for the grant."
  {:about [nido-commit-gate core-api record-workstream] :evidence "round"})

(Claim pr-land-lands-only-own
  "pr-land* lands, through land!, only for the workstream of the session it runs in, and where names-pr? answers true only a PR own-pr answers for that workstream; a session on no workstream, or where a PR is required one that workstream has no :github ref for — another number, another repository — is refused, and nothing reaches land!."
  {:about [pr-land* own-pr land!] :evidence "test"})

(Claim raw-merge-never-granted
  "No grant admits a :gh-pr act: in a turn the core runs, the commit gate's refusal refuses a gh pr merge, a gh or gh pr verb the gate cannot read, and a gh api write that may merge a pull request whatever grants the workstream's core state holds, and names nido:workstream:pr:land when that state, read through core-api, holds an unspent :merge grant."
  {:about [nido-commit-gate core-api] :evidence "round"})

(Claim grant-spent-by-its-landing
  "What may be granted stays one closed set in the report vocabulary, which both the option and the answer schema enumerate; whether a grant is held, and when it is spent, is core state decided by core-domain from core events, not by the report vocabulary and not by reading ledger entries. A :merge grant is spent when the core records a unit of its workstream merged; an :intent grant is spent by the keyed core command the intent verb sends through core-api when it appends the intent that grant admitted."
  {:about [coordinator-report core-domain] :evidence "round"})

(Claim grants-from-the-persons-words
  "A grant is given only by a person's act — their pick of an option carrying it, :grants they pass beside their words, or the gate's Land — and reaches coordination only as a core command [person-acts-by-command], which core-domain admits into the workstream's state. No ledger entry and no text is read for a grant."
  {:about [coordinator-work core-domain core-api] :evidence "round"})

(Claim closing-and-approval-stay-the-persons
  "Only :intent and :merge are grantable: a :grants naming any other kind is refused on write, and under any grant refusal still refuses a close, a design approval, an append of a :merged, :closed or :grant entry, and a landing record."
  {:about [coordinator-report refusal] :evidence "test"})
