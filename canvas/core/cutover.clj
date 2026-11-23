(ns canvas.core.cutover
  "Self-spec: `nido.core.cutover`, part of the core cut-over design (workstream ws-20261122-3274ad). Its
   claims are in canvas.claims.core-cutover."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-cutover
  "The one-shot cut-over from the old work plane to the core: the old daemon and every legacy agent
   session stopped, each unclosed workstream handed to its conductor. The legacy ledgers are not
   its to import: they reach the core through the import verb (record-legacy-ledger).

   HIDES the order of the cut-over."
  (Operation apply-plan!
    "Commit `plan`, a sequence of {:ws :commands}, to the nido home's core store. Answers {:handed
     :refused}; a refusal stops nothing else."
    {:signature [:=> [:catn [:paths :map] [:plan [:sequential :map]]] :map]})
  (Operation marker
    "The file under the core's directory that says the cut-over ran: the old daemon was stopped and
     disabled before anything was handed over."
    {:signature [:=> [:catn [:paths :map]] :string]})
  (Operation -main
    "Apply the plan file at `plan-path` to the nido home's core store, and print the outcome as EDN."
    {:signature [:=> [:catn [:plan-path :string]] :any]}))
