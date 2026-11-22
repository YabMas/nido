(ns canvas.core.provision
  "Self-spec: `nido.core.provision`, part of the core cut-over design (workstream ws-20261122-3274ad). Its
   claims are in canvas.claims.core-cutover."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module core-provision
  "Brings a unit's session up before its first turn, and stops it, as the outcome of a recorded
   provisioning attempt reported back as a keyed command.

   HIDES how a unit's session is brought up and stopped: a bounded call of a session verb that
   brings the working copy and its services up or down through session-lifecycle alone, carrying
   the session into no conductor, seat or agent, and how its result is read back.")
