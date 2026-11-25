(ns canvas.coordinator.lane.restore
  "Self-spec: `nido.coordinator.lane.restore` — bringing back what a failed session start stopped."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-restore
  "Bring back what a failed session start stopped.

   What a person was working in comes back up HERE, in the process running the restore — the
   recovery's own session, never the daemon: when the failure was a defect a recovery has just
   fixed, a bring-up the daemon performed would run the code that failed.

   A start kept on behalf of a Run or a reply's turn is withdrawn: the core runs every turn, and a
   turn whose session did not come up ended failed there, so nothing is left here to continue."

  (Operation continuation
    "What continues a failed start once its session is up: nothing for a person's start. Withdrawn
     for a start made for a Run or a reply's turn, or one naming a session `up!` now refuses —
     restoring it could only be refused again, and a refusal is not a failure, so it would stay
     owed forever. Withdrawn, naming :no-person, for a start kept as a person's in a session
     nobody typed a prompt in within the day before it failed — restoring it brings up what nobody
     works in. When the prompt log cannot be read it answers :unread and the failure is left
     :not-restored, still owed — an unreadable log says nothing about who typed."
    {:signature [:=> [:catn [:failure :map] [:facts :map]] :map]})
  (Operation restore!
    "Restore every failure of a recovery run's cause still owed and record one :session-restored on
     the run saying what happened to each. Refuses for a run that is not a recovery, or has
     recorded no :session-diagnosis."
    {:signature [:=> [:catn [:project ProjectName] [:run-key :string]] :map]
     :delegates [continuation]}))
