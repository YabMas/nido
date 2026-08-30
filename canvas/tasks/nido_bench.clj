(ns canvas.tasks.nido-bench
  "Self-spec: `tasks.nido-bench` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-bench
  "Bb task entry points for bench runs."
  (Operation memory
    "Run the memory-bench matrix for a project. See nido.bench.memory/run-all!."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation levers
    "List known bench levers."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
