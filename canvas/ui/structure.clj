(ns canvas.ui.structure
  "Self-spec: `nido.ui.structure` — the Structure slice's explorer, on `main`."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.design.check :as check]
            [canvas.design.explorer :as explorer]
            [canvas.platform.project :as project]
            [canvas.vsdd.core :as jj]
            [fukan.common.typing.malli]))

(Module ui-structure
  "A project's declared design as it stands on `main`, drawn by fukan's explorer and reached
   through the dashboard.

   HIDES that there is one explorer per project pinned to the commit `main` named when it started,
   that a visit to the slice is what notices `main` moved and replaces it, and that the browser
   reaches it only through a forward under the project's prefix — one origin, and no explorer
   port ever leaves the dashboard."
  (Operation ensure!
    "The explorer over a project's declaration on `main`, started or replaced as `main` requires;
     unmodelled when `main` declares nothing, and saying so when there is no `main`."
    {:signature [:=> [:catn [:project :string]] :map]
     :delegates [project/get-project check/spec-dirs jj/jj! jj/materialize-spec-dirs!
                 explorer/start! explorer/address explorer/stop!]})
  (Operation forward
    "Answer a request under a project's explorer with what the explorer answers, or a notice while
     it starts or after it failed. Never starts one."
    {:signature [:=> [:catn [:project :string] [:path :string] [:query-string [:maybe :string]]] :map]
     :delegates [explorer/address]}))
