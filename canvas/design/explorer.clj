(ns canvas.design.explorer
  "Self-spec: `nido.design.explorer` — fukan's explorer, run as a process nido owns."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.design.check :as check]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module design-explorer
  "fukan's explorer over a declaration in a directory: started, located, stopped.

   HIDES how the explorer is asked for — the project's fukan command with the `explore` verb — and
   how its port is learned: port 0 and the one line it announces once listening, which is also the
   only readiness signal there is. Like `design-check` it never reads the model; fukan draws it.
   A directory rather than a revision, for the reason `diff` takes one: the caller holds the
   revision and materializes it."
  (Operation start!
    "Start an explorer over a directory's declaration and answer its handle at once, or nil when
     the directory declares no design. It dies with the process that started it."
    {:signature [:=> [:catn [:project-name ProjectName] [:dir :string]] [:maybe :map]]
     :delegates [check/design-of]})
  (Operation address
    "Where an explorer is as far as it has said: starting, serving on a port, or failed with why."
    {:signature [:=> [:catn [:handle [:maybe :map]]] :map]})
  (Operation stop!
    "Stop an explorer; one that already exited is a no-op."
    {:signature [:=> [:catn [:handle [:maybe :map]]] :nil]}))
