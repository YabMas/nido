(ns canvas.tasks.nido-ui
  "Self-spec: `tasks.nido-ui` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-ui
  "CLI entry point for the nido dashboard."
  (Operation start
    "Start the nido dashboard. Usage: bb nido:ui [:port 8800]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
