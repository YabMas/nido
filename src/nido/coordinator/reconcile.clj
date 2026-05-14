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
   [nido.coordinator.status-file :as status-file]))

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

(defn- reconcile-one!
  "Read run.edn, decide a terminal state if non-terminal, write it back."
  [run-id]
  (when-let [run (runs/read-run run-id)]
    (when (contains? non-terminal-states (:state run))
      (let [{:keys [state error]} (derive-terminal-state run-id)
            history-entry         {:at (clock/now-iso) :state state}
            updated               (-> run
                                      (assoc :state state
                                             :error error)
                                      (update :state-history conj history-entry))]
        (runs/write-run! updated)))))

(defn reconcile!
  "Scan every Run directory under ~/.nido/runs/ and force any non-terminal
   Run to a terminal state. Idempotent — already-terminal Runs are left alone."
  []
  (let [d (cstate/runs-dir)]
    (when (fs/exists? d)
      (doseq [child (fs/list-dir d)
              :when (fs/directory? child)]
        (reconcile-one! (str (fs/file-name child)))))))
