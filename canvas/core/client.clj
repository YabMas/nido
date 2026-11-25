(ns canvas.core.client
  "Self-spec: `nido.core.client`, how a process outside the daemon reaches the core."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.core.api :as api]
            [canvas.core.local :as local]
            [fukan.common.typing.malli]))

(Kind CoreRequest
  "A request the core answers: a keyed command, or a read of one state, the states under a key
   prefix, entry contents by seq, a project's summaries, the states of a project's workstreams a
   person owes a move, a project's workstreams in one read, or the workstreams held."
  [:multi {:dispatch :op}
   [:command     [:map [:op [:= :command]] [:key :string] [:ws :string] [:command :map]
                  [:input? {:optional true} :boolean]]]
   [:state       [:map [:op [:= :state]] [:ws :string]]]
   [:states      [:map [:op [:= :states]] [:prefix :string]]]
   [:entries     [:map [:op [:= :entries]] [:ws :string] [:seqs [:vector :int]]]]
   [:summaries   [:map [:op [:= :summaries]] [:project :string]]]
   [:asking      [:map [:op [:= :asking]] [:project :string]]]
   [:project-states [:map [:op [:= :project-states]] [:project :string]
                     [:open? {:optional true} :boolean]
                     [:summaries? {:optional true} :boolean]
                     [:newest-of {:optional true} [:vector :keyword]]]]
   [:workstreams [:map [:op [:= :workstreams]]]]])

(Kind Unavailable
  "No core answered: the daemon at the socket named is not running, or did not reply. Answered as
   data, never thrown."
  [:map [:unavailable [:map [:socket :string] [:message :string]]]])

(Kind CommandOutcome
  "What a keyed command came to: committed (its events), refused, undecided, or Unavailable."
  [:or [:map [:events [:vector :map]]]
       [:map [:refused :map]]
       [:map [:error :any]]
       Unavailable])

(Kind ReadReply
  "A read's reply — a state, the states by key, entry contents by seq, a stream's records, the
   workstreams held, an error — or Unavailable."
  [:or [:map [:state :map]]
       [:map [:states [:map-of :string :map]]]
       [:map [:entries [:map-of :int :string]]]
       [:map [:summaries [:map-of :string :map]]]
       [:map [:asking [:map-of :string :map]]]
       [:map [:records [:vector :map]]]
       [:map [:states [:map-of :string :map]]
        [:summaries {:optional true} [:map-of :string :map]]
        [:contents {:optional true} [:map-of :string [:map-of :int :string]]]]
       [:map [:workstreams [:vector :string]]]
       [:map [:error :any]]
       Unavailable])

(Module core-client
  "One request at a time to the core, addressed by a workstream key \"<project>/<ws-id>\".

   HIDES which core answers: the daemon over its socket, or — for the bb test suite and the surface
   bench's measuring child only, each over a nido home of its own — an in-process core over the
   same domain — and retrying a lost reply under the same key. Every request is a CoreRequest and
   every answer data: a CommandOutcome or a ReadReply, Unavailable naming the daemon when no core
   answered — never thrown, never a quiet second store; there is no fallback to files. The daemon
   answers only a person's own process: never one whose nido home it does not serve, nor a request
   made while a test runs — those the in-process core answers, so no test reaches the person's core."
  {:child [CoreRequest Unavailable CommandOutcome ReadReply]}
  (Operation ws-key
    "The core's name for a workstream of a project."
    {:signature [:=> [:catn [:project :any] [:ws-id :string]] :string]})
  (Operation run-key
    "The core's name for a run of a project: `run:<project>/<run-id>`, filed under no project's
     workstreams."
    {:signature [:=> [:catn [:project :any] [:run-id :string]] :string]})
  (Operation run-key?
    "Whether a core key names a run rather than a workstream."
    {:signature [:=> [:catn [:k :any]] :boolean]})
  (Operation journal-key
    "The core's name for a project's named journal, which no read of its workstreams or runs
     returns."
    {:signature [:=> [:catn [:project :any] [:jname :string]] :string]})
  (Operation workstream-key?
    "Whether a core key names a workstream — neither a run nor a journal."
    {:signature [:=> [:catn [:k :any]] :boolean]
     :delegates [run-key?]})
  (Operation split-key
    "The project and workstream id of a core key, or nil — a run's or a journal's key included."
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
    "Send a request to the core and answer its reply, or Unavailable when the daemon does not answer."
    {:signature [:=> [:catn [:req CoreRequest]] [:or CommandOutcome ReadReply]]
     :delegates [api/connect api/request local/answer]})
  (Operation state
    "A workstream's committed state, as a ReadReply."
    {:signature [:=> [:catn [:k :string]] ReadReply]
     :delegates [request!]})
  (Operation states
    "The committed state of every workstream whose key starts with a prefix, as a ReadReply."
    {:signature [:=> [:catn [:prefix :string]] ReadReply]
     :delegates [request!]})
  (Operation summaries
    "The summary of every workstream of a project, by key, as a ReadReply."
    {:signature [:=> [:catn [:project :any]] ReadReply]
     :delegates [request!]})
  (Operation runs
    "The summary of every run of a project, by run id, as a ReadReply."
    {:signature [:=> [:catn [:project :any]] ReadReply]
     :delegates [run-key request!]})
  (Operation records
    "The records of a run or a journal, in append order, each with its seq, as a ReadReply."
    {:signature [:=> [:catn [:k :string]] ReadReply]
     :delegates [state]})
  (Operation append-record!
    "Append one record ({:kind k …}) to a run or a journal, naming how many of its records the
     author read: a later of two racing appends is refused stale. Opens a journal on its first."
    {:signature [:=> [:catn [:k :string] [:key :string] [:record :map] [:after :int]] CommandOutcome]
     :delegates [command!]})
  (Operation asking
    "The committed state of every workstream of a project a person owes a move now — an open ask
     on a workstream not closed — by key, as a ReadReply."
    {:signature [:=> [:catn [:project :any]] ReadReply]
     :delegates [request!]})
  (Operation project-states
    "Every workstream of a project in one request — states, optionally their summaries, optionally
     only the open ones, optionally the content of each one's newest entry of named kinds — as a
     ReadReply."
    {:signature [:=> [:catn [:project :any] [:opts :map]] ReadReply]
     :delegates [request!]})
  (Operation entry-contents
    "The content of named entries of a workstream's ledger, by seq, as a ReadReply."
    {:signature [:=> [:catn [:k :string] [:seqs [:sequential :int]]] ReadReply]
     :delegates [request!]})
  (Operation command!
    "Send a keyed command and answer its committed outcome; a lost reply is asked for again under
     the same key, which answers what was committed rather than deciding twice."
    {:signature [:=> [:catn [:k :string] [:key :string] [:command :map]] CommandOutcome]
     :delegates [request!]})
  (Operation unavailable?
    "Whether an answer says no core answered."
    {:signature [:=> [:catn [:answer :any]] :boolean]})
  (Operation unavailable
    "The Unavailable answer naming the daemon at a socket."
    {:signature [:=> [:catn [:socket :string]] Unavailable]})
  (Operation value!
    "The value under a key of a read's reply, for a caller that cannot go on without it: an
     Unavailable reply is raised as an error naming the daemon. The caller's conversion, not an
     answer of the core's."
    {:signature [:=> [:catn [:reply :map] [:k :keyword]] :any]
     :delegates [unavailable?]}))
