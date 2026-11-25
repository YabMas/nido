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
            [canvas.coordinator.record.entry :as entry]
            [canvas.coordinator.report :as report]
            [canvas.core.client :as client]
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
  "One immutable append on a workstream's ledger: its `:seq`, its `:kind`, and the content
   holding what was said, kept by the core under that seq. The payload is a `LedgerEvent`; this is the envelope around it.

   Immutability is load-bearing rather than tidy: a design record CITES a baseline by seq, so an
   entry that could be rewritten would silently move the ground under every judgement made
   against it.")

(Module record-workstream
  "The workstream: its record, its stage, and the append-only ledger everything else cites.

   The ledger is the part with teeth, and the core holds it: identity and entries are core
   commands, committed by the core's writer and read back from its store. An append names the
   seq its author read, and the core refuses it when the ledger has moved since, so no two
   authors can number the same entry. The cross-record checks an append runs read other
   workstreams, so they run here, before the command, under a per-workstream lock that
   serialises nido's own multi-step writers; the core's compare-and-append is what makes the
   number right whatever holds the lock.

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
  (Operation turn-ws
    "The workstream the core turn this process runs in works on, from the environment the core
     gives every turn, or nil outside every turn."
    {:signature [:=> [:catn [:env [:? [:maybe [:map-of :string :string]]]]] [:maybe [:tuple :keyword :string]]]})
  (Operation read-ws
    "One workstream by id, or nil: its identity and its ledger's index as the core holds them,
     without the stored status fields older records carry. A project's sweep reads every
     workstream of it in one request and serves reads from that for a moment; a read under the
     workstream's lock always asks the core."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe Workstream]]
     :delegates [client/state client/states]})
  (Operation project-records
    "Every workstream record of a project the core holds, in id order, read in one request — the
     open ones only on request, as the core's summary says — with every closure's entry fetched
     beside it."
    {:signature [:=> [:catn [:project ProjectName] [:opts [:? :map]]] [:vector Workstream]]
     :delegates [client/project-states entry/prime!]})
  (Operation over-project
    "Call a function with a project's records read in one request, every read-ws of one of them in
     its extent answered from that read outside the append lock."
    {:signature [:=> [:catn [:project ProjectName] [:f fn?]] :any]
     :delegates [project-records client/project-states entry/prime!]})
  (Operation pass-state
    "The committed state of a workstream the enclosing over-project read, or nil."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]})
  (Operation ledger-stamps
    "A stamp for every workstream of a project, read off its summaries in one request: a value
     that moves whenever its ledger, identity or coordination does."
    {:signature [:=> [:catn [:project ProjectName]] [:vector [:tuple WorkstreamId :any]]]
     :delegates [client/summaries]})
  (Operation record-of-state
    "The workstream record a committed core state holds for an id — what read-ws answers for it —
     or nil, for a reader the core handed states in bulk."
    {:signature [:=> [:catn [:ws-id WorkstreamId] [:state :map]] [:maybe Workstream]]})
  (Operation create!
    "Mint an id and persist a fresh workstream, holding no status — where it is placed, chosen to
     be or settled is what its ledger comes to record."
    {:signature [:=> [:catn [:project ProjectName] [:base :map]] Workstream]
     :delegates [mint-id validate client/command! clock/now-iso]})
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
     :delegates [read-ws client/command!]})
  (Operation set-title!
    "Overwrite a workstream's generated display title, with the basis it was generated from."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:text :string]
                      [:basis :string]] Workstream]
     :delegates [read-ws client/command!]})
  (Operation clear-title!
    "Remove a workstream's generated display title."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Workstream]
     :delegates [read-ws client/command!]})
  (Operation close!
    "Settle a workstream: append a :closed entry naming the outcome."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:outcome :keyword]] Workstream]
     :delegates [read-ws append-lock-path]})
  (Operation reopen!
    "Un-settle a workstream: append a :reopened entry naming the stage chosen for it, after which
     its close no longer stands."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:stage :keyword]] Workstream]
     :delegates [read-ws closure newest-choice append-lock-path]})
  (Operation activation
    "A person's last word on nido's recipe for a workstream — :activated or :deactivated, the
     newer of the two entries — or nil when neither was said."
    {:signature [:=> [:catn [:w :any]] [:maybe [:enum :activated :deactivated]]]})
  (Operation activate!
    "Record a person's grant that nido's recipe runs on a workstream: an :activated entry, written
     only while the recipe does not run and never on a closed workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session [:maybe :string]]] Workstream]
     :delegates [read-ws closure activation append-lock-path]})
  (Operation pause
    "A person's standing pause on a workstream: its newest :paused index row with no :resumed
     after it, or nil."
    {:signature [:=> [:catn [:w :any]] [:maybe :map]]})
  (Operation pause!
    "Record a person's choice not to push a workstream forward for now: a :paused entry with
     their note, written only while no pause stands and never on a closed workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:note [:maybe :string]]] Workstream]
     :delegates [read-ws closure pause append-lock-path]})
  (Operation resume!
    "Lift a person's pause: a :resumed entry, written only while a pause stands."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] Workstream]
     :delegates [read-ws pause append-lock-path]})
  (Operation deactivate!
    "Record a person taking nido's recipe off a workstream: a :deactivated entry, written only
     while the recipe runs."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session [:maybe :string]]] Workstream]
     :delegates [read-ws activation append-lock-path]})
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
  (Operation append-entry!
    "Append an immutable entry and record it in the index, under the workstream's lock. Answers
     the seq the core committed it at — which entry this append wrote, since the ledger's newest
     entry, reread once the lock is released, may be another writer's."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:entry :map] [:content :string]] :int]
     :delegates [append-lock-path read-ws client/command! report/entry-payload
                 generation/opened-by generation/current generation/generation-of
                 generation/closed-design generation/fixed-ids generation/defining-ids]})
  (Operation append-decision!
    "Append a :design-decision — the kind's one writer, which a decision round alone calls; every
     generic append refuses the kind. Every other check an append makes holds."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:content :string]] :int]
     :delegates [append-lock-path read-ws client/command! report/entry-payload
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
                       [:payload [:or :map [:=> [:cat Workstream] :map]]]] :int]
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
  (Operation appending-drain!
    "Answer a function, under the workstream's append lock, of the workstream read under it and an
     append that takes only a drain's kinds — the one path they join a ledger by, which the generic
     appends refuse. Every other check an append makes holds."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:f [:=> [:cat :map] :any]]] :any]
     :delegates [append-lock-path read-ws]})
  (Operation stands-landed?
    "Whether a workstream stands landed: its ledger holds a :merged, or it is closed done. The one landed reading of a slice."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id :any]] :any]
     :delegates [read-ws]})
  (Operation slice-review-passed?
    "Whether a slice's newest review citing the recipe version it runs under passed — :clean or
     :converged. A newer unresolved review withdraws an older pass. The one reading of a slice's
     readiness."
    {:signature [:=> [:catn [:w :any]] :any]})
  (Operation slice-unlandable
    "Why a slice may not land yet — a plan its plan rests on not started as a landed slice
     (:rests-unlanded), else its review not passed (:not-ready) — or nil. The one predicate of a
     slice being landable: its landing's append, lane-drain's ask and the pre-push gate read it."
    {:signature [:=> [:catn [:project ProjectName] [:w :any]] :any]
     :delegates [read-ws slice-review-passed?]})
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
     :delegates [append-lock-path read-ws client/command! phase/progress]})
  (Operation latest-seq
    "The seq of the newest entry on a workstream's ledger as the core holds it now, or 0 — what an
     author names as the entry it read before appending after it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :int]
     :delegates [read-ws]})
  (Operation ledger-stamp
    "A value that moves whenever a workstream's ledger or identity does, for a cache keyed on what
     changed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [read-ws]})
  (Operation entry-text
    "The content of one entry of a workstream record, as it was written, or nil."
    {:signature [:=> [:catn [:w :any] [:seq-n :int]] [:maybe :string]]
     :delegates [entry/text]})
  (Operation check-entry
    "Throw what an append of this entry would throw now, and write nothing — for an author checking
     a record before handing it over, never a guard in place of the append's own."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:entry :map] [:content :string]] :nil]
     :delegates [read-ws report/entry-payload]})
  (Operation append-entry-at!
    "Append only if the ledger's latest entry is still the one the caller read. The optimistic
     lock that stops two people deciding the same thing from both writing the decision. Answers
     the seq committed, or a refusal naming the ledger's latest seq."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:expected-seq :int] [:entry :map] [:content :string]] [:or :int :map]]
     :delegates [append-lock-path read-ws client/command! report/entry-payload
                 generation/opened-by generation/current generation/generation-of
                 generation/closed-design generation/fixed-ids generation/defining-ids]})
  (Operation append-entry-once!
    "Append an entry only when none of its kind is already the one the caller's identity names — an
     entry an interrupted append wrote included, which a read holds — rather than write a second.
     Answers {:appended seq} or {:existing seq}."
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
    "Every workstream id a project has in the core, from one read of every workstream of it."
    {:signature [:=> [:catn [:project ProjectName]] [:vector WorkstreamId]]
     :delegates [client/states]})
  (Operation delete!
    "Discard a workstream in the core and remove its sessions and Runs, leaving the legacy ledger
     files where they are. Idempotent. An open unit or a turn still running on it stops the
     discard — a merged or retracted unit does not: the core refuses, the refusal is
     thrown, and nothing is removed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [client/command! state/workstream-dir]})
  (Operation find-by-ref
    "The workstream answering to an external ref from a named adapter, or nil."
    {:signature [:=> [:catn [:project ProjectName] [:adapter :keyword] [:external-id :string]] [:maybe Workstream]]
     :delegates [project-records]})
  (Operation own-pr
    "The :github ref of a workstream naming a pull request — as `owner/repo#n`, or as `n` when
     exactly one of its refs has that number — or nil: what nido knows as the workstream's own PR."
    {:signature [:=> [:catn [:w :any] [:pr :string]] [:maybe :map]]})
  (Operation unspent-grants
    "The grants a person gave the sessions of a workstream that are still unspent, each as its act
     (:kind) and the :seq of the entry that gave it: the :grants of its :blocker-answered and
     :grant entries that are the person's own acts — not an answer nido recorded for an act, an
     entry nido wrote, or a grant a design round carried forward — each unspent until an entry of
     its act's spending kind follows it (:intent by an :intent, :merge by a :merged). The one
     reading of a grant's spending: the conductor's refusal and instruction and the pipeline's
     position all read it here."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]]
                 [:sequential [:map [:kind :keyword] [:seq :int]]]]
     :delegates [entries-of]})
  (Operation find-by-ref-id
    "The workstream carrying this external id, whichever adapter raised it."
    {:signature [:=> [:catn [:project ProjectName] [:external-id :string]] [:maybe Workstream]]
     :delegates [project-records]})
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
    {:signature [:=> [:catn [:project ProjectName] [:external-id :string] [:entry :map] [:content :string]] [:maybe :int]]
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
     :delegates [read-ws client/command! append-entry!]})
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
