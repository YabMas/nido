(ns canvas.tasks.nido-migrate
  "Self-spec: `tasks.nido-migrate` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-migrate
  "bb task entry points for `nido-migrate`."
  (Operation migrate-cmd
    "bb nido:migrate :project <p> — migrate legacy run.edn/ticket records into the"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation ledger-cmd
    "bb nido:migrate:ledger :project <p> — one-shot, best-effort copy of each"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
