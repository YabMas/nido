(ns canvas.session.agent-guidance
  "Self-spec: `nido.session.agent-guidance`."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate]
            [fukan.common.typing.malli]))


(Module session-agent-guidance
  "The agent files nido manages inside the worktree itself.

   Separate from the session home because some agents only read what is beside the code. Removal
   is explicit for the same reason: nido wrote them, so nido takes them away rather than leaving
   a stale briefing in a worktree it no longer manages."
  (Operation write! "Render the guidance files into the worktree root."
    {:signature [:=> [:catn [:ctx :map]] :any]})
  (Operation write-codex-override! "Write Codex's worktree-local override from the briefing."
    {:signature [:=> [:catn [:worktree-path Path] [:briefing :string]] :any]})
  (Operation remove! "Remove the nido-managed guidance files. No-op when absent."
    {:signature [:=> [:catn [:worktree-path Path]] :any]}))
