(ns canvas.claims.component-catalog
  "The claims the component-catalog design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.strata :refer [ui-blocks ui-state-marks ui-visual-language]]
            [canvas.ui.surface :refer [handle-request question-card shell status-heading
                                        ui-catalog ui-catalog-chat ui-catalog-scenes ui-components-ask ui-components-row
                                        ui-components-state ui-datastar ui-server ui-stylesheet
                                        ui-views workstreams-fragment]]))

(Claim blocks-render-from-a-value
  "Every block the component modules publish renders from its arguments alone: a value, plus — where the block is clickable — the URL or Datastar expression a click carries. None of them reads the ledger, the work plane's state, the session registry, the filesystem, the clock, the system timezone, a request or page state. The one call any of them makes outside the component namespaces is View's single wording of what a workstream is doing, which is a pure function of the value it is given; inside them a row calls a mark, which is the level edge ui-blocks declares onto ui-state-marks."
  {:about [ui-components-state ui-components-row ui-components-ask ui-blocks ui-state-marks] :evidence "test"})

(Claim blocks-are-addressable
  "The dashboard's visual building blocks — the marks, the rows, the ask and the stylesheet — are published from the module of the vocabulary they belong to, and a page renders one of them by calling its published function, never a private copy of its own. What production code calls of nido.ui.views is then pages, fragments, operations-home-cards and fire-signal, from nido.ui.server alone; what nido.ui.views still holds privately is the page-specific regions it composes a page from and the reading of a ledger record."
  {:about [ui-views ui-components-state ui-components-row ui-components-ask ui-server] :evidence "round"})

(Claim one-token-block
  "Every colour and every font size the dashboard renders is declared once, as a custom property in the single :root block nido.ui.stylesheet holds, and every declaration that uses one reads it as var(--…) — whether it is a class rule or one of the inline style attributes the markup already carries. Every class rule the dashboard's pages and blocks name is written in that module, and every chrome that renders dashboard markup emits that module's stylesheet."
  {:about [ui-stylesheet shell ui-visual-language] :evidence "test"})

(Claim one-state-vocabulary
  "The position, the next stage a workstream owes, who owes it, the arc stage and the mark for an arc stage's record state are each worded in one table inside nido.ui.components.state, and the dashboard says them by reading that table rather than wording them inline. A scene cannot word one either: the only way to show a mark is to call the function that words it."
  {:about [ui-components-state status-heading workstreams-fragment ui-state-marks] :evidence "round"})

(Claim posted-names-shared-by-literal
  "The server reads a posted JSON signal body by key names the markup writes as literals in its hiccup — reply, evidence, pickup, findings, staging, and the qcard/answers/q<i> layout of the question card's signals — and names the ws-pane region a gate response patches by a literal of its own. No definition is shared between the sides, so a rename in the markup breaks the server without either namespace saying so. The split moves the reply, evidence and qcard literals into nido.ui.components.ask, which leaves the sharing exactly as it was and puts one more module between the two ends."
  {:about [ui-views ui-server ui-components-ask] :evidence "round"})

(Claim url-params-shared-by-literal
  "The names in the query strings of the URLs the dashboard's markup writes are literals in nido.ui.views and nido.ui.components.ask, and the server side reads each back by a literal of its own, so no definition is shared and a rename on one side breaks the other without either saying so. The catalog is the one exception the split adds rather than inherits: its three names are written and read in nido.ui.catalog alone, from one table, so nothing repeats them."
  {:about [ui-views ui-server ui-components-ask ui-catalog] :evidence "round"})

(Claim every-block-has-a-scene
  "Every function the component modules publish appears in the catalog's registry with at least one named scene, and the scene set covers every shape a block renders differently — which for the question card is one question, several questions, pending, a failed action, no recommendation, an evidence box, and a question too long to read in one line. A test renders every registered scene and fails on one that throws or whose markup has lost what identifies its block. A published function that returns words or a key rather than markup gets a scene that shows what it returns, so the rule carries no exception."
  {:about [ui-catalog-scenes ui-components-state ui-components-row ui-components-ask] :evidence "test"})

(Claim catalog-renders-what-the-app-renders
  "GET /_catalog lists the registry's components in a sidebar and renders every scene of the selected one, with Datastar loaded from the one script element nido.ui.datastar publishes, which the dashboard's pages load it from too. The catalog holds no copy of anything it shows. Its page lists every block vocabulary in a sidebar and renders every scene of the selected one, each by calling the same function the dashboard calls, in the stylesheet nido.ui.stylesheet holds — so a scene that looks wrong is the block being wrong, never the catalog disagreeing with the app."
  {:about [ui-catalog ui-catalog-scenes ui-stylesheet ui-datastar] :evidence "round"})

(Claim catalog-is-unlinked
  "GET /_catalog is served by the one dashboard handler, on every dashboard, and nothing in the product's chrome links to it. Its scene page reads nothing but the registry and its query string. Only the catalog's chat reaches a process, and only through the conversation host and the preview."
  {:about [ui-catalog ui-catalog-chat handle-request] :evidence "round"})

(Claim markup-unchanged
  "The split changes no page main's dashboard renders: for every page and every fragment main serves, the markup produced over given arguments is what main produces over the same arguments. No assertion the existing ui tests make is removed, relaxed or re-aimed at something weaker."
  {:about [ui-views shell handle-request question-card] :evidence "round"})

(Claim lands-on-green
  "On this change's tip, bb nido:test shows no failure and no error beyond the three conductor_test errors main already has, bb nido:design:check reports ok, and bb nido:land:check passes."
  {:about [ui-stylesheet ui-components-state ui-components-row ui-components-ask ui-catalog-scenes ui-catalog ui-views ui-server] :evidence "round"})

