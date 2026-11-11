(ns canvas.tasks.nido-activate
  "Self-spec: `tasks.nido-activate` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: resolve the workstream, ask the work plane, print."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.work :as work]
            [fukan.common.typing.malli]))

(Module nido-activate
  "The person's one act that hands a plain session's workstream to nido's recipe.

   Run from inside the session, its output is the instruction to the agent that ran it: nido
   drives no conversation of a plain session, so the verb's own output is how the activation
   reaches the conversation that holds the understanding the records are written from."
  (Operation activate-cmd*
    "Activate the recipe on the workstream the cwd resolves to, or the one named, and print the
     instruction for the session's agent. Exits 1 when none resolves or it is closed."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [work/activate!]})
  (Operation activate-cmd
    "The `activate-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [activate-cmd*]}))
