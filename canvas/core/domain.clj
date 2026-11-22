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
