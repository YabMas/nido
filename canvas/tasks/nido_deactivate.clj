(ns canvas.tasks.nido-deactivate
  "Self-spec: `tasks.nido-deactivate` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: resolve the workstream, ask the work plane, print."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.work :as work]
            [fukan.common.typing.malli]))

(Module nido-deactivate
  "The person's one act that takes nido's recipe off a session's workstream, for work too small
   to carry its records and review rounds."
  (Operation deactivate-cmd*
    "Deactivate the recipe on the workstream the cwd resolves to, or the one named, and say what
     changed. Exits 1 when none resolves."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [work/deactivate!]})
  (Operation deactivate-cmd
    "The `deactivate-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [deactivate-cmd*]}))
