(ns canvas.claims.session-recovery
  "The claims the session-recovery design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the modules
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.runs :refer [teardown-session-for-run!]]
            [canvas.session.failure :refer [session-failure]]
            [canvas.session.lifecycle :refer [session-lifecycle]]))

(Claim failed-start-kept
  "Every lifecycle verb that starts a session keeps exactly one failure record when the start throws — the error chain, the tails of the instance's service logs, the verb and options attempted, and the origin its caller passed — and rethrows with the original message and ex-data plus the record's id; a record that cannot be kept never replaces the start's exception."
  {:about [session-failure session-lifecycle] :evidence "round"})

(Claim failure-outlives-its-session
  "A kept failure lives apart from the session it describes: destroying, tearing down or reclaiming that session removes no failure record."
  {:about [session-failure teardown-session-for-run!] :evidence "round"})
