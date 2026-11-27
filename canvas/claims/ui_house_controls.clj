(ns canvas.claims.ui-house-controls
  "The claims the house-controls design commits the web dashboard to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.libraries.ui :refer [libraries-ui-style]]
            [canvas.strata :refer [ui-visual-language ui-blocks]]
            [canvas.ui.surface :refer [ui-views ui-stylesheet ui-registry ui-catalog-scenes
                                       ui-components-ask ui-components-dialog]]))

(Claim one-producer-of-controls
  "The control set is the only thing that writes a button, input, textarea or select element in the web dashboard's markup. Pages, blocks and nido's catalog chrome call its functions."
  {:about [ui-visual-language ui-views ui-blocks] :evidence "round"})

(Claim one-rule-per-control-kind
  "Each kind of control is styled by the control set's own rule for it. No other rule, in ui-stylesheet or any block registration, styles a button, input, textarea or select, either by element or under a container selector."
  {:about [ui-visual-language ui-stylesheet] :evidence "round"})

(Claim every-control-class-styled
  "Every class the control set writes on a control is named by a rule in the assembled stylesheet."
  {:about [ui-visual-language ui-registry] :evidence "round"})

(Claim fields-one-look
  "Text input, textarea and select share one sunken-field look at one size, with one focus treatment. A field's width is set by the layout it sits in, never by a variant of the field."
  {:about [ui-visual-language] :evidence "round"})

(Claim one-quiet-button
  "Every borderless or icon button is the set's quiet button. The icon-only form cannot be rendered without an accessible label."
  {:about [ui-visual-language] :evidence "round"})

(Claim choices-are-house-controls
  "A selectable option is the set's choice card, with its chosen and primary states. An Ask's options are drawn with it, and its question stepper is drawn with the quiet icon button."
  {:about [ui-visual-language ui-blocks] :evidence "round"})

(Claim no-inline-style
  "Markup nido's server writes in src/nido/ui — its hiccup — carries no inline style attribute, with no exception for custom properties. Spacing and type go through classes over the tokens, and a value computed from data, the fleet bar's fill for instance, is carried by a control whose element takes it natively (a meter's value), not by a style. A value the page's own script measures from the live layout at runtime, such as the chat log's --chat-tail scroll room (src/nido/ui/components/chat.clj), is outside the rule, by the person's answer, and the written rule says so."
  {:about [ui-visual-language ui-views] :evidence "round"})

(Claim blocks-own-their-rules
  "A rule a block's registration carries is that block's own, and ui-stylesheet holds nido's other rules, the control set's and nido's catalog page's among them. ui-registry assembles both once into the one stylesheet every chrome under src/nido/ui emits, and none of those chromes emits rules of its own. The UI library's catalog page, which frames that stylesheet, keeps its own chrome rules in its catalog layer as the library's."
  {:about [ui-components-ask ui-components-dialog ui-registry] :evidence "round"})

(Claim the-check-holds-the-rule
  "A test run by bb nido:test reads src/nido/ui's sources and the assembled stylesheet. It fails on a button, input, textarea or select hiccup literal outside the control set's namespace, on a class the set writes that no rule names, on a visible control the set renders with no class that a rule names (the hidden input aside), on any :style attribute in src/nido/ui hiccup, and on a control rule outside the set's. Its reach is that hiccup: a control made by client script is governed by one-producer-of-controls but not seen by the test, as the seam records. bb nido:test as a whole is green on the landed tree."
  {:about [ui-registry libraries-ui-style] :evidence "round"})

(Claim catalog-shows-the-set
  "Every public function of the control set has a scene in /_catalog that calls it, and the catalog test's public-function check covers the set's namespace."
  {:about [ui-catalog-scenes] :evidence "round"})

(Claim rule-stated-generically
  "CLAUDE.md and docs/reference/ui-principles.md each state, as a rule for all dashboard UI and not only for form fields, that UI is built from house-style, purpose-made components and never from ad-hoc markup or inline styling. Each names where the set lives, how a new component is added (a function, its one rule and its scene) and the test that holds the rule. Each also says the inline-style ban covers the markup the server writes, and that a value script measures from the live layout at runtime is outside it."
  {:about [ui-visual-language] :evidence "round"})
