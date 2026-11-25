(ns canvas.claims.core-summaries
  "The claims the core-summaries design (workstream ws-20261124-84c150) commits nido to: the core
   keeps a reading of every workstream beside its state, and Needs you reads its gates from
   read-gates. Each Claim's name is the claim's id, its docstring the statement, and its :about the
   elements it is about."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.work :refer [all-gates gate gates read-gates]]
            [canvas.core.client :refer [core-client]]
            [canvas.core.domain :refer [awaiting-person summary]]
            [canvas.core.store :refer [asking core-store]]
            [canvas.ui.surface :refer [derive-screen needs-fragment rail-status-fragment ui-server]]))

(Claim summary-is-a-reading-of-state
  "A workstream's stored summary is always domain/summary of its stored state: core-store writes it in the same transaction that writes the state, and rebuild! and the open-time backfill write it from the stored state; verify reports any workstream whose stored summary differs. A test commits every command the domain admits and finds each summary written equal to domain/summary of the fold of that workstream's events."
  {:about [summary core-store] :evidence "test"})

(Claim asking-is-open-ask-not-closed
  "store/asking answers, for a project, exactly the workstreams whose state holds an open operator ask and is not closed, with their whole states, reading no other workstream's state."
  {:about [asking awaiting-person] :evidence "round"})

(Claim asking-serves-the-cards-in-time
  "One :asking request answers everything a project's core asks' cards show — each open ask's question, who owes the move, the workstream's title — read from the states it returns."
  {:about [asking] :evidence "round"})

(Claim gates-from-the-core
  "all-gates reads each project's core asks with one :asking request and builds each such gate from that workstream's record, its sessions and its open asks; beside them it takes, of the rows this process holds, the card of each whose activity answers :needs-you with no core ask open [needs-you-list-is-the-activity]. It builds and refreshes no row and folds no other workstream's ledger, and a slice's gate is a card of its own."
  {:about [all-gates] :evidence "test"})

(Claim pane-gate-from-the-core
  "gate answers a workstream's gate as read-gates derives it, from one :asking request for its project and the row this process holds for the workstream: the card the Needs you list holds for that workstream, or nil when it holds none."
  {:about [gate] :evidence "test"})

(Claim unread-project-said
  "read-gates answers a GateReading, the boundary where each project's core reply becomes its outcome: a project whose :asking read fails — no core answers, or it answers an error — is a key of :unavailable with the reason and contributes no gates. derive-screen carries :unavailable into the screen, Needs you renders a not-loaded line for each such project, and 'Nothing needs you right now.' renders only when no gate and no unavailable project is left."
  {:about [read-gates derive-screen needs-fragment] :evidence "test"})

(Claim badge-says-unread
  "The rail badge — in a page's chrome, in the needs and workstreams fragments and in the ops panel's patch — shows the count of read-gates' :gates when its :unavailable is empty, and otherwise marks the count as not fully read, never with the empty badge's zero style, so an unread project never shows as nothing needing you."
  {:about [rail-status-fragment read-gates ui-server] :evidence "test"})

(Claim needs-you-without-the-board
  "Needs you, its poll and every page's rail badge take their gates and :unavailable from read-gates alone [unread-project-said], and a pane's question card from gate [pane-gate-from-the-core]; rendering Needs you or its poll makes no list refresh and calls no all-grouped. A response that also draws a board list or a selected pane's rows still waits for those rows: this slice's accepted limit."
  {:about [derive-screen ui-server] :evidence "test"})
