(ns canvas.strata
  "Nido's declared strata: the levels of an area, at module grain, where they have been read.

   Bands say which packages may reach which. Strata say, inside and across them, which modules
   provide a vocabulary and which are written in it. They are declared area by area as a
   workstream reads the levels, never as a sweep, so most modules belong to none yet."
  (:require [fukan.common.vocab.code.stratum :refer [Stratum]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.report.model :refer [report-model]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.review.core :refer [review-analysis review-cache review-conformance review-loop
                                        review-prompts review-reconcile review-retreat]]
            [canvas.review.merge :refer [review-merge]]
            [canvas.review.passes :refer [review-claude review-codex review-layers review-pass
                                          review-stages review-verdict]]
            [canvas.review.record :refer [review-record]]
            [canvas.review.settled :refer [review-settled]]))

;; ── the record machinery, floor first ────────────────────────────────────────

(Stratum record-vocabulary
  "Typed ledger records: what each kind may carry when it is written, and the era an older one is
   read in. A caller validates or parses a record and never names a schema tier."
  {:provided-by [coordinator-report]})

(Stratum record-model
  "Any record read as one model of elements and claims, whatever era it was written in; models
   overlaid and combined by id, and a combination is a model again."
  {:provided-by [report-model]
   :rests-on    [record-vocabulary]})

(Stratum ledger
  "A workstream's entries, appended only when the cross-record checks hold — the baseline a design
   cites, every health observation routed, a seam's phase planned — and read back by kind."
  {:provided-by [record-workstream]
   :rests-on    [record-model record-vocabulary]})

;; ── the review machinery, floor first ────────────────────────────────────────
;;
;; Two levels, and only two: the language the review programs are written in, and the programs.
;; What a program is made of — its prompts, its steps, its readings — sits at the program's level,
;; however many modules it takes; a module boundary is not a level.
;; review-report, review-provenance, review-render and review-frontend belong to none, because a
;; run's record and its display are an output sink, and a sink is not a level: the report folds the
;; programs' own phases and statuses, so it is written in their vocabulary, not they in its.
;; review-digest belongs to none, because a helper everything calls is not one either.

(Stratum review-language
  "What every review program is written in: rounds of a pipeline run to a terminal status, its
   findings told apart across rounds by the identity the program's caller passes, and a read-only
   reviewer launched over a prompt and an answer schema — codex, or claude standing in when codex
   is out of quota — with why none could be run.

   Two primitives side by side, neither written in the other, and one level because the programs
   are written in both: a record round runs its rounds on the engine and judges through the
   launch, and the diff review is run by the engine and judges through the launch."
  {:provided-by [review-loop review-codex review-claude]})

(Stratum review-programs
  "The review programs a task or the daemon runs: the diff review over a stack, the baseline and
   design rounds, a forked unit's design merged back into its parent's, and a finished or orphaned
   run settled. Nothing else in the review machinery is written in them."
  {:provided-by [review-stages review-layers review-cache review-conformance review-prompts
                 review-pass review-verdict review-record review-settled review-retreat
                 review-merge review-analysis review-reconcile]
   :rests-on    [review-language ledger record-model record-vocabulary]})
