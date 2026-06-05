(ns nido.coordinator.core
  "Coordinator main loop. Foreground only in Stage 1a.

   See spec §The coordinator daemon."
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.set :as set]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.anomaly :as anomaly]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.events :as events]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.heartbeat :as heartbeat]
   [nido.coordinator.notify :as notify]
   [nido.coordinator.pid :as pid]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.reconcile :as reconcile]
   [nido.coordinator.review :as review]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.sources :as sources]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.sources.notion :as nsource]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.status-file :as status-file]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.triggers :as triggers]
   [nido.project :as project]))

(def ^:private defaults
  {:poll-ms             1000
   :global-parallel-cap 2
   :executor            {:shutdown-grace-ms 5000}
   :system-prompt       "You are running inside a nido auto-triggered session. The user is not present yet. Write artifacts under <session-home>/artifacts/ with stable filenames. Update <session-home>/_run-status.edn at phase transitions with {:phase :awaiting-input | :working | :complete | :error :note <str>}."})

(def ^:private anomaly-thresholds
  {:spawn-window-ms 60000  :spawn-threshold 5
   :fail-window-ms  300000 :fail-threshold 3})

(defonce ^:private !detector (atom (anomaly/empty-detector)))

(defonce ^:private !source-instances (atom {}))

(defn- registered-projects []
  ;; nido.project/list-projects returns {<string-name> {:directory ...}}.
  ;; Envelopes (and load-all-triggers' returned map) use keyword project names,
  ;; so coerce here to a vector of keywords.
  (mapv keyword (keys (project/list-projects))))

(defn- load-all-triggers
  "Returns {:brian [triggers] :foo [triggers]}."
  []
  (->> (registered-projects)
       (into {} (map (fn [p] [p (triggers/load-for-project p)])))))

(defn- parse-duration-ms [s]
  ;; Minimal duration parser: "30s" "5m" "1h"
  (let [[_ n u] (re-matches #"(\d+)\s*([smh])" s)]
    (when n
      (* (parse-long n)
         (case u "s" 1000 "m" 60000 "h" 3600000)))))

(defn- discover-source-configs
  "Walk loaded triggers and return distinct source-configs whose type is
   registered. Filters out :manual (the queue dir handles that).
   Merges :project from the trigger's owning project into each source-config
   so plugins that resolve per-project registry files (e.g. notion-views.edn)
   know which project they are serving."
  [triggers-by-project]
  (->> (for [[project triggers] triggers-by-project, t triggers
             :let [src (assoc (:source t) :project project)]
             :when (and (not= :manual (:type src))
                        (sources/lookup (:type src)))]
         src)
       distinct))

(defn- start-source! [source-config]
  (let [hash    (sources/config-hash source-config)
        plugin  (sources/lookup (:type source-config))
        handle  ((:start! plugin) source-config sources/emit-broadcast!)
        poll-ms (or (parse-duration-ms (str (:poll source-config))) 300000)]
    (swap! !source-instances assoc hash
           (assoc handle
                  :source-config  source-config
                  :poll-ms        poll-ms
                  :last-polled-ms 0))))

(defn- stop-source! [config-hash]
  (when-let [{:keys [stop!]} (get @!source-instances config-hash)]
    (try (stop!) (catch Exception _ nil)))
  (swap! !source-instances dissoc config-hash))

(defn- reconcile-sources!
  "Start sources that should be running, stop sources that no longer have
   a referencing trigger."
  [triggers-by-project]
  (let [desired-configs (discover-source-configs triggers-by-project)
        desired         (set (map sources/config-hash desired-configs))
        current         (set (keys @!source-instances))]
    (doseq [hash (set/difference current desired)]
      (stop-source! hash))
    (doseq [sc desired-configs
            :when (not (contains? current (sources/config-hash sc)))]
      (start-source! sc))))

(defn- run-blocking!
  "Drive a single :queued Run to terminal/awaiting-review state.
   Called inside an executor-spawned future (one per slot). The name
   reflects that this fn blocks its thread for the duration of the run;
   it is no longer called directly from process-envelope! (which now
   submits to the executor instead)."
  [run-id]
  (runs/transition! run-id :running)
  (let [;; Generate the claude session id up front and persist it to run.edn
        ;; BEFORE launch, so a restart mid-session (or any interruption) never
        ;; strands the Run without a resumable id — the resume shim reads this.
        ;; Passed to claude as --session-id so the transcript uses it.
        session-id   (str (java.util.UUID/randomUUID))
        run          (let [r (assoc (runs/read-run run-id) :claude-session-id session-id)]
                       (runs/write-run! r) r)
        ;; Spawn the session + launch claude. If EITHER throws (e.g. session
        ;; build / worktree creation fails), don't let it escape — that would
        ;; leave the Run stuck :running forever (a zombie that permanently
        ;; consumes its trigger's in-flight slot, since nothing transitions it
        ;; terminal and the resolution sweep only handles :awaiting-review).
        ;; Catch and fall through to the :failed terminal path below.
        ;;
        ;; Claude is launched with the SESSION-HOME as cwd (not the worktree):
        ;; it registers its transcript by cwd under ~/.claude/projects/, which is
        ;; what makes `claude --resume` work from the session home.
        result       (try
                       (runs/spawn-session-for-run! run)
                       (when (= :plan-bug (:skill run))
                         (notify/on-plan-spawn! run))
                       (agent/launch! {:run-id            run-id
                                       :cwd               (cstate/run-session-home-link run-id)
                                       :first-message     (:first-message run)
                                       :system-prompt     (:system-prompt defaults)
                                       :budget            (-> run :limits :budget)
                                       :claude-session-id session-id})
                       (catch Throwable t
                         {:spawn-error true :detail (.getMessage t)}))
        next-state (cond
                     (:spawn-error result) :failed
                     (:timed-out? result) :failed
                     (zero? (:exit-code result))
                     (if (#{:triage-bug :plan-bug} (:skill run))
                       (review/run-state-from-ticket
                         (tickets/status (:project run) (some-> run :event-payload :id)))
                       (status-file/derive-state-after-exit
                         (status-file/read-status run-id)))
                     :else :failed)]
    ;; (session-id was already persisted up front, above)
    (runs/transition! run-id next-state)
    (when (= :failed next-state)
      (let [r (runs/read-run run-id)]
        (runs/write-run! (assoc r :error (cond-> {:exit-code (:exit-code result)}
                                           (:spawn-error result)
                                           (assoc :reason :spawn-failed
                                                  :detail (:detail result))
                                           (:timed-out? result)
                                           (assoc :reason :timeout
                                                  :budget (-> r :limits :budget)))))))
    ;; Keep the ticket record honest on terminal exit (spec §Lifecycle):
    ;; clears a stale :investigating after an abnormal exit, leaves completed
    ;; dispositions and parked :awaiting-input drafts alone.
    (tickets/on-run-terminal! (runs/read-run run-id) next-state)
    ;; Breaker update on terminal state. Default max-failures is 3; the
    ;; trigger's :limits.max-failures (snapshotted onto the Run at create
    ;; time) overrides this.
    (let [project      (:project run)
          trigger-name (:trigger run)
          max-failures (or (-> run :limits :max-failures) 3)]
      (case next-state
        :failed          (do (swap! !detector anomaly/record-failure (clock/now-iso))
                             (breakers/record-failure! project trigger-name max-failures))
        :done            (breakers/record-success! project trigger-name)
        :awaiting-review (breakers/record-success! project trigger-name)
        nil))))

(defn- process-envelope! [envelope triggers-by-project]
  (doseq [routed (events/route envelope triggers-by-project)]
    (cond
      (:error routed)
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: dropping envelope — " (pr-str routed))))

      (breakers/tripped? (:project routed) (-> routed :trigger :name))
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: trigger breaker open — skipping "
                       (name (:project routed)) "/"
                       (name (-> routed :trigger :name)))))

      ;; Dry-run short-circuit (spec §Dry-run mode): record the would-fire
      ;; as a terminal Run, but never spawn a session. Anomaly detector
      ;; still sees the spawn so a chatty mis-configured trigger trips it.
      ;; Breakers stay untouched — neither success nor failure of the skill.
      (-> routed :trigger :dry-run?)
      (let [run (runs/create-run! routed
                                  {:fired-at (clock/now-iso)
                                   :fired-by (System/getenv "USER")})]
        (swap! !detector anomaly/record-spawn (clock/now-iso))
        (runs/transition! (:id run) :dry-run-would-fire))

      ;; Triage pre-spawn gate (spec §Coordinator pre-spawn gate): a triage
      ;; event whose ticket is already completed, or already owned by a live
      ;; session, is dropped without creating a Run. BR-#### rides in the
      ;; event-payload (:id) — no network. Non-triage triggers are unaffected.
      (and (= :triage-bug (-> routed :trigger :skill))
           (when-let [br (-> routed :payload :id)]
             (not= :spawn (tickets/gate-decision (:project routed) br))))
      (let [br       (-> routed :payload :id)
            decision (tickets/gate-decision (:project routed) br)]
        (binding [*err* *err*]
          (.println ^java.io.PrintWriter *err*
                    (str "INFO: triage gate skip — " (name (:project routed)) "/"
                         br " (" (name decision) ")"))))

      :else
      (let [run (runs/create-run! routed
                                  {:fired-at (clock/now-iso)
                                   :fired-by (System/getenv "USER")})]
        (swap! !detector anomaly/record-spawn (clock/now-iso))
        (executor/submit! (:id run) (:priority run) (:uncapped? run)
                          (:trigger run) (-> routed :trigger :max-in-flight))))))

(defn tick!
  "One iteration of the main loop. Public for testability."
  []
  (let [triggers-by-project (load-all-triggers)
        halt-info           (halt/read-halt-info)]
    (if halt-info
      (heartbeat/write! {:status       :halted
                         :halted-by    (:source halt-info)
                         :halt-note    (:note halt-info)
                         :slots-in-use 0})
      (do
        (heartbeat/write! {:status :running :slots-in-use 0})
        (reconcile-sources! triggers-by-project)
        ;; Drain queue first — this consumes envelopes emitted on the PREVIOUS
        ;; tick by source polls. Keeps each tick's unit of work small.
        (doseq [env (queue/drain!)]
          (process-envelope! env triggers-by-project))
        ;; Reap finished executor futures and promote waiting Runs into
        ;; free slots. run-blocking! is the body executed per slot.
        (review/sweep-resolved!)
        (executor/tick! run-blocking! (runs/in-progress-count-by-trigger))
        ;; Then poll due sources. Their emissions land in the queue and
        ;; will be picked up next tick.
        (let [now-ms (System/currentTimeMillis)]
          (doseq [[hash inst] @!source-instances
                  :when (>= (- now-ms (:last-polled-ms inst)) (:poll-ms inst))]
            (try
              ((:poll! inst))
              (catch Exception e
                (binding [*err* *err*]
                  (.println ^java.io.PrintWriter *err*
                            (str "WARN: source " hash " poll! threw — " (ex-message e))))))
            (swap! !source-instances assoc-in [hash :last-polled-ms] now-ms)))
        ;; After draining + polling, check anomaly thresholds.
        (when-let [trip (anomaly/check @!detector anomaly-thresholds)]
          (halt/halt! {:source  :auto
                       :reason  (:trip trip)
                       :details trip
                       :note    (str "auto-halt: " (name (:trip trip))
                                     " count=" (:count trip))}))))))

(defn- install-shutdown-hook! []
  (.addShutdownHook
    (Runtime/getRuntime)
    (Thread.
      (fn []
        (doseq [hash (keys @!source-instances)]
          (stop-source! hash))
        (try (heartbeat/write! {:status :stopped :slots-in-use 0})
             (catch Exception _ nil))
        (try (pid/delete!)
             (catch Exception _ nil))))))

(defn shutdown-grace-ms
  "Return the grace period (in ms) for daemon shutdown (SIGTERM to SIGKILL)."
  []
  (-> defaults :executor :shutdown-grace-ms))

(defn- resubmit-queued!
  "After reconcile, re-add surviving :queued runs to the executor queue so the
   in-memory queue rehydrates across a restart."
  [triggers-by-project]
  (let [cap-of (into {}
                     (for [[_ ts] triggers-by-project, t ts]
                       [(:name t) (:max-in-flight t)]))]
    (doseq [rid (runs/list-run-ids)
            :let [r (runs/read-run rid)]
            :when (and r (= :queued (:state r)))]
      (executor/submit! (:id r) (:priority r) (:uncapped? r)
                        (:trigger r) (get cap-of (:trigger r))))))

(defn run!
  "Start the foreground loop. Blocks until interrupted.
   Also installs the daemon lifecycle: writes coordinator.pid, runs the
   crash-recovery reconcile pass, and registers a JVM shutdown hook."
  [& {:keys [poll-ms] :or {poll-ms (:poll-ms defaults)}}]
  (cstate/ensure-dirs!)
  (println "nido coordinator: starting (poll" poll-ms "ms)")
  (reconcile/reconcile!)
  (resubmit-queued! (load-all-triggers))
  (pid/write! (long (.pid (java.lang.ProcessHandle/current))))
  (install-shutdown-hook!)
  (heartbeat/write! {:status :running :slots-in-use 0})
  (executor/configure! {:global-cap (:global-parallel-cap defaults)})
  (nsource/register!)                                 ; register Notion source plugin
  (loop []
    (tick!)
    (Thread/sleep poll-ms)
    (recur)))
