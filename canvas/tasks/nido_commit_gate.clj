(ns canvas.tasks.nido-commit-gate
  "Self-spec: `tasks.nido-commit-gate` — a bb task entry point, run as a host PreToolUse hook."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.conductor :as conductor]
            [canvas.coordinator.lane.seats :as seats]
            [canvas.session.commit-gate :as gate]
            [fukan.common.typing.malli]))

(Module nido-commit-gate
  "Answer the host's PreToolUse call the way it is heard: exit 2 with the reason on stderr to
   block the command, 0 to let it run. It judges nothing itself: the project's convention is the
   session commit gate's to judge, a seat's naming of its seat the occupancy lane's, and a
   person's act run from the conductor or a seat it answers for the conductor lane's."
  (Operation gate-cmd
    "The `gate-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [gate/verdict seats/gate-verdict conductor/refusal]}))
