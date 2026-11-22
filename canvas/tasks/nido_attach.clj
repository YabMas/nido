(ns canvas.tasks.nido-attach
  "Self-spec: `tasks.nido-attach` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.lane.drive :as drive]
            [canvas.coordinator.record.activity :as activity]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.state :refer [Path WorkstreamId]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.review.core :as review]
            [fukan.common.typing.malli]))

(Module nido-attach
  "The door a person takes to a workstream, which chooses the stage for them.

   NAMES NO STAGE, and that is the whole interface claim: a caller says which workstream and the
   ledger says what is due. It follows whatever live run holds the workstream's claim, and
   otherwise says what is owed: a `:human` stage names the gate, a `:mechanical` one is the
   conductor's to run in the core. It runs nothing itself, and following is a read that takes no
   claim."
  (Operation skip-lines
    "What to tell a person about a position nothing will fire for them: the stage, the mode, and
     the command that would supply it. Pure, and it is where the skip reasons stop being a
     driver's shrug and become an answer — a mode this phase cannot run names the session to
     enter, never its conductor's, a `:human` one names the gate, and a mechanical stage is named as the conductor's to run.

     The stage is read off the POSITION rather than off the decision, because the decision carries
     one only on some branches — :waiting-on-a-human has none, and that is exactly where a person
     most needs telling what they owe."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:position :any]
                            [:decision :map]] [:vector :string]]
     :delegates [session/list-sessions]})
  (Operation amendment-lines
    "The stages an amended goal still owes, worded as the numbered lines a person is shown — the
     record each stage writes or the command that runs it — so the entry task and attach name one
     chain in one wording. Beside skip-lines because it is the same kind of answer: what a person
     is told they owe, worded where commands are."
    {:signature [:=> [:catn [:owed [:vector :map]]] [:vector :string]]})
  (Operation follow-holder!
    "Paint the holder's own run until it lets go, then answer with what it ended on — whatever
     it was doing. Attach asked for no particular work, so any live work is the answer to what
     it asked, and there is no kind-and-target comparison here: that exists in the join protocol
     to protect a caller who NAMED work, and this one named none. Takes no claim; following is
     a read.

     FOLLOWS A REPLACEMENT rather than adopting the run it watched. A live claim read while it
     changes hands can be the PREVIOUS holder's, so following can stop on a run that reached its
     verdict before this door was opened; whoever holds the claim once following stops is what
     says whether the report answers this caller. The join protocol calls that case detached
     because its caller named work a replacement may not be doing — this one named none, so the
     replacement is followed in turn and only a free claim ends the follow."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:holder :any]] :any]
     :delegates [review/follow! activity/read-live]})
  (Operation attach!
    "Follow what is live, or say what is owed, for the workstream the caller is standing in."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:cwd Path]] :any]
     :delegates [activity/read-live follow-holder! skip-lines]})
  (Operation attach-cmd*
    "Resolve the workstream from where the caller is standing, then attach THERE — the resolved
     worktree is what a chosen stage runs against, not merely how the workstream was found.
     Returns the stage's own terminal status when one ran, the holder's when one was followed,
     and a keyword naming the reason when neither."
    {:signature [:=> [:catn [:opts :map]] :any]
     :delegates [attach!]})
  (Operation attach-cmd
    "The `attach-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]
     :delegates [attach-cmd*]}))
