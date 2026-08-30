(ns canvas.coordinator.view.tickets
  "Self-spec: `nido.coordinator.view.tickets` — the tickets screen, read-only.

   Pure read model: read, classify, render. NO WRITES — which is what lets a surface show the
   work plane without being able to disturb it."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.tickets :as tickets :refer [Ticket]]
            [fukan.common.typing.malli]))

(Module view-tickets
  "The tickets screen: every ticket, bucketed by lifecycle stage and ordered by activity."
  (Operation read-all-tickets
    "Every ticket on record."
    {:signature [:=> [:catn] [:vector Ticket]]})
  (Operation classify
    "Which lifecycle bucket a ticket is in."
    {:signature [:=> [:catn [:ticket Ticket]] :keyword]})
  (Operation last-activity
    "The best timestamp to order a ticket by — the latest thing that happened to it, not when
     it was created."
    {:signature [:=> [:catn [:m Ticket]] [:maybe :string]]})
  (Operation grouped-tickets
    "Tickets partitioned into display groups, most recently active first."
    {:signature [:=> [:catn [:all [:vector Ticket]]] :map]
     :delegates [classify last-activity]})
  (Operation format-row
    "One ticket as a display line."
    {:signature [:=> [:catn [:ticket Ticket]] :string]}))
