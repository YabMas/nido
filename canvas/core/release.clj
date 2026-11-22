(ns canvas.core.release
  "Self-spec: `nido.core.release`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]))

(Module core-release
  "How the daemon starts, stops and changes version: starting is recovery, a kill is
   a stop, and a new release goes live by a restart that must pass
   a health gate before taking the command socket. Turns in flight outlive a stop
   and are taken back by the daemon that starts next.

   HIDES the release pointer, the health gate and rollback to the previous release.")
