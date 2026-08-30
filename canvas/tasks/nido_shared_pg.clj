(ns canvas.tasks.nido-shared-pg
  "Self-spec: `tasks.nido-shared-pg` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-shared-pg
  "bb task entry points for `nido-shared-pg`."
  (Operation project-source-dir
    "The project's registered source checkout (its jj root)."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation up
    "Ensure the shared Postgres cluster for a project is up (seed+start), advance"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation status
    "Show shared cluster status for a project."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation down
    "Stop the shared cluster (preserves data)."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation reset
    "Stop, drop PGDATA, re-clone from template, start — recover from a bad"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation destroy
    "Delete the shared cluster for a project."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
