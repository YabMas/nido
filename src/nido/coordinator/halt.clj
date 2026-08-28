(ns nido.coordinator.halt
  "Halted-state file: presence of ~/.nido/coordinator/halted.edn means
   the coordinator has paused. The daemon checks `halted?` each tick
   before draining the queue. See spec §Safety brakes / Kill switch."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]))

(defn halted? []
  (fs/exists? (cstate/halted-path)))

(defn read-halt-info
  "Map with :source (:user | :auto), :reason (optional kw), :note (optional str),
   :halted-at (iso). Returns nil when not halted."
  []
  (when (halted?)
    (try (io/read-edn (cstate/halted-path))
         (catch Exception _ nil))))

(defn halt!
  "Write halted.edn with the given metadata. Always stamps :halted-at."
  [info]
  (io/write-edn! (cstate/halted-path)
                 (assoc info :halted-at (clock/now-iso))))

(defn resume!
  "Remove halted.edn. Idempotent — no-op if absent."
  []
  (when (halted?)
    (fs/delete (cstate/halted-path))))
