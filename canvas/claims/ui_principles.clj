(ns canvas.claims.ui-principles
  "The claims the UI-principles design commits the web dashboard to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.report.ask :refer [report-ask Ask]]
            [canvas.coordinator.work :refer [gates workstream]]
            [canvas.ui.surface :refer [ui-views shell gate-pane action-bar]]))

(Claim principles-stated
  "docs/reference/ui-principles.md states the web dashboard's principles of hierarchy, type, colour, actions, disclosure and orientation, each as a rule a reader can check a page against and each naming the established pattern it is drawn from; it governs the web dashboard and says it does not govern the TUI."
  {:about [ui-views] :evidence "round"})

(Claim tokens-defined-once
  "Every colour and font size the dashboard renders is a CSS custom property declared once in the :root block shell emits: a type scale of four sizes, and colour tokens each named for the role the colour plays — what it is (a surface, a border, a text tier) or what it marks (a state the dashboard's colours distinguish, the source a workstream came from, an action's emphasis) — and never for its hue; each state has one hue, as a foreground and its tint. No rule, card block or inline style in nido.ui.views or nido.ui.server states a colour literal (hex, functional notation or a named hue) or a font size in any unit other than a token outside that block; the keywords transparent, inherit and none state no colour and are not literals."
  {:about [ui-views shell] :evidence "round"})

(Claim prose-is-proportional
  "The dashboard's body text is set in the prose face token (a system-ui stack); the mono face token is applied only to code, pre, kbd and the classes that carry identifiers, paths, commands and machine facts. Prose blocks on the gate pane are bounded to a reading measure in ch, never truncated."
  {:about [ui-views shell] :evidence "round"})

(Claim orientation-before-answers
  "Above the question and unfolded, the gate pane shows what the workstream is for (the live intent's goal and its done-whens), where it is, and how this decision fits. Where it is: the gate's arc of stages, its current stage marked when the position is on the arc; beside it the position named and its next move, as the workstream pane's status heading and next line name them, which is how a gate past the arc — a landing, a phase's exit gate — says where the work is now; and, when something is running, what. How it fits: the Ask's :holding when the Ask carries one, and otherwise the named position, with no sentence of the view's own. A gate with no live intent shows the same band without the goal."
  {:about [gate-pane gates] :evidence "round"})

(Claim holding-per-kind
  "Each registered kind but :generic yields an Ask whose :holding says, in a person's terms, what in the workstream waits on this answer; it is read from the kind's registry entry and the record, never from the view."
  {:about [report-ask Ask] :evidence "round"})

(Claim gate-arc-is-the-panes
  "Every gate carries :arc and :position, the arc of stages and the position that workstream returns for the same workstream at the same moment — the same pipeline/arc over the current phase's entries with the stage and activity of the position the row carries, and that position itself — so the gate pane and the workstream pane cannot show different stages or name the position differently."
  {:about [gates workstream] :evidence "round"})
