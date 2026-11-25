(ns canvas.coordinator.lane.drive
  "Self-spec: the lanes that advance work by themselves — the driver, the arc it reads, the merge
   lane, spawning, review sweeps, run cleanup, and the legacy migration."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.runs :as runs :refer [Run]]
            [canvas.coordinator.record.session :as session :refer [Session]]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.state :as cstate :refer [Path RunId SessionName WorkstreamId]]
            [canvas.coordinator.record.tickets :as tickets :refer [TicketId]]
            [canvas.coordinator.record.workstream :as workstream :refer [Workstream]]
            [canvas.platform.io :as io]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Kind WorkstreamStatus
  "What a workstream's records say about where it stands and who owes it: its position, who owes
   its next move, and whether a person's pause stands.

   RECORD FACTS ONLY. Position is the fold over the ledger; owed-by is read off the mode the
   position's next stage runs in. Whether anything runs is the core's, never a record fact, and
   `view-activity/of` reads these facts beside it as one answer."
  [:map {:closed true}
   [:position :map]
   [:owed-by [:enum :person :agent :machine :nobody]]
   [:paused :boolean]])

(Module lane-pipeline
  "Where a workstream is in its life, read from its own ledger.

   DERIVED, never stored. The arc is what the entries say happened, so a position cannot drift
   from the record it is read out of — and a stage the projection can name is not necessarily
   one the driver can run, which is why parking is the normal case rather than a failure."
  {:child [WorkstreamStatus]}
  (Operation intake-kind "How a workstream came to exist, which decides how it advances."
    {:signature [:=> [:catn [:w Workstream] [:kinds :any]] [:maybe :keyword]]})
  (Operation stage-of "The arc stage an entry kind belongs to, or nil."
    {:signature [:=> [:catn [:kind :keyword]] [:maybe :keyword]]})
  (Operation stage-active
    "The spine stage something is running in right now, or nil."
    {:signature [:=> [:catn [:position [:maybe :map]] [:doing [:maybe :map]]] [:maybe :keyword]]})
  (Operation arc "A ledger read as the arc it travelled."
    {:signature [:=> [:catn [:entries :any] [:opts [:? :map]]] :any] :delegates [stage-of]})
  (Operation baseline-verified?
    "Whether the newest baseline is still footing: found sufficient, and scoped for a goal
     nothing has replaced since — standing's answer, never a presence test of its own."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean]
     :delegates [workstream/latest-entry standing/of-baseline]})
  (Operation design-decided?
    "Whether a decision round let the governing design PROCEED — the current span's, never an
     earlier generation's.

     Standing's answer, `standing/proceeding?`, never re-derived: a round that answered
     `:recut` or `:amend` over any check but the advisory one judged the record rather than
     whether to build it. Public because the gate asks it too: a grant offered on any decision
     at all is a one-click approval of the design its own round sent back."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :boolean]
     :delegates [standing/governing-design standing/proceeding?]})
  (Operation implementation-review-passed?
    "Whether the newest review over the implementation passed, with nothing built since — what
     makes a workstream a release candidate."
    {:signature [:=> [:catn [:w :any]] :boolean]})
  (Operation next-action "The stage to run next and the mode it runs in, or nil at a terminus."
    {:signature [:=> [:catn [:position :any] [:kind :keyword]] [:maybe :map]]})
  (Operation amendment-owed
    "The stages the amendment of a goal still owes that goal's unit, in order — a rebaseline
     while the unit's newest survey stands on the replaced goal, its verification until standing
     verifies that survey, a design while the newest one stands on the replaced goal, its
     decision until a round lets it proceed — each with the live goal and the seqs it cites or
     supersedes, and no text. One function of a goal, answered for whichever unit the caller
     names; a next action whose stage is the first owed by its newest survey's unit carries them
     as :owed. Read off the ledger's goal-amendment reading, with whether standing lets the
     unit's newest design proceed."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:goal-seq :int]]
                 [:vector :map]]
     :delegates [workstream/goal-amendment standing/proceeding?]})
  (Operation disposition "What a finished status means for the workstream it finished on."
    {:signature [:=> [:catn [:status :keyword]] :keyword]})
  (Operation settled-in-notion
    "The Notion status by which a person settled a workstream's ticket since its last stage choice,
     or nil — nil once it is closed, and while its current generation holds an unanswered
     blocker. `of` places a workstream this answers for where a close is."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:whole Workstream]] [:maybe :map]]})
  (Operation owed-blocker
    "The :seq of the blocker `:blocked` places a workstream on — its current generation's newest
     blocker no answer names — or nil. Public because the gate must answer the blocker the
     position halts on, not whatever record was appended on top of it."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] [:maybe :int]]
     :delegates [workstream/read-ws]})
  (Operation reviewed-under-version?
    "Whether a drain slice passed a review judged against the recipe version it runs under — a
     :review citing that version whose status advances the work. What a slice's landing waits on."
    {:signature [:=> [:catn [:project ProjectName] [:w :any]] :any]})
  (Operation of "Where a workstream stands and what should happen to it next."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [workstream/read-ws intake-kind next-action baseline-verified? settled-in-notion]})
  (Operation status
    "A workstream's position, who owes its next move, and whether a person's pause stands: the
     record facts every surface's activity is read beside the core's."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] WorkstreamStatus]
     :delegates [of]}))

(Module lane-runs-clean
  "Deleting terminal runs, by plan and then by execution.

   Two steps rather than one, because deleting run directories is the kind of thing you want to
   read before you do."
  (Operation parse-duration-ms "A duration like `7d` in milliseconds, or nil."
    {:signature [:=> [:catn [:s :string]] [:maybe :int]]})
  (Operation plan-clean "What would be deleted, and from where."
    {:signature [:=> [:catn [:opts :map]] [:vector :map]]
     :delegates [parse-duration-ms cstate/run-dir]})
  (Operation execute! "Delete everything the plan lists. A missing path is not an error."
    {:signature [:=> [:catn [:plan [:vector :map]]] :any]}))

