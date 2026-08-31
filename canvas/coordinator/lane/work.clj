(ns canvas.coordinator.lane.work
  "Self-spec: the lanes that act on a workstream — brief, facets, findings, intake, pickup,
   promote, resume, scratch.

   Each lane is a verb: it reads the records, decides, acts, and appends what it did. None of
   them holds state, and that is what lets the driver run them in any order it likes."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path WorkstreamId SessionName]]
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
     :delegates [workstream/read-ws workstream/append-entry! workstream/set-findings!
                 workstream/reopen!]})
  (Operation resolve! "Mark findings resolved by a pull request or commit."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:item-ids :any] [:by :any]] :map]
     :delegates [workstream/read-ws workstream/set-findings!]})
  (Operation open-count "How many findings are still open on a workstream."
    {:signature [:=> [:catn [:ws-record Workstream]] :int]}))

(Module lane-intake
  "Queue-mode intake: a fire that becomes a passive workstream instead of a session.

   The difference from a live spawn is deliberate — an incoming item waits for a human to pick
   it up, so nothing is provisioned until somebody decides it is worth provisioning."
  (Operation enqueue-inbox! "Create a session-less incoming workstream for a queued fire."
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
    {:signature [:=> [:catn [:s :string]] [:maybe :string]]})
  (Operation resolve-ref "What a pasted reference resolves to."
    {:signature [:=> [:catn [:project ProjectName] [:input :string] [:token NotionToken]] :map]
     :delegates [extract-page-id notion/retrieve-page notion/normalise-page]})
  (Operation pickup! "Resolve a reference and claim its ticket as in flight."
    {:signature [:=> [:catn [:project ProjectName] [:input :string] [:token NotionToken]] :map]
     :delegates [resolve-ref]}))

(Module lane-promote
  "Moving a triaged ticket forward into planning.

   Gated on the ticket's own record rather than on the caller's intent, which is what stops a
   re-emitted event promoting the same ticket twice."
  (Operation promote! "Promote a ticket to a planning run, or say why not."
    {:signature [:=> [:catn [:project ProjectName] [:br-id TicketId]] :map]
     :delegates [tickets/promote-decision tickets/set-status!]})
  (Operation start-triage! "Promote a queued incoming workstream by running its deferred triage."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/read-ws workstream/advance-stage!]})
  (Operation promote-workstream! "Promote a workstream, dispatching on where it came from."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/read-ws promote! start-triage!]}))

(Module lane-resume
  "Re-engaging a parked session with a human's answer."
  (Operation resume-cwd
    "Where to relaunch so the agent finds its own history. A resume in the wrong directory
     starts a conversation that has forgotten everything."
    {:signature [:=> [:catn [:sid :any] [:worktree Path] [:home Path]] Path]})
  (Operation resume! "Re-engage a parked session with input."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:input :string]] :any]
     :delegates [resume-cwd]}))

(Module lane-scratch
  "One-off workstreams: the ones a person started by hand, for work that began from an idea
   rather than a ticket.

   Disposability is MARKED, not inferred. A one-off carries the `:scratch` stage `birth!` writes,
   and that marker is what says it may be discarded with its session. Ref-lessness used to stand
   in for the marker, which held only while nothing else minted a ref-less workstream — the
   described-intent leg does, so the inference and the marker stopped agreeing and the marker is
   the one that was always authored."
  (Operation scratch? "Whether a workstream is marked a one-off."
    {:signature [:=> [:catn [:w Workstream]] :boolean]})
  (Operation birth! "Ensure a one-off workstream owns a named human session."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName] [:weight :keyword]] :any]
     :delegates [workstream/create! workstream/list-ids]})
  (Operation reap!
    "Delete the one-off workstream owning a session — sparing every unmarked one, and every
     marked one that has since acquired an external ref. Two facts, not one: the marker says
     it was born disposable, the absent ref says nothing outside has claimed it since."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName]] :any]
     :delegates [scratch? workstream/list-ids workstream/read-ws workstream/delete!]}))
