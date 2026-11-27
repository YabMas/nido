(ns canvas.claims.retire-ledgers
  "The claims the retire-legacy-ledgers design (workstream ws-20261126-53db03) commits nido to:
   workstreams live only in the core, so nothing reads their legacy files. Each Claim's name is
   the claim's id, its docstring the statement, and its :about the elements it is about — the
   same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [lane-pipeline]]
            [canvas.coordinator.record.workstream :refer [record-workstream delete!]]
            [canvas.coordinator.view.workstreams :refer [ws-source]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.core.store :refer [core-store]]
            [canvas.review.core :refer [payload]]))

(Claim no-legacy-reader
  "No namespace in src reads, writes or names a workstream's legacy ledger files (workstream.edn, entries/, entries.meta/), nido:core:install and nido:core:upgrade neither read nor write an import marker, and core-domain decides no :import-ledger command; the :workstream-created events the retired import committed keep their imported mark, which the fold does not read."
  {:about [core-domain record-workstream] :evidence "round"})

(Claim import-machinery-removed
  "No namespace nido.coordinator.record.legacy-ledger exists, bb.edn defines no nido:core:import-ledgers task, nido.coordinator.record.state defines no workstream-edn-path, and canvas/ declares no element for the import or for that helper."
  {:about [record-workstream] :evidence "round"})

(Claim fold-and-store-unchanged
  "This change leaves core-domain's fold and core-store as they were."
  {:about [core-domain core-store] :evidence "round"})

(Claim discard-removes-the-directory
  "delete! discards the workstream in the core, then removes its directory and everything in it."
  {:about [delete!] :evidence "test"})

(Claim no-analysis-position
  "The board has no position, origin or band for review-loop analyses: lane-pipeline has no position of its own for an analysis ledger, ws-source answers no origin of its own for a review run, and an analysis fire's payload names no :adapter."
  {:about [lane-pipeline ws-source payload] :evidence "round"})
