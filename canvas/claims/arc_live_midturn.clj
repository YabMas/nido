(ns canvas.claims.arc-live-midturn
  "The claims the mid-turn activity design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.view.activity :refer [of]]
            [canvas.coordinator.work :refer [coordinator-work]]))

(Claim doing-mid-turn-last
  "The activity's :doing answers {:source :session :phase :running :session <name>} for a workstream with no closure, claim, core activity, :shipping band, locked run, or instruction pending or queued in a session that is up, when a session that is neither its conductor's nor a chat is stamped :mid-turn? true; every earlier source still wins when present."
  {:about [of] :evidence "round"})

(Claim doing-stays-pure
  "`of` reads no file, process, lock or clock; the turn reaches it as :mid-turn? on the session maps its observation carries."
  {:about [of] :evidence "round"})

(Claim live-counts-mid-turn
  "The activity's :live is true when a work session (not the conductor's, not a chat's) is stamped :mid-turn?, in addition to every other reason; no other reason is removed."
  {:about [of coordinator-work] :evidence "round"})
