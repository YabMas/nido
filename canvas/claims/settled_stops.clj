(ns canvas.claims.settled-stops
  "The claims the settled-stops design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conductor :refer [lane-conductor decide! stops! reseat! answers-in-place! answers!]]
            [canvas.coordinator.lane.seats :refer [owed]]))

(Claim decision-names-hand-back-by-id
  "The conductor names the hand-back it decides by its seat-message id, which decide! checks is a hand-back not yet answered, and the text a conductor's turn is given for a hand-back carries that id on a line of its own ahead of the hand-back's prose."
  {:about [decide! owed] :evidence "test"})

(Claim stall-waits-for-the-ledger
  "An activated, unpaused workstream is handed back as stalled only when status reads it stalled, its latest recorded turn end and its ledger's newest entry are both at least ten minutes old, it owes its conductor no hand-back and no session stands on a dialog; once per turn end."
  {:about [stops!] :evidence "test"})

(Claim nido-answers-only-named-blockers
  "A :blocker-answered the person writes is their act, and the conductor cannot write one. nido writes one itself, read as no act of the person's, in six cases: marked :by :nido when a seat that could not be started is now held, when a dialog it asked about has closed, and when the person prompted the session a person's-turn escalation was about; marked :act, citing the act, when a person's act takes the step a conductor's escalation was made at; and marked :by :nido, its summary saying why, when a :verify-baseline or :decide-design halt whose last attempt ended at a machinery outcome — :timed-out, :reviewer-unavailable, :usage-limited, :reviewer-recently-unavailable, or one whose disposition is :retry — has a newer record of the kind that stage reviews on the ledger, and when nido's escalation of a hand-back the conductor ended its turns without deciding is followed by the conductor deciding a later hand-back from the same session, whether it instructs or escalates. Every other blocker waits for the person."
  {:about [lane-conductor stops! reseat! answers-in-place! answers!] :evidence "test"})
