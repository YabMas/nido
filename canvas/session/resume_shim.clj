(ns canvas.session.resume-shim
  "Self-spec: `nido.session.resume-shim`."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.project :refer [ProjectName]]
            [canvas.session.state :as sstate]
            [fukan.common.typing.malli]))


(Module session-resume-shim
  "The shim that lets a Run's session be re-entered: a link back to the run, written into the
   session home so a resume finds its way without resolving anything."
  (Operation write! "Write the shim and the run link into a session home."
    {:signature [:=> [:catn [:session-home Path] [:run-dir Path]] :any]}))
