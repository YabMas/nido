(ns canvas.tasks.nido-help
  "Self-spec: `tasks.nido-help` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-help
  "bb task entry points for `nido-help`."
  (Operation show
    "Print a curated, grouped overview of nido tasks."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
