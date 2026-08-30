(ns canvas.coordinator.record.session
  "Self-spec: `nido.coordinator.record.session` — a work episode against a workstream."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.state :as state]
            [canvas.coordinator.record.vocabulary :refer [ProjectName WorkstreamId SessionName]]
            [fukan.common.typing.malli]))

(Kind Session
  "One work episode against a workstream: its substrate (live or archived), its weight, and an
   optional autonomy facet that makes it an agent's episode rather than a human's.

   The authoritative work and HITL record — self-sufficient for resume identity, which is why
   `:autonomy` carries the claude session id and the limits rather than pointing at a run.
   A session OUTLIVES the machine it ran on: `:archived` is exactly that outliving, and is why
   this record and the provisioned instance are two things and not one.

   SHAPELESS. The closed malli schema in the code is validated on every write, and a second copy
   here would be the copy nothing checks.")

(Kind StageProjection
  "Where a workstream stands and whether it is waiting on a person — derived, never stored."
  [:map [:stage :keyword] [:needs-you :boolean]])

(Module record-session
  "The work episode: minting one, moving it through its phases, and projecting what a
   workstream's sessions say about where it stands.

   Two halves that do not mix. The record half reads and writes session.edn. The projection half
   is PURE — `engagement-state`, `stage-projection`, `notion-stage` take what they need as
   arguments and answer without touching disk, which is what makes a board's stage testable
   without provisioning a session to test it with."
  (Operation validate
    "The record, or a throw carrying malli's explanation of why it is not one."
    {:signature [:=> [:catn [:s Session]] Session]})
  (Operation read-session
    "One session by project, workstream and name. nil when there is none."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] [:maybe Session]]
     :delegates [state/session-edn-path]})
  (Operation write!
    "Persist a session, validated first. Writing an invalid record is refused rather than
     deferred to whoever reads it next."
    {:signature [:=> [:catn [:s Session]] Session]
     :delegates [validate state/session-edn-path]})
  (Operation create!
    "Mint and persist a fresh live session."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:opts :map]] Session]
     :delegates [write! clock/now-iso]})
  (Operation list-sessions
    "Every session recorded against a workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:sequential Session]]
     :delegates [state/ws-sessions-dir]})
  (Operation live?
    "Whether the session's substrate is still there."
    {:signature [:=> [:catn [:s Session]] :boolean]})
  (Operation autonomous?
    "Whether an agent is driving this episode rather than a person."
    {:signature [:=> [:catn [:s Session]] :boolean]})
  (Operation parked?
    "A live autonomous session waiting on a human answer."
    {:signature [:=> [:catn [:s Session]] :boolean]
     :delegates [live?]})
  (Operation working?
    "A live session doing actual work — a human's, or an autonomous one in flight. A queued or
     parked autonomous session is not working, and conflating the two is how a board reports
     progress that nobody is making."
    {:signature [:=> [:catn [:s Session]] :boolean]
     :delegates [live? autonomous?]})
  (Operation archive!
    "Flip a session to archived, recording when. Idempotent — archiving twice leaves one entry."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] Session]
     :delegates [write! clock/now-iso]})
  (Operation set-phase!
    "Move an autonomous session's phase, recording where it came from."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:new-phase :keyword]] Session]
     :delegates [write! clock/now-iso]})
  (Operation set-error!
    "Record, or clear, why the last resume of an autonomous session failed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:err [:maybe :map]]] Session]
     :delegates [write!]})
  (Operation set-claude-session-id!
    "Persist the conversation id an autonomous session resumes from."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:id [:maybe :string]]] Session]
     :delegates [write!]})
  (Operation engagement-state
    "What a workstream's sessions say about whether anyone is engaged with it. Pure."
    {:signature [:=> [:catn [:closed :any] [:sessions [:sequential Session]]] :keyword]
     :delegates [live? working? parked?]})
  (Operation stage-projection
    "Where a workstream stands and whether it needs a person, from its own sessions. Pure."
    {:signature [:=> [:catn [:closed :any] [:ticket-status :any] [:sessions [:sequential Session]] [:stage-override :any]] StageProjection]})
  (Operation notion-stage
    "Board stage for a Notion-driven workstream, from the ticket's live status and whether it
     has been triaged locally. Pure."
    {:signature [:=> [:catn [:notion-status :any] [:triaged? :boolean]] :keyword]})
  (Operation notion-stage-projection
    "Stage and needs-you for a workstream whose ticket lives in a watched Notion view. Pure."
    {:signature [:=> [:catn [:ctx :map]] StageProjection]
     :delegates [notion-stage]})
  (Operation workstream-id-for
    "Which workstream owns the session with this name, or nil."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName]] [:maybe WorkstreamId]]
     :delegates [state/workstreams-dir list-sessions]})
  (Operation pending-session-for-trigger?
    "Whether this workstream already has an in-flight autonomous session for this trigger — the
     pre-spawn gate that stops a reconcile re-emit piling a duplicate run onto a ticket."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:trigger :keyword]] :boolean]
     :delegates [list-sessions autonomous?]})
  (Operation count-by-trigger
    "Live autonomous sessions per trigger, counting only the phases asked for."
    {:signature [:=> [:catn [:project ProjectName] [:phase-set :any]] [:map-of :keyword :int]]
     :delegates [state/workstreams-dir list-sessions live? autonomous?]})
  (Operation in-flight-by-trigger
    "How much work is actually executing per trigger. Distinct from the scheduling count: a
     parked session occupies a slot without doing anything."
    {:signature [:=> [:catn [:project ProjectName]] [:map-of :keyword :int]]
     :delegates [count-by-trigger]})
  (Operation gating-count-by-trigger
    "Scheduling backpressure per trigger — in-flight plus parked, because a parked session still
     holds the slot it will resume into."
    {:signature [:=> [:catn [:project ProjectName]] [:map-of :keyword :int]]
     :delegates [count-by-trigger]})
  (Operation ship-substate
    "Where a shipping workstream is in the merge lane, from its live autonomous sessions."
    {:signature [:=> [:catn [:sessions [:sequential Session]]] [:maybe :keyword]]
     :delegates [live? autonomous?]}))
