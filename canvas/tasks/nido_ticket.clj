(ns canvas.tasks.nido-ticket
  "Self-spec: `tasks.nido-ticket` — a bb task entry point.

   A COMPOSITION ROOT for one CLI verb: parse the arguments, call the domain, print, exit. It
   reaches widely and declares that it does, which is why Tasks is its own band rather than part
   of Surface — holding a hundred of these to a facade would push forwarding functions into the
   work plane and make it shallow."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module nido-ticket
  "bb task entry points for the per-ticket triage record (the skill's interface)."
  (Operation exit!
    "Redefable wrapper around System/exit (matches the exit-test convention used"
    {:signature [:=> [:catn [:opts [:* :any]]] :any]})
  (Operation open-cmd
    "bb nido:ticket:open :project <p> :br BR-#### :page <id> :url <u> :title <t> :opened-by <kw>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation status-cmd
    "bb nido:ticket:status :project <p> :br BR-#### :status <kw>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation complete-cmd
    "bb nido:ticket:complete :project <p> :br BR-#### :status triaged :disposition <kw>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation dismiss-cmd
    "bb nido:ticket:dismiss :project <p> :br BR-#### (or positional BR-####)"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation append-cmd
    "bb nido:ticket:append :project <p> :br BR-#### :kind <kw> :session <s> :run-id <r> :file <path>"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation report-cmd
    "bb nido:ticket:report :project <p> :br <key> — print the latest triage report as"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation show-cmd
    "bb nido:ticket:show :project <p> :br BR-#### — pretty-print meta.edn."
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation list-cmd
    "bb nido:tickets:list [:status <kw>]"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation apply-cmd
    "bb nido:ticket:apply :project <p> :br BR-#### — execute a parked triage's verdict via"
    {:signature [:=> [:catn [:args [:* :any]]] :any]})
  (Operation promote-cmd
    "bb nido:ticket:promote :project <p> :br BR-#### (or positional BR-####)"
    {:signature [:=> [:catn [:args [:* :any]]] :any]}))
