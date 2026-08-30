(ns canvas.tasks.nido-design
  "Self-spec: `tasks.nido-design` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-design
  "`bb nido:design:check` — does this worktree's code stand up the project's declared design?"
  (Operation coords
    "[project worktree] for `cwd`: a nido session's if cwd is inside one, otherwise the project's"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation check
    "Report the design status of the worktree at `:cwd` (default: here). Returns the exit code."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation cmd
    "bb entry point: exits non-zero on a refusal, so a recipe that runs it before the push stops"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
