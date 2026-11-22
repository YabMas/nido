(ns canvas.tasks.nido-land
  "Self-spec: `tasks.nido-land` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.daemon.lifecycle :as daemon]
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
    "Record the landing of the commit given, else the worktree's tip, once origin's main holds it:
     close the workstream and append a :merged naming that commit and the design that stands, once however often it runs.
     A tip main does not hold, or a design that does not stand, is refused with its way out."
    {:signature [:=> [:catn [:args [:* :any]]] :int]})
  (Operation record-cmd
    "bb entry point: exits non-zero when the landing is not recorded, so the recipe's step stops there."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation gate!
    "Land one revision — the one named, else the cwd's tip read once — on origin's main or refuse:
     fetch, duplicate its arc onto origin's main in a jj workspace of its own (the duplicate
     abandoned on a conflict, and each copy main's content empties dropped — none left is nothing
     to land, refused), bb nido:test there and the landing check over that workspace's files on the
     cwd's workstream, push, then forget the workspace. When no revision was named, a push origin
     took is followed by rebasing the cwd's arc onto origin's main, dropping what main's content
     empties; a named revision leaves the cwd's commits where they were.
     {:landed} or {:refused :way-out}; it records nothing."
    {:signature [:=> [:catn [:cwd :string] [:rev [:? :string]]] :map] :delegates [check]})
  (Operation restart-daemon!
    "Put the coordinator on the code that landed: rebase the checkout the launchd daemon loads onto
     origin's main at once, then record the landing as owed for the daemon to pay on its tick, and
     say what it waits for. Never restarts, starts or signals the daemon. A rebase that fails,
     conflicts or leaves the checkout without the landing is undone and owes nothing; a debt that
     cannot be written leaves the rebase in place and is a failure.
     {:owed <commit> :waits [<why>]} | {:skipped :way-out} | {:failed :way-out}."
    {:signature [:=> [:catn [:landed :string]] :map]})
  (Operation land
    "bb nido:land: the gate over the revision given, else the worktree's tip read once; a line
     saying origin's main took the push, once it has; then the recording, on the worktree's
     workstream, of the commit the gate landed; then a restart owed onto the landing for the
     coordinator to pay. No option of the verb skips that step."
    {:signature [:=> [:catn [:args [:* :any]]] :int] :delegates [gate! record restart-daemon!]})
  (Operation land-cmd
    "bb entry point for land: exits non-zero when nothing was landed, the landing was not recorded, or the restart failed."
    {:signature [:=> [:catn [:args [:* :any]]] :any] :delegates [land]}))
