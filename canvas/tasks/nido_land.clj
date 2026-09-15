(ns canvas.tasks.nido-land
  "Self-spec: `tasks.nido-land` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-land
  "The landing gate: refuse a branch whose design does not stand right now, and record the landing
   once main holds it."
  (Operation check
    "The landing gate: every question, and a refusal from any is a refusal."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation claims-check
    "Whether the claims this branch declares are the claims its cleared design states — by id,
     statement and subjects. A difference is a refusal naming it, because a declared claim no
     round judged, or a judged claim the declaration dropped, is a design that did not land. A claim
     carried unchanged from main is another design's, and an id main gave two Claims carries neither.
     A claim id two Claims declare, or a declaration nobody could read, refuses. A project with no
     canvas keeps its claims in its records alone, so there is nothing to hold them to. A merged
     design writes out its whole combination, so it is asked only for the claims its parent's design
     and its child's design state, never for one it only carries from a baseline."
    {:signature [:=> [:catn [:cwd :string]] :int]})
  (Operation cmd
    "bb entry point: exits non-zero on a refusal, so a recipe that runs it before"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation record
    "Record the landing of the worktree's tip once origin's main holds it: close the workstream and
     append a :merged naming that commit and the design that stands, once however often it runs.
     A tip main does not hold, or a design that does not stand, is refused with its way out."
    {:signature [:=> [:catn [:args [:* :any]]] :int]})
  (Operation record-cmd
    "bb entry point: exits non-zero when the landing is not recorded, so the recipe's step stops there."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
