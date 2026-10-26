(ns canvas.session.run-link
  "Self-spec: `nido.session.run-link`."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [fukan.common.typing.malli]))


(Module session-run-link
  "The link from a Run-owned session's home back to its Run, written into the session home so the
   session's skills, its briefing and a restart reach the Run without resolving anything."
  (Operation write! "Point a session home's run link at its Run, removing the claude shim an older home carried."
    {:signature [:=> [:catn [:session-home Path] [:run-dir Path]] :any]})
  (Operation run-dir
    "The Run directory a session home is linked to, or nil when it has none — what a restart
     carries across the stop that removes the home."
    {:signature [:=> [:catn [:session-home Path]] [:maybe :string]]}))
