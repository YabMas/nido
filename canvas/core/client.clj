(ns canvas.core.client
  "Self-spec: `nido.core.client`, how a process outside the daemon reaches the core."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.core.api :as api]
            [canvas.core.local :as local]
            [fukan.common.typing.malli]))

(Module core-client
  "One request at a time to the core, addressed by a workstream key \"<project>/<ws-id>\".

   HIDES which core answers: the daemon over its socket, or — for the bb test suite and the surface
   bench's measuring child only, each over a nido home of its own — an in-process core over the
   same domain. A process that has neither gets an error naming the
   daemon, never a quiet second store; there is no fallback to files."
  (Operation ws-key
    "The core's name for a workstream of a project."
    {:signature [:=> [:catn [:project :any] [:ws-id :string]] :string]})
  (Operation split-key
    "The project and workstream id of a core key, or nil."
    {:signature [:=> [:catn [:k :string]] [:maybe [:tuple :string :string]]]})
  (Operation socket
    "The daemon's command socket."
    {:signature [:=> [:catn] :string]})
  (Operation use-local!
    "Answer every request of this process from an in-process core. For the test suite and the surface
     bench's measuring child only."
    {:signature [:=> [:catn] :nil]})
  (Operation local?
    "Whether this process speaks to an in-process core."
    {:signature [:=> [:catn] :boolean]})
  (Operation request!
    "Send a request to the core and answer its reply; throws when the daemon does not answer."
    {:signature [:=> [:catn [:req :map]] :any]
     :delegates [api/connect api/request local/answer]})
  (Operation state
    "A workstream's committed state."
    {:signature [:=> [:catn [:k :string]] :map]
     :delegates [request!]})
  (Operation states
    "The committed state of every workstream whose key starts with a prefix."
    {:signature [:=> [:catn [:prefix :string]] [:map-of :string :map]]
     :delegates [request!]})
  (Operation entry-contents
    "The content of named entries of a workstream's ledger, by seq."
    {:signature [:=> [:catn [:k :string] [:seqs [:sequential :int]]] [:map-of :int :string]]
     :delegates [request!]})
  (Operation command!
    "Send a keyed command and answer its committed outcome; a lost reply is asked for again under
     the same key, which answers what was committed rather than deciding twice."
    {:signature [:=> [:catn [:k :string] [:key :string] [:command :map]] :map]
     :delegates [request!]}))
