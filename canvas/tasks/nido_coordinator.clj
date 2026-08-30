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
  (Operation run
    "The `run` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation status
    "The `status` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation halt
    "bb nido:halt [:note '...'] — pauses coordinator; existing Runs get SIGTERM."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation resume
    "bb nido:coordinator:resume — clears halted.edn so the daemon picks back up."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation up
    "bb nido:coordinator:up [:poll-ms <int>] — start the daemon."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation down
    "bb nido:coordinator:down [:force true] — stop the background daemon."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation install
    "bb nido:coordinator:install — write the LaunchAgent plist and start"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation uninstall
    "bb nido:coordinator:uninstall — bootout and remove the plist. Idempotent."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation restart
    "bb nido:coordinator:restart — restart the daemon via launchctl."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation logs
    "bb nido:coordinator:logs [:follow true] [:lines <n>]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
