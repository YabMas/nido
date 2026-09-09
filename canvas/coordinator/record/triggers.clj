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
    "A payload template with its `{{event/…}}` placeholders filled from the event. A placeholder
     the event does not fill renders as `?` rather than throwing — the prompt is still worth
     sending, and a blank could not be told apart from a value that is genuinely empty."
    {:signature [:=> [:catn [:template :string] [:event :map]] :string]})
  (Operation payload-problems
    "One sentence for each way a trigger's payload template and the contract the framework states
     for it disagree, given the event about to be rendered into it. Empty when they agree.

     The template is the one part of a trigger nothing else can hold to anything: it is read from
     the project's triggers.edn, outside any repo, and names payload keys that ship with the code,
     so the two can never land together. Reports rather than refuses — both faults it can find
     cost the message a number or a word, not the run."
    {:signature [:=> [:catn [:trigger Trigger] [:event :map]] [:vector :string]]})
  (Operation placeholder-keys
    "The placeholder names a payload template asks for, in order — the fields a fire form has to
     collect. Top-level keys only: a slash-path is not addressable from a form."
    {:signature [:=> [:catn [:payload-template :string]] [:vector :keyword]]}))
