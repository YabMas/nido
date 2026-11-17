(ns canvas.claims.turn-boundary-readiness
  "The claims the turn-boundary readiness design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conduct :refer [decide baseline-shown baseline-refusal conduct!]]))

(Claim turn-read-off-boundaries
  "whether a session's turn has ended — for readiness, lapse and the wake gate — is read from its latest :prompt or :stop alone; no other event kind recorded after a Stop changes the answer"
  {:about [decide baseline-shown] :evidence "round"})

(Claim in-flight-turn-still-waits
  "a session the listing does not show done, whose latest turn boundary is a :prompt, is not ready, whatever was recorded after that prompt"
  {:about [decide] :evidence "round"})

(Claim mode-from-a-reporting-event
  "the permission mode the baseline check judges is the one the latest event that reports a mode reports; an event reporting none — a start, a notification — neither sets nor clears it"
  {:about [baseline-shown baseline-refusal] :evidence "round"})

(Claim held-is-reported
  "a nido-driven session with something to give, whether or not an instruction is outstanding on it, listed idle at its prompt and not ready, yields a :held report entry carrying the instant it was first seen held; that instant is kept across passes while it stays held and dropped when it is not"
  {:about [decide conduct!] :evidence "round"})

(Claim held-past-bound-shown
  "every session conduct reports :held for longer than the bound is listed by coordinator status with its workstream, session and since, whether or not its record carries an autonomy facet, and is no longer listed once a pass stops reporting it held"
  {:about [conduct!] :evidence "round"})
