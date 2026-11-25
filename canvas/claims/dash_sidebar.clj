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

(Claim sidebar-starts-collapsed
  "A dashboard page loaded in a browser holding no stored choice renders the sidebar collapsed: no slice label shows, and the content column takes the width the open sidebar would have used."
  {:about [surface/shell] :evidence "round"})

(Claim one-sidebar-open-signal
  "Whether the sidebar is open is one signal shell declares on <body> with data-signals__ifmissing, seeded from one localStorage key and written back to it by the toggle and nothing else; no fragment a poll replaces declares or resets it, and the server reads no preference."
  {:about [surface/shell surface/ui-views] :evidence "round"})

(Claim collapsed-rail-keeps-health
  "Collapsed, the rail still shows the toggle and the daemon-health button, which still opens the ops panel; collapse is a class on chrome outside #rail-health, so rail-status-fragment renders the same markup open or closed."
  {:about [surface/shell surface/rail-status-fragment surface/ui-stylesheet] :evidence "round"})

(Claim sidebar-width-stated-once
  "The side column's open width and collapsed width are each stated once in ui-stylesheet, and every grid-template-columns rule — the base grid and each side-chat variant — reads them, so the side-chat layout follows the sidebar's state."
  {:about [surface/ui-stylesheet] :evidence "round"})
