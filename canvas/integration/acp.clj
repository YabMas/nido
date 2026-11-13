(ns canvas.integration.acp
  "Self-spec: `nido.acp.*` — the Agent Client Protocol adapter.

   A process and its wire at the bottom, one parse above it, and nothing that knows what a
   session, a workstream or a page is — which is what makes the agent behind it replaceable."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Kind Item
  "One item of a conversation, tagged by `:kind`. Every item carries its `:id`, its position in
   the conversation (\"i0\", \"i1\", …), which names it for the conversation's life. Text items
   carry `:streaming?` while they grow."
  [:or [:map [:id :string] [:kind [:enum :user]] [:text :string] [:context [:maybe :string]]]
       [:map [:id :string] [:kind [:enum :agent :thought]] [:text :string]]
       [:map [:id :string] [:kind [:enum :tool]] [:title :string]
             [:status [:enum :pending :in-progress :completed :failed]]
             [:body [:maybe :string]] [:diffs [:vector [:map [:path :string] [:new :string]]]]]
       [:map [:id :string] [:kind [:enum :permission]] [:title :string] [:request-id :any]
             [:options [:vector [:map [:id :string] [:name :string]]]] [:answer [:maybe :string]]]
       [:map [:id :string] [:kind [:enum :error]] [:text :string]]])

(Kind Event
  "What a conversation folds in: a message from the agent exactly as the client delivered it — an
   ACP notification or request, which the fold alone reads — or a local event, tagged by `:local`.
   An agent message the fold does not read leaves the conversation as it was."
  [:or [:map [:method :string]]
       [:map [:local [:enum :user]] [:text :string] [:context [:maybe :string]]]
       [:map [:local [:enum :turn-ended]]]
       [:map [:local [:enum :error]] [:text :string]]
       [:map [:local [:enum :answered]] [:item-id :string] [:option-id :string]]])

(Kind Conversation
  "A conversation as a reader sees it: its items in order, and whether a turn is running.
   Agent-neutral: nothing in it is ACP's shape."
  [:map [:items [:vector Item]] [:busy? :boolean]])

(Module acp-client
  "One ACP connection: the agent adapter's process and JSON-RPC 2.0 over its stdio.

   Every request answers with the agent's result, or throws an ex-info naming the method and
   carrying the agent's error — or a timeout, or the process having exited. A throw is the one
   failure outcome; nothing is answered half-done.

   HIDES the process, the framing, request-id correlation and which ACP methods and protocol
   version are spoken. A caller starts sessions and prompts them; every notification and every
   request the agent makes of the client arrives at the one handler it supplied."
  (Operation connect!
    "Start an agent command in a directory and initialize it. `on-message` receives every
     notification and agent request."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation new-session! "Create a session; its id."
    {:signature [:=> [:catn [:conn :map] [:opts :map]] :string]})
  (Operation load-session! "Load an existing session by id, replaying its history to the handler."
    {:signature [:=> [:catn [:conn :map] [:session-id :string] [:opts :map]] :any]})
  (Operation set-mode! "Set a session's permission mode."
    {:signature [:=> [:catn [:conn :map] [:session-id :string] [:mode :string]] :any]})
  (Operation prompt! "Send a prompt; a promise of the turn's stop reason."
    {:signature [:=> [:catn [:conn :map] [:session-id :string] [:text :string]] :any]})
  (Operation cancel! "Cancel a session's running turn."
    {:signature [:=> [:catn [:conn :map] [:session-id :string]] :any]})
  (Operation respond! "Answer a request the agent made of the client."
    {:signature [:=> [:catn [:conn :map] [:request-id :any] [:result :any]] :any]})
  (Operation close! "Stop the agent process."
    {:signature [:=> [:catn [:conn :map]] :any]}))

(Module acp-transcript
  "ACP's session updates read as a transcript.

   HIDES ACP's update vocabulary — the sessionUpdate kinds, the chunk and tool-call shapes, and
   which of them nido ignores. The one place an agent's message shape becomes trusted."
  {:child [Item Event Conversation]}
  (Operation prompt-text
    "The prompt the agent is sent for the person's words and the context they came with, in the
     form a replayed history is read back from."
    {:signature [:=> [:catn [:text :string] [:context [:maybe :string]]] :string]})
  (Operation empty-transcript "A conversation with nothing in it."
    {:signature [:=> [:catn] Conversation]})
  (Operation fold
    "One event folded in: an agent message (an ACP notification or request), or a local one —
     the person's message, a turn ending, an error, a permission answered."
    {:signature [:=> [:catn [:transcript Conversation] [:event Event]] Conversation]}))
