(ns canvas.coordinator.source.review-queue-nightly
  "Self-spec: `nido.coordinator.source.review-queue-nightly` — the :review-queue-nightly source
   plugin."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.source.core :as source]
            [fukan.common.typing.malli]))

(Module source-review-queue-nightly
  "The :review-queue-nightly source: a project's Review queue groomed with nobody deciding. Its
   first poll at or after :at each local day queues one plan run; a later poll approves every
   item of that plan nobody decided and queues its apply.

   Both fires are KEYED, ADDRESSED envelopes rather than broadcasts. A broadcast re-emitted after
   its envelope drained queues a second run, and the key is what makes once-a-day and once-a-plan
   a property of the write instead of a property of this source's own state. The apply's key is
   the one the page's Apply uses, so the page and this source cannot both queue one plan.

   It decides only plan runs it fired itself. A grooming a person started is theirs to decide."
  (Operation due-day
    "The local calendar day a nightly grooming is due for at an instant, or nil before :at that
     day."
    {:signature [:=> [:catn [:source-config :map] [:now :string]] [:maybe :string]]})
  (Operation poll-once!
    "One poll: queue today's plan run when it is due; a poll that queued nothing decides and queues
     the apply of every nightly plan that is written and whose apply is not recorded. Returns the
     state to persist."
    {:signature [:=> [:catn [:source-config :map]] :map]
     :delegates [due-day source/enqueue-keyed!]})
  (Operation start-instance!
    "Start one configured instance: its poll writes what poll-once! returns as the source's state."
    {:signature [:=> [:catn [:source-config :map] [:emit-fn :any]] :map]
     :delegates [poll-once! source/config-hash source/write-state!]})
  (Operation register! "Register this plugin with the source registry, at load time."
    {:signature [:=> [:catn] :any]
     :delegates [source/register-source! start-instance!]}))
