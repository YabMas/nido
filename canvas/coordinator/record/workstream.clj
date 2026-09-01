(ns canvas.coordinator.record.workstream
  "Self-spec: `nido.coordinator.record.workstream` — the source-agnostic spine."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.state :as state :refer [Path WorkstreamId]]
            [canvas.coordinator.report :as report]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind Workstream
  "A unit of work, whatever raised it: its stage, the external refs it answers to, and its
   append-only ledger.

   SOURCE-AGNOSTIC is the whole design. A Notion ticket, a GitHub issue, a Slack message and a
   scratch idea are all the same record with different `:external-refs`, which is why a lane can
   advance one without knowing where it came from.

   SHAPELESS. The closed malli schema in the code is validated on every write.")

(Kind LedgerEntry
  "One immutable append on a workstream's ledger: its `:seq`, its `:kind`, and the file holding
   what was said. The payload is a `LedgerEvent`; this is the envelope around it.

   Immutability is load-bearing rather than tidy: a design record CITES a baseline by seq, so an
   entry that could be rewritten would silently move the ground under every judgement made
   against it.")

(Module record-workstream
  "The workstream: its record, its stage, and the append-only ledger everything else cites.

   The ledger is the part with teeth. Appends are SERIALISED under a per-workstream lock, and the
   whole read-derive-write sits inside it — `:seq` is derived from the index, so two writers
   reading the same index both compute the same number and the second overwrites the first.
   Per workstream rather than globally, so two unrelated workstreams never queue behind each
   other.

   Reads go through the READ contract, not the write one: an entry that was legitimately
   writable when it was written stays readable after the schema moves on, and a reader that
   cannot parse one gets nil rather than a throw."

  {:child [Workstream LedgerEntry]}
  (Operation validate
    "The record, or a throw carrying malli's explanation."
    {:signature [:=> [:catn [:w Workstream]] Workstream]})
  (Operation mint-id
    "A fresh workstream id — dated, with enough randomness that two minted in the same second
     do not collide."
    {:signature [:=> [:catn] WorkstreamId]
     :delegates [clock/now-iso]})
  (Operation read-ws
    "One workstream by id, or nil. Normalises what older records spelled differently."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe Workstream]]
     :delegates [state/workstream-edn-path]})
  (Operation write!
    "Validate, then write atomically. A reader never observes half a record."
    {:signature [:=> [:catn [:w Workstream]] Workstream]
     :delegates [validate state/workstream-edn-path]})
  (Operation create!
    "Mint an id and persist a fresh workstream at the given stage."
    {:signature [:=> [:catn [:project ProjectName] [:base :map]] Workstream]
     :delegates [mint-id write! clock/now-iso]})
  (Operation advance-stage!
    "Move a workstream to a new stage, recording where it came from. A no-op when it is already
     there, so a repeated event does not litter the history."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:new-stage :keyword]] Workstream]
     :delegates [read-ws write! clock/now-iso]})
  (Operation set-facets!
    "Overwrite a workstream's classification facets."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:facets :map]] Workstream]
     :delegates [read-ws write!]})
  (Operation close!
    "Settle a workstream terminally — done, dropped or dismissed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:outcome :keyword]] Workstream]
     :delegates [read-ws write! clock/now-iso]})
  (Operation reopen!
    "Un-settle a workstream: clear its outcome and put it back on a stage."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:stage :keyword]] Workstream]
     :delegates [read-ws write! clock/now-iso]})
  (Operation set-findings!
    "Overwrite the live findings tracker, or remove it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:tracker [:maybe :map]]] Workstream]
     :delegates [read-ws write!]})
  (Operation append-lock-path
    "The lock serialising appends to ONE workstream — per workstream, so two unrelated ones
     never queue behind each other."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Path]
     :delegates [state/workstream-dir]})
  (Operation append-entry!
    "Append an immutable entry and record it in the index, under the workstream's lock."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:entry :map] [:content :string]] Path]
     :delegates [append-lock-path read-ws write! report/entry-payload]})
  (Operation highest-seq-on-disk
    "The largest entry number under entries/, or 0.

     The index is a projection and can fall behind the directory it projects; the entries are
     the record. Numbering an append off the index alone is what let one workstream reach 39
     files against 37 rows, silently overwriting two."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :int]})
  (Operation index-drift
    "How far the index has fallen behind the entries directory, or nil when they agree. What a
     reader is shown, because every other reader here trusts the index."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [read-ws highest-seq-on-disk]})
  (Operation append-entry-at!
    "Append only if the ledger's latest entry is still the one the caller read. The optimistic
     lock that stops two people deciding the same thing from both writing the decision."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:expected-seq :int] [:entry :map] [:content :string]] :map]
     :delegates [append-lock-path read-ws write! report/entry-payload]})
  (Operation latest-entry
    "The most recent entry of a kind, parsed through the READ contract. nil when there is none,
     and nil when the one that is there no longer parses."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:kind :keyword]] [:maybe LedgerEntry]]
     :delegates [read-ws report/parse-event]})
  (Operation unstamp
    "Strip what the readers ADD on the way out, leaving what was written. What you hand back to
     a writer, so a round trip does not persist the reader's own annotations."
    {:signature [:=> [:catn [:entry LedgerEntry]] :map]})
  (Operation entries-of
    "Every entry of a kind, oldest first, each parsed through the READ contract."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:kind :keyword]] [:sequential LedgerEntry]]
     :delegates [read-ws report/parse-event]})
  (Operation entry-at-seq
    "The entry at one position, parsed through the READ contract — how a record cites another
     and gets back what it cited rather than what has since been written."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:seq-n :int]] [:maybe LedgerEntry]]
     :delegates [read-ws report/parse-event]})
  (Operation list-ids
    "Every workstream id a project has."
    {:signature [:=> [:catn [:project ProjectName]] [:vector WorkstreamId]]
     :delegates [state/workstreams-dir]})
  (Operation delete!
    "Remove a workstream and everything under it. Idempotent."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [state/workstream-dir]})
  (Operation find-by-ref
    "The workstream answering to an external ref from a named adapter, or nil."
    {:signature [:=> [:catn [:project ProjectName] [:adapter :keyword] [:external-id :string]] [:maybe Workstream]]
     :delegates [list-ids read-ws]})
  (Operation find-by-ref-id
    "The workstream carrying this external id, whichever adapter raised it."
    {:signature [:=> [:catn [:project ProjectName] [:external-id :string]] [:maybe Workstream]]
     :delegates [list-ids read-ws]})
  (Operation append-to-ref!
    "Append to whichever workstream carries this external id — how an inbound event reaches its
     workstream without the caller knowing the id."
    {:signature [:=> [:catn [:project ProjectName] [:external-id :string] [:entry :map] [:content :string]] [:maybe Path]]
     :delegates [find-by-ref-id append-entry!]})
  (Operation add-ref!
    "Append an external ref, deduped on adapter and id.

     A GitHub ref carries a second obligation: it IS the pull request, so stamping it also files
     the `:pr-opened` event. Those were two steps in a skill once and diverged exactly as you
     would expect — the ref is load-bearing, so it always landed; the ledger event was merely
     informative, so it was dropped on more than half the PRs. One fact, one call.

     `:opts` is OPTIONAL rather than a second arity: the two-arity form is a defaults chain, and
     `[:?]` describes the contract where `[:function …]` would describe the implementation."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:ref :map]
                            [:opts [:? [:maybe :map]]]] Workstream]
     :delegates [read-ws write! append-entry!]})
  (Operation engagement
    "Whether anyone is engaged with this workstream, from its own sessions."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :keyword]
     :delegates [read-ws session/list-sessions session/engagement-state]}))
