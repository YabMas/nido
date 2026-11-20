(ns canvas.claims.merge-grant
  "The claims the merge-grant design commits nido to: a person can hand a seat the landing of its
   workstream's own pull request, once, and the seat lands it instead of escalating.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor refusal tell! grant-landing!]]
            [canvas.coordinator.lane.landing :refer [land!]]
            [canvas.coordinator.record.workstream :refer [own-pr unspent-grants]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.report.ask :refer [report-ask]]
            [canvas.session.commit-gate :refer [acts]]
            [canvas.tasks.nido-workstream :refer [pr-land* blocker-answer*]]
            [canvas.ui.side-chat :refer [ui-side-chat]]))

(Claim merge-grant-lands-own-pr
  "In a turn the conductor answers for, refusal admits nido:workstream:pr:land from a seat whose workstream holds an unspent :merge grant, run where the task takes that workstream for its own, when the command names no PR or names one own-pr answers for that workstream; refusal reads no landing procedure — whether a landing needs a PR is pr-land*'s to decide [pr-land-lands-only-own]. Without an unspent :merge grant, from the conductor, run in another workstream's directory or one the gate cannot read, or naming a PR own-pr does not answer, refusal refuses it as the person's act. While that grant is unspent, refusal also refuses nido:workstream:ref:add in a turn the conductor answers for. This claim is the one statement of which pr:land a :merge grant admits: person-acts-stay-the-persons names it by id rather than restating it."
  {:about [refusal own-pr] :evidence "test"})

(Claim pr-land-lands-only-own
  "pr-land* lands, through land!, only for the workstream of the session it runs in, and where names-pr? answers true only a PR own-pr answers for that workstream; a session on no workstream, or where a PR is required one that workstream has no :github ref for — another number, another repository — is refused, and nothing reaches land!."
  {:about [pr-land* own-pr land!] :evidence "test"})

(Claim raw-merge-never-granted
  "No grant admits a :gh-pr act: in a turn the conductor answers for, a gh pr merge, a gh or gh pr verb the gate cannot read, and a gh api write that may merge a pull request are refused whatever the workstream's grants, and the refusal names nido:workstream:pr:land when a :merge grant is unspent."
  {:about [refusal acts] :evidence "test"})

(Claim grant-spent-by-its-landing
  "What may be granted stays one closed set in the report vocabulary, which both the option and the answer schema enumerate; which ledger kind's append spends each grant — :intent by an :intent, :merge by a :merged — is decided where the workstream record reads grants (unspent-grants), not in the report vocabulary; a grant is unspent while no entry of its spending kind follows the record that gave it."
  {:about [coordinator-report unspent-grants] :evidence "test"})

(Claim grants-from-the-persons-words
  "A grant is given by the person's pick of an option carrying it, by blocker-answer* passed :grants beside the person's words, by tell! passed :grants, which appends a :grant entry holding the words before it posts the hand-back, or by grant-landing!, the gate's Land at :published, which appends a :merge :grant entry. unspent-grants reads a workstream's unspent grants from :blocker-answered and :grant entries that are the person's acts, and from nothing else; refusal and the pipeline read grants only through it. No text is read for a grant."
  {:about [blocker-answer* tell! grant-landing! unspent-grants lane-conductor] :evidence "test"})

(Claim closing-and-approval-stay-the-persons
  "Only :intent and :merge are grantable: a :grants naming any other kind is refused on write, and under any grant refusal still refuses a close, a design approval, an append of a :merged, :closed or :grant entry, and a landing record."
  {:about [coordinator-report refusal] :evidence "test"})

(Claim landing-told-under-grant
  "The conductor's brief tells it to offer a landing as an option carrying :grants [:merge] and that, while one is unspent, nido instructs the seat to land, so it neither escalates nor instructs that landing; the card says beside such an option that the seat lands the workstream through nido:workstream:pr:land, by the procedure its project declares; the chat is told to pass :grants merge when the person's word is to land; and the /land skill lands through nido:workstream:pr:land, escalating only when it refuses."
  {:about [lane-conductor report-ask ui-side-chat] :evidence "round"})
