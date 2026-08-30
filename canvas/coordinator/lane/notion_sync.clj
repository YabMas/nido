(ns canvas.coordinator.lane.notion-sync
  "Self-spec: `nido.coordinator.lane.notion-sync` — keeping workstreams in step with their Notion
   tickets."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.vocabulary :refer [WorkstreamId]]
            [canvas.coordinator.record.workstream :as workstream :refer [Workstream]]
            [canvas.integration.notion :as notion]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module lane-notion-sync
  "Reconcile each open Notion-linked workstream against what its ticket now says.

   `sync-action` is PURE and separate from the poll, which is what makes the decision — status,
   ball holder, whether it is mine — testable without a Notion. The poll is the part that cannot
   be, so it is kept as small as the decision allows."
  (Operation sync-action
    "What to do about a ticket, from its status and who holds the ball. Pure."
    {:signature [:=> [:catn [:facts :map]] [:maybe :map]]})
  (Operation load-config "A project's sync configuration, defaults merged."
    {:signature [:=> [:catn [:project ProjectName]] :map]})
  (Operation page-status "A retrieved page's status option, or nil."
    {:signature [:=> [:catn [:page :map]] [:maybe :string]]})
  (Operation page-ballholder-ids "The Notion user ids holding the ball."
    {:signature [:=> [:catn [:page :map]] :any]})
  (Operation page-ballholder-name "The display name of the first ball holder who is not me."
    {:signature [:=> [:catn [:page :map] [:me :any]] [:maybe :string]]})
  (Operation notion-page-id "A workstream's Notion page id, or nil."
    {:signature [:=> [:catn [:ws Workstream]] [:maybe :string]]})
  (Operation open-notion-workstreams "Every open workstream carrying a Notion page."
    {:signature [:=> [:catn [:project ProjectName]] [:vector Workstream]]
     :delegates [notion-page-id workstream/list-ids workstream/read-ws]})
  (Operation poll-and-react! "One reconcile poll for a project."
    {:signature [:=> [:catn [:project ProjectName] [:opts :map]] :any]
     :delegates [open-notion-workstreams sync-action page-status page-ballholder-ids page-ballholder-name notion/retrieve-page workstream/advance-stage!]}))
