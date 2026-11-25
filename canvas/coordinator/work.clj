(ns canvas.coordinator.work
  "Self-spec: `nido.coordinator.work` — the single vocabulary every surface wraps."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.drive :as pipeline]
            [canvas.coordinator.lane.start :as start]
            [canvas.coordinator.lane.work :as lanes]
            [canvas.coordinator.record.session :as session :refer [Session]]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.source.core :as queue]
            [canvas.coordinator.record.triggers :as triggers]
            [canvas.coordinator.record.state :refer [Path RunId SessionName WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream :refer [Workstream]]
            [canvas.coordinator.view.workstreams :as view]
            [canvas.integration.notion :refer [NotionToken]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.casualties :as casualties]
            [canvas.session.lifecycle :as lifecycle]
            [canvas.session.state :as sstate]
            [fukan.common.typing.malli]))

(Kind Gate
  "A workstream that wants a person now: what it is waiting on, and the follow-actions available.

   The gate is DERIVED from the workstream's own position and its parked halt, never stored — so
   a gate cannot outlive the thing it was gating.")

(Kind GateReading
  "What reading every project's gates came to: the gates of the projects the core answered for,
   and, by project name, why each other project could not be read. A project is in exactly one of
   them, so an unread project is never mistaken for one with nothing to ask."
  [:map
   [:gates [:vector :map]]
   [:unavailable [:map-of :string :string]]])

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
  {:child [Gate GateReading Screen]}
  (Operation board-bands "The ordered stage bands the board shows: the work nido drives."
    {:signature [:=> [:catn [:grouped :map]] :any]})
  (Operation option-action? "Whether an action id names one of a blocker's options."
    {:signature [:=> [:catn [:action-id :any]] :boolean]})
  (Operation position-carrying-action?
    "Whether an action must be rendered with the ledger position it was derived from — the
     optimistic lock that stops two people deciding the same thing twice."
    {:signature [:=> [:catn [:action-id :any]] :boolean]})
  (Operation release-candidate?
    "Whether a position is a release candidate: Land offered, owed by nobody — so never a gate."
    {:signature [:=> [:catn [:position [:maybe :map]]] :boolean]})
  (Operation awaiting-human
    "The stage a PERSON owes a position, or nil when the next move is nido's own. What makes a
     grant reachable: the round that decides a design runs as a task and parks nobody, so a
     workstream waiting to be approved has no parked session to be read from."
    {:signature [:=> [:catn [:position [:maybe :map]]] [:maybe :keyword]]})
  (Operation gate-actions "The follow-actions for a gate, from its stage and halt."
    {:signature [:=> [:catn [:stage :keyword] [:parked? :boolean] [:origin [:? :any]]
                            [:opts [:? :map]]] :any]})
  (Operation asks-several?
    "Whether a report is a blocker asking more than one question — answered on its card, one answer
     per question, by :answer-questions."
    {:signature [:=> [:catn [:report [:maybe :map]]] :boolean]})
  (Operation classify-origin "Where a workstream came from, from its raw record."
    {:signature [:=> [:catn [:ws Workstream]] :keyword]})
  (Operation with-shared-rows
    "Run a thunk with one read of each project's rows shared across it. The board asks two
     questions of the same rows, and this is what makes them one read rather than two."
    {:signature [:=> [:catn [:f :any]] :any]})
  (Operation list-workstreams "Every workstream of a project, as enriched rows on the spine."
    {:signature [:=> [:catn [:project ProjectName]] [:vector :map]]
     :delegates [view/workstream-rows]})
  (Operation winding-down
    "Workstreams that are FINISHED and still holding a live session — the ones costing memory
     for work nobody is doing."
    {:signature [:=> [:catn [:project ProjectName] [:live-names :any] [:rows [:? :any]]] [:vector :map]]})
  (Operation grouped "A project's workstreams grouped along the spine."
    {:signature [:=> [:catn [:project ProjectName] [:live-names [:? :any]]] :map]
     :delegates [list-workstreams]})
  (Operation board-stamp
    "A value equal across two calls exactly when what `grouped` answers cannot have changed
     between them. While the process holds its rows, their version, moved only by a refresh.
     Otherwise read off the disk — directory mtimes for the ledger, session and run records, the
     registry and Notion page facts as values — and blind to what never touches disk: a session
     port gone dead."
    {:signature [:=> [:catn [:project ProjectName]] :any]})
  (Operation hold!
    "Make this process hold its rows and start the sweep that keeps them: the surfaces that
     render the board over and over call it, and every board reading answers from what is held
     from then on. Idempotent; `{:sweep? false}` holds with no sweep thread."
    {:signature [:=> [:catn [:opts [:? :map]]] :any]})
  (Operation release!
    "Stop holding: drop every held row, so readings read the records again."
    {:signature [:=> [:catn] :any]})
  (Operation holding? "Whether this process holds its rows."
    {:signature [:=> [:catn] :boolean]})
  (Operation refresh-list!
    "The list refresh a list render makes first: every unsettled workstream, the rows whose own
     Notion page moved and any new workstream, each recomputed only when its stamp moved. Never
     closed history."
    {:signature [:=> [:catn [:project [:? ProjectName]]] :any]})
  (Operation refresh-selected!
    "The selection refresh a render selecting one workstream makes first: that workstream's row
     alone; every other row is drawn as held."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]})
  (Operation sweep!
    "One run of the sweep: every row, closed history included, recomputed if its stamp moved;
     the held casualty reading recomputed."
    {:signature [:=> [:catn] :any]})
  (Operation shown-revivals
    "The casualties a render shows: the sweep's held copy while the process holds its rows,
     `owed-revivals` otherwise."
    {:signature [:=> [:catn] casualties/Unsettled]
     :delegates [owed-revivals]})
  (Operation latest-report "The workstream's most recent ledger entry."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]})
  (Operation environment "The workstream's current environment — its latest live heavy work session, never its conductor's or its chat's record."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [session/list-sessions session/work-session?]})
  (Operation holds
    "The three records that CURRENTLY hold for a workstream — what it is standing on right now,
     as against everything its ledger ever said. The design is the governing one."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/latest-entry standing/governing-design]})
  (Operation workstream "Full detail for one workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:selected-seq [:? :any]]] :map]
     :delegates [classify-origin holds latest-report environment workstream/read-ws]})
  (Operation gates "The workstreams of a project that want a person now."
    {:signature [:=> [:catn [:project ProjectName]] [:vector Gate]]
     :delegates [list-workstreams gate-actions]})
  (Operation replyable?
    "Whether a reply has someone to reach on a workstream: a session waiting on a person's reply,
     or a conductor's escalation owed the person's answer, whoever's session the work runs in."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean]})
  (Operation gate "Full gate detail for one workstream, or nil when it is not gating."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe Gate]]
     :delegates [gates]})
  (Operation read-gates
    "Every registered project's gates, read from the core — one request per project for the
     workstreams a person owes a move — and never from held rows or a ledger fold: the boundary
     where each project's core reply becomes its outcome, its gates or its reason in :unavailable."
    {:signature [:=> [:catn] GateReading] :delegates [classify-origin workstream/record-of-state]})
  (Operation all-gates
    "The gates read-gates reads: every project's the core answered for."
    {:signature [:=> [:catn] [:vector Gate]] :delegates [read-gates]})
  (Operation all-denials
    "Every open workstream with an auto-mode denial in a session nido drives, across projects —
     an operational notice for the Operations page, never a gate."
    {:signature [:=> [:catn] [:vector :map]] :delegates [list-workstreams]})
  (Operation default-target "Where a new or promote gesture lands by default."
    {:signature [:=> [:catn [:project ProjectName] [:action :keyword]] :keyword]})
  (Operation set-stage!
    "Move a workstream to a stage. THE single mutation behind every board gesture — one place
     that writes the spine, so a surface cannot invent a transition. A choice naming the stage
     already stored is still recorded, since status reads choices off the ledger."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:target :keyword]] :any]
     :delegates [workstream/advance-stage!]})
  (Operation dismiss! "Take a workstream off the radar, recording that it was dismissed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [workstream/close!]})
  (Operation restore! "Undo a dismissal, making the ticket re-triable."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [workstream/reopen!]})
  (Operation apply!
    "Accept a triage verdict — execute its writes, then record the acceptance on the ledger,
     and only then start any drive the verdict begins. Resumes nobody. `opts` is what a gate click carries and a CLI apply does not: the ledger position
     the button was rendered at, which a stale click is refused against, and the index of the
     solution direction the human chose."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:opts [:? :map]]] :any]})
  (Operation conclude!
    "Carry a concluded Slack proposal out before anyone accepts it: file the product ticket and
     link it in the thread, or post the doubt mentioning its owner. A thread's verdict proposing
     several tickets is carried out by giving each its own workstream and concluding that. Never
     starts a drive."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]})
  (Operation start-triage-page! "Force a triage for a watched row that has no workstream yet."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]})
  (Operation resolve-gate!
    "Apply a gate follow-action. Position-carrying actions check the ledger has not moved, which
     is what makes two people clicking the same button safe. An action the core answers — an
     option, a reply or a withdrawal of an ask, Land, Promote, Done, Drop — is sent to it as the
     person's keyed command (`core-resolve!`)."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:action-id :any]
                            [:payload [:? :any]]] :map]
     :delegates [set-stage!]})
  (Operation new! "Birth a scratch workstream and bring its session up."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName]] :any]
     :delegates [lanes/birth! lifecycle/up!]})
  (Operation open-target "Where opening a workstream lands — on a work session, never its conductor's or its chat's record."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :map]]
     :delegates [session/list-sessions view/session-rows]})
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
  (Operation live-session-names "The sessions of a project that are actually up."
    {:signature [:=> [:catn [:project ProjectName]] :any] :delegates [sstate/session-live?]})
  (Operation owed-revivals
    "The unsettled casualties a person is owed a revive of, one per workstream: each whose
     workstream is open and holds a session a person works in, carrying that session."
    {:signature [:=> [:catn] casualties/Unsettled]
     :delegates [casualties/unsettled workstream/read-ws workstream/closure session/list-sessions
                 session/person? lifecycle/session-coords]})
  (Operation prune-dead-registry! "Drop registry entries whose session no longer holds a port."
    {:signature [:=> [:catn [:now-ms [:? :int]]] :any] :delegates [sstate/session-live? casualties/record!]})
  (Operation bring-down! "Down every live session of a workstream but its conductor."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :any]
     :delegates [session/list-sessions lifecycle/down!]})
  (Operation orphan-live-sessions "The live sessions no workstream owns. Pure."
    {:signature [:=> [:catn [:live :any] [:owned :any]] :any]})
  (Operation activate!
    "Hand `ws-id` to the core for a person: their command opening its unit, in the working copy
     `:cwd` names when given — that session is then the unit's — else in a session the core
     provisions; given `:brief`, that is the unit's first turn. Activating twice changes nothing.
     Answers {:ws-id :already? :instruction}, the instruction being what the session's agent is to
     do now; throws when the core refuses."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session [:maybe :string]]
                       [:opts [:? [:map [:cwd {:optional true} [:maybe :string]]]]]] :map]})
  (Operation pause!
    "Pause a workstream for a person, with their note: no conductor drives it while the pause
     stands, and every surface reads it paused rather than stalled."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:note [:maybe :string]]] :map]
     :delegates [workstream/pause!]})
  (Operation resume!
    "Lift a person's pause on a workstream."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/resume!]})
  (Operation deactivate!
    "Take nido's recipe off a workstream for a person — its :deactivated entry — and answer whether
     the recipe was running."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:session [:maybe :string]]] :map]
     :delegates [workstream/deactivate! standing/activated?]})
  (Operation start!
    "Bring a session up on the workstream holding it — a fresh one-off when none does — and on that
     workstream's runtime. The workstream is settled first so the session comes up on its runtime
     at once. Idempotent on a live session. A session this start first recorded is carried once it
     is up. Returns the ws-id."
    {:signature [:=> [:catn [:project ProjectName] [:session-name SessionName] [:opts :map]] :any]
     :delegates [start/start-session!]})
  (Operation adopt-orphans!
    "Enforce the invariant that every live session is reachable from a workstream — an orphan is
     a session doing work the board cannot show — and report every session name two workstreams
     hold that it cannot settle by yielding a bare one-off, naming the holders. A yielded one-off
     is counted past its conductor's record, and its conductor retired before it is deleted."
    {:signature [:=> [:catn [:project ProjectName]] :any]
     :delegates [orphan-live-sessions live-session-names lanes/birth!
                 workstream/delete!]})
  (Operation machine-rows "Machine facts for every worktree of one project."
    {:signature [:=> [:catn [:project-name ProjectName] [:project-dir Path]] [:vector :map]]})
  (Operation machine-facts "Machine facts for named sessions, keyed by name."
    {:signature [:=> [:catn [:project ProjectName] [:names :any]] :map] :delegates [machine-rows]})
  (Operation all-machine-rows "Machine rows across every registered project, live first."
    {:signature [:=> [:catn [:rows-fn [:? :any]] [:projects [:? :any]]] [:vector :map]]})
  (Operation session-recovery
    "Session recovery as the Operations page shows it. The door, not the derivation: it gathers
     kept failures, the recovery workstreams of every project declaring a :session-failure trigger
     with their entries, and that trigger's pacing, and hands them to the recovery read model."
    {:signature [:=> [:catn [:opts [:? [:maybe :map]]]] :map]})
  (Operation improvement-holds
    "What holds each project's improvement sweep, and whether anyone is working on it. The
     open :improvement workstreams are the hold itself — the sweep polls on nothing else — so
     this reads them rather than the copy the poll writes to its state file, and pairs each with
     what the core says of it: a hold the core runs no turn on and asks nothing about is one
     nothing will ever release."
    {:signature [:=> [:catn] [:vector :map]]
     :delegates [triggers/load-for-project workstream/project-records
                 session/list-sessions]})
  (Operation review-queues
    "The latest review-queue grooming of every project whose triggers declare the plan trigger —
     its stage, its plan's tickets in rank order each with its proposed writes, and what was
     decided and done with each write. A project that declares the trigger and was never groomed
     is there at stage :none, so a surface can offer to run it."
    {:signature [:=> [:catn] [:vector :map]]
     :delegates [triggers/load-for-project]})
  (Operation decide-review-item!
    "Approve or skip one write of a grooming's plan. Only those two verdicts are recorded; an
     item the plan does not have, or a plan already being applied, is refused without writing."
    {:signature [:=> [:catn [:run-id RunId] [:n :int] [:verdict :keyword]] :map]})
  (Operation begin-review-apply!
    "Freeze a grooming's decisions and queue its apply through the fire the caller hands it —
     the control facade's fire!, since an envelope is something done to the daemon. Refused
     while an item is undecided, when nothing is approved, and once a fire is recorded; a fire
     that throws leaves the plan frozen and unfired, and the next call fires it again."
    {:signature [:=> [:catn [:run-id RunId] [:fire [:=> [:cat :keyword :map :string] :any]]] :map]})
  (Operation proposals "Every proposal this project's review analyses have made."
    {:signature [:=> [:catn [:project ProjectName]] [:vector :map]]
     :delegates [workstream/project-records]})
  (Operation decide-proposal! "Record a human decision about one proposal."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:decision :map]] :map]
     :delegates [workstream/append-entry-at!]})
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
  (Operation held-candidates
    "The release candidates among the rows this process already holds, building and refreshing
     none: what a screen with no board shows of them."
    {:signature [:=> [:catn] [:vector :map]]})
  (Operation screen
    "The single pure derivation from view-state to what a surface shows. Pure, so the TUI and
     the dashboard render the same board without either being its definition."
    {:signature [:=> [:catn [:view-state :map]] Screen]})
  (Operation pickup! "Resolve a pasted reference into a workstream."
    {:signature [:=> [:catn [:project ProjectName] [:input :string] [:token NotionToken]] :map]})
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
  (Operation core-open?
    "Whether the core holds workstream `ws-id` open: it has heard of it, and has not closed it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean]})
  (Operation core-ask-card
    "The question card for an operator ask, in the Ask shape every card renders: its options
     lettered, each given by an :option-<letter> action, beside a free answer and a withdrawal."
    {:signature [:=> [:catn [:ask :map]] :map]})
  (Operation core-resolve!
    "Do what a gate action asks of the core, as the person's keyed command; nil when the action is
     not one the core answers. An option or a reply answers the oldest open ask, a withdrawal takes
     it back, Land grants the landing, Promote opens the workstream's unit, Done and Drop retract
     its open units and close it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:action-id :any] [:payload [:? :any]]]
                 [:maybe :map]]})
  (Operation core-merged!
    "Tell the core that what a workstream's open units built has landed, keyed by the landing so a
     retry is answered rather than repeated, and close it. Whether it is closed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:landing :string]] :boolean]})
  (Operation person-says!
    "What a person said to a workstream outside a gate — from a bb verb, or passed on by the
     workstream's side chat — as their commands to the core: words answer the oldest ask awaiting
     them, or are posted to the conductor's chat when none does; images, named by digest, go with
     the words to the conductor's chat; each grant is given. Answers {:answered :posted :granted
     :refused}."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:words [:maybe :string]]
                       [:grants [:maybe [:sequential :keyword]]]
                       [:images [:? [:maybe [:sequential :string]]]]] :map]})
  (Operation external-ref
    "The external ref an event payload names, or nil when it carries no usable id."
    {:signature [:=> [:catn [:payload :map]] [:maybe :map]]})
  (Operation ensure-workstream!
    "The workstream an arrival's payload is for: the one its ref already names, or a new one
     stamped with the payload's facets, born running nido's recipe unless the arrival's skill
     carries its own."
    {:signature [:=> [:catn [:project ProjectName] [:payload :map] [:own-recipe? [:? :boolean]]] Workstream]})
  (Operation fire-unit
    "The :open-unit command a trigger's fire opens as `unit` toward `goal`: its first turn is the
     trigger's skill on the rendered payload, run once the unit's session is up."
    {:signature [:=> [:catn [:routed :map] [:unit :string] [:goal :string]] :map]})
  (Operation open-fire!
    "Open the unit a spawning fire asks for, on the workstream its ref names or a new one, keyed by
     the fire so a re-emit is answered rather than repeated. Answers :opened, :running, :settled or
     :refused."
    {:signature [:=> [:catn [:routed :map]] :keyword]})
  (Operation enqueue-inbox!
    "Pen a queue-mode fire for the person, deduped on its ref and placed at :incoming. The core is
     told nothing until the person promotes it."
    {:signature [:=> [:catn [:routed :map]] Workstream]})
  (Operation expire-stale!
    "Drop every penned workstream still at :incoming and older than `max-age-ms`, in its work
     records and in the core. Answers their ids."
    {:signature [:=> [:catn [:project ProjectName] [:max-age-ms :int] [:now-ms :int]] [:vector :string]]})
  (Operation drain-fire!
    "Carry out one fire a drain source emitted, with lane-drain reaching the core."
    {:signature [:=> [:catn [:fire :any]] :any]})
  (Operation drain-wakes!
    "Wake each open drain's coordinator for each event it has not been woken for."
    {:signature [:=> [:catn [:project ProjectName]] :any]})
  (Operation drain-land!
    "The person landing a slice, granted in the core."
    {:signature [:=> [:catn [:project ProjectName] [:slice-id WorkstreamId]] :map]})
  (Operation advance-phase!
    "Assert a phased workstream's gate with its evidence and open its next phase. A gate that
     will not open is an answer — {:decision :phase-refused :because …} — not a thrown failure."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:evidence [:maybe :string]]] :map]
     :delegates [lanes/advance!]})
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
     :delegates [lifecycle/advance-remote!]})
  (Operation drains
    "Every open drain, as the drains page shows it, across every project or the one a scope names."
    {:signature [:=> [:catn [:scope [:maybe :string]]] [:vector :map]]})
  (Operation start-drain!
    "The person's start of a drain from the drains page — the exit criterion and the workstream or
     landed commit that established the pattern — through lane-drain's start, answering the new
     drain or what its records refused."
    {:signature [:=> [:catn [:project :string] [:opts :map]] :map]})
  (Operation drain-act!
    "Carry out one of the person's acts on a drain from the drains page — approve a recipe
     proposal, land a slice, close the drain — answering what it did or what its records refused."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:act :keyword] [:arg [:maybe :string]]] :map]}))
