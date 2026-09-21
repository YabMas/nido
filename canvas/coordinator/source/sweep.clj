(ns canvas.coordinator.source.sweep
  "Self-spec: `nido.coordinator.source.sweep` — the :improvement-sweep source plugin."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.source.core :as source]
            [fukan.common.typing.malli]))

(Module source-sweep
  "The :improvement-sweep source: at most one fire per poll — a plan for today when there is none
   and something is owed, else the next :land claim of today's plan that has not been attempted.

   ONE SLOT for both fires, held by any open workstream carrying the :improvement adapter and
   released by its close and nothing weaker. Being held decides whether the poll emits; it does
   not decide what the poll reports, so the state it returns says what is owed either way."
  (Operation day-of "The calendar day of an ISO timestamp."
    {:signature [:=> [:catn [:iso :string]] :string]})
  (Operation plan-for "Today's plan among a project's plans, or nil."
    {:signature [:=> [:catn [:plans [:vector :map]] [:day :string]] [:maybe :map]]})
  (Operation next-claim
    "The first :land claim of a plan whose claim workstream has never existed, with its index, or
     nil when the plan is discharged."
    {:signature [:=> [:catn [:plan :map] [:plan-ws-id :string] [:attempts [:vector :map]]] [:maybe :map]]})
  (Operation claim-payload "The envelope payload for one claim's implementation."
    {:signature [:=> [:catn [:claim :map] [:plan-ws-id :string] [:plan-seq :int] [:pick :map]] :map]})
  (Operation plan-payload "The envelope payload for a day's planning pass."
    {:signature [:=> [:catn [:day :string] [:owed-count :int]] :map]})
  (Operation poll-once!
    "One poll: emit at most one fire, and return the state to persist — who holds the sweep, the
     day, whether it is planned, how many proposals are owed and what was emitted."
    {:signature [:=> [:catn [:source-config :map] [:emit-fn :any]] :map]
     :delegates [day-of plan-for next-claim claim-payload plan-payload]})
  (Operation start-instance!
    "Start one configured instance: its poll writes what poll-once! returns as the source's state."
    {:signature [:=> [:catn [:source-config :map] [:emit-fn :any]] :map]
     :delegates [poll-once! source/config-hash source/write-state!]})
  (Operation register! "Register this plugin with the source registry, at load time."
    {:signature [:=> [:catn] :any]
     :delegates [source/register-source! start-instance!]}))
