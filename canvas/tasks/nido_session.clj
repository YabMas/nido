(ns canvas.tasks.nido-session
  "Self-spec: `tasks.nido-session` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.work :as work]
            [fukan.common.typing.malli]))

(Module nido-session
  "Bb task entry points for the bundled session lifecycle. Every command"
  (Operation exit!
    "Redefable wrapper around System/exit, so tests capture the exit code."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
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
    "Bring the named session up, on the workstream `:ws-id` names when given — refused before
     anything is provisioned when it cannot join it. Given a brief, the workstream is activated
     with the session as its unit's and the brief as its first turn, which the core runs."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation revive
    "Bring back the workstreams that died with the host: plan from those owed a revive, bring the
     admitted ones up in order on the session a person works in, and reopen its latest
     conversation where it ran. A dry run prints the plan and changes nothing."
    {:signature [:=> [:catn [:args [:* :any]]] :any] :delegates [work/owed-revivals]})
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
  (Operation start
    "Start work on a goal from anywhere: a workstream minted for it and handed to the core, whose
     unit's session the core provisions under the given name and whose first turn is the brief.
     Adopts no session; refuses a name a session already has."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [work/start-goal!]})
  (Operation status
    "Print status for the named session."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation sync-runtime
    "Bring the current session's runtime checkout up to date with every member's working copy."
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
