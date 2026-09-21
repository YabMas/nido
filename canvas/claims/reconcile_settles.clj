(ns canvas.claims.reconcile-settles
  "The claims the reconcile-settles-sessions design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.daemon.lifecycle :refer [reconcile!]]
            [canvas.coordinator.record.runs :refer [owns-session? stop-session-for-parked-run!
                                                    teardown-session-for-run!]]))

(Claim reconcile-settles-owned-sessions
  "reconcile! settles the session a Run owns whenever that session's record is still :live: through teardown-session-for-run! when the Run is :done, :failed or :halted — whether reconcile moved it there on this start or found it there — and through stop-session-for-parked-run! when reconcile moves it to :awaiting-review on this start."
  {:about [reconcile! owns-session? teardown-session-for-run! stop-session-for-parked-run!]
   :evidence "round"})

(Claim reconcile-spares-what-is-not-its-own
  "reconcile! settles no session its Run does not own and no session whose record is not :live. A Run owns the session it names only when that session's record carries the Run's trigger and no Run of that trigger created after it names the same workstream and session — so an older terminal Run never settles the session a later Run of its trigger re-created at the same name, as a :session-recovery retry for one cause or a restore successor does. A second reconcile over the records the first one left settles nothing."
  {:about [reconcile! owns-session?] :evidence "round"})

(Claim reconcile-settles-past-a-failure
  "A settle that throws for one Run is reported and reconcile! goes on to settle every remaining Run."
  {:about [reconcile!] :evidence "round"})
