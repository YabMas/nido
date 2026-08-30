(ns canvas.tasks.nido-runs
  "Self-spec: `tasks.nido-runs` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-runs
  "Bb task entry points for inspecting Run records."
  (Operation list-runs
    "bb nido:runs:list [:state <kw>] [:trigger <kw>] [:project <kw>]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation show
    "bb nido:runs:show <run-id>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
