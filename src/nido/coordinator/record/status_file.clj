(ns nido.coordinator.record.status-file
  "Read the skill-written `<run-dir>/_run-status.edn` and map phases to
   Run lifecycle states. See spec §Skills as auto-trigger targets and
   §Agent launch."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(defn ^{:malli/schema [:=> [:cat :RunId] [:maybe :RunStatus]]}
  read-status
  "Returns the status map or nil if absent / malformed."
  [run-id]
  (let [p (cstate/run-status-path run-id)]
    (when (fs/exists? p)
      (try (io/read-edn p)
           (catch Exception _ nil)))))

(defn ^{:malli/schema [:=> [:cat :keyword] [:maybe :keyword]]}
  phase->state
  "Map a skill-reported phase to a Run state. nil for ongoing phases
   (the daemon should not transition the Run for those)."
  [phase]
  (case phase
    :awaiting-input :awaiting-review
    :complete       :done
    :error          :failed
    nil))

(defn ^{:malli/schema [:=> [:cat [:maybe :RunStatus]] :keyword]}
  derive-state-after-exit
  "Given a status map (or nil), what state should the Run move to
   after a clean agent exit? Absent status → :done."
  [status]
  (or (phase->state (:phase status)) :done))
