(ns canvas.coordinator.record.triggers
  "Self-spec: `nido.coordinator.record.triggers` — what a project may fire, and with what."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :as state]
            [canvas.platform.project :refer [ProjectName]]
            
            [fukan.common.typing.malli]))

(Kind Trigger
  "One thing a project can fire: which source raises it, which skill answers it, the payload
   template that carries the event into the prompt, and the brakes it runs under.

   SHAPELESS here because the schema in the code is the enforced one — a trigger is refused when
   its config is READ, before a session, a worktree and a database have been provisioned for it,
   and a second copy of that shape in the design would be the copy nothing checks.")

(Module record-triggers
  "A project's trigger configuration: load it, find one, fill its payload in.

   Validation happens at the read, not at the launch. A trigger declaring no budget used to pass
   here and be read as infinite at launch time; refusing it when the file is read means the
   failure lands before anything has been spawned."

  {:child [Trigger]}
  (Operation load-for-project
    "Every valid trigger a project declares. Invalid entries are skipped with a warning rather
     than failing the load: one bad trigger should not take the other twelve down with it."
    {:signature [:=> [:catn [:project ProjectName]] [:vector Trigger]]
     :delegates [state/triggers-path]})
  (Operation find-by-name
    "The trigger with this name among ones already loaded, or nil."
    {:signature [:=> [:catn [:triggers [:vector Trigger]] [:name :keyword]] [:maybe Trigger]]})
  (Operation render-payload
    "A payload template with its `{{event/…}}` placeholders filled from the event. A missing
     value renders empty rather than throwing — the prompt is still worth sending."
    {:signature [:=> [:catn [:template :string] [:event :map]] :string]})
  (Operation placeholder-keys
    "The placeholder names a payload template asks for, in order — the fields a fire form has to
     collect. Top-level keys only: a slash-path is not addressable from a form."
    {:signature [:=> [:catn [:payload-template :string]] [:vector :keyword]]}))
