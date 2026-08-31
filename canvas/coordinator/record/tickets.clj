(ns canvas.coordinator.record.tickets
  "Self-spec: `nido.coordinator.record.tickets` — the triage record for one external ticket."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.state :as state :refer [Path]]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind TicketId
  "An external ticket's identifier — `BR-####` for Notion. Directory name and lookup key both."
  :string)

(Kind Ticket
  "What nido knows about one external ticket: where it came from, what triage decided, and
   whether it may be promoted.

   Distinct from the Workstream that answers it. A ticket is the OUTSIDE world's record and is
   keyed by the outside world's id; a workstream is nido's, and one may exist without the other.
   SHAPELESS — the shape lives with the code that writes it.")

(Module record-tickets
  "Per-ticket triage state: one directory per ticket, keyed by the external id.

   The gate lives here. `gate-decision` and `promote-decision` read the ticket's status and say
   what the coordinator may do next — which is why a re-emitted event does not spawn a second
   triage run, and why a ticket nobody has triaged cannot be promoted straight to planning."

  {:child [Ticket TicketId]}
  (Operation ticket-dir
    "One ticket's directory."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] Path]})
  (Operation read-meta
    "What is known about a ticket, or nil. Nil-safe for a blank id, because a caller resolving
     one from an event should get nothing rather than a throw."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] [:maybe Ticket]]
     :delegates [ticket-dir]})
  (Operation list-ids
    "Every ticket id a project has a record for."
    {:signature [:=> [:catn [:project ProjectName]] [:vector TicketId]]})
  (Operation write-meta!
    "Persist what is known about a ticket. A blank id writes nothing."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId] [:m Ticket]] [:maybe Ticket]]
     :delegates [ticket-dir]})
  (Operation status
    "The ticket's triage status, or nil when it has none."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] [:maybe :keyword]]})
  (Operation open!
    "Create a ticket record, or refresh the descriptive fields of one that exists."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId] [:base :map]] Ticket]
     :delegates [read-meta write-meta!]})
  (Operation set-status!
    "Move a ticket's triage status."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId] [:new-status :keyword]] Ticket]
     :delegates [read-meta write-meta!]})
  (Operation complete!
    "Terminal completion of a triage verdict: the status, the disposition it settled on, and
     when."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId] [:new-status :keyword] [:disposition :any]] Ticket]
     :delegates [read-meta write-meta! clock/now-iso]})
  (Operation clear-status!
    "Make a ticket re-triable by dropping its status — which is what puts it back through the
     gate rather than around it."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] [:maybe Ticket]]
     :delegates [read-meta write-meta!]})
  (Operation dismiss!
    "Take a ticket off the triage radar."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] Ticket]
     :delegates [read-meta write-meta!]})
  (Operation latest-triage-report
    "The newest triage report on the workstream this ticket raised, or nil."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] [:maybe :map]]
     :delegates [workstream/find-by-ref-id]})
  (Operation gate-decision
    "What the coordinator may do about this ticket before spawning anything. The gate that stops
     a re-emitted event starting a second triage run."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] :map]})
  (Operation promote-decision
    "Whether this ticket may be promoted to a planning run, and why not when it may not."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] :map]})
  (Operation on-run-terminal!
    "Reconcile a ticket's record when the run triaging or planning it reaches a terminal state."
    {:signature [:=> [:catn [:run :map] [:run-state :keyword]] :any]
     :delegates [read-meta set-status!]}))
