(ns canvas.claims.run-seats
  "The claims the run-seats design commits nido to: a pass starts nothing on a workstream no person
   works in, and a seat occupant nido created is not a session a person works in.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor reseat! conductors!]]
            [canvas.coordinator.lane.seats :refer [occupy!]]
            [canvas.coordinator.record.session :refer [person?]]))

(Claim reseated-only-when-worked
  "reseat! retires, occupies or escalates nothing on a workstream that is not worked, as conductors! reads worked — no session a person works in recorded on it, and no hand-back but an occupant nido created ending a turn logged on it, since conductors began — whatever its records once seated; on an open, carried, worked workstream it re-seats as before."
  {:about [reseat! conductors!] :evidence "test"})

(Claim nido-occupant-is-no-person
  "person? is false of a session record whose seat is not marked :carried — one occupy! created to fill a seat, live or archived — as it is of the conductor's and a Run's; a record a person's start carried, or one released from its seat, is still a person's. The end of a turn nido gave such an occupant, handed back to the conductor, is no work to conductors!, so neither the occupant's record nor its turns make its workstream worked."
  {:about [person? occupy! conductors!] :evidence "test"})

(Claim named-workstreams-stay-inert
  "On the first conduct pass with this change in, none of ws-20261029-0da15b, ws-20261028-228525, ws-20261029-1285ae, ws-20261029-7ccd12, ws-20261028-69a666, ws-20261028-8696ab, ws-20261028-1d4787, ws-20261029-87fd4d, ws-20261028-58b8c7, ws-20261029-e1f5f9, ws-20261029-63cbeb, ws-20261028-2cba8e, ws-20261028-aee532, ws-20261029-36e721, ws-20261029-1da42e is worked, so the pass starts no session and no conductor on any of them and retires the conductor of any that is running: each holds a carried, archived Run record, a <ws>--work-g1 occupant whose seat is not marked :carried and a conductor record, and no hand-back but that occupant's turn end, if any."
  {:about [reseat! conductors!] :evidence "round"})

(Claim lands-through-nido
  "The change lands as nido's own work does: bb nido:test is green and bb nido:land:check prints no REFUSED line on the commit main is fast-forwarded to, main is pushed, bb nido:land:record answers ok, and the root ~/Code/nido checkout is then rebased onto main."
  {:about [lane-conductor] :evidence "round"})

(Claim regression-reproduces-run-seat
  "The change lands with conductor_test's a-seat-only-a-run-held-is-not-reseated, which builds the shape ws-20261028-1d4787 was in — a workstream whose ledger holds a review-analysis entry and nothing else, and whose Run session was carried into its whole seat and then archived — and asserts that the next reseat! starts no session and writes no record and that conductors! starts no conductor; and with an-occupant-nido-started-does-not-make-its-workstream-worked, which adds the shape ws-20261028-aee532 was in — a <ws>--work-g1 occupant occupy! created, its turn end handed back — and asserts the same, and that a person's hold on that occupant handed back makes it worked. Both fail against the code at c21de6de and pass with this change."
  {:about [reseat! conductors!] :evidence "test"})
