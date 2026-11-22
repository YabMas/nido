(ns canvas.core.reconcile
  "Self-spec: `nido.core.reconcile`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-reconcile
  "Reconcilers for what nido does not own — runtimes (one per workstream and project),
   databases, each unit's worktree, executor processes: desired state against observed state, converged. The external
   effects other than turns that a commit records — a push, a PR, a
   message — are desired state too: it attempts each from the committed change, under
   its command key, and records what it learns as a command.

   HIDES how each kind of thing is observed. Every observation carries when it was made
   and how long it holds; past that, or when observing failed, the fact is
   unknown."
  (Operation observed
    "An observation of a value, made at a time and holding for a while."
    {:signature [:=> [:catn [:value :any] [:at :int] [:holds-ms :int]] :map]})
  (Operation failed
    "An observation whose observing failed; it reads as unknown."
    {:signature [:=> [:catn [:at :int] [:why :any]] :map]})
  (Operation fact
    "What an observation says now: its value while it holds, else unknown."
    {:signature [:=> [:catn [:observation [:maybe :map]] [:now :int]] :any]})
  (Operation unknown?
    "Whether a fact is unknown."
    {:signature [:=> [:catn [:fact :any]] :boolean]})
  (Operation call!
    "Run a command under a deadline: its exit and output, or unknown past the deadline or when it could not start."
    {:signature [:=> [:catn [:argv [:sequential :string]] [:opts :map]] :map]})
  (Operation start
    "Start a reconciler converging on events, resyncing at most once a minute."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation event!
    "Deliver an event to a reconciler."
    {:signature [:=> [:catn [:reconciler :map] [:event :any]] :nil]})
  (Operation stop!
    "Stop a reconciler once it has converged on what it holds."
    {:signature [:=> [:catn [:reconciler :map]] :nil]}))
