(ns canvas.tasks.nido-seats
  "Self-spec: `tasks.nido-seats` — bb task entry points for a workstream's seats.

   A COMPOSITION ROOT for the seat verbs: parse the arguments, call occupancy or the strata
   embodiment, print. Who is writing is never an argument — record! and send! stamp it from the
   session the task runs in."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.embodied-strata :as embodied]
            [canvas.coordinator.lane.seats :as seats]
            [fukan.common.typing.malli]))

(Module nido-seats
  "Carry a workstream by its strata, occupy or replace a seat, start the conductor, record a
   tension, accord, withdrawal or re-cut, send a seat message, and show who occupies what."
  (Operation carry*
    "Carry the workstream by its strata."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [embodied/carry!]})
  (Operation occupy*
    "Occupy one seat under the strata embodiment."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [embodied/occupy!]})
  (Operation replace*
    "Replace one seat's occupant."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [embodied/replace!]})
  (Operation conductor*
    "Start the workstream's conductor."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [seats/conductor!]})
  (Operation record*
    "Record a tension, accord, withdrawal or re-cut, its writer stamped from where it runs."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [seats/record!]})
  (Operation send*
    "Send a seat message from the seat or conductor this runs in."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [seats/send!]})
  (Operation show*
    "The current occupants, and a generation's seat messages."
    {:signature [:=> [:catn [:opts :map]] :any] :delegates [seats/occupants seats/traffic]})
  (Operation carry-cmd "bb nido:seats:carry." {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation occupy-cmd "bb nido:seats:occupy." {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation replace-cmd "bb nido:seats:replace." {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation conductor-cmd "bb nido:seats:conductor." {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation record-cmd "bb nido:seats:record." {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation send-cmd "bb nido:seats:send." {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation show-cmd "bb nido:seats:show." {:signature [:=> [:catn [:args [:* :any]]] :any]}))
