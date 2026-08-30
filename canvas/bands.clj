(ns canvas.bands
  "Nido's high-level design: the bands, and what each may depend on.

   `Band` itself is fukan's — `fukan.common.vocab.code.band`, promoted out of this file once a
   second project wanted it. What lives here is only what is nido's: which strata this codebase
   has, which namespaces each claims, and which of them may reach which.

   Membership is DERIVED from the namespace path rather than authored, which is what the
   2026-05-28 restructure bought: every band is a package under src/nido/, so a namespace's band
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

(Band Vsdd
  "Parallel sweep orchestration across dirty modules."
  {:prefix ["nido.vsdd."]
   :may-depend [Platform]})

;; ── the work plane, floor first ──────────────────────────────────────────────
;;
;; The six strata that were one `Coordination` band until 2026-08-30. Fifty-two namespaces and
;; 157 dependencies between them, about which a single band could say nothing: everything was
;; permitted to reach everything, and the only claim on record was that the region existed.
;;
;; They are SIBLINGS rather than sub-bands of a retained `Coordination`, and that is forced, not
;; stylistic. A band's membership is every namespace under its prefix, so a child's members are
;; also the parent's; a child's own internal edge then demands `Child :may-depend [Parent]` while
;; the parent reaching its children demands the reverse, and the acyclicity law refuses the pair.
;; Nesting can seal a LEAF sub-region. It cannot partition a band — the parent has to dissolve.
;;
;; Every `:may-depend` below is the reach the code actually has, measured, with nothing added as
;; headroom: a declared edge no call realizes is over-declaration, and the point of writing this
;; down is that the next edge has to be a decision rather than an accident.

(Band Record
  "The durable records the work plane is about: workstreams, tickets, runs, sessions, and the
   filesystem paths and clock they are read and written through. Depends on no other stratum —
   everything else depends on it."
  {:prefix ["nido.coordinator.record."]
   :may-depend [Platform Session]})

(Band Source
  "Where work arrives from: the source plugin registry, the Notion/Slack pollers, the manual
   envelope queue, and the routing and filtering that turns an arrival into a fire."
  {:prefix ["nido.coordinator.source."]
   :may-depend [Platform Integration Record]})

(Band Daemon
  "The running coordinator: its scheduler, agent launcher, pid and heartbeat files, and the
   brakes — circuit breakers, halt flag, anomaly detection, startup reconciliation."
  {:prefix ["nido.coordinator.daemon."]
   :may-depend [Platform Integration Record]})

(Band View
  "Pure read models over the records, for the surfaces to render. No writes."
  {:prefix ["nido.coordinator.view."]
   :may-depend [Platform Record Source Daemon]})

(Band Lane
  "The verbs: pickup, promote, spawn, drive, ship, resume, intake, and the housekeeping
   reconciliations. Each lane advances a workstream from one state to the next."
  {:prefix ["nido.coordinator.lane."]
   :may-depend [Platform Integration Session Record Source Daemon View]})

(Band WorkPlane
  "`nido.coordinator.work` — the single vocabulary every surface is meant to wrap.

   Its own prefix, and its own band, because being separately addressable is the whole point: a
   surface that may depend on WorkPlane and not on the strata beneath it is a facade with teeth.
   That is NOT yet declared — 110 of the 117 edges into the work plane currently go around this
   namespace — so the seal is a decision still to be made, not a claim this file makes."
  {:prefix ["nido.coordinator.work"]
   :may-depend [Platform Integration Session Record Source View Lane]})

;; ── the bands above the work plane ───────────────────────────────────────────

(Band Review
  "The judgment loops over a record, a design, a diff."
  {:prefix ["nido.review."]
   :may-depend [Platform Session Vsdd Design Record Source Daemon]})

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
   :may-depend [Platform Integration Session Vsdd Review
                Record Source Daemon Lane WorkPlane]})

(Band Surface
  "Every way a human reaches nido: the bb tasks, the TUI, the web dashboard."
  {:prefix ["tasks." "nido.ui."]
   :may-depend [Platform Integration Session Vsdd Review Boot Design
                Record Source Daemon View Lane WorkPlane]})
