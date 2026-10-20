(ns canvas.coordinator.record.tickets
  "Self-spec: `nido.coordinator.record.tickets` — the triage record for one external ticket."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.runs :as runs]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.state :as state :refer [Path]]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.platform.io :as io]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind TicketId
  "An external ticket's identifier — `BR-####` for Notion. Directory name and lookup key both."
  :string)

(Kind Ticket
  "What nido knows about one external ticket: where it came from and what it is called. Where its
   triage stands is not here — that is derived from the ticket's workstream (`status`).

   Distinct from the Workstream that answers it. A ticket is the OUTSIDE world's record and is
   keyed by the outside world's id; a workstream is nido's, and one may exist without the other.
   SHAPELESS — the shape lives with the code that writes it.")

(Module record-tickets
  "Per-ticket triage: one directory per ticket, keyed by the external id, and where its triage
   stands — derived, never stored, from the ledger and runs of the workstream carrying the ticket.

   The gate lives here. `gate-decision` and `promote-decision` read the ticket's status and say
   what the coordinator may do next — which is why a re-emitted event does not spawn a second
   triage run, and why a ticket nobody has triaged cannot be promoted straight to planning.
   DERIVED because what moves it is a run starting or ending and a person accepting or
   dismissing, all of which the ledger and the run records already hold; a stored copy had to be
   patched every time a run ended abnormally, and was wrong whenever the patch missed."

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
  (Operation update-meta!
    "Read a ticket's meta, apply a function to it, write the result back — as one locked
     operation, without the status fields a record written before its status was derived still
     carries.

     Not `io/update-edn!`, and the difference is the branch: `f` returning nil writes nothing, so
     an update can decline to conjure a record for a ticket that never had one. An update that may
     decline to write is not a function from the old value to the new one, and moving that
     decision outside the lock is the race this exists to prevent."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]
                            [:f [:=> [:cat :any] :any]]] [:maybe Ticket]]
     :delegates [ticket-dir io/with-file-lock io/lock-path-for]})
  (Operation status-of
    "Where triage of the ticket a workstream carries stands — dismissed, planning, implementing,
     triaged, investigating, awaiting input, or nil when it is re-triable — read off that
     workstream's ledger and runs, optionally leaving one run out."
    {:signature [:=> [:catn [:project ProjectName] [:w :any]] [:maybe :keyword]]
     :delegates [runs/runs-of workstream/closure standing/verdict standing/chosen-stage
                 standing/restored-at]})
  (Operation status
    "`status-of` the workstream carrying the ticket, or nil when none does."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] [:maybe :keyword]]
     :delegates [status-of workstream/find-by-ref-id]})
  (Operation open!
    "Create a ticket record, or refresh the descriptive fields of one that exists."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId] [:base :map]] Ticket]
     :delegates [update-meta!]})
  (Operation held-by-gate? "Whether an unaccepted Slack proposal holds the ticket, read off the ledger."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] :boolean]
     :delegates [workstream/find-by-ref-id standing/verdict status-of]})
  (Operation mark! "Set one field on an existing ticket record."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId] [:k :keyword] [:v :any]] [:maybe Ticket]]
     :delegates [update-meta!]})
  (Operation unmark! "Drop one field from an existing ticket record."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId] [:k :keyword]] [:maybe Ticket]]
     :delegates [update-meta!]})
  (Operation complete!
    "Terminal completion of a triage verdict: an acceptance of the newest verdict on the ticket's
     workstream — or of the ticket as reported, when it holds none."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] :any]
     :delegates [workstream/find-or-mint-by-ref! standing/verdict workstream/append-entry!]})
  (Operation dismiss!
    "Take a ticket off the triage radar: settle the workstream carrying it :dismissed, minting one
     when none does."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] :any]
     :delegates [workstream/find-or-mint-by-ref! workstream/closure workstream/close!]})
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
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] :map]}))
