(ns nido.coordinator.lane.intake
  "Queue-mode intake: turn a routed queue-mode fire into a passive :incoming
   workstream (no session), and expire stale incoming entries. See spec
   docs/superpowers/specs/2026-06-19-slack-human-gated-queue-design.md."
  (:require
   [nido.coordinator.lane.facets :as facets]
   [nido.coordinator.lane.spawn :as spawn]
   [nido.coordinator.record.workstream :as ws]
   [nido.notion.views :as views]))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  enqueue-inbox!
  "Create a session-less :incoming workstream for a queue-mode fire, deduped on the
   payload's external ref, and record the :incoming stage on its ledger — the pen is a stage
   nido chose, and the board reads it from that entry like any other. Stores the originating
   trigger name + the raw event payload under :intake so promote can later reconstruct the
   triage fire. Returns the workstream (existing or freshly created).

   An existing one still stored in the pen with no stage entry is one whose append died after the
   mint; the refire is the only writer that would ever complete it, so it does."
  [{:keys [project payload trigger]}]
  (let [ref      (spawn/external-ref payload)
        existing (when ref (ws/find-by-ref project (:adapter ref) (:id ref)))
        pen!     #(ws/append-entry! project % {:kind :stage-set}
                                    (pr-str {:format :stage-set :stage :incoming :by :nido}))]
    (if existing
      (if (and (= :incoming (:stage existing))
               (not-any? #(= :stage-set (:kind %)) (:entries existing)))
        (do (pen! (:id existing)) (ws/read-ws project (:id existing)))
        existing)
      (let [w (ws/create! project
                          {:stage         :incoming
                           :external-refs (if ref [ref] [])
                           :facets        (facets/select-facets (views/facet-properties project) payload)
                           :intake        {:trigger (:name trigger) :payload payload}})]
        (pen! (:id w))
        (ws/read-ws project (:id w))))))

(defn- iso-age-ms
  "Milliseconds between ISO-8601 instant `iso` and `now-ms` (epoch millis).
   A nil/blank :created-at reads as age 0 (never expires)."
  [iso now-ms]
  (if iso
    (- now-ms (.toEpochMilli (java.time.Instant/parse iso)))
    0))

(defn ^{:malli/schema [:=> [:cat :ProjectName :int :int] :any]}
  expire-stale!
  "Close (:dropped) every still-open :incoming workstream in `project` whose
   :created-at is older than `max-age-ms`. Promoted workstreams have left :incoming
   and closed ones are skipped. `now-ms` is epoch millis (injected for tests).
   Returns the vector of expired ws-ids."
  [project max-age-ms now-ms]
  (->> (ws/list-ids project)
       (keep #(ws/read-ws project %))
       (filter (fn [w] (and (= :incoming (:stage w))
                            (nil? (:closed w))
                            (>= (iso-age-ms (:created-at w) now-ms) max-age-ms))))
       (mapv (fn [w] (ws/close! project (:id w) :dropped) (:id w)))))
