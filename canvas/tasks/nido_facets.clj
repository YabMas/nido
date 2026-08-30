(ns canvas.tasks.nido-facets
  "Self-spec: `tasks.nido-facets` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-facets
  "bb task entry points for classification-facet maintenance."
  (Operation refresh-cmd
    "bb nido:facets:refresh :project <p> [:ws <ws-id>]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
