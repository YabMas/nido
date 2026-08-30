(ns canvas.tasks.nido-land
  "Self-spec: `tasks.nido-land` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-land
  "The landing gate: refuse a branch whose design does not stand right now."
  (Operation check
    "The landing gate: both questions, and a refusal from either is a refusal."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation cmd
    "bb entry point: exits non-zero on a refusal, so a recipe that runs it before"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
