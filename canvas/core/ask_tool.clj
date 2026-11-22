(ns canvas.core.ask-tool
  "Self-spec: `nido.core.ask-tool`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-ask-tool
  "nido's ask, as an MCP tool any executor can offer its agent: a question
   with what was explored, the options and their consequences, and a recommendation.

   HIDES the MCP protocol. Called without a resolution it refuses, so an executor that failed
   to halt on it cannot let the agent read silence as an answer."
  (Operation respond
    "The MCP response to one message; a call without an answer is refused."
    {:signature [:=> [:catn [:msg :map]] [:maybe :map]]}))
