(ns canvas.tasks.nido-reclaim
  "Self-spec: `tasks.nido-reclaim` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-reclaim
  "Bb task entry point for reclaiming orphaned per-instance state dirs."
  (Operation run
    "Delete per-instance state dirs not referenced by any registry entry."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
