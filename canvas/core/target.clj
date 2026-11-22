(ns canvas.core.target
  "Self-spec: the target coordination core — modules declared ahead of their namespaces.

   Declared by the nido-archetecture-review design: one writer owns every durable change, the store
   commits ledger and derived state together, a Turn is the unit of agent work and an ask is a turn
   that ended. Each Module names the `nido.core.*` / `nido.executor.*` namespace that will realise
   it; until it exists, the check reports the module unrealized, which is the implementation's
   to-do list."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-domain
  "The rules a workstream and its units of work change by. A unit of
   work is one goal and its argument — intent, baseline, design, their judgements —
   in exactly one project, on a ledger of its own; a workstream is the
   arc composing its units, with its conductor, its halts and decisions (each naming the
   unit it concerns, or none for the whole workstream), its landing order and its
   landings. A command is decided against its whole workstream: a pure decision from current
   state and command to events or a refusal, and a pure fold of an
   event into state.

   HIDES the transition rules — which commands a state admits and what each yields —
   so that an illegal state (an open ask whose turn is not asked, two
   active turns on one unit, a workstream landed before its units) has no command
   that produces it."
  (Operation decide
    "Decide a command against the whole workstream: the events it yields, or a refusal naming
     why, which changes nothing."
    {:signature [:=> [:catn [:state :map] [:command :map]]
                 [:or [:map [:events [:vector :map]]] [:map [:refused :map]]]]})
  (Operation evolve
    "Fold one event into a workstream's state."
    {:signature [:=> [:catn [:state :map] [:event :map]] :map]})
  (Operation fold
    "The state a workstream's events fold to, from its first."
    {:signature [:=> [:catn [:events [:sequential :map]]] :map]})
  (Operation step
    "Decide a command and fold what it yields: the new state and its events, or the old state and
     the refusal."
    {:signature [:=> [:catn [:state :map] [:command :map]] :any]})
  (Operation run-unknown-key
    "The unknown a turn's run leaves on its unit when what the run did cannot be known."
    {:signature [:=> [:catn [:turn :string] [:run :int]] :string]})
  (Operation awaiting-person
    "The asks awaiting the person: exactly the open operator asks."
    {:signature [:=> [:catn [:state :map]] [:sequential :map]]})
  (Operation violations
    "Every invariant a state breaks, as data; empty when the state is legal."
    {:signature [:=> [:catn [:state :map]] [:sequential :map]]}))

(Module core-store
  "Where committed state lives: the ledger of events and the state rows derived from
   it, held in one SQLite database in WAL mode with synchronous=FULL.

   HIDES the schema, the transaction boundary and the durability settings. A batch's events and the
   rows they produce commit as one transaction, so stored state is always the fold
   of the ledger and can be rebuilt from it; the rows are a declared
   cache over the ledger, not a second truth."
  (Operation open
    "Open the store for writing, creating it and its schema if absent; one writer holds it."
    {:signature [:=> [:catn [:path :string]] :map]})
  (Operation open-reader
    "Open the store for reading only; under WAL it never waits on the writer."
    {:signature [:=> [:catn [:path :string]] :map]})
  (Operation close
    "Close a store."
    {:signature [:=> [:catn [:store :map]] :nil]})
  (Operation commit!
    "Make a batch of decided commands durable as one transaction, events and derived state together, and answer each entry's last event position; a batch a constraint refuses writes nothing."
    {:signature [:=> [:catn [:store :map] [:batch [:sequential :map]]] [:vector [:maybe :int]]]})
  (Operation changed-since
    "Every workstream whose last commit is after a position, at that commit, in commit order."
    {:signature [:=> [:catn [:store :map] [:pos :int]] [:sequential :map]]})
  (Operation command-outcome
    "The outcome a command key was answered with, or nil if none committed."
    {:signature [:=> [:catn [:store :map] [:key :string]] [:maybe :any]]})
  (Operation state
    "A workstream's committed state."
    {:signature [:=> [:catn [:store :map] [:ws :string]] :map]})
  (Operation workstreams
    "The ids of every workstream with committed events."
    {:signature [:=> [:catn [:store :map]] [:sequential :string]]})
  (Operation events
    "A workstream's events in commit order."
    {:signature [:=> [:catn [:store :map] [:ws :string]] [:sequential :map]]})
  (Operation position
    "The position of the last committed event."
    {:signature [:=> [:catn [:store :map]] :int]})
  (Operation verify
    "Every way the stored state disagrees with the fold of the ledger; reads all of history, so only on request."
    {:signature [:=> [:catn [:store :map]] [:sequential :map]]})
  (Operation rebuild!
    "Recompute every stored state and its rows from the ledger, as a release that changes the fold does."
    {:signature [:=> [:catn [:store :map]] :int]})
  (Operation import-legacy!
    "Import every legacy ledger entry under a nido home as written, only reading the files."
    {:signature [:=> [:catn [:store :map] [:home :string]] :map]})
  (Operation legacy-entries
    "A workstream's imported legacy entries."
    {:signature [:=> [:catn [:store :map] [:project :string] [:ws :string]] [:sequential :map]]}))

(Module core-writer
  "The single writer: one thread takes commands from its queue — a surface's input
   ahead of background commands, each in arrival order — decides each against current state
   unless its key is already committed [commands-are-keyed], commits a batch of bounded size as
   one durable unit, and only then answers callers and publishes the change.

   HIDES batching, group commit, ordering and the output gate. Idle, it blocks on its queue.")

(Module core-api
  "How anything outside the daemon changes or reads nido: commands over a local socket,
   each carrying its sender's key and answered with the outcome committed under that key;
   reads of committed state.

   HIDES the wire format and the socket. CLIs, surfaces and agents' tools are its clients;
   none of them writes the store.")

(Module core-feed
  "The change feed: what each commit changed, in commit order, keyed by commit position,
   delivered to surfaces as pushed messages (SSE to the dashboard, a socket stream to
   the TUI). The asks it delivers as awaiting the person are the open operator asks.

   HIDES subscription, fan-out and resume-from-position, so a surface that reconnects continues from the last position
   it rendered.")

(Module core-turn
  "The Turn: one bounded stretch of agent work, run for a unit of work
   in that unit's project (or, for the conductor's own reasoning, for its workstream), and
   the executor port. An adapter may also hold a live session open for a
   resident conductor — open, send, stream back, close — and one that cannot gives
   the same meaning as one turn per message, resumed.

   HIDES which provider runs a turn. An executor runs a turn from instructions and context
   and returns exactly one outcome — completed, asked (a pending call carrying nido's ask),
   failed or cancelled — cancels a running turn, resumes an asked turn with a
   resolution, takes back a turn by the handle recorded when it started (a turn
   outlives the daemon that started it), and hands a unit's session to a person
   and takes it back. The port is where an executor's output is parsed: output
   that is not one of the four outcomes is the outcome failed.")

(Module executor-claude
  "The Claude Code executor: a turn is one `claude -p` run, detached from the
   daemon, with nido's ask tool, a PreToolUse hook that defers the ask, and `--resume`
   to deliver a resolution and, interactively, to hand the session to a person. A
   turn whose run ended without an outcome is taken back as the outcome failed,
   never resumed.

   HIDES everything Claude-specific: flags, the permission policy passed on every run, the stream format, session
   ids, and the hook protocol: its hooks report to it and to no other
   module.")

(Module core-ask-tool
  "nido's ask, as an MCP tool any executor can offer its agent: a question
   with what was explored, the options and their consequences, and a recommendation.

   HIDES the MCP protocol. Called without a resolution it refuses, so an executor that failed
   to halt on it cannot let the agent read silence as an answer.")

(Module core-buffer
  "The workstream's one conductor, as the core runs it. Every outcome of any of
   its units' turns, in any project, and every outside event that bears on them
   (a merge, a premise moved) comes to it, and it directs the arc: the
   unit's next turn, a new unit proposed for the person to grant, or an
   operator ask, which binds the pending calls of the units' asks it raises or,
   where no unit's call is pending, its own conductor turn's call [conductor-asks-go-to-the-person]. Each ask
   a unit's turn raises that no hold on its unit has taken over, it
   answers, returns for further exploration, or raises in an operator ask, alone or consolidated
   with asks from sibling units, bound to their pending calls; it withdraws an operator
   ask whose premise changed, including by a sibling unit — one binding a conductor
   turn's own call among them — which answers no call it binds. It runs
   asleep — woken per outcome or event — or resident, while a person chats
   with it.

   HIDES the policy that decides between them, so a unit's ask awaits the person only in an
   operator ask it raised.")

(Module core-reconcile
  "Reconcilers for what nido does not own — runtimes (one per workstream and project),
   databases, each unit's worktree, executor processes: desired state against observed state, converged.
   The external effects other than turns that a commit records — a push, a PR, a message — are
   desired state too: it attempts each from the committed change, under its command key, and
   records what it learns as a command.

   HIDES how each kind of thing is observed. Every observation carries when it was made
   and how long it holds; past that, or when observing failed, the fact is
   unknown.")

(Module core-release
  "How the daemon starts, stops and changes version: starting is recovery, a kill is
   a stop, and a new release goes live by a restart that must pass
   a health gate before taking the command socket. Turns in flight outlive a stop
   and are taken back by the daemon that starts next.

   HIDES the release pointer, the health gate and rollback to the previous release.")
