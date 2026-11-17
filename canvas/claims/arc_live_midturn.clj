(ns canvas.claims.arc-live-midturn
  "The claims the mid-turn activity design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conduct :refer [lane-conduct mid-turn? mid-turn-ids]]
            [canvas.coordinator.lane.drive :refer [status]]
            [canvas.coordinator.view.workstreams :refer [doing doing-at]]
            [canvas.coordinator.work :refer [coordinator-work]]))

(Claim mid-turn-is-the-process-turn
  "`mid-turn?` is true of a listing row exactly when the row has a :pid and :status \"busy\"; :state plays no part."
  {:about [mid-turn?] :evidence "round"})

(Claim one-reading-of-mid-turn
  "Whether a listing row shows its process mid-turn is decided by `conduct/mid-turn?`: no namespace outside conduct compares a listing row's :status to \"busy\". This reads the listing only. status's and the liveness probe's :state \"working\" reason stays beside it as the task-judged reading. recover! has a private test of whether a boot stopped a session inside a turn, made from the hook event log. That test is renamed, so conduct holds one var named `mid-turn?`."
  {:about [lane-conduct status coordinator-work] :evidence "round"})

(Claim doing-mid-turn-last
  "`doing` answers {:source :session :phase :running :session <name>} for a workstream with no closure, claim, :shipping band, locked run, or pending/queued instruction, when a session that is neither its conductor's nor a chat is stamped :mid-turn? true; every earlier source still wins when present."
  {:about [doing] :evidence "round"})

(Claim doing-stays-pure
  "`doing` reads no file, process, lock or clock; the turn reaches it as :mid-turn? on the session maps it is given."
  {:about [doing] :evidence "round"})

(Claim stopped-turn-not-working
  "A workstream whose sessions have all stopped their turns — idle at the prompt or waiting on a person's dialog — and that holds nothing else gets no `doing`."
  {:about [doing mid-turn?] :evidence "round"})

(Claim board-passes-observed-turns
  "The board's `doing-at` call is given the set `conduct/mid-turn-ids` returns, and `doing-at` stamps a session :mid-turn? only when its :claude facet's :session-id is in that set."
  {:about [coordinator-work doing-at mid-turn-ids] :evidence "round"})

(Claim live-counts-mid-turn
  "status's :live, and the board's liveness probe that restates it, are true when a driven work session (not the conductor's, not a chat's) is mid-turn by `conduct/mid-turn?`, in addition to every existing reason; no existing reason is removed."
  {:about [status coordinator-work] :evidence "round"})
