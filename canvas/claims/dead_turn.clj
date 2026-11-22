(ns canvas.claims.dead-turn
  "The claims the dead-turn design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.tasks.nido-session-hook :refer [nido-session-hook]]
            [canvas.coordinator.record.session-events :refer [record-session-events]]
            [canvas.coordinator.report :refer [validate-event]]))

(Claim session-end-is-recorded
  "The session hook appends a session-end fact carrying the reason Claude Code gives for every SessionEnd payload that names a session, a sixth kind in a conversation's log, and the launch baseline hooks SessionEnd wherever it hooks the other five."
  {:about [nido-session-hook record-session-events] :evidence "test"})

(Claim stop-kinds-are-two
  "A :blocker's :stop names its kind as :dialog or :dead-turn, and the write contract refuses any other."
  {:about [validate-event] :evidence "test"})
