(ns canvas.coordinator.view.runs
  "Self-spec: `nido.coordinator.view.runs` — the runs screen, read-only.

   Pure read model: read, classify, render. NO WRITES — which is what lets a surface show the
   work plane without being able to disturb it."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.daemon.brakes :as brakes]
            [canvas.coordinator.record.runs :as runs :refer [Run]]
            [canvas.coordinator.record.state :as state]
            [fukan.common.typing.malli]))

(Module view-runs
  "The runs screen: every Run, grouped and rendered, plus the alerts bar above it."
  (Operation read-all-runs
    "Every Run on disk. Malformed ones are skipped rather than fatal — one unreadable run must
     not blank the screen that would have shown you the other forty."
    {:signature [:=> [:catn] [:vector Run]]
     :delegates [runs/read-run state/runs-dir]})
  (Operation classify
    "Which display group a Run belongs to, from its state."
    {:signature [:=> [:catn [:run Run]] :keyword]})
  (Operation grouped-runs
    "Runs partitioned into display groups."
    {:signature [:=> [:catn [:all-runs [:vector Run]]] :map]
     :delegates [classify]})
  (Operation run-subject
    "A recognisable label for a Run — the ticket it is about, not its id."
    {:signature [:=> [:catn [:run Run]] :string]})
  (Operation format-row
    "One Run as a display line."
    {:signature [:=> [:catn [:run Run]] :string]
     :delegates [run-subject]})
  (Operation format-age
    "How long ago, in words."
    {:signature [:=> [:catn [:iso-ts [:maybe :string]]] :string]})
  (Operation breaker-reason
    "Why a breaker is open, in words — a deliberate pause and a failure streak read differently
     and must not render alike."
    {:signature [:=> [:catn [:breaker :map]] :string]
     :delegates [format-age]})
  (Operation read-alerts
    "What the status bar should say: open breakers and anything else demanding attention."
    {:signature [:=> [:catn] :map]
     :delegates [brakes/tripped-triggers breaker-reason]})
  (Operation read-coordinator-status
    "What the daemon last reported, and whether that reading is still current."
    {:signature [:=> [:catn] :map]
     :delegates [state/status-path]}))
