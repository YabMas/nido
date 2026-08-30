(ns nido.coordinator.lane.runs-clean
  "Deletion logic for terminal-state Runs.

   Public surface:
     (plan-clean opts)   — return [{:run <map> :paths [<str>...]} ...] for matching runs
     (execute! plan)     — delete all paths in the plan

   Safe state allowlist (defaults when :state not provided):
     #{:done :failed :dry-run-would-fire :halted :cancelled}

   Live states (always refused, regardless of :state filter):
     #{:queued :running :awaiting-review}"
  (:require
   [babashka.fs :as fs]
   [clojure.set :as set]
   [clojure.string :as str]
   [nido.coordinator.view.runs :as runs-view]
   [nido.coordinator.record.state :as cstate]
   [nido.session.state :as sstate]))

(def safe-default-states
  "States that are safe to clean up by default."
  #{:done :failed :dry-run-would-fire :halted :cancelled})

(def live-states
  "States that indicate a Run may still be doing live work — never delete."
  #{:queued :running :awaiting-review})

;; ---------------------------------------------------------------------------
;; Parsing helpers
;; ---------------------------------------------------------------------------

(defn parse-duration-ms
  "Parse '7d', '12h', '30m' to milliseconds. Returns nil when input is nil."
  [s]
  (when s
    (let [m (re-matches #"(\d+)([dhm])" s)
          [_ n u] m]
      (when m
        (* (Long/parseLong n)
           (case u "d" 86400000 "h" 3600000 "m" 60000))))))

;; ---------------------------------------------------------------------------
;; Run matching
;; ---------------------------------------------------------------------------

(defn- age-ms
  "Milliseconds since the Run was created. Derived from the first state-history entry.
   Returns nil when the timestamp is absent or unparseable."
  [run]
  (when-let [created (some-> run :state-history first :at)]
    (try
      (- (System/currentTimeMillis)
         (.toEpochMilli (java.time.Instant/parse created)))
      (catch Exception _ nil))))

(defn- matches?
  "True iff `run` satisfies all filter criteria in `opts`."
  [run {:keys [state project older-than-ms]}]
  (and (contains? state (:state run))
       (or (nil? project) (= project (:project run)))
       (or (nil? older-than-ms)
           (when-let [a (age-ms run)] (>= a older-than-ms)))))

;; ---------------------------------------------------------------------------
;; Path derivation
;; ---------------------------------------------------------------------------

(defn- run-paths
  "Primary paths for a Run: the run dir + session-home dir.
   Returns a vector of path strings. Absent/nil session-name fields are
   silently omitted."
  [run]
  (let [run-id  (:id run)
        project (some-> run :project name)
        sess    (:session-name run)]
    (cond-> [(cstate/run-dir run-id)]
      (and project sess)
      (conj (sstate/session-home-dir project sess)))))

(defn- instance-state-dirs-for
  "Best-effort glob for the session's state dir under ~/.nido/state/.
   Instance IDs follow the pattern '<project>--<session-name>', so we prefix-
   match on that. Returns a (possibly empty) seq of path strings."
  [run]
  (let [project (some-> run :project name)
        sess    (:session-name run)]
    (when (and project sess)
      (let [prefix     (str project "--" sess)
            state-root (sstate/state-dir)]
        (when (fs/exists? state-root)
          (->> (fs/list-dir state-root)
               (map str)
               (filter #(str/starts-with? (str (fs/file-name %)) prefix))))))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn plan-clean
  "Return [{:run <map> :paths [<str>...]} ...] for Runs matching the filter.

   opts:
     :state        — set of state keywords (default: safe-default-states)
     :project      — keyword; nil = all projects
     :older-than   — string like \"7d\", \"12h\", \"30m\"; nil = no age filter
     :allow-live?  — when true, skip the live-state refusal (interactive override)
     :dry-run?     — ignored here; callers use it to decide whether to call execute!

   Throws ex-info when `state` overlaps with `live-states`, unless `:allow-live?`
   is true. The live-state guard protects the automated/bulk paths (reclaim,
   `plan-clean {}`); the interactive TUI passes `:allow-live? true` because its
   per-run confirmation dialog is the safety layer."
  [{:keys [state project older-than allow-live?]
    :or   {state safe-default-states}}]
  (when (and (not allow-live?)
             (seq (set/intersection state live-states)))
    (throw (ex-info "Cannot clean Runs in live states"
                    {:requested state
                     :live      live-states
                     :rejected  (set/intersection state live-states)})))
  (let [older-ms (parse-duration-ms older-than)
        all-runs (runs-view/read-all-runs)
        matching (filter #(matches? % {:state        state
                                       :project      project
                                       :older-than-ms older-ms})
                         all-runs)]
    (mapv (fn [r]
            {:run   r
             :paths (vec (concat (run-paths r)
                                 (instance-state-dirs-for r)))})
          matching)))

(defn execute!
  "Delete every path listed in the plan. Missing paths are silently skipped.
   Returns nil."
  [plan]
  (doseq [{:keys [paths]} plan]
    (doseq [p paths]
      (when (fs/exists? p)
        (fs/delete-tree p)))))
