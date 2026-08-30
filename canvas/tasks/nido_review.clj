(ns canvas.tasks.nido-review
  "Self-spec: `tasks.nido-review` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-review
  "bb-task entrypoints for the three judgment loops — over a baseline record,"
  (Operation exit-code
    "CLI exit code for a terminal review status. review-failed is the only"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation review-event
    "Pure: build a :review ledger payload from the loop's terminal value `final`"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation append-review-entry!
    "Resolve cwd → session → workstream (the tasks.nido-ship path) and append one :review"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation parked-blocker
    "Pure: the halt a run holding parked findings owes a human, or nil."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation append-blocker!
    "Append the halt, if there is one. Best-effort for the same reason"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation queue-analysis!
    "Queue this run for nido-side analysis. Best-effort, for the same reason"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation verdict-worth-running?
    "Whether the verdict pass has anything to judge."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation append-design-verdict!
    "Run the design verdict and append it as a ledger event. Best-effort throughout,"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation loop-cmd*
    "The `loop-cmd*` entry point."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation loop-cmd
    "The `loop-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation baseline-cmd*
    "Verify a baseline against the code, and keep correcting it until the code stops refuting it.
     `:seq` names WHICH baseline; the newest by default."
    {:signature [:=> [:catn [:opts :map]] :any]})
  (Operation design-cmd*
    "Decide, against the latest design record, whether this should be executed —"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation baseline-cmd
    "The `baseline-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation design-cmd
    "The `design-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
