(ns canvas.claims.record-round-analysis
  "The claims the record-round-analysis design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.review.core :refer [enqueue! payload worth-analysing?]]
            [canvas.review.record :refer [review-record run-figures]]
            [canvas.tasks.nido-review :refer [baseline-cmd* design-cmd*]]))

(Claim record-runs-queued
  "A baseline or design loop that reaches a terminal status passes its run to enqueue! once, keyed by its run id, as a finished diff review does, and the run carries how many of its rounds launched a judge. worth-analysing? refuses, besides what it refuses today, a record run whose count is zero, whatever status it ended in. It never tests the status for this: which outcomes are read before a judge launches is review-record's to decide, and a status such as :premise-unverified, :subjects-undeclared or :declaration-unreadable is reached both before any judge and in a later round, after a judge has judged and an amender amended."
  {:about [baseline-cmd* design-cmd* worth-analysing? enqueue! review-record] :evidence "round"})

(Claim headline-rendered-in-code
  "Every envelope carries a headline payload renders for its kind of run, and the :review-analysis template renders that line rather than naming a diff run's fields one by one; a diff run's envelope still carries every field it carries today, so a template not yet changed renders a diff run as before."
  {:about [payload] :evidence "round"})

(Claim rounds-name-their-run
  "Every baseline review and design decision a record round appends names the run that appended it, and a baseline review appended by a design run's re-survey also names that design run under :within-run, which the design run hands the nested loop when it launches it; one appended before this reads as before, naming none."
  {:about [coordinator-report review-record] :evidence "round"})

(Claim figures-derived-not-stored
  "For a run whose entries name it, the figures per derived check — rounds broken, rounds broken alone, broken at the run's last answer — and per baseline derivation are derived from those entries on every read, count a check a proceeding round broke, and are written to no record. A design run's re-survey rounds are read as that run's by the design run their entries name under :within-run, never by their run id's prefix or their position on the ledger."
  {:about [run-figures] :evidence "round"})
