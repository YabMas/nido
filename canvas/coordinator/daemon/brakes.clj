(ns canvas.coordinator.daemon.brakes
  "Self-spec: the coordinator's safety brakes — `halt`, `pid`, `breakers`, `anomaly`.

   Four modules and one subject: how the daemon is stopped, and how it notices it should be.
   Each is a file whose PRESENCE or CONTENT is the fact — nothing is held in memory, so a
   crashed daemon comes back to the same brakes it left."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.state :as state]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind HaltInfo
  "Why the coordinator was paused: by a person or by the machinery, optionally why, and when."
  [:map [:source :keyword]
        [:reason {:optional true} [:maybe :keyword]]
        [:note {:optional true} [:maybe :string]]
        [:halted-at {:optional true} :string]])

(Kind Breaker
  "One trigger's circuit state: consecutive failures, whether it auto-tripped, whether a person
   disabled it. The two halves are kept apart because a deliberate pause is a normal operational
   state and an auto-trip is a fault — collapsing them makes a long-standing manual pause mask a
   genuine new failure.")

(Module daemon-halt
  "The kill switch. Its PRESENCE is the halt; its contents say who and why.

   A file rather than a flag, because the thing that needs to survive is a decision made while
   the daemon was not running."
  {:child [HaltInfo]}
  (Operation halted?
    "Whether the coordinator is paused."
    {:signature [:=> [:catn] :boolean]
     :delegates [state/halted-path]})
  (Operation read-halt-info
    "Why and when, or nil when it is running."
    {:signature [:=> [:catn] [:maybe HaltInfo]]
     :delegates [halted? state/halted-path]})
  (Operation halt!
    "Pause the coordinator, stamping when."
    {:signature [:=> [:catn [:info HaltInfo]] :any]
     :delegates [state/halted-path clock/now-iso]})
  (Operation resume!
    "Un-pause. Idempotent — resuming a running coordinator is not an error."
    {:signature [:=> [:catn] :any]
     :delegates [halted? state/halted-path]}))

(Module daemon-pid
  "The background daemon's pid file, and whether the process it names is still there.

   Liveness is checked against the OS rather than trusted from the file: a pid file outlives a
   crash, so its presence proves nothing on its own."
  (Operation read
    "The pid on record, or nil when there is none or it is unreadable."
    {:signature [:=> [:catn] [:maybe :int]]
     :delegates [state/pid-path]})
  (Operation write!
    "Record a pid. The caller is responsible for it being the right one."
    {:signature [:=> [:catn [:pid :int]] :any]
     :delegates [state/pid-path]})
  (Operation delete!
    "Remove the pid file. Idempotent."
    {:signature [:=> [:catn] :any]
     :delegates [state/pid-path]})
  (Operation alive?
    "Whether the recorded pid is a live process — asked of the OS, not of the file."
    {:signature [:=> [:catn] :boolean]
     :delegates [read]}))

(Module daemon-breakers
  "Per-trigger circuit breakers: how a trigger that keeps failing stops being fired.

   Auto-trip and user-disable are stored separately and cleared together. Both stop the daemon
   processing that trigger, so the loop reads their union; only the auto half is a fault, so the
   health dot reads that alone."
  {:child [Breaker]}
  (Operation read-all
    "Every breaker on record."
    {:signature [:=> [:catn] :map]
     :delegates [state/breakers-path]})
  (Operation consecutive-failures
    "How many times in a row this trigger has failed."
    {:signature [:=> [:catn [:project ProjectName] [:trigger :keyword]] :int]
     :delegates [read-all]})
  (Operation tripped?
    "Whether this trigger's breaker is open — auto-tripped OR disabled by a person, because
     both stop the daemon processing it."
    {:signature [:=> [:catn [:project ProjectName] [:trigger :keyword]] :boolean]
     :delegates [read-all]})
  (Operation record-failure!
    "Count a failure, tripping the breaker at the threshold."
    {:signature [:=> [:catn [:project ProjectName] [:trigger :keyword] [:max-failures :int]] :any]
     :delegates [read-all]})
  (Operation record-success!
    "Clear the failure count and any auto-trip. A user disable SURVIVES a success — it was a
     decision, not a symptom."
    {:signature [:=> [:catn [:project ProjectName] [:trigger :keyword]] :any]
     :delegates [read-all]})
  (Operation disable-by-user!
    "Pause a trigger deliberately, with a note. Persists across successes."
    {:signature [:=> [:catn [:project ProjectName] [:trigger :keyword] [:note :string]] :any]
     :delegates [read-all]})
  (Operation enable!
    "Clear both the auto-trip and the user disable for one trigger."
    {:signature [:=> [:catn [:project ProjectName] [:trigger :keyword]] :any]
     :delegates [read-all]})
  (Operation tripped-triggers
    "Every open breaker — the set the daemon skips."
    {:signature [:=> [:catn] [:vector :map]]
     :delegates [read-all]})
  (Operation auto-tripped-triggers
    "Only the breakers that tripped on failures. The alarm-worthy set: a deliberate pause is not
     a fault, and must not be able to mask a genuine new one."
    {:signature [:=> [:catn] [:vector :map]]
     :delegates [read-all]}))

(Module daemon-anomaly
  "Rate-based detection of a runaway: too many spawns in a window, or too many failures.

   Pure over an in-memory detector — nothing here reads or writes, which is what lets the
   thresholds be tested without spawning anything."
  (Operation empty-detector
    "A detector with nothing recorded yet."
    {:signature [:=> [:catn] :map]})
  (Operation record-spawn
    "Note a spawn at an instant."
    {:signature [:=> [:catn [:det :map] [:iso-ts :string]] :map]})
  (Operation record-failure
    "Note a failure at an instant."
    {:signature [:=> [:catn [:det :map] [:iso-ts :string]] :map]})
  (Operation check
    "What, if anything, is running away — a spawn burst or a failure burst, and how many."
    {:signature [:=> [:catn [:det :map] [:thresholds :map]] [:maybe :map]]}))
