(ns canvas.bands
  "Nido's high-level design: the bands, and what each may depend on.

   `Band` itself is fukan's — `fukan.common.vocab.code.band`, promoted out of this file once a
   second project wanted it. What lives here is only what is nido's: which strata this codebase
   has, which namespaces each claims, and which of them may reach which.

   Membership is DERIVED from the namespace path rather than authored, which is what the
   2026-08-28 restructure bought: every band is a package under src/nido/, so a namespace's band
   is readable from its name and cannot drift from the tree."
  (:require [fukan.common.vocab.code.band :refer [Band]]))

;; ── the bands, floor first ───────────────────────────────────────────────────

(Band Platform
  "Generic low-level libraries and the nido home. Depends on nothing."
  {:prefix ["nido.platform."]})

(Band Design
  "Reading a project's declared design and checking its code against it.

   A capability band over the floor, like Vsdd: it shells to fukan and reads
   findings back. It is deliberately NOT a floor namespace despite everything
   above eventually wanting it — high fan-in is not what makes something
   generic, and a seam that knows what a `canvas/` is belongs to nido, not to
   its standard library."
  {:prefix ["nido.design."]
   :may-depend [Platform]})

(Band Integration
  "Outbound adapters: Notion, Slack, GitHub, video transcription."
  {:prefix ["nido.notion." "nido.slack." "nido.github." "nido.transcribe."]
   :may-depend [Platform]})

(Band Session
  "Bringing a development session up and down: worktrees, services, the session home."
  {:prefix ["nido.session." "nido.bench."]
   :may-depend [Platform Integration Design]})

(Band Coordination
  "The work plane: workstreams, tickets, runs, sources, the lanes that drive them."
  {:prefix ["nido.coordinator."]
   :may-depend [Platform Integration Session]})

(Band Vsdd
  "Parallel sweep orchestration across dirty modules."
  {:prefix ["nido.vsdd."]
   :may-depend [Platform]})

(Band Review
  "The judgment loops over a record, a design, a diff."
  {:prefix ["nido.review."]
   :may-depend [Platform Session Coordination Vsdd Design]})

(Band Boot
  "The daemon composition root: it wires the other bands together, and is reached
   by nothing but the task that starts it.

   It does NOT declare a dependency on Surface, and that omission is the point. A
   composition root sits above everything, so `Surface :may-depend [Boot]` is the
   direction that holds — a task starting the daemon is what tasks are for. The
   daemon used to start and stop the dashboard HTTP server itself, which made the
   reverse edge real; it now takes a dashboard lifecycle as an argument and the
   task supplies both. The declaration is what forced that."
  {:prefix ["nido.boot."]
   :may-depend [Platform Integration Session Coordination Vsdd Review]})

(Band Surface
  "Every way a human reaches nido: the bb tasks, the TUI, the web dashboard."
  {:prefix ["tasks." "nido.ui."]
   :may-depend [Platform Integration Session Coordination Vsdd Review Boot Design]})
