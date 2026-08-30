(ns canvas.session.misc
  "Self-spec: the smaller pieces around a session — the eval service, session-scoped commands,
   orphan reclaim, the resume shim, agent guidance files, and the memory bench."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate]
            [fukan.common.typing.malli]))

(Module services-eval
  "The application service, driven over nREPL.

   Started by evaluating the project's declared start form in the session's own REPL rather than
   by spawning a process — which is why the app can be restarted without the session going down."
  (Operation start-app! "Evaluate the service's start form and wait for it."
    {:signature [:=> [:catn [:service-def :map] [:saved-state :any] [:session-ctx :map]] :any]})
  (Operation stop-app! "Evaluate the service's stop form."
    {:signature [:=> [:catn [:service-def :map] [:saved-state :any]] :any]}))

(Module session-run
  "Running a project-declared command inside a session's worktree."
  (Operation session-context "The substitution context for a session-scoped command."
    {:signature [:=> [:catn [:project-name ProjectName] [:project-dir Path] [:session-name :string]
                            [:worktree Path]] :map]})
  (Operation run-command-in-session! "Resolve a session's worktree and run a named command there."
    {:signature [:=> [:catn [:project-name ProjectName] [:session-name :string] [:ref :any]] :any]
     :delegates [session-context]}))

(Module session-reclaim
  "Reclaiming the state directories of instances nobody is using.

   AGE-GUARDED, and the guard is load-bearing rather than cautious: a session's state directory
   exists for the whole of its boot BEFORE its registry entry is written, so a freshly-untracked
   directory may be a live boot in flight rather than garbage. The grace window is what tells
   them apart."
  (Operation reclaim-orphans! "Remove orphaned instance state older than the grace window."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [sstate/state-dir sstate/read-registry]})
  (Operation reclaim! "List orphaned state directories, or delete them when told to."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]}))

(Module bench-memory
  "Measuring what a session actually costs in memory, lever by lever.

   Exists because the fleet's budget arithmetic is only as good as its estimate of a typical
   session, and that estimate should be measured rather than assumed."
  (Operation snapshot "A process's memory, broken down."
    {:signature [:=> [:catn [:pid :any]] :map]})
  (Operation run-all! "Run every lever for a project and write the per-lever results."
    {:signature [:=> [:catn [:project-name ProjectName] [:opts [:* :any]]] :any]
     :delegates [snapshot]}))
