(ns canvas.coordinator.lane.drive
  "Self-spec: the lanes that advance work by themselves — the driver, the arc it reads, the merge
   lane, spawning, review sweeps, run cleanup, and the legacy migration."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.agent :as agent]
            [canvas.coordinator.executor :as executor]
            [canvas.coordinator.record.runs :as runs :refer [Run]]
            [canvas.coordinator.record.session :as session :refer [Session]]
            [canvas.coordinator.record.state :as cstate :refer [Path RunId SessionName WorkstreamId]]
            [canvas.coordinator.record.tickets :as tickets :refer [TicketId]]
            [canvas.coordinator.record.workstream :as workstream :refer [Workstream]]
            [canvas.platform.io :as io]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-pipeline
  "Where a workstream is in its life, read from its own ledger.

   DERIVED, never stored. The arc is what the entries say happened, so a position cannot drift
   from the record it is read out of — and a stage the projection can name is not necessarily
   one the driver can run, which is why parking is the normal case rather than a failure."
  (Operation intake-kind "How a workstream came to exist, which decides how it advances."
    {:signature [:=> [:catn [:w Workstream] [:kinds :any]] [:maybe :keyword]]})
  (Operation stage-of "The arc stage an entry kind belongs to, or nil."
    {:signature [:=> [:catn [:kind :keyword]] [:maybe :keyword]]})
  (Operation arc "A ledger read as the arc it travelled."
    {:signature [:=> [:catn [:entries :any] [:opts [:? :map]]] :any] :delegates [stage-of]})
  (Operation baseline-verified? "Whether a review found the newest baseline sufficient."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean]
     :delegates [workstream/entries-of]})
  (Operation next-action "The stage to run next and the mode it runs in, or nil at a terminus."
    {:signature [:=> [:catn [:position :any] [:kind :keyword]] [:maybe :map]]})
  (Operation disposition "What a finished status means for the workstream it finished on."
    {:signature [:=> [:catn [:status :keyword]] :keyword]})
  (Operation of "Where a workstream stands and what should happen to it next."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/read-ws intake-kind next-action baseline-verified?]}))

(Module lane-drive
  "The driver: advance every allow-listed workstream by at most one stage per tick.

   An ALLOW-LIST rather than everything, because autonomous advancement is opt-in per workstream.
   At most one stage per tick, because a driver that ran an arc to completion in one pass would
   be an agent nobody could interrupt.

   Parking is how it stops: a stage that escalates records what it tried and what it needs, and
   the workstream waits for a person rather than retrying into the same wall."
  (Operation attempt "One entry for a halt's record — what a stage did and what came of it."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation halt-for "The halt record for a stage that reached an outcome."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation park! "Stop a workstream and record why."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:halt :map]] :map]
     :delegates [workstream/append-entry! session/set-phase!]})
  (Operation park-on-escalate! "Park only when the outcome's disposition says to."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:opts :map]] :any]
     :delegates [park!]})
  (Operation driven "The workstreams the driver may advance."
    {:signature [:=> [:catn] :any] :delegates [cstate/driving-path]})
  (Operation driving? "Whether a workstream is on the allow-list."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean] :delegates [driven]})
  (Operation drive! "Add a workstream to the allow-list."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [cstate/driving-path io/update-edn!]})
  (Operation undrive! "Take a workstream off the allow-list."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [cstate/driving-path io/update-edn!]})
  (Operation fireable "What the driver should fire for a workstream, or why it should not."
    {:signature [:=> [:catn [:position :any]] :map]})
  (Operation run-stage! "Run one mechanical stage to a settled outcome, then act on it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:stage :keyword]
                            [:opts [:? :map]]] :map]
     :delegates [park-on-escalate!]})
  (Operation in-flight? "Whether a drive run already claims this workstream."
    {:signature [:=> [:catn [:ws-id WorkstreamId]] :boolean] :delegates [runs/list-run-ids runs/read-run]})
  (Operation tick! "Advance every allow-listed workstream by at most one stage."
    {:signature [:=> [:catn [:submit! [:? :any]]] :any]
     :delegates [driven in-flight? fireable]}))

(Module lane-spawn
  "The live path: turn a routed fire into a workstream, a run and a session, then submit it.

   Find-or-create on the workstream, so a second fire for the same external ref joins the
   existing work rather than starting a rival copy of it — and a fire whose ref already has a
   pending session is dropped rather than duplicated."
  (Operation external-ref "The external ref an event payload implies, or nil."
    {:signature [:=> [:catn [:payload :map]] [:maybe :map]]})
  (Operation ensure-workstream! "Find or create the workstream this fire belongs to."
    {:signature [:=> [:catn [:project ProjectName] [:payload :map] [:stage :keyword]] Workstream]
     :delegates [external-ref workstream/find-by-ref workstream/create!]})
  (Operation autonomy-from "The autonomy facet a freshly created run implies."
    {:signature [:=> [:catn [:run Run]] :map]})
  (Operation create-session-for-run! "Persist the authoritative autonomous session for a run."
    {:signature [:=> [:catn [:run Run] [:ws-id WorkstreamId]] Session]
     :delegates [autonomy-from session/create!]})
  (Operation spawn-records! "Ensure the workstream, create the run, create its session."
    {:signature [:=> [:catn [:routed :map] [:meta :map]] :map]
     :delegates [ensure-workstream! runs/create-run! create-session-for-run!]})
  (Operation ref-has-pending-session? "Whether this fire's ref already has work in flight."
    {:signature [:=> [:catn [:routed :map]] :boolean]
     :delegates [external-ref workstream/find-by-ref session/pending-session-for-trigger?]})
  (Operation spawn-and-submit! "Spawn the records and hand the run to the scheduler."
    {:signature [:=> [:catn [:routed :map] [:meta :map]] :any]
     :delegates [spawn-records! executor/submit!]}))

(Module lane-ship
  "The merge lane: the daemon side of shipping a workstream.

   Idempotent by design — a second ship envelope for a workstream already merging is a no-op,
   because the thing that would go wrong is two merges of the same branch."
  (Operation create-merge-run! "Build and persist a queued merge run, reusing the session."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] Run]
     :delegates [runs/write-run!]})
  (Operation merge-run-in-flight? "Whether a merge run already owns this workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean]
     :delegates [runs/list-run-ids runs/read-run]})
  (Operation handle-ship! "Process a ship envelope. No-op when a merge is already running."
    {:signature [:=> [:catn [:opts :map]] [:maybe :any]]
     :delegates [merge-run-in-flight? create-merge-run!]})
  (Operation merge-lane-summary "Counts for the coordinator's status line."
    {:signature [:=> [:catn] :map] :delegates [runs/list-run-ids runs/read-run]})
  (Operation classify-outcome "What a merge run's result means, by declared precedence."
    {:signature [:=> [:catn [:project ProjectName] [:br :any] [:run-id RunId] [:result :any]] :keyword]})
  (Operation drive-home-blocking! "The executor body for a merge run — runs the merge to a verdict."
    {:signature [:=> [:catn [:run-id RunId]] :any]
     :delegates [runs/read-run agent/launch! classify-outcome]}))

(Module lane-review
  "Settling runs whose verdict arrived through the ticket rather than through the run."
  (Operation run-state-from-ticket "The run state a ticket status implies after a clean exit."
    {:signature [:=> [:catn [:ticket-status :keyword]] [:maybe :keyword]]})
  (Operation sweep-resolved! "Transition every awaiting-review run whose ticket has since settled."
    {:signature [:=> [:catn] :any]
     :delegates [runs/list-run-ids runs/read-run runs/transition! tickets/status]}))

(Module lane-runs-clean
  "Deleting terminal runs, by plan and then by execution.

   Two steps rather than one, because deleting run directories is the kind of thing you want to
   read before you do."
  (Operation parse-duration-ms "A duration like `7d` in milliseconds, or nil."
    {:signature [:=> [:catn [:s :string]] [:maybe :int]]})
  (Operation plan-clean "What would be deleted, and from where."
    {:signature [:=> [:catn [:opts :map]] [:vector :map]]
     :delegates [parse-duration-ms cstate/run-dir]})
  (Operation execute! "Delete everything the plan lists. A missing path is not an error."
    {:signature [:=> [:catn [:plan [:vector :map]]] :any]}))

(Module lane-migrate
  "One-shot migration of the legacy ticket and run records into workstreams and sessions.

   Best-effort and idempotent: it is repair, and repair that refuses to run twice cannot be run
   at all when the first attempt half-finished."
  (Operation ticket->workstream "A legacy ticket record as a workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ticket :map]] Workstream]})
  (Operation run->session "A legacy run record as a workstream id and a session."
    {:signature [:=> [:catn [:run :map]] :map]})
  (Operation archive-orphaned-live! "Archive every session whose substrate is gone."
    {:signature [:=> [:catn [:project ProjectName]] :any]
     :delegates [session/list-sessions session/archive! workstream/list-ids]})
  (Operation run-once! "Migrate one project's legacy records."
    {:signature [:=> [:catn [:project ProjectName]] :any]
     :delegates [ticket->workstream run->session workstream/write! session/write!]})
  (Operation ledger->workstreams! "Migrate the legacy per-ticket ledgers."
    {:signature [:=> [:catn [:project ProjectName]] :any]
     :delegates [workstream/append-entry! tickets/list-ids tickets/read-meta]}))
