(ns canvas.coordinator.record.live
  "Self-spec: `nido.coordinator.record.live` — which records can still change, held by the running
   coordinator."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.drain :as drain]
            [canvas.coordinator.record.runs :as runs]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.session-events :as events]
            [canvas.coordinator.record.state :as state :refer [RunId]]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module record-live
  "The SUBJECTS — workstreams that can change without anything writing to them: activated, an
   open drain or an open workstream with a conductor, holding a session whose home stands or
   whose conversation Claude Code lists or wrote lately, or named by an unfinished Run — and what
   moved since the last look.

   HIDES whether those answers come from an in-memory picture kept by a stat census or from a
   fresh read of the disk. The picture is DERIVED only: one full read at start, corrected by
   every census, never written from, gone with the process. A process that has not started it is
   answered from disk exactly as the record layer answers, so nothing outside the daemon can
   tell it exists.

   Correct because every write nido makes moves a stamp the census reads — a record renamed into
   its workstream's, session's or run's directory, an event appended to its conversation's log —
   so a pass over the subjects and the moved misses nothing a pass over everything would act on.
   What changes with NO write is the backstop's: the daemon runs the passes over the subjects
   periodically whatever moved."
  (Operation start!
    "Read every workstream, session record, Run and event log of the projects once; this process
     is answered from the picture from then on. Everything counts as moved until the first
     census has passed that on."
    {:signature [:=> [:catn [:projects [:sequential ProjectName]]] :any]
     :delegates [workstream/list-ids workstream/read-ws workstream/activation workstream/closure
                 drain/drain? session/list-sessions runs/list-run-ids runs/read-run
                 state/workstreams-dir state/workstream-dir state/ws-sessions-dir state/runs-dir
                 state/run-dir state/session-events-path events/use-index!]})
  (Operation stop!
    "Forget the picture; this process is answered from disk again."
    {:signature [:=> [:catn] :any]
     :delegates [events/use-index!]})
  (Operation started?
    "Whether this process holds a picture."
    {:signature [:=> [:catn] :boolean]})
  (Operation census!
    "Stat what can show change and re-read what moved: the subjects, unfinished Runs and recently
     written logs every call, everything at a slower period, and anything new. Answers what
     moved — workstreams, Runs, conversations — or nil when not started."
    {:signature [:=> [:catn] [:maybe :map]]
     :delegates [start!]})
  (Operation listed!
    "Tell the picture which conversations Claude Code lists; a workstream whose session record
     names one is a subject."
    {:signature [:=> [:catn [:conversation-ids [:set :string]]] :any]})
  (Operation moved?
    "Whether the last census found anything moved; true while not started."
    {:signature [:=> [:catn] :boolean]})
  (Operation subjects
    "A project's workstream ids a pass visits: the subjects and the moved, or every one while
     not started."
    {:signature [:=> [:catn [:project ProjectName]] [:vector :string]]
     :delegates [workstream/list-ids]})
  (Operation unfinished-run-ids
    "The Runs not yet done, failed or dry-run, and any that moved; every Run while not started."
    {:signature [:=> [:catn] [:vector RunId]]
     :delegates [runs/list-run-ids]})
  (Operation logs-written-since
    "The conversations whose logs the picture holds as written at or after an instant, in epoch
     milliseconds, or nil while not started."
    {:signature [:=> [:catn [:since-ms :int]] [:maybe [:sequential :string]]]}))
