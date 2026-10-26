(ns canvas.coordinator.lane.seats
  "Self-spec: `nido.coordinator.lane.seats` — filling, replacing and re-seating a workstream's seats."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [WorkstreamId SessionName]]
            [canvas.coordinator.lane.conduct :as conduct]
            [canvas.coordinator.record.seats :as rseats :refer [SeatId]]
            [canvas.coordinator.record.workstream :as ws]
            [canvas.session.commit-gate :as commit-gate]
            [canvas.session.lifecycle :as lifecycle]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-seats
  "Who occupies each seat of a workstream, and what a new occupant is told.

   An embodiment is how a seat is named and its occupant briefed; it selects nothing, since the
   seats are what the seats level reads off the current generation. The whole seat, which a
   generation fixing no ids has, is carried by this module's own default embodiment; one
   occupancy policy serves it and every other embodiment alike.

   HIDES the occupancy policy, for any embodiment: every seat of a workstream an embodiment carries
   is occupied by a session of its own, and a workstream no embodiment carries has no occupants,
   the conductor being a separate session that never covers one, all of them on the workstream's
   one runtime; how an occupant is started, stopped and replaced through conduct, in its own working
   copy brought up first, whose launch baseline carries the commit gate, whose seat hook refuses a commit or PR title not
   naming its seat and its position among the seat's layers; and
   how a first brief is built — from the seats reading and what the embodiment adds, never from
   another session's account. Re-seating retires every seat occupant whose seat the seats reading
   no longer answers — releasing, rather than archiving, one carried from the start of a person or
   a Run — and keeps the conductor's, which is retired only with its workstream and started again on the
   same record if the workstream reopens. Also where occupants, the conductor and seat traffic are
   kept: an occupant is a session record naming its seat and the generation it occupies it in, and
   only re-seating reads an earlier generation's; the conductor is a session record of the
   workstream naming it the conductor, never a seat, whatever the generation; seat messages are a
   per-workstream log beside the ledger, keyed by generation, that no round reads, and what a seat
   or the conductor is still owed is derived from it, never held by a session; each delivery is
   logged with the token conduct gave it and its conversation. And who wrote a
   `:tension`,
   `:accord`, `:withdrawal` or `:recut`: these are appended only through record!, which stamps
   the writer from where it is run, and the generic appends refuse them."
  (Operation occupy!
    "Start a driven session for a seat that has no occupant in the current generation, briefed from
     the seats reading — on a record the embodiment names, or on the live session record of the
     workstream the caller names, which then occupies the seat, started unless nido already drives
     it, on the model the embodiment names when it names one — once the embodiment's refusal passes
     against that generation, in the same transaction."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:embodiment :map] [:seat SeatId]
                      [:session [:? SessionName]]] :map]
     :delegates [rseats/seats rseats/tensions rseats/inherited conduct/retire! conduct/start!]})
  (Operation replace!
    "Retire a seat's occupant of the current generation and occupy the seat afresh, once the
     embodiment's refusal passes against that generation, in the same transaction."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:embodiment :map] [:seat SeatId]] :map]
     :delegates [rseats/seats rseats/tensions rseats/inherited conduct/retire! conduct/start!]})
  (Operation reseat!
    "Retire every seat occupant whose seat the current seats reading does not answer — one of an
     earlier generation, or the whole seat's once its generation fixes ids — its Claude session
     stopped, its record archived; keep the conductor's. An occupant occupy! took on at its
     caller's naming is not archived: it no longer names the seat and stays on its workstream — a
     session a person started released, nido no longer driving it, and a Run's left to its Run. The
     current seats are occupied by whatever carries the workstream, once it can be carried."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [rseats/seats conduct/retire! conduct/release!]})
  (Operation drive!
    "Start a driven conversation, given for no Run and occupying no seat, on a live session record
     already up, on the model given when one is — for a start that could not carry its session."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]
                      [:prompt :string] [:model [:? [:maybe :string]]]] :any]
     :delegates [conduct/start!]})
  (Operation conductor!
    "Start the workstream's conductor on the brief its caller gives: a driven session on the one
     session record naming it the conductor, in its own working copy brought up first. Refused while the conductor's session is running; on a record whose session is not
     running — gone from the listing, or retired with its workstream's close — it is started again
     on the same record."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:brief :string]] :map]
     :delegates [conduct/live-states conduct/retire! conduct/start!]})
  (Operation retire-conductor!
    "Retire the workstream's conductor by reconciling its two resources, each whatever the other's
     state: any Claude process of its record stopped, and its session brought down if it is up —
     its record kept for a start if the workstream reopens. Idempotent: it does nothing only when
     neither needs it, and no conductor record means nothing to retire."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [conduct/retire! lifecycle/status lifecycle/down!]})
  (Operation send!
    "Log a message from a seat's occupant to a seat the seats reading answers, or to the conductor
     by role; any other addressee is refused. No session need be live to be sent one. Refused from
     the conductor, whose word reaches a seat only as a decided instruction (post!)."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:message :map]] :map]
     :delegates [rseats/seats]})
  (Operation default-embodiment
    "The whole seat's embodiment, as the data occupancy takes: its occupant's session named for
     the workstream, briefed to work the recipe's next stage — unless it is a person's step, which
     it leaves to the person and hands back naming — and hand back when the stage ends or it needs a
     decision; refusing a generation that fixes ids."
    {:signature [:=> [:catn] :map]})
  (Operation post!
    "Log a message nido writes — a hand-back to the conductor, or a decided instruction to a seat —
     addressed as send! addresses, at most once per key the caller gives: a second post under the
     same key logs nothing and answers the first."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:message :map]] :map]
     :delegates [rseats/seats]})
  (Operation post-held!
    "post!, for a caller already holding the workstream's ledger append lock with the workstream
     read under it — so an entry it appends under that lock and the messages it posts are one
     step, which no generation's opening or design approval falls between."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:w ws/Workstream] [:message :map]] :map]
     :delegates [rseats/seats]})
  (Operation record!
    "Append a `:tension`, `:accord`, `:withdrawal` or `:recut` to the workstream's ledger, its
     writer stamped from where it is run: a seat's occupant as its seat, the conductor as the
     conductor, and — for a `:recut` alone — any other session by its name, or a person outside
     every session; any other writer is refused, and so is a `:tension` addressed to anything but a
     seat the seats reading answers or the conductor by role."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:entry :map]] :map]
     :delegates [rseats/seats ws/append-stamped!]})
  (Operation occupants
    "Each seat's live occupant, read from the session records naming it in the current generation."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:sequential :map]]})
  (Operation held
    "The current generation's seats whose occupant holds a session — started, or a start in
     flight — not those whose occupant's record is all a failed launch left."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:set SeatId]]})
  (Operation traffic
    "A generation's seat messages, oldest first, each delivered one with the token it was given
     under."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:generation :int]] [:sequential :map]]})
  (Operation handed-back-since?
    "Whether a hand-back to the conductor was logged on the workstream at or after an ISO instant —
     read from the message log alone, with no ledger read."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:since :string]] :boolean]})
  (Operation owed
    "The seat messages a session is owed, oldest first, with no delivery recorded: those the log
     addresses, in the current generation, to the seat it occupies — or, when it conducts, those
     addressed to the conductor in any generation, so a generation's opening drops none, and those
     addressed to the current generation's whole seat once it fixes ids; for the conductor, a
     hand-back no decision answers counts as undelivered until its running conversation was given
     it, so one a stopped conversation took is owed again. An occupant's successor is owed what it
     was."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] [:sequential :map]]})
  (Operation delivered!
    "Record that a seat message was given to its addressee's session under a token, and to the
     conversation whose outstanding instruction carries that token — read from the session's
     record, never passed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:id :string] [:token :string]] :any]})
  (Operation gate-verdict
    "Why a Bash command a seat's occupant runs must not run, or nil: a push publishing a
     description, or a PR title, that does not name the seat and its position among the seat's
     layers. Nil for the whole seat's occupant, whose layers name no seat. Fails open only on
     whether the session occupies a seat; a seat's publication it
     recognises but cannot read is refused. Best-effort: a publication it does not recognise as
     one — a script, a wrapper, a command built at run time — goes unseen."
    {:signature [:=> [:catn [:input :map]] [:maybe :string]]
     :delegates [commit-gate/publications commit-gate/push-range]}))
