(ns canvas.core.turn
  "Self-spec: `nido.core.turn`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-turn
  "The Turn: one bounded stretch of agent work, run for a unit of work
   in that unit's project (or, for the conductor's own reasoning, for its workstream), and
   the executor port. An adapter may also hold a live session open for a
   resident conductor — open, send, stream back, close — and one that cannot gives
   the same meaning as one turn per message, resumed.

   HIDES which provider runs a turn. An executor runs a turn from instructions and context
   and returns exactly one outcome — completed, asked (a pending call carrying nido's ask),
   failed or cancelled — cancels a running turn, resumes an asked turn with a
   resolution, takes back a turn by the handle recorded when it started (a turn
   outlives the daemon that started it), and hands a unit's session to a person
   and takes it back. The port is where an executor's output is parsed: output
   that is not one of the four outcomes is the outcome failed."
  (Operation parse-outcome
    "An executor's report as one of the four outcomes; anything else is failed."
    {:signature [:=> [:catn [:report :any]] :map]})
  (Operation stderr-tail
    "The last a run wrote to its stderr, or nothing: kept on a turn whose run ended without an
     outcome, as the only word on why an agent that crashed died."
    {:signature [:=> [:catn [:run-dir :string]] [:maybe :string]]})
  (Operation turn-dir
    "The directory of a workstream's turn under the runs root."
    {:signature [:=> [:catn [:root :string] [:ws :string] [:turn :string]] :string]})
  (Operation run-dir
    "The directory of one run of a turn: the run's handle, fixed before launch."
    {:signature [:=> [:catn [:turn-dir :string] [:run :int]] :string]})
  (Operation start
    "Start a runner that starts and cancels runs as committed state says, after the commit."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation changed!
    "Hand the runner a commit's changes without blocking."
    {:signature [:=> [:catn [:runner :map] [:changes [:sequential :map]]] :nil]})
  (Operation take-back!
    "Take back the turns a workstream records running, as a starting daemon does; starts nothing."
    {:signature [:=> [:catn [:runner :map] [:ws :string] [:state :map]] :nil]})
  (Operation attach-argv
    "What a person runs to enter a turn's session."
    {:signature [:=> [:catn [:runner :map] [:ws :string] [:turn :map]] [:maybe [:sequential :string]]]})
  (Operation classify-exhaustion
    "A finished run's outcome as resource-exhausted when it failed and the host refused it a
     process or thread — its stderr says so, or forks were refused it while it ran, at its own
     scope's cap or in the cgroups beside it. A launch the host refuses is that run's outcome,
     classified the same way."
    {:signature [:=> [:catn [:outcome :map] [:err [:maybe :string]] [:refused [:maybe :int]]
                         [:now :int]] :map]})
  (Operation pid-refusals-above
    "The forks the pids controller refused at a cgroup and at each of its ancestors, each refusal
     counted once, at the level whose limit refused it — not once more at every slice above it,
     whose hierarchical counters already hold it, and not when a sibling's own cap refused it."
    {:signature [:=> [:catn [:root :string] [:cgroup :string]] [:maybe :int]]})
  (Operation scope-argv
    "What runs in front of a run so it is in a transient scope of its own, under the scope's
     process cap; nothing without a scope."
    {:signature [:=> [:catn [:scope [:maybe :map]] [:run-dir :string] [:turn :string] [:run :int]]
                 [:sequential :string]]})
  (Operation probe-scope
    "A scope for runs when systemd-run can start one under a user systemd manager; none when it
     cannot, and runs start in the core's own cgroup as before."
    {:signature [:=> [:catn [:systemd-run [:maybe :string]] [:tasks-max :int]] [:maybe :map]]})
  (Operation stop!
    "Stop converging; runs keep running for the next daemon to take back."
    {:signature [:=> [:catn [:runner :map]] :nil]}))
