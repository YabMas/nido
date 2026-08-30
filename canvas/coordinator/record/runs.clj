(ns canvas.coordinator.record.runs
  "Self-spec: `nido.coordinator.record.runs` — one execution of a trigger, and its state machine."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.state :as state]
            [canvas.coordinator.record.triggers :as triggers]
            [canvas.coordinator.record.vocabulary :refer [ProjectName RunId SessionName WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream]
            [fukan.common.typing.malli]))

(Kind Run
  "One execution of a trigger against a workstream: what fired it, which skill answers it, the
   session it runs in, and where it is in its lifecycle.

   The execution-driver record, distinct from the Session that is the work record. A Run drives
   the state machine; a session outlives it. SHAPELESS — the closed schema in the code is
   validated on every write.")

(Module record-runs
  "The Run: minting one from a fire request, moving it through its states, and the session it
   occupies while it does.

   The state machine is the point. Transitions are checked against an allowed table rather than
   assigned, so a Run cannot be moved from a terminal state and a caller that tries is refused
   at the write rather than discovered later by whoever reads a nonsense history."
  (Operation validate
    "The run, or a throw carrying humanised errors."
    {:signature [:=> [:catn [:run Run]] Run]})
  (Operation read-run
    "One run by id, or nil. Normalises what older records spelled differently."
    {:signature [:=> [:catn [:run-id RunId]] [:maybe Run]]
     :delegates [state/run-edn-path]})
  (Operation write-run!
    "Validate, then persist. The parent directory must already exist — minting is what creates
     it, and a write that made its own directory would let a typo mint a run."
    {:signature [:=> [:catn [:run Run]] Run]
     :delegates [validate state/run-edn-path]})
  (Operation list-run-ids
    "Every run id on disk."
    {:signature [:=> [:catn] [:vector RunId]]
     :delegates [state/runs-dir]})
  (Operation in-progress-count-by-trigger
    "How many runs are in flight, per trigger — the scheduler's backpressure reading."
    {:signature [:=> [:catn] [:map-of :keyword :int]]
     :delegates [list-run-ids read-run]})
  (Operation find-for-session
    "The newest run owning this session, or nil."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] [:maybe Run]]
     :delegates [list-run-ids read-run]})
  (Operation valid-transition?
    "Whether one state may follow another. Terminal states lead nowhere, which is what stops a
     late event resurrecting a finished run."
    {:signature [:=> [:catn [:from :keyword] [:to :keyword]] :boolean]})
  (Operation transition!
    "Move a run to a new state, recording where it came from. Refused when the move is not one
     the machine allows."
    {:signature [:=> [:catn [:run-id RunId] [:new-state :keyword]] Run]
     :delegates [read-run write-run! valid-transition?]})
  (Operation mirror-run-phase!
    "Best-effort mirror of the run's state onto the session that carries it, so the work record
     and the execution record agree without either owning the other."
    {:signature [:=> [:catn [:run Run]] :any]
     :delegates [session/set-phase!]})
  (Operation create-run!
    "Build a queued run from a fire request and persist it, stamping the workstream's declared
     design scope onto it so the session it spawns can be briefed with the part of the design
     that governs its area."
    {:signature [:=> [:catn [:request :map] [:meta :map]] Run]
     :delegates [write-run! triggers/render-payload workstream/latest-entry state/run-dir]})
  (Operation spawn-session-for-run!
    "Bring up the session a run executes in, marked as owned by it."
    {:signature [:=> [:catn [:run Run]] :any]})
  (Operation home-present?
    "Whether the run's session home still exists. It is ephemeral and may be reclaimed under a
     run that is still going, which is why nothing assumes it."
    {:signature [:=> [:catn [:run Run]] :boolean]
     :delegates [state/run-session-home-link]})
  (Operation ensure-session-home!
    "Re-provision a reclaimed session home so a resume has somewhere to land."
    {:signature [:=> [:catn [:run Run]] :any]
     :delegates [home-present? spawn-session-for-run!]})
  (Operation teardown-session-for-run!
    "Reclaim the session a run spawned, once it has reached a resolved state."
    {:signature [:=> [:catn [:run Run]] :any]
     :delegates [session/archive!]})
  (Operation launch-context
    "The worktree and injected context an agent for this run launches into."
    {:signature [:=> [:catn [:run Run]] :map]}))
