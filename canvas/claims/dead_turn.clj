(ns canvas.claims.dead-turn
  "The claims the dead-turn design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.tasks.nido-session-hook :refer [nido-session-hook]]
            [canvas.coordinator.record.session-events :refer [record-session-events]]
            [canvas.coordinator.lane.conduct :refer [lane-conduct open-turns]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor stops!]]
            [canvas.coordinator.report :refer [validate-event]]))

(Claim session-end-is-recorded
  "The session hook appends a session-end fact carrying the reason Claude Code gives for every SessionEnd payload that names a session, a sixth kind in a conversation's log, and the launch baseline hooks SessionEnd wherever it hooks the other five."
  {:about [nido-session-hook record-session-events] :evidence "test"})

(Claim open-turn-is-kept-with-the-position
  "Conduct keeps for each conversation of a session record, in the write that moves that conversation's read position, whether its turn is open: a prompt opens it; a stop, a session-start that is not a compaction's, or a session-end whose reason is not `other` closes it. Only events read past the record's position count, so a turn that was open before conduct read its prompt is never held open."
  {:about [lane-conduct open-turns] :evidence "test"})

(Claim dead-turn-is-no-turn-end
  "An open turn whose process is gone is never reported as a turn end: it ends no instruction, settles no Run and posts no hand-back."
  {:about [lane-conduct lane-conductor] :evidence "test"})

(Claim dead-needs-two-absences
  "The conductor lane calls an open turn dead only on a pass where a listing read for this check, never the display cache, holds no row with a process for the conversation and, when the conversation's latest start recorded a process, that process is not running as a process started no later than that start was recorded. A listing that cannot be read, or a running process whose start instant cannot be read, makes no turn dead on that pass."
  {:about [stops!] :evidence "test"})

(Claim dead-turn-asks-once
  "For each dead turn of a session that is neither the conductor's nor the chat's, whose conversation holds no instruction given for a Run, on a workstream that is activated, open and not paused, stops! appends one :blocker carrying :stop {:kind :dead-turn} with the session and the instant the turn began, on the first pass that finds it dead; its text names the conversation, when the turn began, any recorded end with its reason, and the command that resumes the conversation in the directory it ran in. No second blocker is written for the same session and instant, and nido answers the blocker itself once conduct no longer holds the turn open or a witness shows its process."
  {:about [stops!] :evidence "test"})

(Claim dead-turn-found-within-five-minutes
  "A turn that qualifies under dead-turn-asks-once and has been dead for five minutes has its blocker, provided that throughout those five minutes the daemon was running and not halted, the listing was readable, every liveness reading dead-needs-two-absences requires could decide, and no two successive stops! passes for its project were more than two minutes apart. A pass on which a required reading cannot decide calls no turn dead and does not count toward the five minutes. The dead-turn check runs on every stops! pass and is throttled by nothing, and stops! prints a line naming the gap whenever its pass for a project comes more than two minutes after its previous pass for that project in the same daemon."
  {:about [stops!] :evidence "round"})

(Claim detection-starts-nothing
  "Nothing reachable from open-turns, or from what stops! does about a dead turn, starts, wakes, restarts, stops or messages a session."
  {:about [open-turns stops!] :evidence "round"})

(Claim stop-kinds-are-two
  "A :blocker's :stop names its kind as :dialog or :dead-turn, and the write contract refuses any other."
  {:about [validate-event] :evidence "test"})
