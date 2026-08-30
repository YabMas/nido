(ns canvas.coordinator.lane.github
  "Self-spec: the two GitHub pollers — merged pull requests, and assigned issues."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.integration.github :as github]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-github-merge
  "Poll for merged pull requests and react to the ones nido raised.

   Deduped against what has already been reacted to, because polling is at-least-once and a
   second reaction to the same merge would file the same event twice."
  (Operation poll-and-react! "One poll: list merged pull requests and react to the new ones."
    {:signature [:=> [:catn [:project ProjectName] [:opts :map]] :any]
     :delegates [github/list-merged-prs workstream/append-entry!]}))
