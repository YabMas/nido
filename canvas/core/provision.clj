(ns canvas.core.provision
  "Self-spec: `nido.core.provision`, part of the core cut-over design (workstream ws-20261122-3274ad). Its
   claims are in canvas.claims.core-cutover."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-provision
  "Brings a unit's session up before its first turn, and stops it, as the outcome of a recorded
   provisioning attempt reported back as a keyed command.

   HIDES how a unit's session is brought up and stopped: a bounded call of a session verb that
   brings the working copy and its services up or down through session-lifecycle alone, carrying
   the session into no conductor, seat or agent, and how its result is read back."
  (Operation start
    "Start the provisioner. A start hands it every workstream not closed, which is how an attempt a
     kill left is made again."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation changed!
    "Hand the provisioner a commit's changes. Never blocks."
    {:signature [:=> [:catn [:provisioner :map] [:changes [:sequential :map]]] :nil]})
  (Operation session-name
    "The session an attempt works on: its working copy's, else its unit's name."
    {:signature [:=> [:catn [:attempt :map]] :string]})
  (Operation verb-argv
    "The argv of the session verb for an attempt."
    {:signature [:=> [:catn [:opts :map] [:attempt :map]] [:sequential :string]]})
  (Operation parse-result
    "The verb's result: the last line of its output that reads as an EDN map, or nil."
    {:signature [:=> [:catn [:out :string]] [:maybe :map]]})
  (Operation outcome-command
    "The keyed command recording an attempt's outcome, from the verb's run."
    {:signature [:=> [:catn [:attempt :map] [:run :map]] :map]}))
