(ns canvas.boot.intake
  "Self-spec: `nido.boot.intake`, part of the core cut-over design (workstream ws-20261122-3274ad). Its
   claims are in canvas.claims.core-cutover."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module boot-intake
  "The intake host: the supervised bb process that runs the sources and triggers and turns each
   arrival into core commands.

   HIDES which sources and triggers run, their limits and breakers, and how an arrival becomes
   commands.")
