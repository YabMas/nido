(ns canvas.coordinator.source.slack-events
  "Self-spec: `nido.coordinator.source.slack-events` — a source plugin.

   The same three functions as every source. What differs is where the work happens: the
   connection is held between polls, so `start-instance!` opens it and `poll-once!` drains what
   it delivered — the daemon still drives the source only through its poll and stop pair."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.source.core :as source]
            [canvas.integration.slack :as slack]
            [fukan.common.typing.malli]))

(Module source-slack-events
  "The `:slack-events` source: hold the daemon's one Socket Mode connection, keep the reactions of
   one emoji, and emit one event per newly reacted message, keyed by its thread, wherever the bot
   has been added. Only the instance holding the connection emits."
  (Operation poll-once!
    "One iteration: take the reactions the connection delivered since the last poll, drop those
     seen within the retention age, resolve each to its thread, emit it, and return the state to persist."
    {:signature [:=> [:catn [:source-config :map] [:inbox :any] [:token :any] [:emit-fn :any]] :map]
     :delegates [slack/conversations-replies slack/normalise-thread]})
  (Operation start-instance!
    "Open the daemon's connection when no instance holds it, and answer with the poll and stop
     functions; an instance started while another holds it emits nothing and its state says it
     is refused. Stop closes the connection its instance holds."
    {:signature [:=> [:catn [:source-config :map] [:emit-fn :any] [:opts :map]] :map]
     :delegates [poll-once! slack/connect!]})
  (Operation register!
    "Register this plugin with the source registry, at load time."
    {:signature [:=> [:catn] :any]
     :delegates [source/register-source! start-instance!]}))
