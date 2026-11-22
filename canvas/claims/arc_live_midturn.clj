(ns canvas.claims.arc-live-midturn
  "The claims the mid-turn activity design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [status]]
            [canvas.coordinator.view.workstreams :refer [doing doing-at]]
            [canvas.coordinator.work :refer [coordinator-work]]))

(Claim doing-mid-turn-last
  "`doing` answers {:source :session :phase :running :session <name>} for a workstream with no closure, claim, :shipping band, locked run, or pending/queued instruction, when a session that is neither its conductor's nor a chat is stamped :mid-turn? true; every earlier source still wins when present."
  {:about [doing] :evidence "round"})

(Claim doing-stays-pure
  "`doing` reads no file, process, lock or clock; the turn reaches it as :mid-turn? on the session maps it is given."
  {:about [doing] :evidence "round"})

(Claim live-counts-mid-turn
  "status's :live, and the board's liveness probe that restates it, are true when a driven work session (not the conductor's, not a chat's) is mid-turn by `conduct/mid-turn?`, in addition to every existing reason; no existing reason is removed."
  {:about [status coordinator-work] :evidence "round"})
