(ns canvas.core.ask-tool
  "Self-spec: `nido.core.ask-tool`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-ask-tool
  "nido's ask, as an MCP tool any executor can offer its agent: a question with the context its
   answer depends on, the options and their consequences, and a recommendation when there is a
   genuine one — or, for several decisions, one question per decision, each with its own options.
   The ask's content is one shape wherever it travels: the tool, the executor's call, the
   conductor's raise_operator_ask. Its interface is the ask's content keys and properties schema;
   the briefing text (ask.md and the core addendum, as read from resources/asking/), which the
   tool's description, raise_operator_ask's and the conductor brief carry; content; and the MCP
   stdio server its -main runs.

   HIDES the MCP protocol of the ask, and where the core's asking briefing is read from. Called
   without a resolution it refuses, so an executor that failed to halt on it cannot let the agent
   read silence as an answer."
  (Operation respond
    "The MCP response to one message; a call without an answer is refused."
    {:signature [:=> [:catn [:msg :map]] [:maybe :map]]})
  (Operation briefing
    "How a core ask is written and read: ask.md, then the core addendum, as resources/asking/ holds
     them when it is called."
    {:signature [:=> [:catn] :string]})
  (Operation content
    "An ask's content out of a tool call's input: its content keys, the empty ones left out."
    {:signature [:=> [:catn [:input [:maybe :map]]] :map]}))
