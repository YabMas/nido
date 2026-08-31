(ns canvas.platform.config
  "Self-spec: `nido.platform.config` — the projects registry, as one file."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]
            [canvas.platform.core :as core]
            [canvas.platform.io :as io]))

(Module platform-config
  "Read and write ~/.nido/projects.edn — the registry every band resolves a project through."
  (Operation projects-file
    "Path to the projects registry."
    {:signature [:=> [:catn] :string]
     :delegates [core/nido-home]})
  (Operation read-projects
    "The projects map, empty when the registry does not exist yet."
    {:signature [:=> [:catn] :map]
     :delegates [projects-file io/read-edn]})
  (Operation update-projects!
    "Apply a function to the projects map and write the result, as one locked operation. How
     every mutation of the registry goes: a read and a write of it are never two separate steps
     a slow or abandoned process can be interrupted between."
    {:signature [:=> [:catn [:f [:=> [:cat :any] :any]]] :any]
     :delegates [projects-file io/update-edn!]}))
