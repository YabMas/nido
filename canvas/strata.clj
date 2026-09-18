(ns canvas.strata
  "Nido's declared strata: the levels of an area, at module grain, where they have been read.

   Bands say which packages may reach which. Strata say, inside and across them, which modules
   provide a vocabulary and which are written in it. They are declared area by area as a
   workstream reads the levels, never as a sweep, so most modules belong to none yet."
  (:require [fukan.common.vocab.code.stratum :refer [Stratum]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.report.model :refer [report-model]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.review.core :refer [review-analysis review-cache review-conformance review-frontend
                                        review-loop review-prompts review-provenance review-reconcile
                                        review-render review-report review-retreat]]
            [canvas.review.merge :refer [review-merge]]
            [canvas.review.passes :refer [review-claude review-codex review-layers review-stages
                                          review-verdict]]
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
;; review-digest belongs to none: it hashes ids for five of these, and a level is a vocabulary
;; something is written in, not a helper everything calls. Two strata hold two levels today —
;; reviewers, because codex also carries the diff pass, and diff-loop, because stages also carries
;; the helpers that locate any run's context — and are read so rather than hidden by an edge.

(Stratum run-record
  "What a review run records about itself: its events folded into one report, and the revision of
   the machinery that ran it."
  {:provided-by [review-report review-provenance]})

(Stratum run-display
  "A run's report as it happens, on screen and on disk: the event sink every loop emits to, the live
   frame around a run, and the frames and lines a report is rendered as."
  {:provided-by [review-render review-frontend]
   :rests-on    [run-record]})

(Stratum diff-prompts
  "The text of every diff-review pass — reviewer, warden, composition, fixer — given the design it is
   judged against and the targets it reads."
  {:provided-by [review-prompts]
   :rests-on    [record-model record-vocabulary]})

(Stratum reviewers
  "A read-only reviewer launched over a prompt and an answer schema — codex, or claude standing in
   when codex is out of quota — and the diff pass's findings parsed from it."
  {:provided-by [review-claude review-codex]
   :rests-on    [diff-prompts]})

(Stratum review-engine
  "Rounds of a pipeline run to a terminal status: when a run has converged, stalled, escalated or
   must stop, and how a round's findings are told apart from the last round's."
  {:provided-by [review-loop]})

(Stratum diff-loop
  "The diff review over a stack — review, warden, reshape and fix — with what a target already
   settled, the declared design's violations as findings and the verdict on a finished run; and the
   helpers that locate any run's project, stance and cited records."
  {:provided-by [review-stages review-layers review-cache review-conformance review-verdict]
   :rests-on    [reviewers diff-prompts ledger record-model record-vocabulary]})

(Stratum record-readings
  "Readings a record round is written in: which claims are settled across ledgers and trees, and
   what an amended record gave up."
  {:provided-by [review-settled review-retreat]
   :rests-on    [ledger record-model]})

(Stratum record-rounds
  "The baseline and design rounds: a record judged against the code by a reviewer, its levels read by
   their own judges, amended between rounds, and what a run's rounds did."
  {:provided-by [review-record]
   :rests-on    [record-readings review-engine reviewers diff-loop ledger record-model record-vocabulary]})

(Stratum unit-merge
  "A child unit's design combined back into its parent's by id, with every conflict named."
  {:provided-by [review-merge]
   :rests-on    [ledger record-model]})

(Stratum after-run
  "What follows a run's end: a finished run queued for analysis, and a run whose process died settled
   from its report."
  {:provided-by [review-analysis review-reconcile]
   :rests-on    [run-display run-record]})
