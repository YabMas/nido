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
   [nido.coordinator.record.proposal :as proposal]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.status-file :as status-file]
   [nido.coordinator.record.workstream :as cws]))

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

(def ^:private max-requeues
  "How many times a restart may put one Run back in the queue. A Run is only
   requeued when its agent never launched, so the loop this bounds needs a boot
   that itself restarts the daemon — cheap to rule out all the same."
  2)

(defn- requeue?
  "Should this restart put `run` back in the queue instead of failing it?

   Only an ORPHAN is, and only one the restart caught still provisioning its
   session. agent.log is the evidence: the agent's first output creates it, so
   its absence means no agent ever ran and nothing was done that a second start
   could repeat. Failing such a Run lost the work outright — its session was
   torn down, and a source that dedups what it has seen (a Slack reaction) never
   fires the event again. A Run whose agent did start is failed as before: what
   it did cannot be read back from here."
  [run-id run {:keys [state error]}]
  (and (#{:preprocessing :running} (:state run))         ; a parked Run has run its agent
       (= :failed state)
       (= :orphaned-from-restart (:reason error))
       (not (fs/exists? (cstate/run-agent-log run-id)))
       (< (count (filter #(= :queued (:state %)) (:state-history run)))
          (inc max-requeues))))

(defn- triage-reconciled-state
  "Reconciled state for a non-:queued triage run, from what its ticket's ledger holds — never
   from the ticket's status, which this run, still open, would answer :investigating:
   dismissed or its verdict accepted → :done; a verdict waiting for a person → parked for them;
   no verdict yet → an orphan mid-investigation fails, unless it was already parked."
  [run]
  (let [br (some-> run :event-payload :id)
        p  (:project run)
        w  (when-not (str/blank? br) (cws/find-by-ref-id p br))
        v  (when w (standing/verdict p (:id w) w))]
    (cond
      (or (= :dismissed (:outcome (when w (cws/closure w))))
          (:accepted? v))                 {:state :done           :error nil}
      v                                   {:state :awaiting-review :error nil}
      (= :awaiting-review (:state run))   {:state :awaiting-review :error nil}
      :else {:state :failed :error {:reason :orphaned-from-restart}})))

(defn- live-owned?
  "Whether `run` owns its session and that session's record is still :live."
  [{:keys [project workstream-id session-name] :as run}]
  (let [s (when workstream-id (session/read-session project workstream-id session-name))]
    ;; :live first: owns-session? reads every Run record, and only a handful
    ;; of sessions are still live at any start.
    (boolean (and s (session/live? s) (runs/owns-session? run)))))

(defn- teardown-due?
  "Whether reconcile tears down the session `run` ran in when it settles the Run at `state`:
   resolved, a session it owns still :live, and one its end reclaims (runs/reclaims-session?).
   The teardown retires the session's background conversation itself, first."
  [run state]
  (boolean (and (resolved-states state) (runs/reclaims-session? run) (live-owned? run))))

(defn- settle-session!
  "Leave the session `run` owns as the executor would have left it at `state`:
   torn down once the Run is resolved, its services stopped when this start has
   just parked it (`parked-now?`). Anything else — a Run left parked, a session
   the Run borrowed, a record no longer :live — is left alone, which is what
   makes a second start a no-op.

   Never throws: one Run's session must not keep the rest from being settled."
  [run state parked-now?]
  (try
    (cond
      (teardown-due? run state)              (runs/teardown-session-for-run! run)
      (and parked-now? (live-owned? run))    (runs/stop-session-for-parked-run! run))
    (catch Throwable e
      (binding [*out* *err*]
        (println (str "nido coordinator: reconcile could not settle the session of "
                      (:id run) " — " (ex-message e)))))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  release-improvement-hold!
  "Close the improvement workstream a resolved Run was working, as :orphaned,
   when the Run ended without closing it — so the sweep it holds is released.

   The sweep is released by a close and by nothing weaker, so a second agent
   never starts on a branch the first abandoned mid-edit. A claim that lands
   is closed by `discharge-claim!` before its Run exits, and one that stops
   untrue or vetoed is closed by its session, so a resolved Run whose claim is
   still open died before either: a restart (09-23, 09-29), a turn ended early
   (09-11), a usage-limit kill (10-08). No session of it will write the close,
   and the sweep would wait on a workstream nothing is working. Each claim gets
   its own workspace, so releasing it puts no second agent on the dead one's
   branch.

   Left open: a workstream without the :improvement adapter, one already
   closed, and one carrying an unanswered :blocker — a halt is the skill
   holding the slot for a person on purpose. Never throws: one Run's claim must
   not keep the rest from being settled."
  [{:keys [project workstream-id id]}]
  (try
    (when-let [w (when workstream-id (cws/read-ws project workstream-id))]
      (let [answered (into #{} (keep :blocker-seq)
                           (cws/entries-of project workstream-id :blocker-answered))
            halted?  (some #(and (= :blocker (:kind %)) (not (answered (:seq %))))
                           (:entries w))]
        (when (and (nil? (cws/closure w))
                   (not halted?)
                   (some #(= proposal/improvement-adapter (:adapter %)) (:external-refs w)))
          (cws/close! project workstream-id :orphaned))))
    (catch Throwable e
      (binding [*out* *err*]
        (println (str "nido coordinator: could not release the improvement hold of "
                      id " — " (ex-message e)))))))

(defn- on-substrate?
  "Whether a Run's session record carries a :claude facet, or a :dispatching note — a start in
   flight that Claude Code has not named yet, which is already a background session and never a
   legacy Run."
  [run]
  (boolean
   (and run (:workstream-id run) (:session-name run)
        (when-let [s (session/read-session (:project run) (:workstream-id run) (:session-name run))]
          (or (session/claude-facet s) (:dispatching s))))))

(defn- reconcile-one!
  "Read run.edn, decide a terminal/parked state if non-terminal, write it back,
   then settle the Run's session and claim for the state it ends at. A Run
   already resolved has them settled too: a restart between a Run's state and
   its session leaves nothing else that would ever reach it.
   :queued runs are pending work — they are left intact for re-submission, and a
   Run caught before its agent launched rejoins them (see requeue?).

   A Run whose conversation is a background session and that is not yet resolved
   is left exactly as it is: the session outlived the daemon, and its own Stop
   settles the Run when the turn ends. Nothing here reads what an exited process
   left behind for it. One already resolved is settled like any other."
  [run-id]
  (when-let [run (let [r (runs/read-run run-id)]
                   (when-not (and (not (resolved-states (:state r))) (on-substrate? r)) r))]
    (when (resolved-states (:state run))
      (settle-session! run (:state run) false)
      ;; Gated on the payload: this branch reads every Run on disk, and only a
      ;; claim's Run can hold the sweep. It is what clears a hold a Run left
      ;; before the executor released holds at all.
      (when (= proposal/improvement-adapter (-> run :event-payload :adapter))
        (release-improvement-hold! run)))
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

              :else                        (derive-terminal-state run-id))
            {:keys [state error]}
            (if (requeue? run-id run {:state state :error error})
              {:state :queued :error nil}
              {:state state :error error})]
        ;; no-op if state unchanged (e.g. parked → parked)
        (when (not= state (:state run))
          (let [history-entry {:at (clock/now-iso) :state state}
                updated       (-> run (assoc :state state :error error)
                                  (update :state-history conj history-entry))]
            (runs/write-run! updated)
            ;; A requeued Run is not terminal: its session is left as it is
            ;; for the start the daemon resubmits it to, and
            ;; `session:up` converges the half-built session it finds.
            (when-not (= :queued state)
              (settle-session! updated state (= :awaiting-review state))
              (when (resolved-states state)
                (release-improvement-hold! updated)))))))))

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
