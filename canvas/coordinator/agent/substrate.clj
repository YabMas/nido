(ns canvas.coordinator.agent.substrate
  "Self-spec: `nido.coordinator.agent.substrate` — Claude Code background sessions, as nido uses them."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module agent-substrate
  "The one door between nido and a Claude Code session: start one, see them all, message one,
   wake one, stop one, restart one, and make a directory one may run in.

   What it HIDES is everything Claude Code calls a research preview: the `--bg` and `agents
   --json` command lines and the listing's shape, where a session's inbox socket is and what a
   message on it looks like, which resume continues a session in place rather than copying it,
   the workspace-trust dialog, and the minimum version all of that was observed on — the
   supervisor restarting a background session whose process dies among it. A caller
   speaks of sessions and messages; when Claude Code moves, this module is the edit.

   It decides NOTHING about a session — whether to message it, whom it belongs to, what its
   turn meant. Those are the driver's, and keeping them out is what lets this stay a thin
   adapter over a moving interface rather than a second place policy lives."
  (Operation parse-version
    "The [major minor patch] a `claude --version` line names, or nil."
    {:signature [:=> [:catn [:s :string]] [:maybe [:vector :int]]]})
  (Operation version-check
    "Whether the installed Claude Code is at least the minimum the substrate was observed on."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [parse-version]})
  (Operation listing
    "Every session the supervisor knows, interactive and background, as Claude Code reports it:
     state, status, what it waits for, pid while alive. Starts the supervisor if it is not
     running; throws rather than answering empty when the listing cannot be read."
    {:signature [:=> [:catn [:opts :map]] [:sequential :map]]})
  (Operation listing-cached
    "The listing, served from a read under two seconds old — for display, never for a decision."
    {:signature [:=> [:catn [:opts :map]] [:sequential :map]]
     :delegates [listing]})
  (Operation dispatch!
    "Start a background session in a directory on a first prompt, under the directory's launch
     baseline. Returns its short id and full session id. Refuses below the minimum Claude Code
     version and in a directory not trusted."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [version-check listing]})
  (Operation send!
    "Deliver one instruction to a session's live process as a message on its inbox. Never
     restarts, stops or copies the session. Answers whether the inbox took it."
    {:signature [:=> [:catn [:session :map] [:text :string]] :map]})
  (Operation wake!
    "Continue a session that has no live process, in the background under its own id, with an
     instruction as its next prompt; a copy Claude Code starts instead is stopped and removed."
    {:signature [:=> [:catn [:session-id :string] [:text :string] [:opts :map]] :map]})
  (Operation stop!
    "Stop a session's process. Its conversation is kept."
    {:signature [:=> [:catn [:id :string] [:opts :map]] :boolean]})
  (Operation respawn!
    "Restart a session from its saved conversation."
    {:signature [:=> [:catn [:id :string] [:opts :map]] :boolean]})
  (Operation trusted?
    "Whether Claude Code has recorded trust for exactly this directory."
    {:signature [:=> [:catn [:dir :string]] :boolean]})
  (Operation ensure-trusted!
    "Make a directory one Claude Code will run a background session in, by accepting its trust
     dialog — only when the caller vouches for it. Answers whether the directory is now trusted."
    {:signature [:=> [:catn [:dir :string] [:opts :map]] :boolean]
     :delegates [trusted?]}))
