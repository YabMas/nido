(ns canvas.coordinator.record.session
  "Self-spec: `nido.coordinator.record.session` — a work episode against a workstream."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.state :as state :refer [WorkstreamId SessionName]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Session
  "One work episode against a workstream: its substrate (live or archived), its weight, and an
   optional autonomy facet that makes it an agent's episode rather than a human's.

   The authoritative work and HITL record — self-sufficient for resume identity, which is why
   `:autonomy` carries the claude session id and the limits rather than pointing at a run.
   A session OUTLIVES the machine it ran on: `:archived` is exactly that outliving, and is why
   this record and the provisioned instance are two things and not one.

   It may name the seat of its workstream it occupies and the generation it occupies it in, or
   name itself the workstream's conductor — never both.

   SHAPELESS. The closed malli schema in the code is validated on every write, and a second copy
   here would be the copy nothing checks.")


(Module record-session
  "The work episode: minting one and reading it back. Where a workstream stands is not a session's
   to say — status derives it from the ledger, run records and held locks — so nothing here
   projects one; `notion-stage` is the one pure reading left, the band a watched Notion page with
   no workstream shows by its live status."

  {:child [Session]}
  (Operation validate
    "The record, or a throw carrying malli's explanation of why it is not one — or naming a record
     that names both a seat and the conductor."
    {:signature [:=> [:catn [:s Session]] Session]})
  (Operation read-session
    "One session by project, workstream and name. nil when there is none."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] [:maybe Session]]
     :delegates [state/session-edn-path]})
  (Operation write!
    "Persist a session, validated first. Writing an invalid record is refused rather than
     deferred to whoever reads it next, and so is a write that would bring a record into existence
     while another workstream of the project holds its name: the check and the write are one step
     under a per-project lock, which is what gives a session name one workstream whichever path
     writes it. Rewriting a record that exists is not a creation and is not checked."
    {:signature [:=> [:catn [:s Session]] Session]
     :delegates [validate state/session-edn-path]})
  (Operation create!
    "Mint and persist a fresh live session — refused, through write!, when another workstream of
     the project already holds the name."
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
  (Operation archive!
    "Flip a session to archived, recording when. Idempotent — archiving twice leaves one entry."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] Session]
     :delegates [write! clock/now-iso]})
  (Operation set-error!
    "Record, or clear, why the last resume of an autonomous session failed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:err [:maybe :map]]] Session]
     :delegates [write!]})
  (Operation claude-facet
    "The Claude Code background session a record is about, and who drives it — nil for a
     conversation from before the substrate."
    {:signature [:=> [:catn [:s Session]] [:maybe :map]]})
  (Operation update-substrate!
    "Replace a session's :claude facet and :dispatching note together, under the record's lock."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:f [:=> [:cat :map] :map]]] Session]})
  (Operation update-claude!
    "Replace a session's :claude facet with what a function makes of it, under the record's lock."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:f [:=> [:cat [:maybe :map]] [:maybe :map]]]] Session]
     :delegates [update-substrate!]})
  (Operation set-claude-session-id!
    "Persist the conversation id an autonomous session resumes from."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:id [:maybe :string]]] Session]
     :delegates [write!]})
  (Operation notion-stage
    "Board stage for a Notion-driven workstream, from the ticket's live status and whether it
     has been triaged locally. Pure."
    {:signature [:=> [:catn [:notion-status :any] [:triaged? :boolean]] :keyword]})
  (Operation workstream-id-for
    "Which workstream holds the session with this name, or nil. A name two workstreams hold is
     refused naming both, never answered with one of them."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName]] [:maybe WorkstreamId]]
     :delegates [state/workstreams-dir list-sessions]}))
