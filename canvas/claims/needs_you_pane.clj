(ns canvas.claims.needs-you-pane
  "The claims the needs-you-pane design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [list-workstreams gates]]
            [canvas.ui.surface :refer [ui-server needs-page needs-fragment derive-screen workstream-pane]]))

(Claim paused-is-not-needs-you
  "An open workstream's row is :needs-you exactly when status says a person owes its next move and no person's pause stands on it. No workstream with a standing pause is a gate, held or unheld: it is absent from all-gates and carries no :gate on its workstream map, and a paused drain is not put on the list by the slices that raise it. Lifting the pause gives back exactly what its position and its drain give it."
  {:about [list-workstreams gates] :evidence "round"})

(Claim one-gate-rule
  "gates answers, held or unheld, through one inbox-gates fold over rows that each carry a gate only by one rule: no person's pause stands on the workstream, and it is :needs-you or a between-phases workstream awaiting its gate (a pause left standing when a closure came after it still keeps it off). The fold then lists a drain in place of its owing slices, unless a pause stands on the drain. A permission denial recorded in a session nido drives makes no gate on either path."
  {:about [gates] :evidence "round"})

(Claim home-opens-the-pane-in-place
  "GET / renders needs-page: the needs-you list beside a pane column. With ?sel= naming a workstream — <project>:<ws-id>, or <project>/<ws-id>, which view-state parses alike — it answers 200 and the pane column is workstream-pane over that workstream, the same render the Scratch page hosts; without one it is the pane's empty placeholder. Every list row links to /?sel=<project>:<ws-id>, a drain raised by its slices included (its own pane), and that drain's row is followed by a link to the drains page where its slices' questions are answered. The selected row is marked, and the list's poll carries the selection so the mark survives a refresh."
  {:about [ui-server needs-page needs-fragment] :evidence "round"})

(Claim selection-detail-on-needs-and-workstreams
  "derive-screen attaches the selected workstream's detail (:ws, dev states, machine facts) when the surface is :needs or :workstreams, and on no other surface."
  {:about [derive-screen] :evidence "round"})

(Claim acts-keep-the-person-on-needs-you
  "An act taken from the pane on / — a gate action, a reply, pause or resume — answers by patching #ws-pane only, so the browser stays on /?sel=…. Whatever it patches in keeps the pane's own poll — the confirmation a gate action or reply answers with, already-in-flight included, polls /_fragment/workstream/<project>/<ws-id> as the pane does — so the pane shows the act's effect at its next poll, and the list and the rail badge at theirs."
  {:about [workstream-pane needs-page] :evidence "round"})
