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
  (Operation write-projects!
    "Replace the projects map."
    {:signature [:=> [:catn [:projects :map]] :any]
     :delegates [projects-file io/write-edn!]}))
