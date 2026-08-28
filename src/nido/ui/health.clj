;; src/nido/ui/health.clj
(ns nido.ui.health
  "Daemon health for the dashboard rail dot. Read-only over the coordinator's
   status/halt/breaker files. Pure `daemon-health` + impure `read-daemon-health`,
   mirroring nido.coordinator.work/all-machine-rows' split so the state logic is testable
   without disk."
  (:require
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.pid :as pid]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]))

(defn daemon-health
  "Pure: derive the rail dot state from extracted inputs.
   :halted (kill switch) > :breaker (any open) > :up (alive + running) > :down.

   This is a SEVERITY ladder for one colored dot — 'is anything wrong anywhere'.
   It deliberately ranks :breaker above :up, so a single tripped trigger outranks
   an otherwise healthy daemon. Do NOT read it as a liveness predicate: use
   `queue-blocker` to ask whether a given envelope will actually be processed."
  [{:keys [alive? halted? breaker-count status]}]
  {:state (cond
            halted?                                       :halted
            (pos? (or breaker-count 0))                   :breaker
            (and alive? (= :running (:status status)))    :up
            :else                                         :down)
   :heartbeat-at (:heartbeat-at status)})

(defn queue-blocker
  "Pure: why a queued envelope targeted at one trigger would NOT be processed,
   or nil when it will. The counterpart to `daemon-health` — same files, a
   different question ('will THIS run' vs 'is anything wrong anywhere'), so a
   caller never has to read the dot's severity ladder as a go/no-go.

   Mirrors what coordinator.core/tick! actually gates on, in the order the
   daemon hits them: a dead process stops everything (nothing consults halt or
   breakers), a live-but-halted daemon skips the drain entirely, and a routed
   envelope is finally dropped when ITS OWN trigger's breaker is open. A breaker
   on any OTHER trigger is irrelevant here — that is the distinction the rail
   dot cannot make."
  [{:keys [alive? halted? status trigger-tripped?]}]
  (cond
    (not alive?)                     :daemon-down
    halted?                          :halted
    (not= :running (:status status)) :daemon-down
    trigger-tripped?                 :breaker))

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

(defn read-queue-blocker
  "Impure: read the coordinator files and derive what, if anything, blocks a
   queued envelope for `project`/`trigger`. nil means it will run."
  [project trigger]
  (queue-blocker
   {:alive?           (pid/alive?)
    :halted?          (halt/halted?)
    :status           (read-edn-safe (cstate/status-path))
    ;; Unlike the dot, a user-disabled trigger DOES block the envelope, so this
    ;; asks breakers/tripped? (auto OR manual) rather than auto-tripped-triggers.
    :trigger-tripped? (boolean (breakers/tripped? project trigger))}))
