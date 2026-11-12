(ns nido.coordinator.lane.promote
  "Whether a ticket may be started, and starting it: gate a triaged Notion ticket, or a GitHub
   issue not yet started, and hand it to `lane.start/start-ticket!`, which activates its
   workstream and brings its impl session up at once — no Run, no trigger. Used by the
   `bb nido:ticket:promote` task and the surfaces' in-progress gesture."
  (:require
   [nido.coordinator.lane.spawn :as spawn]
   [nido.coordinator.lane.start :as start]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.view.workstreams :as wsv]))

(defn- started
  "promote's answer for `start-ticket!`'s: :promote with where it started, or the refusal."
  [{:keys [decision] :as r}]
  (if (= :started decision)
    (assoc (dissoc r :decision) :decision :promote)
    r))

(defn ^{:malli/schema [:=> [:cat :ProjectName :TicketId] :map]}
  promote!
  "Start a triaged ticket, or say why not.
   Returns {:decision <kw>}: on :promote also {:ws-id :session} — the workstream activated and its
   impl session brought up (`start/start-ticket!`); a gate refusal (`tickets/promote-decision`)
   or a start's refusal otherwise, with nothing written."
  [project br-id]
  (let [decision (tickets/promote-decision project br-id)]
    (if (not= :promote decision)
      {:decision decision}
      (let [m (tickets/read-meta project br-id)]
        (started (start/start-ticket! project {:ref {:adapter :notion :id br-id
                                                     :page-id (:notion-page-id m)
                                                     :url (:url m) :title (:title m)}}))))))

(defn- notion-br-id [w]
  (some #(when (= :notion (:adapter %)) (:id %)) (:external-refs w)))

(defn- promote-github!
  "Start a GitHub issue's workstream unless it is closed or was started before."
  [project w]
  (if (or (ws/closure w) (standing/ever-chose? project (:id w) :in-progress))
    {:decision :skip-active}
    (started (start/start-ticket! project {:ws-id (:id w)}))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  start-triage!
  "Promote a queued :incoming workstream by running its deferred triage skill.
   Reads the stored :intake {:trigger :payload}, loads that trigger, force-spawns
   the triage session onto THIS workstream (deduped on its ref), and advances the
   stage :incoming → :triaging. Returns:
     {:decision :triaging}        — triage started
     {:decision :skip-not-inbox}  — the workstream has left the queue
     {:decision :skip-no-trigger} — its originating trigger is gone from triggers.edn"
  [project ws-id]
  (let [w (ws/read-ws project ws-id)]
    (if (not= :incoming (standing/chosen-stage project ws-id w))
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

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :map]}
  promote-workstream!
  "Promote a workstream by id, dispatching on its source. :notion → the triage-gated start
   (promote!); :github → the issue's start; :slack at :incoming → run the deferred triage skill
   (start-triage!); anything else isn't promotable. Returns {:decision}."
  [project ws-id]
  (if-let [w (ws/read-ws project ws-id)]
    (case (wsv/ws-source w)
      :notion (promote! project (notion-br-id w))
      :github (promote-github! project w)
      :slack  (start-triage! project ws-id)
      {:decision :skip-not-promotable})
    {:decision :skip-not-promotable}))
