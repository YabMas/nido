(ns canvas.tasks.nido-coordinator
  "Self-spec: `tasks.nido-coordinator` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-coordinator
  "Bb task entry points for the coordinator daemon."
  (Operation status
    "The `status` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation sweep-hold-lines
    "The status lines for what holds each improvement sweep; a stuck hold names the close that releases it, and an unreadable reading is said rather than read as no hold."
    {:signature [:=> [:catn [:holds [:maybe [:vector :map]]]] [:vector :string]]})
  (Operation halt
    "bb nido:halt [:note '...'] — pauses coordinator; existing Runs get SIGTERM."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation resume
    "bb nido:coordinator:resume — clears halted.edn so the daemon picks back up."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
