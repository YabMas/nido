(ns canvas.coordinator.lane.start
  "Self-spec: `nido.coordinator.lane.start` — a person starting work.

   A person's start of a ticket is an ACT, carried out at the click, not a request the daemon
   fulfils later. It is not a Run: a Run is work nido starts and finishes on its own, and nothing
   a person started waits behind the Run cap, a trigger's breaker or a budget."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.conductor :as conductor]
            [canvas.coordinator.lane.work :as lanes]
            [canvas.coordinator.record.state :refer [WorkstreamId SessionName]]
            [canvas.coordinator.record.tickets :as tickets]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.integration.github :as github]
            [canvas.integration.notion :as notion]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.lifecycle :as lifecycle]
            [fukan.common.typing.malli]))

(Module lane-start
  "What a person's start consists of, and in what order its writes land.

   The ticket's own words are put on the ledger when they are owed — no triage and no
   transcription recorded — whatever else the ledger holds. The Notion properties a start writes
   are the project's configuration, not a trigger's, and best-effort: a failed write never stops
   the session coming up."
  (Operation start-session!
    "Bring a named session up on the workstream holding it — or on the one named, or a minted
     one-off — on that workstream's runtime, and carry it once it is up when this start first
     recorded it, or whenever the caller asks; the carry takes the whole seat only when it is free. The work plane's session start and a ticket start share it. Returns the ws-id."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName] [:opts :map]] WorkstreamId]
     :delegates [lanes/birth! lifecycle/up! conductor/carry!]})
  (Operation start-ticket!
    "A person starts a ticket's workstream: find or mint it by its ref, transcribe the ticket when
     nothing has been, record the person's :in-progress choice unless the ticket is already
     planning or being worked, activate the recipe by :person,
     write the project's start properties to a Notion ticket, and bring its impl session up and
     carried into the whole seat when that seat is free — a re-start's existing session included.

     It is given either {:ws-id} or {:ref {:adapter :id}}, already resolved by the surface lane
     that took the person's input — the lane's gate is where the input is trusted, and
     start-ticket! checks only what it alone can see. Answers {:decision :started :ws-id
     :session}, or, before writing anything, one refusal: :no-such-workstream, :closed,
     :name-held (the impl session's name belongs to another workstream), or :ticket-unreadable
     (the ticket's words are owed to the ledger and cannot be read)."
    {:signature [:=> [:catn [:project ProjectName]
                       [:start [:or [:map [:ws-id WorkstreamId]]
                                    [:map [:ref [:map [:adapter [:enum :notion :github-issue]]
                                                      [:id :string]]]]]]]
                 [:or [:map [:decision [:enum :started]] [:ws-id WorkstreamId] [:session SessionName]]
                      [:map [:decision [:enum :no-such-workstream :closed :name-held
                                         :ticket-unreadable]]]]]
     :delegates [workstream/find-or-mint-by-ref! lanes/ensure-ticket-brief! tickets/status-of workstream/advance-stage!
                 workstream/activate! notion/retrieve-page notion/update-page-properties!
                 github/view-issue start-session!]}))
