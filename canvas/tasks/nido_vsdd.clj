(ns canvas.tasks.nido-vsdd
  "Self-spec: `tasks.nido-vsdd` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-vsdd
  "CLI entry points for VSDD orchestration."
  (Operation run
    "Run VSDD loop."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation resume
    "Resume an interrupted VSDD run."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation analyze
    "Analyze a completed VSDD run for efficiency improvements."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation sweep
    "Run VSDD across all changed modules in parallel."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
