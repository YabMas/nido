(ns nido.coordinator.promote
  "The shared promote action: gate a triaged ticket, mark it :planning, and
   enqueue a direct-target envelope for the project's :plan-bug trigger. Used by
   the `bb nido:ticket:promote` task and — via the identical envelope shape — by
   the triage skill's in-chat `promote` command. See spec §The promote gesture."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.queue :as queue]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]))

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
