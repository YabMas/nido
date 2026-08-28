(ns nido.coordinator.tickets-view
  "Pure data layer for the TUI tickets screen and the `nido:tickets:list` CLI:
   reads per-ticket records across all projects, groups them by lifecycle
   stage, and formats display rows. No charm dependencies — the TUI's
   update/view functions consume this. The actionable group is :ready (acked
   triage, status :triaged) — those are the tickets you promote to start an
   implementation session."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]))

(defn read-all-tickets
  "Read every ticket meta.edn under ~/.nido/projects/*/tickets/*/, each tagged
   with its :project (keyword). Skips malformed/missing files silently."
  []
  (let [projects-dir (fs/path (cstate/nido-root) "projects")]
    (if-not (fs/exists? projects-dir)
      []
      (vec
        (for [proj  (fs/list-dir projects-dir)
              :when (fs/directory? proj)
              :let  [tdir (fs/path proj "tickets")]
              :when (fs/exists? tdir)
              tk    (fs/list-dir tdir)
              :when (fs/directory? tk)
              :let  [m (try (io/read-edn (str (fs/path tk "meta.edn")))
                            (catch Exception _ nil))]
              :when m]
          (assoc m :project (keyword (str (fs/file-name proj)))))))))

(defn classify
  "Bucket a ticket by lifecycle stage:
   - :ready       — acked triage (:triaged), ready to promote to implementation
   - :in-progress — being worked (:investigating :awaiting-input :planning :implementing)
   - :dismissed   — taken off the radar (:dismissed)
   - :other       — anything else / no status"
  [{:keys [status]}]
  (cond
    (= :triaged status)                                                :ready
    (#{:investigating :awaiting-input :planning :implementing} status) :in-progress
    (= :dismissed status)                                              :dismissed
    :else                                                              :other))

(defn last-activity
  "Best timestamp for ordering: the latest ledger entry's :at, else :triaged-at."
  [m]
  (or (some-> m :entries last :at) (:triaged-at m)))

(defn grouped-tickets
  "Partition tickets into display groups, newest activity first.
   :dismissed is capped at 10; :other is dropped from the overview."
  [all]
  (let [by      (group-by classify all)
        newest  (fn [ms] (vec (sort-by last-activity #(compare %2 %1) ms)))]
    {:ready       (newest (:ready by []))
     :in-progress (newest (:in-progress by []))
     :dismissed   (vec (take 10 (newest (:dismissed by []))))}))

(def ^:private title-max 52)

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn format-row
  "Display string for a ticket: `BR-#### · <title>  [status]`."
  [{:keys [br-id status title]}]
  (format "%-9s · %s  [%s]"
          (or br-id "?")
          (truncate (str title) title-max)
          (name (or status :?))))
