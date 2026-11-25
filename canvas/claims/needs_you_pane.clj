(ns canvas.claims.needs-you-pane
  "The claims the needs-you-pane design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [list-workstreams gates]]
            [canvas.ui.surface :refer [ui-server needs-page needs-fragment derive-screen workstream-pane]]))

(Claim home-opens-the-pane-in-place
  "GET / renders needs-page: the needs-you list beside a pane column. With ?sel= naming a workstream — <project>:<ws-id>, or <project>/<ws-id>, which view-state parses alike — it answers 200 and the pane column is workstream-pane over that workstream, the same render the Scratch page hosts; without one it is the pane's empty placeholder. Every list row links to /?sel=<project>:<ws-id>, its own workstream's pane, a slice's and a drain's alike. The selected row is marked, and the list's poll carries the selection so the mark survives a refresh."
  {:about [needs-page needs-fragment] :evidence "round"})

(Claim selection-detail-on-needs-and-workstreams
  "derive-screen attaches the selected workstream's detail (:ws, dev states, machine facts) when the surface is :needs or :workstreams, and on no other surface."
  {:about [derive-screen] :evidence "round"})

(Claim acts-keep-the-person-on-needs-you
  "An act taken from the pane on / — a gate action, a reply, pause or resume — answers by patching #ws-pane only, so the browser stays on /?sel=…. Whatever it patches in keeps the pane's own poll — the confirmation a gate action or reply answers with, already-in-flight included, polls /_fragment/workstream/<project>/<ws-id> as the pane does — so the pane shows the act's effect at its next poll, and the list and the rail badge at theirs."
  {:about [workstream-pane needs-page] :evidence "round"})
