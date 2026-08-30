(ns canvas.coordinator.control
  "Self-spec: `nido.coordinator.control` — daemon control, as its callers ask for it."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.triggers :as triggers :refer [Trigger]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind DaemonHealth
  "The rail dot: one state for `is anything wrong anywhere`, and when the daemon last spoke.

   A SEVERITY ladder, not a liveness predicate — it ranks an open breaker above a healthy
   daemon, so a single tripped trigger outranks everything. Reading it as go/no-go reported a
   healthy daemon as down whenever any unrelated trigger was tripped, which is why the question
   `will THIS envelope run` has its own answer."
  [:map [:state :keyword] [:heartbeat-at [:maybe :string]]])

(Module coordinator-control
  "The work plane's second facade: is the coordinator healthy, will this envelope run, pause it,
   resume it, clear a breaker, fire a trigger.

   Separate from the work facade because the subject is different. `work` is the vocabulary of
   workstreams, tickets and runs; nothing in it wants to know about a pid file. One facade
   answering two unrelated questions is how a facade becomes a junk drawer.

   The pure/impure split is deliberate and predates the namespace: the derivations came from the
   dashboard, where they were coordinator semantics wearing a UI namespace. The surfaces render
   what these return."
  {:child [DaemonHealth]}
  (Operation daemon-health
    "Derive the rail state from already-read inputs. Pure."
    {:signature [:=> [:catn [:inputs :map]] DaemonHealth]})
  (Operation queue-blocker
    "Why a queued envelope for one trigger would NOT be processed, or nil when it will. The
     counterpart to the dot: same files, a different question. Pure."
    {:signature [:=> [:catn [:inputs :map]] [:maybe :keyword]]})
  (Operation read-daemon-health
    "Read the daemon's files and derive the rail state."
    {:signature [:=> [:catn] DaemonHealth]
     :delegates [daemon-health]})
  (Operation read-queue-blocker
    "Read the daemon's files and derive what, if anything, blocks an envelope for this trigger."
    {:signature [:=> [:catn [:project ProjectName] [:trigger :keyword]] [:maybe :keyword]]
     :delegates [queue-blocker]})
  (Operation halted?
    "Whether the coordinator is paused."
    {:signature [:=> [:catn] :boolean]})
  (Operation halt-info
    "Why and when it was paused, or nil when it is running. Named for the question rather than
     for the file it reads."
    {:signature [:=> [:catn] [:maybe :map]]})
  (Operation halt!
    "Pause the coordinator, recording who and why."
    {:signature [:=> [:catn [:info :map]] :any]})
  (Operation resume!
    "Un-pause. Idempotent."
    {:signature [:=> [:catn] :any]})
  (Operation tripped-triggers
    "Every open breaker — auto-tripped or user-disabled, which is the set the daemon skips."
    {:signature [:=> [:catn] [:vector :map]]})
  (Operation clear-breaker!
    "Re-enable one trigger, clearing both the auto-trip and any user disable. `clear-` because
     from a surface this undoes a trip rather than turning a feature on."
    {:signature [:=> [:catn [:project ProjectName] [:trigger :keyword]] :any]})
  (Operation triggers-for
    "Every trigger a project declares. Unfiltered — the TUI's picker and the dashboard's form
     disagree about which subset they want."
    {:signature [:=> [:catn [:project ProjectName]] [:vector Trigger]]
     :delegates [triggers/load-for-project]})
  (Operation trigger-placeholders
    "The fields a fire form has to collect for this trigger."
    {:signature [:=> [:catn [:payload :string]] [:vector :keyword]]
     :delegates [triggers/placeholder-keys]})
  (Operation fire!
    "Queue an envelope at a trigger. Takes the three things a caller HAS rather than the envelope
     map four call sites were each building by hand — the queue's wire shape was leaking into
     two surfaces and a review loop."
    {:signature [:=> [:catn [:project ProjectName] [:trigger :keyword] [:payload :map]] :any]}))
