(ns canvas.claims.drains-surface
  "The claims the drains operating-surface design (ws-20261127-6eff82) commits nido to: each open
   drain on /structure/drains leads with its phase and the one act it owes the person, stands its
   escalations and recipe proposals as sections of their own — each proposal as the recipe it
   would install, diffed against the current one — renders every reading view-drains carries,
   folds nothing inside the polled #drains, and starts a drain from below the list with an
   explicit choice of what established the pattern.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.view.drains :refer [view-drains]]
            [canvas.strata :refer [ui-visual-language ui-state-marks]]
            [canvas.ui.surface :refer [drains-fragment drains-page ui-server ui-views
                                       ui-components-control ui-components-state
                                       ui-catalog-scenes]]))

(Claim one-due-act-leads-each-drain
  "Each drain card's first element after its title is the drain's phase tag beside at most one primary act, the highest of Answer (the first open escalation in the order view-drains lists them — the drain's own, then each slice's), Approve (the oldest pending proposal), Land (the oldest-started cleared slice) and Close (phase :exhausted) that the drain's value admits; it is derived from the value alone, and it is the card's only primary button — the item it names keeps its own act, as every proposal section and slice row does, as a secondary button."
  {:about [drains-fragment] :evidence "round"})

(Claim escalations-stand-as-their-own-section
  "A drain whose value carries open escalations shows them in a section of its own, headed as escalations, with one item for each escalation the value carries."
  {:about [drains-fragment] :evidence "round"})

(Claim proposal-shown-as-its-recipe-diffed
  "view-drains carries each pending proposal's recipe body; the page renders each proposal in its own section with its reason, timestamp and Approve act, and every field the proposed recipe holds line by line in order, each line marked added, removed or unchanged by an ordered line diff against the same field of the drain's current recipe, every line marked added when there is none."
  {:about [view-drains drains-fragment] :evidence "round"})

(Claim drain-states-said-by-the-state-level
  "A drain's phase and a slice's state are said on the page only through ui-components-state: each is a chip in its state's semantic hue — the person's yellow where the person owes the move (a proposal to approve, a drain to close, a slice to land), the working blue where nido or an agent has it, ok for landed — with its words, and ui-views holds no word table for either."
  {:about [ui-components-state ui-views ui-state-marks] :evidence "round"})

(Claim every-carried-reading-rendered
  "A drain card renders every open plan (its sites, the plans it rests on, its start and its state), every open misfit (site and note) under the slice row that raised it, every residue item with its disposition or that it has none, the newest census's remaining count and groups, the charter's established-by and chartered-at, and each slice row's state tag, plan sites and Land act where its state admits one."
  {:about [drains-fragment] :evidence "round"})

(Claim nothing-folded-in-the-polled-fragment
  "#drains contains no disclosure: everything a decision on a drain needs is shown unfolded, so the poll's re-render, morphed into the page, never closes what the person opened. Of the drains view's own content — #drains and the start form, not the shell's shared navigation — the start form is the only disclosure, outside #drains and after it, rendered open only when no drain is listed."
  {:about [drains-fragment drains-page] :evidence "round"})

(Claim start-posts-only-the-chosen-source
  "The start form's established-by source is one choice between a workstream and a landed commit, each field under a visible label; ui-server hands work/start-drain! only the chosen source's value, so a start never carries both."
  {:about [drains-page ui-server] :evidence "round"})

(Claim trend-drawn-as-a-sparkline
  "A drain's census trend under its current recipe, when it has two or more readings, is drawn by ui-components-control's sparkline — an SVG whose points are computed from the readings, with no style attribute and the readings in its accessible name — and no arrow-joined trend string is written."
  {:about [ui-components-control drains-fragment ui-visual-language] :evidence "round"})

(Claim only-one-key-beneath-the-surface
  "The change beneath nido.ui is view-drains carrying each pending proposal's :recipe and nothing else: the page reads every other reading from keys the value already carries, and work/start-drain!, work/drain-act! and the drain lane are unchanged."
  {:about [view-drains ui-views] :evidence "round"})

(Claim test-suite-passes
  "bb nido:test passes on the change, nido.ui.controls-test and nido.ui.catalog-test among its tests."
  {:about [ui-views ui-components-control ui-components-state ui-catalog-scenes] :evidence "round"})
