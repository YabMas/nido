(ns canvas.core.domain
  "Self-spec: `nido.core.domain`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
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
   that produces it. A workstream's identity and its ledger of work records are part of its
   state: an entry is appended only after the seq its author read, so a ledger that moved
   under an author refuses the append rather than numbering past it.

   A run is the other kind of stream: one fire of a trigger, with exactly one unit, its first turn
   and the session profile it names, and no ledger, conductor, inbox, chat or ask. A run admits
   only that unit's mechanics, and is over once its turn ends or its session fails to come up."
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
  (Operation machine-ask?
    "Whether an open ask is a failure the machine raised about itself — its conductor stopped —
     rather than a question: the person's move on it is a recovery, and no surface lists it as
     needing them."
    {:signature [:=> [:catn [:ask :map]] :boolean]})
  (Operation run-status
    "Where a run stands — :starting, :running, :done, :failed or :cancelled — or nil on a
     workstream."
    {:signature [:=> [:catn [:state :map]] [:maybe :keyword]]})
  (Operation background-run?
    "Whether a workstream is a background run: every unit a trigger opened as background work. A
     background run closes when its turn completes; the board does not list it."
    {:signature [:=> [:catn [:state :map]] :boolean]})
  (Operation activity
    "What the core is doing on a workstream, or nothing: an ask awaiting the person — :failed? when
     one is a machine failure, :failure-only? when every one is — a unit's turn working, the
     conductor deciding, or a session starting — the move it owes between turns named as well as
     the turn running."
    {:signature [:=> [:catn [:state :map]] [:maybe :map]]
     :delegates [awaiting-person machine-ask?]})
  (Operation violations
    "Every invariant a state breaks, as data; empty when the state is legal."
    {:signature [:=> [:catn [:state :map]] [:sequential :map]]})
  (Operation session-want
    "What a unit's session should be in a state: :up, :stopped or :destroyed."
    {:signature [:=> [:catn [:state :map] [:unit :map]] [:maybe :keyword]]})
  (Operation sessions-owed
    "Every unit whose session is not what it should be and has no attempt pending, with the want. A
     failed bring-up is not owed again until it is retried."
    {:signature [:=> [:catn [:state :map]] [:sequential :map]]})
  (Operation attempts-pending
    "Every provisioning attempt recorded and not yet resolved."
    {:signature [:=> [:catn [:state :map]] [:sequential :map]]})
  (Operation code-held
    "Why a unit's turn may not change code, or nil when it may: the recipe runs on the workstream
     and no design of it stands cleared. A turn started while it holds runs read-only."
    {:signature [:=> [:catn [:state :map]] [:maybe :keyword]]})
  (Operation first-turns-owed
    "The first turns owed: each open unit opened with a first turn that no turn of it has started
     yet and whose session is ready."
    {:signature [:=> [:catn [:state :map]] [:sequential :map]]})
  (Operation latest-seq
    "The seq of the newest entry on a workstream's ledger; 0 when it holds none."
    {:signature [:=> [:catn [:state :map]] :int]})
  (Operation summary
    "What a reader of many workstreams needs of one, read off its state alone — project, title,
     refs, facets, birth, closure, ledger position, liveness and the asks awaiting the person, and
     whether a person owes it a move now."
    {:signature [:=> [:catn [:state :map]] :map]
     :delegates [awaiting-person]}))
