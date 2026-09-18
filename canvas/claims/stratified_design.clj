(ns canvas.claims.stratified-design
  "The claims the stratified-design design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.coordinator.report :refer [coordinator-report proceeds?]]
            [canvas.coordinator.report.model :refer [report-model]]
            [canvas.review.core :refer [review-prompts]]
            [canvas.review.passes :refer [read-stance]]
            [canvas.review.record :refer [review-record]]
            [canvas.session.briefing :refer [session-launcher]]))

(Claim stance-names-stratified-design
  "The stance read-stance delivers names Abelson & Sussman's 'Lisp: A Language for Stratified Design' and Normand's Grokking Simplicity as the third stage after Parnas and Ousterhout — how boundaries stack, each level a vocabulary the level above is written in — and holds that stage to Ousterhout's test that a level provides a different abstraction from the one below it."
  {:about [read-stance] :evidence "round"})

(Claim stance-read-whole
  "Every judge the stance reaches reads it whole: no stance nido ships is longer than read-stance's cap, and a stance grown past it fails nido's tests instead of reaching a judge truncated."
  {:about [read-stance] :evidence "round"})

(Claim record-machinery-stratified
  "The record machinery is declared as three strata — record vocabulary, record model, ledger — each written only in those below it, and design:check refuses a code dependency between members of two strata that no :rests-on edge declares, a module in two strata, and a :rests-on cycle."
  {:about [coordinator-report report-model record-workstream] :evidence "law"})

(Claim stratum-is-a-model-sort
  "A model element may be a stratum, stating what it provides as its :interface. In a project that declares a design a stratum subject resolves to a declared Stratum before any judge launches; in one that declares none the record gives it an id of its own, as it does every element."
  {:about [report-model review-record] :evidence "round"})

(Claim design-states-no-cut
  "A design appended from this change on carries no :layers — the append refuses one — and every record appended before it, :layers included, still reads under the tier it was written in."
  {:about [coordinator-report] :evidence "round"})

(Claim stratified-check-gates
  "proceeds? holds back a design whose round found the stratified check broken, as it does for every check but decomposable; a recorded decision whose only broken check is decomposable still proceeds."
  {:about [proceeds? review-record] :evidence "round"})

(Claim records-judged-in-their-era
  "Which yardstick a round holds a record to is read from the record: the stratified tier is selected by :strata, which the write contracts require from this change on and no earlier record carries — on a design the strata the change touches, on a baseline the declared strata its bound reaches, each one its model lists, and on either an empty vector where there are none. A record in that tier is judged by the stratified check or derivation; a design without :strata, whether it carries :layers or not, is judged by decomposable exactly as today, and a baseline without :strata against the four derivations it is judged against today."
  {:about [coordinator-report review-record] :evidence "round"})

(Claim doctrine-cuts-from-strata
  "The doctrine a session is briefed with has /stack draw a stack's default cut at stack time from the strata the design names, bottom-up, plus the three boundaries that are not levels; it never has a design state its layers."
  {:about [session-launcher] :evidence "round"})

(Claim warden-judges-no-cut
  "Against a design that states no cut, the warden raises no finding about which layers the design did or did not name; against an earlier design carrying :layers it judges them as it does today."
  {:about [review-prompts] :evidence "round"})
