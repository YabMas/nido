(ns canvas.platform.project
  "Self-spec: `nido.platform.project` — registering a project with nido."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]
            [canvas.platform.core :as core]
            [canvas.platform.config :as config]))

(Kind ProjectName
  "A registered project, by the name it was registered under. A keyword everywhere it is
   addressed and a string on disk, which is why the seam that resolves it takes either.

   Owned HERE because this is the registry: a name is a project's name because this module says
   it is, and nothing else may mint one."
  :keyword)

(Module platform-project
  "Add, remove and look up the projects nido drives."
  {:child [ProjectName]}
  (Operation add!
    "Register a project and create its definitions directory. Refuses a directory that does not
     exist — a registry entry pointing nowhere fails later, further from the cause."
    {:signature [:=> [:catn [:name :string] [:directory :string]] :map]
     :delegates [config/update-projects! core/nido-home core/log-step]})
  (Operation list-projects
    "The projects map."
    {:signature [:=> [:catn] :map]
     :delegates [config/read-projects]})
  (Operation remove!
    "Unregister a project, leaving its definitions on disk. True when it was there."
    {:signature [:=> [:catn [:name :string]] :boolean]
     :delegates [config/read-projects config/update-projects! core/log-step]})
  (Operation get-project
    "One project entry, or nil."
    {:signature [:=> [:catn [:name :string]] [:maybe :map]]
     :delegates [config/read-projects]}))
