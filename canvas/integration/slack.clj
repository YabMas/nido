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
  "The Slack Web API client, its Keychain token, and the message normaliser."
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
  (Operation owl-reacted?
    "Whether a message already carries the ack emoji — how the poller tells a message it has
     handled from one it has not, without keeping its own record."
    {:signature [:=> [:catn [:message :map] [:emoji :string]] :boolean]})
  (Operation post-message "Post a message, optionally into a thread."
    {:signature [:=> [:catn [:channel :string] [:token SlackToken] [:opts :map]] :map]
     :delegates [http-request]})
  (Operation add-reaction "Add an emoji ack. Best-effort — a failed ack must not fail the intake."
    {:signature [:=> [:catn [:channel :string] [:ts :string] [:token SlackToken] [:emoji :string]] :map]
     :delegates [http-request]})
  (Operation message-id "A stable, filesystem-safe id for a channel message."
    {:signature [:=> [:catn [:channel :string] [:ts :string]] :string]})
  (Operation normalise-message "A Slack message as the event payload the coordinator consumes."
    {:signature [:=> [:catn [:channel :string] [:message :map]] :map] :delegates [message-id]}))
