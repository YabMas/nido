(ns canvas.vsdd.core
  "Self-spec: `nido.vsdd.*` — the parallel sweep that drives modules to convergence.

   One loop per module — critic assesses, judge routes, implementer acts — run across every dirty
   module at once in its own jj workspace, then rebased back. The parallelism is the point: the
   modules are independent by construction, so the only serial step is the rebase."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [fukan.common.typing.malli]))

(Kind Manifest
  "The structured record of one complete VSDD run: its module, its iterations, and how it ended.

   The data model for introspection rather than a log — a UI, a history view and a debugging
   session all read this, which is why it is a record and not printed output.")

(Kind Verdict
  "What the judge decided about one iteration, and why."
  [:map [:verdict :keyword] [:reason {:optional true} [:maybe :string]]])

(Kind ShellResult
  "What a shell-out did: its exit code and both streams. Answered rather than thrown, so a
   caller decides whether a non-zero exit is a failure or an answer."
  [:map [:exit :int] [:out :string] [:err :string]])

(Module vsdd-jj
  "Every jj interaction the sweep makes, in one place.

   One module so the VCS is a seam rather than a habit: a sweep that shelled to jj from six
   namespaces could not be tested without a repository, and could not be ported without finding
   all six."
  {:child [ShellResult]}
  (Operation jj!
    "Run a jj command in a directory and answer with what happened."
    {:signature [:=> [:catn [:dir Path] [:args [:* :any]]] ShellResult]})
  (Operation current-change-id
    "The change id of the working copy."
    {:signature [:=> [:catn [:dir Path]] [:maybe :string]] :delegates [jj!]})
  (Operation workspace-change-id
    "A workspace's working-copy change id, read from the main repo."
    {:signature [:=> [:catn [:dir Path] [:ws-name :string]] [:maybe :string]] :delegates [jj!]})
  (Operation create-workspace
    "Create a jj workspace — the isolated checkout one module's run happens in."
    {:signature [:=> [:catn [:dir Path] [:ws-path Path] [:ws-name :string]] ShellResult]
     :delegates [jj!]})
  (Operation forget-workspace
    "Forget a workspace."
    {:signature [:=> [:catn [:dir Path] [:ws-name :string]] ShellResult] :delegates [jj!]})
  (Operation last-converged-commit
    "The most recent commit carrying a converged trailer for this module — where the last sweep
     left it, and therefore what `changed since` is measured from."
    {:signature [:=> [:catn [:dir Path] [:module :string]] [:maybe :string]] :delegates [jj!]})
  (Operation files-changed-since?
    "Whether anything under a module path changed since a change id."
    {:signature [:=> [:catn [:dir Path] [:change-id :string] [:module-path :string]] :boolean]
     :delegates [jj!]})
  (Operation rebase-revision
    "Rebase one revision onto another."
    {:signature [:=> [:catn [:dir Path] [:source-change :string] [:dest-change :string]] ShellResult]
     :delegates [jj!]})
  (Operation describe
    "Set a revision's description."
    {:signature [:=> [:catn [:dir Path] [:change-id :string] [:message :string]] ShellResult]
     :delegates [jj!]})
  (Operation get-description
    "Read a revision's description."
    {:signature [:=> [:catn [:dir Path] [:change-id :string]] :string] :delegates [jj!]})
  (Operation new-on-top
    "Start a fresh empty working copy on top of a revision."
    {:signature [:=> [:catn [:dir Path] [:change-id :string]] ShellResult] :delegates [jj!]})
  (Operation abandon
    "Abandon a revision."
    {:signature [:=> [:catn [:dir Path] [:change-id :string]] ShellResult] :delegates [jj!]}))

(Module vsdd-agent
  "Spawning the agents a VSDD role needs.

   Two modes, and they are different animals: an AGENT gets tools and streams its progress, a
   JUDGE gets neither because it only classifies. Giving the judge tools would let a
   classification step change the thing it was classifying."
  (Operation invoke-agent
    "Spawn an agent for a role, streaming its progress and capturing its result."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation invoke-judge
    "A tool-less call for classification alone."
    {:signature [:=> [:catn [:opts :map]] :map]}))

(Module vsdd-judge
  "Reading the judge's verdict out of what it said."
  {:child [Verdict]}
  (Operation parse-verdict
    "The verdict in the judge's output."
    {:signature [:=> [:catn [:output :string]] Verdict]})
  (Operation build-judge-prompt
    "The prompt that asks the judge to route one critic report."
    {:signature [:=> [:catn [:opts :map]] :string]}))

(Module vsdd-prompts
  "The built-in role prompts, and the tools each role is allowed."
  (Operation load-agent-prompt
    "The built-in prompt for a role."
    {:signature [:=> [:catn [:role :keyword]] [:maybe :string]]})
  (Operation tools-for-role
    "The tools a role may use. A role's tool list is part of its definition, not a caller's
     choice — which is what stops a critic acquiring the ability to fix what it is assessing."
    {:signature [:=> [:catn [:role :keyword]] [:vector :string]]}))

(Module vsdd-manifest
  "The run manifest: creating it, appending to it, and finishing it."
  {:child [Manifest]}
  (Operation create
    "A manifest for a run about to start."
    {:signature [:=> [:catn [:opts :map]] Manifest]})
  (Operation check-liveness
    "Whether an in-progress manifest's process is still there — how a crashed run is told from a
     slow one."
    {:signature [:=> [:catn [:manifest Manifest]] Manifest]})
  (Operation add-iteration
    "Append one iteration's record."
    {:signature [:=> [:catn [:manifest Manifest] [:iteration-data :map]] Manifest]})
  (Operation finalize
    "Close the manifest with its final verdict."
    {:signature [:=> [:catn [:manifest Manifest] [:verdict :any]] Manifest]})
  (Operation manifest-path
    "Where a run's manifest lives."
    {:signature [:=> [:catn [:run-dir Path]] Path]})
  (Operation save!
    "Persist the manifest."
    {:signature [:=> [:catn [:manifest Manifest]] :any] :delegates [manifest-path]})
  (Operation load-manifest
    "Read a run's manifest."
    {:signature [:=> [:catn [:run-dir Path]] [:maybe Manifest]] :delegates [manifest-path]}))

(Module vsdd-loop
  "One module's convergence loop: critic, judge, implementer, repeat.

   Bounded by an iteration cap, because a loop whose exit depends on an agent agreeing it is
   done has no exit."
  (Operation run
    "Drive one module until the judge says it has converged, or the cap is reached."
    {:signature [:=> [:catn [:config :map]] :map]})
  (Operation resume
    "Pick an interrupted run back up from its manifest."
    {:signature [:=> [:catn [:config :map]] :map]}))

(Module vsdd-analyst
  "Reading a finished run for what could be done better next time.

   Retrospective, and deliberately separate from the loop: an analyst inside the loop would be
   another agent to satisfy before anything converged."
  (Operation collect-run-data
    "Everything one run produced — its manifest and every report."
    {:signature [:=> [:catn [:project-dir Path] [:run-id :string]] :map]})
  (Operation analyze
    "What a finished run suggests about doing the next one better."
    {:signature [:=> [:catn [:opts :map]] :map]
     :delegates [collect-run-data]}))

(Module vsdd-sweep
  "The parallel sweep: find the dirty modules, run each in its own workspace, rebase what
   converged.

   The only serial step is the rebase, and it is serial because it has to be — converged
   workspaces land in module order so a later one rebases onto the result of the earlier."
  (Operation module-needs-run?
    "Whether a module has changed since it last converged."
    {:signature [:=> [:catn [:project-dir Path] [:module-path :string]] :map]})
  (Operation detect-dirty-modules
    "Every module that needs a run, and why."
    {:signature [:=> [:catn [:project-dir Path] [:modules [:vector :any]]] [:vector :map]]
     :delegates [module-needs-run?]})
  (Operation create-workspace-for-module
    "A workspace for one module's run."
    {:signature [:=> [:catn [:project-dir Path] [:module-path :string]] :map]})
  (Operation cleanup-workspaces!
    "Forget the workspaces and remove their directories."
    {:signature [:=> [:catn [:project-dir Path] [:workspaces [:vector :map]]] :any]})
  (Operation run-module-in-workspace
    "One module's loop, inside its workspace."
    {:signature [:=> [:catn [:base-config :map] [:ws-info :map]] :map]})
  (Operation launch-parallel-runs
    "Start every module's run at once."
    {:signature [:=> [:catn [:base-config :map] [:workspaces [:vector :map]]] :map]
     :delegates [run-module-in-workspace]})
  (Operation await-all-runs
    "Wait for every run to finish."
    {:signature [:=> [:catn [:futures-map :map]] :map]})
  (Operation rebase-converged!
    "Land the converged workspaces onto the main branch, in module order."
    {:signature [:=> [:catn [:project-dir Path] [:results :map] [:workspaces [:vector :map]]
                            [:module-order [:vector :any]]] :map]})
  (Operation preserve-artifacts!
    "Copy each workspace's run artifacts into the sweep's own directory before the workspaces go."
    {:signature [:=> [:catn [:project-dir Path] [:sweep-id :string] [:workspaces [:vector :map]]
                            [:results :map]] :any]})
  (Operation sweep
    "Run every dirty module in parallel and land what converged."
    {:signature [:=> [:catn [:config :map]] :map]
     :delegates [detect-dirty-modules create-workspace-for-module launch-parallel-runs
                 await-all-runs rebase-converged! preserve-artifacts! cleanup-workspaces!]}))
