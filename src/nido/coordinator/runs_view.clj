(ns nido.coordinator.runs-view
  "Pure data layer for the TUI runs screen: reads runs from disk, classifies
   by state, formats display rows, computes ages. No charm dependencies —
   the TUI's update/view functions consume this. See spec §Run overview
   TUI surface."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.breakers :as breakers]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.halt :as halt]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.state :as cstate]
   [nido.io :as io]))

(defn read-all-runs
  "Read every run.edn under ~/.nido/runs/. Skips malformed files silently
   (invalid runs are noise; the daemon's monitoring is responsible for
   surfacing real errors)."
  []
  (let [d (cstate/runs-dir)]
    (if (fs/exists? d)
      (->> (fs/list-dir d)
           (filter fs/directory?)
           (map (comp str fs/file-name))
           sort
           (keep (fn [run-id]
                   (try (runs/read-run run-id)
                        (catch Exception _ nil))))
           vec)
      [])))

(defn classify
  "Categorize a Run by state for the overview's grouped display:
   - :needs-attention — :awaiting-review, :failed, :halted (user should look)
   - :in-flight       — :queued, :running (daemon's working)
   - :recent          — :done (terminal happy path)
   - :archive         — :dry-run-would-fire and any other terminal/unknown"
  [{:keys [state]}]
  (cond
    (#{:awaiting-review :failed :halted} state) :needs-attention
    (#{:queued :running} state)                 :in-flight
    (= :done state)                             :recent
    :else                                       :archive))

(defn- last-state-at [run]
  (some-> run :state-history last :at))

(defn grouped-runs
  "Partition a vector of Runs into display groups.
   - :needs-attention and :in-flight include all matching runs
   - :recent caps at 10, newest first
   Excludes the :archive bucket (not shown on the overview)."
  [all-runs]
  (let [by-cat  (group-by classify all-runs)
        recent  (->> (:recent by-cat [])
                     (sort-by last-state-at #(compare %2 %1))
                     (take 10)
                     vec)]
    {:needs-attention (vec (:needs-attention by-cat []))
     :in-flight       (vec (:in-flight by-cat []))
     :recent          recent}))

(defn- state-label
  "Display label for a state keyword. Long compound names like
   `awaiting-review` show only the first segment so the bracketed
   state column stays a compact, fixed-width column."
  [state]
  (first (str/split (name state) #"-")))

(defn format-row
  "Display string for a single Run: `[state-padded] project · trigger · subject`.
   Subject is the run-id. A future iteration can substitute the trigger's
   :payload-key value if needed."
  [{:keys [state project trigger id]}]
  (format "[%-15s] %s · %s · %s"
          (state-label state)
          (name project)
          (name trigger)
          id))

(defn format-age
  "Human-readable age string for an ISO-8601 timestamp relative to now.
   Buckets: just now (<10s), Ns ago, Nm ago, Nh ago, Nd ago."
  [iso-ts]
  (try
    (let [now     (java.time.Instant/parse (clock/now-iso))
          then    (java.time.Instant/parse iso-ts)
          seconds (.getSeconds (java.time.Duration/between then now))]
      (cond
        (< seconds 10)        "just now"
        (< seconds 60)        (str seconds "s ago")
        (< seconds 3600)      (str (quot seconds 60) "m ago")
        (< seconds 86400)     (str (quot seconds 3600) "h ago")
        :else                 (str (quot seconds 86400) "d ago")))
    (catch Exception _ "?")))

(def heartbeat-stale-after-seconds 5)

(defn read-alerts
  "Aggregate alert summary for the TUI status bar.
   Returns {:halted? <bool> :halt-source <kw or nil> :halt-note <str or nil> :breakers <int>}."
  []
  (let [halt-info (halt/read-halt-info)]
    {:halted?     (boolean halt-info)
     :halt-source (:source halt-info)
     :halt-note   (:note halt-info)
     :breakers    (count (breakers/tripped-triggers))}))

(defn- read-coordinator-status*
  "Reachability core: reads ~/.nido/coordinator/status.edn and stamps
   `:reachable?` based on heartbeat freshness. Caller layers alerts on top."
  []
  (let [p (cstate/status-path)
        absent {:status :unreachable :reachable? false :heartbeat-at nil :slots-in-use 0}]
    (if-not (fs/exists? p)
      absent
      (try
        (let [s (io/read-edn p)
              hb (:heartbeat-at s)
              fresh? (and hb
                          (try
                            (let [now  (java.time.Instant/parse (clock/now-iso))
                                  then (java.time.Instant/parse hb)
                                  age  (.getSeconds (java.time.Duration/between then now))]
                              (<= age heartbeat-stale-after-seconds))
                            (catch Exception _ false)))]
          (-> s
              (assoc :reachable? (boolean fresh?))
              (cond-> (not fresh?) (assoc :status :unreachable))))
        (catch Exception _ absent)))))

(defn read-coordinator-status
  "Read ~/.nido/coordinator/status.edn and decide reachability.
   Returns:
     {:status <kw or :unreachable> :slots-in-use <int>
      :heartbeat-at <iso or nil> :reachable? <bool>
      :alerts {:halted? <bool> :halt-source <kw or nil> :halt-note <str> :breakers <int>}
      :executor {:cap <int> :in-flight <int> :queued <int> :queue [run-ids…]}}"
  []
  (-> (read-coordinator-status*)
      (assoc :alerts (read-alerts))
      (assoc :executor (executor/snapshot))))
