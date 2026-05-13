(ns nido.coordinator.core
  "Coordinator main loop. Foreground only in Stage 1a.

   See spec §The coordinator daemon."
  (:refer-clojure :exclude [run!])
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.anomaly :as anomaly]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.events :as events]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.heartbeat :as heartbeat]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.status-file :as status-file]
   [nido.coordinator.triggers :as triggers]
   [nido.project :as project]))

(def ^:private defaults
  {:poll-ms             1000
   :global-parallel-cap 2
   :system-prompt       "You are running inside a nido auto-triggered session. The user is not present yet. Write artifacts under <session-home>/artifacts/ with stable filenames. Update <session-home>/_run-status.edn at phase transitions with {:phase :awaiting-input | :working | :complete | :error :note <str>}."})

(def ^:private anomaly-thresholds
  {:spawn-window-ms 60000  :spawn-threshold 5
   :fail-window-ms  300000 :fail-threshold 3})

(defonce ^:private !detector (atom (anomaly/empty-detector)))

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

(defn- run-now!
  "Drive a single :queued Run to terminal/awaiting-review state.
   Synchronous in Stage 1a — concurrency is added in Stage 2."
  [run-id]
  (runs/transition! run-id :running)
  (let [run        (runs/read-run run-id)
        _          (runs/spawn-session-for-run! run)
        worktree   (str (fs/path (cstate/run-session-home-link run-id) "worktree"))
        result     (agent/launch! {:run-id        run-id
                                   :cwd           worktree
                                   :first-message (:first-message run)
                                   :system-prompt (:system-prompt defaults)
                                   :budget        (-> run :limits :budget)})
        next-state (cond
                     (:timed-out? result) :failed
                     (zero? (:exit-code result))
                     (status-file/derive-state-after-exit
                       (status-file/read-status run-id))
                     :else :failed)]
    ;; persist captured claude session-id
    (let [r (runs/read-run run-id)]
      (runs/write-run! (assoc r :claude-session-id (:claude-session-id result))))
    (runs/transition! run-id next-state)
    (when (= :failed next-state)
      (let [r (runs/read-run run-id)]
        (runs/write-run! (assoc r :error (cond-> {:exit-code (:exit-code result)}
                                           (:timed-out? result)
                                           (assoc :reason :timeout
                                                  :budget (-> r :limits :budget)))))))
    ;; Breaker update on terminal state. Default max-failures is 3; the
    ;; trigger's :limits.max-failures (snapshotted onto the Run at create
    ;; time) overrides this.
    (let [project      (:project run)
          trigger-name (:trigger run)
          max-failures (or (-> run :limits :max-failures) 3)]
      (case next-state
        :failed          (breakers/record-failure! project trigger-name max-failures)
        :done            (breakers/record-success! project trigger-name)
        :awaiting-review (breakers/record-success! project trigger-name)
        nil))))

(defn- mark-run-failed! [run-id ex]
  (try
    (let [r (runs/read-run run-id)]
      (when r
        (runs/write-run! (assoc r
                                :state :failed
                                :error {:reason  :coordinator-exception
                                        :message (ex-message ex)
                                        :data    (ex-data ex)}))))
    (catch Exception _ nil)))

(defn- process-envelope! [envelope triggers-by-project]
  (let [routed (events/route envelope triggers-by-project)]
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

      :else
      (let [run (runs/create-run! routed
                                  {:fired-at (clock/now-iso)
                                   :fired-by (System/getenv "USER")})]
        (swap! !detector anomaly/record-spawn (clock/now-iso))
        (try
          (run-now! (:id run))
          ;; If the Run finished :failed, record the failure for anomaly tracking.
          (let [final (runs/read-run (:id run))]
            (when (= :failed (:state final))
              (swap! !detector anomaly/record-failure (clock/now-iso))))
          (catch Exception e
            ;; Don't let one bad Run kill the daemon: mark it :failed and
            ;; continue. Stage 2 will add structured budget/retry, but for
            ;; Stage 1a we just need the loop to survive.
            (swap! !detector anomaly/record-failure (clock/now-iso))
            (binding [*err* *err*]
              (.println ^java.io.PrintWriter *err*
                        (str "ERROR: run-now! threw for "
                             (:id run) " — " (ex-message e))))
            (mark-run-failed! (:id run) e)))))))

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
        (doseq [env (queue/drain!)]
          (process-envelope! env triggers-by-project))
        ;; After draining, check anomaly thresholds.
        (when-let [trip (anomaly/check @!detector anomaly-thresholds)]
          (halt/halt! {:source  :auto
                       :reason  (:trip trip)
                       :details trip
                       :note    (str "auto-halt: " (name (:trip trip))
                                     " count=" (:count trip))}))))))

(defn run!
  "Start the foreground loop. Blocks until interrupted."
  [& {:keys [poll-ms] :or {poll-ms (:poll-ms defaults)}}]
  (cstate/ensure-dirs!)
  (println "nido coordinator: starting (foreground, poll" poll-ms "ms)")
  (heartbeat/write! {:status :running :slots-in-use 0})
  (loop []
    (tick!)
    (Thread/sleep poll-ms)
    (recur)))
