(ns canvas.tasks.nido-workstream
  "Self-spec: `tasks.nido-workstream` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-workstream
  "bb-task entry points for the workstream ledger: append an entry, advance the"
  (Operation entry-add*
    "Append one entry. The body comes from :file when given, else :content — the"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation stage-advance*
    "The `stage-advance*` entry point."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation close*
    "The `close*` entry point."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation ref-add*
    "Stamp an external ref. For :adapter github this also files the :pr-opened"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation show*
    "The `show*` entry point."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation entry-add
    "bb nido:workstream:entry:add :project <p> (:ws-id <id> | :ref BR-####)"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation stage-advance
    "The `stage-advance` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation close-cmd
    "The `close-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation ref-add
    "The `ref-add` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation show-cmd
    "The `show-cmd` entry point."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation landed*
    "Record that an approved proposal is now in the tree."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation landed-cmd
    "bb nido:improvement:landed — the entry point for `landed*`."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation backfill-landings*
    "One-shot: discharge every approval whose note already records the outcome."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation backfill-landings-cmd
    "bb nido:improvement:backfill-landings — the entry point for `backfill-landings*`."
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
