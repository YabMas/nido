(ns canvas.bands
  "Nido's high-level design: the bands, and what each may depend on.

   A Band is a stratum of the codebase. Membership is DERIVED from the namespace
   path rather than authored, which is what the 2026-08-28 restructure bought:
   every band is a package under src/nido/, so a namespace's band is readable
   from its name and cannot drift from the tree.

   This is deliberately NOT fukan's `Subsystem`. Subsystem checks a declared
   :may-depend DAG against `module-depends`, which is built from authored
   `:delegates` — it says nothing until a region is modelled operation by
   operation. A Band checks the same shape of declaration against `ns-depends`,
   the EXTRACTED call graph at namespace altitude, which needs no authoring at
   all. Same declaration, different evidence."
  (:require [fukan.canvas.core.structure :as s :refer [defstructure]]
            [fukan.common.extraction.clojure.module :refer [Ns]]))

(defn ^:export read-prefix
  "A bare string in a :prefix vector -> NsPrefix clauses, so a band authors its
   prefixes as plain strings rather than as constructor calls."
  [v]
  [(list 'value v)])

(defstructure ^:value NsPrefix
  "One namespace prefix a Band claims. A `^:value` structure because fukan has no
   plural scalar slot -- a leaf that repeats is a content-deduped node."
  {:value :string}
  (reader read-prefix))

(defstructure Band
  "A stratum of the codebase: the namespaces under its `:prefix`es, plus the
   bands it is allowed to depend on. The laws are the SLOT SEMANTICS of
   `:may-depend` — without them the declaration is prose."
  {:prefix     [:+ NsPrefix]  ; namespace prefixes whose members this band claims
   :may-depend [:* Band]}     ; the bands it may depend on (declared intent)

  (law "every cross-band namespace dependency follows a declared :may-depend edge"
    ;; The offender is the whole EDGE, plus the two bands it crosses. A law that named only
    ;; ?a would report that nido.review.loop is in the wrong without saying which of its
    ;; requires is the wrong one — true, and useless to whoever has to act on it. All four
    ;; vars are bound in the body already; carrying them costs nothing and is the difference
    ;; between a finding an agent can fix and one it has to investigate.
    {:scope :global
     :offenders [?a ?b ?s ?t]
     :rules [[(declared-dep ?s ?t) (is ?s ::Band) (may-depend ?s ?t)]]
     :where [(ns-depends ?a ?b)
             (in-band ?a ?s) (in-band ?b ?t) [(not= ?s ?t)]
             (not (declared-dep ?s ?t))]})

  (law "the :may-depend graph is acyclic — no band transitively depends on itself"
    {:offenders [?s]
     :rules [[(band-reaches ?s ?t) (may-depend ?s ?t)]
             [(band-reaches ?s ?t) (may-depend ?s ?mid) (band-reaches ?mid ?t)]]
     :where [(band-reaches ?s ?s)]}))

(s/defrelation :in-band
  "Code namespace ?ns belongs to Band ?b — DERIVED from the namespace path: ?ns's
   name starts with one of ?b's declared prefixes. No membership is authored."
  [?ns ?b]
  [(is ?b ::Band) (prefix ?b ?px) [?px :val/value ?p]
   (is ?ns Ns) (named ?ns ?n)
   [(clojure.string/starts-with? ?n ?p)]])

;; ── the bands, floor first ───────────────────────────────────────────────────

(Band Platform
  "Generic low-level libraries and the nido home. Depends on nothing."
  {:prefix ["nido.platform."]})

(Band Integration
  "Outbound adapters: Notion, Slack, GitHub, video transcription."
  {:prefix ["nido.notion." "nido.slack." "nido.github." "nido.transcribe."]
   :may-depend [Platform]})

(Band Session
  "Bringing a development session up and down: worktrees, services, the session home."
  {:prefix ["nido.session." "nido.bench."]
   :may-depend [Platform Integration]})

(Band Coordination
  "The work plane: workstreams, tickets, runs, sources, the lanes that drive them."
  {:prefix ["nido.coordinator."]
   :may-depend [Platform Integration Session]})

(Band Vsdd
  "Parallel sweep orchestration across dirty modules."
  {:prefix ["nido.vsdd."]
   :may-depend [Platform]})

(Band Design
  "Reading a project's declared design and checking its code against it.

   A capability band over the floor, like Vsdd: it shells to fukan and reads
   findings back. It is deliberately NOT a floor namespace despite everything
   above eventually wanting it — high fan-in is not what makes something
   generic, and a seam that knows what a `canvas/` is belongs to nido, not to
   its standard library."
  {:prefix ["nido.design."]
   :may-depend [Platform]})

(Band Review
  "The judgment loops over a record, a design, a diff."
  {:prefix ["nido.review."]
   :may-depend [Platform Session Coordination Vsdd]})

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
