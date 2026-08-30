(ns canvas.boot.core
  "Self-spec: `nido.boot.core` — the daemon's composition root."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.daemon.brakes :as brakes]
            [canvas.coordinator.daemon.lifecycle :as daemon]
            [canvas.coordinator.executor :as executor]
            [canvas.coordinator.lane.drive :as drive]
            [canvas.coordinator.record.runs :as runs]
            [canvas.coordinator.record.vocabulary :refer [RunId]]
            [canvas.coordinator.source.core :as source]
            [fukan.common.typing.malli]))

(Module boot-core
  "The daemon: one loop that drains the queue, routes what it finds, promotes what the caps
   allow, and reaps what finished.

   A COMPOSITION ROOT, which is why it reaches every band and declares that it does. It sits
   above everything and is reached by nothing but the task that starts it — and it deliberately
   does NOT depend on the surfaces. It used to start and stop the dashboard itself, which made
   the reverse edge real; it now takes a dashboard lifecycle as an argument and the task supplies
   both. The declaration is what forced that.

   The tick is the whole design: reconcile on startup, because a Run left mid-flight by a crash
   is indistinguishable from one still going; then the brakes, because a halted daemon should not
   even drain; then drain, route, spawn, promote, reap."
  (Operation dashboard-config "Whether the dashboard runs, and on what port."
    {:signature [:=> [:catn [:opts :map]] :map]})
  (Operation dashboard-status-line "The dashboard's line in the status output."
    {:signature [:=> [:catn [:port [:maybe :int]] [:reachable? :boolean]] :string]})
  (Operation execute! "Hand a run to the body that executes it."
    {:signature [:=> [:catn [:rid RunId]] :any] :delegates [runs/read-run]})
  (Operation drive-log-line "One line for a drive decision."
    {:signature [:=> [:catn [:entry :map]] :string]})
  (Operation tick! "One iteration: brakes, drain, route, spawn, promote, reap."
    {:signature [:=> [:catn] :any]
     :delegates [source/drain! source/route executor/tick! drive/tick! daemon/write! execute!]})
  (Operation shutdown-grace-ms
    "How long to let in-flight work finish before exiting. A daemon that killed its own runs on
     shutdown would leave exactly the mid-flight state reconcile exists to repair."
    {:signature [:=> [:catn] :int]})
  (Operation run! "Start the loop, with a dashboard lifecycle supplied from outside."
    {:signature [:=> [:catn [:opts [:* :any]]] :any]
     :delegates [tick! dashboard-config daemon/reconcile!]}))
