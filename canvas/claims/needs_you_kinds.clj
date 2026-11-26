(ns canvas.claims.needs-you-kinds
  "The claims the needs-you-kinds design commits nido to: the dashboard says what needs the person
   as four kinds — ask, landing, system error and unread chat — each with its own top-bar icon,
   count and list, scoped to the focused project.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.ui.surface :refer [ui-components-state ui-components-row]]))

(Claim kind-marks-in-state-level
  "Each kind has one mark in the marks level, and every surface says that kind with it: a raised hand for an ask, a rocket for a landing, an octagon with an exclamation for a system error, and a speech bubble for an unread chat. Each is a 24-unit stroked SVG in currentColor, coloured --needs, titled with the kind's needs-words."
  {:about [ui-components-state ui-components-row] :evidence "round"})
