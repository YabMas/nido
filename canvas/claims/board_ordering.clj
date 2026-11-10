(ns canvas.claims.board-ordering
  "The claims the board-ordering design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.view.workstreams :refer [grouped-by-stage format-row]]
            [canvas.coordinator.work :refer [gates all-gates]]
            [canvas.ui.surface :refer [ui-tui ui-views]]))

(Claim newest-first-within-band
  "grouped-by-stage orders the Incoming, Awaiting gate, In progress, Shipping and Dismissed bands by :last-activity alone, newest first, with a nil :last-activity last; a row's :needs-you has no effect on its position in any band."
  {:about [grouped-by-stage] :evidence "round"})

(Claim needs-you-still-shown
  "A row that needs a person is still identifiable on both surfaces, by format-row's ⏸ prefix in the TUI and the needs span in the dashboard row, and the set of gates is unchanged, because neither reads a row's position."
  {:about [format-row ui-views gates] :evidence "round"})

(Claim other-orderings-unchanged
  "The band order, the Triage band's in-flight and queued orders, the dashboard's project-by-project grouping, the Winding down band's construction and how :last-activity is computed are as the baseline describes them."
  {:about [grouped-by-stage ui-tui ui-views] :evidence "round"})

(Claim all-gates-docstring-true
  "all-gates' docstring states the order gates come in as the code produces it, and claims no order nothing produces."
  {:about [all-gates] :evidence "round"})
