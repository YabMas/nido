(ns canvas.tasks.nido-work
  "Self-spec: `tasks.nido-work` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-work
  "bb-task entrypoint: boot an interactive agent in the current worktree with"
  (Operation work-cmd*
    "Resolve the session for cwd and assemble the interactive agent invocation."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation work
    "The `work` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
