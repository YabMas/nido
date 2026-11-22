(ns canvas.core.cutover
  "Self-spec: `nido.core.cutover`, part of the core cut-over design (workstream ws-20261122-3274ad). Its
   claims are in canvas.claims.core-cutover."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module core-cutover
  "The one-shot cut-over from the old work plane to the core: the old daemon and every legacy agent
   session stopped, the legacy ledgers imported read-only, each unclosed workstream handed to its
   conductor.

   HIDES the order of the cut-over and how the legacy world is read.")
