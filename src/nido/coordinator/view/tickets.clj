(ns nido.coordinator.view.tickets
  "Pure data layer for the TUI tickets screen and the `nido:tickets:list` CLI:
   reads per-ticket records across all projects with each one's derived status
   (`tickets/status-of`), groups them by lifecycle stage, and formats display
   rows. No charm dependencies — the TUI's update/view functions consume this.
   The actionable group is :ready (acked triage, status :triaged) — those are
   the tickets you promote to start an implementation session."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as cws]
   [nido.platform.io :as io]))

(defn- by-ref
  "Each workstream of `project` by the id of every external ref it carries — read once, so a
   project's tickets find their workstreams without a scan each."
  [project]
  (into {} (for [id (cws/list-ids project)
                 :let [w (cws/read-ws project id)]
                 :when w
                 r (:external-refs w)]
             [(:id r) w])))

(defn ^{:malli/schema [:=> [:cat] [:vector :Ticket]]}
  read-all-tickets
  "Read every ticket meta.edn under ~/.nido/projects/*/tickets/*/, each tagged with its :project
   (keyword), the :status its workstream's ledger and runs derive (`tickets/status-of`), and
   :last-at, its workstream's newest entry. Skips malformed/missing files silently."
  []
  (let [projects-dir (fs/path (cstate/nido-root) "projects")]
    (if-not (fs/exists? projects-dir)
      []
      (vec
        (for [proj  (fs/list-dir projects-dir)
              :when (fs/directory? proj)
              :let  [tdir (fs/path proj "tickets")]
              :when (fs/exists? tdir)
              :let  [project (keyword (str (fs/file-name proj)))
                     wss     (by-ref project)]
              tk    (fs/list-dir tdir)
              :when (fs/directory? tk)
              :let  [m (try (io/read-edn (str (fs/path tk "meta.edn")))
                            (catch Exception _ nil))]
              :when m
              :let  [w (wss (str (fs/file-name tk)))]]
          (assoc m :project project
                   :status  (tickets/status-of project w)
                   :last-at (some-> w :entries last :at)))))))

(defn ^{:malli/schema [:=> [:cat :Ticket] :keyword]}
  classify
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

(defn ^{:malli/schema [:=> [:cat :Ticket] [:maybe :string]]}
  last-activity
  "Best timestamp for ordering: its workstream's newest entry's :at (`read-all-tickets`)."
  [m]
  (:last-at m))

(defn ^{:malli/schema [:=> [:cat [:vector :Ticket]] :map]}
  grouped-tickets
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

(defn ^{:malli/schema [:=> [:cat :Ticket] :string]}
  format-row
  "Display string for a ticket: `BR-#### · <title>  [status]`."
  [{:keys [br-id status title]}]
  (format "%-9s · %s  [%s]"
          (or br-id "?")
          (truncate (str title) title-max)
          (name (or status :?))))
