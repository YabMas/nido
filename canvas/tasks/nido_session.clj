(ns canvas.tasks.nido-session
  "Self-spec: `tasks.nido-session` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-session
  "Bb task entry points for the bundled session lifecycle. Every command"
  (Operation budget-report
    "The lines shown before a session boots. Pure — takes the facts, returns"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation interactive?
    "Whether a human is on the other end. Public because it and `confirm?` are the"
    {:signature [:=> [:catn] :any]})
  (Operation confirm?
    "Ask, defaulting to no. Any answer but an explicit yes leaves the fleet alone."
    {:signature [:=> [:catn] :any]})
  (Operation up
    "Bring the named session up. Creates the worktree (if missing) + starts"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation down
    "Stop the named session. Worktree and on-disk state are preserved."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation reset
    "Nuclear recovery: bring the session down, drop its PGDATA, then bring"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation destroy
    "Bring the named session down and remove its worktree."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation enter
    "Hand off a cwd to the parent shell via `~/.nido/.last-cd`. Paired with"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation status
    "Print status for the named session."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation list-sessions
    "List every session for a project."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation isolate
    "Switch a session to a private Postgres clone so it can run destructive"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation share
    "Switch a session back to the shared Postgres cluster, dropping its private"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
