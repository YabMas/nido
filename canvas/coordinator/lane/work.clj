(ns canvas.coordinator.lane.work
  "Self-spec: the lanes that act on a workstream — brief, facets, findings, intake, pickup,
   promote, resume.

   Each lane is a verb: it reads the records, decides, acts, and appends what it did. None of
   them holds state, and that is what lets the driver run them in any order it likes."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.runs :as runs]
            [canvas.coordinator.record.session :refer [Session]]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.state :refer [WorkstreamId SessionName]]
            [canvas.coordinator.record.tickets :as tickets :refer [TicketId]]
            [canvas.coordinator.record.workstream :as workstream :refer [Workstream]]
            [canvas.integration.notion :as notion :refer [NotionToken]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-brief
  "Put a ticket's own words on its workstream, so an agent reads what was asked rather than a
   link it cannot open."
  (Operation ticket-brief "A ticket's page and comments as markdown."
    {:signature [:=> [:catn [:ref :map] [:token NotionToken]] [:maybe :string]]
     :delegates [notion/walk-blocks notion/list-comments notion/blocks->markdown
                 notion/comments->markdown]})
  (Operation owed? "Whether the ticket's own words are still owed to the ledger: no entry tells it."
    {:signature [:=> [:catn [:w :any]] :boolean]})
  (Operation ensure-ticket-brief! "Append the ticket brief to a workstream, once."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:ref :map]
                            [:token NotionToken]] :any]
     :delegates [ticket-brief workstream/append-entry!]}))

(Module lane-facets
  "Keeping a workstream's classification in step with its ticket.

   Facets are a PROJECTION of the ticket, refreshed rather than authored, so a reclassification
   in Notion reaches the board without anyone re-entering it here."
  (Operation select-facets "The facets a normalised page carries, keeping only configured ones."
    {:signature [:=> [:catn [:facet-props :any] [:normalised :map]] :map]})
  (Operation refresh-ws! "Re-read a workstream's page and rewrite its facets."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:token [:? NotionToken]]] :any]
     :delegates [select-facets workstream/read-ws workstream/set-facets! notion/retrieve-page
                 notion/normalise-page ]})
  (Operation refresh-for-ticket! "Refresh the facets of whichever workstream carries a ticket."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] :any]
     :delegates [refresh-ws!]})
  (Operation refresh-project! "Refresh every open Notion-linked workstream in a project."
    {:signature [:=> [:catn [:project ProjectName]] :any]
     :delegates [refresh-ws! workstream/project-records]}))

(Module lane-findings
  "The staging findings round: what a human found after the work shipped.

   Filing REOPENS a settled workstream, which is the whole point — findings are not a new ticket,
   they are the same work not being finished."
  (Operation file! "File a findings round on a shipped workstream, reopening it as the person's
     choice and starting its ticket again (lane-start's start-ticket!)."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:opts :map]] :map]
     :delegates [workstream/read-ws workstream/closure workstream/append-entry!
                 workstream/reopen!]})
  (Operation resolve! "Record findings resolved by a pull request or commit; the ones still open
     afterwards are what the round owes."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:item-ids :any] [:by :any]] :map]
     :delegates [workstream/read-ws workstream/append-entry! workstream/close!]}))

(Module lane-phase
  "Opening a phased workstream's next phase once its gate has been met.

   The same gesture as a findings round — a settled workstream put back to work — and for the same
   reason it reopens rather than mints: one design record governs every landing of one story. A
   gate is ASSERTED with its evidence, never checked, because nido has no production telemetry of
   its own; the evidence is written down so a reader can tell a number from a vibe."
  (Operation advance!
    "Record that the current phase's gate was met, with its evidence, and put the workstream back
     to work on the next phase of the plan its close names. Refused unless the workstream is
     settled between phases, and refused on blank evidence."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:opts :map]] :map]
     :delegates [workstream/read-ws standing/governing-design workstream/open-phase!]}))
