(ns canvas.coordinator.source.core
  "Self-spec: the source substrate — the registry, the per-source state, the manual queue, and
   the routing that turns an arrival into a fire request."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :as cstate :refer [Path]]
            [canvas.coordinator.record.triggers :refer [Trigger]]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Envelope
  "One thing that arrived, addressed. Two shapes: aimed at a project and trigger, or broadcast
   for the router to resolve. Everything the coordinator acts on enters as one of these, which
   is what lets a source be added without the daemon learning about it.")

(Kind FireRequest
  "An envelope resolved against a project's triggers — what will actually be run, and with what.
   The output of routing, and the input to spawning.")

(Kind SourceConfig
  "One configured instance of a source plugin: its type and whatever that type needs. Identified
   by a stable hash of itself, which is how its poll state survives a restart without anyone
   naming it.")

(Module source-registry
  "The plugin registry: sources register at load time and the coordinator looks them up by type.

   Registration is idempotent so a reload replaces rather than duplicates — a source registered
   twice would poll twice and emit every event twice."
  {:child [SourceConfig]}
  (Operation register-source! "Register a source plugin. Idempotent."
    {:signature [:=> [:catn [:plugin :map]] :any]})
  (Operation lookup "The plugin for a source type, or nil."
    {:signature [:=> [:catn [:type :keyword]] [:maybe :map]]})
  (Operation config-hash
    "A stable short hash of a source config — the identity its poll state is filed under, so two
     identical configs share state and a changed one starts clean."
    {:signature [:=> [:catn [:source-config SourceConfig]] :string]})
  (Operation emit-broadcast! "Write a broadcast envelope into the queue for the router to resolve."
    {:signature [:=> [:catn [:broadcast :map]] :any] :delegates [cstate/queue-dir]}))

(Module source-state
  "Each source config's own state file: its last poll snapshot, its watermark, its breaker.

   Filed under the config's hash rather than a name, so nothing has to be named and a config
   that changes materially starts from a clean watermark instead of skipping what it missed."
  (Operation sources-dir "Where source state lives."
    {:signature [:=> [:catn] Path]})
  (Operation state-path "One source config's state file."
    {:signature [:=> [:catn [:config-hash :string]] Path] :delegates [sources-dir]})
  (Operation read-state "A source's state, or nil when it has never polled."
    {:signature [:=> [:catn [:config-hash :string]] [:maybe :map]] :delegates [state-path]})
  (Operation write-state! "Persist a source's state."
    {:signature [:=> [:catn [:config-hash :string] [:state :map]] :any]
     :delegates [state-path sources-dir]})
  (Operation delete-state! "Drop a source's state."
    {:signature [:=> [:catn [:config-hash :string]] :any] :delegates [state-path]})
  (Operation list-state-hashes "Every source config that has state on disk."
    {:signature [:=> [:catn] [:vector :string]] :delegates [sources-dir]}))

(Module source-queue
  "The manual event source: a filesystem queue of envelope files.

   A directory rather than a channel, and that is the durability: an envelope written while the
   daemon is down is picked up on the next drain rather than lost."
  {:child [Envelope]}
  (Operation drain! "Read and remove every queued envelope."
    {:signature [:=> [:catn] [:vector Envelope]] :delegates [cstate/queue-dir]})
  (Operation enqueue! "Write an envelope into the queue."
    {:signature [:=> [:catn [:envelope Envelope]] :any] :delegates [cstate/queue-dir]}))

(Module source-events
  "Routing: one envelope becomes the fire requests it resolves to.

   Pure. A broadcast envelope resolves against every project's triggers, an addressed one
   against the project it names — and both answer with a vector, because one arrival can
   legitimately fire more than one trigger."
  {:child [FireRequest]}
  (Operation route "The fire requests an envelope resolves to."
    {:signature [:=> [:catn [:envelope Envelope] [:triggers-by-project :map]] [:vector FireRequest]]}))

(Module source-filter
  "Trigger filters over event payloads: map equality and set membership.

   Pure, and deliberately narrow — a filter language that could do more would be a place for
   logic to accumulate outside any namespace that could be tested."
  (Operation accept? "Whether an event satisfies every key of a filter."
    {:signature [:=> [:catn [:filter-map :map] [:event :map]] :boolean]}))

(Module source-notion-cache
  "A read model over the Notion source's own snapshots.

   The poller already persists what each page looked like; this reads those snapshots so the
   board can show a page's status without asking Notion again. Reading the source's state rather
   than re-querying is what keeps a board render off the network."
  (Operation parse-priority-rank
    "The leading integer of a priority label, for ordering. A label without one sorts last."
    {:signature [:=> [:catn [:s [:maybe :string]]] :int]})
  (Operation pages-snapshot "The per-page facts a poll observed."
    {:signature [:=> [:catn [:pages :any]] :map] :delegates [parse-priority-rank]})
  (Operation project-page-facts
    "Every page fact across a project's Notion sources, merged."
    {:signature [:=> [:catn [:project ProjectName]] :map]}))
