(ns nido.coordinator.core
  "Coordinator main loop. Foreground only in Stage 1a.

   See spec §The coordinator daemon."
  (:refer-clojure :exclude [run!])
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.agent :as agent]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.events :as events]
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
                                   :system-prompt (:system-prompt defaults)})
        next-state (if (zero? (:exit-code result))
                     (status-file/derive-state-after-exit
                       (status-file/read-status run-id))
                     :failed)]
    ;; persist captured claude session-id
    (let [r (runs/read-run run-id)]
      (runs/write-run! (assoc r :claude-session-id (:claude-session-id result))))
    (runs/transition! run-id next-state)
    (when (= :failed next-state)
      (let [r (runs/read-run run-id)]
        (runs/write-run! (assoc r :error {:exit-code (:exit-code result)}))))))

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
    (if (:error routed)
      (binding [*err* *err*]
        (.println ^java.io.PrintWriter *err*
                  (str "WARN: dropping envelope — " (pr-str routed))))
      (let [run (runs/create-run! routed
                                  {:fired-at (clock/now-iso)
                                   :fired-by (System/getenv "USER")})]
        (try
          (run-now! (:id run))
          (catch Exception e
            ;; Don't let one bad Run kill the daemon: mark it :failed and
            ;; continue. Stage 2 will add structured budget/retry, but for
            ;; Stage 1a we just need the loop to survive.
            (binding [*err* *err*]
              (.println ^java.io.PrintWriter *err*
                        (str "ERROR: run-now! threw for "
                             (:id run) " — " (ex-message e))))
            (mark-run-failed! (:id run) e)))))))

(defn tick!
  "One iteration of the main loop. Public for testability."
  []
  (let [triggers-by-project (load-all-triggers)
        envelopes           (queue/drain!)]
    (heartbeat/write! {:status :running :slots-in-use 0})
    (doseq [env envelopes]
      (process-envelope! env triggers-by-project))))

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
