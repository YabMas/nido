(ns canvas.coordinator.lane.github-issue-intake
  "Self-spec: `nido.coordinator.lane.github-issue-intake` — assigned issues as workstreams."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.seats :as seats]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.integration.github :as github]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-github-issue-intake
  "Poll a repository for open assigned issues and reconcile them into workstreams.

   REVERSE reconciliation too: an issue that closed upstream settles the workstream it raised,
   so the board does not keep showing work somebody finished elsewhere."
  (Operation poll-and-reconcile!
    "One reconcile poll for a project. A queued issue no longer assigned is deleted while it is
     still unpromoted — read past its conductor's record — its conductor retired before it is."
    {:signature [:=> [:catn [:project ProjectName] [:opts :map]] :any]
     :delegates [github/list-assigned-issues workstream/find-by-ref workstream/create!
                 seats/retire-conductor! workstream/delete!]}))
