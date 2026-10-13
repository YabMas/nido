(ns canvas.tasks.nido-session-hook
  "Self-spec: `tasks.nido-session-hook` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb, run by Claude Code rather than by a person: it is the
   command nido's session hooks name."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.session-events :as events]
            [fukan.common.typing.malli]))

(Module nido-session-hook
  "The ingress for what a Claude Code session says about itself: SessionStart, UserPromptSubmit,
   Stop, Notification (waiting on a person) and PermissionDenied, whoever started the session.

   HIDES the hook payload's shape. It records and lets the session proceed — always, including
   when it cannot record — so a nido fault can never hold a person's session or answer for them."
  (Operation ->event
    "The event a hook payload records, or nil for a hook it does not record — the facts the
     driver reads, and nothing else of the payload."
    {:signature [:=> [:catn [:hook-name :string] [:payload :map]] [:maybe :map]]})
  (Operation hook*
    "Record one hook's payload; answer what to print — the supervisor briefing at SessionStart,
     else nothing. Never throws."
    {:signature [:=> [:catn [:hook-name :string] [:raw [:maybe :string]]] [:maybe :string]]
     :delegates [->event events/append!]})
  (Operation hook-cmd
    "Run as the hook: read stdin, record, print, exit 0."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [hook*]}))
