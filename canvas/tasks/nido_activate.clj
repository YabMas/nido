(ns canvas.tasks.nido-activate
  "Self-spec: `tasks.nido-activate` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: resolve the workstream, ask the work plane, print."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.work :as work]
            [fukan.common.typing.malli]))

(Module nido-activate
  "The person's one act that hands a workstream to the core from a terminal: its unit opened, in a
   session the core provisions. It adopts no session — not even the one it runs in."
  (Operation activate-cmd*
    "Hand the workstream the cwd resolves to, or the one named, to the core. Exits 1 when none
     resolves or the core refuses."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [work/activate!]})
  (Operation activate-cmd
    "The `activate-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [activate-cmd*]}))
