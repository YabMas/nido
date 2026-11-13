(ns canvas.claims.ui-principles
  "The claims the UI-principles design commits the web dashboard to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.drive :refer [arc stage-active]]
            [canvas.coordinator.report.ask :refer [report-ask Ask]]
            [canvas.coordinator.work :refer [gates workstream]]
            [canvas.ui.surface :refer [ui-views shell needs-page needs-fragment ui-server workstream-pane]]))

(Claim principles-stated
  "docs/reference/ui-principles.md states the web dashboard's principles of hierarchy, type, colour, actions, disclosure and orientation, each as a rule a reader can check a page against and each naming the established pattern it is drawn from; it governs the web dashboard and says it does not govern the TUI."
  {:about [ui-views] :evidence "round"})

(Claim tokens-defined-once
  "Every colour and font size the dashboard renders is a CSS custom property declared once in the :root block shell emits: a type scale of four sizes, and colour tokens each named for the role the colour plays — what it is (a surface, a border, a text tier) or what it marks (a state the dashboard's colours distinguish, the source a workstream came from, an action's emphasis) — and never for its hue; each state has one hue, as a foreground and its tint. No rule, card block or inline style in nido.ui.views or nido.ui.server states a colour literal (hex, functional notation or a named hue) or a font size in any unit other than a token outside that block; the keywords transparent, inherit and none state no colour and are not literals."
  {:about [ui-views shell] :evidence "round"})

(Claim prose-is-proportional
  "The dashboard's body text is set in the prose face token (a system-ui stack); the mono face token is applied only to code, pre, kbd and the classes that carry identifiers, paths, commands and machine facts. Prose blocks on the gate pane are bounded to a reading measure in ch, never truncated."
  {:about [ui-views shell] :evidence "round"})

(Claim home-lists-what-waits
  "GET / renders needs-page as the needs-you list alone, with no detail pane, each row linking to /workstreams/<project>/<ws-id>. GET / with ?sel=<project>:<ws-id> and GET /gate/<project>/<ws-id> each redirect to /workstreams/<project>/<ws-id>, and every link the dashboard renders as opening a gate — the list's rows and the 'open the gate' links of the operations and recovery notices — is /workstreams/<project>/<ws-id>. Nothing renders gate-pane or patches #gate-pane, and there is no POST /gate/<project>/<ws-id>/<action> route: a gate action posts through pane-route."
  {:about [ui-server needs-page needs-fragment] :evidence "test"})

(Claim gate-carries-no-pane-facts
  "A gate map carries no :arc and no :position key; of the gate and workstream maps, only the workstream map carries them."
  {:about [gates workstream] :evidence "test"})

(Claim stage-list-marks-waiting
  "pipeline/arc takes :awaiting, the stage whose next move is a person's, beside :active, and in place of :active? gives a facet one :now value: :awaiting on the stage :awaiting names, otherwise :running on the stage :active names. work/workstream passes its position's stage as :awaiting exactly when awaiting-human holds for that position. arc-row dispatches on :now: :awaiting draws a mark of its own in the --needs colour, titled 'waiting on you'; :running draws the running mark; a facet without :now draws its state's glyph."
  {:about [arc stage-active workstream workstream-pane] :evidence "test"})

(Claim no-restated-lines
  "workstream-pane renders no position line ('next · … — waiting on …') and no activity label: no text wsv/doing-label produces appears anywhere in the pane, the stage list's rows and their marks' titles included."
  {:about [workstream-pane] :evidence "test"})

(Claim ask-carries-no-holding
  "The Ask schema has no :holding key, and no registry entry of report.ask builds one."
  {:about [Ask report-ask] :evidence "test"})

(Claim principles-say-context-first
  "docs/reference/ui-principles.md orientation rule says a pane that asks first sets out the context that orients a cold reader — the workstream pane's own status heading, plan, stage list and standing records — and then asks, the question card below that context and above History; its disclosure rule orders overview, then the ask, then evidence; and a rule of its own says a pane never states what its placement already says. The orientation rule names the person's two reviews as its source."
  {:about [ui-views] :evidence "round"})
