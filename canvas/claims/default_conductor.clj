(ns canvas.claims.default-conductor
  "The claims the default-conductor design commits nido to: every workstream work has started on has one conductor
   and at least one seat, a turn end returns to whoever gave the instruction it ended, and each
   hand-back the conductor receives is answered by exactly one recorded decision.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.core :refer [boot-core]]
            [canvas.coordinator.lane.conduct :refer [lane-conduct]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor]]
            [canvas.coordinator.lane.drive :refer [lane-drive lane-pipeline lane-ship]]
            [canvas.coordinator.lane.seats :refer [lane-seats]]
            [canvas.coordinator.lane.work :refer [lane-resume lane-scratch]]
            [canvas.coordinator.record.seats :refer [record-seats]]
            [canvas.session.commit-gate :refer [session-commit-gate]]))

(Claim seats-of-a-generation
  "Every generation has at least one seat: one per id it fixes, each seat's id that element's id, or — while it fixes none, whether no design marks any or its opening carries none — exactly one whole seat whose id is the generation's own and is no element's id; its interface is the governing design's whole (none before a design)."
  {:about [record-seats] :evidence "round"})

(Claim conductor-addressed-by-role
  "A seat message or tension is accepted addressed only to a seat record-seats answers for the current generation, or to the conductor by role. One addressed to the current generation's whole seat becomes the conductor's once the generation fixes ids; no other addressee is read as the conductor's."
  {:about [record-seats lane-seats] :evidence "round"})

(Claim one-occupant-per-seat
  "Each seat of the current generation has at most one live occupant and each session occupies at most one seat, under one occupancy policy for every embodiment. After each conduct pass, a carried workstream has no live occupant of a seat its current seats lack, and each of its current seats is occupied by the embodiment that carries it or named by an escalation saying why none does — whether its seats changed by a generation's opening or by a first generation fixing its ids. An escalation that a seat could not be started is answered by nido, as no act of the person's, once every current seat is held."
  {:about [lane-seats lane-conductor] :evidence "round"})

(Claim carried-by-default
  "A session is carried when a person starts it by the session-up verb, spawn or the work plane's start, that start first recording it on its workstream, once the start has brought it up; and when it is a Run's session, once the daemon has started the Run's conversation: if the workstream's current generation fixes no ids and its whole seat has no occupant, the session becomes the whole seat's occupant, driven by nido, with no seats verb run — a spawned child's first instruction its brief. A spawned child is driven on its brief whether or not it is carried — occupying no seat when it is not — and the tab spawn opens attaches to it. birth!, the orphan sweep and a person's start of a session already recorded carry none, and a session started while the seat is occupied displaces no one. Once its whole seat is gone, a carried session stops occupying it and is not archived: a person's is released to them, and a Run's stays its Run's."
  {:about [lane-conductor lane-seats] :evidence "round"})

(Claim hand-back-to-instructor
  "A turn end returns to whoever answers for the instruction it ended, as recorded when nido queued it. A Run answers for each instruction recorded as given for it — its conversation's first prompt, its merge lane's /drive-home, a gate reply to its session while it awaits review: that turn end settles that Run, and no Run is settled or timed out on an instruction not recorded as its own. The conductor answers for every other instruction nido gives a session it drives on the workstream but the conductor — an occupant's brief, a seat message, a decided instruction, a gate reply no Run awaits: its turn end settles no Run and is posted to the conductor. A person answers for a turn nido did not give: it holds the session under conduct's rule and its end is no hand-back; the hold, handed back on any session nido drives on the workstream, is posted to the conductor. Each is posted exactly once, keyed on its turn end or hand-back, carrying the session, its seat when it has one, the turn's last message, the position pipeline/of derives at that moment, and the governing design and the seats as the records give them then — so the conductor decides each hand-back on the records at that hand-back, not on its brief or its conversation."
  {:about [lane-conductor boot-core lane-seats lane-conduct lane-resume lane-ship] :evidence "round"})

(Claim resident-conductor
  "A workstream is worked once, at or after the instant conductors began on its host — an instant the first conduct pass to need it records, and never moves — a session other than its conductor has been recorded on it or a hand-back has been logged on it. Every open, worked workstream has exactly one conductor — one driven session on one record — whatever path brought its sessions; a workstream not worked since has none, so an item queued in the intake inbox, or a workstream last worked before conductors began, costs nothing until a session starts on it or nido gives a session there an instruction no Run gave. The conduct pass starts the conductor of each open, worked workstream whose conductor's session is not running, and retires it once the workstream is closed — a ticket a person settled in Notion since its last stage choice, as the pipeline places it, included — or while it is open and not worked: any Claude process of its record stopped and its session brought down, each whatever the other's state, its record kept. Every deletion of a workstream retires its conductor first. To every reading that chooses or counts a workstream's sessions to decide whether to delete, reap or reuse the workstream, which session a stage or an environment runs in, whether its work is live, or which session a person is sent to — drive's tick! and session-cwd, the work plane's environment, a duplicate one-off's yielding, intake's refire, the GitHub intake's unpromoted?, spawn's rollback, reap!, pipeline's status, the board's showing of a closed workstream's live sessions, the improvement sweep's reading of an attempt's sessions, the work plane's open-target and attach's suggestion of a session to enter — its conductor's record is none of them, and its :conduct-decision entries and the blockers they parked keep no one-off from reap!. The readings that account for what runs — the orphan sweep's ownership, the runtime, conduct and lane-seats — count it."
  {:about [lane-conductor lane-seats boot-core lane-scratch lane-drive lane-pipeline] :evidence "round"})

(Claim conductor-briefed-from-records
  "Each conductor start is briefed with the seats, the governing design and the position as the records give them at that start, and with the escalation guidance document, which is changed without changing code."
  {:about [lane-conductor] :evidence "round"})

(Claim one-decision-per-hand-back
  "Every hand-back is answered by exactly one :conduct-decision citing it — instructions to one or more seats, or an escalation naming what the person is needed for — written by the conductor, or by nido as an escalation when the conductor turn it was delivered for ends leaving it unanswered; one not yet delivered, or delivered to a conductor conversation that stopped before that turn ended, stays owed, and a second answer is refused."
  {:about [lane-conductor] :evidence "round"})

(Claim decision-records-position
  "Every decision records the position pipeline derives at the decision and that position's next action. An instruction is accepted at any position, the person's next step included: what keeps the person's step theirs is the gate refusing their acts [person-acts-stay-the-persons], and whether an instruction advances work past that step is the escalation guidance's to govern."
  {:about [lane-conductor lane-pipeline] :evidence "round"})

(Claim decisions-execute
  "An accepted instruction is posted as one seat message to each seat it names, and an accepted escalation parks the workstream on a blocker the person owes; when it was escalated at a step whose next action is :human or :establish-intent, the person's act at that step is recorded as the blocker's answer. Each act of the person's recorded on an open workstream after its conductor's record was made — a reply at the gate, recorded as a blocker's answer, included — is posted to the conductor as one hand-back; a blocker's answer nido records for an act is carried by that act's hand-back and posted as none of its own. A hand-back the conductor answered with instructions is not announced to the person."
  {:about [lane-conductor lane-drive] :evidence "round"})

(Claim person-acts-stay-the-persons
  "From the conductor's session, and from any other session's in a turn that follows an instruction the conductor answers for, each Bash command the commit gate's parse could read as a person's act starts the PreToolUse gate — its prefilter matching the text as the parse reads it, quotes and backslashes removed — which refuses each it recognises as one: a nido verb appending an :intent, a granted :design-approved, a :triage-accepted, :phase-gate, :blocker-answered, :merged or :closed entry, a gh pr merge, or a gh api write that may merge a pull request. Pushing, to any branch, opening or readying a PR, and a design round whose clearing carries an earlier grant forward pass."
  {:about [lane-conductor lane-seats session-commit-gate] :evidence "round"})
