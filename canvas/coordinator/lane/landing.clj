(ns canvas.coordinator.lane.landing
  "Self-spec: `nido.coordinator.lane.landing` — landing a workstream by its project's procedure."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.integration.github :as github]
            [canvas.platform.project :as project :refer [ProjectName]]))

(Module lane-landing
  "Land a workstream's change on its project's main by the procedure the project declares.

   HIDES which procedure a project lands by and what each one runs: everything above asks for a
   landing, or whether a landing names a PR, and never names a merge strategy, a merge queue or a
   push. That is what lets the gate, the seat's one landing verb and the commit gate stay the same
   for every project."
  (Operation land!
    "Land by the project's declared procedure (project/landing): :pr-auto puts the PR named by
     :pr on auto-merge or its merge queue, :pr-squash squash-merges it now, :push-main runs
     `bb nido:land` in :dir. A PR procedure without a :pr is refused before anything runs, and so
     is a declaration project/landing refuses.
     {:status :ok :procedure … :via …} or {:error … :detail}, the procedure's refusal in its words."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:opts :map]] :map]
     :delegates [project/landing github/land-pr! github/squash-pr!]})
  (Operation names-pr?
    "Whether the project's landing names a PR — what a caller must supply to land! and what the
     commit gate checks against the workstream's own PR — without saying which procedure it is:
     true for a PR procedure, false for one that pushes main; project/landing's refusal for a
     declaration it refuses."
    {:signature [:=> [:catn [:project ProjectName]] [:or :boolean [:map [:error :keyword]]]]
     :delegates [project/landing]}))
