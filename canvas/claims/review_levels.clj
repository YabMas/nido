(ns canvas.claims.review-levels
  "The claims the review-levels design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.review.core :refer [review-loop]]
            [canvas.review.passes :refer [review-codex review-pass review-stages]]
            [canvas.strata :refer [review-language review-programs]]))

(Claim launch-apart-from-pass
  "review-codex holds only launching a reviewer — which reviewer judges, running codex or claude, claude standing in on codex's quota, and why none could be run. The diff pass — one range's prompt and answer schema, its findings normalized and identified, and the range helpers the diff review aims with — lives in review-pass, which launches its reviewer only through run-reviewer! and takes why none could be run from run-reviewer!'s answer."
  {:about [review-codex review-pass] :evidence "round"})

(Claim review-machinery-levels
  "nido's review machinery is declared as two strata — review-language, which is review-loop, review-codex and review-claude; and review-programs, every review module but those, review-report, review-provenance, review-render, review-frontend and review-digest — with review-programs resting on review-language, and design:check refuses a call between them that no edge declares, a module in both, and a cycle."
  {:about [review-language review-programs] :evidence "law"})

(Claim engine-names-no-program
  "review-loop runs what its caller passes and names nothing of the programs above it: a program's stage list, the identity its findings are told apart by across rounds, the identity of an attempt at one, and every refusal that ends its run other than the engine's own two about the judge — a review that produced none, a reviewer that could not be run — are passed by the program's caller. The diff review's — its stage list, the warden's handle with its file-line-title fallback, that identity paired with the layer a ruling aimed at, and :stack-unmovable — live with the diff review, in review-stages and among its stage-statuses, and its caller passes them; run-loop has no default finding identity."
  {:about [review-loop review-stages review-language] :evidence "round"})
