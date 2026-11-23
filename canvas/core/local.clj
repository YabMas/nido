(ns canvas.core.local
  "Self-spec: `nido.core.local`, an in-process core for the bb test suite and the surface
   bench's measuring child."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.core.domain :as domain]
            [fukan.common.typing.malli]))

(Module core-local
  "A core held in memory, one per nido home, deciding with the same domain the daemon does and
   answering the requests the daemon's api answers, so a client cannot tell them apart.

   HIDES that nothing is durable. Never installed in a process that serves a person."
  (Operation answer
    "Answer a request as the daemon's api would, against the current home's core."
    {:signature [:=> [:catn [:req :map]] :any]
     :delegates [domain/step]})
  (Operation put!
    "Plant a workstream's state and entry contents directly — history a test needs that no
     command may write any more."
    {:signature [:=> [:catn [:k :string] [:f :any] [:contents [:? [:map-of :int :string]]]] :nil]}))
