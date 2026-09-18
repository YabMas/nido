(ns canvas.strata
  "Nido's declared strata: the levels of an area, at module grain, where they have been read.

   Bands say which packages may reach which. Strata say, inside and across them, which modules
   provide a vocabulary and which are written in it. They are declared area by area as a
   workstream reads the levels, never as a sweep, so most modules belong to none yet."
  (:require [fukan.common.vocab.code.stratum :refer [Stratum]]
            [canvas.coordinator.report :refer [coordinator-report]]
            [canvas.coordinator.report.model :refer [report-model]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]))

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
