(ns canvas.coordinator.record.seats
  "Self-spec: `nido.coordinator.record.seats` — the seats that carry a workstream, read off its records."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.generation :as generation]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.coordinator.report.model :as model]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind SeatId
  "A seat's id: the id of an element a generation fixes (a string), or — for the whole seat of a
   generation fixing none — the generation's own number (an int)."
  [:or :string :int])

(Module record-seats
  "A workstream as its seats: one per id its current generation fixes or, while it fixes none, one
   seat holding the generation's whole design — which an embodiment, when one carries the
   workstream, gives an occupant. A generation always has a seat.

   HIDES how that picture is derived from the records: a seat is an id the current generation
   fixes, as record-generation reads it, whether or not a design of the generation states it yet,
   with its interface from the governing design once one does — or, while it fixes none (no
   design marks any, or its opening carries none), the whole seat, whose id is the generation's own
   and never an element's — and is never stored; whether a tension is open is derived from an
   `:accord` by its addressee or a `:withdrawal` by its raiser citing it. A tension is addressed to
   a seat this reading answers or to the conductor by role, never to the conductor by default —
   save one addressed to the generation's whole seat once the generation fixes ids and the whole
   seat is gone, which is the conductor's. It
   names no sort of element itself and takes no selection from its caller — which ids are seats is the
   generation's — so a caller asks for seats and tensions and never names the entries they are
   read from."
  {:child [SeatId]}
  (Operation seats
    "The current generation's seats: each id the generation fixes, with the interface its governing
     design gives that element — none while no design of the generation states it — and the open
     tensions addressed to it. While the generation fixes none, the whole seat alone — its id the
     generation's, its interface the governing design's whole."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:sequential :map]]
     :delegates [generation/current generation/fixed-ids standing/governing-design
                 workstream/entry-at-seq model/overlay]})
  (Operation tensions
    "The current generation's tensions, each with the seat raising it, its addressee, the ids at
     issue and whether it is open or settled, and by which entry."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:sequential :map]]
     :delegates [generation/current generation/fixed-ids generation/current-span]})
  (Operation inherited
    "What the current generation inherited from the one before it, as its `:generation` entry
     carries it; nil in a first generation."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [generation/opened-by]}))
