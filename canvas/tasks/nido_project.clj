(ns canvas.tasks.nido-project
  "Self-spec: `tasks.nido-project` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-project
  "bb task entry points for `nido-project`."
  (Operation init
    "Create the ~/.nido/ skeleton directory structure."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation add
    "Register a project: <name> <directory>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation list-cmd
    "List registered projects."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation remove-cmd
    "Unregister a project: <name>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
