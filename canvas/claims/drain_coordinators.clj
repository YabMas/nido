(ns canvas.claims.drain-coordinators
  "The claims the drain-coordinators design (ws-20261127-8af672) commits nido to: an open drain's
   coordinator is woken by notices nido posts to the drain's core workstream — its charter, each
   census, landing and failed landing — and is briefed as the drain's coordinator; closing a drain
   closes its core workstream, which retires its conductor.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.boot.intake :refer [boot-intake]]
            [canvas.coordinator.lane.drain :refer [close! coordinator-brief propose! wakes!]]
            [canvas.coordinator.record.drain :refer [record-drain]]
            [canvas.coordinator.work :refer [drain-act! drain-wakes!]]
            [canvas.core.buffer :refer [brief conductor-spec-fn core-buffer person-input? wake-owed?]]
            [canvas.core.domain :refer [core-domain decide evolve]]
            [canvas.core.release :refer [core-release]]))

(Claim charter-wakes-coordinator
  "A drain with no approved recipe version and no pending proposal has a wake event keyed by its :drain charter entry, so the intake pass posts it once and the drain's conductor is woken with no person's act."
  {:about [wakes! record-drain] :evidence "round"})

(Claim charter-turn-proposes-recipe
  "The coordinator brief of a drain with no approved recipe version says none is approved and asks the turn to propose one, and the propose lever, run from the drain's conductor turn, appends a :recipe-proposed entry to the drain's ledger."
  {:about [coordinator-brief propose! record-drain] :evidence "round"})

(Claim drain-wakes-are-census-and-landings
  "A drain's wake events are its charter while it has no recipe version and no pending proposal, each :census reading, each slice that has landed, and each failed slice landing — a unit turn of one of its slices that [landing-turn-marked] marks as having ended under the slice's :merge grant — each keyed by its event, so each is posted once."
  {:about [wakes! record-drain] :evidence "round"})

(Claim landing-turn-marked
  "A unit turn that ends other than completed while its workstream holds the :merge grant and its unit has not merged is marked in state as having ended under the grant, by whichever fold ends it — :turn-ended, :operator-ask-raised cancelling a concerned unit's running turns, or :unit-held cancelling a held unit's active turn — and no other unit turn is; whether a turn was a failed landing is read off that mark, never inferred later from the grants state holds then."
  {:about [evolve] :evidence "round"})

(Claim wakes-posted-as-keyed-chat
  "A drain wake reaches the core as a :post-notice command whose notice id is the event's key, sent through the drain-core map. The core keeps every notice id posted to a workstream in its state's :notices for the workstream's life, refuses a :post-notice whose id :notices already holds, and folds a notice into :notices and into an inbox item of kind :notice, which is not a person's input, and never into :chat. The notice carries the event, not the coordinator brief. lane-drain reads :notices through the drain-core map's state read, and counts an event posted when its key is in :notices or is the id of a chat message posted as a wake before notices existed."
  {:about [wakes! drain-wakes! decide evolve] :evidence "round"})

(Claim conductor-brief-is-generic
  "A conductor turn on a workstream whose ledger index holds a :drain birth entry is told the drain's coordinator brief, as nido's own verb answers it when the turn's spec is made, in place of the generic role and the generic Rules for conducting units; it keeps the generic Rules on asking the person — mcp__nido__ask as the only tool call of its turn, written to the ask briefing — and on the person's chat messages, and what it has not yet heard and the workstream now as the generic brief gives them. When the verb gives no brief, the drain's wake is withheld: core-buffer submits no command under the wake's key, so nothing is committed there, and decides the drain's wake again no later than a retry interval after withholding it, until a brief is given; meanwhile what it would hear stays in its inbox. A conductor turn on any other workstream is told the generic brief exactly as before, and the brief reader is not asked for it."
  {:about [brief conductor-spec-fn core-release] :evidence "round"})

(Claim brief-states-runnable-levers
  "The coordinator brief prints each lever as `bb --config ~/Code/nido/bb.edn nido:drain:…`, which resolves from the core's workstream directory; it says that what is the person's to decide is asked of the person, leaving how an ask is made to the Rules its turn is told beside it, and says nothing about which tools the turn may act through; it states a failed landing among its wakes."
  {:about [coordinator-brief] :evidence "round"})

(Claim escalations-read-through-record-drain
  "The coordinator brief reads a drain's open escalations through a record-drain reading, not off the ledger's :blocker and :blocker-answered entries itself."
  {:about [coordinator-brief record-drain] :evidence "round"})

(Claim drain-close-is-ledger-only
  "Every caller of lane-drain's close! — the person's verb, the drains page and a drain marked :done — reaches it through coordinator-work's close entry point, which hands it the drain-core map; close! commits :exit-observed, then a keyed :close-workstream through the map, then :closed, so no conductor turn starts on a drain after close! commits its :closed entry. A drain whose ledger closure stands by any other path is closed in the core by the next wakes pass, which sends the same keyed close for any drain whose ledger closure stands while the core holds it open."
  {:about [close! drain-act! wakes!] :evidence "round"})

(Claim slice-landing-heard-by-slice
  "A slice lands by the person's :merge grant on the slice's own workstream, whose conductor directs the landing; the drain's conductor hears of that landing's outcome as a notice — once for the slice when it lands, once for each of its unit turns [landing-turn-marked] marks."
  {:about [wakes!] :evidence "round"})

(Claim core-carries-conduct-duties
  "Of the deleted conduct pass's duties, the drain coordinator's start is carried by the charter notice; answers! by the core's asks, whose answers reach the conductor as inbox items; reseat! by core-provision re-provisioning a unit's session; and of stops!'s, answer-moot! by the conductor's withdraw_operator_ask, dialogs! by the executor's hook-held ask, and dead-turns! by the core's take-back at start of the turns recorded running, heard as each turn's end. Two stay with no counterpart, deliberately: close-unanswered!'s re-offer and escalation of a hand-back a completed turn heard without deciding, and stops!'s stall hand-back of work idle after a turn end — the core runs a conductor only for something in its inbox."
  {:about [core-buffer core-domain boot-intake] :evidence "round"})

(Claim only-observed-events-wake
  "A notice wakes a conductor as any inbox item does, under the cap on turns hearing no person's input; a drain conductor turn is never owed to anything but an item in its inbox."
  {:about [wake-owed? person-input?] :evidence "round"})
