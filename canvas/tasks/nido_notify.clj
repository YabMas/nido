(ns canvas.tasks.nido-notify
  "Self-spec: `tasks.nido-notify` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-notify
  "Bb task entry point for nido's desktop notifications."
  (Operation install
    "Build the nido notifier app and send one test notification through it."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
