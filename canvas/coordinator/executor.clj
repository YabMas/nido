(ns canvas.coordinator.executor
  "Self-spec: `nido.coordinator.executor` — the slot-based scheduler."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.vocabulary :refer [RunId]]
            [fukan.common.typing.malli]))

(Module coordinator-executor
  "The wait queue and the slots work is promoted into.

   Two caps, and they compose rather than override: a global concurrency cap, and a per-trigger
   `:max-in-flight`. An uncapped unit bypasses the global cap and still respects its trigger's —
   which is what lets an urgent lane through without letting one trigger monopolise the machine.

   The run id is OPAQUE here. The executor schedules units of work and knows nothing about runs,
   which is why it can also schedule turns against a run already spawned."
  (Operation configure!
    "Set the global concurrency cap. Pure configuration — starts nothing."
    {:signature [:=> [:catn [:opts :map]] :any]})
  (Operation clear!
    "Reset the queue and the in-flight tally. Test-only, and does not cancel what is already
     running — a reset that killed live work would be a different function."
    {:signature [:=> [:catn] :any]})
  (Operation submit!
    "Queue a unit of work.

     Genuinely POLYMORPHIC rather than a defaults chain, which is why it is spelled
     `[:function …]`: the map arity is the real contract and the positional ones are
     conveniences over it, and they skip arity four rather than growing one argument at a time."
    {:signature [:function
                 [:=> [:catn [:opts :map]] :any]
                 [:=> [:catn [:run-id RunId] [:priority :int]] :any]
                 [:=> [:catn [:run-id RunId] [:priority :int] [:uncapped? :boolean]] :any]
                 [:=> [:catn [:run-id RunId] [:priority :int] [:uncapped? :boolean]
                             [:trigger :keyword] [:max-in-flight [:maybe :int]]] :any]]})
  (Operation turn-id
    "The queue identity of one turn against a run — distinct from the run's own id, because a
     run may be scheduled more than once."
    {:signature [:=> [:catn [:run-id RunId] [:n :int]] :string]})
  (Operation submit-turn!
    "Queue a body of work against a run that has already been spawned."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [turn-id submit!]})
  (Operation driven?
    "Whether anything in this process has ever ticked the executor — how a caller tells a live
     daemon from a bare JVM that merely loaded the namespace."
    {:signature [:=> [:catn] :boolean]})
  (Operation tick!
    "One poll: reap what finished, then promote what the caps allow."
    {:signature [:=> [:catn [:on-spawn :any] [:in-flight-by-trigger :any]] :any]})
  (Operation snapshot
    "A read-only view of the queue for the TUI. Unlocked on purpose — a consistent-enough
     reading now beats a perfectly consistent one that made the scheduler wait for a renderer."
    {:signature [:=> [:catn] :map]}))
