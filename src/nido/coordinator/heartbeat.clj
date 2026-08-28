(ns nido.coordinator.heartbeat
  "Write ~/.nido/coordinator/status.edn with a fresh timestamp.

   The main loop ticks this every iteration so the TUI can age-check the
   file to detect a missing or stuck daemon. See spec §The coordinator
   daemon / Comms with TUI/CLI."
  (:require
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]))

(defn write!
  "Persist `state` (a map) to status.edn with a fresh :heartbeat-at."
  [state]
  (io/write-edn! (cstate/status-path)
                 (assoc state :heartbeat-at (clock/now-iso))))
