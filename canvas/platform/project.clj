(ns canvas.platform.project
  "Self-spec: `nido.platform.project` — registering a project with nido."
  (:require [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.vocab.code.module :refer [Module]]
            [canvas.platform.core :as core]
            [canvas.platform.config :as config]))

(Module platform-project
  "Add, remove and look up the projects nido drives."
  (Operation add!
    "Register a project and create its definitions directory. Refuses a directory that does not
     exist — a registry entry pointing nowhere fails later, further from the cause."
    {:signature [:=> [:catn [:name :string] [:directory :string]] :map]
     :delegates [config/read-projects config/write-projects! core/nido-home core/log-step]})
  (Operation list-projects
    "The projects map."
    {:signature [:=> [:catn] :map]
     :delegates [config/read-projects]})
  (Operation remove!
    "Unregister a project, leaving its definitions on disk. True when it was there."
    {:signature [:=> [:catn [:name :string]] :boolean]
     :delegates [config/read-projects config/write-projects! core/log-step]})
  (Operation get-project
    "One project entry, or nil."
    {:signature [:=> [:catn [:name :string]] [:or :map :nil]]
     :delegates [config/read-projects]}))
