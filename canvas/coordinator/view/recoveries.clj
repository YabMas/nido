(ns canvas.coordinator.view.recoveries
  "Self-spec: `nido.coordinator.view.recoveries` — session recovery, as something a person watches.

   Pure read model: handed records, it derives. NO READS AND NO WRITES — the work plane gathers
   what recovery keeps and hands it here, so the reading is testable without a ledger and cannot
   disturb what it reads."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.source.start-failures :as sf]
            [fukan.common.typing.malli]))

(Module view-recoveries
  "Session recovery as a monitoring reading: which causes are shown, the state each is in, what a
   row says was found and done, and which facts make up the activity feed.

   The states are the recovery source's decisions, asked rather than restated — owed, in flight,
   and when a cause is next due — so the page cannot call a cause waiting that the source is
   about to fire."
  (Operation cause-state
    "Where one cause stands: needs-you, recovering, waiting (with when it is next due), owed,
     restored or dismissed."
    {:signature [:=> [:catn [:cause :string] [:ctx :map]] :map]
     :delegates [sf/in-flight? sf/due-at sf/failed-in-a-row]})
  (Operation feed-page
    "The page of every recovery fact that follows a feed position — the newest when there is none
     — at most eighty events, naming the position of the next older page when events remain."
    {:signature [:=> [:catn [:records :map] [:from [:maybe :string]]] :map]})
  (Operation overview
    "Counts, one row per live or recently settled cause, and the activity feed, from kept failures
     carrying their causes, the source's recovery readings carrying their entries, a time and a
     pacing."
    {:signature [:=> [:catn [:inputs :map]] :map]
     :delegates [cause-state feed-page sf/owed]}))
