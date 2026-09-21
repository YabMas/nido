(ns nido.coordinator.daemon.reconcile
  "On daemon startup, force any non-terminal Run to a terminal state by
   reading observable evidence (artifacts, _run-status.edn, agent.log), and
   leave each Run's own session as the executor would have left it for that
   state.

   See spec §The coordinator daemon / Crash recovery."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.status-file :as status-file]
   [nido.coordinator.record.tickets :as tickets]))

(def ^:private non-terminal-states
  (set (keys runs/allowed-transitions)))

(def ^:private resolved-states
  "The Run states at which the executor tears a Run's session down."
  #{:done :failed :halted})

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
      (:triaged :dismissed) {:state :done           :error nil}
      :awaiting-input       {:state :awaiting-review :error nil}
      ;; :investigating / cleared / absent — orphan mid-investigation runs;
      ;; a run already parked at :awaiting-review stays parked.
      (if (= :awaiting-review (:state run))
        {:state :awaiting-review :error nil}
        {:state :failed :error {:reason :orphaned-from-restart}}))))

(defn- settle-session!
  "Leave the session `run` owns as the executor would have left it at `state`:
   torn down once the Run is resolved, its services stopped when this start has
   just parked it (`parked-now?`). Anything else — a Run left parked, a session
   the Run borrowed, a record no longer :live — is left alone, which is what
   makes a second start a no-op.

   Never throws: one Run's session must not keep the rest from being settled."
  [run state parked-now?]
  (try
    (let [{:keys [project workstream-id session-name]} run
          s (when workstream-id (session/read-session project workstream-id session-name))]
      ;; :live first: owns-session? reads every Run record, and only a handful
      ;; of sessions are still live at any start.
      (when (and s (session/live? s) (runs/owns-session? run))
        (cond
          (resolved-states state) (runs/teardown-session-for-run! run)
          parked-now?             (runs/stop-session-for-parked-run! run))))
    (catch Throwable e
      (binding [*out* *err*]
        (println (str "nido coordinator: reconcile could not settle the session of "
                      (:id run) " — " (ex-message e)))))))

(defn- reconcile-one!
  "Read run.edn, decide a terminal/parked state if non-terminal, write it back,
   then settle the Run's session for the state it ends at. A Run already
   resolved has its session settled too: a restart between a Run's state and
   its session leaves nothing else that would ever reach it.
   :queued runs are pending work — they are left intact for re-submission."
  [run-id]
  (when-let [run (runs/read-run run-id)]
    (when (resolved-states (:state run))
      (settle-session! run (:state run) false))
    (when (and (contains? non-terminal-states (:state run))
               (not= :queued (:state run)))
      ;; Back-fill the resumable id onto the session regardless of state change,
      ;; so a still-parked session becomes self-sufficient on restart.
      (when-let [ws-id (:workstream-id run)]
        (when (:claude-session-id run)
          (try (session/set-claude-session-id! (:project run) ws-id
                                               (:session-name run) (:claude-session-id run))
               (catch Exception _ nil))))      ; best-effort; human/missing session
      (let [{:keys [state error]}
            (cond
              (= :merge (:trigger run))
              ;; A half-driven merge: re-provisioning isn't safe to auto-resume,
              ;; so park it as blocked (gate inbox) rather than failing it.
              (if (= :awaiting-review (:state run))
                {:state :awaiting-review :error nil}            ; already parked
                {:state :awaiting-review
                 :error {:reason :orphaned-from-restart}})

              (= :triage-bug (:skill run)) (triage-reconciled-state run)

              ;; A recovery finishes only on a diagnosis it made while it ran —
              ;; the same rule its execution applies, or a restart would let one
              ;; through that the daemon would have failed.
              (runs/recovery? run)
              (let [derived (derive-terminal-state run-id)]
                (if (and (#{:done :awaiting-review} (:state derived))
                         (not (runs/diagnosed-while-running? run)))
                  {:state :failed :error {:reason :undiagnosed}}
                  derived))

              :else                        (derive-terminal-state run-id))]
        ;; no-op if state unchanged (e.g. parked → parked)
        (when (not= state (:state run))
          (let [history-entry {:at (clock/now-iso) :state state}
                updated       (-> run (assoc :state state :error error)
                                  (update :state-history conj history-entry))]
            (runs/write-run! updated)
            (runs/mirror-run-phase! updated)
            ;; Keep the ticket record honest: an orphaned triage Run clears a stale :investigating.
            (tickets/on-run-terminal! updated state)
            (settle-session! updated state (= :awaiting-review state))))))))

(defn ^{:malli/schema [:=> [:cat] :any]}
  reconcile!
  "Scan every Run directory under ~/.nido/runs/, force any non-terminal Run to
   a terminal state, and settle the session each Run owns for the state it ends
   at. Idempotent — an already-terminal Run's state is never rewritten, and a
   session is settled only while its record is still :live."
  []
  (let [d (cstate/runs-dir)]
    (when (fs/exists? d)
      (doseq [child (fs/list-dir d)
              :when (fs/directory? child)]
        (reconcile-one! (str (fs/file-name child)))))))
