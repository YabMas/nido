(ns nido.ci.step-run
  "Pure state machine for a StepRun: one step's execution inside an
   aggregate Run. Transitions are value-returning functions over a
   step-run map; no I/O. See specs/local_ci.allium for the semantic
   model."
  (:require
   [nido.core :as core]))

(defn new-step-run
  "Seed a fresh StepRun in state :pending. Caller supplies the parent
   run-id, the step-name, the resolved step, the Session value and the
   derived StepRunPaths."
  [{:keys [run-id step-name step session paths]}]
  {:schema-version 1
   :run-id run-id
   :step-run-id (name step-name)
   :state :pending
   :started-at (core/now-iso)
   :finished-at nil
   :step-name step-name
   :command (:command step)
   :after (vec (or (:after step) []))
   :services {}
   :isolation nil
   :command-exit-code nil
   :command-pid nil
   :command-pgid nil
   :error-message nil
   :outcome nil
   :session {:instance-id (:instance-id session)
             :project-name (:project-name session)
             :worktree-path (:worktree-path session)
             :instance-state-dir (:instance-state-dir session)
             :pg-port (:pg-port session)}
   :paths paths})

(defn prepare [run isolation]
  (-> run
      (assoc :state :preparing)
      (assoc :isolation isolation)))

(defn begin-command [run]
  (assoc run :state :running_command))

(defn record-command-exit [run exit-code]
  (-> run
      (assoc :command-exit-code exit-code)
      (assoc :state :tearing_down)))

(defn attach-process [run pid pgid]
  (-> run
      (assoc :command-pid pid)
      (assoc :command-pgid pgid)))

(defn fail [run message]
  (-> run
      (assoc :error-message message)
      (assoc :state :tearing_down)))

(defn interrupt [run]
  (fail run "interrupted"))

(defn timed-out [run]
  (fail run "step-run exceeded step_run_timeout"))

(defn aborted-by-run [run]
  (fail run "run interrupted"))

(defn- derive-outcome [run]
  (let [msg (:error-message run)]
    (cond
      (contains? #{"interrupted" "run interrupted"} msg) :interrupted
      msg                                                :errored
      (= 0 (:command-exit-code run))                     :passed
      :else                                              :failed)))

(defn finish [run]
  (-> run
      (assoc :state :done)
      (assoc :finished-at (core/now-iso))
      (assoc :outcome (derive-outcome run))))

(defn terminal? [run]
  (= :done (:state run)))

(defn tearing-down? [run]
  (= :tearing_down (:state run)))

;; ---------------------------------------------------------------------------
;; Service state
;; ---------------------------------------------------------------------------

(defn new-service
  "Seed a fresh Service map in state :not_started. log-path is
   fully resolved by the caller (see nido.ci.paths/service-log-path)."
  [service-def log-path]
  {:definition service-def
   :name       (:name service-def)
   :state      :not_started
   :pid        nil
   :pgid       nil
   :started-at nil
   :ready-at   nil
   :exited-at  nil
   :exit-code  nil
   :log-path   log-path})

(defn with-services [run service-states]
  (assoc run :services service-states))

(defn begin-starting-services [run]
  (assoc run :state :starting_services))

(defn service-starting [run svc-name pid pgid]
  (update-in run [:services svc-name]
             (fn [s] (-> s
                         (assoc :state :starting)
                         (assoc :pid pid)
                         (assoc :pgid pgid)
                         (assoc :started-at (core/now-iso))))))

(defn service-ready [run svc-name]
  (update-in run [:services svc-name]
             (fn [s] (-> s
                         (assoc :state :ready)
                         (assoc :ready-at (core/now-iso))))))

(defn service-exited [run svc-name exit-code]
  (update-in run [:services svc-name]
             (fn [s] (-> s
                         (assoc :state :exited)
                         (assoc :exit-code exit-code)
                         (assoc :exited-at (core/now-iso))))))

(defn service-killed [run svc-name]
  (update-in run [:services svc-name]
             (fn [s] (if (#{:exited :killed} (:state s))
                       s
                       (-> s
                           (assoc :state :killed)
                           (assoc :exited-at (core/now-iso)))))))

(defn all-services-ready? [run]
  (let [svcs (vals (:services run))]
    (and (seq svcs)
         (every? #(= :ready (:state %)) svcs))))
