(ns canvas.coordinator.lane.conduct
  "Self-spec: `nido.coordinator.lane.conduct` — driving the Claude Code sessions nido drives."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.agent.substrate :as substrate]
            [canvas.coordinator.record.session-events :as events]
            [canvas.coordinator.record.state :refer [WorkstreamId SessionName]]
            [canvas.platform.process :as process]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-conduct
  "Who drives each session, and what nido does about the ones it drives.

   HIDES the driving policy: a session's driver and hold, when a turn has really ended, when the
   next instruction is sent and when it is held back, when a person is told, and what is
   restarted after a reboot. ORIGIN is recorded and decides none of it — a session a person
   started and one nido started are driven, held, handed back and recovered the same way."
  (Operation tagged
    "An instruction as nido gives it: the text, then a last line carrying its token."
    {:signature [:=> [:catn [:text :string] [:token :string]] :string]})
  (Operation baseline-shown
    "What a session's recorded events show of the launch baseline: its latest start recorded by
     nido's hooks and where, and the permission mode its latest event itself reports."
    {:signature [:=> [:catn [:session-id :string]] :map]
     :delegates [events/events-since]})
  (Operation baseline-refusal
    "Why what a session's recorded events show does not stand up the launch baseline beside its
     listing row, or nil when it does — the one check adoption, a send, a wake and the session
     list make."
    {:signature [:=> [:catn [:shown :map] [:row [:maybe :map]]] [:maybe [:tuple :keyword :string]]]})
  (Operation decide
    "The next facet and the actions it calls for, from a session's facet, its listing row and its
     new events. Pure: it says what to give and never gives it; a token it gives is already pending
     in the facet it returns."
    {:signature [:=> [:catn [:facet :map] [:row [:maybe :map]] [:evs [:sequential :map]] [:ctx :map]] :map]
     :delegates [tagged]})
  (Operation faceted-sessions
    "Every session record carrying a :claude facet, across the registered projects."
    {:signature [:=> [:catn [:opts :map]] [:sequential :map]]})
  (Operation dispatching-sessions
    "Every session record with a start in flight — a :dispatching note — across the projects."
    {:signature [:=> [:catn [:opts :map]] [:sequential :map]]})
  (Operation start!
    "Start a conversation for a session record as a background session nido drives, its first
     prompt's token recorded before the session starts, refused naming the hold while one stands;
     trust is given only to a directory made from a trusted source."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [tagged substrate/trusted? substrate/ensure-trusted! substrate/dispatch!]})
  (Operation adopt!
    "Make nido the driver of a background session that already exists, refused unless its own
     recorded events show the launch baseline, and refused naming the hold while one stands;
     turns from before never hold it."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [substrate/listing baseline-shown]})
  (Operation release!
    "Stop driving a session. The session is untouched and keeps running."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] :map]})
  (Operation hand-back!
    "Clear the hold a turn nido did not give put on a session, making nido its driver again."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName]] :map]})
  (Operation instruct!
    "Queue the next instruction for a session nido drives, given once it is ready for it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:text :string]] :map]})
  (Operation message!
    "Put a seat message in a driven session's inbox, behind any already waiting: each is given in
     turn, tokened, once nothing is queued and the session is ready, and none is refused because
     another waits."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:text :string]] :map]})
  (Operation conduct!
    "One pass: read every new session event and the substrate's listing, decide, record, then
     deliver — only while the session's own events still show the launch baseline; answer what
     the pass saw — turn ends, denials, holds, blocked sessions, deliveries, refusals."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [faceted-sessions decide baseline-shown events/events-since substrate/listing substrate/send! substrate/wake!]})
  (Operation retire!
    "Stop driving a session and stop its process, before the session it belongs to is torn down."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session-name SessionName] [:opts :map]] [:maybe :map]]
     :delegates [release! substrate/stop!]})
  (Operation live-states
    "Each listed session by claude session id, from a listing at most two seconds old — for
     display and liveness, never for a decision to give one an instruction."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [substrate/listing-cached]})
  (Operation current-boot
    "This host boot's identity, or nil when it cannot be read."
    {:signature [:=> [:catn] [:maybe :string]]
     :delegates [process/host-boot]})
  (Operation recover!
    "At nido's first start after a host boot, restart once each auto-respawn session without a
     live process, recording only a restart Claude Code accepted; answer the rest as resumable."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [current-boot faceted-sessions substrate/listing substrate/respawn!]}))
