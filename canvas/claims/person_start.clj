(ns canvas.claims.person-start
  "The claims the person-start design (ws-20261112-08df09) commits nido to: a person's start of a
   ticket is an activated workstream brought up at the click and never a Run, the Run cap bounds
   Runs only, and a person's start counts as the grant to conduct.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.executor :as c-coordinator-executor]
            [canvas.coordinator.lane.start :as c-coordinator-lane-start]
            [canvas.coordinator.lane.work :as c-coordinator-lane-work]
            [canvas.coordinator.record.standing :as c-coordinator-record-standing]
            [canvas.coordinator.record.tickets :as c-coordinator-record-tickets]))

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
