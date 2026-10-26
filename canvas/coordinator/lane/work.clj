(ns canvas.coordinator.lane.work
  "Self-spec: the lanes that act on a workstream — brief, facets, findings, intake, pickup,
   promote, resume, scratch.

   Each lane is a verb: it reads the records, decides, acts, and appends what it did. None of
   them holds state, and that is what lets the driver run them in any order it likes."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.seats :as seats]
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
     :delegates [refresh-ws! workstream/list-ids workstream/read-ws]}))

(Module lane-findings
  "The staging findings round: what a human found after the work shipped.

   Filing REOPENS a settled workstream, which is the whole point — findings are not a new ticket,
   they are the same work not being finished."
  (Operation file! "File a findings round on a shipped workstream, reopening it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:opts :map]] :map]
     :delegates [workstream/read-ws workstream/closure workstream/append-entry!
                 workstream/reopen!]})
  (Operation resolve! "Record findings resolved by a pull request or commit; the ones still open
     afterwards are what the round owes."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:item-ids :any] [:by :any]] :map]
     :delegates [workstream/read-ws workstream/append-entry! workstream/close!]}))

(Module lane-intake
  "Queue-mode intake: a fire that becomes a passive workstream instead of a session.

   The difference from a live spawn is deliberate — an incoming item waits for a human to pick
   it up, so nothing is provisioned until somebody decides it is worth provisioning."
  (Operation enqueue-inbox!
    "Create a session-less incoming workstream for a queued fire. One an earlier fire minted and
     never penned is told by holding no entry and no session past its conductor's record."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [workstream/create!]})
  (Operation expire-stale!
    "Close incoming workstreams nobody picked up. An inbox that never expires stops being an
     inbox and becomes a list nobody reads."
    {:signature [:=> [:catn [:project ProjectName] [:max-age-ms :int] [:now-ms :int]] :any]
     :delegates [workstream/list-ids workstream/read-ws workstream/close!]}))

(Module lane-pickup
  "Turning a pasted reference into a claimed workstream.

   Accepts a URL, a page id or a ticket id, because what a person has in their clipboard is
   whichever of those they were looking at."
  (Operation extract-page-id "The page id inside a Notion URL or uuid, dashed."
    {:signature [:=> [:catn [:s [:maybe :string]]] [:maybe :string]]
     :delegates [notion/extract-page-id]})
  (Operation resolve-ref "What a pasted reference resolves to."
    {:signature [:=> [:catn [:project ProjectName] [:input :string] [:token NotionToken]] :map]
     :delegates [extract-page-id notion/retrieve-page notion/normalise-page]})
  (Operation claim-and-drive!
    "Claim a resolved ticket as in flight and enqueue the leg that drives it."
    {:signature [:=> [:catn [:project ProjectName] [:ref :map]] :map]
     :delegates [tickets/status tickets/open! workstream/advance-stage!]})
  (Operation pickup! "Resolve a reference and claim its ticket as in flight."
    {:signature [:=> [:catn [:project ProjectName] [:input :string] [:token NotionToken]] :map]
     :delegates [resolve-ref tickets/held-by-gate? claim-and-drive!]}))

(Module lane-promote
  "Moving a triaged ticket forward into planning.

   Gated on the ticket's own record rather than on the caller's intent, which is what stops a
   re-emitted event promoting the same ticket twice."
  (Operation promote! "Promote a ticket to a planning run, or say why not."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] :map]
     :delegates [tickets/promote-decision workstream/advance-stage!]})
  (Operation start-triage! "Promote a queued incoming workstream by running its deferred triage."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/read-ws workstream/advance-stage!]})
  (Operation promote-workstream! "Promote a workstream, dispatching on where it came from."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/read-ws promote! start-triage!]}))

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

(Module lane-resume
  "Re-engaging a session waiting on a person with their answer, as the session's next instruction.
   Which session waits is read off run records, the executor, status and the session's :claude
   facet — no stored phase says it."
  (Operation waiting-session
    "The session waiting on a person's reply: no reply to it queued or pending, and its run parked
     for review or a person owed the workstream's next move."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe Session]]
     :delegates [runs/find-for-session]})
  (Operation resume!
    "Give a session waiting on a person their input as its next instruction, admitted under the
     global cap, recorded as given for the session's Run when that Run awaits review and for no Run
     otherwise; refused for a conversation from before background sessions.

     The session may be named by a caller that found it waiting before recording the person's
     answer: the answer ends the workstream owing the person its next move, so looking again
     after writing it finds nobody."
    {:signature [:function
                 [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:input :string]] :any]
                 [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:input :string]
                             [:waiting [:maybe Session]]] :any]]
     :delegates [waiting-session]}))

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
     :delegates [joinable workstream/create! workstream/list-ids seats/retire-conductor! workstream/delete!]})
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
     :delegates [scratch? workstream/list-ids workstream/read-ws seats/retire-conductor! workstream/delete!]}))

(Module lane-backfill
  "Record as entries the status a workstream's mutable records already hold, so a status read off
   the ledger alone answers for workstreams decided before decisions were recorded. Idempotent:
   it appends only what the ledger does not already say."
  (Operation unaccepted-triage
    "The :seq of the newest triage no acceptance cites, or nil."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :int]]
     :delegates [workstream/read-ws workstream/entries-of]})
  (Operation backfill-workstream!
    "Bring one workstream's ledger level with its records; the kinds appended, in order."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:facts :map]] [:vector :keyword]]
     :delegates [workstream/freeze-index! workstream/append-entry!]})
  (Operation backfill-orphan-tickets!
    "Give every ticket whose record holds a status, and that no workstream carries, a workstream
     closed as the record says it ended — so the status derived off a ledger is the one the
     record held."
    {:signature [:=> [:catn [:project ProjectName]] [:vector :keyword]]
     :delegates [tickets/list-ids tickets/read-meta workstream/find-or-mint-by-ref!
                 workstream/close!]})
  (Operation register-runs!
    "Register every run of a project with its workstream, so the runs minted before registration
     are found from it."
    {:signature [:=> [:catn [:project ProjectName]] :int]
     :delegates [runs/register!]})
  (Operation backfill!
    "Give orphan tickets a workstream, register the project's runs, then backfill every
     workstream; counts appended by kind, and what failed."
    {:signature [:=> [:catn [:project ProjectName]] :map]
     :delegates [backfill-orphan-tickets! register-runs! backfill-workstream!]}))
