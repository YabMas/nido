(ns canvas.session.briefing
  "Self-spec: `nido.session.launcher` — what an agent finds when it arrives."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.design.check :as design]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate :refer [InstanceId]]
            [fukan.common.typing.malli]))

(Module session-launcher
  "The session home: the briefing, the MCP config, and the agent files an agent reads on arrival.

   Written per session and RE-RENDERABLE, because a briefing composed once at boot goes stale —
   a link added later, a design scope decided by a baseline round afterwards.

   The design section is the part with teeth: it is rendered by fukan rather than read off disk,
   as PROSE rather than as the forms that authored it, and it carries no violation count. A count
   written here would be true at session:up and wrong by the agent's first edit, and a stale
   green is worse than no green at all — so the section says how to ask instead."
  (Operation mcp-path "A session's MCP config."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] Path]
     :delegates [sstate/session-home-dir]})
  (Operation claude-md-path "A session's CLAUDE.md."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] Path]
     :delegates [sstate/session-home-dir]})
  (Operation agents-md-path "A session's AGENTS.md."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] Path]
     :delegates [sstate/session-home-dir]})
  (Operation worktree-link "The link from a session home back to its worktree."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] Path]
     :delegates [sstate/session-home-dir]})
  (Operation write-session-mcp! "Render and write a session's MCP config."
    {:signature [:=> [:catn [:instance-id InstanceId] [:project-name ProjectName] [:worktree Path]
                            [:pg-svc :any] [:pg-port :any]] [:maybe Path]]
     :delegates [sstate/session-mcp-path]})
  (Operation read-project-briefing
    "A project's own briefing markdown, spliced in verbatim. Kept to domain rules nido's generic
     briefing cannot state."
    {:signature [:=> [:catn [:project-name ProjectName]] [:maybe :string]]})
  (Operation nido-add-dirs "The directories an agent needs beyond its worktree."
    {:signature [:=> [:catn] [:vector :string]]})
  (Operation write-artifacts!
    "Write every session-home artifact. Takes the owning Run's directory as its own argument —
     a per-session fact, kept out of the project's shared session.edn."
    {:signature [:=> [:catn [:ctx :map] [:session-edn :map] [:run-dir [:maybe Path]]] :any]
     :delegates [claude-md-path agents-md-path mcp-path write-session-mcp!
                 read-project-briefing design/describe]})
  (Operation session-briefing "The briefing text, from persisted state and live readings."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]
                            [:instance-id InstanceId]] :string]
     :delegates [read-project-briefing design/describe]})
  (Operation rerender-briefing!
    "Re-render only the briefing files — for when what it should say changed without the session
     restarting."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]
                            [:instance-id InstanceId]] :any]
     :delegates [session-briefing claude-md-path agents-md-path]})
  (Operation remove-artifacts! "Remove a session home. No-op when it is already gone."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string]] :any]
     :delegates [sstate/session-home-dir]}))
