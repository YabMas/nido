(ns canvas.claims.workstream-page
  "The claims the workstream-page design commits the web dashboard to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.ui.surface :refer [ui-views workstream-pane workstreams-page workstream-page
                                       handle-request]]))

(Claim page-hosts-the-pane
  "GET /workstreams/<p>/<ws-id> answers workstream-page, which draws that workstream's content by calling workstream-pane with the screen selection's :ws, :dev-states and :machine — the same call workstreams-page makes — and draws no board (#workstreams) and no other workstream content of its own."
  {:about [workstream-page workstream-pane workstreams-page] :evidence "test"})

(Claim page-is-live
  "On the workstream's page the pane refreshes and acts as on the board, through the routes the board's pane uses [pane-is-self-refreshing]: its poll and each of its actions answer on the page with the fragment they answer on the board, which replaces #ws-pane, and no route or fragment is added for the page alone."
  {:about [workstream-pane handle-request] :evidence "round"})

(Claim pane-links-to-page
  "workstream-pane, for any workstream but a bare watched-view row, renders a link to /workstreams/<p>/<ws-id> in its heading on every host, and takes no argument saying which page it is drawn on; the page hides that link by a stylesheet rule scoped to the page."
  {:about [workstream-pane] :evidence "test"})

(Claim page-full-width
  "The workstream page lays the pane out as the only column of the shell's content area — no grid column beside it and no width rule narrower than the content area on the pane's container; prose inside keeps its reading measure."
  {:about [workstream-page] :evidence "round"})

(Claim one-page-href
  "ui-views builds the path of a workstream's page in one function, and every link that names one workstream as a destination outside the board's own rows and Needs-you's rows — the open-the-gate links, hold rows, recovery rows, denials, pickup results, the gate-action confirmation, the drains' slice links and the pane's own link — uses it."
  {:about [ui-views] :evidence "round"})
