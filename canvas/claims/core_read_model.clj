(ns canvas.claims.core-read-model
  "The claims the core read model commits nido to: what runs on a workstream is read from the core,
   never from a lock a process holds or a session being up. Each Claim's name is the claim's id,
   its docstring the statement, and its :about the elements it is about."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [status]]
            [canvas.coordinator.view.activity :refer [of observe]]
            [canvas.coordinator.work :refer [coordinator-work list-workstreams]]))

(Claim what-runs-from-the-core
  "An open workstream row's :live and the core's part of its :doing are read from the core's committed state of its workstream; status answers the position, owed-by, activation and pause its work records say, and nothing about what runs. :live exactly while the core's activity on it is :working, :deciding or :starting and no pause stands. :doing is the core's activity, or — while the core does nothing on a :shipping row — a merge waiting its turn, which is underway but not live. :needs-you holds whenever the core holds an operator ask open, and then owed-by is :person; it holds besides for what the activity's other categories name (owed-by-the-person-is-needs-you). :paused while a person's pause stands or a person holds every open unit. No Run, Run lock, activity claim or session being up is read as something running on a workstream."
  {:about [coordinator-work list-workstreams status of] :evidence "test"})

(Claim claim-labels-a-working-turn
  "While the core's activity on an open workstream is :working, a held activity claim against it is what the row's :doing says, with its review round's :progress when its report can be read; at any other time no claim is read. A row whose claim it shows is recomputed when the claim's report is rewritten."
  {:about [observe of coordinator-work] :evidence "test"})

(Claim stall-is-the-cores
  "An open workstream's row reads :stalled exactly when the core is its mover — it holds the workstream with the recipe activated on it, or holds an open unit of it that no person holds — the core has no activity on it, nothing is underway, no pause stands, no failure only the person clears stands and the move is an agent's or the machine's — a move the person owes, a release candidate's landing included, and a session that failed to start both need the person otherwise, and a move nobody owes waits on nothing; none of them is stalled. A stalled row needs the person as a :system-error. A workstream the core is not the mover of is never stalled."
  {:about [of coordinator-work] :evidence "test"})
