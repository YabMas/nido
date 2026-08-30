(ns nido.coordinator.lane.github-issue-intake
  "Coordinator housekeeping: poll a project's GitHub repo for OPEN issues
   assigned to you and reconcile a workstream per issue — born at stage :ready,
   NO session spawn (you promote it manually — Phase 4's workstream-level
   promote). Snapshot-free: idempotent upsert by the :github-issue ref, plus a
   reverse pass that DROPs the queue entry when an issue is no longer assigned AND
   its workstream is still unpromoted (stage :ready, no sessions). Unlike the
   merge poller the FIRST poll surfaces the whole backlog — nothing auto-fires, so
   the backlog IS the queue.

   Half-open breaker mirrors github-merge: an :auth failure (or >=3 consecutive
   failures) opens it; while open the poll is skipped until breaker-cooldown-s,
   then one probe runs (success clears, failure re-arms)."
  (:require
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.source.state :as sstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.github.client :as gh]))

(defn- warn [msg]
  (binding [*err* *err*]
    (.println ^java.io.PrintWriter *err* (str "WARN: " msg))))

(defn- state-key [project] (str "github-issues-" (name project)))

(def ^:private breaker-cooldown-s (* 30 60))

(defn- cooldown-elapsed? [state]
  (if-let [opened (:breaker-opened-at state)]
    (try
      (>= (.toSeconds (java.time.Duration/between
                        (java.time.Instant/parse opened)
                        (java.time.Instant/parse (clock/now-iso))))
          breaker-cooldown-s)
      (catch Exception _ true))
    true))

(defn- issue-id [repo number] (str repo "#" number))

(defn- issue-ref-id [w]
  (some #(when (= :github-issue (:adapter %)) (:id %)) (:external-refs w)))

(defn- unpromoted?
  "A github-issue workstream still in the queue: stage :ready and no sessions."
  [project w]
  (and (= :ready (:stage w))
       (empty? (session/list-sessions project (:id w)))))

(defn- upsert-issue!
  "Ensure a :ready workstream exists for one assigned issue. Idempotent via
   find-by-ref on the :github-issue adapter."
  [project repo {:keys [number url title]}]
  (let [id (issue-id repo number)]
    (when-not (ws/find-by-ref project :github-issue id)
      (ws/create! project {:stage :ready
                           :external-refs [(cond-> {:adapter :github-issue :id id}
                                             url   (assoc :url url)
                                             title (assoc :title title))]}))))

(defn- reverse-reconcile!
  "Delete queue entries whose issue is no longer in `assigned-ids` AND that are
   still unpromoted. delete! (not close!) so a re-assigned issue re-creates a
   fresh :ready entry next poll. Promoted workstreams are left untouched."
  [project assigned-ids]
  (doseq [ws-id (ws/list-ids project)
          :let  [w  (ws/read-ws project ws-id)
                 id (some-> w issue-ref-id)]
          :when (and id (not (contains? assigned-ids id)) (unpromoted? project w))]
    (ws/delete! project ws-id)))

(defn poll-and-reconcile!
  "One reconcile poll for a project. Reverse-reconcile runs only on a SUCCESSFUL
   poll (an error never drops the queue). Returns nil."
  [project {:keys [repo issues]}]
  (let [k        (state-key project)
        prior    (sstate/read-state k)
        assignee (or (:assignee issues) "@me")]
    (when-not (and (= :open (:breaker prior)) (not (cooldown-elapsed? prior)))
      (let [res (gh/list-assigned-issues repo assignee)]
        (if (:error res)
          (let [auth? (= :auth (:error res))
                fails (inc (or (:consecutive-failures prior) 0))
                open? (or auth? (>= fails 3) (= :open (:breaker prior)))]
            (sstate/write-state! k (merge (or prior {:type :github-issues :project project})
                                          (cond-> {:consecutive-failures fails
                                                   :breaker (if open? :open (:breaker prior))}
                                            open? (assoc :breaker-opened-at (clock/now-iso)))))
            (warn (str "github-issues: gh poll failed for " project " — " (:error res))))
          (let [assigned-ids (into #{} (map #(issue-id repo (:number %))) (:issues res))]
            (doseq [iss (:issues res)] (upsert-issue! project repo iss))
            (reverse-reconcile! project assigned-ids)
            (sstate/write-state! k {:type :github-issues :project project
                                    :consecutive-failures 0 :breaker nil})))))
    nil))
