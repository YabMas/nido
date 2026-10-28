(ns canvas.integration.slack
  "Self-spec: `nido.slack.*` — the Slack adapter.

   A redef seam at the boundary, pure normalisation above it, and nothing that knows what a
   workstream is — which is what makes an adapter replaceable."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind SlackToken
  "The Slack bot token, read from the user's macOS Keychain. Passed explicitly to every call
   rather than held in a var, for the same reason the Notion one is."
  :string)

(Module slack-client
  "The Slack Web API client, its Keychain token, the message and thread normalisers, slack id making and parsing, and the stubbable HTTP and shell primitives the socket module shares."
  {:child [SlackToken]}
  (Operation sh! "Shell out, wrapped so tests can stub the Keychain."
    {:signature [:=> [:catn [:args :any]] :map]})
  (Operation keychain-token "The Slack bot token, or nil."
    {:signature [:=> [:catn] [:maybe SlackToken]] :delegates [sh!]})
  (Operation keychain-set! "Store or replace the Slack bot token."
    {:signature [:=> [:catn [:token SlackToken]] :any] :delegates [sh!]})
  (Operation http-request "One HTTP call, wrapped so tests can stub the network."
    {:signature [:=> [:catn [:method :keyword] [:url :string] [:opts :map]] :map]})
  (Operation conversations-history "A channel's recent messages."
    {:signature [:=> [:catn [:channel :string] [:token SlackToken] [:opts :map]] :map]
     :delegates [http-request]})
  (Operation chat-permalink "One message's permalink — the durable way to point at it."
    {:signature [:=> [:catn [:channel :string] [:ts :string] [:token SlackToken]] [:maybe :string]]
     :delegates [http-request]})
  (Operation post-message "Post a message, optionally into a thread."
    {:signature [:=> [:catn [:channel :string] [:token SlackToken] [:opts :map]] :map]
     :delegates [http-request]})
  (Operation add-reaction "Add an emoji ack. Best-effort — a failed ack must not fail the intake."
    {:signature [:=> [:catn [:channel :string] [:ts :string] [:token SlackToken] [:emoji :string]] :map]
     :delegates [http-request]})
  (Operation message-id "A stable, filesystem-safe id for a channel message."
    {:signature [:=> [:catn [:channel :string] [:ts :string]] :string]})
  (Operation ticket-id
    "The id of one ticket proposed from a thread: the thread's id scoped by the ticket's key."
    {:signature [:=> [:catn [:thread-id :string] [:key :string]] :string] :delegates [parse-id]})
  (Operation parse-id
    "A thread or ticket id back into its channel, root ts and (for a ticket) key — or nil for
     anything that is not exactly one of the two forms. The one place a slack id is read."
    {:signature [:=> [:catn [:id :any]] [:maybe :map]]})
  (Operation conversations-replies "A thread: its root message and every reply, oldest first."
    {:signature [:=> [:catn [:channel :string] [:ts :string] [:token SlackToken]] :map]
     :delegates [http-request]})
  (Operation users-info "A user's display name, or nil when Slack answers none."
    {:signature [:=> [:catn [:user :string] [:token SlackToken]] [:maybe :string]]
     :delegates [http-request]})
  (Operation normalise-thread
    "A thread as the event payload the coordinator consumes: keyed by the thread, its text the
     transcript, the reacted message's ts beside it."
    {:signature [:=> [:catn [:channel :string] [:messages [:vector :map]] [:reacted-ts :string] [:permalink [:maybe :string]]] :map]
     :delegates [message-id]})
  (Operation normalise-message "A Slack message as the event payload the coordinator consumes."
    {:signature [:=> [:catn [:channel :string] [:message :map]] :map] :delegates [message-id]}))

(Kind AppToken
  "The Slack app-level token (xapp-) that opens a Socket Mode connection, read from the
   Keychain beside the bot token. A different credential from SlackToken: it can open a
   connection and do nothing else."
  :string)

(Module slack-socket
  "Slack's Socket Mode: an outbound websocket over which Slack pushes events. Hides the wire —
   opening it with the app token, the hello and disconnect frames, and the ack every envelope
   needs — and hands its caller only the reaction events it carried."
  {:child [AppToken]}
  (Operation app-token "The app-level token, or nil."
    {:signature [:=> [:catn] [:maybe AppToken]] :delegates [sh!]})
  (Operation app-token-set! "Store or replace the app-level token."
    {:signature [:=> [:catn [:token AppToken]] :any] :delegates [sh!]})
  (Operation normalise-envelope
    "One Socket Mode frame as {:ack <envelope id or nil> :event <a kept reaction or nil>
     :control <:hello | :disconnect | nil>} — the one place a frame's shape is read."
    {:signature [:=> [:catn [:frame :string]] :map]})
  (Operation open-websocket "Open one websocket — the seam tests replace."
    {:signature [:=> [:catn [:opts :map]] :any]})
  (Operation connect!
    "Open a connection and keep it open — acking every envelope on receipt, reopening with
     backoff when it drops, and stopping on an auth refusal — calling `on-event` with each
     reaction and `on-status` with every change of connection state. Answers a close function."
    {:signature [:=> [:catn [:token AppToken] [:on-event :any] [:on-status :any]] :any]
     :delegates [normalise-envelope open-websocket http-request]}))
