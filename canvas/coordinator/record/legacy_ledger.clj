(ns canvas.coordinator.record.legacy-ledger
  "Self-spec: `nido.coordinator.record.legacy-ledger` — the ledger files, read once into the core."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.core.client :as client]
            [canvas.coordinator.record.state :as state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module record-legacy-ledger
  "The ledgers as files kept them before the core held them: workstream.edn, entries/ and
   entries.meta/. The only reader of those files, and it never writes them.

   HIDES how a row is recovered where index and directory disagreed — the row kept beside the
   entry, else the index's row for that file, else the row the file name implies — so the core
   receives each ledger as the ledger always read it; and whether a ledger's numbered entry files
   are one to a seq."
  (Operation legacy-ids
    "Every workstream of a project with a legacy record on disk."
    {:signature [:=> [:catn [:project ProjectName]] [:vector :string]]
     :delegates [state/workstreams-dir]})
  (Operation legacy-projects
    "Every project with a workstreams directory."
    {:signature [:=> [:catn] [:vector :keyword]]})
  (Operation read-legacy
    "A workstream's identity and its entries, rows and content, as its files hold them, and the
     files that share a seq with another."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [state/workstream-edn-path state/workstream-dir]})
  (Operation import!
    "Send one legacy ledger to the core whole, as a single keyed command; refused once the core
     holds it. A ledger whose files are not one to a seq is sent as no command: it is refused,
     naming the files that share a seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [read-legacy client/command!]})
  (Operation import-all!
    "Import every legacy ledger and count what was imported, already held, and refused."
    {:signature [:=> [:catn [:projects [:? [:maybe [:vector :keyword]]]]] :map]
     :delegates [legacy-projects legacy-ids import!]}))
