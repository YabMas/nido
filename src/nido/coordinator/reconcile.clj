(ns nido.coordinator.reconcile
  "On daemon startup, force any non-terminal Run to a terminal state by
   reading observable evidence (artifacts, _run-status.edn, agent.log).

   See spec §The coordinator daemon / Crash recovery."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.status-file :as status-file]
   [nido.coordinator.tickets :as tickets]))

(def ^:private non-terminal-states
  (set (keys runs/allowed-transitions)))

(defn- agent-log-reached-result?
  "True iff the last non-blank line of agent.log contains a `result` event.
   That's claude-code's terminal stream-json event; its presence means
   the agent exited cleanly without writing a status file."
  [run-id]
  (let [p (cstate/run-agent-log run-id)]
    (when (fs/exists? p)
      (let [lines (->> (str/split-lines (slurp p))
                       (remove str/blank?))]
        (when-let [last-line (last lines)]
          (str/includes? last-line "\"type\":\"result\""))))))

(defn- derive-terminal-state
  "Decide the terminal state for one non-terminal Run."
  [run-id]
  (let [status (status-file/read-status run-id)]
    (cond
      (= :complete       (:phase status)) {:state :done :error nil}
      (= :awaiting-input (:phase status)) {:state :awaiting-review :error nil}
      (= :error          (:phase status)) {:state :failed
                                           :error {:reason :skill-reported-error
                                                   :note   (:note status)}}
      (agent-log-reached-result? run-id) {:state :done :error nil}
      :else                               {:state :failed
                                           :error {:reason :orphaned-from-restart}})))

(defn- triage-reconciled-state
  "Reconciled state for a non-:queued triage run, derived from its ticket record.
   Ticket status drives the decision; for runs still mid-investigation at restart
   (no resolved ticket) the run state determines whether to park or fail."
  [run]
  (let [br (some-> run :event-payload :id)
        ts (when (and br (not (str/blank? br)))
             (tickets/status (:project run) br))]
    (case ts
      (:triaged :skipped) {:state :done          :error nil}
      :awaiting-input     {:state :awaiting-review :error nil}
      ;; :investigating / cleared / absent — orphan mid-investigation runs;
      ;; a run already parked at :awaiting-review stays parked.
      (if (= :awaiting-review (:state run))
        {:state :awaiting-review :error nil}
        {:state :failed :error {:reason :orphaned-from-restart}}))))

(defn- reconcile-one!
  "Read run.edn, decide a terminal/parked state if non-terminal, write it back.
   :queued runs are pending work — they are left intact for re-submission."
  [run-id]
  (when-let [run (runs/read-run run-id)]
    (when (and (contains? non-terminal-states (:state run))
               (not= :queued (:state run)))
      (let [{:keys [state error]} (if (= :triage-bug (:skill run))
                                    (triage-reconciled-state run)
                                    (derive-terminal-state run-id))]
        (when (not= state (:state run))                  ; no-op if state unchanged (e.g. parked → parked)
          (let [history-entry {:at (clock/now-iso) :state state}
                updated       (-> run
                                  (assoc :state state
                                         :error error)
                                  (update :state-history conj history-entry))]
            (runs/write-run! updated)
            (runs/mirror-run-phase! updated)
            ;; Keep the ticket record honest: an orphaned triage Run clears
            ;; a stale :investigating so the ticket is re-triable next poll.
            (tickets/on-run-terminal! updated state)))))))

(defn reconcile!
  "Scan every Run directory under ~/.nido/runs/ and force any non-terminal
   Run to a terminal state. Idempotent — already-terminal Runs are left alone."
  []
  (let [d (cstate/runs-dir)]
    (when (fs/exists? d)
      (doseq [child (fs/list-dir d)
              :when (fs/directory? child)]
        (reconcile-one! (str (fs/file-name child)))))))
