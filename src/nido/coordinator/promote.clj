(ns nido.coordinator.promote
  "The shared promote action: gate a triaged ticket, mark it :planning, and
   enqueue a direct-target envelope for the project's :plan-bug trigger. Used by
   the `bb nido:ticket:promote` task and — via the identical envelope shape — by
   the triage skill's in-chat `promote` command. See spec §The promote gesture."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.spawn :as spawn]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.triggers :as triggers]
   [nido.coordinator.workstream :as ws]
   [nido.coordinator.workstreams-view :as wsv]
   [nido.github.client :as gh]))

(defn- latest-entry-of-kind
  "Absolute path to the most recent workstream-ledger entry of `kind` (workstream
   resolved by the BR-#### ref), or nil."
  [project br-id kind]
  (when-let [w (ws/find-by-ref-id project br-id)]
    (when-let [e (->> (:entries w) (filter #(= kind (:kind %))) last)]
      (str (fs/path (cstate/workstream-dir project (:id w)) (:file e))))))

(defn promote!
  "Attempt to promote a ticket to a planning Run.
   Returns {:decision <kw>}; on :promote also {:queued <envelope-path>}.
   Side effects on :promote only: sets status :planning, enqueues the envelope."
  [project br-id]
  (let [decision (tickets/promote-decision project br-id)]
    (if (not= :promote decision)
      {:decision decision}
      (let [m       (tickets/read-meta project br-id)
            payload {:id             br-id
                     :notion-page-id (:notion-page-id m)
                     :url            (:url m)
                     :title          (:title m)
                     :report-path    (latest-entry-of-kind project br-id :triage)}]
        (cstate/ensure-dirs!)
        (tickets/set-status! project br-id :planning)
        {:decision :promote
         :queued   (queue/enqueue! {:target  {:project (keyword (name project)) :trigger :plan-bug}
                                    :payload payload})}))))

(defn- notion-br-id [w]
  (some #(when (= :notion (:adapter %)) (:id %)) (:external-refs w)))

(defn- github-issue-ref [w]
  (some #(when (= :github-issue (:adapter %)) %) (:external-refs w)))

(defn- parse-issue-id
  "\"owner/repo#42\" → {:repo \"owner/repo\" :number 42}."
  [id]
  (let [i (.lastIndexOf ^String id "#")]
    {:repo (subs id 0 i) :number (parse-long (subs id (inc i)))}))

(defn- promote-github! [project w]
  (if (not= :ready (:stage w))
    {:decision :skip-active}
    (let [ref (github-issue-ref w)
          {:keys [repo number]} (parse-issue-id (:id ref))
          res (gh/view-issue repo number)]
      (if (:error res)
        {:decision :gh-error}
        (let [{:keys [title url body]} (:issue res)
              payload {:id (:id ref) :url url :title title :body body}]
          (cstate/ensure-dirs!)
          (ws/advance-stage! project (:id w) :in-progress)
          {:decision :promote
           :queued   (queue/enqueue! {:target  {:project (keyword (name project)) :trigger :plan-github-issue}
                                      :payload payload})})))))

(defn start-triage!
  "Promote a queued :incoming workstream by running its deferred triage skill.
   Reads the stored :intake {:trigger :payload}, loads that trigger, force-spawns
   the triage session onto THIS workstream (deduped on its ref), and advances the
   stage :incoming → :triaging. Returns:
     {:decision :triaging}        — triage started
     {:decision :skip-not-inbox}  — the workstream has left the queue
     {:decision :skip-no-trigger} — its originating trigger is gone from triggers.edn"
  [project ws-id]
  (let [w (ws/read-ws project ws-id)]
    (if (not= :incoming (:stage w))
      {:decision :skip-not-inbox}
      (let [{:keys [trigger payload]} (:intake w)
            t (triggers/find-by-name (triggers/load-for-project project) trigger)]
        (if (nil? t)
          {:decision :skip-no-trigger}
          (let [routed {:project         project
                        :trigger         t
                        :payload         payload
                        :priority        (or (:priority t) 0)
                        :session-profile (:session-profile t)
                        :uncapped?       (boolean (:uncapped? t))}]
            (cstate/ensure-dirs!)
            (spawn/spawn-and-submit! routed {:fired-at (clock/now-iso)
                                             :fired-by "promote"})
            (ws/advance-stage! project ws-id :triaging)
            {:decision :triaging}))))))

(defn promote-workstream!
  "Promote a workstream by id, dispatching on its source. :notion → the existing
   triage-gated :plan-bug leg; :github → fetch the issue body + provision the
   issue-impl leg; :slack at :incoming → run the deferred triage skill (start-triage!);
   anything else isn't promotable. Returns {:decision}."
  [project ws-id]
  (if-let [w (ws/read-ws project ws-id)]
    (case (wsv/ws-source w)
      :notion (promote! project (notion-br-id w))
      :github (promote-github! project w)
      :slack  (start-triage! project ws-id)
      {:decision :skip-not-promotable})
    {:decision :skip-not-promotable}))
