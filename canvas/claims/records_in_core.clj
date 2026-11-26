(ns canvas.claims.records-in-core
  "The claims the records-into-core design (workstream ws-20261123-0ee6ff) commits nido to: one
   store per workstream, the core's. Each Claim's name is the claim's id, its docstring the
   statement, and its :about the elements it is about."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.record.entry :refer [record-entry]]
            [canvas.coordinator.record.workstream :refer [record-workstream]]
            [canvas.coordinator.work :refer [coordinator-work]]
            [canvas.core.client :refer [core-client]]
            [canvas.core.domain :refer [core-domain]]
            [canvas.core.feed :refer [core-feed]]
            [canvas.core.local :refer [core-local]]
            [canvas.core.store :refer [core-store]]
            [canvas.core.writer :refer [core-writer]]
            [canvas.ui.surface :refer [ui-server ui-tui]]))

(Claim one-store-per-workstream
  "A workstream has one store, the core's: its identity, every work-record entry on its ledger and its coordination state are committed by core-writer from keyed commands core-domain decides, and read back from core-store. Apart from the release's rebuild command and the schema migrations core-store runs each time it is opened, nothing writes a row of the core database but core-writer, and nothing in src reads or writes a workstream's record anywhere else."
  {:about [core-store core-writer record-workstream] :evidence "round"})

(Claim append-after-what-was-read
  "An entry is appended by an :append-entry command naming the newest seq its author read. core-domain numbers the entry one past that seq and refuses the command, changing nothing, when the workstream's ledger has moved since or the workstream has no identity; so no two entries of a ledger share a seq, and an entry is never numbered against a ledger its author did not read."
  {:about [core-domain record-workstream] :evidence "round"})

(Claim checks-before-the-command
  "The cross-record checks an append must pass are made by record-workstream, holding the workstream's lock, against the ledger read from the core, before the :append-entry command is sent; the seq the command names is that read's. core-domain checks only what it can see in the one workstream: an identity, the entry's shape and its seq. The lock serialises nido's own multi-step writers; whatever holds it, the core's refusal of a moved ledger is what keeps a check made against one ledger from being committed onto another."
  {:about [record-workstream core-domain] :evidence "round"})

(Claim entries-immutable-by-seq
  "An entry's content is carried by the :entry-appended event that appends it and kept by core-store under its workstream and seq in the same transaction; no command rewrites or removes an entry. A workstream's state holds only its ledger's index rows, so the feed carries no entry content, and a reader asks the core for content by seq — which, an entry never changing, it may keep for as long as it runs."
  {:about [core-store core-domain core-feed record-entry] :evidence "round"})

(Claim identity-in-the-core
  "A workstream's identity — project, creation time, external refs, intake payload, facets and generated title — is created by a :create-workstream command, refused when the core already holds the workstream; changed only by :revise-identity, and then only in its refs, intake, facets and title; and ended by :discard-workstream, refused while it has an open unit or an active turn — a merged or retracted unit and an ended turn do not hold it. read-ws answers the identity and index the core holds, and nil for a workstream it holds none for or has discarded."
  {:about [core-domain record-workstream] :evidence "round"})

(Claim record-reaches-core-through-client
  "The Record band reaches the core only through core-client, over the daemon's socket. A process whose daemon does not answer is told so by an error naming it, never served from files or from a second store; the processes that answer from an in-process core, over the same core-domain, are the bb test suite and the surface bench's measuring child, each over a nido home of its own that no person's process serves. The in-process core opens no database, so core-client and the Record band load under bb; the measuring child's core holds, in its own memory, the project's workstreams its parent read from the live core through core-client, planted as committed, and the bench's synthetic workstreams, planted as commands. The work plane's core client, nido.coordinator.work.core, takes its workstream key, the daemon's socket and every request from core-client, and in a process whose core-client answers in-process reads what it holds from core-client rather than from a feed; so which core answers is decided in core-client alone."
  {:about [core-client core-local record-workstream coordinator-work] :evidence "round"})

(Claim records-read-from-core
  "The dashboard, the TUI and nido:workstream:show render a workstream's identity and work records from the core: its record from read-ws, an entry's content through record-entry. A project's sweep reads every workstream's state of the project in one request; an entry's content is asked once per ledger by the process that first reads it and kept for as long as it runs, so only a process's first reading of a ledger, or a ledger that moved since, costs a content request. The board's held rows are keyed on the ledger's stamp as the core holds it [row-follows-own-stamp], and which workstreams it holds rows for on the project's one read of the core, project-records [refresh-paths]."
  {:about [coordinator-work ui-server ui-tui record-entry] :evidence "round"})
