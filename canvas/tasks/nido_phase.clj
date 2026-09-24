(ns canvas.tasks.nido-phase
  "Self-spec: `tasks.nido-phase` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-phase
  "bb task entry points for a phased workstream."
  (Operation advance-cmd
    "bb nido:phase:advance :project <p> (:ws-id <id> | :ref <ref>) :evidence <what was observed>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
