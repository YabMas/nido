(ns canvas.tasks.nido-recovery
  "Self-spec: `tasks.nido-recovery` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-recovery
  "The verbs a recovery session works through: read what failed, restore it, land a nido fix.

   The land verb is the gate, not advice. A recovery lands on nido's main unattended, so the
   verb itself runs the tests and the landing check at the exact tip it pushes, and refuses on a
   workstream whose diagnosis does not name a nido defect — a skill that skipped a step cannot
   land around it."
  (Operation failures
    "Print kept failures with their evidence: all of them, the owed ones, or one cause's."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation restore
    "Restore a cause's owed failures onto the recovery workstream the session belongs to."
    {:signature [:=> [:catn [:args [:* :any]]] :int]})
  (Operation restore-cmd "bb entry point for restore: exits with its code."
    {:signature [:=> [:catn [:args [:* :any]]] :any] :delegates [restore]})
  (Operation land
    "Land a recovery's nido fix: refuse without a nido-defect diagnosis, test and check the tip,
     fast-forward origin's main, record the :merged once."
    {:signature [:=> [:catn [:args [:* :any]]] :int]})
  (Operation land-cmd "bb entry point for land: exits with its code."
    {:signature [:=> [:catn [:args [:* :any]]] :any] :delegates [land]}))
