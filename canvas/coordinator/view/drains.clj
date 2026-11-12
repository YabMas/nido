(ns canvas.coordinator.view.drains
  "Self-spec: `nido.coordinator.view.drains` — the drains surface, read-only."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module view-drains
  "Each open drain as the /drains page shows it: phase, recipe version, newest census and its
   trend, slices by state, open escalations and misfits. Pure read model — NO WRITES.")
