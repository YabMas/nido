(ns canvas.session.failure
  "Self-spec: `nido.session.failure` — failed session starts, kept."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [fukan.common.typing.malli]))

(Kind SessionFailure
  "One failed session start: what was attempted, on whose behalf, and the evidence read at the
   moment it failed. Immutable once kept. Whether it has been recovered is not a fact about the
   failure but about what was done since, so it lives in the ledger and is derived from there.")

(Kind Origin
  "On whose behalf a start ran, as its caller states it — a person, a Run, a reply resuming a
   parked Run. Kept verbatim and never interpreted here: what continues a failed start is the
   restore lane's question, not the substrate's.")

(Module session-failure
  "Failed session starts, kept where a process other than the one that failed can find them.

   A start's exception is otherwise rendered wherever it happened and then lost, and a failed
   Run's teardown deletes the logs that explain it. A record snapshots that evidence at the moment
   of failure, in a directory no session's destroy, teardown or reclaim touches, so the diagnosis
   can happen later and in another process.

   The cause key lives here rather than with recovery because grouping is a property of the
   error: two failures share a cause when their errors differ only in what names one session."
  {:child [SessionFailure Origin]}
  (Operation failures-dir "Where kept failures live — outside every instance's state directory."
    {:signature [:=> [:catn] Path]})
  (Operation record!
    "Keep one failed start. Returns the record, or nil when it could not be written. Never throws:
     a failure to keep the record must not replace the failure it records."
    {:signature [:=> [:catn [:attempt :map] [:t :any]] [:maybe :map]]})
  (Operation failure "One kept failure by id, or nil."
    {:signature [:=> [:catn [:id :string]] [:maybe :map]]})
  (Operation failures "Every kept failure, oldest first."
    {:signature [:=> [:catn] [:vector :map]]})
  (Operation cause
    "The key two failures share when their errors differ only in paths, ports, ids and counts."
    {:signature [:=> [:catn [:failure :map]] :string]}))
