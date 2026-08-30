(ns canvas.coordinator.record.status-file
  "Self-spec: `nido.coordinator.record.status-file` — reading what a skill said about its own run."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :as state]
            [canvas.coordinator.record.vocabulary :refer [RunId]]
            [fukan.common.typing.malli]))

(Kind RunStatus
  "What the skill running inside a run reports about itself, in `_run-status.edn`.

   SHAPELESS, and honestly so: nido does not write this file and does not own its shape. A skill
   does. All nido reads from it is `:phase`, and asserting a shape here would be nido describing
   a document it has no say over — which is the definition of a claim that will drift.")

(Module record-status-file
  "The run's own account of itself, and what the daemon should do about it.

   The one place a skill's self-report crosses into the Run state machine. Absent or malformed
   reads as nil rather than throwing: a skill that crashed before writing is the ordinary case,
   and the daemon's answer for it is the same as for a clean exit with nothing to say."
  (Operation read-status
    "The status a run reported, or nil when it wrote none or wrote something unreadable."
    {:signature [:=> [:catn [:run-id RunId]] [:maybe RunStatus]]
     :delegates [state/run-status-path]})
  (Operation phase->state
    "The Run state a reported phase moves to, or nil for a phase that is still in progress —
     which is the daemon's signal to leave the Run where it is."
    {:signature [:=> [:catn [:phase :keyword]] [:maybe :keyword]]})
  (Operation derive-state-after-exit
    "Where a Run goes after its agent exits cleanly. A status that says nothing means done."
    {:signature [:=> [:catn [:status [:maybe RunStatus]]] :keyword]
     :delegates [phase->state]}))
