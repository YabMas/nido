(ns canvas.tasks.nido-coordinator-source
  "Self-spec: `tasks.nido-coordinator-source` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-coordinator-source
  "Bb task entry points for source-instance inspection + reset (Stage 5)."
  (Operation list-cmd
    "bb nido:coordinator:source:list -- one row per source-instance state file."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation reset-cmd
    "bb nido:coordinator:source:reset :type <source-type> :database <id> [:view <name>] [:poll <dur>]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
