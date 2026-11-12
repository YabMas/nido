(ns canvas.claims.person-start
  "The claims the person-start design (ws-20261112-08df09) commits nido to: a person's start of a
   ticket is an activated workstream brought up at the click and never a Run, the Run cap bounds
   Runs only, and a person's start counts as the grant to conduct.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.executor :as c-coordinator-executor]
            [canvas.coordinator.lane.conduct :as c-coordinator-lane-conduct]
            [canvas.coordinator.lane.conductor :as c-coordinator-lane-conductor]
            [canvas.coordinator.lane.start :as c-coordinator-lane-start]
            [canvas.coordinator.lane.work :as c-coordinator-lane-work]
            [canvas.coordinator.record.runs :as c-coordinator-record-runs]
            [canvas.coordinator.record.standing :as c-coordinator-record-standing]
            [canvas.coordinator.record.tickets :as c-coordinator-record-tickets]))

(Claim a-person-start-is-no-run
  "Starting a ticket — promote-workstream! on a Notion or GitHub workstream, promote! (behind bb nido:ticket:promote), pickup, an accepted Slack proposal, a findings round — enqueues no envelope and mints no Run: it calls start-ticket!, which before returning has recorded the person's :in-progress choice unless the ticket already reads :planning or :implementing (tickets/status-of) — a second start never pulls a ticket with recorded work back to :planning — appended an :activated entry by :person, and brought the impl session up on the workstream. That session becomes the whole seat's occupant when the current generation fixes no ids and nobody occupies the whole seat; otherwise the existing occupants stand, and the conductor the start grants decides who works (reseat!)."
  {:about [c-coordinator-lane-start/start-ticket! c-coordinator-lane-work/promote-workstream! c-coordinator-lane-work/claim-and-drive! c-coordinator-lane-work/file! c-coordinator-lane-work/promote!] :evidence "round"})

(Claim the-start-writes-notion
  "start-ticket! on a Notion-backed workstream writes the project's configured start properties (status, Ball Holder replaced, Participants merged) to the ticket's page itself, best-effort: a failed write is logged and never stops the start. The configuration is the project's, read from its projects.edn entry, not a trigger's."
  {:about [c-coordinator-lane-start/start-ticket!] :evidence "round"})

(Claim brief-before-the-ledger-moves
  "start-ticket! transcribes the ticket's own words (its Notion page and comments, or the GitHub issue body) onto the ledger as a :ticket entry whenever its workstream records no telling of the ticket — no triage (:triage, or a Slack :proposed-ticket), no earlier :ticket transcription and no :issue — whatever else that ledger already holds; the transcription is owed by that absence, not by an empty ledger."
  {:about [c-coordinator-lane-start/start-ticket!] :evidence "round"})

(Claim no-provision-leg
  "Nothing provisions, briefs, settles by skill or reads ticket status from a :plan-bug or :plan-github-issue Run: the triggers are gone from brian's triggers.edn, the daemon executes no provision-only branch, settle derives no state from those skills, and ticket status and the review sweep read no plan Run. What remains is generic: reconcile settles every resolved Run at a daemon start, those Runs' among them, and runs keeps a private set of the two skills only so that teardown and the parked-session stop never reclaim a session such a Run handed to a person."
  {:about [c-coordinator-lane-start/lane-start] :evidence "round"})

(Claim planning-is-the-persons-choice
  "status-of reads a ticket :planning exactly when an :in-progress choice still holds as standing/chosen-stage reads it — a person's start, or nido's from a Notion status it syncs; no plan Run enters it. Its other readings — :investigating from an open triage Run among them — are as they were."
  {:about [c-coordinator-record-tickets/status-of] :evidence "round"})

(Claim the-cap-bounds-runs
  "The working count tick! subtracts from free global slots counts a session only while its outstanding instruction was recorded as given for a capped Run; a working session on an instruction for no Run — a conductor's, a seat occupant's, a reply into a workstream's session — takes no slot. Shown by a test: with the cap full of capped Runs a queued Run waits, and once those Runs end while a conductor, a seat occupant and an uncapped Run are still working, the queued Run is admitted — which counting every working session would refuse."
  {:about [c-coordinator-executor/tick! c-coordinator-lane-conduct/conduct!] :evidence "round"})

(Claim a-no-run-reply-is-given-at-once
  "resume! queues a reply through the executor only when it is a Run's (its Run awaits review); a reply that is no Run's is handed to conduct at once, in the same call, whatever the executor holds."
  {:about [c-coordinator-lane-work/resume!] :evidence "round"})

(Claim never-started-is-not-predates
  "resume! refusing a session with no :claude facet says it predates background sessions only when a Claude session id is recorded for it; one with none is refused as :not-started, with a message saying the session has not started a conversation yet and to try again once it has."
  {:about [c-coordinator-lane-work/resume!] :evidence "round"})

(Claim worked-is-a-recent-person-act
  "conductors! and reseat! treat an open workstream as worked exactly when, within the last 24 hours, a person typed a person-prompt? prompt into one of its sessions (conductor/prompted) or the workstream's ledger records a person's start (standing/person-started-at: its :activated entry, or a person's :in-progress choice or reopen, whichever is newest). Nothing nido writes — a session's creation, a seat, a hand-back, a Run — makes a workstream worked, so a workstream nobody started or typed into in a day still gets no conductor. A re-start of a ticket already being worked writes no such entry, so it renews nothing; the person's next typed prompt does."
  {:about [c-coordinator-lane-conductor/conductors! c-coordinator-lane-conductor/prompted c-coordinator-record-standing/person-started-at] :evidence "round"})

(Claim the-promote-incident-is-reproduced
  "The change lands with tests that fail on the code before it — a promote that mints no Run and activates; under a full cap, a conductor session working on no Run's instruction not stopping a queued Run being admitted once a Run ends, and a reply that is no Run's given at once rather than queued; a never-started session's refusal without 'predates' — and lands only once bb nido:test is green and bb nido:land:check prints no REFUSED line. After landing, BR-6800's impl session carries no :no-background-session error and its workstream is activated; its plan Run, already :done, is left as it is."
  {:about [c-coordinator-lane-start/start-ticket! c-coordinator-executor/tick! c-coordinator-lane-work/resume!] :evidence "round"})

(Claim provisioned-session-outlives-its-run
  "A Run of a handed-over skill (:plan-bug, :plan-github-issue) never has its session reclaimed: teardown-session-for-run! and stop-session-for-parked-run! leave such a Run's session as it is, so a session it handed to a person stays theirs across every reconcile, though no such Run is minted any more."
  {:about [c-coordinator-record-runs/teardown-session-for-run! c-coordinator-record-runs/stop-session-for-parked-run!] :evidence "round"})
