(ns canvas.session.service
  "Self-spec: `nido.session.service` — the service protocol every session service implements."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [fukan.common.typing.malli]))

(Module session-service
  "Four polymorphic operations and nothing else — the whole contract a session service has to
   satisfy: start it, stop it, say what it is doing, and stop what it left on record when no
   saved state names it.

   Dispatch is on the service's declared `:type`, so adding a kind of service adds its methods
   in its own namespace and changes nothing here. That is what lets a project declare services
   nido has never heard of, and it is why this namespace implements nothing but the no-op
   default of stop-recorded!."
  (Operation start-service!
    "Bring one declared service up, contributing whatever the rest of the session may reference."
    {:signature [:=> [:catn [:service-def :map] [:ctx :map] [:opts :any]] :map]})
  (Operation stop-service!
    "Bring one declared service down, given what starting it saved."
    {:signature [:=> [:catn [:service-def :map] [:saved-state :any]] :any]})
  (Operation service-status
    "What one declared service is doing."
    {:signature [:=> [:catn [:service-def :map] [:saved-state :any]] :map]})
  (Operation stop-recorded!
    "Stop what one declared service left on its own record for an instance when no saved state
     names it — a start that never wrote session.edn. A type keeping no such record does nothing."
    {:signature [:=> [:catn [:service-def :map] [:instance-id :string]] :any]}))

;; `nido.session.services.config-file` and `nido.session.services.process` carry ONLY defmethods
;; of the operations above. They are the protocol's implementations and declare no public
;; function of their own, so there is nothing here to model: a Module for either would be a
;; Module with no operations, asserting a boundary that holds nothing.
;;
;; They show on the adoption frontier for that reason, and correctly — the frontier reports
;; unmodelled code the adopted region reaches, and a namespace of pure method bodies is exactly
;; that. Modelling defmethod BODIES is a fukan question (attribution of a polymorphic operation
;; to its implementations), not a nido one; see the judgement list.
