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
   ledger says what is due. The driver already selects this way for the `:mechanical` stages it
   may run unasked, and the gate surface already reads the same `:next` for the `:human` ones;
   this is the third door, and the only one that was reading an argument.

   FOLLOWING IS ITS FIRST QUESTION, not a consequence of losing a race. It reads the live claim
   before it reads the position, and follows whatever holds one — whatever kind, whatever target,
   whatever mode the position names. Reaching the claim only by calling a runner would reach it
   only for a `:mechanical` stage, so a live round on a workstream owing an approval would never
   be found at all; and the join protocol's kind-and-target comparison exists to protect a caller
   who NAMED work from watching something else finish, which a door that names none has no use
   for. Following is a read and takes no claim.

   It adds no judgement about what to RUN. `lane-drive/fireable` stays the single mapping from a
   position to a stage anything runs, and what changes is that its `:skip` reasons — which the
   driver discards into a log line — become the thing a person is told. Starting a second run
   against a claimed workstream is still prevented by the claim rather than by the read: a holder
   that appears after the read comes back as `{:skip :claimed}`, and the answer to that is to read
   again and follow."
  (Operation skip-lines
    "What to tell a person about a position nothing will fire for them: the stage, the mode, and
     the command that would supply it. Pure, and it is where the skip reasons stop being a
     driver's shrug and become an answer — a mode this phase cannot run names the session to
     enter, a `:human` one names the gate, `:no-runner` names the stage that has none.

     The stage is read off the POSITION rather than off the decision, because `fireable` carries
     one only on the branches its own caller needs it for — :waiting-on-a-human has none, and
     that is exactly where a person most needs telling what they owe."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:position :any]
                            [:decision :map]] [:vector :string]]
     :delegates [session/list-sessions]})
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
    "Follow what is live, or run what is due, or say what is owed — and run it against the
     worktree the caller is standing in, which is why the resolved path comes this far rather
     than being derived again. The driver's fallback takes the first session a workstream lists,
     ordered and filtered by nothing, so a workstream with two of them could have the stage judge
     a tree nobody asked about or halt over a session that is no longer there."
    {:signature [:=> [:catn [:project ProjectName] [:ws-id WorkstreamId] [:cwd Path]] :any]
     :delegates [activity/read-live follow-holder! drive/fireable drive/run-stage! skip-lines]})
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
