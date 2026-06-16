(ns nido.coordinator.promote
  "The shared promote action: gate a triaged ticket, mark it :planning, and
   enqueue a direct-target envelope for the project's :plan-bug trigger. Used by
   the `bb nido:ticket:promote` task and — via the identical envelope shape — by
   the triage skill's in-chat `promote` command. See spec §The promote gesture."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as ws]
   [nido.coordinator.workstreams-view :as wsv]
   [nido.github.client :as gh]))

(defn- latest-entry-of-kind
  "Absolute path to the most recent ledger entry of `kind`, or nil."
  [project br-id kind]
  (when-let [m (tickets/read-meta project br-id)]
    (when-let [e (->> (:entries m) (filter #(= kind (:kind %))) last)]
      (str (fs/path (tickets/ticket-dir project br-id) (:file e))))))

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
         :queued   (queue/enqueue! {:target  {:project project :trigger :plan-bug}
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
           :queued   (queue/enqueue! {:target  {:project project :trigger :plan-github-issue}
                                      :payload payload})})))))

(defn promote-workstream!
  "Promote a workstream by id, dispatching on its source. :notion → the existing
   triage-gated :plan-bug leg; :github → fetch the issue body + provision the
   issue-impl leg; anything else (scratch, slack) isn't promotable. Returns {:decision}."
  [project ws-id]
  (if-let [w (ws/read-ws project ws-id)]
    (case (wsv/ws-source w)
      :notion (promote! project (notion-br-id w))
      :github (promote-github! project w)
      {:decision :skip-not-promotable})
    {:decision :skip-not-promotable}))
