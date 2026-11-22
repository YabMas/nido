(ns canvas.tasks.nido-commit-gate
  "Self-spec: `tasks.nido-commit-gate` — a bb task entry point, run as a host PreToolUse hook."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.session.commit-gate :as gate]
            [fukan.common.typing.malli]))

(Module nido-commit-gate
  "Answer the host's PreToolUse call the way it is heard: exit 2 with the reason on stderr to
   block the command, 0 to let it run. The project's convention is the session commit gate's to
   judge; whether a command run in a turn the core runs is a person's act is its own refusal's,
   which moves here when the conductor lane is deleted (core cut-over, ws-20261122-3274ad).

   HIDES how a command is judged, and that a grant is read from the turn's workstream's core
   state through core-api."
  (Operation gate-cmd
    "The `gate-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [gate/verdict refusal]})
  (Operation refusal
    "Why a command must not run, or nil: a person's act — an intent, a design grant, a triage
     acceptance, a phase gate, an answer, a landing, a PR merge or a close — run in a turn the core
     runs, a unit's or the conductor's, judged over what the commit gate reads the command to do,
     and admitted only as an unspent grant in the turn's workstream's core state, read through
     core-api, admits it. A turn the core does not run is the person's, and its acts are allowed.
     Pushing, whatever the branch, and opening or readying a PR are not a person's act."
    {:signature [:=> [:catn [:input :map] [:env [:? [:maybe :map]]]] [:maybe :string]]
     :delegates [gate/acts]}))
