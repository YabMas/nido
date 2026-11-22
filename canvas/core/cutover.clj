(ns canvas.core.cutover
  "Self-spec: `nido.core.cutover`, part of the core cut-over design (workstream ws-20261122-3274ad). Its
   claims are in canvas.claims.core-cutover."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-cutover
  "The one-shot cut-over from the old work plane to the core: the old daemon and every legacy agent
   session stopped, the legacy ledgers imported read-only, each unclosed workstream handed to its
   conductor.

   HIDES the order of the cut-over and how the legacy world is read."
  (Operation apply-plan!
    "Import the nido home's legacy ledgers into its core store and commit `plan`, a sequence of
     {:ws :commands}. Answers {:import :handed :refused}; a refusal stops nothing else."
    {:signature [:=> [:catn [:paths :map] [:plan [:sequential :map]]] :map]})
  (Operation marker
    "The file under the core's directory that says the cut-over ran: the old daemon was stopped and
     disabled before anything was imported."
    {:signature [:=> [:catn [:paths :map]] :string]})
  (Operation -main
    "Apply the plan file at `plan-path` to the nido home's core store, and print the outcome as EDN."
    {:signature [:=> [:catn [:plan-path :string]] :any]}))
