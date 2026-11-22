(ns canvas.core.writer
  "Self-spec: `nido.core.writer`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-writer
  "The single writer: one thread takes commands from its queue — a surface's input
   ahead of background commands, each in arrival order — decides each against current state
   unless its key is already committed [commands-are-keyed], commits a batch of bounded size as
   one durable unit, and only then answers callers and publishes the change.

   HIDES batching, group commit, ordering and the output gate. Idle, it blocks on its queue."
  (Operation start
    "Start the one writer thread over a store; idle, it blocks on its queue."
    {:signature [:=> [:catn [:store :map] [:opts :map]] :map]})
  (Operation submit!
    "Queue a keyed command and answer a promise of its outcome, delivered only once that outcome is durable."
    {:signature [:=> [:catn [:writer :map] [:envelope :map]] :any]})
  (Operation stop!
    "Finish the batch in progress, refuse what is still queued, and end the thread."
    {:signature [:=> [:catn [:writer :map]] :nil]}))
