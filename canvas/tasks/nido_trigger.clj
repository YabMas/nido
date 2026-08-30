(ns canvas.tasks.nido-trigger
  "Self-spec: `tasks.nido-trigger` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-trigger
  "Bb task entry points for firing manual triggers and listing trigger"
  (Operation fire
    "bb nido:trigger:fire :project <p> <trigger-name> :url <v> :ticket-id <v> ..."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation list-triggers
    "bb nido:trigger:list :project <p>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation enable
    "bb nido:trigger:enable :project <p> <trigger-name>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation disable
    "bb nido:trigger:disable :project <p> <trigger-name> [:note <str>]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
