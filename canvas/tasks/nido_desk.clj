(ns canvas.tasks.nido-desk
  "Self-spec: `tasks.nido-desk` — a bb task entry point.

   A COMPOSITION ROOT for the desk's CLI verbs: running it on the person's machine, streaming
   effects to it from nido's host, and declaring where the person sits."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.platform.desk :as desk]
            [fukan.common.typing.malli]))

(Module nido-desk
  "Bb task entry points for the desk: parse the args, call the desk, report."
  (Operation run-cmd
    "`bb nido:desk :host <ssh-host>`: hold the desk's connection and perform what it asks."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [desk/run!]})
  (Operation stream-cmd
    "`bb nido:desk:stream`: what a desk runs on nido's host over ssh."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [desk/stream!]})
  (Operation person-cmd
    "`bb nido:desk:person :here|:elsewhere`: declare where the person sits relative to this host."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [desk/set-person!]}))
