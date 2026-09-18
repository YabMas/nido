(ns canvas.claims.level-test
  "The claims the level-test design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.review.record :refer [baseline-prompt design-prompt parse-stratum-reading run-figures stratum-prompt]]))

(Claim lens-can-say-no-level
  ":stratified/level reads a stratum sound, mixed, bypassed, wide or not-a-level — the last for a stratum nothing resting on it is written in that is not the one level the program is written at — and a baseline reading a stratum not-a-level is refused unless health names it."
  {:about [coordinator-report] :evidence "round"})

(Claim level-judge-questions
  "A level judge is asked first whether its stratum is a level — what the strata resting on it are written in, or whether it is the one level the program is written at — then whether the design's need is built from what it provides, whether new vocabulary is argued, and whether a part placed there belongs; it answers not-a-level, misplaced, widens or fits, the first that holds, with its reason."
  {:about [stratum-prompt parse-stratum-reading] :evidence "round"})

(Claim stratified-check-questions
  "Put to a design that carries :strata, the stratified check asks four things of its commitment and nothing of how it is cut: PLACEMENT, each part written in the vocabulary of the stratum it sits in; BARRIER, a stratum's interface grows only with a stated reason; FIT, a levelling that would make the change markedly simpler has been adopted, rejected with a reason or deferred with a ref; LEVEL, every stratum the design declares or restates is a level — something resting on it is written in its vocabulary, or it is the one level the program is written at."
  {:about [design-prompt] :evidence "round"})

(Claim figures-count-no-level
  "run-figures counts, per stratum a run's decisions read, its not-a-level readings beside its fits, widens, misplaced and failed ones."
  {:about [run-figures] :evidence "round"})

(Claim survey-reads-every-level
  "A baseline reads every stratum its model lists through the stratification lens, whose verdicts are closed — sound, mixed, bypassed, wide, not-a-level — and whose reason is required; a listed stratum with no such reading leaves the stratified derivation blocked, and any verdict but sound reaches the design as a health observation naming that stratum, which the design must route."
  {:about [baseline-prompt coordinator-report] :evidence "round"})
