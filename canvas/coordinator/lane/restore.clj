(ns canvas.coordinator.lane.restore
  "Self-spec: `nido.coordinator.lane.restore` — bringing back what a failed session start stopped."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-restore
  "Bring back what a failed session start stopped: the session, and whatever was waiting on it.

   The session is started HERE, in the process running the restore, and the daemon is handed only
   what continues once it is up — a Run to execute, a reply's turn to replay. That split is the
   point. The daemon loads nido's code once; when the failure was a defect a recovery has just
   fixed, a bring-up the daemon performed would run the code that failed. A continuation reaches a
   session that is already up, so it never runs the start path at all.

   A :failed Run stays failed. What it was doing continues as a new Run of its trigger and payload
   on its workstream; a Run that parked instead of failing is executed again as itself."
  (Operation continuation
    "What continues a failed start once its session is up, read from the failure's origin: nothing
     for a person's start, the Run or its successor for a Run, the reply's turn for a resume.
     Withdrawn only when the workstream the origin names closed after the failure — never because
     the session is gone, which every failed Run's teardown makes true."
    {:signature [:=> [:catn [:failure :map] [:facts :map]] :map]})
  (Operation restore!
    "Restore every failure of a recovery workstream's cause still owed, append one :session-restored
     entry saying what happened to each, and close the workstream when all came back or were
     withdrawn. Refuses on a workstream that holds no :session-diagnosis."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [continuation]}))
