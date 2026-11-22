(ns canvas.core.release
  "Self-spec: `nido.core.release`, part of the core cut-over design (workstream ws-20261122-3274ad). Its
   claims are in canvas.claims.core-cutover."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module core-release
  "The core daemon's composition root: store, writer, feed, api, turn runner, conductor rules and
   provisioner assembled into one supervised JVM process, started, health-gated and upgraded.

   HIDES how the daemon is assembled from its modules, what its health gate checks, and how an
   upgrade hands the command socket from one release to the next.")
