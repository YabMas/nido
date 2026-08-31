(ns nido.coordinator.daemon.breakers
  "Per-trigger circuit breaker. Stores consecutive-failure counts at
   ~/.nido/coordinator/breakers.edn. When a trigger's count meets its
   max-failures threshold the daemon stops processing its envelopes.

   See spec §Safety brakes / Circuit breaker."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(defn ^{:malli/schema [:=> [:cat] :map]}
  read-all []
  (let [p (cstate/breakers-path)]
    (if (fs/exists? p)
      (try (io/read-edn p) (catch Exception _ {}))
      {})))

(defn- update-all!
  "Apply `f` to the breakers map and write the result, as one locked operation.

   The daemon is not the only writer: `bb nido:trigger:enable` and
   `coordinator:source:reset` clear breakers from a separate process while the
   loop is recording failures. A lost update here is not a cosmetic one — the
   surviving write decides whether a trigger fires, so a dropped
   :disabled-by-user? silently un-pauses a trigger the operator paused on
   purpose, and a dropped enable! leaves one tripped with nothing to say why."
  [f]
  (io/update-edn! (cstate/breakers-path)
                  (fn [m] (f (if (map? m) m {})))))

(defn- entry [m project trigger]
  (get-in m [project trigger]
          {:consecutive-failures 0
           :tripped?             false
           :disabled-by-user?    false
           :last-failure-at      nil
           :note                 nil}))

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword] :int]}
  consecutive-failures [project trigger]
  (:consecutive-failures (entry (read-all) project trigger)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword] :boolean]}
  tripped?
  "True iff the breaker is open: either consecutive failures hit the
   threshold last seen on this trigger, or the user disabled it."
  [project trigger]
  (let [e (entry (read-all) project trigger)]
    (or (:tripped? e) (:disabled-by-user? e))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword :int] :any]}
  record-failure!
  "Increment the consecutive-failure counter for (project, trigger).
   If the new count meets max-failures, mark :tripped? true."
  [project trigger max-failures]
  (update-all!
   (fn [m]
     (let [e  (entry m project trigger)
           n  (inc (:consecutive-failures e))
           e' (-> e
                  (assoc :consecutive-failures n
                         :last-failure-at      (clock/now-iso)
                         :tripped?             (>= n max-failures)))]
       (assoc-in m [project trigger] e')))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword] :any]}
  record-success!
  "Clear the consecutive-failure counter and auto-disable flag. Does
   NOT clear :disabled-by-user? — user disables stick until enable!."
  [project trigger]
  (update-all!
   (fn [m]
     (let [e' (-> (entry m project trigger)
                  (assoc :consecutive-failures 0 :tripped? false))]
       (assoc-in m [project trigger] e')))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword :string] :any]}
  disable-by-user!
  "Manual disable. Persists across success transitions."
  [project trigger note]
  (update-all!
   (fn [m]
     (let [e' (-> (entry m project trigger)
                  (assoc :disabled-by-user? true :note note))]
       (assoc-in m [project trigger] e')))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :keyword] :any]}
  enable!
  "Clear both auto-trip and user disable for one (project, trigger).
   Mirrors `bb nido:trigger:enable`."
  [project trigger]
  (update-all!
   (fn [m]
     (let [e' (-> (entry m project trigger)
                  (assoc :consecutive-failures 0
                         :tripped? false
                         :disabled-by-user? false))]
       (assoc-in m [project trigger] e')))))

(defn ^{:malli/schema [:=> [:cat] [:vector :map]]}
  tripped-triggers
  "Vector of {:project :trigger :info} for every breaker that's open —
   auto-tripped OR user-disabled. Both halt the daemon's processing of that
   trigger, so this is the set the loop/inspectors skip."
  []
  (vec
    (for [[project ts] (read-all)
          [trigger e]  ts
          :when (or (:tripped? e) (:disabled-by-user? e))]
      {:project project :trigger trigger :info e})))

(defn ^{:malli/schema [:=> [:cat] [:vector :map]]}
  auto-tripped-triggers
  "Vector of {:project :trigger :info} for breakers that AUTO-tripped on
   consecutive failures — deliberate user pauses excluded. This is the
   alarm-worthy set: the health dot uses it so a long-standing manual pause
   doesn't read as a fault (and can't mask a genuine new trip)."
  []
  (vec
    (for [[project ts] (read-all)
          [trigger e]  ts
          :when (:tripped? e)]
      {:project project :trigger trigger :info e})))
