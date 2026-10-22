(ns canvas.coordinator.record.seats
  "Self-spec: `nido.coordinator.record.seats` — the seats that carry a workstream, read off its records."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.generation :as generation]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module record-seats
  "A workstream as its seats: the ids its current generation fixes, which an embodiment, when one
   carries the workstream, gives an occupant.

   HIDES how that picture is derived from the records: a seat is an id the current generation
   fixes, as record-generation reads it, whether or not a design of the generation states it yet,
   with its interface from the governing design once one does, and is never stored; whether a
   tension is open is derived from an `:accord` by its addressee or a `:withdrawal` by its raiser
   citing it, and a tension addressed to an id the generation does not fix is the conductor's. It
   names no sort of element itself and takes no selection from its caller — which ids are seats is the
   generation's — so a caller asks for seats and tensions and never names the entries they are
   read from."
  (Operation seats
    "The current generation's seats: each id the generation fixes, with the interface its governing
     design gives that element — none while no design of the generation states it — and the open
     tensions addressed to it. Empty while the generation fixes none."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:sequential :map]]
     :delegates [generation/fixed-ids standing/governing-design]})
  (Operation tensions
    "The current generation's tensions, each with the seat raising it, its addressee, the ids at
     issue and whether it is open or settled, and by which entry."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:sequential :map]]
     :delegates [generation/fixed-ids generation/current-span]})
  (Operation inherited
    "What the current generation inherited from the one before it, as its `:generation` entry
     carries it; nil in a first generation."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [generation/opened-by]}))
