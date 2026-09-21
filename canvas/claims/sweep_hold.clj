(ns canvas.claims.sweep-hold
  "The claims the sweep-hold reading commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.session :refer [engagement-state]]
            [canvas.coordinator.record.triggers :refer [load-for-project]]
            [canvas.coordinator.work :refer [improvement-holds]]
            [canvas.ui.surface :refer [handle-request operations-page sweep-fragment]]))

(Claim holds-are-the-sweeps-hold
  "work/improvement-holds reports, for each project whose triggers declare an :improvement-sweep source, exactly the workstreams carrying an :improvement external ref that have no :closed, derived from the records on every call and stored nowhere."
  {:about [improvement-holds load-for-project] :evidence "round"})

(Claim hold-state-is-engagement
  "Each hold's state is engagement-state over its sessions — stuck when that answers :idle, waiting-on-you when :parked-at-gate, working when :active or :queued — and a stuck hold carries the latest time any of its sessions recorded a phase or substrate change, nil when it has no session."
  {:about [improvement-holds engagement-state] :evidence "round"})

(Claim operations-shows-the-hold
  "The Operations page and its five-second poll render improvement-holds under Improvement backlog as a fragment of its own, patched in the same SSE event as recovery and proposals: a stuck hold as a notice naming the workstream, saying nothing is planned or implemented until it is closed and when its last session ended; a waiting hold as a pointer to its gate; a working hold as one line naming what it works on; no holds as nothing; and a reading that could not be made as a sentence saying so, with recovery and proposals still rendered."
  {:about [sweep-fragment operations-page handle-request] :evidence "round"})
