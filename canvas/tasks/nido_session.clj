(ns canvas.tasks.nido-session
  "Self-spec: `tasks.nido-session` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.conduct :as conduct]
            [canvas.coordinator.lane.conductor :as conductor]
            [canvas.coordinator.lane.seats :as seats]
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
     anything is provisioned when it cannot join it. A session this start first recorded is
     carried once it is up — a step of this verb's own, which spawn takes too."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [conductor/carry!]})
  (Operation revive
    "Bring back the sessions that died with the host: plan from the casualty records, bring the
     admitted ones up in order, and reopen each one's latest conversation where it ran. A dry run
     prints the plan and changes nothing."
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
  (Operation kickoff-prompt
    "A spawned child agent's first turn: whom it answers to, when to write, then the brief."
    {:signature [:=> [:catn [:opts :map]] :string]})
  (Operation attach
    "Attach this terminal to a background session through the substrate adapter."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation adopt
    "Make nido the driver of a background session started in a session's home or worktree."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation release
    "Stop nido driving a session's background session; the session keeps running."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation hand-back
    "Let nido drive a session's background session again after a person's prompt held it, and
     hand the hold back to its workstream's conductor."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [conduct/hand-back! conductor/hand-back!]})
  (Operation spawn
    "Bring a child session up as `up` does and drive it on the brief addressed back to the parent's
     agent: carried as `up` carries a session, occupying its workstream's whole seat when that seat
     is free, and otherwise driven occupying no seat. Record it and the session spawning it as each
     other's :session link, and open a tab that attaches to the child rather than starting a
     conversation of its own. `:ws-id` is passed to `up` and nothing else."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [conductor/carry! seats/drive!]})
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
