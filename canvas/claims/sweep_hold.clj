(ns canvas.claims.sweep-hold
  "The claims the sweep-hold reading commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [status]]
            [canvas.coordinator.record.triggers :refer [load-for-project]]
            [canvas.coordinator.work :refer [improvement-holds]]
            [canvas.ui.surface :refer [handle-request improvements-page sweep-fragment]]))

(Claim holds-are-the-sweeps-hold
  "work/improvement-holds reports, for each project whose triggers declare an :improvement-sweep source, exactly the workstreams carrying an :improvement external ref whose ledger holds no standing close, derived from the records on every call and stored nowhere."
  {:about [improvement-holds load-for-project] :evidence "round"})

(Claim hold-state-is-engagement
  "Each hold's state is the engagement its workstream's status reads — waiting-on-you when a person owes its next move, working when something is live or the machine owes the move, stuck when it is idle or stalled — and a stuck hold carries the latest time any of its runs recorded a transition or its sessions a substrate change, nil when it has neither."
  {:about [improvement-holds status] :evidence "round"})

(Claim operations-shows-the-hold
  "The improvement-backlog page (/operations/improvements) and its five-second poll render improvement-holds above the proposals as a fragment of its own, patched in the same SSE event as the proposals: a stuck hold as a notice naming the workstream, saying nothing is planned or implemented until it is closed and when its last session ended; a waiting hold as a pointer to its gate; a working hold as one line naming what it works on; no holds as nothing; and a reading that could not be made as a sentence saying so, with the proposals still rendered."
  {:about [sweep-fragment improvements-page handle-request] :evidence "round"})
