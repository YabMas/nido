(ns canvas.claims.seatless-activation
  "The claims the seatless-activation design (ws-20261119-39caf3) commits nido to: a workstream
   activated after a plain start has its seat occupied by the session it was activated from, a pass
   re-seats every activated workstream a person works, and an instruction no occupant nido drives can
   take reaches the person as a blocker.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [carry! reseat! lane-conductor]]
            [canvas.coordinator.lane.seats :refer [held owed]]
            [canvas.coordinator.work :as work]))

(Claim activation-carries-its-session
  "work/activate!, given the session a person ran it from, carries that session once the workstream is activated: when the session is a live record of the workstream occupying no seat and not its conductor or chat, the current generation fixes no ids and the whole seat has no occupant, the session becomes the whole seat's occupant, marked carried by activation, and no conversation is started; otherwise nothing is carried. Activation from no session carries nothing, and neither does the activation a start makes of the session it is itself bringing up (the session-up path spawn and nido:workstream:start share), which that start's own carry! carries [carried-by-default]."
  {:about [work/activate! carry!] :evidence "round"})

(Claim activation-occupant-started-when-owed
  "An occupant carried by activation on which nido drives no conversation counts as holding its seat, so reseat! puts no other occupant in it. On a workstream reseat! reseats — the activation's own occupancy makes it carried — reseat! starts a conversation nido drives on such an occupant once a seat message is owed to it, and not while none is: its first instruction the occupant's brief followed by the messages then owed to it, which a start that succeeds records delivered under its token."
  {:about [held reseat!] :evidence "round"})

(Claim undelivered-instruction-is-raised
  "After each conduct pass's reseat! of a workstream, whatever reseat! decided and whatever the workstream's activation, pause or closure, each undelivered :instruction addressed to one of its current seats that no live session nido drives is owed is named by a :blocker for the person, which stores the message's id alone under :seat-message — one blocker per instruction, read by that field. The instruction's seat and generation are read from the seat-message log row that id names, not stored on the blocker; nido answers that blocker once the instruction is delivered, once a live session nido drives is owed it, or once that row's seat in its generation is no longer current."
  {:about [lane-conductor owed] :evidence "round"})

(Claim plain-start-activation-reproduced
  "The change lands with tests that fail on the code before it: a plain session started on a workstream, the workstream activated from that session, the conductor deciding an :instruct to the whole seat, and a conduct pass's reseat! leave the instruction recorded delivered to that session; and an undelivered instruction to a current seat no session nido drives is owed — on a workstream activated from no session, and on one whose occupant nido does not drive and reseat! does not start — leaves after the pass an open blocker naming that instruction, answered once it is delivered. The existing not-activated and unworked tests stay green."
  {:about [work/activate! reseat!] :evidence "round"})
