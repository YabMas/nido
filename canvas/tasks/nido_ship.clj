(ns canvas.tasks.nido-ship
  "Self-spec: `tasks.nido-ship` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-ship
  "CLI entrypoint: hand the current session's branch to nido's merge lane."
  (Operation enqueue-ship!
    "Write a :ship envelope. Returns the envelope path."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation ship
    "`nido ship [:project <p> <session>]` — enqueue the resolved session's branch"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
