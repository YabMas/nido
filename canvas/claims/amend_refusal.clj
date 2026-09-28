(ns canvas.claims.amend-refusal
  "The claims the amend-refusal design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.review.core :refer [review-report]]
            [canvas.review.record :refer [amend-prompt design-amend-prompt refusal-prompt
                                          review-record]]))

(Claim refused-amendment-handed-back
  "When the ledger refuses a record a baseline or design amender returned, the round launches an amender over refusal-prompt — the refusal and the record as offered — and offers the record it returns to the ledger in its place. The round ends :amend-invalid, with the last refusal as :amend-error, only when two such repairs are refused as well or a repair returns no record."
  {:about [review-record refusal-prompt] :evidence "test"})

(Claim repair-stays-in-its-round
  "A repair belongs to the round whose amendment it repairs: its prompt carries no finding, the round still appends at most one record, and a repair that writes to the working copy ends the round :amend-touched-code."
  {:about [review-record refusal-prompt] :evidence "test"})

(Claim refusals-on-the-report
  "An amend phase in the run report carries :refusals — every refusal its round was repaired over, oldest first — whenever there was at least one, and no :refusals when the ledger took the amendment first time."
  {:about [review-report] :evidence "test"})

(Claim amenders-told-coupled-edits
  "The baseline amend prompt, for a baseline naming strata, and refusal-prompt for such a baseline, say that a stratum read as anything but :sound is named under :about by a :health observation in the same record; the design amend prompt and refusal-prompt, for a design in the shared model, say that removals go under :model :removed and never at the record's top level."
  {:about [amend-prompt design-amend-prompt refusal-prompt] :evidence "test"})
