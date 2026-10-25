(ns canvas.coordinator.record.session-events
  "Self-spec: `nido.coordinator.record.session-events` — what a Claude Code session reported about itself."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module record-session-events
  "An append-only log per Claude Code session of the events its hooks reported: it started (and
   where its inbox is), a prompt was submitted, a turn stopped (with its last message and any
   work still running), a permission was denied.

   HIDES the log's layout and how concurrent appends from a session's hooks stay whole. The
   events are FACTS, never decisions: nothing here knows whether a prompt was nido's or a
   person's, or what a stop means for a run. That is read off them by the driver."
  (Operation append!
    "Record one event for a session. Never throws to its caller: a hook that cannot record must
     still let the session proceed."
    {:signature [:=> [:catn [:session-id :string] [:event :map]] :boolean]})
  (Operation events-since
    "A session's events after a cursor, oldest first, with the cursor to read from next."
    {:signature [:=> [:catn [:session-id :string] [:cursor [:maybe :int]]] :map]})
  (Operation conversations
    "Every recorded conversation with the cwd and time of its last event that carried a cwd —
     what ties a conversation nido did not drive to the session it ran in."
    {:signature [:=> [:catn] [:vector :map]]}))
