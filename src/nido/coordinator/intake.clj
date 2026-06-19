(ns nido.coordinator.intake
  "Queue-mode intake: turn a routed queue-mode fire into a passive :inbox
   workstream (no session), and expire stale inbox entries. See spec
   docs/superpowers/specs/2026-06-19-slack-human-gated-queue-design.md."
  (:require
   [nido.coordinator.spawn :as spawn]
   [nido.coordinator.workstream :as ws]))

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
                     :intake        {:trigger (:name trigger) :payload payload}}))))
