(ns canvas.coordinator.agent
  "Self-spec: `nido.coordinator.agent` — launching a headless agent for a run."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module coordinator-agent
  "Spawn an agent for a run and wait for it, under a budget.

   A CAPABILITY, not part of the daemon — two lanes, three Review namespaces and the composition
   root reach it, and nothing in the daemon does. The budget is the reason this is one function
   rather than a shell-out at each call site: parsing REFUSES an unparseable duration rather than
   answering nil, because the sole reader armed its kill timer only when a budget was present,
   and a brake that silently is not there is worse than no brake."
  (Operation parse-budget-ms
    "A duration like `30m`, `45m` or `2h` in milliseconds. Throws rather than degrading: nil for
     an unparseable budget is how agents came to run with no wall clock at all."
    {:signature [:=> [:catn [:s :string]] :int]})
  (Operation launch!
    "Run an agent headlessly to completion, killed at its budget. Blocks."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [parse-budget-ms]}))
