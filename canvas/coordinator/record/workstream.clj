(ns canvas.coordinator.record.workstream
  "Self-spec: `nido.coordinator.record.workstream` — the source-agnostic spine."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.clock :as clock]
            [canvas.coordinator.record.generation :as generation]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.phase :as phase]
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
    "One workstream by id, or nil, without the stored status fields older records carry. Reads
     its index as the entries directory has it: an absent index rebuilt, a row naming a file the
     directory does not hold under that :seq and kind replaced or dropped, and every other row
     read as the row kept beside its entry — kept there by this read when none was, which freezes
     the facts only the index held. A file no row names — an append that died before indexing
     it, having passed the append's checks and owing the record nothing else — reads as indexed
     at its :seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe Workstream]]
     :delegates [state/workstream-edn-path]})
  (Operation write!
    "Validate, then write atomically. A reader never observes half a record."
    {:signature [:=> [:catn [:w Workstream]] Workstream]
     :delegates [validate state/workstream-edn-path]})
  (Operation create!
    "Mint an id and persist a fresh workstream, holding no status — where it is placed, chosen to
     be or settled is what its ledger comes to record."
    {:signature [:=> [:catn [:project ProjectName] [:base :map]] Workstream]
     :delegates [mint-id write! clock/now-iso]})
  (Operation advance-stage!
    "Choose a stage for a workstream: append a :stage-set entry naming it and who decided. A no-op
     when the newest choice already names it, so a repeated event does not litter the ledger —
     save that a person's choice is recorded even then, validated like any other."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:new-stage :keyword]
                      [:by [:? [:enum :person :nido :backfill]]]] Workstream]
     :delegates [read-ws newest-choice append-lock-path]})
  (Operation set-facets!
    "Overwrite a workstream's classification facets."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:facets :map]] Workstream]
     :delegates [read-ws write!]})
  (Operation set-title!
    "Overwrite a workstream's generated display title, with the basis it was generated from."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:text :string]
                      [:basis :string]] Workstream]
     :delegates [read-ws write!]})
  (Operation clear-title!
    "Remove a workstream's generated display title."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Workstream]
     :delegates [read-ws write!]})
  (Operation close!
    "Settle a workstream: append a :closed entry naming the outcome."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:outcome :keyword]] Workstream]
     :delegates [read-ws append-lock-path]})
  (Operation reopen!
    "Un-settle a workstream: append a :reopened entry naming the stage chosen for it, after which
     its close no longer stands."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:stage :keyword]] Workstream]
     :delegates [read-ws closure newest-choice append-lock-path]})
  (Operation append-lock-path
    "The lock serialising appends to ONE workstream — per workstream, so two unrelated ones
     never queue behind each other."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Path]
     :delegates [state/append-lock-path]})
  (Operation newest-record
    "The index row of the newest entry that records the work rather than a status decision —
     what every reader asking `what happened last` means."
    {:signature [:=> [:catn [:w Workstream]] [:maybe :map]]})
  (Operation newest-goal
    "The goal the newest :intent entry states, or nil — what the work is for, once anyone has
     said."
    {:signature [:=> [:catn [:w Workstream]] [:maybe :string]]})
  (Operation rebuilt-index
    "The index the entries directory implies: each entry's row as kept beside it, else the row the
     given record's index holds, else the row its file name implies — a kept or held row only when it names that file. The
     index is a cache of this."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:w :any]] [:vector :map]]
     :delegates [state/workstream-dir]})
  (Operation freeze-index!
    "Keep beside every indexed entry the row its index holds, for each that has none — the only
     copy of an earlier entry's :at and :amended-by — so the index can be deleted and rebuilt.
     `read-ws` is what keeps them; this reads under the lock and counts what that read kept."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :int]
     :delegates [append-lock-path read-ws state/workstream-dir state/workstream-edn-path]})
  (Operation append-entry!
    "Append an immutable entry and record it in the index, under the workstream's lock."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:entry :map] [:content :string]] Path]
     :delegates [append-lock-path read-ws write! report/entry-payload
                 generation/opened-by generation/current generation/generation-of
                 generation/closed-design generation/fixed-ids generation/defining-ids]})
  (Operation open-generation!
    "Append a :generation a person gives, opening the next generation of the design — the kind's
     one writer, under the workstream's lock. Refused while the workstream waits between phases,
     while a review run of it is live — naming the run — or unless it cites a :recut of the
     generation it closes. The tensions that re-cut names are
     carried on it by value from the ledger."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:gen :map]] Workstream]
     :delegates [append-lock-path read-ws generation/current generation/generation-of]})
  (Operation append-stamped!
    "Append a :tension, :accord, :withdrawal, :recut or :conduct-decision whose writer its caller stamped — with
     appending!, the one path these kinds join a ledger by, which the generic appends refuse. Every other check an
     append makes holds. The payload may be a function of the workstream read under the lock, so
     a writer is stamped against the generation its entry joins."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]
                       [:payload [:or :map [:=> [:cat Workstream] :map]]]] Path]
     :delegates [append-lock-path read-ws
                 generation/opened-by generation/current generation/generation-of
                 generation/closed-design generation/fixed-ids generation/defining-ids]})
  (Operation appending!
    "Answer a function, under the workstream's append lock, of the workstream read under it, a check
     throwing what appending an entry now would throw and writing nothing, and an append against the
     ledger as the last append left it — a stamped kind on append-stamped!'s terms — so a caller
     checks every entry before writing any, writes several, and acts on each once it is taken, as
     one step no other append, generation's opening or design approval falls between."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:f [:=> [:cat :map] :any]]] :any]
     :delegates [append-lock-path read-ws]})
  (Operation holding-ledger
    "Answer a function of the workstream read under its append lock — the lock every append and a
     generation's opening hold — so what it decides from that read stays true while it runs."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:f [:=> [:cat Workstream] :any]]] :any]
     :delegates [append-lock-path read-ws]})
  (Operation open-phase!
    "Append a :phase-gate and reopen the workstream, under its lock and in one write of the
     record — the kind's one writer. Refused unless the workstream is closed :between-phases, the evidence is not blank, and the
     gate opens the phase immediately after the current one of the plan it cites."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:gate :map]] Workstream]
     :delegates [append-lock-path read-ws write! phase/progress]})
  (Operation highest-seq-on-disk
    "The largest entry number under entries/, or 0.

     The index is a projection and can fall behind the directory it projects; the entries are
     the record. Numbering an append off the index alone is what let one workstream reach 39
     files against 37 rows, silently overwriting two."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :int]})
  (Operation seq-of-path
    "The :seq of the entry file an append returned, read off its name, or nil.

     Which entry an append wrote is answered by what it returned. The ledger's newest entry,
     reread once the lock is released, may be another writer's."
    {:signature [:=> [:catn [:path :any]] [:maybe :int]]})
  (Operation check-entry
    "Throw what an append of this entry would throw now, and write nothing — for an author checking
     a record before handing it over, never a guard in place of the append's own."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:entry :map] [:content :string]] :nil]
     :delegates [read-ws report/entry-payload]})
  (Operation append-entry-at!
    "Append only if the ledger's latest entry is still the one the caller read. The optimistic
     lock that stops two people deciding the same thing from both writing the decision."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:expected-seq :int] [:entry :map] [:content :string]] :map]
     :delegates [append-lock-path read-ws write! report/entry-payload
                 generation/opened-by generation/current generation/generation-of
                 generation/closed-design generation/fixed-ids generation/defining-ids]})
  (Operation append-entry-once!
    "Append an entry only when none of its kind is already the one the caller's identity names — an
     entry an interrupted append wrote included, which a read holds — rather than write a second."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:entry :map] [:content :string] [:same? :any]] :map]
     :delegates [append-lock-path read-ws report/parse-event
                 generation/opened-by generation/current generation/generation-of
                 generation/closed-design generation/fixed-ids generation/defining-ids]})
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
  (Operation amended-seqs
    "The positions of the entries a review loop's amender wrote, read off the index stamp the loop
     alone sets — how a reader tells an amender's rewrite from a person's reply."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:set :int]]
     :delegates [read-ws]})
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
  (Operation newest-choice
    "The newest stage choice on a workstream, whatever has been recorded since."
    {:signature [:=> [:catn [:w :any]] [:maybe :map]]})
  (Operation closure
    "The newest :closed entry, unless a :reopened or a :phase-gate came after it — how the ledger
     says a workstream stands settled, read off its entries and never off a stored field."
    {:signature [:=> [:catn [:w :any]] [:maybe :map]]})
  (Operation find-or-mint-by-ref!
    "The workstream carrying this external id, minting a minimal one when none does — the spine
     every ticket-keyed write shares, so intake-triage and pickup-drive land on one workstream."
    {:signature [:=> [:catn [:project ProjectName] [:external-id :string]] Workstream]
     :delegates [find-by-ref-id create!]})
  (Operation append-to-ref!
    "Append to whichever workstream carries this external id, minting it when none does — how an
     inbound event reaches its workstream without the caller knowing the id."
    {:signature [:=> [:catn [:project ProjectName] [:external-id :string] [:entry :map] [:content :string]] [:maybe Path]]
     :delegates [find-or-mint-by-ref! append-entry!]})
  (Operation add-ref!
    "Append an external ref, deduped on adapter and id.

     A GitHub ref carries a second obligation: it IS the pull request, so stamping it also files
     the `:pr-opened` event. Those were two steps in a skill once and diverged exactly as you
     would expect — the ref is load-bearing, so it always landed; the ledger event was merely
     informative, so it was dropped on more than half the PRs. One fact, one call.

     The event cites the design the PUBLISHED work was done under — the one the publisher names
     in `:opts`, else the one an implementation record listing the PR names — and never the
     newest design, which may have been appended while that work was being done. Where nothing
     names one on a ledger holding a design, the ref is stamped and the event skipped rather
     than guessed.

     `:opts` is OPTIONAL rather than a second arity: the two-arity form is a defaults chain, and
     `[:?]` describes the contract where `[:function …]` would describe the implementation."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:ref :map]
                            [:opts [:? [:maybe :map]]]] Workstream]
     :delegates [read-ws write! append-entry!]})
  (Operation unit-of
    "The unit an entry belongs to, addressed by its root goal's `:seq` — or nil where the
     entry is the WORKSTREAM's rather than any unit's.

     A workstream may hold more than one goal-to-implementation unit, and this is what tells
     them apart. The unit is the citation closure rooted at a goal — an `:intent`, the one
     kind a baseline's and a design's `:intent` may cite: a baseline cites its goal, a design
     cites its baseline and its goal, an implementation and a review cite the design they were
     made under, and an intent citing `:supersedes` continues its unit while one citing
     one marked `:independent` opens another. Only the first intent on a workstream carries
     neither: the append refuses a later one that does not say which it means. The append boundary refuses a
     record reaching more goals than any one of its citations does, so the partition is
     established by CONSTRUCTION rather than asserted here. A record from before that boundary reaching several goals is the
     workstream's, like one reaching none, and so is everything citing it.

     A landing is outside the closure, not missing from it. A `:pr-opened` and a `:merged`
     NAME the design whose work they carry and stand on none of it, so they reach no root and
     are the workstream's — as are a triage, a note and a blocker, which stand on nothing at
     all.

     Resolved and never stored, for the reason every other reading over this ledger is
     derived: a field beside the citations would be a second answer to a question the graph
     already settles, and the one that drifts."
    {:signature [:=> [:catn [:w Workstream] [:seq-n :int]] [:maybe :int]]})
  (Operation goal-amendment
    "How far the amendment of a goal has got, read off citations alone and within that goal's
     unit: the live goal of the chain the given intent is on, the goal it replaced, and the
     unit's newest baseline and its newest design in the current generation's span with whether
     each still reaches a replaced goal. nil when nothing in the unit was built on a replaced goal.
     Facts only — which stages they leave owed, and whether a replacement was judged, are the
     arc's to say."
    {:signature [:=> [:catn [:w Workstream] [:goal-seq :int]] [:maybe :map]]
     :delegates [generation/current-span]})
  (Operation holds-design?
    "Whether a workstream holds a :design at all — a PRESENCE CHECK, and never a citation
     source.

     Every trail record is about work, and cites what that work named: a PR the design its
     publisher names, a merge the PR's own records, a review the design its rounds judged
     against. When nothing names one the writer skips the record and says so, because append
     order is not evidence of which design work was done under; all it needs to know is whether
     a citation was owed at all. A boolean on purpose, so no writer can read it as `which
     design`. PUBLIC because the :pr-opened writer, the merge poller and the review loop each
     ask it, and three implementations of `does this ledger hold a design` would drift."
    {:signature [:=> [:catn [:w Workstream]] :boolean]}))
