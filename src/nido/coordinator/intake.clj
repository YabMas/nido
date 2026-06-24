(ns nido.coordinator.intake
  "Queue-mode intake: turn a routed queue-mode fire into a passive :inbox
   workstream (no session), and expire stale inbox entries. See spec
   docs/superpowers/specs/2026-06-19-slack-human-gated-queue-design.md."
  (:require
   [nido.coordinator.facets :as facets]
   [nido.coordinator.spawn :as spawn]
   [nido.coordinator.workstream :as ws]
   [nido.notion.views :as views]))

(defn enqueue-inbox!
  "Create a session-less :inbox workstream for a queue-mode fire, deduped on the
   payload's external ref. Stores the originating trigger name + the raw event
   payload under :intake so promote can later reconstruct the triage fire.
   Returns the workstream (existing or freshly created)."
  [{:keys [project payload trigger]}]
  (let [ref      (spawn/external-ref payload)
        existing (when ref (ws/find-by-ref project (:adapter ref) (:id ref)))]
    (or existing
        (ws/create! project
                    {:stage         :inbox
                     :external-refs (if ref [ref] [])
                     :facets        (facets/select-facets (views/facet-properties project) payload)
                     :intake        {:trigger (:name trigger) :payload payload}}))))

(defn- iso-age-ms
  "Milliseconds between ISO-8601 instant `iso` and `now-ms` (epoch millis).
   A nil/blank :created-at reads as age 0 (never expires)."
  [iso now-ms]
  (if iso
    (- now-ms (.toEpochMilli (java.time.Instant/parse iso)))
    0))

(defn expire-stale!
  "Close (:dropped) every still-open :inbox workstream in `project` whose
   :created-at is older than `max-age-ms`. Promoted workstreams have left :inbox
   and closed ones are skipped. `now-ms` is epoch millis (injected for tests).
   Returns the vector of expired ws-ids."
  [project max-age-ms now-ms]
  (->> (ws/list-ids project)
       (keep #(ws/read-ws project %))
       (filter (fn [w] (and (= :inbox (:stage w))
                            (nil? (:closed w))
                            (>= (iso-age-ms (:created-at w) now-ms) max-age-ms))))
       (mapv (fn [w] (ws/close! project (:id w) :dropped) (:id w)))))
