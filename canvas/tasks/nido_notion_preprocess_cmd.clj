(ns canvas.tasks.nido-notion-preprocess-cmd
  "Self-spec: `tasks.nido-notion-preprocess-cmd` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-notion-preprocess-cmd
  "bb task entry point for Notion ticket preprocessing."
  (Operation exit!
    "The `exit!` entry point."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation run
    "bb nido:notion:preprocess-ticket :page <id> :out <dir> [:budget 10m]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
