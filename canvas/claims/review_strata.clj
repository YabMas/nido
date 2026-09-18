(ns canvas.claims.review-strata
  "The claims the review-strata design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.review.core :refer [review-loop]]
            [canvas.review.passes :refer [review-stages]]
            [canvas.strata :refer [after-run diff-loop diff-prompts record-readings record-rounds review-engine reviewers run-display run-record unit-merge]]))

(Claim review-levels-declared
  "nido's review machinery is declared as ten strata, every edge between them a dependency the code has, and design:check refuses a call between two of them that no edge declares, a module in two of them, and a cycle."
  {:about [run-record run-display diff-prompts reviewers review-engine diff-loop record-readings record-rounds unit-merge after-run] :evidence "law"})

(Claim engine-names-no-program
  "review-loop runs only the pipeline its caller passes; the diff loop's stage list lives with the diff loop and is passed by the diff loop's caller, so the engine names nothing of a stratum above it."
  {:about [review-loop review-stages review-engine] :evidence "round"})

(Claim mixed-levels-visible
  "The two review strata that hold two levels, reviewers and diff-loop, are read :mixed with the reason, and their splits are filed rather than hidden behind an edge."
  {:about [reviewers diff-loop] :evidence "round"})
