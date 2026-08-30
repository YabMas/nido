(ns canvas.tasks.nido-followup
  "Self-spec: `tasks.nido-followup` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-followup
  "Bb task entry points for the personal follow-up DB — the horizontal"
  (Operation add
    "File a spin-out in the follow-up DB and print its ref."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation list-cmd
    "Print open follow-ups, worst-decay first. `:status <s>` reads another band."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation check-cmd
    "Validate the configured property names + vocabularies against the live DB."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
