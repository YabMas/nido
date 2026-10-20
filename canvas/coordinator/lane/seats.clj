(ns canvas.coordinator.lane.seats
  "Self-spec: `nido.coordinator.lane.seats` — filling, replacing and re-seating a workstream's seats."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.session.commit-gate :as commit-gate]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-seats
  "Who occupies each seat of a workstream, and what a new occupant is told.

   An embodiment is how a seat is named and its occupant briefed; it selects nothing, since the
   seats are the design's generation-defining elements.

   HIDES the occupancy policy, for any embodiment: every seat of a workstream an embodiment carries
   is occupied by a session of its own, and a workstream no embodiment carries has no occupants,
   the conductor being a separate session that never covers one, all of them on the workstream's
   one runtime; how an occupant is started, stopped and replaced through conduct, with a hook that
   refuses a commit or PR title not naming its seat and its position among the seat's layers; and
   how a first brief is built — from the seats reading and what the embodiment adds, never from
   another session's account. When a generation opens it retires every seat occupant of the one
   before and keeps the conductor's. Also where occupants, the conductor and seat traffic are
   kept: an occupant is a session record naming its seat and the generation it occupies it in, and
   only re-seating reads an earlier generation's; the conductor is a session record of the
   workstream naming it the conductor, never a seat, whatever the generation; seat messages are a
   per-workstream log beside the ledger, keyed by generation, that no round reads. And who wrote a `:tension`,
   `:accord`, `:withdrawal` or `:recut`: these are appended only through record!, which stamps
   the writer from where it is run, and the generic appends refuse them."
  (Operation occupy!
    "Start a driven session for a seat that has no occupant in the current generation, briefed from
     the seats reading."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:embodiment :map] [:seat :string]] :map]})
  (Operation replace!
    "Retire a seat's occupant of the current generation and occupy the seat afresh."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:embodiment :map] [:seat :string]] :map]})
  (Operation reseat!
    "On a newly opened generation: retire every seat occupant of an earlier generation — its Claude
     session stopped, its record archived — and keep the conductor's. The new generation's seats
     are occupied by whatever carries it, once it can be carried."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]})
  (Operation conductor!
    "Start the workstream's conductor: a driven session on a session record naming it the
     conductor. Refused while the workstream's conductor is live."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]})
  (Operation send!
    "Log a message from a seat's occupant or the conductor to a seat or the conductor, and hand it
     to the addressee's session through conduct, which gives a session's seat messages in the
     order sent."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:message :map]] :map]})
  (Operation record!
    "Append a `:tension`, `:accord`, `:withdrawal` or `:recut` to the workstream's ledger, its
     writer stamped from where it is run: a seat's occupant as its seat, the conductor as the
     conductor, and — for a `:recut` alone — any other session by its name, or a person outside
     every session; any other writer is refused."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:entry :map]] :map]})
  (Operation occupants
    "Each seat's live occupant, read from the session records naming it in the current generation."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:sequential :map]]})
  (Operation traffic
    "A generation's seat messages, oldest first."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:generation :int]] [:sequential :map]]})
  (Operation gate-verdict
    "Why a Bash command a seat's occupant runs must not run, or nil: a push publishing a
     description, or a PR title, that does not name the seat and its position among the seat's
     layers. Fails open, as the commit gate does."
    {:signature [:=> [:catn [:input :map]] [:maybe :string]]
     :delegates [commit-gate/checks commit-gate/push-range]}))
