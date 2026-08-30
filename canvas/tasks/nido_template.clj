(ns canvas.tasks.nido-template
  "Self-spec: `tasks.nido-template` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-template
  "bb task entry points for `nido-template`."
  (Operation init
    "Initialize a fresh template cluster for a project."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation refresh
    "Refresh the template cluster by running the project's declared"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation status
    "Show template status for a project."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation stop
    "Stop the template cluster (no-op when already stopped)."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation destroy
    "Delete the template cluster entirely."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
