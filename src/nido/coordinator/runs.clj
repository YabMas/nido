(ns nido.coordinator.runs
  "Canonical Run record: schema, read/write, state machine.

   See spec §Runs."
  (:require
   [babashka.fs :as fs]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(def states
  "Permitted Run states. See spec §Runs / Lifecycle."
  #{:queued :running :awaiting-review :done :failed :halted :dry-run-would-fire})

(def Run
  [:map {:closed true}
   [:id                string?]
   [:project           keyword?]
   [:trigger           keyword?]
   [:source            [:map [:type keyword?]]]
   [:event-payload     [:map-of keyword? any?]]
   [:skill             keyword?]
   [:first-message     string?]
   [:agent             keyword?]
   [:session-name      string?]
   [:claude-session-id [:maybe string?]]
   [:limits            [:map-of keyword? any?]]
   [:state             (into [:enum] states)]
   [:state-history     [:vector [:map
                                 [:at    string?]
                                 [:state (into [:enum] states)]]]]
   [:artifacts         [:vector [:map
                                 [:path string?]
                                 [:written-at string?]]]]
   [:error             [:maybe [:map-of keyword? any?]]]])

(defn validate
  "Returns the run or throws ex-info with humanized errors."
  [run]
  (if (m/validate Run run)
    run
    (throw (ex-info "Invalid Run record"
                    {:errors (m/explain Run run)
                     :run    run}))))

(defn read-run
  "Read a run.edn by id. Returns nil if absent."
  [run-id]
  (let [path (cstate/run-edn-path run-id)]
    (when (fs/exists? path)
      (io/read-edn path))))

(defn write-run!
  "Validate then write a Run record. Parent dir must already exist."
  [run]
  (validate run)
  (io/write-edn! (cstate/run-edn-path (:id run)) run)
  run)

(def allowed-transitions
  "Map of from-state → set of to-states.
   See spec §Runs / Lifecycle. Terminal states have no entries."
  {:queued          #{:running :failed :halted}
   :running         #{:awaiting-review :done :failed :halted}
   :awaiting-review #{:running :done :failed :halted}})

(defn valid-transition?
  "True iff `from` → `to` is in `allowed-transitions`. Terminal states
   have no entry and so reject every transition."
  [from to]
  (contains? (get allowed-transitions from #{}) to))

(defn transition!
  "Atomically update a Run's state with history. Throws ex-info if the
   run is absent or the transition is invalid. Returns the updated Run."
  [run-id new-state]
  (let [run  (or (read-run run-id)
                 (throw (ex-info "Run not found" {:run-id run-id})))
        from (:state run)]
    (when-not (valid-transition? from new-state)
      (throw (ex-info "Invalid transition"
                      {:run-id run-id :from from :to new-state})))
    (let [updated (-> run
                      (assoc :state new-state)
                      (update :state-history conj
                              {:at (clock/now-iso) :state new-state}))]
      (write-run! updated)
      updated)))
