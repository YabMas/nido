(ns canvas.boot.intake
  "Self-spec: `nido.boot.intake`, part of the core cut-over design (workstream ws-20261122-3274ad). Its
   claims are in canvas.claims.core-cutover."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module boot-intake
  "The intake host: the supervised bb process that runs the sources and triggers and turns each
   arrival into core commands.

   HIDES which sources and triggers run, their limits and breakers, and how an arrival becomes
   commands."
  (Operation dispatch!
    "Route one drained envelope: a drain fire to lane-drain, an envelope of the retired run
     machinery dropped with a word, anything else a source event for trigger matching."
    {:signature [:=> [:catn [:envelope :map] [:triggers-by-project :map]] :any]})
  (Operation tick!
    "One pass of the loop. A halt stops intake — nothing is drained or polled — and nothing else."
    {:signature [:=> [:catn] :any]})
  (Operation run!
    "Run the intake host until the process ends: register the source plugins, then tick."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]}))
