(ns canvas.coordinator.lane.work
  "Self-spec: the lanes that act on a workstream — brief, facets, findings, intake, pickup,
   promote, resume, scratch.

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

(Module lane-scratch
  "Which workstream a session a person started by hand belongs to: the one the start names, or
   else a one-off minted for it — the home of work that began from an idea rather than a ticket.

   Naming one is how a unit that has no ticket to spawn from, a forked child above all, gets a
   session at all. It is also the only way a person chooses a session's workstream, and the choice
   is made once: a session is never moved afterwards.

   Disposability is MARKED, not inferred. A one-off carries the `:scratch` stage `birth!` writes,
   and that marker is what says it may be discarded with its session. Ref-lessness used to stand
   in for the marker, which held only while nothing else minted a ref-less workstream — the
   described-intent leg does, so the inference and the marker stopped agreeing and the marker is
   the one that was always authored."
  (Operation bare?
    "Whether a workstream holds nothing but its own :scratch birth and what its conductor decided —
     its :conduct-decision entries and the blockers they parked — so discarding it loses nothing."
    {:signature [:=> [:catn [:w Workstream]] :boolean]})
  (Operation scratch? "Whether a workstream is marked a one-off."
    {:signature [:=> [:catn [:w Workstream]] :boolean]})
  (Operation joinable
    "Why a start naming this workstream may not join it — missing, closed, or its session name held
     by another workstream — or nil when it may."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [workstream/read-ws]})
  (Operation birth!
    "Ensure a workstream owns a named human session: the one named, or else whichever already holds
     the name, or else a minted one-off. Given a workstream, it is called before the session is
     provisioned, so the record claims the name before any worktree exists. A one-off minted for a
     name another start took first is deleted, its conductor retired before it is."
    {:signature [:function
                 [:=> [:catn [:project ProjectName] [:session-name SessionName] [:weight [:maybe :keyword]]] :any]
                 [:=> [:catn [:project ProjectName] [:session-name SessionName] [:weight [:maybe :keyword]] [:ws-id [:maybe WorkstreamId]]] :any]]
     :delegates [joinable workstream/create! workstream/list-ids workstream/delete!]})
  (Operation settle-runtimes!
    "Move each live session onto the runtime it belongs on: its workstream's, or — for one no
     workstream holds — its own, keyed by its whole name. Returns the names it moved."
    {:signature [:=> [:catn [:project ProjectName] [:live [:set SessionName]]] [:vector SessionName]]})
  (Operation reap!
    "Delete the one-off workstream owning a session — sparing every unmarked one, and every
     marked one that has since acquired an external ref. Two facts, not one: the marker says
     it was born disposable, the absent ref says nothing outside has claimed it since. Neither its
     conductor's record nor what its conductor decided keeps it, and the conductor is retired before
     it is deleted."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName]] :any]
     :delegates [scratch? workstream/project-records workstream/delete!]}))
