(ns canvas.claims.jj-floor
  "The claims the convergence loop's retirement commits nido to: one jj wrapper on the floor, a
   design seam handed directories rather than revisions, and nothing of the loop left anywhere.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.platform.jj :refer [platform-jj materialize-spec-dirs!]]
            [canvas.tasks.nido-design :refer [nido-design]]
            [canvas.tasks.nido-help :refer [nido-help]]
            [canvas.ui.structure :refer [ui-structure]]))

(Claim jj-on-the-floor
  "nido.platform.jj, in the Platform band, is nido's one jj wrapper: the review loop, the landing, recovery and design tasks, the composition root and the structure explorer run jj through its jj!, current-change-id and materialize-spec-dirs!, and no other namespace publishes a jj wrapper."
  {:about [platform-jj] :evidence "round"})

(Claim design-seam-takes-a-directory
  "A declaration at a revision is resolved to a directory by materialize-spec-dirs!, and nido.design.* is handed that directory and never a revision: no nido.design.* namespace runs jj."
  {:about [materialize-spec-dirs! nido-design ui-structure] :evidence "round"})

(Claim convergence-loop-retired
  "nido has no critic/judge/implementer convergence loop: no source namespace, bb task, help section, resource, spec, canvas declaration or band carries the retired loop's name, and no band's :may-depend names the band it had."
  {:about [nido-help platform-jj] :evidence "round"})

(Claim retirement-lands-green
  "At the change's tip, bb nido:test exits 0 and bb nido:design:check exits 0."
  {:about [platform-jj nido-design] :evidence "round"})
