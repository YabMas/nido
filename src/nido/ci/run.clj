(ns nido.ci.run
  "Pure state machine for an aggregate Run: a set of StepRuns that
   execute in parallel (subject to step-level :after deps). Transitions
   are value-returning functions over a run map; no I/O. See
   specs/local_ci.allium for the semantic model."
  (:require
   [nido.core :as core]))

(defn new-run
  "Seed a fresh Run in state :pending. Step-run snapshots are layered in
   later by the orchestrator as each step-run progresses."
  [{:keys [run-id session paths rerun-of step-names]}]
  {:schema-version 1
   :run-id run-id
   :state :pending
   :started-at (core/now-iso)
   :finished-at nil
   :rerun-of rerun-of
   :error-message nil
   :outcome nil
   :step-names (vec step-names)
   :step-runs {} ; step-name-kw -> latest step-run snapshot
   :session {:instance-id        (:instance-id session)
             :project-name       (:project-name session)
             :worktree-path      (:worktree-path session)
             :instance-state-dir (:instance-state-dir session)
             :pg-port            (:pg-port session)}
   :paths paths})

(defn begin [run]
  (assoc run :state :running))

(defn record-step-run [run step-name step-run-snapshot]
  (assoc-in run [:step-runs step-name] step-run-snapshot))

(defn fail [run message]
  (-> run
      (assoc :state :tearing_down)
      (assoc :error-message message)))

(defn interrupt [run]
  (fail run "interrupted"))

(defn enter-teardown [run]
  (if (or (= :done (:state run)) (= :tearing_down (:state run)))
    run
    (assoc run :state :tearing_down)))

(defn- any-step-with [run outcome]
  (some #(= outcome (:outcome %)) (vals (:step-runs run))))

(defn- derive-outcome [run]
  (let [msg (:error-message run)]
    (cond
      (or (= "interrupted" msg)
          (any-step-with run :interrupted))  :interrupted
      (or msg (any-step-with run :errored))  :errored
      (any-step-with run :failed)            :failed
      :else                                  :passed)))

(defn finish [run]
  (-> run
      (assoc :state :done)
      (assoc :finished-at (core/now-iso))
      (assoc :outcome (derive-outcome run))))

(defn terminal? [run]
  (= :done (:state run)))

(defn tearing-down? [run]
  (= :tearing_down (:state run)))

(defn all-step-runs-terminal?
  "Predicate over the in-memory Run state. step-names is the full set
   the orchestrator is waiting on; :step-runs is keyed by step-name and
   its snapshots must all be :done for the Run to roll up."
  [run]
  (let [wanted (set (:step-names run))
        recorded (:step-runs run)]
    (and (seq wanted)
         (every? (fn [n]
                   (= :done (get-in recorded [n :state])))
                 wanted))))
