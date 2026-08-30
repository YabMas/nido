(ns nido.coordinator.control
  "Daemon control, as a surface may ask for it: is the coordinator healthy, will this envelope
   run, pause it, resume it, clear a breaker.

   The work plane's SECOND facade, and it exists because the first one is about the wrong
   subject. `nido.coordinator.work` is the vocabulary of workstreams, tickets and runs; nothing
   in it wants to know about a pid file or a launchd plist. Pushing halt and breakers through it
   to satisfy a dependency rule would have made `work` answer two unrelated questions, which is
   how a facade becomes a junk drawer.

   So: two facades over the work plane, one per question a surface actually asks. Surfaces reach
   these and not the strata beneath them; `Tasks` and `Boot` are composition roots and reach the
   daemon directly, because installing a launchd plist is not a thing a facade should have an
   opinion about.

   The pure/impure split is deliberate and predates this namespace — it came from
   `nido.ui.health`, whose two derivations were coordinator semantics wearing a UI namespace: a
   `daemon-health` that ranks 'is anything wrong anywhere', and a `queue-blocker` that answers
   the different question 'will THIS envelope run'. Both belong to whoever owns the daemon's
   state files, which is here. The dashboard renders what they return."
  (:require
   [nido.coordinator.daemon.breakers :as breakers]
   [nido.coordinator.daemon.halt :as halt]
   [nido.coordinator.daemon.pid :as pid]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

;; ── health: the rail dot, and the different question underneath it ───────────

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

   Mirrors what the daemon's tick actually gates on, in the order it hits them: a
   dead process stops everything (nothing consults halt or breakers), a
   live-but-halted daemon skips the drain entirely, and a routed envelope is
   finally dropped when ITS OWN trigger's breaker is open. A breaker on any OTHER
   trigger is irrelevant here — that is the distinction the rail dot cannot make."
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

;; ── the levers ───────────────────────────────────────────────────────────────

(defn halted?
  "True iff the coordinator is paused."
  []
  (halt/halted?))

(defn halt-info
  "Why and when the coordinator was paused, or nil when it is running.
   Named for the question rather than for the file it reads."
  []
  (halt/read-halt-info))

(defn halt!
  "Pause the coordinator. `info` carries :source (:user | :auto) and an optional
   :reason / :note; the timestamp is stamped for you."
  [info]
  (halt/halt! info))

(defn resume!
  "Un-pause the coordinator. Idempotent."
  []
  (halt/resume!))

(defn tripped-triggers
  "Every open breaker as {:project :trigger :info} — auto-tripped OR user-disabled,
   which is the set the daemon skips."
  []
  (breakers/tripped-triggers))

(defn clear-breaker!
  "Re-enable one (project, trigger): clears both the auto-trip and any user
   disable. `clear-` rather than `enable-` because from a surface this is
   undoing a trip, not turning a feature on."
  [project trigger]
  (breakers/enable! project trigger))
