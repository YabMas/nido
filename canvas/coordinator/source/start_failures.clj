(ns canvas.coordinator.source.start-failures
  "Self-spec: `nido.coordinator.source.start-failures` — a source plugin.

   Named for what it polls rather than for the session substrate it reads: a module named
   `source-session-failure` would suffix-match `nido.session.failure` as well as this namespace,
   and a module pairing two namespaces is ambiguous."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.source.core :as source]
            [canvas.platform.project :refer [ProjectName]]
            [fukan.common.typing.malli]))

(Module source-start-failures
  "The `:session-failure` source: kept session-start failures nothing has recovered yet, one
   arrival per cause.

   An arrival rather than a direct fire from where the start failed, because the session substrate
   cannot reach the queue and because one cause failing five sessions is one thing to recover, not
   five. A recovery is one run of the recovery trigger, its records what that run did; a recovery
   from before runs is read as one from its archived holder. Owed is DERIVED on every poll: a
   failure stops being owed when a recovery run records it restored or withdrawn, or when a
   recovery that named it closed — a person's dismissal of the run in the project's recovery
   journal, or an archived recovery's own close. Nothing else marks a failure as handled, so a
   recovery that dies or gives up leaves its failures owed and the cause is fired again.

   PACED PER CAUSE, not braked per trigger. A breaker counts a trigger, so one cause whose recovery
   keeps failing would silence the recovery of every other; here a cause waits a delay that doubles
   with each consecutive recovery run that ended leaving it owed, up to a declared ceiling, and the
   other causes are untouched.

   It never emits for a failure whose origin is a Run this source fired, from before runs — a
   recovery whose own session cannot start is not something another recovery could fix."
  (Operation duration-ms "A declared duration — 90s, 2m, 1h, 1d — in milliseconds, or nil."
    {:signature [:=> [:catn [:s :string]] [:maybe :int]]})
  (Operation pacing
    "The pacing a :session-failure source config declares, in milliseconds, with the defaults."
    {:signature [:=> [:catn [:source-config [:maybe :map]]] :map] :delegates [duration-ms]})
  (Operation failed-in-a-row
    "How many of a cause's recovery runs ended leaving it owed — failed, or stopped for a person —
     at the end of the list."
    {:signature [:=> [:catn [:runs [:vector :map]]] :int]})
  (Operation pacing-runs
    "The runs that pace a cause, oldest first: every recovery run of it."
    {:signature [:=> [:catn [:recoveries [:vector :map]] [:cause :string]] [:vector :map]]})
  (Operation due-at
    "When a cause may next be recovered — nil when it may be at any time. After k consecutive
     failures, the last one's end plus one poll interval doubled k-1 times, capped at the ceiling."
    {:signature [:=> [:catn [:runs [:vector :map]] [:pacing :map]] :any]
     :delegates [failed-in-a-row]})
  (Operation recoveries
    "Every recovery of a project as data, oldest first — one per recovery run, a recovery from
     before runs read from its archived holder: its cause, whether and when it ended, the failures
     named on it and those it settled, its runs, and its records by kind."
    {:signature [:=> [:catn [:project ProjectName]] [:vector :map]]})
  (Operation owed
    "The failures still owed a recovery: not settled, and not the start of a recovery Run from
     before runs."
    {:signature [:=> [:catn [:failures [:vector :map]] [:recoveries [:vector :map]]] [:vector :map]]})
  (Operation discharged-ids
    "The ids of every failure settled — recorded restored or withdrawn by a recovery run, or named
     by a recovery that closed — known without opening a record."
    {:signature [:=> [:catn [:recoveries [:vector :map]]] [:set :string]]})
  (Operation undischarged-failures
    "Every kept failure not settled, read from disk; settled records stay unopened."
    {:signature [:=> [:catn [:recoveries [:vector :map]]] [:vector :map]]
     :delegates [discharged-ids]})
  (Operation owed-failures "The failures a project's recoveries still owe, read now."
    {:signature [:=> [:catn [:project ProjectName]] [:vector :map]]
     :delegates [recoveries undischarged-failures owed]})
  (Operation in-flight?
    "Whether a recovery of a cause is in flight: a recovery run of it starting or running."
    {:signature [:=> [:catn [:recoveries [:vector :map]] [:cause :string]] :boolean]})
  (Operation due?
    "Whether a cause may be recovered now, given the runs that pace it: always when the latest did
     not end leaving it owed, else once the doubled delay since the last has passed."
    {:signature [:=> [:catn [:runs [:vector :map]] [:now :any] [:pacing :map]] :boolean]
     :delegates [due-at]})
  (Operation recovery-events
    "One event per cause among owed failures that is due, naming every owed failure of it, under an
     id keyed by the cause, its earliest owed failure and how many recovery runs it has had — the
     key a run is started under, so a re-emitted fire is answered and a retry is a new run."
    {:signature [:=> [:catn [:owed [:vector :map]] [:recoveries [:vector :map]] [:now :any] [:pacing :map]]
                 [:vector :map]]
     :delegates [in-flight? due? pacing-runs]})
  (Operation poll-once!
    "One iteration: derive the owed failures, emit the due causes' events, return the state to
     persist."
    {:signature [:=> [:catn [:source-config :map] [:emit-fn :any]] :map]
     :delegates [recoveries undischarged-failures owed recovery-events]})
  (Operation start-instance!
    "Start one configured instance, answering with its poll and stop functions."
    {:signature [:=> [:catn [:source-config :map] [:emit-fn :any]] :map]
     :delegates [poll-once!]})
  (Operation register!
    "Register this plugin with the source registry, at load time."
    {:signature [:=> [:catn] :any]
     :delegates [source/register-source! start-instance!]}))
