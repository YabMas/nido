(ns canvas.coordinator.lane.github
  "Self-spec: the two GitHub pollers — merged pull requests, and assigned issues."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.integration.github :as github]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-github-merge
  "Poll for merged pull requests and react to the ones nido raised, and read a landing PR's
   merge-queue history.

   Deduped against what has already been reacted to, because polling is at-least-once and a
   second reaction to the same merge would file the same event twice. A queue step is deduped by
   its GitHub event id for the same reason. The queue is read only for a workstream the core
   reports, at that poll, as holding the person's :merge grant — a reading boot hands in beside
   the core settlement, since this lane never reaches the core itself — and only of the one PR its
   current landing submitted: the PR its newest :submitted step names, written after its newest
   :phase-gate and :reopened. An earlier landing's PR is never read again."
  (Operation poll-and-react!
    "One poll: list merged pull requests and react to the new ones — appending :merged — and
     append :landing :queued / :ejected for the queue events of the PR a landing workstream's
     current landing submitted."
    {:signature [:=> [:catn [:project ProjectName] [:opts :map]] :any]
     :delegates [github/list-merged-prs github/merge-queue-timeline
                 workstream/append-entry! workstream/append-entry-once!]}))
