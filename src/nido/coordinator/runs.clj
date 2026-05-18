(ns nido.coordinator.runs
  "Canonical Run record: schema, read/write, state machine.

   See spec §Runs."
  (:require
   [babashka.fs :as fs]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.triggers :as triggers]
   [nido.io :as io]
   [nido.session.lifecycle :as session-lifecycle]
   [nido.session.state :as session-state]))

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
   [:priority          int?]
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
  "Read a run.edn by id. Returns nil if absent.
   Normalizes legacy records: backfills :priority 0 when the key is absent
   so that pre-Plan-A on-disk Runs pass the closed schema on write-back."
  [run-id]
  (let [path (cstate/run-edn-path run-id)]
    (when (fs/exists? path)
      (-> (io/read-edn path)
          (update :priority #(if (int? %) % 0))))))

(defn write-run!
  "Validate then write a Run record. Parent dir must already exist."
  [run]
  (validate run)
  (io/write-edn! (cstate/run-edn-path (:id run)) run)
  run)

(def allowed-transitions
  "Map of from-state → set of to-states.
   See spec §Runs / Lifecycle. Terminal states have no entries."
  {:queued          #{:running :failed :halted :dry-run-would-fire}
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

(defn- new-run-parts
  "Returns {:run-id ... :session-name ... :suffix ...} so callers don't have
   to re-derive the session-name from the run-id by string surgery."
  [project trigger-name]
  ;; clock/now-iso is ISO-8601 (YYYY-MM-DDTHH:...Z); first 10 chars = date.
  (let [date (subs (clock/now-iso) 0 10)
        suf  (subs (str (java.util.UUID/randomUUID)) 0 8)]
    {:run-id       (str date "-" (name project) "-" (name trigger-name) "-" suf)
     :session-name (str "run-" (name project) "-" (name trigger-name) "-" suf)
     :suffix       suf}))

(defn create-run!
  "Build a :queued Run record from a fire request and persist run.edn.
   `meta` carries source-call metadata: {:fired-at <iso> :fired-by <str>}."
  [{:keys [project trigger payload priority]} meta]
  (let [{:keys [run-id session-name]} (new-run-parts project (:name trigger))
        ;; First message format per spec §Agent launch: "/<skill> <interpolated-payload>".
        ;; The trigger's :payload holds just the skill args; the framework prepends "/<skill> ".
        message (str "/" (name (:skill trigger)) " "
                     (triggers/render-payload (:payload trigger) payload))
        run     {:id              run-id
                 :project         project
                 :trigger         (:name trigger)
                 ;; Preserve all source config keys (e.g. :database, :view for :notion-view)
                 ;; so the Run record stays useful for debugging non-:manual sources later.
                 :source          (merge (:source trigger) meta)
                 :event-payload   payload
                 :skill           (:skill trigger)
                 :first-message   message
                 :agent           (or (:agent trigger) :claude)
                 :session-name    session-name
                 :claude-session-id nil
                 :limits          (or (:limits trigger) {:budget "30m" :max-failures 3})
                 :priority        (or priority 0)
                 :state           :queued
                 :state-history   [{:at (clock/now-iso) :state :queued}]
                 :artifacts       []
                 :error           nil}]
    (fs/create-dirs (cstate/run-dir run-id))
    (fs/create-dirs (cstate/run-artifacts-dir run-id))
    (write-run! run)))

(defn spawn-session-for-run!
  "Bring up a session for the given Run, marked :owned-by-run. The launcher
   picks up :owned-by-run in the session-edn and writes the resume shim +
   run-link via nido.coordinator.shim. After session-up, also writes the
   reverse `<run-dir>/session-home` symlink the coordinator uses to locate
   the worktree (per spec §Runs / Identity & storage). Returns whatever
   session-lifecycle/up! returns."
  [run]
  (let [{:keys [project session-name id]} run
        result       (session-lifecycle/up! session-name
                                            {:project      project
                                             :owned-by-run id})
        session-home (session-state/session-home-dir (name project) session-name)
        link-path    (cstate/run-session-home-link id)]
    (when (fs/exists? link-path) (fs/delete link-path))
    (fs/create-sym-link link-path session-home)
    result))
