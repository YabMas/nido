(ns canvas.claims.stratified-design
  "The claims the stratified-design design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.report.model :refer [report-model]]))

(Claim record-machinery-stratified
  "The record machinery is declared as three strata — record vocabulary, record model, ledger — each written only in those below it, and design:check refuses a code dependency between members of two strata that no :rests-on edge declares, a module in two strata, and a :rests-on cycle."
  {:about [coordinator-report report-model record-workstream] :evidence "law"})

(Claim design-states-no-cut
  "A design appended from this change on carries no :layers — the append refuses one — and every record appended before it, :layers included, still reads under the tier it was written in."
  {:about [coordinator-report] :evidence "round"})
