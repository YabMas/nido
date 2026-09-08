(ns canvas.tasks.nido-owed
  "Self-spec: `tasks.nido-owed` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, ask the domain, print. Its whole
   claim is that it does nothing else."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.drive :as pipeline]
            [canvas.coordinator.record.activity :as activity]
            [canvas.coordinator.record.state :refer [WorkstreamId]]
            [canvas.coordinator.work :as work]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module nido-owed
  "The same question `nido-attach` asks, with nothing done about the answer.

   THAT IS THE WHOLE OF IT, and the restriction is what the module is for. Attach follows a live
   run, fires a stage, prints a skip — every one of those is an act, and an act is exactly what
   must not happen where this is meant to be asked: at a turn boundary, on every turn, in a
   session somebody is working in. A reading can be asked a thousand times and change nothing.

   IT ADDS NO VOCABULARY. The mode is the whole of the answer, and whether a PERSON owes the next
   stage comes from `coordinator-work/awaiting-human` rather than from a test of its own. That is
   a dependency chosen against the easier option of asking `(= :human mode)` here: the same
   function decides whether a workstream stands in the needs-you inbox, so asking it means the
   inbox and the turn boundary cannot come apart about who is holding something up. A second
   reading of the same field would be a second answer waiting to disagree.

   The activity claim is part of the answer rather than a caller's afterthought: a workstream
   something else is already advancing owes this caller nothing, whatever its position says."
  (Operation owed
    "What a workstream owes: its position, the stage and mode due, the stage a PERSON owes if one
     does, and whoever holds the claim. The person's stage is a stage rather than a flag because
     the two readers want different halves — a boundary wants to know THAT somebody does, a
     person wants to know WHAT — and nil already answers the first."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId]] :map]
     :delegates [pipeline/of activity/read-live work/awaiting-human]})
  (Operation owed-line
    "One line saying what is owed, read by a person and by a model alike — which is why it names
     the command as well as the stage. Pure over the answer."
    {:signature [:=> [:catn [:answer :map]] :string]})
  (Operation owed-at
    "The answer for wherever the caller is standing, or nil when that is no workstream. Through
     the home-aware union, because a session HOME is a place an agent legitimately stands and the
     worktree-keyed resolution does not reach one."
    {:signature [:=> [:catn [:given :any]] [:maybe :map]]
     :delegates [owed]})
  (Operation owed-cmd*
    "Print what is owed, and exit zero whatever it finds. A reading is not a gate: a question that
     can fail is a question nobody can ask in a script."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [owed-at owed-line]})
  (Operation owed-cmd
    "The `owed-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [owed-cmd*]}))
