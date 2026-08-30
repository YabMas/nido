(ns canvas.coordinator.source.notion
  "Self-spec: `nido.coordinator.source.notion` — a source plugin.

   Every source plugin is the same three functions, and that IS the contract: `register!` at load
   time, `start-instance!` to hand back a poll/stop pair, `poll-once!` for one iteration. A new
   source is a namespace implementing these and nothing in the daemon changes."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.source.core :as source]
            [fukan.common.typing.malli]))

(Module source-notion
  "The `:notion-view` source: poll a database through the views registry, diff against the last snapshot, emit one event per changed page."
  (Operation poll-once!
    "One iteration: read the prior state, ask the outside world, emit what is new, return the
     state to persist. Separated from the loop so an iteration can be tested without one."
    {:signature [:=> [:catn [:source-config :map] [:token :any] [:emit-fn :any]] :map]})
  (Operation start-instance!
    "Start one configured instance, answering with its poll and stop functions."
    {:signature [:=> [:catn [:source-config :map] [:emit-fn :any] [:opts :map]] :map]
     :delegates [poll-once!]})
  (Operation register!
    "Register this plugin with the source registry, at load time."
    {:signature [:=> [:catn] :any]
     :delegates [source/register-source! start-instance!]}))
