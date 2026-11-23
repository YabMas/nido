(ns canvas.core.store
  "Self-spec: `nido.core.store`, a module of the target coordination core (the
   nido-archetecture-review design). Its claims are in canvas.claims.core-target."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Module core-store
  "Where committed state lives: the ledger of events and the state rows derived from
   it, held in one SQLite database in WAL mode with synchronous=FULL.

   HIDES the schema, the transaction boundary and the durability settings. A batch's events and the
   rows they produce commit as one transaction, so stored state is always the fold
   of the ledger and can be rebuilt from it; the rows are a declared
   cache over the ledger, not a second truth. A workstream's work-record entries are among them:
   an entry's content is carried by the event that appends it and kept by seq in the same
   transaction, so state carries only the ledger's index and a reader asks for content by seq."
  (Operation open
    "Open the store for writing, creating it and its schema if absent; one writer holds it."
    {:signature [:=> [:catn [:path :string]] :map]})
  (Operation open-reader
    "Open the store for reading only; under WAL it never waits on the writer."
    {:signature [:=> [:catn [:path :string]] :map]})
  (Operation close
    "Close a store."
    {:signature [:=> [:catn [:store :map]] :nil]})
  (Operation commit!
    "Make a batch of decided commands durable as one transaction, events and derived state together, and answer each entry's last event position; a batch a constraint refuses writes nothing."
    {:signature [:=> [:catn [:store :map] [:batch [:sequential :map]]] [:vector [:maybe :int]]]})
  (Operation changed-since
    "Every workstream whose last commit is after a position, at that commit, in commit order."
    {:signature [:=> [:catn [:store :map] [:pos :int]] [:sequential :map]]})
  (Operation command-outcome
    "The outcome a command key was answered with, or nil if none committed."
    {:signature [:=> [:catn [:store :map] [:key :string]] [:maybe :any]]})
  (Operation state
    "A workstream's committed state."
    {:signature [:=> [:catn [:store :map] [:ws :string]] :map]})
  (Operation workstreams
    "The ids of every workstream with committed events."
    {:signature [:=> [:catn [:store :map]] [:sequential :string]]})
  (Operation events
    "A workstream's events in commit order."
    {:signature [:=> [:catn [:store :map] [:ws :string]] [:sequential :map]]})
  (Operation position
    "The position of the last committed event."
    {:signature [:=> [:catn [:store :map]] :int]})
  (Operation verify
    "Every way the stored state disagrees with the fold of the ledger; reads all of history, so only on request."
    {:signature [:=> [:catn [:store :map]] [:sequential :map]]})
  (Operation rebuild!
    "Recompute every stored state and its rows from the ledger, as a release that changes the fold does."
    {:signature [:=> [:catn [:store :map]] :int]})
  (Operation states
    "The committed state of every workstream whose key starts with a prefix, by key — one read for a sweep of a project."
    {:signature [:=> [:catn [:store :map] [:prefix :string]] [:map-of :string :map]]})
  (Operation entry-contents
    "The content of each named entry of a workstream's ledger, by seq; an entry is kept once, by the commit that appends it, and never rewritten."
    {:signature [:=> [:catn [:store :map] [:ws :string] [:seqs [:sequential :int]]] [:map-of :int :string]]}))
