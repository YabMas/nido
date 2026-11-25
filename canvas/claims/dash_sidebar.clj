(ns canvas.claims.dash-sidebar
  "The claims the dashboard-sidebar design commits nido to: the sidebar is a rail that starts
   collapsed and opens on a toggle the browser remembers, and the board the slices table lists
   first is named Legacy in that table alone.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.ui.surface :as surface]))

(Claim board-labelled-from-slices-table
  "The board's name, Legacy, is stated only in the slices table; the sidebar link, workstreams-page's title and slice-page's pointer link read it from there. Its path stays /workstreams and the :scratch workstream origin is untouched."
  {:about [surface/ui-views surface/workstreams-page surface/slice-page] :evidence "round"})
