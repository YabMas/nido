(ns canvas.tasks.nido-notion-views
  "Self-spec: `tasks.nido-notion-views` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-notion-views
  "Bb task entry points for Notion view registry validation."
  (Operation check-cmd
    "bb nido:notion:views:check :project <p> — validate the registry against the live DB."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
