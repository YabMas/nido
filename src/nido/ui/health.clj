;; src/nido/ui/health.clj
(ns nido.ui.health
  "Daemon health for the dashboard rail dot. Read-only over the coordinator's
   status/halt/breaker files. Pure `daemon-health` + impure `read-daemon-health`,
   mirroring nido.ui.server/all-session-rows' split so the state logic is testable
   without disk."
  (:require
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.pid :as pid]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn daemon-health
  "Pure: derive the rail dot state from extracted inputs.
   :halted (kill switch) > :breaker (any open) > :up (alive + running) > :down."
  [{:keys [alive? halted? breaker-count status]}]
  {:state (cond
            halted?                                       :halted
            (pos? (or breaker-count 0))                   :breaker
            (and alive? (= :running (:status status)))    :up
            :else                                         :down)
   :heartbeat-at (:heartbeat-at status)})

(defn- read-edn-safe [path]
  (try (io/read-edn path) (catch Throwable _ nil)))

(defn read-daemon-health
  "Impure: read the coordinator status/halt/breaker files and derive health."
  []
  (daemon-health
   {:alive?        (pid/alive?)
    :halted?       (halt/halted?)
    ;; Only AUTO-tripped breakers (real failures) light the dot — a deliberate
    ;; user-pause is a normal operational state, not a fault.
    :breaker-count (count (breakers/auto-tripped-triggers))
    :status        (read-edn-safe (cstate/status-path))}))
