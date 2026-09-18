(ns canvas.claims.record-round-analysis
  "The claims the record-round-analysis design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.review.record :refer [review-record run-figures]]))

(Claim rounds-name-their-run
  "Every baseline review and design decision a record round appends names the run that appended it, and a baseline review appended by a design run's re-survey also names that design run under :within-run, which the design run hands the nested loop when it launches it; one appended before this reads as before, naming none."
  {:about [coordinator-report review-record] :evidence "round"})

(Claim figures-derived-not-stored
  "For a run whose entries name it, the figures per derived check — rounds broken, rounds broken alone, broken at the run's last answer — and per baseline derivation are derived from those entries on every read, count a check a proceeding round broke, and are written to no record. A design run's re-survey rounds are read as that run's by the design run their entries name under :within-run, never by their run id's prefix or their position on the ledger."
  {:about [run-figures] :evidence "round"})
