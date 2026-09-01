(ns canvas.coordinator.work
  "Self-spec: `nido.coordinator.work` — the single vocabulary every surface wraps."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.drive :as pipeline]
            [canvas.coordinator.lane.work :as lanes]
            [canvas.coordinator.record.session :as session :refer [Session]]
            [canvas.coordinator.source.core :as queue]
            [canvas.coordinator.record.triggers :as triggers]
            [canvas.coordinator.record.state :refer [Path SessionName WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream :refer [Workstream]]
            [canvas.coordinator.view.workstreams :as view]
            [canvas.integration.notion :refer [NotionToken]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.lifecycle :as lifecycle]
            [fukan.common.typing.malli]))

(Kind Gate
  "A workstream that wants a person now: what it is waiting on, and the follow-actions available.

   The gate is DERIVED from the workstream's own position and its parked halt, never stored — so
   a gate cannot outlive the thing it was gating.")

(Kind Screen
  "The whole rendered state of a surface, from view-state alone. Pure: the same view-state always
   produces the same screen, which is what lets the TUI and the dashboard render the same board
   without either being the definition of it.")

(Module coordinator-work
  "The work plane's vocabulary: what the surfaces read, and every mutation they may make.

   ONE spine — intake, triage, ready, in-progress, done — with scratch folded in at in-progress
   and runs presented as autonomous sessions. Surfaces render and route; all model logic lives
   here, which is what stops a TUI and a dashboard disagreeing about what stage something is at.

   Ships as a PROJECTION over today's storage. Nothing here has its own records: every reading
   is derived from workstreams, sessions and the ledger, so the facade could be replaced without
   a migration."
  {:child [Gate Screen]}
  (Operation tab-bands "The ordered stage bands a tab shows."
    {:signature [:=> [:catn [:tab :keyword] [:grouped :map]] :any]})
  (Operation option-action? "Whether an action id names one of a blocker's options."
    {:signature [:=> [:catn [:action-id :any]] :boolean]})
  (Operation position-carrying-action?
    "Whether an action must be rendered with the ledger position it was derived from — the
     optimistic lock that stops two people deciding the same thing twice."
    {:signature [:=> [:catn [:action-id :any]] :boolean]})
  (Operation awaiting-human
    "The stage a PERSON owes a position, or nil when the next move is nido's own. What makes a
     grant reachable: the round that decides a design runs as a task and parks nobody, so a
     workstream waiting to be approved has no parked session to be read from."
    {:signature [:=> [:catn [:position [:maybe :map]]] [:maybe :keyword]]})
  (Operation gate-actions "The follow-actions for a gate, from its stage and halt."
    {:signature [:=> [:catn [:stage :keyword] [:parked? :boolean] [:origin [:? :any]]
                            [:opts [:? :map]]] :any]})
  (Operation classify-origin "Where a workstream came from, from its raw record."
    {:signature [:=> [:catn [:ws Workstream]] :keyword]})
  (Operation with-shared-rows
    "Run a thunk with one read of each project's rows shared across it. The board asks two
     questions of the same rows, and this is what makes them one read rather than two."
    {:signature [:=> [:catn [:f :any]] :any]})
  (Operation list-workstreams "Every workstream of a project, as enriched rows on the spine."
    {:signature [:=> [:catn [:project ProjectName] [:live-names [:? :any]]] [:vector :map]]
     :delegates [view/workstream-rows]})
  (Operation winding-down
    "Workstreams that are FINISHED and still holding a live session — the ones costing memory
     for work nobody is doing."
    {:signature [:=> [:catn [:project ProjectName] [:live-names :any] [:rows [:? :any]]] [:vector :map]]})
  (Operation grouped "A project's workstreams grouped along the spine."
    {:signature [:=> [:catn [:project ProjectName] [:live-names [:? :any]]] :map]
     :delegates [list-workstreams]})
  (Operation latest-report "The workstream's most recent ledger entry."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]})
  (Operation environment "The workstream's current environment — its latest live session."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [session/list-sessions]})
  (Operation holds
    "The three records that CURRENTLY hold for a workstream — what it is standing on right now,
     as against everything its ledger ever said."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/latest-entry]})
  (Operation workstream "Full detail for one workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:selected-seq [:? :any]]] :map]
     :delegates [classify-origin holds latest-report environment workstream/read-ws]})
  (Operation gates "The workstreams of a project that want a person now."
    {:signature [:=> [:catn [:project ProjectName] [:live-names [:? :any]]] [:vector Gate]]
     :delegates [list-workstreams gate-actions]})
  (Operation gate "Full gate detail for one workstream, or nil when it is not gating."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe Gate]]
     :delegates [gates]})
  (Operation all-gates "Gates across every registered project."
    {:signature [:=> [:catn] [:vector Gate]] :delegates [gates]})
  (Operation default-target "Where a new or promote gesture lands by default."
    {:signature [:=> [:catn [:project ProjectName] [:action :keyword]] :keyword]})
  (Operation set-stage!
    "Move a workstream to a stage. THE single mutation behind every board gesture — one place
     that writes the spine, so a surface cannot invent a transition."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:target :keyword]] :any]
     :delegates [workstream/advance-stage!]})
  (Operation dismiss! "Take a workstream off the radar, recording that it was dismissed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [workstream/close!]})
  (Operation restore! "Undo a dismissal, making the ticket re-triable."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [workstream/reopen!]})
  (Operation apply! "Accept a parked triage verdict without resuming the agent."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]})
  (Operation start-triage-page! "Force a triage for a watched row that has no workstream yet."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]})
  (Operation option-input "What choosing one of a blocker's options resumes the agent with."
    {:signature [:=> [:catn [:i :int] [:option :map]] :string]})
  (Operation resolve-gate!
    "Apply a gate follow-action. Position-carrying actions check the ledger has not moved, which
     is what makes two people clicking the same button safe."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:action-id :any]
                            [:payload [:? :any]]] :map]
     :delegates [option-input set-stage!]})
  (Operation new! "Birth a scratch workstream and bring its session up."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName]] :any]
     :delegates [lanes/birth! lifecycle/up!]})
  (Operation open-target "Where opening a workstream lands."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [session/list-sessions]})
  (Operation reclaimed? "Whether a session's substrate was reclaimed under it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session :any]] :boolean]})
  (Operation ensure-open! "Make a session landable, re-provisioning it if it was reclaimed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session :any]] :any]})
  (Operation facet-dimensions "The facet keys available for a source in a project."
    {:signature [:=> [:catn [:project ProjectName] [:source [:? :any]]] :any]})
  (Operation grouped-rows "Every row in a grouped map, flat."
    {:signature [:=> [:catn [:grouped :map]] :any]})
  (Operation facet-values "The distinct values a facet takes across a project."
    {:signature [:=> [:catn [:project ProjectName] [:k :keyword]] :any]})
  (Operation facet-match? "Whether a row satisfies every active facet selection."
    {:signature [:=> [:catn [:facet-filter :map] [:row :map]] :boolean]})
  (Operation session-live?
    "Whether a registry-shaped session holds a port RIGHT NOW. The registry records what was
     started; the port is what says it is still there."
    {:signature [:=> [:catn [:s :map]] :boolean]})
  (Operation live-session-names "The sessions of a project that are actually up."
    {:signature [:=> [:catn [:project ProjectName]] :any] :delegates [session-live?]})
  (Operation prune-dead-registry! "Drop registry entries whose session no longer holds a port."
    {:signature [:=> [:catn [:now-ms [:? :int]]] :any] :delegates [session-live?]})
  (Operation bring-down! "Down every live session of a workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [session/list-sessions lifecycle/down!]})
  (Operation orphan-live-sessions "The live sessions no workstream owns. Pure."
    {:signature [:=> [:catn [:live :any] [:owned :any]] :any]})
  (Operation adopt-orphans!
    "Enforce the invariant that every live session is reachable from a workstream — an orphan is
     a session doing work the board cannot show."
    {:signature [:=> [:catn [:project ProjectName]] :any]
     :delegates [orphan-live-sessions live-session-names lanes/birth!]})
  (Operation machine-rows "Machine facts for every worktree of one project."
    {:signature [:=> [:catn [:project-name ProjectName] [:project-dir Path]] [:vector :map]]})
  (Operation machine-facts "Machine facts for named sessions, keyed by name."
    {:signature [:=> [:catn [:project ProjectName] [:names :any]] :map] :delegates [machine-rows]})
  (Operation all-machine-rows "Machine rows across every registered project, live first."
    {:signature [:=> [:catn [:rows-fn [:? :any]] [:projects [:? :any]]] [:vector :map]]})
  (Operation proposals "Every proposal this project's review analyses have made."
    {:signature [:=> [:catn [:project ProjectName]] [:vector :map]]
     :delegates [workstream/list-ids]})
  (Operation decide-proposal! "Record a human decision about one proposal."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:decision :map]] :map]
     :delegates [workstream/append-entry-at!]})
  (Operation grantable?
    "May a human grant this workstream's design right now?

     `lane-pipeline/design-decided?` under a WorkPlane name. Two surfaces render the Approve
     button and one of them is Surface, which may not reach Lane — so without this the pane
     would have to ask a different question, and it did: it read the recommendation off the
     report the button was rendered from, which is a different answer the moment a design told
     to proceed is later sent back."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean]
     :delegates [pipeline/design-decided?]})
  (Operation record-landing!
    "Record that an approved proposal is now carried by a revision.

     No position guard, unlike `decide-proposal!`: that guard makes a decision an honest answer
     to the page it was read from, and a landing is not an answer to a page — it is a fact about
     the repository, true whatever the ledger has grown since."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:opts :map]] :map]})
  (Operation backfill-landings!
    "Discharge every approval whose note already says what became of it, from before there was a
     landing record. Idempotent — the ledger has no delete."
    {:signature [:=> [:catn [:project ProjectName]] :map]
     :delegates [proposals record-landing!]})
  (Operation all-grouped "Grouped boards across every registered project."
    {:signature [:=> [:catn] [:vector :map]] :delegates [grouped]})
  (Operation screen
    "The single pure derivation from view-state to what a surface shows. Pure, so the TUI and
     the dashboard render the same board without either being its definition."
    {:signature [:=> [:catn [:view-state :map]] Screen]})
  (Operation pickup! "Resolve a pasted reference into a workstream."
    {:signature [:=> [:catn [:project ProjectName] [:input :string] [:token NotionToken]] :map]
     :delegates [lanes/pickup!]})
  (Operation start-intent!
    "Start work from a description: enqueue it for the daemon, which mints the ref-less
     workstream and hands the text to an agent as its first message.

     No lane, because there is no decision left for one to hide — `spawn/ensure-workstream!`
     already creates a workstream for a payload carrying no external ref, and the agent writes
     the `:intent`. A lane here would advance nothing and hide nothing, which by this project's
     own vocabulary is a file rather than a module.

     Enqueues through `Source` rather than through `control/fire!`, which builds the same
     envelope: `WorkPlane` declares no `Control` edge, and a facade reaching a peer facade is
     the wrong shape. That `fire!` belongs in the queue's own vocabulary is FU-39.

     Resolves the leg through `Record` BEFORE it writes: a project that declares no
     `:start-intent` trigger is refused here, rather than by an envelope the daemon drains,
     routes to nothing and drops to stderr."
    {:signature [:=> [:catn [:project ProjectName] [:text :string]] :map]
     :delegates [triggers/load-for-project triggers/find-by-name queue/enqueue!]})
  (Operation file-findings! "File a findings round on a shipped workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:opts :map]] :map]})
  (Operation session-started! "Tell the work plane a session came up."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName]] :any]
     :delegates [lanes/birth! lifecycle/session-weight]})
  (Operation session-destroyed! "Tell the work plane a session was destroyed."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName]] :any]
     :delegates [lanes/reap!]})

  (Operation record-plan!
    "Append one day's plan of the owed proposals into claims, or refuse it.

     Derives the owed set ITSELF rather than trusting the caller's, because the generic
     ledger boundary validates an entry's shape and has no access to it — a plan appended
     any other way would satisfy every schema and none of the claim. Derivation and append
     happen under the append locks of every workstream the frontier names, so nothing can
     be decided or landed between deciding what is owed and writing the partition down."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:plan :map]] :map]})
  (Operation reserve-claim!
    "Fix a claim's veto deadline, or refuse it because a covered address was declined.

     The deadline is a ledger entry rather than a moment inside an agent, so the board can
     answer whether a late decline stopped anything. Eligibility is re-derived under the
     append lock of every workstream the claim's addresses live in, and the reservation is
     appended while they are held — to the claim's OWN workstream, which is not among them."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:claim :map]] :map]})
  (Operation discharge-claim!
    "Push a revision and record what it carried, or refuse — the only path by which the
     sweep reaches nido's main.

     Refuses without a standing reservation, which is what makes the veto a restriction
     rather than an instruction. The push happens under no lock. Re-runnable: an
     interruption between the push and the last append leaves the workstream open, and
     running it again appends only for the addresses that do not already carry a landing."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:claim :map]] :map]
     :delegates [lifecycle/advance-remote!]}))
