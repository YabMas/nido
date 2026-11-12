(ns canvas.coordinator.source.drain
  "Self-spec: `nido.coordinator.source.drain` — the :drain source plugin."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]))

(Module source-drain
  "Polls open drains: takes census readings by running the current recipe's counter against the
   project's main and parsing its output — the one place that output is trusted — each reading
   citing the recipe whose counter it ran, and fires at most one thing per drain per poll, within
   the drain's in-flight cap: the start of a slice the coordinator planned and no start has
   consumed, a landing, or — when the drain's census reading fails — that failure, naming the drain
   and its cause, which lane-drain consumes as a brake. It plans nothing itself — the coordinator plans, this source schedules."
  (Operation landed?
    "Whether slice workstream `slice-ws-id` has landed: its ledger records a :merged, or it was closed done. What the source's scheduling, lane-drain's closing and the drains page read as landed."
    {:signature [:=> [:catn [:project ProjectName] [:slice-ws-id :any]] :any]})
  (Operation open-drains
    "The ids of `project`'s drains that are not closed."
    {:signature [:=> [:catn [:project ProjectName]] :any]})
  (Operation poll-once!
    "One poll over the project's open drains: take each one's census, fire at most one thing for it, and answer the state to persist. A drain whose poll throws is logged and skipped."
    {:signature [:function [:=> [:catn [:source-config :any] [:emit-fn :any]] :any] [:=> [:catn [:source-config :any] [:emit-fn :any] [:run :any]] :any]]})
  (Operation read-counter
    "A counter's printed `out` as a census reading {:remaining n :groups [...]}, or {:failed cause} with cause :unreadable (not EDN) or :invalid (EDN that is not a reading). `:groups` may be left out and reads as []."
    {:signature [:=> [:catn [:out :any]] :any]})
  (Operation register!
    ""
    {:signature [:=> [:catn] :any]})
  (Operation run-counter
    "Run `command` in `dir` under bash and read what it printed: a reading, or {:failed cause} with :counter-failed when it could not run, exited non-zero or ran past the timeout."
    {:signature [:=> [:catn [:dir :any] [:command :any]] :any]})
  (Operation take-census!
    "Run drain `ws-id`'s current recipe's counter against the project's main checkout and append the reading, citing the recipe — unless it reads the same as the newest under that recipe. `run` (default `run-counter`) is the injection seam a test substitutes. Answers {:census seq} for an appended reading, {:unchanged seq} for a repeated one, {:failed cause} for a failed one, and {:failed :no-recipe} for a drain with none."
    {:signature [:function [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any] [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:run :any]] :any]]}))
